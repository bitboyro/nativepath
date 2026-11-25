# Benchmarks (`np-benchmarks`, not published as a library)

| What | Where |
|---|---|
| `np-bench` (the one entry point), `bench.config.json` (every setting) | `np-benchmarks/`, [how to run](../how-to-run-the-benchmark.md) |
| Docker: `bench.sh`, `docker/Dockerfile`, `docker/compose.yml`, `versions.env` (the tool versions) | `np-benchmarks/` |
| Dashboard: live, results, new run | `np-benchmarks/dashboard/` (`index.html`, `serve.py`) |
| Experiments | `scripts/`: `server_layers`, `server_tls`, `server_ramp`, `soak`, `scan_load`, `correct`, `db_load` |
| Host sampler (RSS, CPU, threads, JVM heap and GC) | `scripts/np_sampler.py` |
| `results.json` builder | `scripts/np_report.py` |
| JMH: client against the JDK client, layers, server path, database driver | `src/main/java/.../benchmarks` |
| Memory proofs | `ResidueScanTest`, `ServerResidueScanTest`, `DbResidueScanTest`, `MemoryDumpTest` |
| Server apps under test | `ServerBenchApp` (WebFlux on NativePath or Netty), `ScanServerApp` (the same, plus planted-secret routes and a self-scan), `MvcBenchApp` (MVC on Tomcat or NativePath), `RawServerBench` |
| Sustained database load | `DbLoadTest` |
| Load generator | `np-http-server/examples/loadgen.rs` (Rust: open or closed loop, TLS, new-connection mode, per-second events, response verification) |
| Scripts | `scripts/` (single machine, tables), `remote/` (two machines), `pg_test_db.sh` (PostgreSQL in Docker, TLS on) |

Continuous integration: tests on every push (`ci.yml`); benchmarks and the Linux memory proofs for
releases or on demand (`bench.yml`).

## Capacity experiment

`capacity` uses one connection ceiling (`max_connections`, default 100,000) for all five
servers. It must exceed the largest search or overload user count. Before measuring, the
controller checks `/__capacity` for the expected server identity and applied ceiling. The
Spring Netty benchmark explicitly selects Netty even with Tomcat on the classpath.

A passing search at `max_users` is a lower bound (shown as ≥), not a measured maximum.
A level requires successful, verified responses without mismatches; a failed recovery fails
the experiment. Memory is checked against resident size, not enforced by the kernel.
Old runs without these checks do not establish comparable capacity. Re-run the experiment
on two Linux hosts before publishing a capacity comparison.

Regression checks: `python3 np-benchmarks/scripts/test_capacity.py`. After a reactor build,
`NP_CAPACITY_LIVE_TEST=1 python3 np-benchmarks/scripts/test_capacity.py` also starts all five
servers using `bench.cp` for dependencies and checks identity, connection limits, and released slots.
