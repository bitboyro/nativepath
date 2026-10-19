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
