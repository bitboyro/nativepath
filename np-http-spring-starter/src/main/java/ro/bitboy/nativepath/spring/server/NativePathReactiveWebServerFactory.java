package ro.bitboy.nativepath.spring.server;

import ro.bitboy.nativepath.NativePathHttpServer;
import org.springframework.boot.web.reactive.server.AbstractReactiveWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.server.reactive.HttpHandler;

import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Serves a Spring WebFlux application on the native NativePath engine instead of Netty. Honours
 * {@code server.port}, {@code server.address} and TLS from {@code server.ssl.certificate} /
 * {@code server.ssl.certificate-private-key} (PEM files, terminated in the engine so the key never
 * enters the JVM). Spring Boot registers it through {@code NativePathServerAutoConfiguration} when
 * {@code nativepath.http.server.enabled=true}.
 */
public class NativePathReactiveWebServerFactory extends AbstractReactiveWebServerFactory {

    private int workerThreads = 0;
    private long maxBodyBytes = 16L * 1024 * 1024;
    private List<String> secretHeaders = List.of();
    private String dispatch = "lane";
    private int maxInFlight = 0;
    private EngineSettings engine = new EngineSettings();
    private Duration headerReadTimeout;
    private Duration idleTimeout;
    private Duration bodyReadTimeout;
    private Duration tlsHandshakeTimeout;
    private Long maxHeaderBytes;
    private Long maxConnections;
    private Long maxInflightBodyBytes;

    public NativePathReactiveWebServerFactory() {
    }

    public NativePathReactiveWebServerFactory(int port) {
        super(port);
    }

    /**
     * Worker threads, one per lane (event loop); each handles its lane's requests one at a time.
     * 0 (default) means one per core.
     */
    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    /** Larger request bodies are answered with 413 (default 16 MB). */
    public void setMaxBodyBytes(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    /**
     * Request headers whose values never become Java strings: hidden from Spring's
     * {@code HttpHeaders} and read with {@link NativePathSecrets#header}. Default none (Spring Security
     * reads {@code Authorization} as a string, so hiding it is a deliberate choice).
     */
    public void setSecretHeaders(List<String> secretHeaders) {
        this.secretHeaders = List.copyOf(secretHeaders);
    }

    /**
     * {@code lane} (default): requests run on the lane threads, for handlers that never block.
     * {@code virtual}: one virtual thread per request, for applications that block.
     */
    public void setDispatch(String dispatch) {
        this.dispatch = dispatch;
    }

    /** With dispatch {@code virtual}: requests running at once (default 0: no limit); see {@link InFlightLimit}. */
    public void setMaxInFlight(int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    /** Lane tuning and the queue limit (batching, spinning, work stealing, max-queued); see {@link EngineSettings}. */
    public void setEngineSettings(EngineSettings engine) {
        this.engine = engine;
    }

    /** See {@link NativePathHttpServer.Builder#headerReadTimeout}; null keeps the engine default. */
    public void setHeaderReadTimeout(Duration headerReadTimeout) {
        this.headerReadTimeout = headerReadTimeout;
    }

    /** See {@link NativePathHttpServer.Builder#idleTimeout}; null keeps the engine default. */
    public void setIdleTimeout(Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    /** See {@link NativePathHttpServer.Builder#bodyReadTimeout}; null keeps the engine default. */
    public void setBodyReadTimeout(Duration bodyReadTimeout) {
        this.bodyReadTimeout = bodyReadTimeout;
    }

    /** See {@link NativePathHttpServer.Builder#tlsHandshakeTimeout}; null keeps the engine default. */
    public void setTlsHandshakeTimeout(Duration tlsHandshakeTimeout) {
        this.tlsHandshakeTimeout = tlsHandshakeTimeout;
    }

    /** See {@link NativePathHttpServer.Builder#maxHeaderBytes}; null keeps the engine default. */
    public void setMaxHeaderBytes(Long maxHeaderBytes) {
        this.maxHeaderBytes = maxHeaderBytes;
    }

    /** See {@link NativePathHttpServer.Builder#maxConnections}; null keeps the engine default. */
    public void setMaxConnections(Long maxConnections) {
        this.maxConnections = maxConnections;
    }

    /** See {@link NativePathHttpServer.Builder#maxInflightBodyBytes}; null keeps the engine default. */
    public void setMaxInflightBodyBytes(Long maxInflightBodyBytes) {
        this.maxInflightBodyBytes = maxInflightBodyBytes;
    }

    @Override
    public WebServer getWebServer(HttpHandler httpHandler) {
        InetAddress address = getAddress();
        NativePathHttpServer.Builder builder = NativePathHttpServer.builder()
                .host(address == null ? "0.0.0.0" : address.getHostAddress())
                .port(getPort())
                .lanes(workerThreads)
                .maxBodyBytes(maxBodyBytes)
                .secretHeaders(secretHeaders);
        engine.applyTo(builder);
        if (headerReadTimeout != null) builder.headerReadTimeout(headerReadTimeout);
        if (idleTimeout != null) builder.idleTimeout(idleTimeout);
        if (bodyReadTimeout != null) builder.bodyReadTimeout(bodyReadTimeout);
        if (tlsHandshakeTimeout != null) builder.tlsHandshakeTimeout(tlsHandshakeTimeout);
        if (maxHeaderBytes != null) builder.maxHeaderBytes(maxHeaderBytes);
        if (maxConnections != null) builder.maxConnections(maxConnections);
        if (maxInflightBodyBytes != null) builder.maxInflightBodyBytes(maxInflightBodyBytes);
        Ssl ssl = getSsl();
        if (Ssl.isEnabled(ssl)) {
            if (ssl.getBundle() != null || ssl.getKeyStore() != null || ssl.getCertificate() == null
                    || ssl.getCertificatePrivateKey() == null) {
                throw new IllegalStateException("NativePath supports TLS from PEM files only: set "
                        + "server.ssl.certificate and server.ssl.certificate-private-key (keystores and SSL bundles are not supported yet)");
            }
            if (ssl.getClientAuth() != null && ssl.getClientAuth() != Ssl.ClientAuth.NONE) {
                throw new IllegalStateException("NativePath does not support client certificate authentication yet");
            }
            builder.tls(pemFile(ssl.getCertificate()), pemFile(ssl.getCertificatePrivateKey()));
        }
        return new NativePathWebServer(httpHandler, builder, dispatch, maxInFlight);
    }

    /** The engine reads PEM files itself; only file-system locations are accepted. */
    private static Path pemFile(String location) {
        if (location.startsWith("classpath:")) {
            throw new IllegalStateException("NativePath reads TLS files from the file system; '" + location
                    + "' is a classpath location (use a file path or file: URL)");
        }
        return Path.of(location.startsWith("file:") ? location.substring("file:".length()) : location);
    }
}
