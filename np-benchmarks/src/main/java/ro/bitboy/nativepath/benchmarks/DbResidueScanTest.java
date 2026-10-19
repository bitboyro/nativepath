package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.internal.ForeignBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Scans this process's memory (JVM heap, native heap, stacks) for the np-db canary values while
 * and after they pass through the NativePath PostgreSQL driver. The real values exist only in the
 * files and the container created by np-benchmarks/pg_test_db.sh; this class holds them XOR-masked
 * and the scanner compares on the fly. macOS only.
 *
 * Usage: DbResidueScanTest &lt;scenario&gt; [dir]   (dir defaults to target/pg; NP_PG_SSLMODE sets sslmode, default prefer)
 *   db-control   card read with getString on a non-secret connection (must be FOUND: the scanner works)
 *   db-password  password from passwordFile: gone once authentication ends, with the connection still open
 *   db-secret    secret column written from a NativeSecret and read back as one: gone after close,
 *                with the connection still open (protocol buffers are wiped after every statement)
 */
public class DbResidueScanTest {

    private static final byte MASK = 0x5A;
    // "NP-PG-CANARY-PASSWORD-3e9a71c45d" and "NP-PG-CANARY-CARD-5f2b8d0c7e14" XOR 0x5A
    private static final byte[] PASSWORD_MASKED  = { 20, 10, 119, 10, 29, 119, 25, 27, 20, 27, 8, 3, 119, 10, 27, 9, 9, 13, 21, 8, 30, 119, 105, 63, 99, 59, 109, 107, 57, 110, 111, 62 };
    private static final byte[] CARD_MASKED  = { 20, 10, 119, 10, 29, 119, 25, 27, 20, 27, 8, 3, 119, 25, 27, 8, 30, 119, 111, 60, 104, 56, 98, 62, 106, 57, 109, 63, 107, 110 };

    private static boolean failed = false;
    private static Path dir;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "db-secret";
        dir = Path.of(args.length > 1 ? args[1] : "target/pg").toAbsolutePath();
        Class.forName("ro.bitboy.nativepath.db.NativePathDriver");
        switch (scenario) {
            case "db-control" -> control();
            case "db-password" -> password();
            case "db-secret" -> secretColumn();
            default -> throw new IllegalArgumentException(scenario);
        }
        System.out.println(failed ? "RESULT: FAIL" : "RESULT: PASS");
        System.exit(failed ? 1 : 0);
    }

    private static String url(String extra) {
        return "jdbc:nativepath:postgresql://" + System.getenv().getOrDefault("NP_PG_HOST", "127.0.0.1") + ":" + System.getenv().getOrDefault("NP_PG_PORT", "55432") + "/np?user=np"
                + "&sslmode=" + System.getenv().getOrDefault("NP_PG_SSLMODE", "prefer") + extra;
    }

    private static Properties passwordFile() {
        Properties p = new Properties();
        p.setProperty("passwordFile", dir.resolve("password.txt").toString());
        return p;
    }

    private static void insertCard(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS np_scan; CREATE TABLE np_scan (name text, card text)");
        }
        try (NativeSecret card = NativeSecret.fromFile(dir.resolve("card.txt"));
             PreparedStatement ps = c.prepareStatement("INSERT INTO np_scan VALUES (?, ?)")) {
            ps.setString(1, "alice");
            ps.setObject(2, card);
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- scenarios

    private static void control() throws Exception {
        try (Connection c = DriverManager.getConnection(url(""), passwordFile())) {
            insertCard(c);
            String leaked;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT card FROM np_scan")) {
                rs.next();
                leaked = rs.getString(1); // deliberate heap copy: no secretColumns on this connection
            }
            expectAtLeast("card copied to a Java String (control)", scan(CARD_MASKED), 1);
            leaked = null;
        }
    }

    private static void password() throws Exception {
        try (NativeSecret control = NativeSecret.fromFile(dir.resolve("password.txt"))) {
            expectAtLeast("password alive in a NativeSecret (control)", scan(PASSWORD_MASKED), 1);
        }
        expectExactly("after the control secret is closed", scan(PASSWORD_MASKED), 0);
        try (Connection c = DriverManager.getConnection(url(""), passwordFile())) {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT current_user")) {
                rs.next();
            }
            expectExactly("after SCRAM authentication, connection open", scan(PASSWORD_MASKED), 0);
        }
        expectExactly("after connection close", scan(PASSWORD_MASKED), 0);
    }

    private static void secretColumn() throws Exception {
        try (Connection c = DriverManager.getConnection(url("&secretColumns=card"), passwordFile())) {
            insertCard(c);
            expectExactly("after INSERT from a NativeSecret, connection open", scan(CARD_MASKED), 0);
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT name, card FROM np_scan")) {
                rs.next();
                try (NativeSecret card = rs.getObject("card", NativeSecret.class)) {
                    expectAtLeast("result rows + NativeSecret alive (control)", scan(CARD_MASKED), 1);
                    expectThrows("getString on the secret column", () -> rs.getString("card"));
                }
            }
            expectExactly("after result, statement and secret closed, connection open", scan(CARD_MASKED), 0);
            try (Statement s = c.createStatement()) {
                s.execute("DROP TABLE np_scan");
            }
        }
        expectExactly("after connection close", scan(CARD_MASKED), 0);
    }

    // ---------------------------------------------------------------- helpers (as in ResidueScanTest)

    private static long scan(byte[] masked) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment m = a.allocate(masked.length);
            MemorySegment.copy(MemorySegment.ofArray(masked), 0, m, 0, masked.length);
            long n = ForeignBridge.scanMemory(m, masked.length, MASK);
            if (n > 0 && n != -1) {
                MemorySegment hits = a.allocate(8L * 16);
                long k = ForeignBridge.scanHits(hits, 16);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < k; i++) sb.append(String.format("0x%x ", hits.getAtIndex(ValueLayout.JAVA_LONG, i)));
                System.out.println("      match addresses: " + sb);
            }
            return n;
        }
    }

    private static void expectExactly(String what, long got, long want) {
        boolean ok = got == want;
        System.out.printf("   [%s] %-62s found %d (expected %d)%n", ok ? "ok" : "FAIL", what, got, want);
        if (!ok) failed = true;
    }

    private static void expectAtLeast(String what, long got, long min) {
        boolean ok = got >= min && got != -1;
        System.out.printf("   [%s] %-62s found %d (expected >= %d)%n", ok ? "ok" : "FAIL", what, got, min);
        if (!ok) failed = true;
    }

    private static void expectThrows(String what, ThrowingRunnable r) {
        try {
            r.run();
            System.out.printf("   [FAIL] %-62s did not throw%n", what);
            failed = true;
        } catch (Exception e) {
            System.out.printf("   [ok] %-62s refused: %s%n", what, e.getClass().getSimpleName());
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
