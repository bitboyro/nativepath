package ro.bitboy.nativepath.benchmarks;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ro.bitboy.nativepath.SecretSource;
import ro.bitboy.nativepath.db.NativePathDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sustained SQL load: N threads run one scenario for a fixed time through one HikariCP pool, with
 * pgjdbc or the NativePath driver, and report once a second (throughput, latency of that second,
 * errors, connections in use, rows read) as events on stderr ({@code NPEV} lines, collected by
 * np-bench), then a result for the measured window. The JMH benchmark (DbDriverBenchmark) measures
 * the cost of one operation; this one shows what a driver does under steady pressure.
 *
 * <p>Usage: {@code DbLoadTest <pgjdbc|nativepath> <select_pk|insert|rows10000|batch1000> <seconds> <threads> <pool> [tls]}.
 * Needs the database from np-benchmarks/pg_test_db.sh ({@code NP_PG_DIR}, {@code NP_PG_PORT}).
 */
public class DbLoadTest {

    static final int ITEMS = 10_000, BATCH = 1_000, WARMUP_S = 2;

    public static void main(String[] a) throws Exception {
        String driver = a[0], scenario = a[1];
        int seconds = Integer.parseInt(a[2]), threads = Integer.parseInt(a[3]), poolSize = Integer.parseInt(a[4]);
        boolean tls = a.length > 5 && a[5].equals("tls");
        Path dir = Path.of(System.getenv().getOrDefault("NP_PG_DIR", "target/pg")).toAbsolutePath();
        String port = System.getenv().getOrDefault("NP_PG_PORT", "55432");
        String mode = tls ? "require" : "disable";
        String host = System.getenv().getOrDefault("NP_PG_HOST", "127.0.0.1");

        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("load-" + driver);
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(poolSize);
        if (driver.equals("pgjdbc")) {
            cfg.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/np?sslmode=" + mode);
            cfg.setUsername("np");
            cfg.setPassword(Files.readString(dir.resolve("password.txt")).strip());
        } else {
            NativePathDataSource ds = new NativePathDataSource();
            ds.setUrl("jdbc:nativepath:postgresql://" + host + ":" + port + "/np?sslmode=" + mode);
            ds.setUser("np");
            ds.setPasswordSource(SecretSource.file(dir.resolve("password.txt")));
            cfg.setDataSource(ds);
        }
        try (HikariDataSource pool = new HikariDataSource(cfg)) {
            prepare(pool);
            run(pool, driver, scenario, seconds, threads, poolSize);
        }
    }

    static void prepare(HikariDataSource pool) throws Exception {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS bench_items (id int primary key, name text not null, "
                    + "price numeric(10,2) not null, created timestamptz not null)");
            s.execute("CREATE TABLE IF NOT EXISTS bench_inserts (id bigserial primary key, name text, price numeric(10,2))");
            s.execute("INSERT INTO bench_items SELECT g, 'item-' || g, (g % 1000) / 10.0, now() - g * interval '1 minute' "
                    + "FROM generate_series(1, " + ITEMS + ") g ON CONFLICT DO NOTHING");
            s.execute("TRUNCATE bench_inserts");
        }
    }

    /** One operation of the scenario; returns the rows it touched. */
    static long op(HikariDataSource pool, String scenario) throws Exception {
        switch (scenario) {
            case "select_pk":
                try (Connection c = pool.getConnection();
                     PreparedStatement ps = c.prepareStatement("SELECT id, name, price, created FROM bench_items WHERE id = ?")) {
                    ps.setInt(1, ThreadLocalRandom.current().nextInt(1, ITEMS + 1));
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next(); rs.getInt(1); rs.getString(2); rs.getBigDecimal(3); rs.getTimestamp(4);
                    }
                    return 1;
                }
            case "insert":
                try (Connection c = pool.getConnection();
                     PreparedStatement ps = c.prepareStatement("INSERT INTO bench_inserts(name, price) VALUES (?, ?)")) {
                    ps.setString(1, "row");
                    ps.setBigDecimal(2, new BigDecimal("9.99"));
                    return ps.executeUpdate();
                }
            case "rows10000":
                try (Connection c = pool.getConnection();
                     PreparedStatement ps = c.prepareStatement("SELECT id, name, price, created FROM bench_items")) {
                    long n = 0;
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) { rs.getInt(1); rs.getString(2); rs.getBigDecimal(3); rs.getTimestamp(4); n++; }
                    }
                    return n;
                }
            case "batch1000":
                try (Connection c = pool.getConnection();
                     PreparedStatement ps = c.prepareStatement("INSERT INTO bench_inserts(name, price) VALUES (?, ?)")) {
                    c.setAutoCommit(false);
                    for (int i = 0; i < BATCH; i++) { ps.setString(1, "row"); ps.setBigDecimal(2, new BigDecimal("9.99")); ps.addBatch(); }
                    ps.executeBatch();
                    c.commit();
                    return BATCH;
                }
            default:
                throw new IllegalArgumentException("unknown scenario " + scenario);
        }
    }

    /** Latency histogram in microseconds with 1% buckets: flat memory however long the run. */
    static final class Hist {
        private final long[] counts = new long[1700];
        private long total;
        void record(long us) {
            int i = (int) (Math.log(Math.max(us, 1)) / Math.log(1.01));
            counts[Math.min(i, counts.length - 1)]++;
            total++;
        }
        long pct(double p) {
            if (total == 0) return 0;
            long want = Math.min((long) (total * p / 100.0), total - 1), seen = 0;
            for (int i = 0; i < counts.length; i++) {
                seen += counts[i];
                if (seen > want) return Math.round(Math.pow(1.01, i));
            }
            return 0;
        }
        void reset() { java.util.Arrays.fill(counts, 0); total = 0; }
    }

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    static void emit(String type, String fields) {
        System.err.println("NPEV {\"t\":" + System.currentTimeMillis() + ",\"type\":\"" + type + "\",\"exp\":\"db\",\"src\":\"dbload\"," + fields + "}");
    }

    static void run(HikariDataSource pool, String driver, String scenario, int seconds, int threads, int poolSize) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong ops = new AtomicLong(), errors = new AtomicLong(), rows = new AtomicLong();
        Hist second = new Hist(), measured = new Hist();
        final Object lock = new Object();
        long start = System.nanoTime();
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = Thread.ofPlatform().name("load-" + i).start(() -> {
                String firstError = null;
                while (!stop.get()) {
                    long t0 = System.nanoTime();
                    try {
                        long n = op(pool, scenario);
                        long us = (System.nanoTime() - t0) / 1000;
                        ops.incrementAndGet(); rows.addAndGet(n);
                        synchronized (lock) {
                            second.record(us);
                            if ((System.nanoTime() - start) / 1_000_000_000L >= WARMUP_S) measured.record(us);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        if (firstError == null) { firstError = e.toString(); System.err.println("dbload: first error: " + firstError); }
                    }
                }
            });
        }
        long lastOps = 0, lastErr = 0, lastRows = 0, measuredOps = 0, measuredErr = 0, measuredRows = 0;
        var mx = pool.getHikariPoolMXBean();
        for (int sec = 1; sec <= seconds + WARMUP_S; sec++) {
            Thread.sleep(Math.max(1, start / 1_000_000 + sec * 1000L - System.nanoTime() / 1_000_000));
            long o = ops.get(), e = errors.get(), r = rows.get();
            long p50, p99;
            synchronized (lock) {
                p50 = second.pct(50); p99 = second.pct(99);
                second.reset();
            }
            boolean warm = sec <= WARMUP_S;
            if (!warm) { measuredOps += o - lastOps; measuredErr += e - lastErr; measuredRows += r - lastRows; }
            emit("tick", "\"driver\":\"" + driver + "\",\"scenario\":\"" + scenario + "\",\"sec\":" + sec + ",\"phase\":\"" + (warm ? "warmup" : "measure")
                    + "\",\"threads\":" + threads + ",\"pool\":" + poolSize + ",\"ops_s\":" + (o - lastOps) + ",\"rows_s\":" + (r - lastRows)
                    + ",\"p50_us\":" + p50 + ",\"p99_us\":" + p99 + ",\"errors\":" + (e - lastErr) + ",\"pool_in_use\":" + mx.getActiveConnections()
                    + ",\"pool_waiting\":" + mx.getThreadsAwaitingConnection());
            lastOps = o; lastErr = e; lastRows = r;
        }
        stop.set(true);
        for (Thread w : workers) w.join(10_000);
        double s = seconds;
        emit("result", "\"driver\":\"" + driver + "\",\"scenario\":\"" + scenario + "\",\"seconds\":" + seconds + ",\"threads\":" + threads + ",\"pool\":" + poolSize
                + ",\"ops_s\":" + Math.round(measuredOps / s) + ",\"rows_s\":" + Math.round(measuredRows / s)
                + ",\"p50_us\":" + measured.pct(50) + ",\"p99_us\":" + measured.pct(99) + ",\"p999_us\":" + measured.pct(99.9)
                + ",\"errors\":" + measuredErr);
        System.out.printf("%-10s %-10s %,10.0f ops/s  p50 %d us  p99 %d us  errors %d%n", driver, scenario, measuredOps / s,
                measured.pct(50), measured.pct(99), measuredErr);
    }
}
