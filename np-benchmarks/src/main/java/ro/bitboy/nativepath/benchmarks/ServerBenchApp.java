package ro.bitboy.nativepath.benchmarks;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The same Spring Boot WebFlux app on either server, for side-by-side load tests from a separate
 * process (see {@code np-http-server/examples/loadgen.rs}):
 *
 * <pre>
 * java -cp ... ro.bitboy.nativepath.benchmarks.ServerBenchApp nativepath 8081
 * java -cp ... ro.bitboy.nativepath.benchmarks.ServerBenchApp netty 8082
 * java -cp ... ro.bitboy.nativepath.benchmarks.ServerBenchApp netty 8443 tls
 * </pre>
 *
 * With {@code tls} both servers serve HTTPS from the same PEM test certificate through Spring
 * Boot's standard {@code server.ssl.*} properties (run from the benchmarks directory). Netty uses
 * OpenSSL when netty-tcnative is on the classpath, else the JDK's TLS; the choice is logged.
 *
 * Environment overrides for runs across machines: {@code BENCH_BIND} (address to bind, default
 * 127.0.0.1), {@code BENCH_CERT} and {@code BENCH_KEY} (PEM files, default the test fixtures, which
 * are valid only for 127.0.0.1 and localhost).
 *
 * Routes: {@code GET /hello} (5 bytes), {@code GET /payload?size=N} (N bytes),
 * {@code POST /echo} (returns the request body), {@code POST /json} (parses and re-serializes),
 * {@code GET /__stats} (heap allocated so far and engine timings, see {@link BenchStats}).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class ServerBenchApp {

    record Order(String id, String customer, int quantity, double price, java.util.List<String> tags) {}

    private static final Map<Integer, byte[]> PAYLOADS = new ConcurrentHashMap<>();

    @Bean
    RouterFunction<ServerResponse> routes(org.springframework.context.ApplicationContext ctx) {
        return RouterFunctions.route()
                .GET("/__stats", req -> {
                    var web = ((org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext) ctx).getWebServer();
                    var engine = web instanceof ro.bitboy.nativepath.spring.server.NativePathWebServer e ? e.server() : null;
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(BenchStats.json(engine));
                })
                .GET("/hello", req -> ServerResponse.ok().contentType(MediaType.TEXT_PLAIN).bodyValue("hello"))
                .GET("/payload", req -> {
                    int size = Integer.parseInt(req.queryParam("size").orElse("1024"));
                    byte[] body = PAYLOADS.computeIfAbsent(size, n -> {
                        byte[] b = new byte[n];
                        Arrays.fill(b, (byte) 'A');
                        return b;
                    });
                    return ServerResponse.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).bodyValue(body);
                })
                .POST("/echo", req -> DataBufferUtils.join(req.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class))
                        .map(buf -> {
                            byte[] b = new byte[buf.readableByteCount()];
                            buf.read(b);
                            DataBufferUtils.release(buf);
                            return b;
                        })
                        .defaultIfEmpty(new byte[0])
                        .flatMap(b -> ServerResponse.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).bodyValue(b)))
                .POST("/json", req -> req.bodyToMono(Order.class)
                        .flatMap(o -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(o)))
                .build();
    }

    /** OPENSSL when netty-tcnative is usable, else JDK (what Netty picks by default). */
    private static String nettyTlsProvider() {
        try {
            Class<?> openSsl = Class.forName("io.netty.handler.ssl.OpenSsl");
            boolean available = (boolean) openSsl.getMethod("isAvailable").invoke(null);
            return available ? "OPENSSL (" + openSsl.getMethod("versionString").invoke(null) + ")" : "JDK";
        } catch (ReflectiveOperationException e) {
            return "JDK";
        }
    }

    public static void main(String[] args) {
        String server = args.length > 0 ? args[0] : "nativepath";
        String port = args.length > 1 ? args[1] : "8080";
        boolean tls = args.length > 2 && args[2].equals("tls");
        java.nio.file.Path fixtures = java.nio.file.Path.of("../np-http-server/tests/fixtures").toAbsolutePath().normalize();
        String bind = System.getenv().getOrDefault("BENCH_BIND", "127.0.0.1");
        String cert = System.getenv().getOrDefault("BENCH_CERT", fixtures.resolve("test-cert.pem").toString());
        String key = System.getenv().getOrDefault("BENCH_KEY", fixtures.resolve("test-key.pem").toString());
        if (tls && server.equals("netty")) {
            System.out.println("netty TLS provider: " + nettyTlsProvider());
        }
        new SpringApplicationBuilder(ServerBenchApp.class)
                .web(WebApplicationType.REACTIVE)
                .properties(
                        "server.ssl.enabled=" + tls,
                        "server.ssl.certificate=" + cert,
                        "server.ssl.certificate-private-key=" + key,
                        "server.port=" + port,
                        "server.address=" + bind,
                        "nativepath.http.server.enabled=" + server.equals("nativepath"),
                        "spring.main.banner-mode=off",
                        "logging.level.root=warn",
                        "logging.level.ro.bitboy.nativepath=info")
                .run(args);
    }
}
