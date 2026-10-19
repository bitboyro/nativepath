package ro.bitboy.nativepath.spring.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Spring Boot WebFlux app with Netty (and Tomcat) on the classpath: {@code nativepath.http.server.enabled=true}
 * swaps the embedded server for NativePath, and leaving it off keeps Boot's own choice.
 */
class NativePathServerBootTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        RouterFunction<ServerResponse> routes() {
            return RouterFunctions.route()
                    .GET("/ping", req -> ServerResponse.ok().bodyValue("pong"))
                    .build();
        }
    }

    private static ConfigurableApplicationContext run(boolean nativepath) {
        return new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.REACTIVE)
                .properties("server.port=0", "nativepath.http.server.enabled=" + nativepath, "spring.main.banner-mode=off")
                .run();
    }

    @Test
    void enabledServesTheAppOnNativePath() throws Exception {
        try (ConfigurableApplicationContext ctx = run(true)) {
            var server = ((ReactiveWebServerApplicationContext) ctx).getWebServer();
            assertInstanceOf(NativePathWebServer.class, server);
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/ping")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertEquals("pong", r.body());
        }
    }

    /** Boot's own choice: Netty, or Tomcat, which is also on this module's test classpath (for the MVC server). */
    @Test
    void disabledKeepsBootsOwnServer() {
        try (ConfigurableApplicationContext ctx = run(false)) {
            var server = ((ReactiveWebServerApplicationContext) ctx).getWebServer();
            assertFalse(server instanceof NativePathWebServer, server.getClass().getName());
        }
    }
}
