# Can We Keep Secrets Out of the JVM Heap Without Paying for It?

*An extended, code-checked account of NativePath: what the implementation does, what the measurements show, and which claims the evidence cannot support.*

**Review scope:** current working-tree code inspected on October 21, 2026; benchmark archive from October 20, release label `0.1.2`; historical EnclaveHttp development notes where explicitly identified. This is a source review and interpretation of existing measurements, not a security audit or a fresh benchmark run. The archive does not record a precise source SHA, so current code and historical measurements are kept distinct.

A request ends. Its credentials do not necessarily end with it.

In a Java service, a password or bearer token can travel through configuration, HTTP headers, TLS buffers, JSON parsing, and application objects. Those layers have different owners and different ideas of when data is no longer needed. Making a Java object unreachable is not an instruction to overwrite every copy of its contents.

I built NativePath to investigate whether changing the I/O boundary could give an application more control over that lifetime. Business logic stays in Java. Selected networking and database operations run in Rust. Java reaches them through Project Panama's Foreign Function & Memory API.

The interesting result is a set of tradeoffs. Selected secret paths produced clean marker scans. Several client workloads delivered more throughput while allocating substantially less on the Java heap. Raw server workloads often lost to Netty. A two-hour HTTPS run sustained its requested traffic, but its worst latency intervals coincided with memory scans. The benchmark itself also needed closer scrutiny: some comparisons did not establish which server they were measuring.

The question in the title is useful precisely because the answer is conditional.

## The experiment starts with a threat model

The target is unnecessary plaintext exposure in the JVM heap and residual copies in process memory after supported operations finish. It is not protection from an attacker who can inspect a live native allocation, execute arbitrary code in the process, or read secrets after application code has deliberately converted them to Java objects.

This is an old problem with a modern implementation opportunity. Pridgen, Garfinkel, and Wallach examined latent secrets in HotSpot in [*Present but Unreachable*](https://www.cs.rice.edu/~dwallach/pub/persistent-but-reachable.pdf). DWP's [secure-strings](https://github.com/dwp/secure-strings) offers encrypted storage with sealing and revealing operations; its documented usage still accepts and returns Java strings. Neither observation makes NativePath novel by itself. The contribution being tested is the integration of explicit native ownership, Spring adapters, and measurable cleanup behavior.

Java's FFM API became final in JDK 22. [Oracle's Java engineering team describes its introduction here](https://inside.java/2024/09/17/jdk-23-available/). It makes calling native functions and accessing foreign memory practical; it does not make the called code or its lifetime conventions automatically correct.

The early project name, EnclaveHttp, implied a stronger boundary than the implementation provides. There is no hardware enclave. Rust and Java share one process and its privileges. NativePath is a better description of the route the data takes.

## The architecture visible in the code

The Rust side has HTTP client and server engines, shared memory primitives, a PostgreSQL protocol engine, and one C ABI library. The Java side has FFM bindings, native resources, Spring integration, and JDBC-facing classes.

The client uses reqwest and rustls. The server uses hyper's HTTP/1.1 implementation and rustls. A server lane owns its event loop and connections, and Java workers pull requests through the native boundary. This avoids native-to-JVM callbacks in the request path. Blocking work on a lane can still delay that lane's connections; moving I/O to Rust does not make blocking handlers disappear.

Three boundaries matter more than the language names:

| Boundary | What must be controlled |
|---|---|
| Network → native library | HTTP/TLS buffers, protocol parsing, allocation, and reuse |
| Native library → Java | Whether Java receives an opaque handle, native view, or heap copy |
| Java → business logic | Whether application code creates strings, arrays, DTO fields, exceptions, or logs containing secrets |

The third boundary remains the application's responsibility. A drop-in transport can change the first two while leaving Java materialization unchanged.

## `NativeSecret` is a resource, not a security boundary

[The implementation](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-binding/src/main/java/ro/bitboy/nativepath/NativeSecret.java:43) stores an address, length, capacity, a memory segment, a heap-copy flag, and cleanup state. Closing it invokes native wipe-and-free through a `Cleaner.Cleanable`; the cleaner is also a fallback for an abandoned resource.

The name requires qualification. The default constructor and `allocate(length)` allow heap copies. A protected instance must have `heapCopiesAllowed=false`. Its `toByteArray()` and `toUtf8String()` methods then reject the conversion. File-backed secrets are constructed with that restriction.

Consequently, these are different statements:

- “The value is represented by `NativeSecret`.”
- “The value's wrapper rejects its convenience methods for making heap copies.”
- “No application code can ever copy this value.”

Only the second describes the opt-in guard. The third is false: `asMemorySegment()` intentionally exposes bytes that application code can read and copy.

The lifetime boundary also needs care. The segment is created from a raw address with `MemorySegment.ofAddress(...).reinterpret(...)`. `close()` marks the wrapper closed and frees the native allocation; it does not invalidate a previously returned segment. Later calls through the wrapper check the closed flag, but a segment already held by the caller does not go through that check.

A caller must therefore stop using all borrowed views before close and must not race a read against close. Keeping an old view is a native lifetime hazard. The atomic closed flag prevents duplicate wrapper cleanup; it does not make borrowing and closing concurrently safe.

Some source comments describe stronger properties, including physical-memory and zeroize wording, than the implementation establishes. The article follows the code: an owned allocation is wiped through the project's bulk-wipe routine and freed. That is not a guarantee about every physical copy, CPU register, swap page, or escaped Java view.

## Wiping is about allocations and reuse

[The allocator](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-memory/src/alloc.rs:14) uses a bulk zeroing write, passes the pointer through `black_box`, and applies a compiler fence. Its deallocation wrapper wipes the allocation's layout size before returning the block to the underlying allocator.

Reallocation is deliberately implemented as allocate, copy, wipe, and free. Calling the underlying allocator's ordinary realloc could release the old block without first clearing it. The explicit move gives the implementation control over that step, at the cost of extra work during growth.

The wrapper is registered as the global allocator in [the native library](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-ffi/src/lib.rs:20). That covers allocations routed through this Rust allocation path, including participating Rust dependency buffers. It does not replace the JVM allocator, erase arbitrary Java objects, or establish control over every allocation a foreign C library might make.

An allocator only acts when a block is freed or moved. A live connection can retain plaintext in a buffer even if every eventual free is wiped. Reusable buffers therefore need explicit lifecycle handling as well. Client stream buffers are cleared between chunks and on drop; `SecretBuffer` clears its full vector allocation on cleanup.

The EnclaveHttp notes record how this distinction emerged. Wiping only the final body left intermediate copies unaddressed. Adding zeroization broadly then exposed a performance problem in the byte-wise wiping approach. The later bulk wipe reduced that cost. These are development findings, not measurements newly reproduced in the Linux archive.

The [RustCrypto zeroize documentation](https://docs.rs/zeroize/latest/zeroize/) provides useful background on temporary copies and reallocations. NativePath's current bulk wipe is its own implementation; citing zeroize is not evidence that this implementation has inherited an audit or universal erasure guarantee.

## Connection isolation is a real cost

The old memory tests found a request credential in a live connection's write buffer after application resources had closed. That influenced the pooling policy.

In [the client request path](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-http-client/src/lib.rs:301), isolation is selected if global isolation is enabled, a body is marked sensitive, or any request header is marked sensitive. The isolated client disables idle pooling with `pool_max_idle_per_host(0)`.

That code is more precise than saying “secret mode disables pooling.” A protected response does not, by itself, require every request to be isolated; sensitive outbound data and explicit isolation settings drive this branch. Ordinary requests can still use the shared pooled client.

Avoiding idle connection reuse changes setup, teardown, and port behavior. That cost must be measured with the application's actual TLS and request pattern. The ordinary pooled-client throughput results below cannot estimate it.

## Server cleanup has a different ownership strategy

[ServerExchange](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-binding/src/main/java/ro/bitboy/nativepath/ServerExchange.java:102) separates wiping a request body from releasing its allocation. Its body view is associated with `Arena.ofAuto()` and cleanup tied to reachability. The normal response/close path zeros the body immediately; freeing occurs later when the associated view becomes unreachable.

The internal header block is freed directly because that raw block does not escape through the public header API. Public, non-secret header values can become Java strings when read. Named secret headers have native access paths instead. Method, request target, and peer address are also materialized for Java use; a secret in a URL is outside the intended protected path.

This distinction matters for both security and memory graphs. A buffer being cleared does not imply that RSS immediately falls. Delayed freeing is also not equivalent to retaining plaintext. Conversely, `NativeSecret`'s immediate free does not share the server-body view's reachability strategy; the two resource types should not be described interchangeably.

The current WebFlux response adapter collects response buffers before sending. Its source does not justify a general streaming/SSE equivalence claim. Likewise, FFI panic guards catch Rust unwinding panics; they are not containment for invalid pointers, undefined behavior, process aborts, or signals. These remain engineering limits of the boundary.

## What was actually run

The main archive is `20261020-124239-run`, dated October 20, 2026, with release label `0.1.2`. Server and client ran on separate Linux VMs, each assigned four cores and approximately 8 GB of memory. Host records identify RHEL 9.6, Docker, OpenJDK 23.0.2 on the server, four pinned cores, and image identifier `18866ad54d4c`.

The records do not supply the CPU model, an exact source SHA, proof of exclusive physical cores, or a full immutable image digest. These are reproducibility limitations. A separate load generator removes one important source of interference, but does not make VM timing immune to the environment.

Short throughput experiments used three rounds and reversed configuration order on alternate rounds. The client harness warmed each configuration for two seconds and measured for ten seconds, using the same JVM and retained client instances across configurations. The results are arithmetic means, not confidence intervals or independent JMH forks.

The longer soak ran for two hours. The database was absent from this Linux run. Capacity is deliberately absent from this article's result tables.

## Memory evidence: strong controls, scoped conclusions

The baseline suite reported eight scenarios meeting their expected outcomes. Its server Java-string control found 44 token matches and 64 password matches; the protected path found zero. The client tests also found native-held markers while alive and none at the recorded post-close scan points.

The under-load suite recorded three cycles of five scenarios:

| Scenario | Cycle 1 token/password | Cycle 2 | Cycle 3 |
|---|---:|---:|---:|
| Java-string control | 4 / 8 | 12 / 12 | 25 / 27 |
| Protected request | 0 / 0 | 0 / 0 | 0 / 0 |
| Handler error | 0 / 0 | 0 / 0 | 0 / 0 |
| Mid-body abort | 0 / 0 | 0 / 0 | 0 / 0 |
| Stalled-body timeout scenario | 0 / 0 | 0 / 0 | 0 / 0 |

The [control route](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-benchmarks/src/main/java/ro/bitboy/nativepath/benchmarks/ScanServerApp.java:53) retains its last few Java strings in a deque. That is intentional: a control that GC overwrites before the scan cannot show that the scanner works. It establishes detectability, not how long an ordinary unreachable string would naturally remain. The match counts are observations, not counts of distinct live Java objects.

There is an additional weakness in the under-load experiment. [The shell script](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-benchmarks/scripts/scan_load.sh:60) discards the sender's output and ignores its nonzero exit status. The Python sender also prints responses without asserting every expected protected response or server-side timeout transition. Its abort and timeout modes transmit a full marker within a deliberately incomplete announced body, but their local completion does not independently prove which server state was reached.

Therefore “all 15 scans met their marker expectations” is supported. “All 15 scenarios conclusively validated delivery, rejection, timeout, cleanup, and concurrent load” would overstate the harness. The soak's secret injections have a similar ignored-sender-status weakness.

The positive-control rule checks the sum of token and password matches against one. Both markers were found in these recorded controls, but the rule itself would accept detection of only one of them. That is another reason to preserve the actual counts rather than rely solely on PASS.

## What “whole-process scan” means here

[The Linux scanner](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-memory/src/scan.rs:180) reads `/proc/self/maps`, selects readable mappings, and copies chunks with `process_vm_readv`. It excludes selected special mappings and its own scratch area, skips failed reads, and searches for exact byte sequences. Overlap between chunks handles markers spanning a chunk boundary.

The process continues running. This is not an atomic snapshot, complete memory-coverage attestation, or exhaustive search of UTF-16, escaped JSON, encoded tokens, fragments, registers, and kernel buffers. A failed read can be skipped without invalidating the entire result as long as some memory was readable.

Scanning also copies memory into a temporary scratch mapping. That buffer is explicitly wiped on drop, but the observer itself has allocation, memory-bandwidth, CPU, and scheduling costs. A scan is an active workload, not a free measurement.

No wiping-disabled end-to-end run is included in this archive. The strongest defensible statement is: the scanner did not detect the tested plaintext byte sequences at the recorded scan points on the exercised protected paths, subject to the harness and coverage limits above.

## Client results: the response representation matters

The direct paths in [ClientBench](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-benchmarks/src/main/java/ro/bitboy/nativepath/benchmarks/ClientBench.java:63) do different application-facing work:

```java
// JDK path: receive and materialize a Java byte array.
jdk.send(request, HttpResponse.BodyHandlers.ofByteArray()).body().length;

// NativePath path: receive a native body and inspect its size.
try (NativePathResponse response = np.get(url)) {
    return (int) response.bodyAsSecret().asMemorySegment().byteSize();
}
```

The snippet abbreviates request construction, but preserves the materialization difference in the actual harness. The NativePath request still receives the body; checking its size is not a content-consumption benchmark.

At 16 threads:

| Body | JDK, req/s | NativePath, req/s | Change | JDK allocation, B/request | NativePath allocation, B/request |
|---|---:|---:|---:|---:|---:|
| 256 B | 21,886 | 28,756 | +31% | 17,385 | 421 |
| 8 KB | 18,408 | 25,342 | +38% | 33,712 | 425 |
| 64 KB | 8,370 | 9,613 | +15% | 150,375 | 483 |
| 512 KB | 1,260 | 1,295 | +3% | 1,088,955 | 1,044 |

The allocation reduction is useful when the application can retain a native representation. It is not the cost of delivering the same Java array through two interchangeable engines. Converting the NativePath body to a `byte[]` would do additional work this direct path intentionally avoids.

The Spring comparison is closer to equivalent output: both `RestClient` calls request `body(byte[].class)`. At 512 KB and 16 threads:

| Spring adapter | Throughput | Java allocation/request |
|---|---:|---:|
| JDK-backed | 1,276 req/s | 1,635,511 B |
| NativePath-backed | 1,240 req/s | 533,538 B |

Here the allocation advantage remains substantial, while throughput does not improve. These are ordinary HTTP paths, not a measure of sensitive-header isolation. The client harness checks response length; it is not the separate full status/hash/echo correctness test.

Java allocation is measured with total-thread allocated-byte counters around the workload. It includes Java harness and worker overhead, and excludes Rust allocations. It is neither live heap size nor total process memory. At 512 KB the direct clients reached roughly 661–679 MB/s, suggesting a shared bottleneck; the experiment does not identify whether that bottleneck is network, server work, copying, or another resource.

## Raw HTTP server results include losses

These comparisons use the raw Java-worker NativePath server and explicitly instantiated raw Netty, bypassing the disputed Spring Boot selection. Three-round mean throughput:

| Workload | NativePath + Java | Raw Netty | Change |
|---|---:|---:|---:|
| Hello, 16 connections | 31,997 req/s | 38,019 req/s | −16% |
| Hello, 64 connections | 69,187 req/s | 84,167 req/s | −18% |
| 64 KB, 16 connections | 10,163 req/s | 10,236 req/s | −1% |
| JSON echo, 16 connections | 30,321 req/s | 37,683 req/s | −20% |

Both raw JSON routes echo bytes; these rows do not measure equivalent JSON object mapping in Spring. At 64 hello connections, NativePath used approximately 25.3 µs of server CPU per request against 12.4 µs for raw Netty. That is a material cost, not evidence of universal native superiority.

Run-to-run spreads also matter. For raw hello at 64 connections, `(max − min) / mean` was about 5.1% for NativePath/Java and 4.0% for Netty. That does not turn three rounds into a statistical study, but it makes the observed gap harder to dismiss as a tiny fluctuation within this run.

## Why the attractive Spring and TLS multipliers are excluded

The historical Spring app places Tomcat and Netty on the same classpath and does not explicitly select Netty for its comparison configuration. The archive retains measurements named `spring_netty`, `netty_jdk_tls`, and `netty_openssl_tls`, but does not establish the running server identity or native TLS provider.

A label is an intention, not runtime evidence. The current code now explicitly selects Netty and checks identity for capacity testing, but those edits were made after the archived measurements. They cannot retroactively validate them.

This is why the article excludes the previously quoted Spring and TLS speedup ratios. NativePath's absolute recorded traffic remains an observation; the unsupported competitor attribution is removed. Capacity also stays excluded: configured connection limits and reaching a search cap are not measurements of an intrinsic throughput or user ceiling.

## The two-hour HTTPS run

The soak targeted 60% of a short measured peak: 20,892 requests per second on 64 connections, using the NativePath Spring HTTPS scan server. It completed **150,421,941 requests in 7,200 seconds with zero reported errors**.

| Metric | Recorded observation |
|---|---:|
| p50 | 0.824 ms |
| p95 | 2.296 ms |
| p99 | 5.852 ms |
| p99.9 | 1,617.512 ms |
| RSS start / end / peak | 500.7 / 664.4 / 666.1 MB |
| Final-hour fitted RSS slope | +0.326 MB/hour |
| Recorded protected scans | 7, each with zero marker matches |

There was also one positive control scan, which found six token matches and six password matches. The reported RSS slope uses the final hour. Total start-to-end growth was 163.7 MB. The late slope supports stabilization in that period; it does not prove the absence of a smaller leak, explain the earlier growth, or predict longer runs.

The soak did not verify response contents (`verified=0`). Separately, the correctness experiment recorded 5,525,104 verified responses across both configurations, including warm-up verifications, with zero mismatches or errors. Its checks cover the exercised routes and assertions, not complete HTTP conformance or 5.5 million NativePath-only requests.

## The long tail is associated with scanning

The aggregate p99.9 initially looked unexplained. The retained event stream permits a narrower investigation.

There are 7,200 measurement ticks and eight scan records. A scan record contains its end timestamp and elapsed milliseconds. For this analysis, I defined a deliberately broad association window from **one second before the inferred scan start through ten seconds after its recorded end**. The allowance covers event timing and post-scan queue drainage; it is an analysis choice, not a measured boundary of scan impact.

Those windows contain 113 of the 7,200 ticks, about 1.6% of the run.

| Tick-level condition | Total ticks | Within scan-associated windows |
|---|---:|---:|
| p99 > 100 ms | 33 | 32 |
| p99 > 1 second | 27 | 27 |

The largest tick p99 outside the windows was 153.496 ms. Inside them it reached 6,233.068 ms.

The code provides a plausible mechanism: the scan endpoint starts one token scan in a future, performs the password scan synchronously in the request handler, and waits for the other result. That can occupy a lane worker while the scan touches process memory. CPU and memory-bandwidth effects can reach other workers too.

This supports an association and an implementation-based explanation worth testing. It does not isolate scanning from GC, VM scheduling, or other events. There is no matched scan-disabled soak in the supplied archive. Nor can per-second percentiles be subtracted or averaged to reconstruct an aggregate scan-free p99.9. The 1.62-second value remains part of the observed run and should not be edited away.

The [analysis script](analysis/soak_scan_timing.py) and [derived counts](analysis/soak-scan-timing.json) accompany this article so the window definition is inspectable.

## Database work is implemented, but the evidence is separate

The current repository has more than a proposed database adapter. The Rust engine speaks PostgreSQL's protocol, and the Java layer exposes JDBC-facing behavior. Statement execution builds extended-protocol messages, receives results, and wipes reusable protocol buffers after the operation. The TLS stream uses rustls's unbuffered API so the engine owns the relevant data buffers.

The scope remains limited. Rows are collected in native storage, and converting ordinary columns to Java values can create heap objects. Secret-column access must use the protected getter path. SQL text is still Java data, so embedding a secret in SQL bypasses the intended parameter path.

Earlier Apple M1 measurements, with PostgreSQL in Docker on October 18, found 10,000-row retrieval roughly three times slower and 1,000-insert batches roughly forty times slower than pgjdbc. The current prepared-statement batch path iterates executions rather than establishing a pipeline advantage. That code is consistent with an obvious optimization opportunity, but does not independently explain every measured millisecond.

The October 20 archive contains no database experiment. It cannot establish a fully protected or faster client → HTTP server → database application. The article keeps the historical losses visible and does not promote the planned end-to-end workload into a completed result.

## What I would claim today

The supported statement is narrower than “native memory is secure” and more useful than “Rust is faster.”

NativePath provides explicit native representations and cleanup paths that can reduce deliberate Java plaintext materialization. Its baseline marker tests and recorded protected scans show encouraging outcomes under the stated conditions. The client measurements demonstrate how avoiding a Java representation can reduce heap allocation; the Spring materialized-result comparison shows a smaller but still substantial allocation reduction without a throughput win at 512 KB. Raw Netty remains faster in several server workloads.

The evidence also identifies costs and unfinished questions: connection isolation, raw-view lifetimes, scan coverage, sender-side validation, production semantics, performance under real application work, and verification of the comparison stack itself.

For an application whose secrets can remain within supported native paths, those tradeoffs may be worth investigating. The design's value is the ability to name the buffers, their owners, and their cleanup points—and to test those claims—while being explicit about every boundary where that control ends.
