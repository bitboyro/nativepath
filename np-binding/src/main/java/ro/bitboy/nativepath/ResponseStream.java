package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;

/**
 * The body of a streamed response ({@link ServerExchange#respondStreaming}). Each write copies the
 * bytes into native memory and hands them to the engine, blocking while the client is slower than
 * the producer. {@link #close()} ends the body; {@link #abort()} closes the connection instead, so
 * a response cut short never looks complete. Not thread-safe; never write from the thread driving
 * the exchange's lane.
 */
public final class ResponseStream extends OutputStream {

    private static final int CHUNK = 64 * 1024;

    private MemorySegment writer;
    private final ServerLane lane;
    private final Runnable onEnd;
    private MemorySegment buffer;

    ResponseStream(MemorySegment writer, ServerLane lane, Runnable onEnd) {
        this.writer = writer;
        this.lane = lane;
        this.onEnd = onEnd;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        java.util.Objects.checkFromIndexSize(off, len, b.length);
        check();
        MemorySegment buf = buffer();
        while (len > 0) {
            int n = Math.min(len, CHUNK);
            MemorySegment.copy(b, off, buf, ValueLayout.JAVA_BYTE, 0, n);
            send(buf, n);
            off += n;
            len -= n;
        }
    }

    /** Writes the buffer's remaining bytes (native buffers are passed without a copy). */
    public void write(ByteBuffer src) throws IOException {
        check();
        if (src.isDirect()) {
            int n = src.remaining();
            send(MemorySegment.ofBuffer(src), n);
            src.position(src.limit());
            return;
        }
        MemorySegment buf = buffer();
        while (src.hasRemaining()) {
            int n = Math.min(src.remaining(), CHUNK);
            MemorySegment.copy(MemorySegment.ofBuffer(src), ValueLayout.JAVA_BYTE, 0, buf, ValueLayout.JAVA_BYTE, 0, n);
            src.position(src.position() + n);
            send(buf, n);
        }
    }

    /** Ends the body normally. */
    @Override
    public void close() {
        end(1);
    }

    /** Ends the response by closing the connection (the client sees a truncated response). */
    public void abort() {
        end(0);
    }

    public boolean isOpen() {
        return writer != null;
    }

    private void check() throws IOException {
        if (writer == null) {
            throw new IOException("response stream closed");
        }
        if (lane != null && lane.isDrivenBy(Thread.currentThread())) {
            throw new IllegalStateException("streamed writes would block the lane they wait for: "
                    + "write from another thread");
        }
    }

    private MemorySegment buffer() {
        if (buffer == null) {
            buffer = Arena.ofAuto().allocate(CHUNK);
        }
        return buffer;
    }

    private void send(MemorySegment data, long len) throws IOException {
        if (len > 0 && ForeignBridge.responseWrite(writer, data, len) != 0) {
            abort();
            throw new IOException("client closed the connection");
        }
    }

    private void end(int ok) {
        MemorySegment w = writer;
        if (w == null) {
            return;
        }
        writer = null;
        try {
            ForeignBridge.responseFinish(w, ok);
        } finally {
            onEnd.run();
        }
    }
}
