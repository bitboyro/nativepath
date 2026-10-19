package ro.bitboy.nativepath.db;

import org.junit.jupiter.api.Test;
import ro.bitboy.nativepath.db.pg.PgConnection;


import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLS, channel binding, query timeouts and cancel, against the database from
 * {@code np-benchmarks/pg_test_db.sh} (TLS on). Skipped when {@code NP_PG_DIR} is not set.
 */
class PostgresTlsAndCancelTest {

    private static String ca() {
        java.nio.file.Path ca = PostgresDriverTest.dir().resolve("ca.pem");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(ca), "ca.pem missing; rerun np-benchmarks/pg_test_db.sh");
        return ca.toString();
    }

    private static Connection open(String host, String params) throws SQLException {
        String port = System.getenv().getOrDefault("NP_PG_PORT", "55432");
        return DriverManager.getConnection("jdbc:nativepath:postgresql://" + host + ":" + port + "/np?user=np" + params,
                PostgresDriverTest.passwordFile());
    }

    private static void assertWorks(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void verifyFullWithChannelBinding() throws SQLException {
        try (Connection c = open("localhost", "&sslmode=verify-full&sslrootcert=" + ca() + "&channelBinding=require")) {
            PgConnection pg = c.unwrap(PgConnection.class);
            assertTrue(pg.isEncrypted());
            assertTrue(pg.isChannelBound(), "SCRAM-SHA-256-PLUS expected over TLS");
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
                rs.next();
                assertTrue(rs.getBoolean(1), "the server sees an SSL connection");
            }
        }
    }

    @Test
    void requireWithoutRootCertIsStillProtectedByChannelBinding() throws SQLException {
        try (Connection c = open("127.0.0.1", "&sslmode=require&channelBinding=require")) {
            PgConnection pg = c.unwrap(PgConnection.class);
            assertTrue(pg.isEncrypted());
            assertTrue(pg.isChannelBound());
            assertWorks(c);
        }
    }

    @Test
    void verifyCaAndDefaultPreferAndDisable() throws SQLException {
        try (Connection c = open("127.0.0.1", "&sslmode=verify-ca&sslrootcert=" + ca())) {
            assertTrue(c.unwrap(PgConnection.class).isEncrypted());
        }
        try (Connection c = open("127.0.0.1", "")) {
            assertTrue(c.unwrap(PgConnection.class).isEncrypted(), "sslmode=prefer uses TLS when offered");
        }
        try (Connection c = open("127.0.0.1", "&sslmode=disable")) {
            assertFalse(c.unwrap(PgConnection.class).isEncrypted());
            assertFalse(c.unwrap(PgConnection.class).isChannelBound());
            assertWorks(c);
        }
        SQLException e = assertThrows(SQLException.class,
                () -> open("127.0.0.1", "&sslmode=disable&channelBinding=require"));
        assertTrue(e.getMessage().contains("channel_binding"), e.getMessage());
    }

    @Test
    void wrongCaIsRejected() throws Exception {
        java.nio.file.Path other = Files.createTempFile("np-other-ca", ".pem");
        try {
            // Any valid certificate that did not sign the server's: the server certificate itself.
            Files.writeString(other, Files.readString(PostgresDriverTest.dir().resolve("certs/server.crt")));
            SQLException e = assertThrows(SQLException.class,
                    () -> open("localhost", "&sslmode=verify-full&sslrootcert=" + other));
            assertEquals("08001", e.getSQLState());
        } finally {
            Files.deleteIfExists(other);
        }
    }

    @Test
    void queryTimeoutCancelsOnTheServerAndKeepsTheConnection() throws SQLException {
        for (String tls : new String[] {"&sslmode=disable", "&sslmode=verify-full&sslrootcert=" + ca()}) {
            try (Connection c = open("localhost", tls); Statement s = c.createStatement()) {
                s.setQueryTimeout(1);
                long start = System.nanoTime();
                SQLTimeoutException e = assertThrows(SQLTimeoutException.class, () -> s.executeQuery("SELECT pg_sleep(30)"));
                long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                assertEquals("57014", e.getSQLState());
                assertTrue(ms < 5000, "cancelled after " + ms + " ms");
                assertTrue(c.isValid(2), "connection must survive the cancel");
                assertWorks(c);
                // Quick statements under the same timeout are not cancelled late.
                for (int i = 0; i < 50; i++) {
                    try (ResultSet rs = s.executeQuery("SELECT " + i)) {
                        rs.next();
                    }
                }
            }
        }
    }

    @Test
    void queryTimeoutInsideATransaction() throws SQLException {
        try (Connection c = open("127.0.0.1", ""); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("CREATE TEMP TABLE t (v int)");
            s.setQueryTimeout(1);
            assertThrows(SQLTimeoutException.class, () -> s.execute("SELECT pg_sleep(30)"));
            c.rollback();
            s.setQueryTimeout(0);
            assertWorks(c);
            c.commit();
        }
    }

    @Test
    void statementCancelFromAnotherThread() throws Exception {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
        try (Connection c = open("127.0.0.1", ""); Statement s = c.createStatement()) {
            ex.schedule(() -> {
                s.cancel();
                return null;
            }, 500, TimeUnit.MILLISECONDS);
            SQLException e = assertThrows(SQLException.class, () -> s.execute("SELECT pg_sleep(30)"));
            assertEquals("57014", e.getSQLState());
            assertFalse(e instanceof SQLTimeoutException, "a manual cancel is not a timeout");
            assertWorks(c);
            s.cancel(); // nothing running: harmless
            assertWorks(c);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void observerSeesConnectsStatementsAndTimeouts() throws SQLException {
        java.util.List<Object> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        DbObserver obs = new DbObserver() {
            @Override
            public void connected(ConnectEvent e) {
                events.add(e);
            }

            @Override
            public void statementFinished(StatementEvent e) {
                events.add(e);
            }
        };
        java.util.Properties p = PostgresDriverTest.passwordFile();
        p.put("observer", obs);
        String port = System.getenv().getOrDefault("NP_PG_PORT", "55432");
        try (Connection c = DriverManager.getConnection("jdbc:nativepath:postgresql://localhost:" + port
                + "/np?user=np&sslmode=verify-full&sslrootcert=" + ca(), p);
             Statement s = c.createStatement()) {
            s.executeQuery("SELECT generate_series(1, 7)").close();
            s.setQueryTimeout(1);
            assertThrows(SQLTimeoutException.class, () -> s.execute("SELECT pg_sleep(30)"));
        }
        DbObserver.ConnectEvent connect = (DbObserver.ConnectEvent) events.get(0);
        assertTrue(connect.success() && connect.encrypted() && connect.channelBound());
        DbObserver.StatementEvent query = (DbObserver.StatementEvent) events.get(1);
        assertEquals(DbObserver.Kind.QUERY, query.kind());
        assertEquals(7, query.rowsReturned());
        DbObserver.StatementEvent timeout = (DbObserver.StatementEvent) events.get(2);
        assertEquals(DbObserver.Outcome.TIMEOUT, timeout.outcome());
        assertEquals("57014", timeout.sqlState());
    }

    @Test
    void abortCancelsAndCloses() throws Exception {
        Connection c = open("127.0.0.1", "");
        Statement s = c.createStatement();
        java.util.concurrent.ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Executors.newSingleThreadScheduledExecutor().schedule(() -> {
                c.abort(ex);
                return null;
            }, 300, TimeUnit.MILLISECONDS);
            SQLException e = assertThrows(SQLException.class, () -> s.execute("SELECT pg_sleep(30)"));
            assertInstanceOf(SQLException.class, e);
            ex.shutdown();
            assertTrue(ex.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(c.isClosed());
        } finally {
            ex.shutdownNow();
        }
    }
}
