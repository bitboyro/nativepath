package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.net.InetSocketAddress;

import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RestClient reads streamed bodies through NativePathStream's mark/reset and readNBytes (not a
 * PushbackInputStream wrapper); the bytes must arrive intact either way.
 */
class StreamingBodyTest {

    private static final int SIZE = 1024 * 1024 + 3;
    private static final byte[] PAYLOAD = new byte[SIZE];
    private static HttpServer server;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        for (int i = 0; i < SIZE; i++) PAYLOAD[i] = (byte) (i % 251);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixed", ex -> {
            ex.sendResponseHeaders(200, PAYLOAD.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(PAYLOAD); }
        });
        server.createContext("/chunked", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (OutputStream os = ex.getResponseBody()) {
                for (int off = 0; off < SIZE; off += 4000) os.write(PAYLOAD, off, Math.min(4000, SIZE - off));
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/fixed", "/chunked"})
    void restClientReadsWholeBody(String path) {
        try (NativePathHttpClient client = NativePathHttpClient.create()) {
            RestClient rest = RestClient.builder().requestFactory(new NativePathClientHttpRequestFactory(client, true)).build();
            assertArrayEquals(PAYLOAD, rest.get().uri(base + path).retrieve().body(byte[].class));
        }
    }
}
