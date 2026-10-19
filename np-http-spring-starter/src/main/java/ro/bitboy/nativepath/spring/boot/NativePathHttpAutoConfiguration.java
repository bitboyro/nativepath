package ro.bitboy.nativepath.spring.boot;

import ro.bitboy.nativepath.NativePath;
import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.SecretSource;
import ro.bitboy.nativepath.VaultSecretSource;
import ro.bitboy.nativepath.spring.NativePathClientHttpRequestFactory;
import ro.bitboy.nativepath.spring.NativePathSecretClient;
import ro.bitboy.nativepath.spring.SecretPolicy;
import ro.bitboy.nativepath.spring.SecretSources;
import ro.bitboy.nativepath.spring.boot.NativePathHttpProperties.SecretSourceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wires NativePath into a Spring Boot application:
 * <ul>
 *   <li>an {@link NativePathHttpClient} built from {@code nativepath.*} properties,</li>
 *   <li>an {@link NativePathClientHttpRequestFactory} over it,</li>
 *   <li>a {@link RestClientCustomizer}, so every {@code RestClient.Builder} injected by Spring Boot
 *       uses NativePath,</li>
 *   <li>an {@link NativePathSecretClient} when {@code nativepath.http.secret-mode=true} and Jackson is present,</li>
 *   <li>a {@link SecretSources} bean when at least one {@code nativepath.secrets.<name>} is configured,</li>
 *   <li>an {@link NativePath} facade when {@code nativepath.http.secret-mode=true}, wired to Jackson and the named sources.</li>
 * </ul>
 * Switch it off with {@code nativepath.http.enabled=false}.
 */
@AutoConfiguration
@ConditionalOnClass({ RestClient.class, NativePathHttpClient.class })
@ConditionalOnProperty(prefix = "nativepath.http", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(NativePathHttpProperties.class)
public class NativePathHttpAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public NativePathHttpClient nativePathHttpClient(NativePathHttpProperties p) {
        return NativePathHttpClient.builder()
                .trustSelfSigned(p.isTrustSelfSigned())
                .secretMode(p.isSecretMode())
                .isolateConnections(p.isIsolateConnections())
                .maxBodyBytes(p.getMaxBodyBytes())
                .build();
    }

    // The client bean owns the native resources; the factory only borrows it (no destroy method).
    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public NativePathClientHttpRequestFactory nativePathClientHttpRequestFactory(NativePathHttpClient client, NativePathHttpProperties p) {
        return new NativePathClientHttpRequestFactory(client, p.isStreaming(),
                SecretPolicy.of(p.getForbiddenHeaders(), p.isForbidRequestBodies()));
    }

    @Bean
    @ConditionalOnMissingBean(name = "nativePathRestClientCustomizer")
    public RestClientCustomizer nativePathRestClientCustomizer(NativePathClientHttpRequestFactory factory) {
        return builder -> builder.requestFactory(factory);
    }

    @Bean
    @ConditionalOnClass(ObjectMapper.class)
    @ConditionalOnProperty(prefix = "nativepath.http", name = "secret-mode", havingValue = "true")
    @ConditionalOnMissingBean
    public NativePathSecretClient nativePathSecretClient(NativePathHttpClient client, ObjectProvider<ObjectMapper> mapper) {
        return new NativePathSecretClient(client, mapper.getIfAvailable(ObjectMapper::new));
    }

    /** The facade borrows the shared client (no destroy method); named sources and Jackson are used when present. */
    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "nativepath.http", name = "secret-mode", havingValue = "true")
    @ConditionalOnMissingBean
    public NativePath nativepath(NativePathHttpClient client, ObjectProvider<SecretSources> sources,
                           ObjectProvider<NativePath.JsonMapper> json) {
        NativePath.Builder b = NativePath.builder(client).jsonMapper(json.getIfAvailable());
        SecretSources s = sources.getIfAvailable();
        if (s != null) b.sources(name -> s.names().contains(name) ? s.get(name) : null);
        return b.build();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ObjectMapper.class)
    static class JacksonMapping {
        @Bean
        @ConditionalOnMissingBean
        NativePath.JsonMapper nativePathJsonMapper(ObjectProvider<ObjectMapper> mapper) {
            return mapper.getIfAvailable(ObjectMapper::new)::readValue;
        }
    }

    /**
     * Vault sources need a secret-mode client: the shared one if it is in secret mode, otherwise a
     * private one owned (and closed) by the {@link SecretSources} bean.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @Conditional(OnSecretSources.class)
    public SecretSources nativePathSecretSources(Environment env, NativePathHttpProperties p, ObjectProvider<NativePathHttpClient> shared) {
        Map<String, SecretSourceProperties> config = Binder.get(env)
                .bind("nativepath.secrets", Bindable.mapOf(String.class, SecretSourceProperties.class)).orElseGet(LinkedHashMap::new);
        Map<String, SecretSource> sources = new LinkedHashMap<>();
        config.forEach((name, c) -> {
            if (c.getType() != SecretSourceProperties.Type.VAULT) sources.put(name, plainSource(name, c));
        });
        NativePathHttpClient vaultClient = null;
        NativePathHttpClient owned = null;
        try {
            for (Map.Entry<String, SecretSourceProperties> e : config.entrySet()) {
                SecretSourceProperties c = e.getValue();
                if (c.getType() != SecretSourceProperties.Type.VAULT) continue;
                SecretSource token = c.getTokenSource() == null ? null : sources.get(c.getTokenSource());
                if (token == null) {
                    throw new IllegalArgumentException(key(e.getKey()) + ".token-source must name a file or keychain source");
                }
                if (vaultClient == null) {
                    NativePathHttpClient s = shared.getIfAvailable();
                    vaultClient = s != null && s.isSecretMode() ? s
                            : (owned = NativePathHttpClient.builder().secretMode(true).trustSelfSigned(p.isTrustSelfSigned()).build());
                }
                try {
                    sources.put(e.getKey(), VaultSecretSource.builder(vaultClient).address(c.getAddress()).mount(c.getMount())
                            .path(c.getPath()).field(c.getField()).kvVersion(c.getKvVersion()).namespace(c.getNamespace())
                            .token(token).build());
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(key(e.getKey()) + ": " + ex.getMessage(), ex);
                }
            }
        } catch (RuntimeException ex) {
            if (owned != null) owned.close();
            throw ex;
        }
        Map<String, SecretSource> ordered = new LinkedHashMap<>();
        config.keySet().forEach(name -> ordered.put(name, sources.get(name)));
        return new SecretSources(ordered, owned);
    }

    private static SecretSource plainSource(String name, SecretSourceProperties c) {
        if (c.getType() == null) {
            throw new IllegalArgumentException(key(name) + ".type is required (file, keychain or vault)");
        }
        return switch (c.getType()) {
            case FILE -> {
                if (c.getPath() == null) throw new IllegalArgumentException(key(name) + ".path is required");
                yield SecretSource.file(Path.of(c.getPath()));
            }
            case KEYCHAIN -> {
                if (c.getService() == null || c.getAccount() == null) {
                    throw new IllegalArgumentException(key(name) + ".service and .account are required");
                }
                yield SecretSource.keychain(c.getService(), c.getAccount());
            }
            case VAULT -> throw new IllegalStateException();
        };
    }

    private static String key(String name) {
        return "nativepath.secrets." + name;
    }

    /** Opt-in: matches only when some {@code nativepath.secrets.<name>} is configured. */
    static class OnSecretSources extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            boolean any = Binder.get(context.getEnvironment())
                    .bind("nativepath.secrets", Bindable.mapOf(String.class, SecretSourceProperties.class))
                    .map(m -> !m.isEmpty()).orElse(false);
            return any ? ConditionOutcome.match("nativepath.secrets configured")
                    : ConditionOutcome.noMatch("no nativepath.secrets configured");
        }
    }
}
