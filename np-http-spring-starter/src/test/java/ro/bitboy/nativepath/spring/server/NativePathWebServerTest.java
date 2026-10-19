package ro.bitboy.nativepath.spring.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

/** Spring WebFlux routes served by the NativePath engine (no Spring Boot context). */
class NativePathWebServerTest {

    record Item(String name, int qty) {}

    private static WebServer server;
    private static String base;
    private static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void start() {
        RouterFunction<ServerResponse> routes = RouterFunctions.route()
                .GET("/hello", req -> ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
                        .bodyValue("hello " + req.queryParam("name").orElse("world")))
                .POST("/items", req -> req.bodyToMono(Item.class)
                        .flatMap(i -> ServerResponse.status(201).contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new Item(i.name().toUpperCase(), i.qty() * 2))))
                .GET("/cookie", req -> ServerResponse.ok()
                        .cookie(ResponseCookie.from("session", req.cookies().getFirst("in").getValue()).httpOnly(true).build())
                        .build())
                .GET("/boom", req -> Mono.error(new IllegalStateException("boom")))
                .build();
        NativePathReactiveWebServerFactory factory = new NativePathReactiveWebServerFactory(0);
        factory.setWorkerThreads(4);
        factory.setMaxBodyBytes(64 * 1024);
        server = factory.getWebServer(RouterFunctions.toHttpHandler(routes));
        server.start();
        base = "http://127.0.0.1:" + server.getPort();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    private static HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void plainTextWithQueryParam() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/hello?name=nativepath")));
        assertEquals(200, r.statusCode());
        assertEquals("hello nativepath", r.body());
        assertTrue(r.headers().firstValue("content-type").orElseThrow().startsWith("text/plain"));
    }

    @Test
    void jsonRequestAndResponseBodies() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/items"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"widget\",\"qty\":21}")));
        assertEquals(201, r.statusCode());
        assertEquals("{\"name\":\"WIDGET\",\"qty\":42}", r.body());
    }

    @Test
    void cookiesInAndOut() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/cookie")).header("Cookie", "a=1; in=xyz"));
        assertEquals(200, r.statusCode());
        String setCookie = r.headers().firstValue("set-cookie").orElseThrow();
        assertTrue(setCookie.startsWith("session=xyz"), setCookie);
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
    }

    @Test
    void handlerErrorBecomes500AndServerKeepsWorking() throws Exception {
        assertEquals(500, send(HttpRequest.newBuilder(URI.create(base + "/boom"))).statusCode());
        assertEquals(200, send(HttpRequest.newBuilder(URI.create(base + "/hello"))).statusCode());
    }

    @Test
    void unknownRouteIs404() throws Exception {
        assertEquals(404, send(HttpRequest.newBuilder(URI.create(base + "/nope"))).statusCode());
    }

    @Test
    void oversizedBodyIs413() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(base + "/items"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[64 * 1024 + 1])));
        assertEquals(413, r.statusCode());
    }

    @Test
    void manyConcurrentRequests() throws Exception {
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 200; i++) {
            futures.add(http.sendAsync(HttpRequest.newBuilder(URI.create(base + "/hello?name=" + i)).build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        for (int i = 0; i < futures.size(); i++) {
            HttpResponse<String> r = futures.get(i).get();
            assertEquals(200, r.statusCode());
            assertEquals("hello " + i, r.body());
        }
    }
}
