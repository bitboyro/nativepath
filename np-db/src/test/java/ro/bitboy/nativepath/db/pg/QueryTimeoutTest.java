package ro.bitboy.nativepath.db.pg;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTimeoutTest {

    @Test
    void firesAfterTheTimeout() throws InterruptedException {
        CountDownLatch cancelled = new CountDownLatch(1);
        QueryTimeout t = QueryTimeout.start(50, cancelled::countDown);
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        assertTrue(t.fired());
        t.close();
    }

    @Test
    void doesNotFireWhenTheStatementFinishesFirst() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        QueryTimeout t = QueryTimeout.start(100, calls::incrementAndGet);
        t.close();
        Thread.sleep(300);
        assertEquals(0, calls.get());
        assertFalse(t.fired());
    }

    @Test
    void closeWaitsForACancelInFlight() throws InterruptedException {
        // If the statement finishes while the cancel is being sent, close() must not return before
        // the cancel is done; otherwise the cancel could reach the next statement.
        CountDownLatch sending = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean();
        QueryTimeout t = QueryTimeout.start(10, () -> {
            sending.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.set(true);
        });
        assertTrue(sending.await(2, TimeUnit.SECONDS));
        t.close();
        assertTrue(finished.get(), "close() returned while the cancel was still being sent");
    }

    @Test
    void aSlowCancelDoesNotDelayOtherTimeouts() throws InterruptedException {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        QueryTimeout slow = QueryTimeout.start(10, () -> {
            slowStarted.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(slowStarted.await(2, TimeUnit.SECONDS));
        CountDownLatch fast = new CountDownLatch(1);
        QueryTimeout other = QueryTimeout.start(20, fast::countDown);
        assertTrue(fast.await(2, TimeUnit.SECONDS), "second timeout was blocked by the first cancel");
        release.countDown();
        slow.close();
        other.close();
    }
}
