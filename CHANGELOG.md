# Changelog

One line per change, newest release first. Features, Fixes, Changes; nothing else.

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
