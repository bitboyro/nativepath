package ro.bitboy.nativepath.spring.server;

import ro.bitboy.nativepath.ServerLane;

import java.util.concurrent.Semaphore;

/**
 * Caps the requests a server has handed to other threads (virtual threads or an executor) and not
 * yet finished, like Tomcat's {@code maxThreads}. At the cap a lane's worker takes no new request:
 * it keeps driving the lane ({@link ServerLane#drive}) so finished responses are still written,
 * while new requests wait in the engine's queue, which answers 503 once it is full
 * ({@code max-queued}). That is backpressure instead of an ever-growing number of threads.
 */
public final class InFlightLimit {

    // Short: a permit freed by another lane's request does not wake this lane's drive.
    private static final long DRIVE_MILLIS = 2;

    private final Semaphore permits;

    /** {@code max} requests in flight; 0 means no limit. */
    public InFlightLimit(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max-in-flight must be >= 0: " + max);
        }
        this.permits = max == 0 ? null : new Semaphore(max);
    }

    /** Waits for room, writing {@code lane}'s responses meanwhile; false once the server has stopped. */
    public boolean acquire(ServerLane lane) {
        if (permits == null) {
            return true;
        }
        while (!permits.tryAcquire()) {
            if (!lane.drive(DRIVE_MILLIS)) {
                return false;
            }
        }
        return true;
    }

    /** One request acquired with {@link #acquire} is finished. */
    public void release() {
        if (permits != null) {
            permits.release();
        }
    }
}
