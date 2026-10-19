# Spring Boot starter (`np-http-spring-starter`)

Auto-configuration for Spring Boot 3.x. Everything is a bean you can override.

| Auto-configuration | Condition | Provides |
|---|---|---|
| `NativePathHttpAutoConfiguration` | `nativepath.http.enabled` (default true) | `NativePathHttpClient`, a `ClientHttpRequestFactory` and `RestClientCustomizer` (every `RestClient.Builder` uses NativePath); in secret mode with Jackson, `NativePathSecretClient` and the `NativePath` facade; `SecretSources` when `nativepath.secrets.*` is set |
| `NativePathServerAutoConfiguration` | `nativepath.http.server.enabled=true`, WebFlux | The server factory, before Boot's own selection |
| `NativePathServletServerAutoConfiguration` | `nativepath.http.server.enabled=true`, Servlet + Tomcat | Catalina on the NativePath transport (experimental) |

Properties: [configuration](../configuration.md). Server details and limits: [http-server](http-server.md).
`NativePathSecrets` (WebFlux) and `NativePathServletSecrets` (MVC) read secret request headers and
fields as `NativeSecret`.

Guardrails: in secret mode a plain `RestClient` refuses heap bodies, forbidden headers (`nativepath.http.forbidden-headers`) and request bodies, so a mistake fails loudly instead of leaking.
