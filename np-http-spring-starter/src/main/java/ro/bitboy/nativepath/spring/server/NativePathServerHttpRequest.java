package ro.bitboy.nativepath.spring.server;

import ro.bitboy.nativepath.ServerExchange;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.AbstractServerHttpRequest;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;

import java.lang.foreign.MemorySegment;
import java.net.URI;

/**
 * A WebFlux request over a {@link ServerExchange}. The body is handed to Spring as one
 * {@link DataBuffer} viewing the engine's native memory: nothing is copied onto the heap unless
 * the application's codecs read it. The memory is zeroed once the exchange is answered.
 */
final class NativePathServerHttpRequest extends AbstractServerHttpRequest {

    private static final SslInfo ENGINE_TLS = new SslInfo() {
        @Override
        public String getSessionId() {
            return null; // the TLS session lives in the engine
        }

        @Override
        public java.security.cert.X509Certificate[] getPeerCertificates() {
            return null; // no client certificates in this version
        }
    };

    private final ServerExchange exchange;
    private final boolean tls;

    NativePathServerHttpRequest(ServerExchange exchange, boolean tls) {
        super(HttpMethod.valueOf(exchange.method()), uri(exchange, tls), null, headers(exchange));
        this.exchange = exchange;
        this.tls = tls;
    }

    /** Read from native memory on demand; only the headers Spring or the app look up become strings. */
    private static MultiValueMap<String, String> headers(ServerExchange exchange) {
        return new NativeRequestHeaders(exchange);
    }

    private static URI uri(ServerExchange exchange, boolean tls) {
        String host = exchange.header("host");
        String authority = host == null || host.isEmpty() ? "localhost" : host;
        return URI.create((tls ? "https://" : "http://") + authority + exchange.target());
    }

    @Override
    protected MultiValueMap<String, HttpCookie> initCookies() {
        MultiValueMap<String, HttpCookie> cookies = new LinkedMultiValueMap<>();
        for (String header : getHeaders().getOrEmpty("cookie")) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String name = pair.substring(0, eq).trim();
                    cookies.add(name, new HttpCookie(name, pair.substring(eq + 1).trim()));
                }
            }
        }
        return cookies;
    }

    @Override
    protected SslInfo initSslInfo() {
        return tls ? ENGINE_TLS : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getNativeRequest() {
        return (T) exchange;
    }

    @Override
    public Flux<DataBuffer> getBody() {
        MemorySegment body = exchange.body();
        if (body.equals(MemorySegment.NULL) || body.byteSize() == 0) {
            return Flux.empty();
        }
        return Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body.asByteBuffer()));
    }
}
