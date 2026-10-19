package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathRequest;
import ro.bitboy.nativepath.NativePathStream;
import ro.bitboy.nativepath.NativeSecret;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The server echoes "METHOD|content-type|x-test|body" so each test can check what actually arrived. */
class RequestMethodsTest {

    private static HttpServer server;
    private static String base;
    private static NativePathClientHttpRequestFactory factory;
    private static NativePathHttpClient client;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String out = ex.getRequestMethod() + "|" + ex.getRequestHeaders().getFirst("Content-Type")
                    + "|" + ex.getRequestHeaders().getFirst("X-Test") + "|" + body;
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/echo";
        client = NativePathHttpClient.create();
        factory = new NativePathClientHttpRequestFactory(client, true);
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        client.close();
    }

    private static RestClient rest() {
        return RestClient.builder().requestFactory(factory).build();
    }

    @Test
    void postSendsBodyAndContentType() {
        String r = rest().post().uri(base).contentType(MediaType.APPLICATION_JSON).body("{\"a\":1}")
                .retrieve().body(String.class);
        assertEquals("POST|application/json|null|{\"a\":1}", r);
    }

    @Test
    void putDeleteAndPatch() {
        assertTrue(rest().put().uri(base).body("x").retrieve().body(String.class).startsWith("PUT|"));
        assertTrue(rest().delete().uri(base).retrieve().body(String.class).startsWith("DELETE|"));
        assertTrue(rest().patch().uri(base).body("y").retrieve().body(String.class).endsWith("|y"));
    }

    @Test
    void headersAreForwardedOnGet() {
        String r = rest().get().uri(base).header("X-Test", "hello").retrieve().body(String.class);
        assertEquals("GET|null|hello|", r);
    }

    @Test
    void largeBodyArrivesIntact() {
        String big = "z".repeat(3_000_000);
        String r = rest().post().uri(base).body(big).retrieve().body(String.class);
        assertTrue(r.startsWith("POST|"));
        assertEquals(big.length(), r.length() - r.indexOf("|zzz") - 1);
    }

    @Test
    void secretBodyComesFromNativeMemory() throws Exception {
        try (NativeSecret body = NativeSecret.allocate(6)) {
            for (int i = 0; i < 6; i++) body.asMemorySegment().set(ValueLayout.JAVA_BYTE, i, (byte) ('a' + i));
            try (NativePathRequest req = NativePathRequest.of("POST", base).header("X-Test", "s").secretBody(body);
                 NativePathStream s = client.getStream(req);
                 NativeSecret out = s.readToSecret(1024)) {
                assertEquals("POST|null|s|abcdef", new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        }
    }
}
