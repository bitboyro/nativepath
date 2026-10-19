package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathRequest;
import ro.bitboy.nativepath.NativePathStream;
import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretJson;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Typed JSON responses with secret fields kept off the JVM heap.
 *
 * <pre>{@code
 * record Token(String token_type, long expires_in, String access_token) {}   // access_token arrives as ""
 *
 * try (SecretResult<Token> r = secrets.getJson(
 *         NativePathRequest.get(url).secretHeader("Authorization", "Basic ", clientCredentials),
 *         Token.class, "access_token")) {
 *     long ttl = r.value().expires_in();                 // ordinary heap data
 *     NativeSecret token = r.secret("access_token");     // native memory only
 *     other.secretHeader("Authorization", "Bearer ", token);
 * }
 * }</pre>
 *
 * The body is read into native memory, the named string fields are copied native to native, and
 * only the remaining (public) JSON is turned into the target type by Jackson.
 */
public final class NativePathSecretClient {

    private static final long MAX_BODY = 1L << 20;   // secret documents are small; larger ones should stream

    private final NativePathHttpClient client;
    private final ObjectMapper mapper;

    public NativePathSecretClient(NativePathHttpClient client, ObjectMapper mapper) {
        if (!client.isSecretMode()) {
            throw new IllegalArgumentException("NativePathSecretClient needs a client built with secretMode(true)");
        }
        this.client = client;
        this.mapper = mapper;
    }

    public <T> SecretResult<T> getJson(NativePathRequest request, Class<T> type, String... secretFields) throws IOException {
        try (NativePathStream stream = client.getStream(request);
             NativeSecret body = stream.readToSecret(MAX_BODY)) {
            SecretJson.Extracted parts = SecretJson.extract(body, Set.of(secretFields));
            try {
                T value = mapper.readValue(parts.publicJson(), type);
                return new SecretResult<>(stream.statusCode(), value, parts.secrets());
            } catch (IOException | RuntimeException e) {
                parts.close();
                throw e;
            }
        }
    }

    /** A decoded response: public data as an object, secrets as native memory. Close to wipe the secrets. */
    public record SecretResult<T>(int status, T value, Map<String, NativeSecret> secrets) implements AutoCloseable {
        public NativeSecret secret(String field) {
            NativeSecret s = secrets.get(field);
            if (s == null) throw new IllegalArgumentException("no secret field '" + field + "' in the response");
            return s;
        }

        @Override
        public void close() {
            secrets.values().forEach(NativeSecret::close);
        }
    }
}
