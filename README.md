# NativePath

**The boundary between a JVM application and the network, rebuilt in Rust.** Business logic stays in
Java. Everything that crosses the wire goes through one native layer, reached with Project Panama
(Java 22+): HTTP client and server, secrets that stay off the Java heap, and a PostgreSQL driver.

*Drop in, get faster. Opt in, leave nothing behind.*

## What it does today

- **Faster where it counts.** Large bodies 45–80% faster than the JDK client; TLS-heavy servers up to
  3.3× the handshakes of Netty's default. Small plain-HTTP requests are level, not faster.
- **Secrets that leave nothing behind.** In secret mode, values never become Java strings; whole-process
  memory scans find no copies after close (client, server, database driver; see the docs for platforms).
- **Drop-in for Spring Boot.** `RestClient`, WebFlux and (experimental) MVC servers.

What is not claimed, and what is unproven (request-smuggling strictness, server throughput parity
with Netty), is listed with the evidence in [`docs/0.1/index.md`](docs/0.1/index.md). A secret is only
as protected as the weakest hop: NativePath covers what crosses it, not other libraries or your own
`String`s.

## Modules

| Module | |
|---|---|
| `np-memory` | Zeroizing allocator, secure wipe, memory scanner (Rust) |
| `np-http-client` | HTTP client engine (Rust) |
| `np-http-server` | HTTP/1.1 server engine (Rust) |
| `np-ffi` | One C ABI library, `libnativepath`, for every protocol (Rust) |
| `np-binding` | Java 22+ Panama bindings, `NativeSecret`, secret sources |
| `np-http-spring-starter` | Spring Boot starter |
| `np-db`, `np-db-postgres` | PostgreSQL JDBC driver and its protocol engine |
| `np-benchmarks` | JMH and load benchmarks, memory proofs, `np-bench` (not published) |

## Get started

- Use it: [`docs/0.1/how-to-use.md`](docs/0.1/how-to-use.md)
- Migrate an application: [`how-to-migrate.md`](docs/0.1/how-to-migrate.md), then [`how-to-verify-migration.md`](docs/0.1/how-to-verify-migration.md)
- Run the benchmark experiment (throughput, ramp, soak, scans, correctness, database), on your machine or on two VMs, and watch it in the dashboard: [`how-to-run-the-benchmark.md`](docs/0.1/how-to-run-the-benchmark.md)
- Measured results: [`results.md`](docs/0.1/results.md). What changed: [`CHANGELOG.md`](CHANGELOG.md)
- Agent skills to install and migrate: [`skills/`](skills/)

Releases carry the compiled native library per platform, the jars, and the `np-bench` bundle.

## Build from source

```sh
cargo build --release -p np-ffi          # libnativepath in target/release
cargo test --release --workspace
mvn -B verify                            # needs JDK 22+
np-benchmarks/np-bench check             # quick pass of every experiment
np-benchmarks/np-bench serve             # dashboard: pick tests, tune them, watch runs live, open results
```

## Contributing and agents

Working rules for people and agents are in [`.claude/skills/`](.claude/skills/) (`np-developer`,
`np-benchmark-engineer`).

## License

[Apache License, Version 2.0](LICENSE).
