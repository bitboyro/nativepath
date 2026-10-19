# Changelog

One line per change, newest release first. Features, Fixes, Changes; nothing else.

## 0.1.2

### Features
- `lanes` experiment: engine lane settings (batch, spin, work stealing) against the defaults; in the default run.

### Fixes
- `engine_java` benchmark server now binds to `BENCH_BIND`; it was unreachable from another machine.
- Two-host throughput stops with a message when the client cannot reach the server.

## 0.1.1

### Features
- Load generator: lean hyper client by default, generator CPU in every result, "generator-bound" flag.
- `client` experiment: java.net.http, RestClient and the NativePath client against one server (rps, latency, CPU and heap per request).
- `capacity` experiment: virtual users find what each server sustains within an SLA, then overload and recovery; timeline view in the dashboard.
- Load generator: virtual users (think time, ramp, route mix) and p95.
- Raw Netty and Tomcat baselines (no Spring) in the throughput and ramp experiments.
- Engine phase histograms (read, queue, handler, respond, reply) and `scripts/profile.sh` (perf).

### Fixes
- Server: request headers freed directly, not through a Cleaner per request; young GC pauses 30-65 ms to 3-7 ms.
- Server: response headers packed without a growing buffer.

## 0.1.0

### Features
- HTTP client engine with Java 22 Panama binding and a Spring `RestClient` drop-in.
- HTTP/1.1 server engine with TLS and a Spring WebFlux drop-in; Spring MVC on Tomcat (experimental).
- Secret mode: `NativeSecret`, secret sources (file, Keychain, Vault), the `NativePath` facade.
- PostgreSQL JDBC driver, phase 1 (`np-db`).
- Zeroizing allocator and process memory scanner for residue proofs.
- `np-bench`: the whole benchmark experiment in one command, on one machine or two.
- Experiments: stress ramp, soak, scan under load, correctness under load, database under load.
- Every experiment's settings tunable from the CLI, a config file or the dashboard; check or full run.
- Dashboard: live view of a run, results of earlier runs, and a form to start new ones.
- `bench.sh`: the whole benchmark in Docker (only Docker needed), single machine or two; the image is the release bundle.
- Linux bundles built on Rocky Linux 8 (glibc 2.28): they load on RHEL-family and Ubuntu 20.04+ hosts.
- Release publishes only after the Docker check passes (memory proofs with controls, wiring, database).


### Changes
- Renamed from EnclaveHttp: modules `np-*`, packages `ro.bitboy.nativepath`, library `libnativepath`.
