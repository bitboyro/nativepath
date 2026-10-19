package ro.bitboy.nativepath.spring;

import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a secret-mode client refuses to send through Spring's {@code RestClient}. Spring builds
 * header values and bodies as Java strings and arrays on the heap, so a credential sent that way
 * is already exposed before this library sees it. Failing closed turns that silent leak into an
 * immediate error that points at the native API ({@code NativePathRequest.secretHeader/secretBody}).
 *
 * <p>The policy applies only when the client was built with {@code secretMode(true)}.
 */
public record SecretPolicy(Set<String> forbiddenHeaders, boolean forbidBodies) {

    public static final List<String> DEFAULT_FORBIDDEN_HEADERS =
            List.of("authorization", "proxy-authorization", "cookie", "x-api-key", "x-vault-token");

    public static final SecretPolicy DEFAULT = of(DEFAULT_FORBIDDEN_HEADERS, true);

    public static SecretPolicy of(List<String> forbiddenHeaders, boolean forbidBodies) {
        return new SecretPolicy(
                forbiddenHeaders.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()),
                forbidBodies);
    }

    /** Allows everything (use only to opt out of the check). */
    public static SecretPolicy permissive() {
        return new SecretPolicy(Set.of(), false);
    }

    void check(HttpHeaders headers, long bodyBytes) {
        for (String name : headers.keySet()) {
            if (forbiddenHeaders.contains(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("secret mode: the '" + name + "' header cannot be sent through RestClient "
                        + "(its value would be a Java string on the heap). Use NativePathRequest.secretHeader(...) with a "
                        + "NativeSecret, or change nativepath.http.forbidden-headers.");
            }
        }
        if (forbidBodies && bodyBytes > 0) {
            throw new IllegalStateException("secret mode: request bodies cannot be sent through RestClient "
                    + "(Spring holds them as a Java array). Use NativePathRequest.secretBody(...) with SecretBody, or set "
                    + "nativepath.http.forbid-request-bodies=false for non-secret bodies.");
        }
    }
}
