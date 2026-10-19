package ro.bitboy.nativepath.spring.server;

import ro.bitboy.nativepath.ServerExchange;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.AbstractServerHttpResponse;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A WebFlux response over a {@link ServerExchange}. The body is collected while the handler runs
 * and sent in one native call when it completes ({@link #send()}); streaming responses (SSE) are
 * buffered the same way in this version.
 */
final class NativePathServerHttpResponse extends AbstractServerHttpResponse {

    private final ServerExchange exchange;
    private final List<DataBuffer> chunks = new ArrayList<>();

    NativePathServerHttpResponse(ServerExchange exchange) {
        super(DefaultDataBufferFactory.sharedInstance, new HttpHeaders());
        this.exchange = exchange;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getNativeResponse() {
        return (T) exchange;
    }

    @Override
    protected Mono<Void> writeWithInternal(Publisher<? extends DataBuffer> body) {
        return Flux.from(body).doOnNext(this::retain).then();
    }

    @Override
    protected Mono<Void> writeAndFlushWithInternal(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        return Flux.from(body).concatMap(this::writeWithInternal).then();
    }

    private synchronized void retain(DataBuffer buffer) {
        chunks.add(buffer);
    }

    @Override
    protected void applyStatusCode() {
        // Read in send(); nothing to push to the engine before the body is complete.
    }

    @Override
    protected void applyHeaders() {
    }

    @Override
    protected void applyCookies() {
        for (List<ResponseCookie> cookies : getCookies().values()) {
            for (ResponseCookie cookie : cookies) {
                getHeaders().add(HttpHeaders.SET_COOKIE, cookie.toString());
            }
        }
    }

    /**
     * Sends status, headers and the collected body. Call once, after the handler has completed.
     * The engine copies the body once, straight from Spring's buffer (heap or direct); several
     * buffers are first joined on the heap.
     */
    synchronized void send() {
        HttpStatusCode status = getStatusCode();
        int code = status == null ? 200 : status.value();
        try {
            exchange.respond(code, getHeaders(), body());
        } finally {
            release();
        }
    }

    private MemorySegment body() {
        if (chunks.isEmpty()) {
            return MemorySegment.NULL;
        }
        if (chunks.size() == 1) {
            DataBuffer b = chunks.get(0);
            if (b.readableByteCount() == 0) {
                return MemorySegment.NULL;
            }
            try (DataBuffer.ByteBufferIterator it = b.readableByteBuffers()) {
                ByteBuffer bb = it.next();
                if (!it.hasNext()) {
                    return MemorySegment.ofBuffer(bb);
                }
            }
        }
        int total = 0;
        for (DataBuffer b : chunks) {
            total += b.readableByteCount();
        }
        byte[] joined = new byte[total];
        int offset = 0;
        for (DataBuffer b : chunks) {
            int n = b.readableByteCount();
            b.read(joined, offset, n);
            offset += n;
        }
        return total == 0 ? MemorySegment.NULL : MemorySegment.ofArray(joined);
    }

    synchronized void release() {
        chunks.forEach(DataBufferUtils::release);
        chunks.clear();
    }
}
