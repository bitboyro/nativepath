package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.KeychainSecretSource;
import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretJson;
import ro.bitboy.nativepath.SecretSource;
import ro.bitboy.nativepath.VaultSecretSource;
import ro.bitboy.nativepath.spring.boot.NativePathHttpAutoConfiguration;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SecretSourcesTest {

    private static final String TOKEN = "hvs.test-token-123";
    // Vault (Go) HTML-escapes <, > and &; the value also has a quote, a backslash, a newline, é and an emoji.
    private static final String VALUE_JSON = "p\\\"w\\\\d\\n\\u003c\\u0026\\u00e9\\ud83d\\ude00";
    private static final String VALUE = "p\"w\\d\n<&é😀";

    private static HttpServer server;
    private static String address;
    private static final AtomicReference<String> lastNamespace = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/secret/data/app/db", ex -> {
            lastNamespace.set(ex.getRequestHeaders().getFirst("X-Vault-Namespace"));
            if (!TOKEN.equals(ex.getRequestHeaders().getFirst("X-Vault-Token"))) {
                send(ex, 403, "{\"errors\":[\"permission denied\"]}");
                return;
            }
            send(ex, 200, "{\"request_id\":\"r1\",\"lease_id\":\"\",\"data\":{\"data\":{\"user\":\"app\",\"password\":\""
                    + VALUE_JSON + "\"},\"metadata\":{\"version\":3}},\"warnings\":null}");
        });
        server.createContext("/v1/kv/app", ex -> send(ex, 200, "{\"data\":{\"password\":\"v1-value\"},\"lease_duration\":0}"));
        server.start();
        address = "http://127.0.0.1:" + server.getAddress().getPort();
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

    private static Path tokenFile(Path dir, String token) throws IOException {
        Path f = dir.resolve("vault-token");
        Files.writeString(f, token + "\n");
        return f;
    }

    // ---------------------------------------------------------------- SecretJson.extractAt

    @Test
    void extractAtFollowsNestedKeysAndDecodesEscapesNatively() {
        try (NativeSecret doc = secret("{\"a\":[1,{\"x\":\"y\"}], \"data\" : {\"other\":{\"password\":\"no\"},"
                + " \"password\":\"" + VALUE_JSON + "\"}}");
             NativeSecret v = SecretJson.extractAt(doc, "data", "password")) {
            assertEquals(VALUE, read(v));
        }
    }

    @Test
    void extractAtErrorsNameThePathButNotTheValue() {
        try (NativeSecret doc = secret("{\"data\":{\"password\":\"hunter2\",\"n\":5,\"bad\":\"\\ud800x\"}}")) {
            var missing = assertThrows(IllegalArgumentException.class, () -> SecretJson.extractAt(doc, "data", "nope"));
            assertTrue(missing.getMessage().contains("data.nope"), missing.getMessage());
            var notString = assertThrows(IllegalArgumentException.class, () -> SecretJson.extractAt(doc, "data", "n"));
            assertTrue(notString.getMessage().contains("not a JSON string"));
            var notObject = assertThrows(IllegalArgumentException.class, () -> SecretJson.extractAt(doc, "data", "password", "x"));
            assertFalse(notObject.getMessage().contains("hunter2"));
            assertThrows(IllegalArgumentException.class, () -> SecretJson.extractAt(doc, "data", "bad"));
        }
    }

    @Test
    void extractRejectsADuplicateSecretKeyInsteadOfLeakingTheFirstValue() {
        try (NativeSecret doc = secret("{\"password\":\"hunter2\",\"user\":\"u\",\"password\":\"x\"}")) {
            var e = assertThrows(IllegalArgumentException.class, () -> SecretJson.extract(doc, Set.of("password")));
            assertTrue(e.getMessage().contains("password"), e.getMessage());
            assertFalse(e.getMessage().contains("hunter2"));
        }
        // Duplicate public keys are the mapper's business, not a leak.
        try (NativeSecret doc = secret("{\"user\":\"a\",\"user\":\"b\",\"password\":\"p\"}");
             SecretJson.Extracted parts = SecretJson.extract(doc, Set.of("password"))) {
            assertEquals("p", read(parts.secrets().get("password")));
        }
    }

    @Test
    void extractMatchesEscapedKeysTheWayAJsonMapperReadsThem() {
        try (NativeSecret doc = secret("{\"user\":\"u\",\"pass\\u0077ord\":\"hunter2\"}");
             SecretJson.Extracted parts = SecretJson.extract(doc, Set.of("password"))) {
            assertEquals("hunter2", read(parts.secrets().get("password")));
            String pub = new String(parts.publicJson(), StandardCharsets.UTF_8);
            assertFalse(pub.contains("hunter2"), pub);
            assertEquals("{\"user\":\"u\",\"pass\\u0077ord\":\"\"}", pub);
        }
        try (NativeSecret doc = secret("{\"d\\u0061ta\":{\"t\\\"k\":\"v\"}}");
             NativeSecret v = SecretJson.extractAt(doc, "data", "t\"k")) {
            assertEquals("v", read(v));
        }
    }

    // ---------------------------------------------------------------- vault

    private static NativePathHttpClient secretClient() {
        return NativePathHttpClient.builder().secretMode(true).build();
    }

    @Test
    void vaultKv2ReadsOneFieldWithATokenFromAFile(@TempDir Path dir) throws Exception {
        try (NativePathHttpClient c = secretClient()) {
            SecretSource src = VaultSecretSource.builder(c).address(address + "/").path("/app/db").field("password")
                    .namespace("team-a").token(SecretSource.file(tokenFile(dir, TOKEN))).build();
            try (NativeSecret v = src.load()) {
                assertEquals(VALUE, read(v));
                assertFalse(v.heapCopiesAllowed());
                assertThrows(IllegalStateException.class, v::toByteArray);
            }
            assertEquals("team-a", lastNamespace.get());
            assertFalse(src.toString().contains(TOKEN));
        }
    }

    @Test
    void vaultKv1(@TempDir Path dir) throws Exception {
        try (NativePathHttpClient c = secretClient();
             NativeSecret v = VaultSecretSource.builder(c).address(address).mount("kv").path("app").field("password")
                     .kvVersion(1).token(SecretSource.file(tokenFile(dir, TOKEN))).build().load()) {
            assertEquals("v1-value", read(v));
        }
    }

    @Test
    void vaultErrorsDoNotContainTheTokenOrValue(@TempDir Path dir) throws Exception {
        String wrong = "hvs.wrong-token-987";
        try (NativePathHttpClient c = secretClient()) {
            var denied = assertThrows(IOException.class, () -> VaultSecretSource.builder(c).address(address)
                    .path("app/db").field("password").token(SecretSource.file(tokenFile(dir, wrong))).build().load());
            assertTrue(denied.getMessage().contains("HTTP 403"), denied.getMessage());
            assertFalse(denied.getMessage().contains(wrong));

            var noField = assertThrows(IOException.class, () -> VaultSecretSource.builder(c).address(address)
                    .path("app/db").field("nope").token(SecretSource.file(tokenFile(dir, TOKEN))).build().load());
            assertTrue(noField.getMessage().contains("data.data.nope"), noField.getMessage());
            assertFalse(noField.getMessage().contains(TOKEN));

            var down = assertThrows(IOException.class, () -> VaultSecretSource.builder(c).address("http://127.0.0.1:1")
                    .path("app/db").field("password").token(SecretSource.file(tokenFile(dir, TOKEN))).build().load());
            assertFalse(down.getMessage().contains(TOKEN));
        }
    }

    @Test
    void vaultRefusesAClientThatAllowsHeapCopies() {
        try (NativePathHttpClient c = NativePathHttpClient.create()) {
            var e = assertThrows(IllegalArgumentException.class, () -> VaultSecretSource.builder(c).address(address)
                    .path("app/db").field("password").token(() -> secret(TOKEN)).build());
            assertTrue(e.getMessage().contains("secretMode"));
        }
    }

    // ---------------------------------------------------------------- keychain

    private static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("mac");
    }

    private static int security(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "security";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    // Opt-in (-Dnativepath.http.test.keychain=true): the item is created by the `security` tool, so
    // macOS asks for the login keychain password before this unsigned process may read it, on
    // every run (each run creates a new item, so "Always Allow" cannot stick).
    @Test
    void keychainReadsAGenericPassword() throws Exception {
        assumeTrue(mac(), "macOS keychain");
        assumeTrue(Boolean.getBoolean("nativepath.http.test.keychain"),
                "prompts for the keychain password; run with -Dnativepath.http.test.keychain=true");
        String service = "nativepath-unit-" + UUID.randomUUID();
        assertEquals(0, security("add-generic-password", "-U", "-A", "-s", service, "-a", "unit", "-w", "kc-värde-1"));
        try (NativeSecret v = SecretSource.keychain(service, "unit").load()) {
            assertEquals("kc-värde-1", read(v));
            assertThrows(IllegalStateException.class, v::toByteArray);
        } finally {
            security("delete-generic-password", "-s", service, "-a", "unit");
        }
    }

    @Test
    void keychainMissingItemIsAnIOException() {
        assumeTrue(mac(), "macOS keychain");
        var e = assertThrows(IOException.class,
                () -> new KeychainSecretSource("nativepath-missing-" + UUID.randomUUID(), "nobody").load());
        assertTrue(e.getMessage().contains("item not found"), e.getMessage());
    }

    @Test
    void keychainOffMacIsUnsupported() {
        assumeTrue(!mac(), "non-macOS only");
        assertThrows(UnsupportedOperationException.class, () -> SecretSource.keychain("s", "a").load());
    }

    // ---------------------------------------------------------------- Spring

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NativePathHttpAutoConfiguration.class));

    @Test
    void noSecretSourcesBeanUnlessConfigured() {
        runner.run(ctx -> assertTrue(ctx.getBeansOfType(SecretSources.class).isEmpty()));
    }

    @Test
    void namedSourcesFromProperties(@TempDir Path dir) throws Exception {
        Path token = tokenFile(dir, TOKEN);
        runner.withPropertyValues(
                        "nativepath.secrets.vault-token.type=file",
                        "nativepath.secrets.vault-token.path=" + token,
                        "nativepath.secrets.api-key.type=keychain",
                        "nativepath.secrets.api-key.service=svc",
                        "nativepath.secrets.api-key.account=acct",
                        "nativepath.secrets.db.type=vault",
                        "nativepath.secrets.db.address=" + address,
                        "nativepath.secrets.db.path=app/db",
                        "nativepath.secrets.db.field=password",
                        "nativepath.secrets.db.token-source=vault-token")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    SecretSources sources = ctx.getBean(SecretSources.class);
                    assertEquals(java.util.List.of("vault-token", "api-key", "db"), java.util.List.copyOf(sources.names()));
                    // The shared client is not in secret mode, so the vault source got its own secret-mode client.
                    try (NativeSecret v = sources.load("db")) {
                        assertEquals(VALUE, read(v));
                    }
                    assertThrows(IllegalArgumentException.class, () -> sources.get("missing"));
                });
    }

    @Test
    void vaultSourceNeedsAFileOrKeychainTokenSource() {
        runner.withPropertyValues(
                        "nativepath.secrets.db.type=vault",
                        "nativepath.secrets.db.address=" + address,
                        "nativepath.secrets.db.path=app/db",
                        "nativepath.secrets.db.field=password",
                        "nativepath.secrets.db.token-source=nothing")
                .run(ctx -> {
                    assertNotNull(ctx.getStartupFailure());
                    Throwable t = ctx.getStartupFailure();
                    while (t.getCause() != null) t = t.getCause();
                    assertTrue(t.getMessage().contains("nativepath.secrets.db.token-source"), t.getMessage());
                });
    }
}
