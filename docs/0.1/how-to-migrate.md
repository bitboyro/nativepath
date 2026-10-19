# How to migrate an application to NativePath

Migrate in small steps, each one reversible and each one checked
([how to verify](how-to-verify-migration.md)). Stop at any step: they are independent.

An agent can do the mechanical parts: the skills in `skills/` (`np-install`, `np-migrate-app`,
`np-switch-variant`) follow exactly these steps.

## Step 0. Baseline

Before touching anything, record how the application behaves and performs now: tests green, a load
test result, p50/p99 latency, CPU and heap under load. Keep the numbers; step 5 compares against them.

## Step 1. Install

1. Java 22+ (`java -version`). Spring Boot 3.x.
2. Put `libnativepath` somewhere the JVM can read it; add `-Dnativepath.lib=...` and
   `--enable-native-access=ALL-UNNAMED` ([how to use](how-to-use.md#1-get-the-native-library)).
3. Add `np-http-spring-starter` (and `np-db` if you want the driver).

## Step 2. Outbound HTTP (lowest risk)

Nothing to change in code: `RestClient` uses NativePath once the starter is on the classpath.
Run the tests. If something differs, `nativepath.http.enabled=false` restores the old behaviour.

Differences to expect: connection pooling and timeouts follow NativePath's defaults, not the JDK
client's; check any code that tunes `HttpClient` directly, which is no longer used.

## Step 3. Secrets (opt in)

1. List what is secret: outbound API keys and tokens, inbound `Authorization` headers, card or token
   fields in JSON, the database password.
2. Move each outbound credential to a source (`nativepath.secrets.*`), send it with `.bearer(...)` /
   `.apiKey(...)` on the `NativePath` facade instead of building a header string.
3. Turn on `nativepath.http.secret-mode=true`. Any code that read a response body through `RestClient`
   now fails loudly: switch those calls to the facade or `NativePathSecretClient`. A failure here is
   the feature working, not a bug.
4. Inbound: list the headers in `nativepath.http.server.secret-headers` and read them with
   `NativePathSecrets`.

## Step 4. Server

1. `nativepath.http.server.enabled=true`. WebFlux applications: done. MVC: experimental; keep
   `dispatch=virtual` and set `max-in-flight` from your current Tomcat `maxThreads`.
2. TLS: replace `server.ssl.key-store*` with `server.ssl.certificate` and
   `certificate-private-key` (PEM). Keystores are not supported.
3. Anything on the "missing" list in [http-server](components/http-server.md) (HTTP/2, client
   certificates, non-blocking servlet I/O, `server.tomcat.*`) means: stay on the old server.

## Step 5. Database (only if the limits fit)

Switch the JDBC URL scheme from `jdbc:postgresql:` to `jdbc:nativepath:postgresql:` and use
`NativePathDataSource`. Do this only if you do not use Hibernate/JPA, Flyway or Liquibase, and do not
depend on large result sets or batch inserts being fast ([db limitations](components/db.md#limitations-phase-1)).

## Step 6. Compare with the baseline

Re-run the baseline from step 0 and compare ([how to verify](how-to-verify-migration.md)). If a step
made things worse, undo that step; the others stand.

## Rolling back

Each step has its own switch: `nativepath.http.enabled=false`, `nativepath.http.server.enabled=false`,
`nativepath.http.secret-mode=false`, the old JDBC URL. Removing the starter removes everything.
