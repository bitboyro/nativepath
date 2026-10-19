package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathRequest;
import ro.bitboy.nativepath.NativePathResponse;
import ro.bitboy.nativepath.NativePathStream;
import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretJson;
import ro.bitboy.nativepath.SecretSource;
import ro.bitboy.nativepath.VaultSecretSource;
import ro.bitboy.nativepath.internal.ForeignBridge;
import ro.bitboy.nativepath.spring.NativePathSecretClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Set;

/**
 * Scans this process's own memory (JVM heap, Rust heap, stacks, everything) for known secret
 * values after requests complete. The real values live only in a separate Python server
 * (np-benchmarks/secret_server.py); this class holds them XOR-masked and the scanner compares on
 * the fly, so the plain text is never assembled here. macOS only.
 *
 * Usage: ResidueScanTest &lt;scenario&gt;
 *   heap-control   default client, body copied to a Java String (must be FOUND: proves the scanner works)
 *   native-only    default client, body read from native memory only
 *   pooled-big     default (pooled) client, 300 KB body with the value in the middle, consumed natively
 *   body-headers   secret mode: secret POST body out, secret Set-Cookie header in
 *   from-file      NativeSecret.fromFile: a secret read from a file leaves no copy after close
 *   body-template  SecretBody assembling a JSON body from a secret leaves no copy after close
 *   keychain       secret mode: a macOS keychain item (created by the server via `security`) sent as a header
 *   vault          secret mode: VaultSecretSource reads a KV v2 field, token from a file source
 *   facade         NativePath facade: bearer from a file source, fetchField of the response token
 *   secret-stream  secret mode: secret request header, streamed body, SecretJson extraction
 *   spring-secret  secret mode through NativePathSecretClient + Jackson into a typed object
 *   guards         secret mode must refuse every heap-copying accessor
 */
public class ResidueScanTest {

    private static final byte MASK = 0x5A;
    // "NATIVEPATH-CANARY-RESPONSE-..." and "NATIVEPATH-CANARY-REQUEST-..." XOR 0x5A
    private static final byte[] RESP_MASKED  = { 20, 27, 14, 19, 12, 31, 10, 27, 14, 18, 119, 25, 27, 20, 27, 8, 3, 119, 8, 31, 9, 10, 21, 20, 9, 31, 119, 109, 60, 105, 59, 99, 107, 57, 104, 62, 110, 63, 98, 111, 56, 108, 106 };
    private static final byte[] REQ_MASKED  = { 20, 27, 14, 19, 12, 31, 10, 27, 14, 18, 119, 25, 27, 20, 27, 8, 3, 119, 8, 31, 11, 15, 31, 9, 14, 119, 56, 107, 57, 106, 62, 63, 110, 104, 59, 99, 60, 105, 109, 63, 111, 98 };
    private static final int PORT = 49998;
    private static final String KEYCHAIN_SERVICE = "nativepath-residue-scan";

    private static boolean failed = false;

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "secret-stream";
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                "python3", "np-benchmarks/secret_server.py", String.valueOf(PORT), "target/secret-file.txt"));
        if (scenario.equals("keychain")) cmd.add(KEYCHAIN_SERVICE);
        Process server = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(server.getInputStream()));
        if (!"READY".equals(r.readLine())) throw new IllegalStateException("server did not start");
        String base = "http://127.0.0.1:" + PORT;
        try {
            switch (scenario) {
                case "heap-control" -> heapControl(base);
                case "native-only" -> nativeOnly(base);
                case "pooled-big" -> pooledBig(base);
                case "from-file" -> fromFile();
                case "body-template" -> bodyTemplate();
                case "body-headers" -> bodyAndHeaders(base);
                case "keychain" -> keychain(base);
                case "vault" -> vault(base);
                case "facade" -> facade(base);
                case "secret-stream" -> secretStream(base);
                case "spring-secret" -> springSecret(base);
                case "guards" -> guards(base);
                default -> throw new IllegalArgumentException(scenario);
            }
        } finally {
            server.destroy();
            if (scenario.equals("keychain")) {
                new ProcessBuilder("security", "delete-generic-password", "-s", KEYCHAIN_SERVICE, "-a", "scan")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor();
            }
        }
        System.out.println(failed ? "RESULT: FAIL" : "RESULT: PASS");
        System.exit(failed ? 1 : 0);
    }

    // ---------------------------------------------------------------- scenarios

    private static void heapControl(String base) throws Exception {
        try (NativePathHttpClient c = NativePathHttpClient.create()) {
            NativePathResponse resp = c.get(base + "/plain");
            String leaked = resp.asString();        // deliberate heap copy
            resp.close();
            leaked = null;
            expectAtLeast("after heap copy (control)", scan(RESP_MASKED), 1);
        }
    }

    private static void nativeOnly(String base) throws Exception {
        try (NativePathHttpClient c = NativePathHttpClient.create()) {
            try (NativePathResponse resp = c.get(base + "/plain")) {
                expectAtLeast("body alive in native memory (control)", scan(RESP_MASKED), 1);
            }
            expectExactly("after close, client open", scan(RESP_MASKED), 0);
        }
        Thread.sleep(300);
        expectExactly("after client close", scan(RESP_MASKED), 0);
    }

    private static void pooledBig(String base) throws Exception {
        try (NativePathHttpClient c = NativePathHttpClient.create()) {
            for (int i = 0; i < 3; i++) {
                try (NativePathStream s = c.getStream(base + "/big")) {
                    MemorySegment seg;
                    long total = 0;
                    while ((seg = s.nextChunk()) != null) total += seg.byteSize();
                    if (total != 300000 + RESP_MASKED.length) fail("unexpected body size " + total);
                }
            }
            Thread.sleep(300);
            expectExactly("after 3 big pooled responses, connection still open", scan(RESP_MASKED), 0);
        }
        Thread.sleep(300);
        expectExactly("after client close", scan(RESP_MASKED), 0);
    }

    private static void bodyAndHeaders(String base) throws Exception {
        try (NativePathHttpClient c = NativePathHttpClient.builder().secretMode(true).build()) {
            NativeSecret body = unmasked(REQ_MASKED);
            NativeSecret cookie;
            try (NativePathRequest req = NativePathRequest.of("POST", base + "/submit").secretBody(body);
                 NativePathStream s = c.getStream(req);
                 NativeSecret reply = s.readToSecret(1 << 16)) {
                cookie = s.headerSecret("set-cookie");
                if (cookie == null) fail("no Set-Cookie header received");
                expectAtLeast("secret request body alive natively (control)", scan(REQ_MASKED), 1);
                expectAtLeast("secret Set-Cookie value alive natively (control)", scan(RESP_MASKED), 1);
                String json = new String(copyOut(reply));
                System.out.println("   server reply: " + json);
                if (!json.contains("\"body_ok\": true")) fail("server did not receive the exact secret body");
            }
            body.close();
            if (cookie != null) cookie.close();
            Thread.sleep(300);
            expectExactly("request body after close, client open", scan(REQ_MASKED), 0);
            expectExactly("Set-Cookie value after close, client open", scan(RESP_MASKED), 0);
        }
        Thread.sleep(300);
        expectExactly("request body after client close", scan(REQ_MASKED), 0);
        expectExactly("Set-Cookie value after client close", scan(RESP_MASKED), 0);
    }

    /** Reads a non-secret reply (public JSON) for the assertion above. */
    private static byte[] copyOut(NativeSecret s) {
        byte[] out = new byte[(int) s.length()];
        MemorySegment.copy(s.asMemorySegment(), 0, MemorySegment.ofArray(out), 0, out.length);
        return out;
    }

    private static void fromFile() throws Exception {
        try (NativeSecret s = NativeSecret.fromFile(java.nio.file.Path.of("target/secret-file.txt"))) {
            if (s.length() != REQ_MASKED.length) fail("unexpected length " + s.length() + " (trailing newline not stripped?)");
            expectAtLeast("secret read from file alive natively (control)", scan(REQ_MASKED), 1);
        }
        Thread.sleep(300);
        expectExactly("after close", scan(REQ_MASKED), 0);
    }

    private static void bodyTemplate() throws Exception {
        NativeSecret pw = unmasked(REQ_MASKED);
        NativeSecret body = ro.bitboy.nativepath.SecretBody.builder().text("{\"password\":\"").jsonString(pw).text("\"}").build();
        expectAtLeast("secret and assembled body alive natively (control)", scan(REQ_MASKED), 2);
        pw.close();
        body.close();
        Thread.sleep(300);
        expectExactly("after closing the secret and the body", scan(REQ_MASKED), 0);
    }

    private static void keychain(String base) throws Exception {
        try (NativePathHttpClient c = NativePathHttpClient.builder().secretMode(true).build()) {
            try (NativeSecret credential = SecretSource.keychain(KEYCHAIN_SERVICE, "scan").load()) {
                if (credential.length() != REQ_MASKED.length) fail("unexpected keychain value length " + credential.length());
                expectAtLeast("keychain secret alive natively (control)", scan(REQ_MASKED), 1);
                try (NativePathRequest req = NativePathRequest.get(base + "/token").secretHeader("Authorization", "Bearer ", credential);
                     NativePathStream s = c.getStream(req);
                     NativeSecret body = s.readToSecret(1 << 16);
                     SecretJson.Extracted parts = SecretJson.extract(body, Set.of("access_token"))) {
                    String json = new String(parts.publicJson());
                    System.out.println("   public JSON: " + json);
                    if (!json.contains("\"auth_ok\": true")) fail("server did not receive the keychain secret");
                }
            }
            Thread.sleep(300);
            expectExactly("keychain secret after close, client open", scan(REQ_MASKED), 0);
            expectExactly("response token after close, client open", scan(RESP_MASKED), 0);
        }
        Thread.sleep(300);
        expectExactly("keychain secret after client close", scan(REQ_MASKED), 0);
        expectExactly("response token after client close", scan(RESP_MASKED), 0);
    }

    private static void vault(String base) throws Exception {
        try (NativePathHttpClient c = NativePathHttpClient.builder().secretMode(true).build()) {
            SecretSource source = VaultSecretSource.builder(c).address(base).path("app").field("password")
                    .token(SecretSource.file(java.nio.file.Path.of("target/secret-file.txt"))).build();
            System.out.println("   source: " + source);
            try (NativeSecret value = source.load()) {
                if (value.length() != RESP_MASKED.length) fail("unexpected vault value length " + value.length());
                expectAtLeast("vault value alive natively (control)", scan(RESP_MASKED), 1);
            }
            Thread.sleep(300);
            expectExactly("vault value after close, client open", scan(RESP_MASKED), 0);
            expectExactly("vault token after close, client open", scan(REQ_MASKED), 0);
        }
        Thread.sleep(300);
        expectExactly("vault value after client close", scan(RESP_MASKED), 0);
        expectExactly("vault token after client close", scan(REQ_MASKED), 0);
    }

    private static void facade(String base) throws Exception {
        try (NativePathHttpClient c = NativePathHttpClient.builder().secretMode(true).build()) {
            ro.bitboy.nativepath.NativePath nativepath = ro.bitboy.nativepath.NativePath.builder(c).build();
            SecretSource credential = SecretSource.file(java.nio.file.Path.of("target/secret-file.txt"));
            try (NativeSecret token = nativepath.get(base + "/token").bearer(credential).fetchField("access_token")) {
                if (token.length() != RESP_MASKED.length) fail("unexpected token length " + token.length());
                expectAtLeast("fetched token alive natively (control)", scan(RESP_MASKED), 1);
            }
            nativepath.get(base + "/token").bearer(credential).send(s -> {
                try (NativeSecret body = s.readToSecret(1 << 16);
                     SecretJson.Extracted parts = SecretJson.extract(body, Set.of("access_token"))) {
                    String json = new String(parts.publicJson());
                    System.out.println("   public JSON: " + json);
                    if (!json.contains("\"auth_ok\": true")) fail("server did not receive the credential");
                }
                return null;
            });
            Thread.sleep(300);
            expectExactly("token after close, client open", scan(RESP_MASKED), 0);
            expectExactly("credential after close, client open", scan(REQ_MASKED), 0);
        }
        Thread.sleep(300);
        expectExactly("token after client close", scan(RESP_MASKED), 0);
        expectExactly("credential after client close", scan(REQ_MASKED), 0);
    }

    private static void secretStream(String base) throws Exception {
        try (NativePathHttpClient c = NativePathHttpClient.builder().secretMode(true).build()) {
            NativeSecret credential = unmasked(REQ_MASKED);
            try (NativePathRequest req = NativePathRequest.get(base + "/token").secretHeader("Authorization", "Bearer ", credential)) {
                expectAtLeast("request credential alive in native memory (control)", scan(REQ_MASKED), 1);
                SecretJson.Extracted parts;
                try (NativePathStream stream = c.getStream(req); NativeSecret body = stream.readToSecret(1 << 20)) {
                    parts = SecretJson.extract(body, Set.of("access_token"));
                }
                System.out.println("   public JSON: " + new String(parts.publicJson()));
                expectAtLeast("extracted token alive in native memory (control)", scan(RESP_MASKED), 1);
                parts.close();
            }
            credential.close();
            Thread.sleep(300);
            expectExactly("response value after all closed, client open", scan(RESP_MASKED), 0);
            expectExactly("request value after all closed, client open", scan(REQ_MASKED), 0);
        }
        Thread.sleep(300);
        expectExactly("response value after client close", scan(RESP_MASKED), 0);
        expectExactly("request value after client close", scan(REQ_MASKED), 0);
    }

    public record Token(String token_type, long expires_in, String access_token, boolean auth_ok) {}

    private static void springSecret(String base) throws Exception {
        NativePathHttpClient c = NativePathHttpClient.builder().secretMode(true).build();
        NativePathSecretClient secrets = new NativePathSecretClient(c, new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
        NativeSecret credential = unmasked(REQ_MASKED);
        try (NativePathRequest req = NativePathRequest.get(base + "/token").secretHeader("Authorization", "Bearer ", credential);
             NativePathSecretClient.SecretResult<Token> res = secrets.getJson(req, Token.class, "access_token")) {
            System.out.println("   typed object: " + res.value());
            System.out.println("   server accepted the secret header: " + res.value().auth_ok());
            if (!res.value().auth_ok()) fail("server rejected the secret request header");
            expectAtLeast("typed result holds the token natively (control)", scan(RESP_MASKED), 1);
        }
        credential.close();
        Thread.sleep(300);
        expectExactly("response value after close, client open", scan(RESP_MASKED), 0);
        expectExactly("request value after close, client open", scan(REQ_MASKED), 0);
        c.close();
        Thread.sleep(300);
        expectExactly("response value after client close", scan(RESP_MASKED), 0);
        expectExactly("request value after client close", scan(REQ_MASKED), 0);
    }

    private static void guards(String base) throws Exception {
        try (NativePathHttpClient c = NativePathHttpClient.builder().secretMode(true).build()) {
            try (NativePathResponse r = c.get(base + "/plain")) {
                expectThrows("NativeSecret.toByteArray", () -> r.bodyAsSecret().toByteArray());
                expectThrows("NativePathResponse.asString", r::asString);
            }
            try (NativePathStream s = c.getStream(base + "/plain")) {
                expectThrows("NativePathStream.read(byte[])", () -> s.read(new byte[16], 0, 16));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Writes the unmasked value straight into native memory, never into a Java array or String. */
    private static NativeSecret unmasked(byte[] masked) {
        NativeSecret s = NativeSecret.allocate(masked.length);
        MemorySegment seg = s.asMemorySegment();
        for (int i = 0; i < masked.length; i++) seg.set(ValueLayout.JAVA_BYTE, i, (byte) (masked[i] ^ MASK));
        return s;
    }

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
        System.out.printf("   [%s] %-58s found %d (expected %d)%n", ok ? "ok" : "FAIL", what, got, want);
        if (!ok) failed = true;
    }

    private static void expectAtLeast(String what, long got, long min) {
        boolean ok = got >= min && got != -1;
        System.out.printf("   [%s] %-58s found %d (expected >= %d)%n", ok ? "ok" : "FAIL", what, got, min);
        if (!ok) failed = true;
    }

    private static void expectThrows(String what, ThrowingRunnable r) {
        try {
            r.run();
            System.out.printf("   [FAIL] %-58s did not throw%n", what);
            failed = true;
        } catch (Exception e) {
            System.out.printf("   [ok] %-58s refused: %s%n", what, e.getClass().getSimpleName());
        }
    }

    private static void fail(String msg) {
        System.out.println("   [FAIL] " + msg);
        failed = true;
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
