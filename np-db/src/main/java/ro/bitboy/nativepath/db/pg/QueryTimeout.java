package ro.bitboy.nativepath.db.pg;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Cancels a statement on the server when it outlives its timeout, leaving the connection usable.
 *
 * <p>A cancel must never hit the next statement. {@link #close()} therefore waits for a cancel that
 * is already being sent; the native cancel returns only once the server has taken the request, and
 * PostgreSQL ignores a cancel that reaches an idle connection. So when {@code close()} returns, any
 * cancel has either hit this statement or been ignored.
 */
final class QueryTimeout implements AutoCloseable {

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(daemon("nativepath-query-timeout"));
    // Sending a cancel opens a connection, which can be slow; do it off the timer thread so one
    // slow cancel does not delay other timeouts.
    private static final ExecutorService CANCELLERS = Executors.newCachedThreadPool(daemon("nativepath-cancel"));

    private final Runnable cancel;
    private volatile ScheduledFuture<?> timer;
    private volatile boolean fired;
    private boolean done;

    private QueryTimeout(Runnable cancel) {
        this.cancel = cancel;
    }

    /** Starts the clock: after {@code millis}, {@code cancel} runs unless {@link #close()} came first. */
    static QueryTimeout start(long millis, Runnable cancel) {
        QueryTimeout t = new QueryTimeout(cancel);
        t.timer = TIMER.schedule(() -> CANCELLERS.execute(t::fire), millis, TimeUnit.MILLISECONDS);
        return t;
    }

    private synchronized void fire() {
        if (done) return;
        fired = true;
        cancel.run();
    }

    /** True if the timeout expired and a cancel was sent. */
    boolean fired() {
        return fired;
    }

    @Override
    public void close() {
        timer.cancel(false);
        synchronized (this) {
            done = true; // waits here while fire() is sending a cancel
        }
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
