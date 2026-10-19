package ro.bitboy.nativepath.benchmarks;

import ro.bitboy.nativepath.spring.server.servlet.NativePathProtocolHandler;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link ServerBenchApp}'s routes as a Spring MVC app on Tomcat (Servlet stack), with Tomcat's
 * defaults ({@code maxThreads=200}), for load tests from a separate process:
 *
 * <pre>
 * java -cp ... ro.bitboy.nativepath.benchmarks.MvcBenchApp 8083
 * java -cp ... ro.bitboy.nativepath.benchmarks.MvcBenchApp 8443 tls
 * java -cp ... ro.bitboy.nativepath.benchmarks.MvcBenchApp 8084 --bench.transport=nativepath
 * java -cp ... ro.bitboy.nativepath.benchmarks.MvcBenchApp 8085 --bench.transport=nativepath --bench.dispatch=lane
 * </pre>
 *
 * With {@code tls}, HTTPS from the same PEM test certificate through Boot's {@code server.ssl.*}
 * properties (run from the benchmarks directory); standard Tomcat then uses the JDK's TLS. With
 * {@code --bench.transport=nativepath}, Catalina runs on the NativePath engine instead of Coyote's
 * NIO connector (the Milestone 3 spike, {@code NativePathServletServerAutoConfiguration} in the starter); requests run on
 * virtual threads, or with {@code --bench.dispatch=lane} on the engine's lane threads.
 *
 * <p>Besides {@link ServerBenchApp}'s routes: {@code GET /slow?ms=N} blocks its thread for N ms (a
 * stand-in for a database or remote call), {@code GET /deferred?ms=N} answers through a
 * {@code DeferredResult} completed N ms later on another thread, and {@code GET /callable?ms=N}
 * through a {@code Callable} (both async Servlet requests).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(MvcBenchApp.Routes.class)
public class MvcBenchApp {

    private static final Map<Integer, byte[]> PAYLOADS = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService LATER =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("bench-later").factory());

    @RestController
    static class Routes {
        private final ApplicationContext ctx;

        Routes(ApplicationContext ctx) {
            this.ctx = ctx;
        }

        @GetMapping(value = "/__stats", produces = MediaType.APPLICATION_JSON_VALUE)
        String stats() {
            var web = ((WebServerApplicationContext) ctx).getWebServer();
            var engine = web instanceof TomcatWebServer t
                    && t.getTomcat().getConnector().getProtocolHandler() instanceof NativePathProtocolHandler h ? h.server() : null;
            return BenchStats.json(engine);
        }

        @GetMapping(value = "/hello", produces = MediaType.TEXT_PLAIN_VALUE)
        String hello() {
            return "hello";
        }

        @GetMapping(value = "/payload", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        byte[] payload(@RequestParam(name = "size", defaultValue = "1024") int size) {
            return PAYLOADS.computeIfAbsent(size, n -> {
                byte[] b = new byte[n];
                Arrays.fill(b, (byte) 'A');
                return b;
            });
        }

        @PostMapping(value = "/echo", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        byte[] echo(@RequestBody(required = false) byte[] body) {
            return body == null ? new byte[0] : body;
        }

        @GetMapping(value = "/slow", produces = MediaType.TEXT_PLAIN_VALUE)
        String slow(@RequestParam(name = "ms", defaultValue = "5") long ms) throws InterruptedException {
            Thread.sleep(ms);
            return "slow";
        }

        @GetMapping(value = "/deferred", produces = MediaType.TEXT_PLAIN_VALUE)
        DeferredResult<String> deferred(@RequestParam(name = "ms", defaultValue = "5") long ms) {
            DeferredResult<String> result = new DeferredResult<>(10_000L);
            LATER.schedule(() -> result.setResult("deferred"), ms, TimeUnit.MILLISECONDS);
            return result;
        }

        @GetMapping(value = "/callable", produces = MediaType.TEXT_PLAIN_VALUE)
        Callable<String> callable(@RequestParam(name = "ms", defaultValue = "5") long ms) {
            return () -> {
                Thread.sleep(ms);
                return "callable";
            };
        }

        /** Never completes: the async timeout (1 s here) must answer it. */
        @GetMapping(value = "/hang", produces = MediaType.TEXT_PLAIN_VALUE)
        DeferredResult<String> hang() {
            DeferredResult<String> result = new DeferredResult<>(1_000L);
            result.onTimeout(() -> result.setErrorResult(
                    org.springframework.http.ResponseEntity.status(503).body("timed out")));
            return result;
        }

        @PostMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
        ServerBenchApp.Order json(@RequestBody ServerBenchApp.Order order) {
            return order;
        }
    }

    public static void main(String[] args) {
        List<String> positional = Arrays.stream(args).filter(a -> !a.startsWith("--")).toList();
        List<String> options = Arrays.asList(args);
        boolean nativepath = options.contains("--bench.transport=nativepath");
        String dispatch = options.contains("--bench.dispatch=lane") ? "lane" : "virtual";
        String port = positional.size() > 0 ? positional.get(0) : "8080";
        boolean tls = positional.size() > 1 && positional.get(1).equals("tls");
        java.nio.file.Path fixtures = java.nio.file.Path.of("../np-http-server/tests/fixtures").toAbsolutePath().normalize();
        new SpringApplicationBuilder(MvcBenchApp.class)
                .web(WebApplicationType.SERVLET) // WebFlux is on the classpath too
                .properties(
                        "server.ssl.enabled=" + tls,
                        "server.ssl.certificate=" + fixtures.resolve("test-cert.pem"),
                        "server.ssl.certificate-private-key=" + fixtures.resolve("test-key.pem"),
                        "server.port=" + port,
                        "server.address=127.0.0.1",
                        "nativepath.http.server.enabled=" + nativepath,
                        "nativepath.http.server.dispatch=" + dispatch,
                        "spring.main.banner-mode=off",
                        "logging.level.root=warn",
                        "logging.level.org.springframework.boot.web.embedded.tomcat=info",
                        "logging.level.ro.bitboy.nativepath=info")
                .run(args);
    }
}
