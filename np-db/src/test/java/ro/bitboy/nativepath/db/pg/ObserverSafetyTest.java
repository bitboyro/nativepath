package ro.bitboy.nativepath.db.pg;

import org.junit.jupiter.api.Test;
import ro.bitboy.nativepath.db.DbObserver;

import java.sql.SQLException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObserverSafetyTest {

    @Test
    void aThrowingObserverNeverReachesTheApplication() {
        DbObserver broken = new DbObserver() {
            @Override
            public void connected(ConnectEvent event) {
                throw new IllegalStateException("metrics backend down");
            }
        };
        assertDoesNotThrow(() -> PgConnection.observe(broken, o -> o.connected(new DbObserver.ConnectEvent(1, true, null, false, false))));
        assertDoesNotThrow(() -> PgConnection.observe(null, o -> { throw new AssertionError("not called"); }));
    }

    @Test
    void observerComesFromProperties() throws SQLException {
        DbObserver obs = new DbObserver() {};
        Properties p = new Properties();
        p.put("observer", obs);
        assertSame(obs, PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u", p).observer());
        p.put("observer", "not an observer");
        assertThrows(SQLException.class, () -> PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u", p));
    }
}
