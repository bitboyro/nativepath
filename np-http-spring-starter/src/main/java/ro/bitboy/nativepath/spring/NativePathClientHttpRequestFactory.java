package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathHttpClient;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.http.HttpMethod;

/**
 * A Spring {@link ClientHttpRequestFactory} backed by the native NativePath Rust engine.
 *
 * <p>Drop-in replacement for Spring's default {@code JdkClientHttpRequestFactory}:
 * <pre>{@code
 * RestClient restClient = RestClient.builder()
 *     .requestFactory(new NativePathClientHttpRequestFactory())
 *     .build();
 * }</pre>
 *
 * <p>The factory holds a single long-lived {@link NativePathHttpClient} with an internal
 * Tokio async runtime and connection pool. It is thread-safe and should be
 * shared across the application.
 */
public class NativePathClientHttpRequestFactory implements ClientHttpRequestFactory, AutoCloseable {

    private final NativePathHttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean streaming;
    private final SecretPolicy policy;

    public NativePathClientHttpRequestFactory() {
        this(NativePathHttpClient.create(), true);
    }

    public NativePathClientHttpRequestFactory(NativePathHttpClient client) {
        this(client, true);
    }

    /**
     * @param streaming {@code true} (default) serves bodies as a native-backed stream of any size;
     *                  {@code false} buffers the whole body off-heap first (subject to the client's
     *                  max body size) and copies it to the heap once on read.
     */
    public NativePathClientHttpRequestFactory(NativePathHttpClient client, boolean streaming) {
        this(client, streaming, SecretPolicy.DEFAULT);
    }

    /** @param policy what a secret-mode client refuses to send through RestClient (ignored otherwise). */
    public NativePathClientHttpRequestFactory(NativePathHttpClient client, boolean streaming, SecretPolicy policy) {
        this.client = client;
        this.streaming = streaming;
        this.policy = policy;
    }

    /**
     * Returns a builder for configuring the factory (e.g., trust self-signed certs).
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        if (closed.get()) {
            throw new IllegalStateException("NativePathClientHttpRequestFactory has been closed");
        }
        return new NativePathClientHttpRequest(client, uri, method, streaming, policy);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.close();
        }
    }

    public static final class Builder {
        private boolean trustSelfSigned = false;
        private boolean streaming = true;
        private long maxBodyBytes = -1;
        private boolean secretMode = false;

        /**
         * Secret mode: response data never reaches the JVM heap, so RestClient bodies cannot be read
         * (they would require a heap copy). Use {@link NativePathSecretClient} or the native
         * {@code NativePathStream} API instead.
         */
        public Builder secretMode(boolean enabled) {
            this.secretMode = enabled;
            return this;
        }

        /** Serve bodies as a streaming {@code InputStream} (default) or buffer them first. */
        public Builder streaming(boolean streaming) {
            this.streaming = streaming;
            return this;
        }

        /** Largest body accepted in buffered mode (ignored when streaming). */
        public Builder maxBodyBytes(long max) {
            this.maxBodyBytes = max;
            return this;
        }

        public Builder trustSelfSigned(boolean trust) {
            this.trustSelfSigned = trust;
            return this;
        }

        public NativePathClientHttpRequestFactory build() {
            NativePathHttpClient client = NativePathHttpClient.builder()
                    .trustSelfSigned(trustSelfSigned)
                    .maxBodyBytes(maxBodyBytes)
                    .secretMode(secretMode)
                    .build();
            return new NativePathClientHttpRequestFactory(client, streaming);
        }
    }
}
