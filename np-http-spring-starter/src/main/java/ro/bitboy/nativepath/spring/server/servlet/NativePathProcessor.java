package ro.bitboy.nativepath.spring.server.servlet;

import ro.bitboy.nativepath.ResponseStream;
import ro.bitboy.nativepath.ServerExchange;
import jakarta.servlet.ServletConnection;
import org.apache.coyote.AbstractProcessor;
import org.apache.coyote.Adapter;
import org.apache.coyote.ContinueResponseTiming;
import org.apache.coyote.InputBuffer;
import org.apache.coyote.OutputBuffer;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.http.MimeHeaders;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;
import org.apache.tomcat.util.net.ApplicationBufferHandler;
import org.apache.tomcat.util.net.DispatchType;
import org.apache.tomcat.util.net.SocketEvent;
import org.apache.tomcat.util.net.SocketWrapperBase;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs one {@link ServerExchange} through Catalina: fills the Coyote request, calls
 * {@code Adapter.service} and answers the exchange. One instance per request in flight, pooled by
 * {@link NativePathProtocolHandler}. There is no {@code SocketWrapperBase}: the socket-derived request
 * attributes (client address and port) are prefilled from the exchange instead. The exchange
 * itself is the request attribute {@link NativePathServletWebServerFactory#EXCHANGE_ATTRIBUTE}.
 *
 * <p><b>Response.</b> Buffered and answered in one call on {@code CLOSE}, the fast path. It is
 * streamed instead ({@link ServerExchange#respondStreaming}) once more than
 * {@value #STREAM_THRESHOLD} bytes are buffered, or when an async request flushes (server-sent
 * events, {@code StreamingResponseBody}). Synchronous flushes are not acted on: Spring's message
 * converters flush every response. Never on a lane thread, whose writes would block the I/O they
 * wait for; there the response is always buffered.
 *
 * <p><b>Async.</b> A request that starts async processing stays open after {@code service}
 * returns. Tomcat's {@code AsyncStateMachine} then asks for dispatches through
 * {@link #processSocketEvent}, which normally goes to the socket's poller; here it goes to the
 * handler's executor, which runs Tomcat's own {@code dispatch} loop (as
 * {@code AbstractProcessorLight.process} does) until the request is done.
 *
 * <p>The request body is copied from native memory into a small heap chunk per read, never handed
 * to Catalina as a direct buffer: {@code getReader()} decodes through {@code B2CConverter}, which
 * calls {@code ByteBuffer.array()}. The chunk is wiped after each request.
 */
final class NativePathProcessor extends AbstractProcessor {

    private static final Log log = LogFactory.getLog(NativePathProcessor.class);
    private static final AtomicLong CONNECTION_IDS = new AtomicLong();
    static final int STREAM_THRESHOLD = 256 * 1024;

    private final NativePathProtocolHandler owner;
    private final boolean tls;
    private final int localPort;
    private final String localAddress;
    private final BodyInput input = new BodyInput();
    private final BodyOutput output = new BodyOutput();
    // Serializes the request's container work: the first service call and later async dispatches.
    private final ReentrantLock lock = new ReentrantLock();
    private ServerExchange exchange;
    private ResponseStream stream;

    NativePathProcessor(NativePathProtocolHandler owner, Adapter adapter, boolean tls, int localPort, String localAddress) {
        super(adapter);
        this.owner = owner;
        this.tls = tls;
        this.localPort = localPort;
        this.localAddress = localAddress;
        request.setInputBuffer(input);
        response.setOutputBuffer(output);
    }

    /** Serves a new request; returns once it is done or waiting asynchronously. */
    void process(ServerExchange ex) {
        lock.lock();
        try {
            exchange = ex;
            SocketState state;
            try {
                fill(ex);
                adapter.service(request, response);
                state = getErrorState().isError() ? SocketState.CLOSED
                        : isAsync() ? SocketState.LONG : SocketState.OPEN;
            } catch (Throwable t) {
                log.error("Request failed: " + ex.method() + " " + ex.target(), t);
                state = SocketState.CLOSED;
            }
            if (isAsync() && state != SocketState.CLOSED) {
                state = asyncPostProcess();
            }
            settle(drive(state, SocketEvent.OPEN_READ));
        } finally {
            lock.unlock();
        }
    }

    /** An async event (dispatch, complete, timeout), on the handler's executor. */
    private void onEvent(SocketEvent event) {
        lock.lock();
        try {
            if (exchange == null) {
                return; // already finished
            }
            SocketState state;
            try {
                state = dispatch(event);
                if (isAsync() && state != SocketState.CLOSED) {
                    state = asyncPostProcess();
                }
            } catch (Throwable t) {
                log.error("Async dispatch failed: " + exchange.method() + " " + exchange.target(), t);
                state = SocketState.CLOSED;
            }
            settle(drive(state, event));
        } finally {
            lock.unlock();
        }
    }

    /** Tomcat's dispatch loop (AbstractProcessorLight.process) for what remains after a step. */
    private SocketState drive(SocketState state, SocketEvent event) {
        Iterator<DispatchType> dispatches = getIteratorAndClearDispatches();
        try {
            while (state == SocketState.ASYNC_END || dispatches != null && state != SocketState.CLOSED) {
                state = dispatches != null ? dispatch(dispatches.next().getSocketStatus()) : dispatch(event);
                if (isAsync() && state != SocketState.CLOSED) {
                    state = asyncPostProcess();
                }
                if (dispatches == null || !dispatches.hasNext()) {
                    dispatches = getIteratorAndClearDispatches();
                }
            }
        } catch (IOException e) {
            log.error("Dispatch failed", e);
            return SocketState.CLOSED;
        }
        return state;
    }

    /** Keeps an async request open (LONG), or finishes the exchange and returns to the pool. */
    private void settle(SocketState state) {
        if (state == SocketState.LONG) {
            owner.waiting(this);
            return;
        }
        owner.done(this);
        ServerExchange ex = exchange;
        exchange = null;
        ResponseStream s = stream;
        stream = null;
        if (s != null && s.isOpen()) {
            if (getErrorState().isError()) {
                s.abort(); // a response cut short must not look complete
            } else {
                s.close();
            }
        }
        ex.close(); // sends 500 if Catalina never closed the response
        request.recycle();
        response.recycle();
        input.reset(MemorySegment.NULL);
        output.reset();
        recycle();
        owner.release(this);
    }

    private void fill(ServerExchange ex) {
        request.setStartTimeNanos(System.nanoTime());
        request.method().setString(ex.method());
        byte[] target = ex.target().getBytes(StandardCharsets.UTF_8);
        int q = indexOf(target, (byte) '?');
        request.requestURI().setBytes(target, 0, q < 0 ? target.length : q);
        if (q >= 0) {
            request.queryString().setBytes(target, q + 1, target.length - q - 1);
        }
        request.protocol().setString("HTTP/1.1");
        request.scheme().setString(tls ? "https" : "http");
        MimeHeaders headers = request.getMimeHeaders();
        ex.forEachHeader((name, value) -> headers.addValue(name).setString(value));
        String host = ex.header("host");
        if (host != null) {
            int colon = host.lastIndexOf(':');
            if (colon > host.lastIndexOf(']')) {
                request.serverName().setString(host.substring(0, colon));
                request.setServerPort(Integer.parseInt(host.substring(colon + 1)));
            } else {
                request.serverName().setString(host);
            }
        }
        String peer = ex.peerAddress();
        if (peer != null) {
            request.remoteAddr().setString(peer);
            request.peerAddr().setString(peer);
            request.remoteHost().setString(peer);
        }
        request.setRemotePort(ex.peerPort());
        request.setLocalPort(ex.localPort() > 0 ? ex.localPort() : localPort);
        request.localAddr().setString(localAddress);
        request.localName().setString(localAddress);
        request.setAttribute(NativePathServletWebServerFactory.EXCHANGE_ATTRIBUTE, ex);
        MemorySegment body = ex.body();
        if (!body.equals(MemorySegment.NULL)) {
            request.setContentLength(body.byteSize());
        }
        input.reset(body);
    }

    private static int indexOf(byte[] b, byte c) {
        for (int i = 0; i < b.length; i++) {
            if (b[i] == c) return i;
        }
        return -1;
    }

    // The response, sent on CLOSE (Catalina's OutputBuffer.close when the request completes).

    @Override
    protected void prepareResponse() {
        // Headers are read from the Coyote response when it is finished.
    }

    @Override
    protected void finishResponse() throws IOException {
        if (stream != null) {
            stream.close();
            return;
        }
        ServerExchange ex = exchange;
        if (ex == null || ex.isAnswered()) {
            return;
        }
        ex.respond(response.getStatus(), responseHeaders(), output.segment());
    }

    /** Sends the head now and the rest of the body as it is written. */
    private void startStreaming() throws IOException {
        long length = response.getContentLengthLong(); // -1 when the application did not set it
        stream = exchange.respondStreaming(response.getStatus(), responseHeaders(), length);
        output.drainTo(stream);
    }

    private boolean canStream() {
        return stream == null && exchange != null && !exchange.isAnswered() && !NativePathProtocolHandler.onLaneThread();
    }

    private Map<String, List<String>> responseHeaders() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String contentType = response.getContentType();
        if (contentType != null) {
            headers.put("Content-Type", List.of(contentType));
        }
        String language = response.getContentLanguage();
        if (language != null) {
            headers.put("Content-Language", List.of(language));
        }
        MimeHeaders mh = response.getMimeHeaders();
        for (int i = 0; i < mh.size(); i++) {
            String name = mh.getName(i).toString();
            if (name.equalsIgnoreCase("content-length") || name.equalsIgnoreCase("transfer-encoding")
                    || name.equalsIgnoreCase("content-type")) {
                continue; // set by the engine, or above
            }
            headers.computeIfAbsent(name, k -> new ArrayList<>(1)).add(mh.getValue(i).toString());
        }
        return headers;
    }

    @Override
    protected void ack(ContinueResponseTiming continueResponseTiming) {
        // The engine has read the whole body before the request reaches Java.
    }

    @Override
    protected void earlyHints() {
    }

    /** A flush during async processing means "send now" (events, streams); see the class comment. */
    @Override
    protected void flush() throws IOException {
        if (isAsync() && canStream()) {
            startStreaming();
        }
    }

    @Override
    protected int available(boolean doRead) {
        return input.available();
    }

    @Override
    protected void setRequestBody(ByteChunk body) {
        throw new UnsupportedOperationException("request body replay (FORM authentication)");
    }

    @Override
    protected void setSwallowResponse() {
    }

    @Override
    protected void disableSwallowRequest() {
    }

    @Override
    protected boolean isRequestBodyFullyRead() {
        return input.available() == 0;
    }

    @Override
    protected void registerReadInterest() {
        throw new UnsupportedOperationException("non-blocking I/O");
    }

    @Override
    protected boolean isReadyForWrite() {
        return true;
    }

    @Override
    protected boolean isTrailerFieldsReady() {
        return true;
    }

    @Override
    protected ServletConnection getServletConnection() {
        String id = Long.toString(CONNECTION_IDS.incrementAndGet());
        return new ServletConnection() {
            @Override public String getConnectionId() { return id; }
            @Override public String getProtocol() { return "HTTP/1.1"; }
            @Override public String getProtocolConnectionId() { return ""; }
            @Override public boolean isSecure() { return tls; }
        };
    }

    @Override
    protected boolean flushBufferedWrite() {
        return false;
    }

    @Override
    protected SocketState dispatchEndRequest() {
        return SocketState.OPEN;
    }

    // Where a socket-based processor would go through its SocketWrapperBase.

    /** {@code AsyncContext.start(Runnable)}. */
    @Override
    protected void execute(Runnable runnable) {
        owner.executor().execute(runnable);
    }

    /** Async dispatch, complete or timeout: run the dispatch loop on the executor. */
    @Override
    protected void processSocketEvent(SocketEvent event, boolean dispatch) {
        owner.executor().execute(() -> onEvent(event));
    }

    /** Socket-driven entry point, unused: requests arrive through process(ServerExchange). */
    @Override
    protected SocketState service(SocketWrapperBase<?> socketWrapper) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected Log getLog() {
        return log;
    }

    @Override
    public void pause() {
    }

    /** The request body, copied from native memory one heap chunk at a time. */
    private static final class BodyInput implements InputBuffer {
        private final byte[] chunk = new byte[16 * 1024];
        private MemorySegment body = MemorySegment.NULL;
        private long pos;
        private int used;

        void reset(MemorySegment body) {
            if (used > 0) {
                Arrays.fill(chunk, 0, used, (byte) 0);
                used = 0;
            }
            this.body = body;
            pos = 0;
        }

        private long remaining() {
            return body.equals(MemorySegment.NULL) ? 0 : body.byteSize() - pos;
        }

        @Override
        public int doRead(ApplicationBufferHandler handler) {
            long rem = remaining();
            if (rem <= 0) {
                return -1;
            }
            int n = (int) Math.min(rem, chunk.length);
            MemorySegment.copy(body, ValueLayout.JAVA_BYTE, pos, chunk, 0, n);
            pos += n;
            used = Math.max(used, n);
            handler.setByteBuffer(ByteBuffer.wrap(chunk, 0, n));
            return n;
        }

        @Override
        public int available() {
            return (int) Math.min(remaining(), Integer.MAX_VALUE);
        }
    }

    /**
     * The response body: accumulated on the heap and handed to the engine in one copy, or, once
     * streaming, passed straight to the {@link ResponseStream}.
     */
    private final class BodyOutput implements OutputBuffer {
        private byte[] buf = new byte[8 * 1024];
        private int count;
        private long written;

        void reset() {
            count = 0;
            written = 0;
            if (buf.length > STREAM_THRESHOLD * 2) {
                buf = new byte[8 * 1024]; // do not keep a large buffer from a lane-thread response
            }
        }

        MemorySegment segment() {
            return count == 0 ? MemorySegment.NULL : MemorySegment.ofArray(buf).asSlice(0, count);
        }

        void drainTo(ResponseStream out) throws IOException {
            if (count > 0) {
                out.write(buf, 0, count);
                count = 0;
            }
        }

        @Override
        public int doWrite(ByteBuffer chunk) throws IOException {
            int n = chunk.remaining();
            written += n;
            if (stream != null) {
                stream.write(chunk);
                return n;
            }
            if (count + n > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, count + n));
            }
            chunk.get(buf, count, n);
            count += n;
            if (count > STREAM_THRESHOLD && canStream()) {
                startStreaming();
            }
            return n;
        }

        @Override
        public long getBytesWritten() {
            return written;
        }
    }
}
