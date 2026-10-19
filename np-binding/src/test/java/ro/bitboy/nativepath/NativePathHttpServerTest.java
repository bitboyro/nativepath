package ro.bitboy.nativepath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class NativePathHttpServerTest {

    private NativePathHttpServer server;
    private final List<Thread> workers = new java.util.ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    /** Starts a server whose workers (one per lane) run {@code handler} on every request. */
    private String serve(NativePathHttpServer.Builder builder, Consumer<ServerExchange> handler) {
        server = builder.start();
        for (ServerLane lane : server.lanes()) {
            workers.add(Thread.ofPlatform().start(() -> {
                while (server.isRunning()) {
                    try (ServerExchange ex = lane.poll(50)) {
                        if (ex != null) handler.accept(ex);
                    }
                }
            }));
        }
        return "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() throws InterruptedException {
        if (server != null) {
            server.close();
            for (Thread w : workers) {
                w.join(5000);
                assertFalse(w.isAlive(), "worker should exit once the server is closed");
            }
        }
    }

    @Test
    void echoesMethodTargetHeadersAndBody() throws Exception {
        String base = serve(NativePathHttpServer.builder(), ex -> {
            byte[] body = ex.body().equals(MemorySegment.NULL) ? new byte[0] : ex.body().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            ex.respond(201, Map.of(
                    "x-method", List.of(ex.method()),
                    "x-target", List.of(ex.target()),
                    "x-token-echo", ex.headers().getOrDefault("x-token", List.of("none"))), body);
        });
        byte[] payload = new byte[300_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i % 251);
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/items?id=7"))
                .header("X-Token", "abc")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(201, r.statusCode());
        assertEquals("POST", r.headers().firstValue("x-method").orElseThrow());
        assertEquals("/api/items?id=7", r.headers().firstValue("x-target").orElseThrow());
        assertEquals("abc", r.headers().firstValue("x-token-echo").orElseThrow());
        assertArrayEquals(payload, r.body());
    }

    @Test
    void nativePathClientTalksToNativePathServer() {
        String base = serve(NativePathHttpServer.builder(), ex -> ex.respond(200, Map.of("content-type", List.of("text/plain")), "hello".getBytes()));
        try (NativePathHttpClient client = NativePathHttpClient.create();
             NativePathResponse r = client.get(base + "/hi")) {
            assertEquals(200, r.statusCode());
            assertEquals("hello", r.asString());
        }
    }

    @Test
    void exposesPeerAddressAndPorts() throws Exception {
        String base = serve(NativePathHttpServer.builder(), ex -> ex.respond(200, Map.of(
                "x-peer", List.of(String.valueOf(ex.peerAddress())),
                "x-peer-port", List.of(String.valueOf(ex.peerPort())),
                "x-local-port", List.of(String.valueOf(ex.localPort()))), new byte[0]));
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base + "/who")).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals("127.0.0.1", r.headers().firstValue("x-peer").orElseThrow());
        assertTrue(Integer.parseInt(r.headers().firstValue("x-peer-port").orElseThrow()) > 0);
        assertEquals(server.port(), Integer.parseInt(r.headers().firstValue("x-local-port").orElseThrow()));
    }

    @Test
    void streamsResponsesFromAnotherThread() throws Exception {
        String base = serve(NativePathHttpServer.builder(), ex -> {
            long length = ex.target().equals("/sized") ? 300_000 : -1;
            ResponseStream out = ex.respondStreaming(200, Map.of("content-type", List.of("text/plain")), length);
            Thread.ofVirtual().start(() -> {
                try (out) {
                    byte[] chunk = new byte[100_000];
                    java.util.Arrays.fill(chunk, (byte) 'z');
                    out.write(chunk);
                    Thread.sleep(20); // produced over time
                    out.write(java.nio.ByteBuffer.wrap(chunk));
                    out.write(chunk, 0, chunk.length);
                } catch (Exception e) {
                    out.abort();
                }
            });
        });
        for (String path : List.of("/chunked", "/sized")) {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(base + path)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, r.statusCode());
            assertEquals(path.equals("/sized"), r.headers().firstValue("content-length").isPresent(), path);
            assertEquals(300_000, r.body().length, path);
        }
    }

    @Test
    void streamedWritesOnTheLaneThreadAreRefused() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        String base = serve(NativePathHttpServer.builder(), ex -> {
            ResponseStream out = ex.respondStreaming(200, Map.of(), -1);
            try {
                out.write(new byte[10]);
            } catch (Throwable t) {
                error.set(t);
            }
            out.abort();
        });
        assertThrows(java.io.IOException.class, () -> http.send(HttpRequest.newBuilder(URI.create(base + "/x")).build(),
                HttpResponse.BodyHandlers.ofByteArray()));
        assertInstanceOf(IllegalStateException.class, error.get());
    }

    @Test
    void oversizedBodyGets413WithoutReachingWorker() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String base = serve(NativePathHttpServer.builder().maxBodyBytes(1000), ex -> {
            seen.set(ex.target());
            ex.respond(200, Map.of(), new byte[0]);
        });
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base + "/big"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[1001])).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(413, r.statusCode());
        assertNull(seen.get());
    }

    @Test
    void unansweredExchangeGets500AndBodyIsZeroedAfterwards() throws Exception {
        AtomicReference<MemorySegment> leaked = new AtomicReference<>();
        // Keeps the body view, never answers; the worker loop then closes the exchange.
        String base = serve(NativePathHttpServer.builder(), ex -> leaked.set(ex.body()));
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base + "/x"))
                .POST(HttpRequest.BodyPublishers.ofString("secret")).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(500, r.statusCode());
        // A view kept past the exchange stays valid memory but holds only zeros: the secret is gone.
        byte[] stale = leaked.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        assertEquals(6, stale.length);
        assertArrayEquals(new byte[6], stale);
    }

    @Test
    void headerInjectionIsRejected() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        String base = serve(NativePathHttpServer.builder(), ex -> {
            try {
                ex.respond(200, Map.of("x-bad", List.of("a\r\nset-cookie: evil")), new byte[0]);
            } catch (IllegalArgumentException e) {
                error.set(e);
            }
        });
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base + "/x")).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(500, r.statusCode());
        assertNotNull(error.get());
        assertTrue(r.headers().firstValue("set-cookie").isEmpty());
    }

    @Test
    void portInUseFailsClearly() {
        serve(NativePathHttpServer.builder(), ex -> ex.respond(200, Map.of(), new byte[0]));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NativePathHttpServer.builder().port(server.port()).start());
        assertTrue(e.getMessage().contains(String.valueOf(server.port())));
    }

    static final java.nio.file.Path CERT = java.nio.file.Path.of("../np-http-server/tests/fixtures/test-cert.pem");
    static final java.nio.file.Path KEY = java.nio.file.Path.of("../np-http-server/tests/fixtures/test-key.pem");

    /** A JDK client that trusts exactly the test certificate (full verification, no shortcuts). */
    static HttpClient trustingTestCert() throws Exception {
        var cf = java.security.cert.CertificateFactory.getInstance("X.509");
        var ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        ks.load(null, null);
        try (var in = java.nio.file.Files.newInputStream(CERT)) {
            ks.setCertificateEntry("test", cf.generateCertificate(in));
        }
        var tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        var ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ctx).build();
    }

    @Test
    void httpsWithSecretHeaderAndBodyKeptNative() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        serve(NativePathHttpServer.builder().tls(CERT, KEY).secretHeaders(List.of("Authorization")), ex -> {
            // The secret header is not among the string headers at all.
            boolean hidden = !ex.headers().containsKey("authorization");
            try (NativeSecret auth = ex.headerSecret("authorization");
                 NativeSecret body = ex.bodySecret()) {
                boolean authOk = auth != null && auth.asMemorySegment().mismatch(java.lang.foreign.MemorySegment.ofArray("Bearer t0k3n".getBytes())) == -1;
                boolean bodyOk = body.asMemorySegment().mismatch(java.lang.foreign.MemorySegment.ofArray("pw=hunter2".getBytes())) == -1;
                assertThrows(IllegalStateException.class, auth::toByteArray, "secret copies refuse the heap");
                seen.set(hidden + "/" + authOk + "/" + bodyOk);
            }
            assertThrows(IllegalArgumentException.class, () -> ex.headerSecret("x-other"));
            ex.respond(200, Map.of(), "ok".getBytes());
        });
        assertTrue(server.isTls());
        HttpResponse<String> r = trustingTestCert().send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.port() + "/login"))
                .header("Authorization", "Bearer t0k3n")
                .POST(HttpRequest.BodyPublishers.ofString("pw=hunter2")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("ok", r.body());
        assertEquals("true/true/true", seen.get());
    }

    @Test
    void tlsNeedsBothFiles() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> NativePathHttpServer.builder().tls(CERT, java.nio.file.Path.of("missing-key.pem")).start());
        assertTrue(e.getMessage().contains("missing-key.pem"), e.getMessage());
    }

    @Test
    void lazyHeaderLookupIsCaseInsensitiveAndRepeatable() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String base = serve(NativePathHttpServer.builder().lanes(2), ex -> {
            seen.set(ex.header("X-Multi") + "|" + ex.headerValues("x-multi") + "|" + ex.header("missing")
                    + "|" + ex.headers().get("x-multi"));
            ex.respond(204, Map.of(), new byte[0]);
        });
        HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(base + "/h"))
                .header("X-Multi", "a").header("X-Multi", "b").build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(204, r.statusCode());
        assertEquals("a|[a, b]|null|[a, b]", seen.get());
    }

    @Test
    void lanesShareTheLoad() throws Exception {
        java.util.Set<String> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        String base = serve(NativePathHttpServer.builder().lanes(4), ex -> {
            threads.add(Thread.currentThread().getName());
            ex.respond(200, Map.of(), "ok".getBytes());
        });
        assertEquals(4, server.lanes().size());
        var futures = new java.util.ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
        for (int i = 0; i < 64; i++) {
            // A fresh client per request opens a new connection, so the acceptor spreads them over lanes.
            futures.add(HttpClient.newHttpClient().sendAsync(HttpRequest.newBuilder(URI.create(base + "/" + i)).build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        for (var f : futures) assertEquals("ok", f.get().body());
        assertTrue(threads.size() > 1, "requests should be handled by more than one lane: " + threads);
    }
}
