package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.SecretSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import ro.bitboy.nativepath.db.NativePathDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * pgjdbc vs the NativePath driver on the same PostgreSQL, through the same HikariCP pool, running the
 * same SQL. Both use their defaults otherwise: pgjdbc switches to a reused server-side prepared
 * statement after five executions (prepareThreshold=5); NativePath parses every statement (no
 * statement cache yet), so small repeated queries are expected to favour pgjdbc.
 *
 * <p>pgjdbc needs the password as a heap String; NativePath reads it from the file into native memory.
 *
 * <p>Needs the database from {@code np-benchmarks/pg_test_db.sh}. Run with {@code np-benchmarks/scripts/db_bench.sh},
 * which uses the release native library and {@code -prof gc} (JVM allocation per operation).
 */
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 2, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@State(Scope.Benchmark)
public class DbDriverBenchmark {

    static final int ITEMS = 10_000;
    static final int BIG_BYTES = 1 << 20;
    static final int BATCH = 1_000;

    @Param({"pgjdbc", "nativepath"})
    public String driver;

    /** TLS without certificate verification (sslmode=require) for both drivers, or plaintext. */
    @Param({"false", "true"})
    public boolean tls;

    private HikariDataSource pool;
    private String port;
    private Path dir;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        dir = Path.of(System.getenv().getOrDefault("NP_PG_DIR", "target/pg")).toAbsolutePath();
        port = System.getenv().getOrDefault("NP_PG_PORT", "55432");
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("bench-" + driver);
        cfg.setMaximumPoolSize(16);
        cfg.setMinimumIdle(16);
        if (driver.equals("pgjdbc")) {
            cfg.setJdbcUrl(pgjdbcUrl());
            cfg.setUsername("np");
            cfg.setPassword(Files.readString(dir.resolve("password.txt")).strip());
        } else {
            cfg.setDataSource(nativepath());
        }
        pool = new HikariDataSource(cfg);
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS bench_items (id int primary key, name text not null, "
                    + "price numeric(10,2) not null, created timestamptz not null)");
            s.execute("CREATE TABLE IF NOT EXISTS bench_big (id int primary key, body text not null)");
            s.execute("CREATE TABLE IF NOT EXISTS bench_inserts (id bigserial primary key, name text, price numeric(10,2))");
            s.execute("INSERT INTO bench_items SELECT g, 'item-' || g, (g % 1000) / 10.0, now() - g * interval '1 minute' "
                    + "FROM generate_series(1, " + ITEMS + ") g ON CONFLICT DO NOTHING");
            s.execute("INSERT INTO bench_big VALUES (1, repeat('x', " + BIG_BYTES + ")) ON CONFLICT DO NOTHING");
        }
    }

    private String pgjdbcUrl() {
        return "jdbc:postgresql://" + System.getenv().getOrDefault("NP_PG_HOST", "127.0.0.1") + ":" + port + "/np?sslmode=" + (tls ? "require" : "disable");
    }

    private NativePathDataSource nativepath() {
        NativePathDataSource ds = new NativePathDataSource();
        ds.setUrl("jdbc:nativepath:postgresql://" + System.getenv().getOrDefault("NP_PG_HOST", "127.0.0.1") + ":" + port + "/np?sslmode=" + (tls ? "require" : "disable"));
        ds.setUser("np");
        ds.setPasswordSource(SecretSource.file(dir.resolve("password.txt")));
        return ds;
    }

    @TearDown(Level.Iteration)
    public void truncateInserts() throws SQLException {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE bench_inserts");
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
    }

    /** The most common web-application query: one row by primary key. */
    @Benchmark
    public void selectByPk(Blackhole bh) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, name, price, created FROM bench_items WHERE id = ?")) {
            ps.setInt(1, ThreadLocalRandom.current().nextInt(1, ITEMS + 1));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                bh.consume(rs.getInt(1));
                bh.consume(rs.getString(2));
                bh.consume(rs.getBigDecimal(3));
                bh.consume(rs.getTimestamp(4));
            }
        }
    }

    @Benchmark
    public void insertOne(Blackhole bh) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO bench_inserts(name, price) VALUES (?, ?)")) {
            ps.setString(1, "row");
            ps.setBigDecimal(2, new BigDecimal("9.99"));
            bh.consume(ps.executeUpdate());
        }
    }

    /** {@value #BATCH} inserts in one batch (how Hibernate writes with batching on). */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void batchInsert1000(Blackhole bh) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO bench_inserts(name, price) VALUES (?, ?)")) {
            c.setAutoCommit(false);
            for (int i = 0; i < BATCH; i++) {
                ps.setString(1, "batch-" + i);
                ps.setBigDecimal(2, BigDecimal.valueOf(i, 2));
                ps.addBatch();
            }
            bh.consume(ps.executeBatch());
            c.commit();
            c.setAutoCommit(true);
        }
    }

    /** Reads all {@value #ITEMS} rows, every column. */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void select10kRows(Blackhole bh) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, name, price, created FROM bench_items");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                bh.consume(rs.getInt(1));
                bh.consume(rs.getString(2));
                bh.consume(rs.getBigDecimal(3));
                bh.consume(rs.getTimestamp(4));
            }
        }
    }

    /** One 1 MiB text value, read into a String by both drivers. */
    @Benchmark
    public void readLargeText(Blackhole bh) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT body FROM bench_big WHERE id = 1");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            bh.consume(rs.getString(1));
        }
    }

    /** A new physical connection (socket, TLS when on, SCRAM), outside the pool. */
    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void openConnection(Blackhole bh) throws Exception {
        Connection c;
        if (driver.equals("pgjdbc")) {
            Properties p = new Properties();
            p.setProperty("user", "np");
            p.setProperty("password", Files.readString(dir.resolve("password.txt")).strip());
            c = new org.postgresql.Driver().connect(pgjdbcUrl(), p);
        } else {
            c = nativepath().getConnection();
        }
        try (c) {
            bh.consume(c.isValid(1));
        }
    }
}
