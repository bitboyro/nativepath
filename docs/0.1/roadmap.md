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
7. HTTP/3, SIMD parsing, certificate hot-reloading, streaming server bodies.

## Later, with the reasons

- **HTTP/2.** Most HTTPS traffic is h2 and Netty and Tomcat both serve it, so until we do, our TLS
  result only covers HTTP/1.1 clients. Needs: HPACK with a header table size of 0 (a dynamic table
  would keep header values, secrets included, for the life of the connection), and limits against
  Rapid Reset and similar abuse.
- **Kernel TLS.** Moves record encryption into the kernel (`sendfile` stays zero-copy). Only helps
  large responses; small ones pay the setup. The `tls` kernel module must be loaded on the host.
- **`io_uring`.** Fewer syscalls per request. Docker's default seccomp profile blocks it and recent
  RHEL kernels disable it by default, so it cannot be on by default, and a container needs a custom
  profile.
- **Break test, the rest.** `capacity` covers the SLA search, overload and recovery. Still to do:
  a kernel-enforced memory limit (it is checked, not enforced), hostile clients (slow readers, slow
  headers, oversized bodies), an overload soak, a database-backed workload, TLS.
- **Detached runs.** A run that survives the controller: ssh keepalives, start on the server host and
  collect afterwards, "interrupted at N of M minutes" as a verdict apart from failed.
- **Ramp on common absolute rates**, with a "not saturated" flag, so servers of different speeds meet
  at the same steps.
- **Results record** the Netty TLS provider and each server's log.
- Run Tomcat/MVC configurations on the two VMs.

Not planned: Oracle (no mature pure-Rust protocol implementation).
