package ro.bitboy.nativepath.spring.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** WebFlux with dispatch {@code virtual}: blocking handlers on virtual threads, capped, with shedding. */
class VirtualDispatchTest {

    private static final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();

    /** A handler that blocks its thread, as JDBC or a blocking client would. */
    private RouterFunction<ServerResponse> blocking(long millis) {
        return RouterFunctions.route()
                .GET("/block", req -> {
                    peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        running.decrementAndGet();
                    }
                    return ServerResponse.ok().bodyValue("done");
                })
                .build();
    }

    private static WebServer start(RouterFunction<ServerResponse> routes, int maxInFlight, EngineSettings engine) {
        NativePathReactiveWebServerFactory factory = new NativePathReactiveWebServerFactory(0);
        factory.setWorkerThreads(1);
        factory.setDispatch("virtual");
        factory.setMaxInFlight(maxInFlight);
        factory.setEngineSettings(engine);
        WebServer server = factory.getWebServer(RouterFunctions.toHttpHandler(routes));
        server.start();
        return server;
    }

    private static List<HttpResponse<String>> concurrently(WebServer server, int n) throws Exception {
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/block")).build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        List<HttpResponse<String>> out = new ArrayList<>();
        for (var f : futures) {
            out.add(f.get());
        }
        return out;
    }

    @Test
    void blockingHandlersRunInParallelUpToTheCap() throws Exception {
        WebServer server = start(blocking(300), 4, new EngineSettings());
        try {
            long start = System.nanoTime();
            List<HttpResponse<String>> rs = concurrently(server, 8);
            long millis = (System.nanoTime() - start) / 1_000_000;
            for (HttpResponse<String> r : rs) {
                assertEquals(200, r.statusCode());
                assertEquals("done", r.body());
            }
            // One lane: on the lane thread the eight would take 2.4 s one after another.
            assertTrue(peak.get() > 1, "handlers never overlapped");
            assertTrue(peak.get() <= 4, "cap exceeded: " + peak.get());
            assertTrue(millis < 2000, millis + " ms");
        } finally {
            server.stop();
        }
    }

    @Test
    void overTheCapAndQueueRequestsGet503() throws Exception {
        WebServer server = start(blocking(300), 1, new EngineSettings().maxQueued(1));
        try {
            List<HttpResponse<String>> rs = concurrently(server, 8);
            long ok = rs.stream().filter(r -> r.statusCode() == 200).count();
            List<HttpResponse<String>> shed = rs.stream().filter(r -> r.statusCode() == 503).toList();
            assertTrue(ok >= 1, "nothing served");
            assertFalse(shed.isEmpty(), "nothing shed");
            assertEquals(8, ok + shed.size());
            shed.forEach(r -> assertEquals("1", r.headers().firstValue("retry-after").orElse(null)));
            var stats = ((NativePathWebServer) server).server().engineStats();
            assertEquals(shed.size(), stats.shedRequests());
            assertEquals(1, peak.get());
        } finally {
            server.stop();
        }
    }
}
