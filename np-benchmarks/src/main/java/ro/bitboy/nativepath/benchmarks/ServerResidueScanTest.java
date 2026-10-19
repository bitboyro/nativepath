package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretJson;
import ro.bitboy.nativepath.internal.ForeignBridge;
import ro.bitboy.nativepath.spring.server.NativePathSecrets;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Set;

/**
 * Server-side residue scan: a Spring Boot WebFlux app on the NativePath engine, over TLS, receives
 * a secret bearer token (header) and password (JSON field) from a separate Python process
 * (np-benchmarks/secret_client.py). This JVM holds the values only XOR-masked. After the requests,
 * the whole process memory (JVM heap, Rust heap, stacks) is scanned for the plaintext. macOS only.
 *
 * Usage (from the repo root, after building): ServerResidueScanTest &lt;scenario&gt;
 *   secure    secret header + NativePathSecrets: both values must be found 0 times
 *   control   same requests, read the normal Spring way (strings): must be FOUND (proves the scan works)
 */
public class ServerResidueScanTest {

    static final byte MASK = 0x5A;
    // "NATIVEPATH-SRV-TOKEN-..." and "NATIVEPATH-SRV-PASSWORD-..." XOR 0x5A
    static final byte[] TOKEN_MASKED  = { 20, 27, 14, 19, 12, 31, 10, 27, 14, 18, 119, 9, 8, 12, 119, 14, 21, 17, 31, 20, 119, 110, 60, 99, 59, 104, 57, 109, 107, 63, 106, 62, 105, 111, 56, 98, 108 };
    static final byte[] PASSWORD_MASKED  = { 20, 27, 14, 19, 12, 31, 10, 27, 14, 18, 119, 9, 8, 12, 119, 10, 27, 9, 9, 13, 21, 8, 30, 119, 99, 107, 57, 105, 63, 109, 59, 106, 56, 111, 62, 104, 60, 110, 108, 98 };
    private static final byte[] BEARER = "Bearer ".getBytes();
    private static final Path CERT = Path.of("np-http-server/tests/fixtures/test-cert.pem");
    private static final Path KEY = Path.of("np-http-server/tests/fixtures/test-key.pem");
    private static final int REQUESTS = 20;

    /** Compares a native value with a masked expected value byte by byte; never assembles the plaintext. */
    static boolean matchesMasked(MemorySegment value, long offset, byte[] masked) {
        if (value.byteSize() - offset != masked.length) return false;
        for (int i = 0; i < masked.length; i++) {
            if ((byte) (value.get(ValueLayout.JAVA_BYTE, offset + i) ^ MASK) != masked[i]) return false;
        }
        return true;
    }

    static boolean bearerMatches(NativeSecret auth) {
        if (auth == null) return false;
        MemorySegment s = auth.asMemorySegment();
        return s.byteSize() > BEARER.length
                && s.asSlice(0, BEARER.length).mismatch(MemorySegment.ofArray(BEARER)) == -1
                && matchesMasked(s, BEARER.length, TOKEN_MASKED);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        RouterFunction<ServerResponse> routes() {
            return RouterFunctions.route()
                    .POST("/secure", req -> {
                        try (NativeSecret auth = NativePathSecrets.header(req.exchange(), "authorization");
                             SecretJson.Extracted body = NativePathSecrets.bodyJson(req.exchange(), Set.of("password"))) {
                            NativeSecret pw = body.secrets().get("password");
                            boolean ok = bearerMatches(auth) && pw != null && matchesMasked(pw.asMemorySegment(), 0, PASSWORD_MASKED);
                            return ServerResponse.ok().bodyValue(ok ? "verified" : "rejected");
                        }
                    })
                    // The usual Spring way: header and body become Java strings.
                    .POST("/control", req -> req.bodyToMono(String.class).flatMap(body -> {
                        String auth = req.headers().firstHeader("Authorization");
                        return ServerResponse.ok().bodyValue("read " + (auth == null ? 0 : auth.length()) + "+" + body.length() + " chars");
                    }))
                    .build();
        }
    }

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "secure";
        boolean secure = scenario.equals("secure");
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.REACTIVE)
                .properties("server.port=0", "spring.main.banner-mode=off", "logging.level.root=warn",
                        "nativepath.http.server.enabled=true",
                        "nativepath.http.server.secret-headers=" + (secure ? "authorization" : ""),
                        "server.ssl.certificate=" + CERT.toAbsolutePath(),
                        "server.ssl.certificate-private-key=" + KEY.toAbsolutePath())
                .run();
        int port = ((ReactiveWebServerApplicationContext) ctx).getWebServer().getPort();
        System.out.println("scenario " + scenario + ": " + REQUESTS + " HTTPS requests from a separate process");

        Process client = new ProcessBuilder("python3", "np-benchmarks/secret_client.py",
                "https://127.0.0.1:" + port + "/" + scenario, CERT.toString(),
                HexFormat.of().formatHex(TOKEN_MASKED), HexFormat.of().formatHex(PASSWORD_MASKED),
                String.valueOf(MASK), String.valueOf(REQUESTS)).redirectErrorStream(true).start();
        String last = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(client.getInputStream()))) {
            for (String line; (line = r.readLine()) != null; ) last = line;
        }
        int exit = client.waitFor();
        System.out.println("   client exit " + exit + ", last response: " + last);
        if (exit != 0) {
            System.out.println("   [FAIL] client failed");
            System.exit(1);
        }

        // Let connection teardown and pending frees finish, and collect garbage (heap Strings in the control run).
        Thread.sleep(500);
        System.gc();
        Thread.sleep(200);

        long token = scan(TOKEN_MASKED), password = scan(PASSWORD_MASKED);
        boolean ok;
        if (secure) {
            ok = "200 verified".equals(last) && token == 0 && password == 0;
            report("bearer token in process memory", token, "0");
            report("password in process memory", password, "0");
        } else {
            ok = token >= 1 && password >= 1;
            report("bearer token in process memory (control)", token, ">= 1");
            report("password in process memory (control)", password, ">= 1");
        }
        ctx.close();
        System.out.println(ok ? "PASS" : "FAIL");
        System.exit(ok ? 0 : 1);
    }

    private static void report(String what, long found, String expected) {
        System.out.printf("   %-45s found %d (expected %s)%n", what, found, expected);
    }

    static long scan(byte[] masked) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment m = a.allocate(masked.length);
            MemorySegment.copy(MemorySegment.ofArray(masked), 0, m, 0, masked.length);
            return ForeignBridge.scanMemory(m, masked.length, MASK);
        }
    }
}
