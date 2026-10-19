package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.spring.boot.NativePathHttpAutoConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class NativePathHttpAutoConfigurationTest {

    private static HttpServer server;
    private static String url;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NativePathHttpAutoConfiguration.class));

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hello", ex -> {
            byte[] body = "{\"message\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hello";
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void defaultsGiveAStreamingClientAndACustomizer() {
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            NativePathHttpClient client = ctx.getBean(NativePathHttpClient.class);
            assertFalse(client.isSecretMode());
            assertNotNull(ctx.getBean(NativePathClientHttpRequestFactory.class));
            assertNotNull(ctx.getBean(RestClientCustomizer.class));
            assertTrue(ctx.getBeansOfType(NativePathSecretClient.class).isEmpty(), "secret client only in secret mode");
        });
    }

    @Test
    void restClientBuiltThroughTheCustomizerUsesNativePath() {
        runner.run(ctx -> {
            RestClient.Builder builder = RestClient.builder();
            ctx.getBean(RestClientCustomizer.class).customize(builder);
            String body = new String(builder.build().get().uri(url).retrieve().body(byte[].class), StandardCharsets.UTF_8);
            assertEquals("{\"message\":\"hello\"}", body);
        });
    }

    @Test
    void propertiesReachTheClient() {
        runner.withPropertyValues("nativepath.http.secret-mode=true", "nativepath.http.max-body-bytes=1024")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertTrue(ctx.getBean(NativePathHttpClient.class).isSecretMode());
                    assertNotNull(ctx.getBean(NativePathSecretClient.class), "secret client is created in secret mode");
                });
    }

    @Test
    void secretModeRefusesToHandRestClientABodyOnTheHeap() {
        runner.withPropertyValues("nativepath.http.secret-mode=true").run(ctx -> {
            RestClient.Builder builder = RestClient.builder();
            ctx.getBean(RestClientCustomizer.class).customize(builder);
            assertThrows(RuntimeException.class, () -> builder.build().get().uri(url).retrieve().body(byte[].class));
        });
    }

    @Test
    void canBeSwitchedOff() {
        runner.withPropertyValues("nativepath.http.enabled=false").run(ctx -> {
            assertTrue(ctx.getBeansOfType(NativePathHttpClient.class).isEmpty());
            assertTrue(ctx.getBeansOfType(RestClientCustomizer.class).isEmpty());
        });
    }

    @Test
    void aUserDefinedClientWins() {
        runner.withBean(NativePathHttpClient.class, () -> NativePathHttpClient.builder().secretMode(true).build())
                .run(ctx -> {
                    assertEquals(1, ctx.getBeansOfType(NativePathHttpClient.class).size());
                    assertTrue(ctx.getBean(NativePathHttpClient.class).isSecretMode());
                });
    }
}
