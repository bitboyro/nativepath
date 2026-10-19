package ro.bitboy.nativepath;

import java.io.IOException;

/**
 * Reads one field of a HashiCorp Vault KV secret through NativePath itself, so neither the vault
 * token nor the value becomes a Java string:
 * <ul>
 *   <li>the token comes from another {@link SecretSource} (for example {@code ~/.vault-token} or the
 *       keychain) and is sent as a secret {@code X-Vault-Token} header on a connection of its own;</li>
 *   <li>the response is read into native memory and only the named field is copied out
 *       ({@link SecretJson#extractAt}); the rest of the document is wiped with the body.</li>
 * </ul>
 *
 * <pre>{@code
 * SecretSource dbPassword = VaultSecretSource.builder(secretModeClient)
 *         .address("https://vault.internal:8200")
 *         .path("billing/db")                      // GET /v1/secret/data/billing/db
 *         .field("password")
 *         .token(SecretSource.file(Path.of(System.getProperty("user.home"), ".vault-token")))
 *         .build();
 * try (NativeSecret pw = dbPassword.load()) { ... }
 * }</pre>
 *
 * Every {@link #load()} is a fresh request (no caching). Supports token authentication with KV
 * version 2 (default) or 1; other auth methods should obtain a token first and hand it over as a
 * {@code SecretSource}.
 */
public final class VaultSecretSource implements SecretSource {

    private static final long MAX_BODY = 1L << 20;

    private final NativePathHttpClient client;
    private final String url;
    private final String[] jsonPath;
    private final String namespace;
    private final SecretSource token;

    private VaultSecretSource(Builder b) {
        this.client = b.client;
        String address = b.address.replaceAll("/+$", "");
        String mount = trimSlashes(b.mount);
        String path = trimSlashes(b.path);
        if (b.kvVersion == 2) {
            this.url = address + "/v1/" + mount + "/data/" + path;
            this.jsonPath = new String[] { "data", "data", b.field };
        } else {
            this.url = address + "/v1/" + mount + "/" + path;
            this.jsonPath = new String[] { "data", b.field };
        }
        this.namespace = b.namespace;
        this.token = b.token;
    }

    /** {@code client} must be built with {@code secretMode(true)}; the source borrows it and does not close it. */
    public static Builder builder(NativePathHttpClient client) {
        return new Builder(client);
    }

    @Override
    public NativeSecret load() throws IOException {
        try (NativeSecret t = token.load();
             NativePathRequest req = NativePathRequest.get(url).secretHeader("X-Vault-Token", t)) {
            if (namespace != null) req.header("X-Vault-Namespace", namespace);
            try (NativePathStream s = open(req)) {
                if (s.statusCode() != 200) {
                    throw new IOException("vault returned HTTP " + s.statusCode() + " for " + this);
                }
                try (NativeSecret body = s.readToSecret(MAX_BODY)) {
                    return SecretJson.extractAt(body, jsonPath);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    throw new IOException("unexpected vault response for " + this + ": " + e.getMessage(), e);
                }
            }
        }
    }

    private NativePathStream open(NativePathRequest req) throws IOException {
        try {
            return client.getStream(req);
        } catch (RuntimeException e) {
            throw new IOException("vault request failed for " + this + ": " + e.getMessage(), e);
        }
    }

    private static String trimSlashes(String s) {
        return s.replaceAll("^/+|/+$", "");
    }

    @Override
    public String toString() {
        return "vault(" + url + ", field=" + jsonPath[jsonPath.length - 1] + ")";
    }

    public static final class Builder {
        private final NativePathHttpClient client;
        private String address;
        private String mount = "secret";
        private String path;
        private String field;
        private int kvVersion = 2;
        private String namespace;
        private SecretSource token;

        private Builder(NativePathHttpClient client) {
            this.client = client;
        }

        /** Vault address, for example {@code https://vault.internal:8200}. */
        public Builder address(String address) { this.address = address; return this; }

        /** KV mount (default {@code secret}). */
        public Builder mount(String mount) { this.mount = mount; return this; }

        /** Secret path inside the mount, for example {@code billing/db}. */
        public Builder path(String path) { this.path = path; return this; }

        /** The one field of the secret to load. */
        public Builder field(String field) { this.field = field; return this; }

        /** KV engine version, 2 (default) or 1. */
        public Builder kvVersion(int kvVersion) { this.kvVersion = kvVersion; return this; }

        /** Vault Enterprise namespace (sent as {@code X-Vault-Namespace}); optional. */
        public Builder namespace(String namespace) { this.namespace = namespace; return this; }

        /** Where the vault token comes from; loaded and closed again on every {@link #load()}. */
        public Builder token(SecretSource token) { this.token = token; return this; }

        public VaultSecretSource build() {
            if (client == null || !client.isSecretMode()) {
                throw new IllegalArgumentException("VaultSecretSource needs a client built with secretMode(true)");
            }
            if (address == null || path == null || field == null || token == null) {
                throw new IllegalArgumentException("vault address, path, field and token source are required");
            }
            if (kvVersion != 1 && kvVersion != 2) {
                throw new IllegalArgumentException("vault KV version must be 1 or 2");
            }
            return new VaultSecretSource(this);
        }
    }
}
