# Can We Keep Secrets Out of the JVM Heap Without Paying for It?

*Measuring secret exposure and performance with Spring Boot, Rust, and Project Panama.*

A credential has a longer journey than the API call that uses it. It may start in a configuration value, become an authorization header, pass through a TLS implementation, and end up in an application object. Returning from the request does not tell us which copies remain in memory.

I built NativePath to investigate that journey. The application keeps its Java business logic and Spring integration. Rust handles selected network paths, while Java accesses native resources through the Foreign Function & Memory API.

The question was whether that arrangement could reduce plaintext secret residue while keeping performance practical. The results support a qualified answer: protected paths passed our memory scans, some client workloads used substantially less Java allocation and delivered more throughput, and other workloads were slower. Explicit cleanup also introduced costs that ordinary throughput benchmarks would have missed.

## Start with the secret's lifetime

This problem predates NativePath. Pridgen, Garfinkel, and Wallach studied persistent secrets in HotSpot in [*Present but Unreachable*](https://www.cs.rice.edu/~dwallach/pub/persistent-but-reachable.pdf). An object becoming unreachable is a different event from its former contents being overwritten.

There are several ways to address parts of the problem. DWP's [secure-strings](https://github.com/dwp/secure-strings) stores a sealed representation and exposes methods to seal and reveal strings. That can improve the stored representation, but its example still receives and returns plaintext Java strings. An I/O library must also consider the buffers used while sending and receiving them.

NativePath takes a different approach on its protected paths: keep selected values in native buffers, expose them to Java as resources, and wipe owned storage when its lifetime ends. Java's FFM API, finalized in JDK 22, supplies the interop mechanism. [Oracle's Java engineering team describes that API here](https://inside.java/2024/09/17/jdk-23-available/).

The early prototype was called EnclaveHttp. That name was misleading: it uses no hardware enclave and provides no separate security domain inside the process. NativePath describes the architecture more accurately.

## What crosses the boundary

The implementation combines Rust HTTP libraries, a C ABI, Java bindings, and Spring adapters. The HTTP client uses reqwest and rustls; the server uses hyper and rustls. Both share memory-handling primitives. A PostgreSQL engine and JDBC adapter extend the same approach to selected database operations.

A protected value can be represented by `NativeSecret` with heap copying disabled. The type also supports ordinary buffers: its default constructor and `allocate(length)` permit heap copies, so the class name alone is not a protection guarantee. Secret-aware APIs can pass it onward without first constructing a Java `String`. For a mixed JSON response, the application can keep named secret fields native while mapping the public fields to ordinary Java objects.

This requires opting in. Replacing a Spring HTTP transport does not make a password field in a Java DTO disappear. A source that first produces a Java string has already placed that value on the heap. Secret sources and consumers both have to participate.

The server also needs explicit ownership rules. Java workers pull requests from native event-loop lanes. Native code does not call back into the JVM. Request data has a defined lifetime, cleanup must run on exceptional paths, and Rust panics must be contained at the foreign-function boundary.

Rust is useful here because it gives the implementation control over these buffers. It does not remove the need to inspect dependencies, FFM lifetimes, cancellation, or concurrent close operations.

## Two bugs that shaped the design

The first lesson was that wiping the final response buffer was insufficient. Intermediate HTTP and TLS allocations could still contain plaintext. NativePath therefore uses a global zeroizing allocator in the native library, alongside explicit wiping of buffers that remain allocated for reuse.

That change initially made large responses substantially slower. The prototype's byte-wise wiping approach was expensive when applied to every freed allocation. Replacing it with the project's bulk wipe implementation changed the result. The lesson was to measure the actual cleanup path, rather than assume that either copying or the Java/native transition dominated it.

The second lesson concerned connection reuse. A memory scan found a request credential still present in a live connection's write buffer after the request had closed. It disappeared only after the client closed.

The protected client now isolates requests carrying secret headers onto their own connections. That addresses the observed residue, but incurs connection setup and teardown. A secret-header request therefore pays for connection setup rather than reusing an existing connection, and teardown can put pressure on the available client ports. The pooled-client measurements below should not be used to estimate that cost.

The title's question therefore has no blanket “free” answer. Some protection costs can be reduced through implementation work. Others change connection behavior and remain visible to the application.

## How the Linux experiment ran

The main results below come from October 20, 2026, using NativePath release label `0.1.2`. A server and a separate client/load-generator VM each had four cores and approximately 8 GB of memory. The hosts ran RHEL 9.6, with the benchmark bundle inside Docker; the recorded server JDK was OpenJDK 23.0.2. The run pinned four cores and recorded image identifier `18866ad54d4c`.

Short throughput measurements used three rounds, with configuration order reversed on alternate rounds. The tables show arithmetic means. Client measurements used the same client JVM, with warm-up for each configuration; they were not independent JMH forks. These are observations from this pair of VMs, not hardware-independent capacity figures or confidence intervals.

The machine records do not include a CPU model or an exact source commit hash beyond the release label. Those omissions limit reproducibility and should remain visible when interpreting the results.

## Memory scans: controls first

The tests scan readable process memory for known plaintext markers, rather than inspect only a Java heap dump. A separate process supplies the secret, and the scanning test keeps its comparison value masked so that the test itself does not deliberately create another plaintext copy.

Every successful negative result needs a positive control. The baseline server control, which used ordinary Java strings, found 44 bearer-token matches and 64 password matches. The protected server path found none.

| Test group | Observed result |
|---|---|
| Baseline memory suite | All 8 scenarios passed their expectations |
| Client body held in native memory | Marker detected while alive; none detected after close, with client open and closed |
| Three large pooled client responses | No marker detected afterward, including with the connection still open |
| Server scans under load | All 15 scans passed: three cycles of control, secure, error, abort, and timeout scenarios |
| Protected scans during the two-hour soak | No marker detected in the recorded periodic scans |

The under-load control deliberately retains its last few Java strings. It tests scanner sensitivity, not the natural lifetime of unreachable Java objects. The sender scripts also discard request output and ignore sender exit status. The negative scans therefore establish recorded marker-search outcomes, but do not independently prove that every intended request or failure path completed.

In the under-load controls, token/password match counts were 4/8, 12/12, and 25/27 across the three cycles. Each corresponding protected scenario reported zero.

On Linux, the scanner enumerates readable mappings, skips selected special mappings, and skips reads that fail. It does not freeze the process or search every possible representation.

These observations support a specific claim: the scanner did not detect the tested plaintext markers at the documented scan points on the protected paths. They do not prove that no transient copy ever existed, that all possible encodings were searched, or that an attacker with process access cannot read a live secret. This run also did not include the counterfactual experiment of disabling wiping and demonstrating failure.

## Client throughput and Java allocation

Each HTTP client contacted the same separate raw Netty server. The direct JDK path creates a `byte[]` with `BodyHandlers.ofByteArray()`; the direct NativePath path receives a native body and inspects its size without creating the equivalent Java array. This compares different response representations. It is useful for applications able to keep data native, but is not a comparison of equivalent materialized Java results. At 16 client threads:

| Response body | JDK HttpClient, req/s | NativePath, req/s | Difference |
|---|---:|---:|---:|
| 256 B | 21,886 | 28,756 | +31% |
| 8 KB | 18,408 | 25,342 | +38% |
| 64 KB | 8,370 | 9,613 | +15% |
| 512 KB | 1,260 | 1,295 | +3% |

The strongest throughput difference was in small responses with concurrency. Large responses converged: at 512 KB, the clients reached approximately 661 and 679 MB/s. A common transfer bottleneck is a plausible explanation, but this run did not isolate it.

Java allocation told a different story. At 512 KB and 16 threads, the JDK client allocated approximately 1,088,955 bytes per request; the direct NativePath client allocated approximately 1,044 bytes. That is a reduction in **Java heap allocation**, not total process allocation. Native buffers still exist and consume memory.

Using Spring's `RestClient` materializes more data on the Java side. At that same size and concurrency, the JDK-backed adapter allocated approximately 1.64 MB per request and the NativePath-backed adapter approximately 0.53 MB. Their throughput was similar: 1,276 versus 1,240 req/s.

These client measurements used ordinary data paths. They do not measure the extra connection cost of a secret header, and should not be presented as secret-mode throughput.

## The server did not universally beat Java

The raw server comparison helps separate the engine and Java boundary from Spring's processing. Here, NativePath means the Rust engine with plain Java request workers; both raw servers echo the JSON body rather than deserialize it.

| HTTP workload | NativePath + Java, req/s | Raw Netty, req/s | Difference |
|---|---:|---:|---:|
| Hello, 16 connections | 31,997 | 38,019 | −16% |
| Hello, 64 connections | 69,187 | 84,167 | −18% |
| 64 KB response, 16 connections | 10,163 | 10,236 | −1% |
| JSON echo, 16 connections | 30,321 | 37,683 | −20% |

For the 64-connection hello case, NativePath also used approximately 25.3 µs of server CPU per request against Netty's 12.4 µs. Reduced Java allocation does not automatically translate into lower CPU cost.

The archive includes Spring and TLS comparisons labeled as Netty. During review, I found that the benchmark allowed Spring Boot to choose an embedded server with both Tomcat and Netty on the classpath. The archive does not establish which server actually ran for those configurations. I have excluded those relative comparisons, including the attractive TLS multipliers. Capacity comparisons are also excluded because the experiment encountered inconsistent connection limits.

## Two hours exposed a different question

The NativePath Spring HTTPS server sustained a target of 20,892 requests per second for 120 minutes, completing **150,421,941 requests with zero reported errors**. Periodic protected scans remained clean.

Resident memory rose from 500.7 MB to 664.4 MB, with a peak of 666.1 MB. The final-hour fitted trend was approximately +0.33 MB/hour. That is evidence of late-run stabilization; it does not mean the process grew by only 0.33 MB/hour throughout the experiment.

Latency deserves separate attention. Aggregate p99 was 5.85 ms, while p99.9 was approximately 1.62 seconds. The event timeline provides a useful association: all 27 measurement ticks whose p99 exceeded one second occurred in scan-associated windows. Those windows extend from one second before the inferred scan start through ten seconds after the recorded scan end, covering 113 of 7,200 measurement ticks. The largest tick p99 outside them was 153.5 ms. The scan endpoint performs one scan synchronously inside the request handler, so it can occupy a serving lane. This makes scanning a plausible contributor; the broad timing windows and absence of a scan-disabled control prevent a causal conclusion. These per-second percentiles do not allow us to recompute the aggregate p99.9 without the scan traffic.

The soak also did not enable response-content verification (`verified=0`). It demonstrates sustained traffic and the recorded resource behavior. The separate correctness experiment verified 5,525,104 responses across NativePath and the comparison server with zero mismatches; those checks covered status, expected size, hashes, and echo behavior, not every HTTP semantic.

## What this establishes—and what remains open

The project now has evidence that selected native secret paths can pass whole-process marker scans, including after several failure scenarios, while retaining Java and Spring application code. It also has evidence of much lower Java allocation, workload-dependent client gains, and real server regressions.

The database driver remains an early implementation. Earlier measurements on an Apple M1 with PostgreSQL in Docker, on October 18, 2026, found large results roughly three times slower and batches roughly forty times slower than pgjdbc. No database experiment ran in this Linux archive, so it cannot establish an end-to-end HTTP-to-database security or performance result.

There is also a concrete lifetime limit in the Java binding: `NativeSecret` exposes an address-backed `MemorySegment`, and closing the secret wipes/frees the allocation without revoking a segment already returned. Retaining that view after close or racing close with a read is outside a safe usage contract. Wrapper checks and the API’s heap-copy guard do not provide isolation from application code.

Other boundaries remain: application-created strings and objects, logs, privileged process inspection, swap, crash dumps, transient stack/register copies, and unexamined dependencies. HTTP/2 and a request-smuggling conformance corpus are also outside this experiment. Existing zeroization work documents why reallocations and temporary copies complicate broad guarantees. [The RustCrypto zeroize documentation](https://docs.rs/zeroize/latest/zeroize/) is useful background on those limits; NativePath's bulk wipe is its own implementation.

For a team whose sensitive values can stay within supported native paths, explicit lifetimes offer something worth investigating: fewer places where plaintext is deliberately materialized, and concrete tests for what remains after use. The decision to adopt it still depends on the workload, the connection behavior, and the cost of maintaining a Java/native boundary.

For the detailed code walkthrough and claim audit, see [the extended version](nativepath-experiment-ultra.md) and [evidence notes](nativepath-evidence.md).
