package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretJson;
import ro.bitboy.nativepath.spring.server.NativePathSecrets;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.nio.file.Path;
import java.util.Set;

/**
 * The benchmark server (every route of {@link ServerBenchApp}) that can also scan its own process
 * for planted secrets while it serves, over TLS on the NativePath engine. Used by the soak and the
 * scan-under-load experiments, which run ordinary load against it, send planted secrets through
 * separate client processes (np-benchmarks/scan_client.py) and ask it to scan itself.
 *
 * <ul>
 *   <li>{@code POST /secure}: secret {@code Authorization} header and JSON {@code password} read
 *       through {@link NativePathSecrets}; answers "verified" when they are the planted values.</li>
 *   <li>{@code POST /error}: the same, then fails: the error path with secrets in flight.</li>
 *   <li>{@code POST /control}: the usual Spring way (strings) with the control canaries in
 *       {@code X-Control-Token} and the body, and the last few kept alive like an application that
 *       holds on to a value would; the scan must find these, which shows it works. (Without keeping
 *       them, a busy server's collections overwrite the copies before the scan reads them, and a
 *       control that is sometimes found proves nothing.)</li>
 *   <li>{@code GET /__scan?set=secure|control}: scans the whole process (JVM heap, native heap,
 *       stacks) for that set's token and password; answers JSON with the number of copies of each.</li>
 * </ul>
 *
 * Arguments: {@code <port>} (default 8080). Environment as {@link ServerBenchApp}: {@code BENCH_BIND},
 * {@code BENCH_CERT}, {@code BENCH_KEY}. The body read timeout is 2 s, so a stalled upload ends
 * within a test's patience.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(ServerBenchApp.class)
public class ScanServerApp {

    // "NATIVEPATH-CTL-TOKEN-..." and "NATIVEPATH-CTL-PASSWORD-..." XOR 0x5A
    static final byte[] CTL_TOKEN_MASKED = { 20, 27, 14, 19, 12, 31, 10, 27, 14, 18, 119, 25, 14, 22, 119, 14, 21, 17, 31, 20, 119, 111, 57, 107, 62, 98, 63, 106, 109, 56, 104, 59, 99, 110, 60, 105, 108 };
    static final byte[] CTL_PASSWORD_MASKED = { 20, 27, 14, 19, 12, 31, 10, 27, 14, 18, 119, 25, 14, 22, 119, 10, 27, 9, 9, 13, 21, 8, 30, 119, 63, 110, 98, 59, 104, 107, 57, 109, 99, 62, 106, 105, 56, 111, 60, 108 };

    /** The last control values, held the way application code might hold a String. */
    private static final java.util.ArrayDeque<String> RETAINED = new java.util.ArrayDeque<>();

    private static void retain(String s) {
        synchronized (RETAINED) {
            RETAINED.addLast(s);
            while (RETAINED.size() > 8) RETAINED.removeFirst();
        }
    }

    @Bean
    RouterFunction<ServerResponse> scanRoutes() {
        return RouterFunctions.route()
                .POST("/secure", req -> {
                    try (NativeSecret auth = NativePathSecrets.header(req.exchange(), "authorization");
                         SecretJson.Extracted body = NativePathSecrets.bodyJson(req.exchange(), Set.of("password"))) {
                        NativeSecret pw = body.secrets().get("password");
                        boolean ok = ServerResidueScanTest.bearerMatches(auth) && pw != null
                                && ServerResidueScanTest.matchesMasked(pw.asMemorySegment(), 0, ServerResidueScanTest.PASSWORD_MASKED);
                        return ServerResponse.ok().bodyValue(ok ? "verified" : "rejected");
                    }
                })
                .POST("/error", req -> {
                    try (NativeSecret auth = NativePathSecrets.header(req.exchange(), "authorization");
                         SecretJson.Extracted body = NativePathSecrets.bodyJson(req.exchange(), Set.of("password"))) {
                        throw new IllegalStateException("planned failure with secrets in flight");
                    }
                })
                // The usual Spring way: header and body become Java strings.
                .POST("/control", req -> req.bodyToMono(String.class).flatMap(body -> {
                    String token = req.headers().firstHeader("X-Control-Token");
                    retain(token);
                    retain(body);
                    return ServerResponse.ok().bodyValue("read " + (token == null ? 0 : token.length()) + "+" + body.length() + " chars");
                }))
                .GET("/__scan", req -> {
                    boolean control = req.queryParam("set").orElse("secure").equals("control");
                    long t0 = System.nanoTime();
                    // The two scans are independent reads of the same memory: run them side by side.
                    var tokenScan = java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> ServerResidueScanTest.scan(control ? CTL_TOKEN_MASKED : ServerResidueScanTest.TOKEN_MASKED));
                    long password = ServerResidueScanTest.scan(control ? CTL_PASSWORD_MASKED : ServerResidueScanTest.PASSWORD_MASKED);
                    long token = tokenScan.join();
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON)
                            .bodyValue("{\"set\":\"" + (control ? "control" : "secure") + "\",\"token\":" + token + ",\"password\":" + password + ",\"ms\":" + ms + "}");
                })
                .build();
    }

    public static void main(String[] args) {
        String port = args.length > 0 ? args[0] : "8080";
        Path fixtures = Path.of("../np-http-server/tests/fixtures").toAbsolutePath().normalize();
        String bind = System.getenv().getOrDefault("BENCH_BIND", "127.0.0.1");
        String cert = System.getenv().getOrDefault("BENCH_CERT", fixtures.resolve("test-cert.pem").toString());
        String key = System.getenv().getOrDefault("BENCH_KEY", fixtures.resolve("test-key.pem").toString());
        new SpringApplicationBuilder(ScanServerApp.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                        "server.ssl.enabled=true",
                        "server.ssl.certificate=" + cert,
                        "server.ssl.certificate-private-key=" + key,
                        "server.port=" + port,
                        "server.address=" + bind,
                        "nativepath.http.server.enabled=true",
                        "nativepath.http.server.secret-headers=authorization",
                        "nativepath.http.server.body-read-timeout=2s",
                        "spring.main.banner-mode=off",
                        "logging.level.root=warn")
                .run(args);
    }
}
