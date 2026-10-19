# How to use NativePath

Requirements: Java 22 or newer (Project Panama), Linux (x86_64 or aarch64, glibc 2.28 or newer: RHEL,
Rocky and Alma 8 and later, Ubuntu 20.04 and later) or macOS (aarch64). Spring Boot 3.x for the starter.
Windows is not supported yet (the benchmarks run there in Docker Desktop with WSL2).

## 1. Get the native library

Every NativePath module calls one native library, `libnativepath.so` (Linux) or
`libnativepath.dylib` (macOS). It is not inside the Java jars (see the [roadmap](roadmap.md)): take it
from the release bundle for your platform (`lib/`) and tell the JVM where it is, in this order of
precedence:

1. `-Dnativepath.lib=/opt/nativepath/libnativepath.so`
2. the environment variable `NATIVEPATH_LIB`
3. `target/release/libnativepath.*` relative to the working directory (development builds)

An explicit path that does not exist is an error; the loader never falls back silently. Run the JVM
with `--enable-native-access=ALL-UNNAMED`.

## 2. Pick what you need

| I want | Add | Switch it on |
|---|---|---|
| Faster `RestClient` / `WebClient` calls | `np-http-spring-starter` | Nothing: it is on by default (`nativepath.http.enabled=true`) |
| Secrets that never become `String`s in outbound calls | `np-http-spring-starter` | `nativepath.http.secret-mode=true`, use `NativePathSecretClient` or the `NativePath` facade |
| A faster TLS-terminating server (WebFlux) | `np-http-spring-starter` | `nativepath.http.server.enabled=true` |
| The same for Spring MVC (experimental) | `np-http-spring-starter`, Tomcat on the classpath | `nativepath.http.server.enabled=true` |
| Secrets from file, Keychain or Vault straight into native memory | `np-http-spring-starter` | `nativepath.secrets.<name>.type=file\|keychain\|vault` |
| Inbound secret headers and fields that stay native | `np-http-spring-starter` | `nativepath.http.server.secret-headers=authorization,...` and `NativePathSecrets` |
| A PostgreSQL driver with off-heap credentials | `np-db` | JDBC URL `jdbc:nativepath:postgresql://...` |
| No Spring | `np-binding` | `NativePathHttpClient.builder()...build()` |

Maven coordinates (group `ro.bitboy.nativepath`): `np-binding`, `np-http-spring-starter`, `np-db`.

## 3. Scenarios

### Spring Boot application, speed only

Add `np-http-spring-starter`, set `-Dnativepath.lib=...`. Every `RestClient.Builder` Spring Boot
injects now uses NativePath. Nothing else changes. Turn it off with `nativepath.http.enabled=false`.

### Outbound calls that carry credentials

```properties
nativepath.http.secret-mode=true
nativepath.secrets.billing-key.type=file
nativepath.secrets.billing-key.path=/run/secrets/billing-key
```

```java
@Autowired NativePath nativePath;

int status = nativePath.post(url).apiKey("X-Api-Key", nativePath.secret("billing-key")).json(body).send();
```

In secret mode a plain `RestClient` cannot read response bodies (that would need a heap copy); use
the `NativePath` facade or `NativePathSecretClient`. See [secrets](components/secrets.md) and
[security](security.md).

### Server on the NativePath engine

```properties
nativepath.http.server.enabled=true
server.port=8443
server.ssl.certificate=/etc/tls/cert.pem
server.ssl.certificate-private-key=/etc/tls/key.pem
nativepath.http.server.secret-headers=authorization
```

TLS is terminated in the engine; the private key never enters the JVM. For blocking applications
(JDBC, remote calls) keep `dispatch=virtual` and set `max-in-flight`. See
[http-server](components/http-server.md).

### PostgreSQL with credentials off the heap

```java
NativePathDataSource ds = new NativePathDataSource();
ds.setUrl("jdbc:nativepath:postgresql://db.internal/app?sslmode=verify-full&sslrootcert=/etc/ssl/db-ca.pem");
ds.setUser("app");
ds.setPasswordSource(SecretSource.file(Path.of("/run/secrets/db-password")));
ds.setSecretColumns("card_number,api_token");
```

Give HikariCP the data source object (`hikariConfig.setDataSource(ds)`); Hikari's driver path turns
properties into strings. Phase 1 limits: [db](components/db.md).

### Plain Java, no Spring

```java
try (NativePathHttpClient client = NativePathHttpClient.builder().secretMode(true).build()) { ... }
```

## 4. Check it works

`np-bench check` runs a quick pass of every experiment on your machine
([how to run the benchmark](how-to-run-the-benchmark.md)). To check your own application after
adopting NativePath: [how to verify a migration](how-to-verify-migration.md).
