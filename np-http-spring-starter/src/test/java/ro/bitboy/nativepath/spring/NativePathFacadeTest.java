package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePath;
import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathHttpException;
import ro.bitboy.nativepath.JsonResult;
import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretBody;
import ro.bitboy.nativepath.SecretSource;
import ro.bitboy.nativepath.spring.boot.NativePathHttpAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NativePathFacadeTest {

    private static HttpServer server;
    private static String base;
    private static final AtomicReference<String> lastAuth = new AtomicReference<>();
    private static final AtomicReference<String> lastType = new AtomicReference<>();
    private static final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", ex -> {
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            lastType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(ex, 201, "{}");
        });
        server.createContext("/token", ex -> send(ex, 200,
                "{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":60}"));
        server.createContext("/nested", ex -> send(ex, 200, "{\"data\":{\"token\":\"n-\\u003c1\"}}"));
        server.createContext("/dup", ex -> send(ex, 200,
                "{\"access_token\":\"leak-me\",\"token_type\":\"Bearer\",\"expires_in\":60,\"access_token\":\"x\"}"));
        server.createContext("/forbidden", ex -> send(ex, 403, "{\"errors\":[\"denied\"]}"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    // Test-only helpers: plain test values in and out of native memory.
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

    /** A source that remembers what it handed out, to check the facade closes it. */
    private static final class Tracking implements SecretSource {
        final String value;
        final List<NativeSecret> loaded = new ArrayList<>();
        Tracking(String value) { this.value = value; }
        @Override public NativeSecret load() {
            NativeSecret s = secret(value);
            loaded.add(s);
            return s;
        }
    }

    @Test
    void bearerAndJsonBodyAreSentAndEverythingLoadedIsWiped() throws Exception {
        Tracking token = new Tracking("s3cr3t");
        Tracking password = new Tracking("p\"w");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("user", "bob");
        json.put("password", password);
        json.put("n", java.util.Arrays.asList(1, true, null, Map.of("k", "v\n")));
        try (NativePath nativepath = NativePath.create()) {
            int status = nativepath.post(base + "/echo").bearer(token).json(json).send();
            assertEquals(201, status);
        }
        assertEquals("Bearer s3cr3t", lastAuth.get());
        assertEquals("application/json", lastType.get());
        assertEquals("{\"user\":\"bob\",\"password\":\"p\\\"w\",\"n\":[1,true,null,{\"k\":\"v\\u000a\"}]}", lastBody.get());
        assertTrue(token.loaded.get(0).isClosed());
        assertTrue(password.loaded.get(0).isClosed());
    }

    @Test
    void borrowedSecretsAreNotClosedAndACallCanBeResent() throws Exception {
        Tracking key = new Tracking("k-1");
        try (NativePath nativepath = NativePath.create(); NativeSecret mine = secret("mine")) {
            NativePath.Call call = nativepath.get(base + "/echo").bearer(mine).apiKey("X-Api-Key", key);
            call.send();
            call.send();
            assertFalse(mine.isClosed());
            assertEquals(2, key.loaded.size(), "loaded fresh on every send");
            assertTrue(key.loaded.stream().allMatch(NativeSecret::isClosed));
        }
    }

    @Test
    void basicCredentialsAreEncodedNatively() throws Exception {
        try (NativePath nativepath = NativePath.create()) {
            nativepath.get(base + "/echo").basic("bob", new Tracking("pä:ss")).send();
            assertEquals("Basic " + Base64.getEncoder().encodeToString("bob:pä:ss".getBytes(StandardCharsets.UTF_8)), lastAuth.get());
            nativepath.get(base + "/echo").basic(new Tracking("client-id"), new Tracking("client-secret")).send();
            assertEquals("Basic " + Base64.getEncoder().encodeToString("client-id:client-secret".getBytes()), lastAuth.get());
        }
    }

    @Test
    void formBodyEncodesPublicAndSecretValues() throws Exception {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("grant_type", "client credentials");
        form.put("client_secret", new Tracking("a b+c/é"));
        try (NativePath nativepath = NativePath.create()) {
            nativepath.post(base + "/echo").form(form).send();
        }
        assertEquals("application/x-www-form-urlencoded", lastType.get());
        assertEquals("grant_type=client+credentials&client_secret=a%20b%2Bc%2F%C3%A9", lastBody.get());
    }

    @Test
    void fetchFieldFetchSecretAndErrors() throws Exception {
        try (NativePath nativepath = NativePath.create()) {
            try (NativeSecret t = nativepath.get(base + "/nested").fetchField("data", "token")) {
                assertEquals("n-<1", read(t));
                assertThrows(IllegalStateException.class, t::toByteArray);
            }
            try (NativeSecret all = nativepath.get(base + "/token").fetchSecret()) {
                assertTrue(read(all).startsWith("{\"access_token\""));
            }
            var denied = assertThrows(NativePathHttpException.class,
                    () -> nativepath.get(base + "/forbidden").bearer(new Tracking("never-in-messages")).fetchSecret());
            assertEquals(403, denied.statusCode());
            assertFalse(denied.getMessage().contains("never-in-messages"));
            var missing = assertThrows(IOException.class, () -> nativepath.get(base + "/nested").fetchField("data", "nope"));
            assertTrue(missing.getMessage().contains("data.nope"), missing.getMessage());
            assertThrows(IOException.class, () -> nativepath.get(base + "/token").maxBytes(8).fetchSecret());
            assertThrows(IllegalStateException.class, () -> nativepath.get(base + "/token").fetchJson(Object.class),
                    "fetchJson needs a mapper");
        }
    }

    public record Token(String access_token, String token_type, long expires_in) {}

    @Test
    void fetchJsonSplitsPublicAndSecretFields() throws Exception {
        try (NativePath nativepath = NativePath.builder().jsonMapper(new ObjectMapper()::readValue).build();
             JsonResult<Token> r = nativepath.get(base + "/token").fetchJson(Token.class, "access_token")) {
            assertEquals(60, r.value().expires_in());
            assertEquals("", r.value().access_token());
            assertEquals("tok-1", read(r.secret("access_token")));
        }
    }

    @Test
    void fetchJsonRefusesADuplicateSecretField() {
        try (NativePath nativepath = NativePath.builder().jsonMapper(new ObjectMapper()::readValue).build()) {
            var e = assertThrows(IOException.class, () -> nativepath.get(base + "/dup").fetchJson(Token.class, "access_token"));
            assertTrue(e.getMessage().contains("more than once"), e.getMessage());
            assertFalse(e.getMessage().contains("leak-me"));
        }
    }

    @Test
    void withSecretWipesAfterUseAndNamedSourcesResolve(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("k");
        Files.writeString(f, "file-key\n");
        NativeSecret[] seen = new NativeSecret[1];
        try (NativePath nativepath = NativePath.builder().sources(Map.of("k", SecretSource.file(f))).build()) {
            long len = nativepath.withSecret("k", s -> { seen[0] = s; return s.length(); });
            assertEquals(8L, len);
            assertTrue(seen[0].isClosed());
            assertThrows(IllegalArgumentException.class, () -> nativepath.secret("other"));
        }
    }

    @Test
    void refusesAClientThatAllowsHeapCopies() {
        try (NativePathHttpClient c = NativePathHttpClient.create()) {
            assertThrows(IllegalArgumentException.class, () -> NativePath.builder(c).build());
        }
    }

    @Test
    void base64MatchesRfc4648() {
        for (String v : new String[] { "", "f", "fo", "foo", "foob", "fooba", "foobar" }) {
            try (NativeSecret in = secret(v); NativeSecret out = SecretBody.builder().base64(in).build()) {
                assertEquals(Base64.getEncoder().encodeToString(v.getBytes()), read(out), v);
            }
        }
    }

    // ---------------------------------------------------------------- Spring

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NativePathHttpAutoConfiguration.class));

    @Test
    void springGivesAnNativePathInSecretModeWiredToJacksonAndNamedSources(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("api-key");
        Files.writeString(f, "spring-key");
        runner.run(ctx -> assertTrue(ctx.getBeansOfType(NativePath.class).isEmpty(), "only in secret mode"));
        runner.withPropertyValues("nativepath.http.secret-mode=true",
                        "nativepath.secrets.api-key.type=file",
                        "nativepath.secrets.api-key.path=" + f)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    NativePath nativepath = ctx.getBean(NativePath.class);
                    assertSame(ctx.getBean(NativePathHttpClient.class), nativepath.client());
                    nativepath.get(base + "/echo").bearer(nativepath.secret("api-key")).send();
                    assertEquals("Bearer spring-key", lastAuth.get());
                    try (JsonResult<Token> r = nativepath.get(base + "/token").fetchJson(Token.class, "access_token")) {
                        assertEquals("tok-1", read(r.secret("access_token")));
                    }
                });
    }
}
