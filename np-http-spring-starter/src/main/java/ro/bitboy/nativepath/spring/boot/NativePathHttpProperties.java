package ro.bitboy.nativepath.spring.boot;

import ro.bitboy.nativepath.spring.SecretPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration under the {@code nativepath.http.*} prefix.
 *
 * <pre>
 * nativepath.http.enabled=true                # use NativePath for RestClient (default true)
 * nativepath.http.secret-mode=false           # keep response data off the JVM heap (see below)
 * nativepath.http.isolate-connections=false   # every request on its own connection
 * nativepath.http.streaming=true              # RestClient bodies are streamed, not buffered
 * nativepath.http.max-body-bytes=268435456    # limit for buffered bodies (streaming has none)
 * nativepath.http.trust-self-signed=false     # accept self-signed certificates (development only)
 * nativepath.http.forbidden-headers=authorization,proxy-authorization,cookie,x-api-key,x-vault-token   # refused via RestClient in secret mode
 * nativepath.http.forbid-request-bodies=true  # refuse RestClient request bodies in secret mode
 *
 * nativepath.http.server.enabled=false        # serve WebFlux apps on the NativePath engine instead of Netty,
 *                                         # and Spring MVC apps on it instead of Tomcat's connector
 * nativepath.http.server.worker-threads=0     # one per lane (event loop); 0 = one per core
 * nativepath.http.server.dispatch=virtual     # Spring MVC: requests on virtual threads (or Boot's executor);
 *                                         # "lane": on the lane threads (handlers must not block)
 * nativepath.http.server.max-body-bytes=16777216   # larger request bodies get 413
 * nativepath.http.server.secret-headers=      # request headers kept out of Java strings (see NativePathSecrets)
 * nativepath.http.server.header-read-timeout=10s       # unset: engine defaults (shown)
 * nativepath.http.server.idle-timeout=60s
 * nativepath.http.server.body-read-timeout=30s
 * nativepath.http.server.tls-handshake-timeout=10s
 * nativepath.http.server.max-header-bytes=65536
 * nativepath.http.server.max-connections=10000
 * nativepath.http.server.max-inflight-body-bytes=268435456
 * server.ssl.certificate / certificate-private-key   # PEM files: TLS terminated in the engine
 *
 * # optional named secret sources (a SecretSources bean exists only when at least one is configured)
 * nativepath.secrets.vault-token.type=file
 * nativepath.secrets.vault-token.path=/var/run/secrets/vault-token
 * nativepath.secrets.api-key.type=keychain
 * nativepath.secrets.api-key.service=billing-api
 * nativepath.secrets.api-key.account=prod
 * nativepath.secrets.db-password.type=vault
 * nativepath.secrets.db-password.address=https://vault.internal:8200
 * nativepath.secrets.db-password.path=billing/db     # KV path (mount defaults to "secret")
 * nativepath.secrets.db-password.field=password
 * nativepath.secrets.db-password.token-source=vault-token   # a file or keychain source
 * </pre>
 *
 * With {@code secret-mode=true}, a plain {@code RestClient} cannot read bodies (that needs a heap
 * copy); use the {@code NativePathSecretClient} bean or {@code NativePathHttpClient} directly.
 */
@ConfigurationProperties(prefix = "nativepath.http")
public class NativePathHttpProperties {

    private boolean enabled = true;
    private boolean secretMode = false;
    private boolean isolateConnections = false;
    private boolean streaming = true;
    private long maxBodyBytes = -1;
    private boolean trustSelfSigned = false;
    private java.util.List<String> forbiddenHeaders = new java.util.ArrayList<>(SecretPolicy.DEFAULT_FORBIDDEN_HEADERS);
    private boolean forbidRequestBodies = true;
    private final Server server = new Server();

    public Server getServer() { return server; }

    /** {@code nativepath.http.server.*}: the embedded server (WebFlux, or Spring MVC through Tomcat's Catalina). */
    public static class Server {
        private boolean enabled = false;
        private int workerThreads = 0;
        // null: each server's own default (virtual for Servlet apps, lane for WebFlux)
        private String dispatch;
        private int maxInFlight = 0;
        private int batch = 1;
        private int spin = 0;
        private boolean workStealing = false;
        private int maxQueued = 0;
        private long maxBodyBytes = 16L * 1024 * 1024;
        private java.util.List<String> secretHeaders = new java.util.ArrayList<>();
        // Limits: null keeps the engine default.
        private java.time.Duration headerReadTimeout;
        private java.time.Duration idleTimeout;
        private java.time.Duration bodyReadTimeout;
        private java.time.Duration tlsHandshakeTimeout;
        private Long maxHeaderBytes;
        private Long maxConnections;
        private Long maxInflightBodyBytes;

        public java.time.Duration getHeaderReadTimeout() { return headerReadTimeout; }
        public void setHeaderReadTimeout(java.time.Duration v) { this.headerReadTimeout = v; }

        public java.time.Duration getIdleTimeout() { return idleTimeout; }
        public void setIdleTimeout(java.time.Duration v) { this.idleTimeout = v; }

        public java.time.Duration getBodyReadTimeout() { return bodyReadTimeout; }
        public void setBodyReadTimeout(java.time.Duration v) { this.bodyReadTimeout = v; }

        public java.time.Duration getTlsHandshakeTimeout() { return tlsHandshakeTimeout; }
        public void setTlsHandshakeTimeout(java.time.Duration v) { this.tlsHandshakeTimeout = v; }

        public Long getMaxHeaderBytes() { return maxHeaderBytes; }
        public void setMaxHeaderBytes(Long v) { this.maxHeaderBytes = v; }

        public Long getMaxConnections() { return maxConnections; }
        public void setMaxConnections(Long v) { this.maxConnections = v; }

        public Long getMaxInflightBodyBytes() { return maxInflightBodyBytes; }
        public void setMaxInflightBodyBytes(Long v) { this.maxInflightBodyBytes = v; }

        /** Sets the limits configured here on a server builder; unset ones keep the engine default. */
        public void applyLimits(ro.bitboy.nativepath.NativePathHttpServer.Builder b) {
            if (headerReadTimeout != null) b.headerReadTimeout(headerReadTimeout);
            if (idleTimeout != null) b.idleTimeout(idleTimeout);
            if (bodyReadTimeout != null) b.bodyReadTimeout(bodyReadTimeout);
            if (tlsHandshakeTimeout != null) b.tlsHandshakeTimeout(tlsHandshakeTimeout);
            if (maxHeaderBytes != null) b.maxHeaderBytes(maxHeaderBytes);
            if (maxConnections != null) b.maxConnections(maxConnections);
            if (maxInflightBodyBytes != null) b.maxInflightBodyBytes(maxInflightBodyBytes);
        }

        public java.util.List<String> getSecretHeaders() { return secretHeaders; }
        public void setSecretHeaders(java.util.List<String> secretHeaders) { this.secretHeaders = secretHeaders; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getDispatch() { return dispatch; }
        public void setDispatch(String dispatch) { this.dispatch = dispatch; }

        public int getMaxInFlight() { return maxInFlight; }
        public void setMaxInFlight(int maxInFlight) { this.maxInFlight = maxInFlight; }
        public int getBatch() { return batch; }
        public void setBatch(int batch) { this.batch = batch; }
        public int getSpin() { return spin; }
        public void setSpin(int spin) { this.spin = spin; }
        public boolean isWorkStealing() { return workStealing; }
        public void setWorkStealing(boolean workStealing) { this.workStealing = workStealing; }
        public int getMaxQueued() { return maxQueued; }
        public void setMaxQueued(int maxQueued) { this.maxQueued = maxQueued; }

        public ro.bitboy.nativepath.spring.server.EngineSettings engineSettings() {
            return new ro.bitboy.nativepath.spring.server.EngineSettings().batch(batch).spin(spin)
                    .workStealing(workStealing).maxQueued(maxQueued);
        }

        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }


        public long getMaxBodyBytes() { return maxBodyBytes; }
        public void setMaxBodyBytes(long maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    }

    /** One entry of {@code nativepath.secrets.<name>.*}: a location, never a value. */
    public static class SecretSourceProperties {
        public enum Type { FILE, KEYCHAIN, VAULT }

        private Type type;
        private String path;          // file: file path; vault: secret path inside the mount
        private String service;       // keychain
        private String account;       // keychain
        private String address;       // vault
        private String mount = "secret";
        private String field;         // vault
        private int kvVersion = 2;    // vault
        private String namespace;     // vault (optional)
        private String tokenSource;   // vault: name of a file or keychain source holding the token

        public Type getType() { return type; }
        public void setType(Type type) { this.type = type; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public String getService() { return service; }
        public void setService(String service) { this.service = service; }
        public String getAccount() { return account; }
        public void setAccount(String account) { this.account = account; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public String getMount() { return mount; }
        public void setMount(String mount) { this.mount = mount; }
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public int getKvVersion() { return kvVersion; }
        public void setKvVersion(int kvVersion) { this.kvVersion = kvVersion; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public String getTokenSource() { return tokenSource; }
        public void setTokenSource(String tokenSource) { this.tokenSource = tokenSource; }
    }

    public java.util.List<String> getForbiddenHeaders() { return forbiddenHeaders; }
    public void setForbiddenHeaders(java.util.List<String> forbiddenHeaders) { this.forbiddenHeaders = forbiddenHeaders; }

    public boolean isForbidRequestBodies() { return forbidRequestBodies; }
    public void setForbidRequestBodies(boolean forbidRequestBodies) { this.forbidRequestBodies = forbidRequestBodies; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isSecretMode() { return secretMode; }
    public void setSecretMode(boolean secretMode) { this.secretMode = secretMode; }

    public boolean isIsolateConnections() { return isolateConnections; }
    public void setIsolateConnections(boolean isolateConnections) { this.isolateConnections = isolateConnections; }

    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }

    public long getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(long maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }

    public boolean isTrustSelfSigned() { return trustSelfSigned; }
    public void setTrustSelfSigned(boolean trustSelfSigned) { this.trustSelfSigned = trustSelfSigned; }
}
