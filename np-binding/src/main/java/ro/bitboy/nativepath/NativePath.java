package ro.bitboy.nativepath;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The short way to make secret-carrying requests. Every call loads its secrets, sends, hands back
 * the result and wipes everything it loaded, so there is no try-with-resources chain to get wrong:
 *
 * <pre>{@code
 * try (NativePath nativepath = NativePath.create()) {                       // its own secret-mode client
 *     int status = nativepath.post(url).bearer(SecretSource.keychain("billing", "prod"))
 *             .json(Map.of("amount", 1200, "card", cardSource)).send();
 *
 *     try (NativeSecret token = nativepath.post(tokenUrl).basic("billing", passwordSource)
 *             .form(Map.of("grant_type", "client_credentials"))
 *             .fetchField("access_token")) { ... }
 * }
 * }</pre>
 *
 * Wherever a secret is expected you can pass a {@link SecretSource} (loaded at send time and closed
 * afterwards) or a {@link NativeSecret} you own (borrowed, never closed by the facade). Anything
 * returned as a {@link NativeSecret} or {@link JsonResult} belongs to the caller.
 *
 * <p>Always in secret mode: response data never becomes a Java string or array unless you copy it.
 */
public final class NativePath implements AutoCloseable {

    /** Reads the response stream; it is closed when the handler returns. */
    @FunctionalInterface
    public interface ResponseHandler<T> {
        T handle(NativePathStream response) throws IOException;
    }

    @FunctionalInterface
    public interface SecretFunction<T> {
        T apply(NativeSecret secret) throws IOException;
    }

    /** Maps the public part of a JSON document; with Jackson: {@code objectMapper::readValue}. */
    public interface JsonMapper {
        <T> T read(byte[] json, Class<T> type) throws IOException;
    }

    private static final long DEFAULT_MAX_BYTES = 1L << 20;

    private final NativePathHttpClient client;
    private final boolean ownsClient;
    private final Function<String, SecretSource> sources;
    private final JsonMapper jsonMapper;

    private NativePath(Builder b) {
        if (b.client != null) {
            if (!b.client.isSecretMode()) {
                throw new IllegalArgumentException("NativePath needs a client built with secretMode(true)");
            }
            this.client = b.client;
            this.ownsClient = false;
        } else {
            this.client = NativePathHttpClient.builder().secretMode(true).trustSelfSigned(b.trustSelfSigned).build();
            this.ownsClient = true;
        }
        this.sources = b.sources;
        this.jsonMapper = b.jsonMapper;
    }

    /** An nativepath with its own secret-mode client, closed with the nativepath. */
    public static NativePath create() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder(null);
    }

    /** Uses an existing secret-mode client; the nativepath borrows it and does not close it. */
    public static Builder builder(NativePathHttpClient client) {
        return new Builder(client);
    }

    // ---------------------------------------------------------------- requests

    public Call get(String url) { return new Call("GET", url); }
    public Call post(String url) { return new Call("POST", url); }
    public Call put(String url) { return new Call("PUT", url); }
    public Call patch(String url) { return new Call("PATCH", url); }
    public Call delete(String url) { return new Call("DELETE", url); }
    public Call request(String method, String url) { return new Call(method, url); }

    // ---------------------------------------------------------------- secrets

    /** A named source (configured with {@link Builder#sources}); holds no value. */
    public SecretSource secret(String name) {
        if (sources == null) throw new IllegalStateException("no named secret sources configured");
        SecretSource s = sources.apply(name);
        if (s == null) throw new IllegalArgumentException("no secret source '" + name + "'");
        return s;
    }

    /** Loads a named secret; the caller closes it. */
    public NativeSecret load(String name) throws IOException {
        return secret(name).load();
    }

    /** Loads the secret, runs {@code fn} and wipes the secret, whatever happens. */
    public <T> T withSecret(SecretSource source, SecretFunction<T> fn) throws IOException {
        try (NativeSecret s = source.load()) {
            return fn.apply(s);
        }
    }

    public <T> T withSecret(String name, SecretFunction<T> fn) throws IOException {
        return withSecret(secret(name), fn);
    }

    /** A vault source builder bound to this nativepath's client. */
    public VaultSecretSource.Builder vault() {
        return VaultSecretSource.builder(client);
    }

    public NativePathHttpClient client() {
        return client;
    }

    @Override
    public void close() {
        if (ownsClient) client.close();
    }

    // ---------------------------------------------------------------- call

    /** Produces a native secret at send time, registering anything it creates in {@code owned}. */
    private interface Maker {
        NativeSecret make(Deque<AutoCloseable> owned) throws IOException;
    }

    private interface Body {
        void apply(NativePathRequest req, Deque<AutoCloseable> owned) throws IOException;
    }

    private record Header(String name, String prefix, String value, Maker secret) {}

    /**
     * One request under construction. Holds sources and borrowed secrets only; secrets are loaded
     * when a terminal method ({@code send}, {@code fetch...}) runs, so a call can be sent again.
     */
    public final class Call {
        private final String method;
        private final String url;
        private final List<Header> headers = new ArrayList<>();
        private Body body;
        private String contentType;
        private long maxBytes = DEFAULT_MAX_BYTES;

        private Call(String method, String url) {
            this.method = method;
            this.url = url;
        }

        /** A non-secret header. */
        public Call header(String name, String value) {
            headers.add(new Header(name, null, value, null));
            return this;
        }

        public Call secretHeader(String name, SecretSource value) { return secretHeader(name, null, value); }
        public Call secretHeader(String name, NativeSecret value) { return secretHeader(name, null, value); }

        /** A sensitive header made of a plain prefix and a secret. */
        public Call secretHeader(String name, String prefix, SecretSource value) {
            headers.add(new Header(name, prefix, null, ref(value)));
            return this;
        }

        public Call secretHeader(String name, String prefix, NativeSecret value) {
            headers.add(new Header(name, prefix, null, ref(value)));
            return this;
        }

        /** {@code Authorization: Bearer <token>}. */
        public Call bearer(SecretSource token) { return secretHeader("Authorization", "Bearer ", token); }
        public Call bearer(NativeSecret token) { return secretHeader("Authorization", "Bearer ", token); }

        /** An API key header, for example {@code apiKey("X-Api-Key", source)}. */
        public Call apiKey(String header, SecretSource key) { return secretHeader(header, key); }
        public Call apiKey(String header, NativeSecret key) { return secretHeader(header, key); }

        /** {@code Authorization: Basic base64(user:password)}, encoded natively. */
        public Call basic(String user, SecretSource password) { return basic(user, null, ref(password)); }
        public Call basic(String user, NativeSecret password) { return basic(user, null, ref(password)); }

        /** Basic credentials where the user name is secret too (for example an OAuth client id). */
        public Call basic(SecretSource user, SecretSource password) { return basic(null, ref(user), ref(password)); }

        private Call basic(String user, Maker secretUser, Maker password) {
            headers.add(new Header("Authorization", "Basic ", null, owned -> {
                SecretBody.Builder pair = SecretBody.builder();
                if (secretUser != null) pair.raw(secretUser.make(owned)).text(":");
                else pair.text(user + ":");
                NativeSecret plain = keep(owned, pair.raw(password.make(owned)).build());
                return keep(owned, SecretBody.builder().base64(plain).build());
            }));
            return this;
        }

        /**
         * A JSON body from a map. Values may be strings, numbers, booleans, null, nested maps and
         * lists (public), or {@link SecretSource} / {@link NativeSecret} (written as JSON strings,
         * escaped natively). Use a {@code LinkedHashMap} to keep the field order.
         */
        public Call json(Map<String, ?> fields) {
            contentType = "application/json";
            body = (req, owned) -> {
                SecretBody.Builder b = SecretBody.builder();
                boolean[] secret = { false };
                writeJson(fields, b, owned, secret);
                req.nativeBody(keep(owned, b.build()), secret[0]);
            };
            return this;
        }

        /** An {@code application/x-www-form-urlencoded} body; secret values are percent-encoded natively. */
        public Call form(Map<String, ?> fields) {
            contentType = "application/x-www-form-urlencoded";
            body = (req, owned) -> {
                SecretBody.Builder b = SecretBody.builder();
                boolean secret = false;
                Iterator<? extends Map.Entry<String, ?>> it = fields.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, ?> e = it.next();
                    b.text(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=");
                    if (isSecret(e.getValue())) {
                        b.urlEncoded(resolve(e.getValue(), owned));
                        secret = true;
                    } else {
                        b.text(URLEncoder.encode(String.valueOf(e.getValue()), StandardCharsets.UTF_8));
                    }
                    if (it.hasNext()) b.text("&");
                }
                req.nativeBody(keep(owned, b.build()), secret);
            };
            return this;
        }

        /** A body that is itself secret (for example built with {@link SecretBody}); borrowed, not closed. */
        public Call secretBody(NativeSecret value, String contentType) {
            this.contentType = contentType;
            body = (req, owned) -> req.secretBody(value);
            return this;
        }

        /** A non-secret text body. */
        public Call body(String text, String contentType) {
            this.contentType = contentType;
            body = (req, owned) -> req.body(text.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        /** Largest body the {@code fetch...} methods accept (default 1 MB). */
        public Call maxBytes(long maxBytes) {
            this.maxBytes = maxBytes;
            return this;
        }

        // ------------------------------------------------------------ terminals

        /** Sends the request and runs {@code handler} on the response; everything loaded is wiped afterwards. */
        public <T> T send(ResponseHandler<T> handler) throws IOException {
            Deque<AutoCloseable> owned = new ArrayDeque<>();
            try (NativePathRequest req = NativePathRequest.of(method, url)) {
                boolean typed = false;
                for (Header h : headers) {
                    if (h.secret() == null) req.header(h.name(), h.value());
                    else req.secretHeader(h.name(), h.prefix(), h.secret().make(owned));
                    typed |= h.name().equalsIgnoreCase("Content-Type");
                }
                if (body != null) {
                    if (!typed && contentType != null) req.header("Content-Type", contentType);
                    body.apply(req, owned);
                }
                try (NativePathStream s = open(req)) {
                    return handler.handle(s);
                }
            } finally {
                while (!owned.isEmpty()) {
                    try {
                        owned.pop().close();
                    } catch (Exception ignored) {
                        // NativeSecret.close does not throw
                    }
                }
            }
        }

        /** Sends the request and returns the status; the body is discarded unread. */
        public int send() throws IOException {
            return send(NativePathStream::statusCode);
        }

        /** The whole body as a native secret (caller closes). Throws {@link NativePathHttpException} on non-2xx. */
        public NativeSecret fetchSecret() throws IOException {
            return send(s -> {
                checkOk(s);
                return read(s);
            });
        }

        /**
         * One string of a JSON response, found by a path of keys (for example
         * {@code fetchField("data", "access_token")}), as a native secret the caller closes.
         */
        public NativeSecret fetchField(String... path) throws IOException {
            return send(s -> {
                checkOk(s);
                try (NativeSecret body = read(s)) {
                    return SecretJson.extractAt(body, path);
                } catch (IllegalArgumentException e) {
                    throw new IOException("unexpected response from " + method + " " + url + ": " + e.getMessage(), e);
                }
            });
        }

        /**
         * A JSON response mapped to {@code type}, with the named top-level string fields kept as
         * native secrets (they arrive empty in the object). Needs a {@link JsonMapper}.
         */
        public <T> JsonResult<T> fetchJson(Class<T> type, String... secretFields) throws IOException {
            if (jsonMapper == null) {
                throw new IllegalStateException("no JsonMapper configured: NativePath.builder().jsonMapper(objectMapper::readValue)");
            }
            return send(s -> {
                checkOk(s);
                try (NativeSecret body = read(s)) {
                    SecretJson.Extracted parts;
                    try {
                        parts = SecretJson.extract(body, Set.of(secretFields));
                    } catch (IllegalArgumentException | UnsupportedOperationException e) {
                        throw new IOException("unexpected response from " + method + " " + url + ": " + e.getMessage(), e);
                    }
                    try {
                        return new JsonResult<>(s.statusCode(), jsonMapper.read(parts.publicJson(), type), parts.secrets());
                    } catch (IOException | RuntimeException e) {
                        parts.close();
                        throw e;
                    }
                }
            });
        }

        private NativePathStream open(NativePathRequest req) throws IOException {
            try {
                return client.getStream(req);
            } catch (RuntimeException e) {
                throw new IOException(method + " " + url + " failed: " + e.getMessage(), e);
            }
        }

        private void checkOk(NativePathStream s) throws NativePathHttpException {
            if (s.statusCode() < 200 || s.statusCode() > 299) throw new NativePathHttpException(s.statusCode(), method, url);
        }

        private NativeSecret read(NativePathStream s) throws IOException {
            try {
                return s.readToSecret(maxBytes);
            } catch (IllegalStateException e) {
                throw new IOException(method + " " + url + ": " + e.getMessage(), e);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isSecret(Object v) {
        return v instanceof NativeSecret || v instanceof SecretSource;
    }

    private static Maker ref(Object secret) {
        if (!isSecret(secret)) throw new IllegalArgumentException("expected a SecretSource or NativeSecret");
        return owned -> resolve(secret, owned);
    }

    /** A borrowed {@link NativeSecret} as is, or a {@link SecretSource} loaded now and registered for wiping. */
    private static NativeSecret resolve(Object secret, Deque<AutoCloseable> owned) throws IOException {
        if (secret instanceof NativeSecret n) return n;
        return keep(owned, ((SecretSource) secret).load());
    }

    private static NativeSecret keep(Deque<AutoCloseable> owned, NativeSecret s) {
        owned.push(s);
        return s;
    }

    private static void writeJson(Object v, SecretBody.Builder b, Deque<AutoCloseable> owned, boolean[] secret) throws IOException {
        if (isSecret(v)) {
            b.text("\"").jsonString(resolve(v, owned)).text("\"");
            secret[0] = true;
        } else if (v == null) {
            b.text("null");
        } else if (v instanceof String s) {
            b.text(quote(s));
        } else if (v instanceof Boolean) {
            b.text(v.toString());
        } else if (v instanceof Number n) {
            if ((n instanceof Double d && !Double.isFinite(d)) || (n instanceof Float f && !Float.isFinite(f))) {
                throw new IllegalArgumentException("JSON cannot represent " + n);
            }
            b.text(n.toString());
        } else if (v instanceof Map<?, ?> m) {
            b.text("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                b.text((first ? "" : ",") + quote(String.valueOf(e.getKey())) + ":");
                writeJson(e.getValue(), b, owned, secret);
                first = false;
            }
            b.text("}");
        } else if (v instanceof Iterable<?> list) {
            b.text("[");
            boolean first = true;
            for (Object o : list) {
                if (!first) b.text(",");
                writeJson(o, b, owned, secret);
                first = false;
            }
            b.text("]");
        } else {
            throw new IllegalArgumentException("unsupported JSON value type " + v.getClass().getName());
        }
    }

    /** JSON-quotes a public string. */
    private static String quote(String s) {
        StringBuilder q = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') q.append('\\').append(c);
            else if (c < 0x20) q.append(String.format("\\u%04x", (int) c));
            else q.append(c);
        }
        return q.append('"').toString();
    }

    public static final class Builder {
        private final NativePathHttpClient client;
        private Function<String, SecretSource> sources;
        private JsonMapper jsonMapper;
        private boolean trustSelfSigned;

        private Builder(NativePathHttpClient client) {
            this.client = client;
        }

        /** Named sources for {@link NativePath#secret(String)}, for example {@code secretSources::get}. */
        public Builder sources(Function<String, SecretSource> sources) { this.sources = sources; return this; }

        public Builder sources(Map<String, SecretSource> sources) { this.sources = Map.copyOf(sources)::get; return this; }

        /** For {@link Call#fetchJson}; with Jackson: {@code objectMapper::readValue}. */
        public Builder jsonMapper(JsonMapper jsonMapper) { this.jsonMapper = jsonMapper; return this; }

        /** Only for the nativepath's own client (development only). */
        public Builder trustSelfSigned(boolean trust) { this.trustSelfSigned = trust; return this; }

        public NativePath build() {
            return new NativePath(this);
        }
    }
}
