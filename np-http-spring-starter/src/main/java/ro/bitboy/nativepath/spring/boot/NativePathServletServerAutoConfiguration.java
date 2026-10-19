package ro.bitboy.nativepath.spring.boot;

import ro.bitboy.nativepath.NativePathHttpServer;
import ro.bitboy.nativepath.spring.server.servlet.NativePathServletWebServerFactory;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.ProtocolHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;

/**
 * Serves Spring MVC (Servlet) apps on the native NativePath engine when
 * {@code nativepath.http.server.enabled=true}: Tomcat's Servlet container (Catalina) stays, the
 * engine replaces its transport (sockets, HTTP parsing, TLS). Runs before Boot's own server
 * selection, which then backs off because a {@link ServletWebServerFactory} already exists.
 * {@code server.port}, {@code server.address} and {@code server.ssl.certificate} /
 * {@code certificate-private-key} apply; {@code server.tomcat.*} connector settings do not.
 */
@AutoConfiguration(before = ServletWebServerFactoryAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ Tomcat.class, ProtocolHandler.class, NativePathHttpServer.class })
@ConditionalOnProperty(prefix = "nativepath.http.server", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(NativePathHttpProperties.class)
public class NativePathServletServerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ServletWebServerFactory.class)
    public NativePathServletWebServerFactory nativePathServletWebServerFactory(NativePathHttpProperties p) {
        NativePathHttpProperties.Server s = p.getServer();
        NativePathServletWebServerFactory factory = new NativePathServletWebServerFactory();
        factory.setLanes(s.getWorkerThreads());
        if (s.getDispatch() != null) {
            factory.setDispatch(s.getDispatch());
        }
        factory.setMaxInFlight(s.getMaxInFlight());
        factory.setEngineSettings(s.engineSettings());
        factory.setMaxBodyBytes(s.getMaxBodyBytes());
        factory.setSecretHeaders(s.getSecretHeaders());
        factory.setLimits(s::applyLimits);
        return factory;
    }
}
