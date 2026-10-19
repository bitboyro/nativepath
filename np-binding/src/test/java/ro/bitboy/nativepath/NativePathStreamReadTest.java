package ro.bitboy.nativepath;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** The InputStream read paths of NativePathStream, over fixed-length and chunked bodies. */
class NativePathStreamReadTest {

    private static final int SIZE = 3 * 1024 * 1024 + 7;
    private static final byte[] PAYLOAD = new byte[SIZE];
    private static HttpServer server;
    private static String base;
    private static NativePathHttpClient client;

    @BeforeAll
    static void start() throws Exception {
        for (int i = 0; i < SIZE; i++) PAYLOAD[i] = (byte) (i % 251);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixed", ex -> {
            ex.sendResponseHeaders(200, PAYLOAD.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(PAYLOAD); }
        });
        server.createContext("/chunked", ex -> {
            ex.sendResponseHeaders(200, 0); // 0 = chunked transfer encoding
            try (OutputStream os = ex.getResponseBody()) {
                for (int off = 0; off < SIZE; off += 4000) os.write(PAYLOAD, off, Math.min(4000, SIZE - off));
            }
        });
        server.createContext("/empty", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        client = NativePathHttpClient.create();
    }

    @AfterAll
    static void stop() {
        client.close();
        server.stop(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/fixed", "/chunked"})
    void readAllBytes(String path) throws IOException {
        try (NativePathStream s = client.getStream(base + path)) {
            assertArrayEquals(PAYLOAD, s.readAllBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/fixed", "/chunked"})
    void mixedReadNBytesReadAndReadAllBytes(String path) throws IOException {
        try (NativePathStream s = client.getStream(base + path)) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] head = s.readNBytes(1000);
            byte[] mid = s.readNBytes(70_000);
            all.write(head);
            all.write(mid);
            all.write(s.read());
            all.write(s.readAllBytes());
            assertEquals(1000, head.length);
            assertEquals(70_000, mid.length);
            assertArrayEquals(PAYLOAD, all.toByteArray());
            assertEquals(-1, s.read());
            assertEquals(0, s.readAllBytes().length);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/fixed", "/chunked"})
    void byteByByteRead(String path) throws IOException {
        try (NativePathStream s = client.getStream(base + path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int c;
            while ((c = s.read()) != -1) bos.write(c);
            assertArrayEquals(PAYLOAD, bos.toByteArray());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/fixed", "/chunked"})
    void readAllBytesAfterNextChunkReturnsTheRest(String path) throws IOException {
        try (NativePathStream s = client.getStream(base + path)) {
            s.nextChunk();
            byte[] rest = s.readAllBytes();
            assertTrue(rest.length < SIZE);
            assertArrayEquals(Arrays.copyOfRange(PAYLOAD, SIZE - rest.length, SIZE), rest);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/fixed", "/chunked"})
    void readNBytesBeyondBody(String path) throws IOException {
        try (NativePathStream s = client.getStream(base + path)) {
            assertArrayEquals(PAYLOAD, s.readNBytes(SIZE + 500));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/fixed", "/chunked"})
    void markReadResetAtStart(String path) throws IOException {
        // Spring's empty-body check: mark(1), read(), reset() before anything was read
        try (NativePathStream s = client.getStream(base + path)) {
            assertTrue(s.markSupported());
            s.mark(1);
            assertEquals(PAYLOAD[0] & 0xFF, s.read());
            s.reset();
            assertArrayEquals(PAYLOAD, s.readAllBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/fixed", "/chunked"})
    void markResetWithinChunk(String path) throws IOException {
        try (NativePathStream s = client.getStream(base + path)) {
            s.readNBytes(10);
            s.mark(5);
            assertArrayEquals(Arrays.copyOfRange(PAYLOAD, 10, 15), s.readNBytes(5));
            s.reset();
            assertArrayEquals(Arrays.copyOfRange(PAYLOAD, 10, SIZE), s.readAllBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/fixed", "/chunked"})
    void resetAcrossChunkBoundaryThrows(String path) throws IOException {
        try (NativePathStream s = client.getStream(base + path)) {
            s.mark(Integer.MAX_VALUE);
            s.readNBytes(SIZE / 2);
            assertThrows(IOException.class, s::reset);
        }
    }

    @Test
    void emptyBody() throws IOException {
        try (NativePathStream s = client.getStream(base + "/empty")) {
            assertEquals(0, s.readAllBytes().length);
            assertEquals(-1, s.read());
        }
    }

    @Test
    void secretModeRefusesHeapReads() throws IOException {
        try (NativePathHttpClient secret = NativePathHttpClient.builder().secretMode(true).build();
             NativePathStream s = secret.getStream(base + "/fixed")) {
            assertThrows(IOException.class, s::readAllBytes);
            assertThrows(IOException.class, s::read);
        }
    }
}
