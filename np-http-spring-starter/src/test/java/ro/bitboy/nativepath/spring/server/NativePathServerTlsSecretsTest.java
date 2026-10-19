package ro.bitboy.nativepath.spring.server;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretJson;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring Boot WebFlux over HTTPS terminated in the engine (server.ssl.* PEM files), with a secret
 * header and a secret JSON field read natively through {@link NativePathSecrets}.
 */
class NativePathServerTlsSecretsTest {

    static final Path CERT = Path.of("../np-http-server/tests/fixtures/test-cert.pem");
    static final Path KEY = Path.of("../np-http-server/tests/fixtures/test-key.pem");

    record Login(String user, String password) {}

    static boolean equalsNative(NativeSecret s, String expected) {
        return s != null && s.asMemorySegment().mismatch(MemorySegment.ofArray(expected.getBytes(StandardCharsets.UTF_8))) == -1;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        RouterFunction<ServerResponse> routes(ObjectMapper mapper) {
            return RouterFunctions.route()
                    .POST("/login", req -> {
                        boolean hidden = req.headers().header("Authorization").isEmpty();
                        try (NativeSecret auth = NativePathSecrets.header(req.exchange(), "authorization");
                             SecretJson.Extracted body = NativePathSecrets.bodyJson(req.exchange(), Set.of("password"))) {
                            Login publicPart = mapper.readValue(body.publicJson(), Login.class);
                            String result = "https=" + req.exchange().getRequest().getURI().getScheme().equals("https")
                                    + ",ssl=" + (req.exchange().getRequest().getSslInfo() != null)
                                    + ",hidden=" + hidden
                                    + ",auth=" + equalsNative(auth, "Bearer s3cr3t-t0k3n")
                                    + ",pw=" + equalsNative(body.secrets().get("password"), "hunter2-pw")
                                    + ",user=" + publicPart.user()
                                    + ",pwOnHeap=" + publicPart.password();
                            return ServerResponse.ok().bodyValue(result);
                        } catch (java.io.IOException e) {
                            return ServerResponse.badRequest().build();
                        }
                    })
                    .build();
        }
    }

    @Test
    void httpsAndSecretsThroughSpringBoot() throws Exception {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.REACTIVE)
                .properties("server.port=0", "spring.main.banner-mode=off",
                        "nativepath.http.server.enabled=true",
                        "nativepath.http.server.secret-headers=authorization",
                        "server.ssl.certificate=" + CERT.toAbsolutePath(),
                        "server.ssl.certificate-private-key=file:" + KEY.toAbsolutePath())
                .run()) {
            int port = ((ReactiveWebServerApplicationContext) ctx).getWebServer().getPort();
            HttpResponse<String> r = trustingTestCert().send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/login"))
                    .header("Authorization", "Bearer s3cr3t-t0k3n")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"alice\",\"password\":\"hunter2-pw\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("https=true,ssl=true,hidden=true,auth=true,pw=true,user=alice,pwOnHeap=", r.body());
        }
    }

    @Test
    void keystoreConfigurationFailsClearly() {
        var app = new SpringApplicationBuilder(App.class)
                .web(WebApplicationType.REACTIVE)
                .properties("server.port=0", "spring.main.banner-mode=off", "nativepath.http.server.enabled=true",
                        "server.ssl.key-store=classpath:missing.p12");
        Exception e = assertThrows(Exception.class, app::run);
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        assertTrue(root.getMessage().contains("PEM files only"), root.getMessage());
    }

    static HttpClient trustingTestCert() throws Exception {
        var cf = java.security.cert.CertificateFactory.getInstance("X.509");
        var ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        ks.load(null, null);
        try (var in = Files.newInputStream(CERT)) {
            ks.setCertificateEntry("test", cf.generateCertificate(in));
        }
        var tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        var ssl = javax.net.ssl.SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).build();
    }
}
