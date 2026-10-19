# Measured results

Each table says where and when it was measured. These are relative comparisons from the machines
named; they are not capacity figures. `np-bench` reproduces them
([how to run the benchmark](how-to-run-the-benchmark.md)). Differences under about 20% on the laptop
are not evidence either way.

## Memory proofs (pass/fail)

Whole-process scans for canary values after real requests, with the connection still open and after
close. Control scenarios must find the value; protected paths must find none.

| Scan | Result |
|---|---|
| Client: secret request header, secret response body, streamed secret, Spring typed mapping, pooled big responses | 0 copies after use, macOS and Linux |
| Server over TLS: bearer token header and password field read the usual Spring way (control) | 32 / 8 copies |
| Server: secret header + `NativePathSecrets` | 0 / 0 copies |
| PostgreSQL driver: password and secret column, plaintext and TLS, connection open and closed | 0 copies (macOS, PostgreSQL 17) |

Limit: on macOS the allocator clears small freed blocks itself, so a zero there proves no live
copy; the Linux run credits the zeroizing allocator. Not yet done: a run with wiping deliberately
disabled to show the scan fails without it.

## Client (Apple Silicon laptop, loopback, 2026-10-17)

Requests per second, JMH throughput:

| | NativePath `get` | NativePath stream | Spring + NativePath | JDK HttpClient |
|---|---|---|---|---|
| 8 KB, HTTP, 1 thread | 16,135 | 14,220 | 13,940 | 14,176 |
| 8 KB, HTTP, 8 threads | 41,106 | 27,273 | 19,876 | 15,697 |
| 512 KB, HTTP, 1 thread | 4,771 | 5,891 | 5,218 | 3,286 |
| 512 KB, HTTP, 8 threads | 6,498 | 7,331 | 5,183 | 3,968 |
| 8 KB, HTTPS, 8 threads | 30,342 | 22,466 | 17,373 | 16,655 |
| 512 KB, HTTPS, 1 thread | 2,069 | 1,983 | 1,889 | 1,265 |

Small bodies on one thread are level (the loopback round trip dominates). Large bodies are 45–80%
faster than the JDK client. Chunked bodies against an independent server: 241–2,446 MB/s against
178–567 MB/s, with 6–11 MB of JVM allocation against 1.1–1.2 GB.

## Server over plain HTTP (Apple M1, quiet gate, 2026-10-18)

Spring WebFlux on NativePath against Netty, 16 connections:

| Scenario | NativePath req/s | Netty req/s | CPU/req NativePath / Netty | p99.9 NativePath / Netty |
|---|---|---|---|---|
| GET 5 B | 45,900 | 37,100 | 106 / 146 µs | 5.7 / 12.4 ms |
| GET 64 KB | 34,300 | 35,400 | 133 / 135 µs | 7.9 / 8.1 ms |
| POST JSON | 28,000 | 29,800 | 226 / 188 µs | 24 / 17 ms |

There is no consistent difference over plain HTTP: results differ by up to about 20% either way
between runs, and the laptop's mix of fast and slow cores makes close comparisons unstable.
**We do not claim plain-HTTP server throughput beats Netty.** Where the time goes: the lane
machinery costs about 17–20 µs of CPU per request over plain hyper; crossing into Java costs 7–11 µs;
Spring costs 27–46 µs and about 16 KB per request, the same as on Netty.

## Server over HTTPS (Apple M1, 2026-10-18)

TLS 1.3, EC P-256, 16 connections, against Netty with JDK TLS and with BoringSSL:

| Scenario | NativePath | Netty, JDK TLS | Netty, BoringSSL |
|---|---|---|---|
| GET 5 B | 76,100 req/s | 76,300 | 66,600 |
| GET 512 KB | 6,600 req/s, 576 µs CPU | 5,330 req/s, 1,138 µs | 6,310 req/s, 801 µs |
| New connection per request | 3,350 handshakes/s, 288 µs median | 1,020 / 926 µs | 2,380 / 393 µs |

TLS-dominated work is where the engine wins clearly: 3.3× the handshakes of Netty's default, 1.4×
BoringSSL, at a third of BoringSSL's CPU. To be re-run on Linux with separate machines before it is
quoted as a release figure.

## Spring MVC on the NativePath transport against standard Tomcat (GitHub runner, 2 vCPU, 2026-10-18)

One runner with the load generator on it; the comparisons within the run are the result.

- HTTP: 1.9× the requests per second at 43% of the CPU per request at 16 connections; at 64
  connections Tomcat closes the gap.
- HTTPS: 3–3.7× the throughput at 22–30% of the CPU per request; 2.2× the handshakes at 28% of the CPU.
- Not measured: blocking handlers, Tomcat with an FFM OpenSSL provider. Netty numbers from this runner
  were unexplained and are not quoted.

## PostgreSQL driver against pgjdbc 42.7.13 (Apple M1, Docker, 2026-10-18)

Same SQL, same HikariCP pool, `-prof gc`.

| Scenario | TLS | pgjdbc | NativePath | Ratio | Heap/op pgjdbc | Heap/op NativePath |
|---|---|---|---|---|---|---|
| Open a connection (SCRAM) | off | 5.3 ms | 4.3 ms | 0.81 | 276 KB | 6.4 KB |
| One row by primary key | off | 304 µs | 346 µs | 1.14 | 1.9 KB | 4.8 KB |
| One insert | off | 340 µs | 367 µs | 1.08 | 1.3 KB | 1.7 KB |
| 10,000 rows, 4 columns | off | 5.4 ms | 16.8 ms | **3.13** | 3.9 MB | 27.7 MB |
| Batch of 1,000 inserts | off | 7.4 ms | 308 ms | **41.5** | 0.9 MB | 1.4 MB |

Connecting is faster with far less heap; small statements are 4–14% slower; large results are about
3× slower; batches are much slower (no pipelining yet). Published whatever the result.

## Not measured, not proven

- Strictness against request smuggling and malformed input (no corpus yet).
- Server throughput parity with Netty at the median over plain HTTP.
- Small-request client latency against the JDK client.
- Everything on dedicated hardware with two machines: the first release run will add it here.
