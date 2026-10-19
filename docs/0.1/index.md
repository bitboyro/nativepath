# NativePath 0.1

NativePath is the boundary between a JVM application and the network, rebuilt in Rust. Business
logic stays in Java; everything that crosses the wire goes through one native layer reached with
Project Panama (Java 22+). It is meant to be a drop-in replacement that is faster in the cases
below, and that keeps secrets off the Java heap when you opt in.

*Drop in, get faster. Opt in, leave nothing behind.*

## What is in this release

| Component | Artifact / crate | What it is | State |
|---|---|---|---|
| [Memory](components/memory.md) | `np-memory` (Rust) | Zeroizing allocator, secure wipe, process memory scanner for tests | Built |
| [HTTP client](components/http-client.md) | `np-http-client` (Rust) | HTTP client engine (reqwest, rustls) | Built |
| [HTTP server](components/http-server.md) | `np-http-server` (Rust) | HTTP/1.1 server engine with lanes and TLS | Built; Spring MVC path experimental |
| [C ABI](components/ffi.md) | `np-ffi` (Rust) | One native library, `libnativepath`, for every protocol | Built |
| [Java binding](components/binding.md) | `np-binding` (Java 22+) | Panama bindings: client, server, `NativeSecret`, secret sources | Built |
| [Spring Boot starter](components/spring-starter.md) | `np-http-spring-starter` | `RestClient` drop-in, WebFlux and MVC server drop-in, secret sources | Built |
| [Secrets](components/secrets.md) | in `np-binding` / starter | File, macOS Keychain, HashiCorp Vault sources; the `NativePath` facade | Built |
| [PostgreSQL driver](components/db.md) | `np-db`, `np-db-postgres` | JDBC driver on a Rust protocol engine | Phase 1; not a general pgjdbc replacement yet |
| [Benchmarks](components/benchmarks.md) | `np-benchmarks` | JMH and load benchmarks, memory proofs, `np-bench` | Built; not published as a library |

## What is proven, and what is not

Only claims with evidence are made. Details and the machine each number comes from: [results](results.md).

| Claim | Evidence | Status |
|---|---|---|
| Large bodies are faster than the JDK client | 45–80% faster over HTTP and HTTPS; chunked bodies 241–2,446 MB/s against 178–567 MB/s, with MB instead of GB of JVM allocation | Proven on one laptop; to be repeated on dedicated hardware |
| Small requests are faster | Within noise of the JDK client at one thread; about 2.5× at 8 threads | Not shown for single requests |
| TLS-heavy server workloads are faster | 3.3× the handshakes of Netty's default, 1.4× Netty with BoringSSL; 512 KB responses at half the CPU | One laptop; re-run on Linux with two machines before relying on it |
| Plain-HTTP server throughput beats Netty | No consistent difference | **Not claimed** |
| Secrets leave nothing behind | Whole-process memory scans find 0 copies after close: client and server (macOS and Linux), database driver (macOS; Linux job exists) | Proven on the protected paths; limits in [security](security.md) |
| Stricter than Tomcat/Netty (smuggling, malformed input) | Parsing comes from hyper; no conformance or fuzz corpus exists | **Unproven** |
| Drop-in | Speed: yes, through the Spring starter. Secret protection needs secret headers, `NativePathSecretClient` or `NativePathSecrets` | True for speed, not for secrets |

## What it does not do

A secret is only as protected as the weakest hop it passes through. NativePath keeps secrets that
cross it off the JVM heap. It cannot protect what other libraries do (a JDBC driver other than
`np-db`, Kafka clients, loggers), and anything your code turns into a `String` is on the heap.

Not built yet: HTTP/2, HTTP/3, SIMD parsing, `io_uring`, certificate hot-reloading, streaming
request bodies on the server, Hibernate/JPA for the database driver. See the [roadmap](roadmap.md).

## Where to start

1. [How to use](how-to-use.md): add it to a project.
2. [How to migrate](how-to-migrate.md) an existing application, then
   [verify the migration](how-to-verify-migration.md).
3. [How to run the benchmark](how-to-run-the-benchmark.md) on your own hardware.
4. Agent skills for installing and migrating are in the repository's `skills/` folder.
