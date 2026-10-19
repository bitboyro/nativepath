---
name: np-benchmark-engineer
description: Rules for writing and running NativePath tests, memory proofs and benchmarks, and for reporting their results. Use when adding or changing a JMH benchmark, load test, residue scan, np-bench experiment, CI benchmark job, or when publishing measured numbers.
---

# NativePath tests and benchmarks

You build the evidence the project's claims rest on. A benchmark that cannot fail, a number without
its machine, or a comparison that favours one side is worse than none.

## Principles

1. **Pass/fail first, speed second.** Correctness and memory proofs run before any timing and stop the
   run if they fail. Every proof has a **control** that must find the planted value; a scan that cannot
   find a known copy proves nothing.
2. **Fair comparisons.** Same app, same payloads, same TLS certificate and protocol version, same
   JVM flags and heap, each configuration in its own process, warm-up before measuring, odd rounds in
   order and even rounds reversed, a quiet-machine gate before each run. Compare against the strongest
   realistic alternative (Netty with BoringSSL, not only JDK TLS; pgjdbc with its defaults).
3. **Noise is the default assumption.** On a laptop (mixed performance/efficiency cores) differences
   under about 20% are not evidence. Report spread between rounds. Three runs giving three answers
   means the claim is withdrawn, not averaged.
4. **Publish whatever the result.** A loss goes in `results.md` next to the wins, with the reason.
   Never remove a configuration because it looks bad.
5. **Say where it was measured.** Machine, cores, OS, JDK, commit, date, whether the load generator
   shared the machine. Release numbers come from two Linux machines (server and load generator
   separate, each pinned) via `np-bench run --server ... --client ...`.

## Where each question is answered

| Question | Where |
|---|---|
| Correctness, memory proofs | any machine; CI on every push (`ci.yml`); `np-bench check --only memory` |
| Linux memory proof (allocator wiping, not the OS) | `np-bench` / `bench.yml` on Linux |
| Effects of 2× or more | developer machine with the quiet gate, or CI |
| Effects of 5–20% (tuning) | two dedicated machines, pinned cores |
| Many-client handshakes | Linux only (macOS runs out of client ports) |
| Database | Linux with Docker (Docker Desktop port forwarding adds latency and exhausts ports) |

## Docker is the way to run it

- `np-benchmarks/bench.sh check` is the portable check: it builds the image (Rocky Linux 8, the release
  bundle inside) and runs `np-bench` in it. CI gates releases on it (`docker-check.yml`).
- Remote hosts run the same image in a container; `hosts.sh` hides that (`on`, `sync_tree`, `host_put`,
  `host_get` know about `NP_RUNTIME=docker`). Never reach into a host's tree with `scp`, `cp` or a path
  of your own: use those helpers, or the experiment breaks in a container.
- Tool versions live in `np-benchmarks/versions.env` only. Never pin a JDK, Rust or Maven version
  anywhere else.
- Anything a script needs from the OS must exist in Rocky 8 (OpenSSL 1.1.1, bash 4.4, Python 3.9, no
  `rsync` assumptions on the host). Test new scripts in the image, not only on your machine.
- Timing from Docker on a laptop is wiring only. Published numbers come from two Linux hosts.

## Events, settings and the dashboard (every experiment must follow this)

- **Collect everything, show the relevant.** An experiment records every metric it can (the load
  generator's tick has latency percentiles, errors, shed, MB/s, verified, mismatches; the sampler has
  RSS, CPU, threads, descriptors, JVM heap and GC). Which of them the dashboard shows by default is a
  decision in `dashboard/index.html` (`Rv.<experiment>`); the rest sits behind *More metrics*.
- **Events, not files, are the interface.** Anything the dashboard shows is an event on `events.jsonl`:
  loadgen and samplers write `NPEV {json}` lines on stderr (works over ssh), np-bench routes them. Tag
  load with `LOADGEN_TAG` / `NP_TAG` (`"exp"`, `"config"`, `"scenario"` ...). New event fields go into
  `np_report.py` (verdict, table, series) and the view together.
- **Every knob is a setting.** Add it to `bench.config.json` (type, default, a shorter `check` value,
  min/max, help); scripts read `NP_<EXPERIMENT>_<KEY>`. Never hard-code a duration, a connection count
  or a limit in a script; the UI form and `--set` are generated from the schema.
- **A verdict is a rule written down.** The knee is served >= 95% with p99 under the limit and no
  errors; a soak needs 10 minutes and a slope under the limit; a scan control must be found. Put the rule
  in `np_report.py`, not in the view, and make `check` fail when it breaks.
- **A control that is sometimes found proves nothing.** Under load the collector overwrites a planted
  heap `String` within a tenth of a second, so the scan control retains its value. Test every
  positive control under the heaviest load the experiment applies.
- **Validate results, not just exit codes.** A run that served zero requests, or errors, or mismatches,
  fails (`validate_results` in `np-bench`). Only known platform limits (macOS handshake ports) are
  skipped, and with a printed note.
- Run from jars: an IDE's Java language server recompiles `target/classes` with the Eclipse compiler
  and leaves "Unresolved compilation problem" classes there. `bench_classpath` uses packaged jars.

## How to add things

- **A new experiment** is a script in `np-benchmarks/scripts/` plus an `exp_<name>` function in
  `np-benchmarks/np-bench` and an entry in `bench.config.json`. It uses the `hosts.sh` primitives, so it
  runs on one machine or two; it emits events, honours `check` (finishes in minutes, proves wiring) and
  `run`, and exits non-zero on failure. Add its report builder, its dashboard view, and a row in
  `docs/<version>/how-to-run-the-benchmark.md`.
- **A residue scan**: a separate process (or the real server) sends the canary; the scanning JVM knows
  the value only XOR-masked; scan the whole process (heap, native heap, stacks); scenarios: control,
  value alive in native memory, after request/statement close with the connection open, after client
  close. Expectations are exact (`found 0` or `>= 1`). If you change a canary string, regenerate the
  masked arrays and the helper's plaintext together.
- **A JMH benchmark**: `-prof gc` to report allocation per operation; fixed forks, warm-up and
  measurement iterations; no shared mutable state between forks; avoid dead-code elimination
  (`Blackhole`). Compare against the incumbent in the same JMH class and pool.
- **A load test**: the Rust `loadgen` (closed loop, TLS, new-connection mode), on another machine
  when possible; read the server's own CPU seconds, not the machine's.
- **CI jobs**: tests on push only; long benchmarks on release tags or by hand. Keep `continue-on-error`
  off for proofs.

## Traps we have already hit

- A kept-alive connection retains the last request's header bytes in its write buffer: isolate
  connections that carry secrets.
- rustls's buffered API leaves the last plaintext in its receive buffer: use the unbuffered API for
  anything scanned.
- Closing a shared `Arena` per request serialises threads (a first run gave 1,700 req/s).
- A leftover server on a test port answers your requests: check ports before starting.
- `openConnection` benchmarks leave ports in TIME_WAIT and break the benchmarks that follow on macOS.
- Load generator on the server machine steals its CPU; unpinned runs on one machine compare nothing.
- The first-run Netty numbers on a 2-vCPU runner were unexplained: do not quote what you cannot explain.

## Reporting

Update `docs/<version>/results.md` (table, machine, date, caveats, what is **not** proven) and one line
in `CHANGELOG.md` only when a user-visible number or claim changes. Never edit a historical result to
match a newer one; add the new run beside it.
