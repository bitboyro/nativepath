package ro.bitboy.nativepath.spring.server.servlet;

import ro.bitboy.nativepath.NativePathHttpServer;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;

import java.nio.file.Path;

/**
 * Tomcat (Catalina) with the NativePath engine as its transport ({@link NativePathProtocolHandler}).
 *
 * <p>Boot hands {@code server.ssl.*} only to {@code AbstractHttp11JsseProtocol} handlers, as a
 * {@code KeyStore} holding the private key on the heap. This factory passes the PEM paths to the
 * engine instead and hides SSL from Boot's connector customization, so the key never enters the JVM.
 * {@code server.address} is passed here too (Boot sets it only on {@code AbstractProtocol}).
 */
public class NativePathServletWebServerFactory extends TomcatServletWebServerFactory {

    /** Request attribute holding the request's {@link ro.bitboy.nativepath.ServerExchange}. */
    public static final String EXCHANGE_ATTRIBUTE = "ro.bitboy.nativepath.ServerExchange";

    private int lanes = 0;
    private String dispatch = "virtual";
    private long maxBodyBytes = 16L * 1024 * 1024;
    private java.util.Collection<String> secretHeaders = java.util.List.of();
    private int maxInFlight = 0;
    private ro.bitboy.nativepath.spring.server.EngineSettings engine = new ro.bitboy.nativepath.spring.server.EngineSettings();
    private java.util.function.Consumer<NativePathHttpServer.Builder> limits = b -> { };

    public NativePathServletWebServerFactory() {
        setProtocol(NativePathProtocolHandler.class.getName());
    }

    /** Number of lanes, one worker thread each; 0 (default) means one per core. */
    public void setLanes(int lanes) {
        this.lanes = lanes;
    }

    /** {@code virtual} (default) or {@code lane}; see {@link NativePathProtocolHandler#setDispatch}. */
    public void setDispatch(String dispatch) {
        this.dispatch = dispatch;
    }

    public void setMaxBodyBytes(long maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    /** See {@link NativePathProtocolHandler#setMaxInFlight}. */
    public void setMaxInFlight(int maxInFlight) {
        this.maxInFlight = maxInFlight;
    }

    /** Lane tuning and the queue limit; see {@link ro.bitboy.nativepath.spring.server.EngineSettings}. */
    public void setEngineSettings(ro.bitboy.nativepath.spring.server.EngineSettings engine) {
        this.engine = engine;
    }

    public void setSecretHeaders(java.util.Collection<String> secretHeaders) {
        this.secretHeaders = secretHeaders;
    }

    /** Sets connection limits on the engine's builder; see {@link NativePathProtocolHandler#setLimits}. */
    public void setLimits(java.util.function.Consumer<NativePathHttpServer.Builder> limits) {
        this.limits = limits;
    }

    @Override
    protected void customizeConnector(Connector connector) {
        NativePathProtocolHandler handler = (NativePathProtocolHandler) connector.getProtocolHandler();
        handler.setLanes(lanes);
        handler.setDispatch(dispatch);
        handler.setMaxBodyBytes(maxBodyBytes);
        handler.setSecretHeaders(secretHeaders);
        handler.setMaxInFlight(maxInFlight);
        handler.setEngineSettings(engine);
        handler.setLimits(limits);
        if (getAddress() != null) {
            handler.setAddress(getAddress().getHostAddress());
        }
        Ssl ssl = getSsl();
        boolean tls = Ssl.isEnabled(ssl);
        if (tls) {
            if (ssl.getBundle() != null || ssl.getKeyStore() != null || ssl.getCertificate() == null
                    || ssl.getCertificatePrivateKey() == null) {
                throw new IllegalStateException("NativePath supports TLS from PEM files only: set "
                        + "server.ssl.certificate and server.ssl.certificate-private-key");
            }
            if (ssl.getClientAuth() != null && ssl.getClientAuth() != Ssl.ClientAuth.NONE) {
                throw new IllegalStateException("NativePath does not support client certificate authentication yet");
            }
            handler.setTls(pemFile(ssl.getCertificate()), pemFile(ssl.getCertificatePrivateKey()));
        }
        setSsl(null); // keep Boot's SslConnectorCustomizer (KeyStore, JSSE handler assertion) away
        try {
            super.customizeConnector(connector);
        } finally {
            setSsl(ssl);
        }
        if (tls) {
            connector.setScheme("https");
            connector.setSecure(true);
        }
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
