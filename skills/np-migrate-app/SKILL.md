---
name: np-migrate-app
description: Move an existing Spring Boot application onto NativePath in small, reversible, verified steps (outbound HTTP, secrets, server, PostgreSQL driver). Use when the user wants to adopt NativePath in an existing application or asks what migrating would involve.
---

# Migrate an application to NativePath

Follow the release's `docs/<version>/how-to-migrate.md` and `how-to-verify-migration.md`; this skill
is the procedure for doing it with an agent. Each step is independent, reversible, and checked.
**Never batch steps; never skip the baseline; never commit without the user's approval.**

## 0. Inspect (read only)

Find: Spring Boot and Java versions, `RestClient`/`WebClient`/`RestTemplate` usage, direct JDK
`HttpClient` or Apache/OkHttp use, embedded server (Netty/Tomcat/Undertow/Jetty), TLS config
(`server.ssl.*`), JDBC driver and ORM (Hibernate/JPA, Flyway, Liquibase), where credentials and
personal data are handled. Write a short summary and propose which steps apply.

Hard stops (say so, do not work around): Java < 22; Windows; the server needs HTTP/2, client
certificates, WebSocket, non-blocking servlet I/O, `server.tomcat.*` tuning, or keystores; the database
layer uses Hibernate/JPA/Flyway/Liquibase or relies on big result sets or batch inserts being fast.

## 1. Baseline

Run the existing tests; record a load result (rps, p50/p99, CPU, heap) if the user has a load test.
Save it in `migration-baseline.md` next to the code. Without a baseline, performance cannot be
verified: tell the user.

## 2. Install

Use `np-install`.

## 3. Outbound HTTP

Add the starter, run the tests with NativePath on and with `nativepath.http.enabled=false`. Same results
required. Note code that tunes the old client directly (it is bypassed).

## 4. Secrets (only if the user wants it)

Identify real secrets with the user (API keys, tokens, card numbers, DB password). For each outbound
credential: a `nativepath.secrets.<name>` source, and the `NativePath` facade (`.bearer(..)`,
`.apiKey(..)`) instead of header strings. Then `nativepath.http.secret-mode=true`. Calls that fail
because a plain `RestClient` cannot read a body are expected: convert them to the facade or
`NativePathSecretClient`. Inbound: `nativepath.http.server.secret-headers` and `NativePathSecrets`.
Never print, log or paste a secret value while doing this; never put one in a URL.

## 5. Server (only if the user wants it)

`nativepath.http.server.enabled=true`; PEM TLS instead of keystores; for MVC (experimental) keep
`dispatch=virtual` and set `max-in-flight` from the current Tomcat `maxThreads`. Tell the user MVC is a
preview.

## 6. Database (only if limits fit)

URL `jdbc:nativepath:postgresql://...` and `NativePathDataSource`; list `secretColumns`. Check
`docs/<version>/components/db.md` limitations against the application first.

## 7. Verify each step

After every step run `how-to-verify-migration.md`: tests both ways, error paths, load comparison with
the baseline, and for secret paths a heap-dump search for a canary value. Report a before/after
table with the machine. If a step is worse or breaks something, revert that step (its switch is in
`how-to-migrate.md#rolling-back`) and report.

## Output

A summary of what was changed (files, properties), the verification results, anything left on the old
path and why, and the rollback switches. Honest about what was not verified.
