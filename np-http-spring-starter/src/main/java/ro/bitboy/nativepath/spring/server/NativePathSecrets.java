package ro.bitboy.nativepath.spring.server;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretJson;
import ro.bitboy.nativepath.ServerExchange;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.web.server.ServerWebExchange;

import java.util.Set;

/**
 * Reads secret parts of a request served by the NativePath engine without turning them into Java
 * strings or arrays. Every result refuses heap copies and must be closed (that wipes it).
 *
 * <pre>{@code
 * route().POST("/login", req -> {
 *     try (NativeSecret auth = NativePathSecrets.header(req.exchange(), "authorization");
 *          SecretJson.Extracted body = NativePathSecrets.bodyJson(req.exchange(), Set.of("password"))) {
 *         boolean ok = credentials.check(auth, body.secrets().get("password"));
 *         return ok ? ServerResponse.ok().build() : ServerResponse.status(401).build();
 *     }
 * })
 * }</pre>
 *
 * Headers must be listed in {@code nativepath.http.server.secret-headers}; they are then absent from
 * Spring's {@code HttpHeaders}. A route that reads its body here must not also decode it with
 * Spring's codecs (that would copy it onto the heap).
 */
public final class NativePathSecrets {

    private NativePathSecrets() {}

    /** The engine exchange behind a WebFlux exchange (through any request decorators). */
    public static ServerExchange exchange(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        Object nativeRequest = ServerHttpRequestDecorator.getNativeRequest(request);
        if (nativeRequest instanceof ServerExchange ex) {
            return ex;
        }
        throw new IllegalStateException("not served by NativePath (native request is "
                + (nativeRequest == null ? "null" : nativeRequest.getClass().getName()) + ")");
    }

    /** First value of a configured secret header, or {@code null} if absent. */
    public static NativeSecret header(ServerWebExchange exchange, String name) {
        return exchange(exchange).headerSecret(name);
    }

    /** The whole request body. */
    public static NativeSecret body(ServerWebExchange exchange) {
        return exchange(exchange).bodySecret();
    }

    /**
     * A JSON request body split into secret top-level string fields (native) and the rest (a heap
     * {@code byte[]} with those fields emptied, for an ordinary JSON mapper). See {@link SecretJson}.
     */
    public static SecretJson.Extracted bodyJson(ServerWebExchange exchange, Set<String> secretFields) {
        try (NativeSecret body = body(exchange)) {
            return SecretJson.extract(body, secretFields);
        }
    }
}
