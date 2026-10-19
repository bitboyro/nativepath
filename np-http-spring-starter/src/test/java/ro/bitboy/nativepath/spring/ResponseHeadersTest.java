package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathStream;
import ro.bitboy.nativepath.NativeSecret;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ResponseHeadersTest {

    private static HttpServer server;
    private static String url;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/h", ex -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("Set-Cookie", "session=SECRET-COOKIE-VALUE; HttpOnly");
            ex.getResponseHeaders().add("Set-Cookie", "second=2");
            ex.getResponseHeaders().add("X-Custom", "custom value");
            ex.sendResponseHeaders(201, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/h";
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void restClientSeesStatusAndHeaders() {
        try (NativePathHttpClient client = NativePathHttpClient.create()) {
            RestClient rest = RestClient.builder().requestFactory(new NativePathClientHttpRequestFactory(client, true)).build();
            ResponseEntity<String> r = rest.get().uri(url).retrieve().toEntity(String.class);
            assertEquals(201, r.getStatusCode().value());
            assertEquals(MediaType.APPLICATION_JSON, r.getHeaders().getContentType());
            assertEquals("custom value", r.getHeaders().getFirst("X-Custom"));
            assertEquals(2, r.getHeaders().get("Set-Cookie").size());
            assertEquals("{\"ok\":true}", r.getBody());
        }
    }

    @Test
    void secretModeKeepsHeaderValuesNative() {
        try (NativePathHttpClient client = NativePathHttpClient.builder().secretMode(true).build();
             NativePathStream s = client.getStream(url)) {
            assertTrue(s.headerNames().contains("set-cookie"));
            assertThrows(IllegalStateException.class, s::headers);
            try (NativeSecret cookie = s.headerSecret("Set-Cookie")) {
                assertNotNull(cookie);
                assertEquals("session=SECRET-COOKIE-VALUE; HttpOnly",
                        new String(copy(cookie), StandardCharsets.UTF_8));
                assertThrows(IllegalStateException.class, cookie::toByteArray);
            }
            assertNull(s.headerSecret("X-Missing"));
        }
    }

    // Test-only read of a native value; the secret-mode NativeSecret refuses toByteArray().
    private static byte[] copy(NativeSecret s) {
        byte[] out = new byte[(int) s.length()];
        java.lang.foreign.MemorySegment.copy(s.asMemorySegment(), 0, java.lang.foreign.MemorySegment.ofArray(out), 0, out.length);
        return out;
    }
}
