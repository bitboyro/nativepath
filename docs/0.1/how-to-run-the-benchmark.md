# How to run the benchmark

`np-bench` runs the whole experiment from one command: on this machine, or across two machines (the
server under test on one, the load generator on the other), which is how release numbers are taken.
Pick which experiments to run, tune every setting, and watch it live in a browser.

## What you need

Docker. That is all, on every operating system: the image (Rocky Linux 8, a JDK, the release bundle)
brings everything else, so the host can be Ubuntu, RHEL, Rocky, Alma, Debian, macOS (Docker Desktop) or
Windows (Docker Desktop with WSL2).

```sh
np-benchmarks/bench.sh check               # builds the image once, then a quick pass of every experiment
np-benchmarks/bench.sh check --with-db     # adds the database experiment (PostgreSQL as a service)
np-benchmarks/bench.sh serve               # the dashboard on http://127.0.0.1:8765/
np-benchmarks/bench.sh run --only soak --set soak.minutes=240
```

`bench.sh` takes any `np-bench` command and options (everything below applies to it) and writes results
to `./np-results`. `NP_NO_BUILD=1` skips the image build; `NP_IMAGE` names another image.

| Where | What it is for |
|---|---|
| Docker on a laptop (Docker Desktop runs a Linux VM) | wiring, correctness and the memory proofs; **not timing** |
| Two Linux hosts, each running the image | numbers you will quote (section 4) |
| No Docker (a source checkout or a release bundle) | advanced; needs a JDK 22+, Python 3.9+, `bash`, `curl`, `openssl` and, from source, Rust and Maven. `np-benchmarks/np-bench` runs directly |

The memory proofs run on the container's Linux kernel and allocator. The container needs
`SYS_PTRACE` (compose sets it) so the scans can read the process (`process_vm_readv`).

## 1. Check first, then run

```sh
./np-bench check          # quick pass of the chosen experiments: proves the setup works
./np-bench run            # the real experiment
./np-bench check --only soak,scan          # just those
./np-bench run --with-db                   # add the PostgreSQL experiment
```

`check` uses short durations (1 round, 2 seconds, a 2-minute soak) and skips the quiet-machine wait. It
measures nothing worth quoting; it proves that the real run will work, and exits non-zero naming the
step that failed. `run` uses the full defaults.

## 2. The experiments

Every experiment collects everything it can measure. The dashboard shows the relevant metrics and the
*More metrics* button shows the rest; `events.jsonl` has all of it.

| Name | What it does | Relevant metrics |
|---|---|---|
| `preflight` | tools, platform match, native library, ssh | pass/fail |
| `memory` | one-shot whole-process scans for planted secrets, client and server | found vs expected |
| `throughput` | closed loop: the most each server delivers, per configuration, alternating order each round | req/s, p99, CPU per request, spread |
| `ramp` | open loop: offers fractions of the peak rate, finds the knee | offered vs served, p99 per step, knee |
| `scan` | secrets sent under background load, normally and through failure paths (a handler that fails, a request dropped mid-body, a stalled upload); the server scans its own process | found vs expected; the control must be found |
| `correct` | every response checked against what its route must return, for NativePath and Netty | verified, mismatches |
| `soak` | steady open-loop load for a long time; the server host sampled; scans at intervals | resident memory trend (MB/hour against a limit), heap, GC |
| `db` | pgjdbc against NativePath, same SQL, same pool, sustained; the driver's memory scans afterwards | ops/s, p99, pool use |

A scan's **control** sends the same secret the usual Spring way and holds on to it. A control that
finds nothing means the scanner is blind, and the experiment fails.

## 3. Settings

Everything tunable is in `np-benchmarks/bench.config.json`; `np-bench list` prints it.

```sh
./np-bench run --set soak.minutes=240 --set ramp.slo_ms=5 --set throughput.configs="spring_nativepath spring_netty"
./np-bench run --config my-settings.json      # {"soak": {"minutes": 240}, "ramp": {"slo_ms": 5}}
```

Every setting has a default for `run` and a shorter one for `check`. A bad name or value is refused
with a message, before anything starts. The dashboard's *New run* form is generated from the same file.

## 4. Two machines (recommended for numbers you will quote)

Each machine needs Docker and ssh key login from the machine you run the command on (a laptop is
fine); nothing else is installed on them. The same image runs on both, so what is measured does not
depend on their distributions (Ubuntu, RHEL, Rocky, Alma ... all work).

```sh
./np-benchmarks/np-bench setup user@10.0.0.5 user@10.0.0.6    # checks Docker, tunes the hosts (sudo, until reboot)
./np-benchmarks/bench.sh check --server user@10.0.0.5 --client user@10.0.0.6 --server-ip 10.0.0.5
./np-benchmarks/bench.sh run   --server user@10.0.0.5 --client user@10.0.0.6 --server-ip 10.0.0.5 --cores 4
```

- `--server-ip` is the address the client reaches the server on.
- `--cores N` gives each container that many cores (`--cpuset-cpus`) and pins the server and the load
  generator inside it.
- The image is sent to a host with `docker save | docker load` when the host lacks that exact image, and
  the containers use the host network, so the load goes over the real network.
- On RHEL-family hosts with firewalld, open the benchmark ports on the server
  (`sudo firewall-cmd --add-port=18000-18999/tcp`); `setup` says when this applies.
- A certificate for the server's address is made for each TLS experiment, on the server machine.
- `machines.txt` and `results.json` record what Docker reports about each host (kernel, cores, memory)
  and the image id, not just the container's view.
- The `db` experiment runs on the machine running `np-bench` (a PostgreSQL service next to it).
- Without Docker on the hosts: `--runtime native` rsyncs a checkout (or `--bundle` copies a release
  bundle) and needs a JDK and tools there (`np-bench setup --native HOST` does this on Ubuntu).

## 5. The dashboard

```sh
./np-bench serve                    # http://127.0.0.1:8765/
./np-bench run --serve              # run, and serve this run's dashboard
```

| Tab | What it is |
|---|---|
| **Live** | follows the run in progress: a gauge or chart set per experiment, updated every second |
| **Results** | every run in the results directory; open one for its summary and per-experiment views, or open a `results.json` from another machine (button or drag and drop) |
| **New run** | choose the experiments, quick check or full run, every setting, and the machines; start and stop runs |
| **Demo** | the same views on simulated data |

When a run finishes, the page switches from live views to the run summary. A table row in the throughput
view charts that measurement. Deep links: `#results/<run>/<experiment>`, `#new`.

The server listens on 127.0.0.1. Starting and stopping runs needs the session token it prints and is
refused from any other machine, so `--bind 0.0.0.0` lets others watch but not run anything. Through
`bench.sh serve` the port is mapped to the host's loopback only and the server trusts that mapping
(`--trust-bind`); runs started there are single-machine.

## 6. Output

Each run writes `np-results/<timestamp>-<mode>/`:

| File | |
|---|---|
| `events.jsonl` | every tick, sample, scan and result as it happens, one JSON object per line |
| `results.json` | the finished summary: a verdict per experiment, tables, downsampled series; self-contained, safe to share |
| `run.json` | the settings, machines and commit |
| `<experiment>.log` | what the experiment printed |

## Reading the results

- Compare within a run, never across machines.
- A run waits for the machine to be idle (`global.quiet_idle`, default 85% for `run`, 0 for `check`)
  and aborts after 10 minutes rather than measure noise.
- Spread between rounds is in the tables. Differences under the spread, or under about 20% on a laptop,
  are not evidence.
- A memory trend needs at least 10 minutes of soak before it gets a verdict.
- Include the machine description (`results.json`, `machines.txt`) when you share numbers.

## Pitfalls we have hit

- macOS runs out of client ports on handshake tests; the one-machine check notes and skips that scenario.
- A load generator on the server's machine takes the server's CPU: use two machines or pin cores.
- Leftover servers on the test ports invalidate a run: the scripts abort if a port is taken.
- Docker Desktop's port forwarding adds latency to the database experiment: use Linux.
- An IDE's Java language server can recompile `target/classes` with errors; np-bench runs from jars.
- OpenSSL 1.1.1 (RHEL 8) needs a config file, not `-addext`, for the test certificate; the scripts do this.
