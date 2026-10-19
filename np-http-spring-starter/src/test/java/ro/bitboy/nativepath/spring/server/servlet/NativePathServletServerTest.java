package ro.bitboy.nativepath.spring.server.servlet;

import ro.bitboy.nativepath.NativeSecret;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Spring MVC app with {@code nativepath.http.server.enabled=true}: Tomcat's Catalina on the
 * NativePath engine. Covers the synchronous path, request details, secrets, streamed responses,
 * async Servlet requests (DeferredResult, Callable, SSE, timeouts) and that a blocking handler
 * does not hold up other requests.
 */
class NativePathServletServerTest {

    private static final ScheduledExecutorService LATER = Executors.newSingleThreadScheduledExecutor();

    record Order(String id, int quantity) {}

    @RestController
    static class Routes {
        @GetMapping("/ping")
        String ping() {
            return "pong";
        }

        @PostMapping("/json")
        Order json(@RequestBody Order order) {
            return new Order(order.id(), order.quantity() + 1);
        }

        @PostMapping("/reader")
        String reader(HttpServletRequest request) throws Exception {
            try (BufferedReader r = request.getReader()) {
                return r.lines().collect(Collectors.joining("\n")).toUpperCase();
            }
        }

        @GetMapping("/who")
        Map<String, Object> who(HttpServletRequest request) {
            return Map.of("remoteAddr", request.getRemoteAddr(), "remotePort", request.getRemotePort(),
                    "localPort", request.getLocalPort(), "scheme", request.getScheme(),
                    "authorizationVisible", request.getHeader("authorization") != null);
        }

        @GetMapping("/secret")
        String secret(HttpServletRequest request) {
            try (NativeSecret auth = NativePathServletSecrets.header(request, "authorization")) {
                return "length=" + auth.length();
            }
        }

        @GetMapping("/big")
        byte[] big(@RequestParam(name = "size") int size) {
            byte[] b = new byte[size];
            Arrays.fill(b, (byte) 'b');
            return b;
        }

        @GetMapping("/stream")
        StreamingResponseBody stream() {
            return out -> {
                for (int i = 0; i < 3; i++) {
                    out.write(("part" + i + "\n").getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            };
        }

        @GetMapping("/sse")
        SseEmitter sse() {
            SseEmitter emitter = new SseEmitter(5_000L);
            LATER.execute(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        emitter.send(SseEmitter.event().data("event" + i));
                        Thread.sleep(50);
                    }
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        }

        @GetMapping("/deferred")
        DeferredResult<String> deferred() {
            DeferredResult<String> result = new DeferredResult<>(5_000L);
            LATER.schedule(() -> result.setResult("deferred"), 30, TimeUnit.MILLISECONDS);
            return result;
        }

        @GetMapping("/callable")
        Callable<String> callable() {
            return () -> "callable";
        }

        @GetMapping("/hang")
        DeferredResult<String> hang() {
            DeferredResult<String> result = new DeferredResult<>(500L);
            result.onTimeout(() -> result.setErrorResult(ResponseEntity.status(503).body("timed out")));
            return result;
        }

        @GetMapping("/slow")
        String slow(@RequestParam(name = "ms") long ms) throws InterruptedException {
            Thread.sleep(ms);
            return "slow";
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Routes.class)
    static class App {}

    private static ConfigurableApplicationContext ctx;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        ctx = run(true, "virtual");
        base = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) ctx).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        ctx.close();
    }

    private static ConfigurableApplicationContext run(boolean nativepath, String dispatch) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0", "server.address=127.0.0.1", "spring.main.banner-mode=off",
                        "nativepath.http.server.enabled=" + nativepath,
                        "nativepath.http.server.dispatch=" + dispatch,
                        "nativepath.http.server.worker-threads=1", // one lane: a blocked lane would show
                        "nativepath.http.server.secret-headers=authorization")
                .run();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesOnTheNativePathTransport() {
        var server = (TomcatWebServer) ((ServletWebServerApplicationContext) ctx).getWebServer();
        assertInstanceOf(NativePathProtocolHandler.class, server.getTomcat().getConnector().getProtocolHandler());
    }

    @Test
    void disabledKeepsStandardTomcat() {
        try (ConfigurableApplicationContext plain = run(false, "virtual")) {
            var server = (TomcatWebServer) ((ServletWebServerApplicationContext) plain).getWebServer();
            assertEquals("Http11NioProtocol", server.getTomcat().getConnector().getProtocolHandler().getClass().getSimpleName());
        }
    }

    @Test
    void synchronousRequestsJsonReaderAndErrors() throws Exception {
        assertEquals("pong", get("/ping").body());
        HttpResponse<String> json = http.send(HttpRequest.newBuilder(URI.create(base + "/json"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"a\",\"quantity\":2}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, json.statusCode());
        assertEquals("{\"id\":\"a\",\"quantity\":3}", json.body());
        // getReader() decodes through Tomcat's B2CConverter, which needs a heap buffer.
        HttpResponse<String> reader = http.send(HttpRequest.newBuilder(URI.create(base + "/reader"))
                .header("Content-Type", "text/plain; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("héllo\nworld")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals("HÉLLO\nWORLD", reader.body());
        assertEquals(404, get("/nope").statusCode());
    }

    @Test
    void requestKnowsTheClientAndKeepsSecretHeadersNative() throws Exception {
        HttpRequest who = HttpRequest.newBuilder(URI.create(base + "/who")).header("Authorization", "Bearer abc123").build();
        String body = http.send(who, HttpResponse.BodyHandlers.ofString()).body();
        assertTrue(body.contains("\"remoteAddr\":\"127.0.0.1\""), body);
        assertTrue(body.contains("\"scheme\":\"http\""), body);
        assertTrue(body.contains("\"authorizationVisible\":false"), body);
        assertFalse(body.contains("\"remotePort\":0"), body);
        HttpRequest secret = HttpRequest.newBuilder(URI.create(base + "/secret")).header("Authorization", "Bearer abc123").build();
        assertEquals("length=13", http.send(secret, HttpResponse.BodyHandlers.ofString()).body());
    }

    @Test
    void largeResponsesAreStreamedAndComplete() throws Exception {
        int size = 3 * NativePathProcessor.STREAM_THRESHOLD + 17;
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(base + "/big?size=" + size)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode());
        assertEquals(size, r.body().length);
        assertEquals(String.valueOf(size), r.headers().firstValue("content-length").orElse("chunked"));
        // Small responses keep the one-shot path (Content-Length, no chunking).
        HttpResponse<byte[]> small = http.send(HttpRequest.newBuilder(URI.create(base + "/big?size=10")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals("10", small.headers().firstValue("content-length").orElseThrow());
    }

    @Test
    void asyncRequests() throws Exception {
        assertEquals("deferred", get("/deferred").body());
        assertEquals("callable", get("/callable").body());
        assertEquals("part0\npart1\npart2\n", get("/stream").body());
        HttpResponse<String> timeout = get("/hang");
        assertEquals(503, timeout.statusCode());
        assertEquals("timed out", timeout.body());
    }

    @Test
    void serverSentEventsArriveAsTheyAreSent() throws Exception {
        long start = System.nanoTime();
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(base + "/sse")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, r.statusCode());
        try (BufferedReader lines = new BufferedReader(new java.io.InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            assertEquals("data:event0", lines.readLine());
            // The first event arrives before the emitter has finished (3 events, 50 ms apart).
            assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(100) + TimeUnit.SECONDS.toNanos(1));
            String rest = lines.lines().collect(Collectors.joining("\n"));
            assertTrue(rest.contains("data:event1") && rest.contains("data:event2"), rest);
        }
    }

    @Test
    void blockingHandlerDoesNotHoldUpTheLane() throws Exception {
        // One lane: on virtual threads a 1 s blocking handler must not delay a concurrent request.
        CompletableFuture<HttpResponse<String>> slow = http.sendAsync(
                HttpRequest.newBuilder(URI.create(base + "/slow?ms=1000")).build(), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(100);
        long t0 = System.nanoTime();
        assertEquals("pong", get("/ping").body());
        long pingMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(pingMs < 500, "ping took " + pingMs + " ms behind a blocking request");
        assertEquals("slow", slow.get().body());
    }

    @Test
    void laneDispatchServesTooAndBuffersLargeResponses() throws Exception {
        try (ConfigurableApplicationContext lane = run(true, "lane")) {
            String b = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) lane).getWebServer().getPort();
            int size = 2 * NativePathProcessor.STREAM_THRESHOLD;
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(b + "/big?size=" + size)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(size, r.body().length);
            assertEquals("deferred", http.send(HttpRequest.newBuilder(URI.create(b + "/deferred")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
        }
    }
}
