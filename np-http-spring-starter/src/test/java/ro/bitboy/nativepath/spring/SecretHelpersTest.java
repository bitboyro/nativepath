package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretBody;
import ro.bitboy.nativepath.spring.boot.NativePathHttpAutoConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SecretHelpersTest {

    private static HttpServer server;
    private static String url;

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/x", ex -> {
            byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/x";
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    // Test-only read of native bytes into a string, to compare against the expected value.
    private static String read(NativeSecret s) {
        byte[] out = new byte[(int) s.length()];
        MemorySegment.copy(s.asMemorySegment(), 0, MemorySegment.ofArray(out), 0, out.length);
        return new String(out, StandardCharsets.UTF_8);
    }

    private static NativeSecret secret(String value) {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        NativeSecret s = NativeSecret.allocate(b.length);
        MemorySegment.copy(MemorySegment.ofArray(b), 0, s.asMemorySegment(), 0, b.length);
        return s;
    }

    @Test
    void jsonStringEscapesQuotesBackslashesAndControlBytes() {
        try (NativeSecret pw = secret("a\"b\\c\nd");
             NativeSecret body = SecretBody.builder().text("{\"p\":\"").jsonString(pw).text("\"}").build()) {
            assertEquals("{\"p\":\"a\\\"b\\\\c\\u000Ad\"}", read(body));
            assertThrows(IllegalStateException.class, body::toByteArray);
        }
    }

    @Test
    void urlEncodedAndRawParts() {
        try (NativeSecret pw = secret("p@ss w/rd~1");
             NativeSecret tok = secret("abc123");
             NativeSecret body = SecretBody.builder().text("user=bob&password=").urlEncoded(pw)
                     .text("&t=").raw(tok).build()) {
            assertEquals("user=bob&password=p%40ss%20w%2Frd~1&t=abc123", read(body));
        }
    }

    @Test
    void fromFileDropsTrailingNewlineAndRefusesHeapCopies(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("token");
        Files.writeString(f, "s3cret-value\n");
        try (NativeSecret s = NativeSecret.fromFile(f)) {
            assertEquals("s3cret-value", read(s));
            assertThrows(IllegalStateException.class, s::toByteArray);
        }
        Files.writeString(f, "");
        try (NativeSecret empty = NativeSecret.fromFile(f)) {
            assertEquals(0, empty.length());
        }
    }

    @Test
    void secretModeRefusesAuthorizationAndBodiesThroughRestClient() {
        try (NativePathHttpClient client = NativePathHttpClient.builder().secretMode(true).build()) {
            RestClient rest = RestClient.builder().requestFactory(new NativePathClientHttpRequestFactory(client, true)).build();
            var e1 = assertThrows(RuntimeException.class,
                    () -> rest.get().uri(url).header("Authorization", "Bearer x").retrieve().toBodilessEntity());
            assertTrue(rootMessage(e1).contains("secretHeader"), rootMessage(e1));
            var e2 = assertThrows(RuntimeException.class,
                    () -> rest.post().uri(url).body("payload").retrieve().toBodilessEntity());
            assertTrue(rootMessage(e2).contains("secretBody"), rootMessage(e2));
            // A harmless header is fine.
            assertEquals(200, rest.get().uri(url).header("X-Trace", "1").retrieve().toBodilessEntity().getStatusCode().value());
        }
    }

    @Test
    void policyIsOnlyAppliedInSecretMode() {
        try (NativePathHttpClient client = NativePathHttpClient.create()) {
            RestClient rest = RestClient.builder().requestFactory(new NativePathClientHttpRequestFactory(client, true)).build();
            assertEquals(200, rest.get().uri(url).header("Authorization", "Bearer x").retrieve().toBodilessEntity()
                    .getStatusCode().value());
        }
    }

    @Test
    void policyIsConfigurableAndPermissiveOptOutWorks() {
        try (NativePathHttpClient client = NativePathHttpClient.builder().secretMode(true).build()) {
            RestClient rest = RestClient.builder()
                    .requestFactory(new NativePathClientHttpRequestFactory(client, true, SecretPolicy.permissive())).build();
            assertEquals(200, rest.get().uri(url).header("Authorization", "x").retrieve().toBodilessEntity()
                    .getStatusCode().value());
        }
    }

    @Test
    void propertiesConfigureThePolicy() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NativePathHttpAutoConfiguration.class))
                .withPropertyValues("nativepath.http.secret-mode=true", "nativepath.http.forbidden-headers=x-custom-secret",
                        "nativepath.http.forbid-request-bodies=false")
                .run(ctx -> {
                    RestClient rest = RestClient.builder()
                            .requestFactory(ctx.getBean(NativePathClientHttpRequestFactory.class)).build();
                    // Authorization is no longer forbidden; the custom header is.
                    assertEquals(200, rest.get().uri(url).header("Authorization", "x").retrieve().toBodilessEntity()
                            .getStatusCode().value());
                    assertThrows(RuntimeException.class,
                            () -> rest.get().uri(url).header("X-Custom-Secret", "v").retrieve().toBodilessEntity());
                });
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
