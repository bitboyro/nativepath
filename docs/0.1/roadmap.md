# Roadmap

## Built

HTTP client and server engines; Java binding; Spring Boot starter (RestClient, WebFlux server, MVC
server preview); secret mode and secret sources (file, macOS Keychain, Vault); PostgreSQL driver
phase 1; memory proofs; benchmark suite and `np-bench` with a live dashboard (throughput, stress ramp, soak, scan under load, correctness under load, database under load).

## In progress

- Security hardening of the server: connection limits (implemented, tests pending), dump protection,
  Spring startup checks, safer defaults for secret headers.
- PostgreSQL driver performance: batch pipelining, parsing from native bytes, a prepared-statement cache.

## Before the first public release

- Ship the native library with the artifacts: today the jars do not contain it. Decision pending:
  per-platform classifier jars that extract the library at startup, or keep the separate bundle.
- First two-machine Linux measurement of the server and a published run of `np-bench`.
- Publish to a Maven repository (Maven Central needs the `ro.bitboy.nativepath` namespace verified).

## Planned, in order

1. Strictness experiment: a request-smuggling and malformed-input corpus against NativePath, Tomcat
   and Netty. Pass: every case rejected at least as strictly as both.
2. Server throughput: close the gap to Netty at the median over plain HTTP.
3. Small-request client latency against the JDK client.
4. AWS Secrets Manager and Azure Key Vault sources (SigV4 signed natively).
5. PostgreSQL: Hibernate/JPA compatibility (metadata, arrays, LOBs, savepoints), column encryption,
   then MySQL/MariaDB and SQL Server; R2DBC.
6. SFTP/SSH, then LDAP, each with the same promise and the same proof.
7. HTTP/2 and HTTP/3, SIMD parsing, `io_uring`, certificate hot-reloading, streaming server bodies.

Not planned: Oracle (no mature pure-Rust protocol implementation).
