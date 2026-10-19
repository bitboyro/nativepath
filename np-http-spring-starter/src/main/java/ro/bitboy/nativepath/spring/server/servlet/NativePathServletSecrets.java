package ro.bitboy.nativepath.spring.server.servlet;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretJson;
import ro.bitboy.nativepath.ServerExchange;
import jakarta.servlet.ServletRequest;

import java.util.Set;

/**
 * The Servlet (Spring MVC) counterpart of {@code NativePathSecrets}: reads secret parts of a request
 * served by the NativePath engine without turning them into Java strings or arrays. Every result
 * refuses heap copies and must be closed (that wipes it).
 *
 * <pre>{@code
 * @PostMapping("/login")
 * ResponseEntity<Void> login(HttpServletRequest request) {
 *     try (NativeSecret auth = NativePathServletSecrets.header(request, "authorization");
 *          SecretJson.Extracted body = NativePathServletSecrets.bodyJson(request, Set.of("password"))) {
 *         return credentials.check(auth, body.secrets().get("password"))
 *                 ? ResponseEntity.ok().build() : ResponseEntity.status(401).build();
 *     }
 * }
 * }</pre>
 *
 * Headers must be listed in {@code nativepath.http.server.secret-headers}; they are then absent from
 * the servlet request. A handler that reads its body here must not also read it through the
 * servlet API ({@code @RequestBody}, {@code getInputStream}), which copies it onto the heap.
 */
public final class NativePathServletSecrets {

    private NativePathServletSecrets() {}

    /** The engine exchange behind a servlet request. */
    public static ServerExchange exchange(ServletRequest request) {
        if (request.getAttribute(NativePathServletWebServerFactory.EXCHANGE_ATTRIBUTE) instanceof ServerExchange ex) {
            return ex;
        }
        throw new IllegalStateException("not served by NativePath (no " + NativePathServletWebServerFactory.EXCHANGE_ATTRIBUTE
                + " request attribute)");
    }

    /** First value of a configured secret header, or {@code null} if absent. */
    public static NativeSecret header(ServletRequest request, String name) {
        return exchange(request).headerSecret(name);
    }

    /** The whole request body. */
    public static NativeSecret body(ServletRequest request) {
        return exchange(request).bodySecret();
    }

    /** A JSON request body split into secret top-level string fields (native) and the rest. See {@link SecretJson}. */
    public static SecretJson.Extracted bodyJson(ServletRequest request, Set<String> secretFields) {
        try (NativeSecret body = body(request)) {
            return SecretJson.extract(body, secretFields);
        }
    }
}
