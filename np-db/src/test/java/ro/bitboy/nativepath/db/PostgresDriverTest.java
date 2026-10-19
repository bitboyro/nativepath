package ro.bitboy.nativepath.db;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against the database from {@code np-benchmarks/pg_test_db.sh}; skipped when
 * {@code NP_PG_DIR} (the directory holding password.txt and card.txt) is not set.
 */
class PostgresDriverTest {

    private Connection conn;

    static Path dir() {
        String d = System.getenv("NP_PG_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(d != null, "NP_PG_DIR not set; start np-benchmarks/pg_test_db.sh");
        return Path.of(d);
    }

    static String url(String extra) {
        String port = System.getenv().getOrDefault("NP_PG_PORT", "55432");
        return "jdbc:nativepath:postgresql://127.0.0.1:" + port + "/np?user=np" + extra;
    }

    static Properties passwordFile() {
        Properties p = new Properties();
        p.setProperty("passwordFile", dir().resolve("password.txt").toString());
        return p;
    }

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection(url("&secretColumns=card"), passwordFile());
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS np_t; CREATE TABLE np_t (id serial primary key, name text unique, card text, amount numeric(12,2))");
        }
    }

    @AfterEach
    void close() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            if (!conn.getAutoCommit()) conn.rollback();
            try (Statement s = conn.createStatement()) {
                s.execute("DROP TABLE IF EXISTS np_t");
            }
            conn.close();
        }
    }

    @Test
    void typesRoundTrip() throws SQLException {
        UUID uuid = UUID.randomUUID();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ?::int AS i, ?::bigint AS l, ?::numeric AS n, ?::bool AS b, ? AS t, ?::bytea AS by, "
                        + "?::date AS d, ?::timestamp AS ts, ?::timestamptz AS tz, ?::uuid AS u, ?::text AS z, ?::float8 AS f")) {
            ps.setInt(1, 42);
            ps.setLong(2, Long.MAX_VALUE);
            ps.setBigDecimal(3, new BigDecimal("12345.678"));
            ps.setBoolean(4, true);
            ps.setString(5, "héllo 'quoted' ?");
            ps.setBytes(6, new byte[] {0, 1, (byte) 0xff});
            ps.setObject(7, LocalDate.of(2026, 10, 18));
            ps.setObject(8, LocalDateTime.of(2026, 10, 18, 12, 34, 56, 789_000_000));
            ps.setObject(9, OffsetDateTime.of(2026, 10, 18, 12, 0, 0, 0, ZoneOffset.ofHours(3)));
            ps.setObject(10, uuid);
            ps.setNull(11, Types.VARCHAR);
            ps.setDouble(12, 0.1);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(42, rs.getInt("i"));
                assertEquals(42, rs.getObject("i"));
                assertEquals(Long.MAX_VALUE, rs.getLong(2));
                assertEquals(new BigDecimal("12345.678"), rs.getBigDecimal("n"));
                assertTrue(rs.getBoolean("b"));
                assertEquals(Boolean.TRUE, rs.getObject("b"));
                assertEquals("héllo 'quoted' ?", rs.getString("t"));
                assertArrayEquals(new byte[] {0, 1, (byte) 0xff}, rs.getBytes("by"));
                assertEquals(LocalDate.of(2026, 10, 18), rs.getObject("d", LocalDate.class));
                assertEquals(LocalDateTime.of(2026, 10, 18, 12, 34, 56, 789_000_000), rs.getObject("ts", LocalDateTime.class));
                assertEquals(Timestamp.valueOf(LocalDateTime.of(2026, 10, 18, 12, 34, 56, 789_000_000)), rs.getTimestamp("ts"));
                assertEquals(OffsetDateTime.of(2026, 10, 18, 9, 0, 0, 0, ZoneOffset.UTC).toInstant(),
                        rs.getObject("tz", OffsetDateTime.class).toInstant());
                assertEquals(uuid, rs.getObject("u"));
                assertNull(rs.getString("z"));
                assertTrue(rs.wasNull());
                assertNull(rs.getObject("z", Integer.class));
                assertEquals(0, rs.getInt("z"));
                assertEquals(0.1, rs.getDouble("f"));
                ResultSetMetaData md = rs.getMetaData();
                assertEquals(12, md.getColumnCount());
                assertEquals("i", md.getColumnLabel(1));
                assertEquals(Types.INTEGER, md.getColumnType(1));
                assertEquals("numeric", md.getColumnTypeName(3));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void updatesKeysBatchesAndMultipleResults() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO np_t(name, amount) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "a");
            ps.setBigDecimal(2, new BigDecimal("1.50"));
            assertEquals(1, ps.executeUpdate());
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                assertEquals(1, keys.getInt("id"));
            }
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO np_t(name) VALUES (?)")) {
            for (String n : new String[] {"b", "c", "d"}) {
                ps.setString(1, n);
                ps.addBatch();
            }
            assertArrayEquals(new int[] {1, 1, 1}, ps.executeBatch());
        }
        try (Statement s = conn.createStatement()) {
            assertTrue(s.execute("SELECT count(*) FROM np_t; UPDATE np_t SET amount = 2; SELECT name FROM np_t ORDER BY id"));
            try (ResultSet rs = s.getResultSet()) {
                assertTrue(rs.next());
                assertEquals(4, rs.getLong(1));
            }
            assertFalse(s.getMoreResults());
            assertEquals(4, s.getUpdateCount());
            assertTrue(s.getMoreResults());
            ResultSet rs = s.getResultSet();
            int n = 0;
            while (rs.next()) n++;
            assertEquals(4, n);
            assertFalse(s.getMoreResults());
            assertEquals(-1, s.getUpdateCount());
        }
    }

    @Test
    void transactions() throws SQLException {
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("INSERT INTO np_t(name) VALUES ('rolled back')");
            conn.rollback();
            s.executeUpdate("INSERT INTO np_t(name) VALUES ('kept')");
            conn.commit();
            // A failed transaction cannot be committed.
            s.executeUpdate("INSERT INTO np_t(name) VALUES ('lost')");
            assertThrows(SQLException.class, () -> s.executeQuery("SELECT 1/0"));
            SQLException e = assertThrows(SQLException.class, conn::commit);
            assertEquals("25P02", e.getSQLState());
            conn.setAutoCommit(true);
            try (ResultSet rs = s.executeQuery("SELECT string_agg(name, ',') FROM np_t")) {
                rs.next();
                assertEquals("kept", rs.getString(1));
            }
        }
    }

    @Test
    void errorsHideDetailByDefaultAndKeepTheConnection() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.executeUpdate("INSERT INTO np_t(name) VALUES ('dup-value-123')");
            SQLException e = assertThrows(SQLIntegrityConstraintViolationException.class,
                    () -> s.executeUpdate("INSERT INTO np_t(name) VALUES ('dup-value-123')"));
            assertEquals("23505", e.getSQLState());
            assertFalse(e.getMessage().contains("dup-value-123"), e.getMessage());
            assertTrue(conn.isValid(2));
        }
        try (Connection c = DriverManager.getConnection(url("&errorDetail=true"), passwordFile());
             Statement s = c.createStatement()) {
            s.execute("CREATE TEMP TABLE u (v text unique); INSERT INTO u VALUES ('x-detail')");
            SQLException e = assertThrows(SQLException.class, () -> s.execute("INSERT INTO u VALUES ('x-detail')"));
            assertTrue(e.getMessage().contains("x-detail"), e.getMessage());
        }
    }

    @Test
    void secretColumnStaysNative() throws Exception {
        Path cardFile = dir().resolve("card.txt");
        try (NativeSecret card = NativeSecret.fromFile(cardFile);
             PreparedStatement ps = conn.prepareStatement("INSERT INTO np_t(name, card) VALUES (?, ?)")) {
            ps.setString(1, "alice");
            ps.setObject(2, card);
            assertEquals(1, ps.executeUpdate());
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, card FROM np_t WHERE name = ?")) {
            ps.setString(1, "alice");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("alice", rs.getString("name"));
                assertThrows(SQLException.class, () -> rs.getString("card"));
                assertThrows(SQLException.class, () -> rs.getBytes("card"));
                assertThrows(SQLException.class, () -> rs.getObject("card", String.class));
                try (NativeSecret fromDb = rs.getObject("card", NativeSecret.class);
                     NativeSecret expected = NativeSecret.fromFile(cardFile)) {
                    assertEquals(-1, fromDb.asMemorySegment().mismatch(expected.asMemorySegment()));
                    assertFalse(fromDb.heapCopiesAllowed());
                    assertThrows(IllegalStateException.class, fromDb::toByteArray);
                }
                Object generic = rs.getObject("card");
                assertInstanceOf(NativeSecret.class, generic);
                ((NativeSecret) generic).close();
            }
        }
    }

    @Test
    void passwordFormsAndFailures() throws Exception {
        Path pw = dir().resolve("password.txt");
        Properties p = new Properties();
        p.put("password", SecretSource.file(pw));
        try (Connection c = DriverManager.getConnection(url(""), p)) {
            assertTrue(c.isValid(1));
            assertEquals("PostgreSQL", c.getMetaData().getDatabaseProductName());
            assertEquals(17, c.getMetaData().getDatabaseMajorVersion());
            assertEquals("public", c.getSchema());
        }
        try (NativeSecret secret = NativeSecret.fromFile(pw)) {
            Properties q = new Properties();
            q.put("password", secret);
            try (Connection c = DriverManager.getConnection(url(""), q)) {
                assertTrue(c.isValid(1));
            }
            assertFalse(secret.isClosed(), "a caller-owned NativeSecret is not closed by the driver");
        }
        Properties wrong = new Properties();
        wrong.setProperty("password", "wrong");
        SQLException e = assertThrows(SQLInvalidAuthorizationSpecException.class, () -> DriverManager.getConnection(url(""), wrong));
        assertEquals("28P01", e.getSQLState());
        try (Connection c = DriverManager.getConnection(url("&sslmode=require"), passwordFile())) {
            assertTrue(c.unwrap(ro.bitboy.nativepath.db.pg.PgConnection.class).isEncrypted());
        }
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> DriverManager.getConnection(url("&sslmode=require&sslcert=/client.pem"), passwordFile()));
    }

    @Test
    void closedObjectsRefuseUse() throws SQLException {
        Statement s = conn.createStatement();
        ResultSet rs = s.executeQuery("SELECT 1");
        s.close();
        assertTrue(rs.isClosed());
        assertThrows(SQLException.class, rs::next);
        conn.close();
        assertFalse(conn.isValid(1));
        assertThrows(SQLException.class, conn::createStatement);
    }

    @Test
    void manyRows() throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT g, md5(g::text) FROM generate_series(1, 20000) g")) {
            long sum = 0;
            while (rs.next()) sum += rs.getInt(1);
            assertEquals(20000L * 20001 / 2, sum);
        }
    }

    @Test
    void secretOfLengthMatches() throws Exception {
        // A NativeSecret parameter is sent as its exact bytes (no trailing newline from the file).
        try (NativeSecret card = NativeSecret.fromFile(dir().resolve("card.txt"));
             PreparedStatement ps = conn.prepareStatement("SELECT octet_length(?)")) {
            ps.setObject(1, card);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(card.length(), rs.getLong(1));
            }
        }
    }
}
