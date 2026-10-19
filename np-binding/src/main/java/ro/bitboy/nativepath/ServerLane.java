package ro.bitboy.nativepath;

import ro.bitboy.nativepath.internal.ForeignBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * One event loop of an {@link NativePathHttpServer}. Exactly one thread drives a lane, by calling
 * {@link #poll(long)} in a loop; the call runs the lane's I/O (TLS, parsing, writing earlier
 * responses) on that thread until a request is ready. A concurrent second caller gets {@code null}.
 */
public final class ServerLane {

    private final NativePathHttpServer server;
    private final int index;
    // Filled by each poll; reused, so polling allocates nothing native per request.
    private final MemorySegment view = Arena.ofAuto().allocate(ForeignBridge.REQUEST_VIEW);
    // The thread currently driving the lane (inside poll), for ResponseStream's deadlock check.
    private volatile Thread driver;

    ServerLane(NativePathHttpServer server, int index) {
        this.server = server;
        this.index = index;
    }

    public int index() {
        return index;
    }

    /** Whether {@code t} drives this lane (the last thread to poll it). */
    boolean isDrivenBy(Thread t) {
        return driver == t;
    }

    /**
     * Drives the lane for up to {@code timeoutMillis} and returns the next request, or {@code null}
     * on timeout or once the server has stopped ({@link NativePathHttpServer#isRunning()} tells which).
     * The caller must close the exchange; answering it on this thread before the next poll gets
     * the response written soonest.
     */
    public ServerExchange poll(long timeoutMillis) {
        if (!server.lifecycle.readLock().tryLock()) {
            return null; // closing
        }
        driver = Thread.currentThread();
        try {
            if (server.destroyed || !server.running) {
                return null;
            }
            MemorySegment req = ForeignBridge.laneNext(server.handle, index, timeoutMillis, view);
            int rc = view.get(ValueLayout.JAVA_INT, 0);
            if (rc < 0) {
                server.running = false;
            }
            return rc == 1 ? new ServerExchange(req, view, server.secretHeaders, this) : null;
        } finally {
            server.lifecycle.readLock().unlock();
        }
    }

    /**
     * Drives the lane without taking a request, until one of its requests is answered or
     * {@code timeoutMillis} passes. For a worker that hands requests to other threads and has
     * reached its limit of requests in flight: their responses still get written, while new
     * requests wait in the engine's queue (and are shed with 503 once it is full, see
     * {@link NativePathHttpServer.Builder#maxQueued(int)}). Returns false once the server has stopped.
     */
    public boolean drive(long timeoutMillis) {
        if (!server.lifecycle.readLock().tryLock()) {
            return false; // closing
        }
        driver = Thread.currentThread();
        try {
            if (server.destroyed || !server.running) {
                return false;
            }
            if (ForeignBridge.laneDrive(server.handle, index, timeoutMillis) == 0) {
                server.running = false;
                return false;
            }
            return true;
        } finally {
            server.lifecycle.readLock().unlock();
        }
    }
}
