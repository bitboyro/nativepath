package ro.bitboy.nativepath.db.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import ro.bitboy.nativepath.db.DbObserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativePathDbMetricsTest {

    @Test
    void connectAndStatementEventsBecomeMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NativePathDbMetrics m = new NativePathDbMetrics(registry, "orders");
        m.connected(new DbObserver.ConnectEvent(2_000_000, true, null, true, true));
        m.connected(new DbObserver.ConnectEvent(1_000_000, false, "28P01", false, false));
        m.statementFinished(new DbObserver.StatementEvent(DbObserver.Kind.QUERY, 500_000, DbObserver.Outcome.SUCCESS, null, 25, 0, 1));
        m.statementFinished(new DbObserver.StatementEvent(DbObserver.Kind.UPDATE, 300_000, DbObserver.Outcome.SUCCESS, null, 0, 3, 0));
        m.statementFinished(new DbObserver.StatementEvent(DbObserver.Kind.QUERY, 1_000_000_000, DbObserver.Outcome.TIMEOUT, "57014", 0, 0, 0));
        m.statementFinished(new DbObserver.StatementEvent(DbObserver.Kind.UPDATE, 100_000, DbObserver.Outcome.ERROR, "23505", 0, 0, 2));

        assertEquals(1, registry.get("nativepath.db.connect").tags("name", "orders", "outcome", "success", "tls", "true", "channel_binding", "true").timer().count());
        assertEquals(1, registry.get("nativepath.db.connect").tags("outcome", "error", "tls", "false").timer().count());
        assertEquals(1, registry.get("nativepath.db.statements").tags("kind", "query", "outcome", "success", "error", "none").timer().count());
        assertEquals(1, registry.get("nativepath.db.statements").tags("kind", "query", "outcome", "timeout", "error", "57").timer().count());
        assertEquals(1, registry.get("nativepath.db.statements").tags("kind", "update", "outcome", "error", "error", "23").timer().count());
        assertEquals(25, registry.get("nativepath.db.rows").tags("direction", "returned").counter().count());
        assertEquals(3, registry.get("nativepath.db.rows").tags("direction", "affected").counter().count());
        assertEquals(3, registry.get("nativepath.db.secret.parameters").counter().count());
    }
}
