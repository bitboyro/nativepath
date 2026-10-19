package ro.bitboy.nativepath.spring.boot;

import ro.bitboy.nativepath.NativePathHttpServer;
import ro.bitboy.nativepath.spring.server.NativePathReactiveWebServerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.reactive.ReactiveWebServerFactoryAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.reactive.HttpHandler;
import reactor.core.publisher.Mono;

/**
 * Replaces the embedded WebFlux server (Netty) with the native NativePath engine when
 * {@code nativepath.http.server.enabled=true}. Runs before Boot's own server selection, which then
 * backs off because a {@link ReactiveWebServerFactory} already exists. {@code server.port} and
 * {@code server.address} apply as usual.
 */
@AutoConfiguration(before = ReactiveWebServerFactoryAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass({ HttpHandler.class, Mono.class, NativePathHttpServer.class })
@ConditionalOnProperty(prefix = "nativepath.http.server", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(NativePathHttpProperties.class)
public class NativePathServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ReactiveWebServerFactory.class)
    public NativePathReactiveWebServerFactory nativePathReactiveWebServerFactory(NativePathHttpProperties p) {
        NativePathHttpProperties.Server s = p.getServer();
        NativePathReactiveWebServerFactory factory = new NativePathReactiveWebServerFactory();
        factory.setWorkerThreads(s.getWorkerThreads());
        factory.setMaxBodyBytes(s.getMaxBodyBytes());
        factory.setSecretHeaders(s.getSecretHeaders());
        if (s.getDispatch() != null) {
            factory.setDispatch(s.getDispatch());
        }
        factory.setMaxInFlight(s.getMaxInFlight());
        factory.setEngineSettings(s.engineSettings());
        factory.setHeaderReadTimeout(s.getHeaderReadTimeout());
        factory.setIdleTimeout(s.getIdleTimeout());
        factory.setBodyReadTimeout(s.getBodyReadTimeout());
        factory.setTlsHandshakeTimeout(s.getTlsHandshakeTimeout());
        factory.setMaxHeaderBytes(s.getMaxHeaderBytes());
        factory.setMaxConnections(s.getMaxConnections());
        factory.setMaxInflightBodyBytes(s.getMaxInflightBodyBytes());
        return factory;
    }
}
