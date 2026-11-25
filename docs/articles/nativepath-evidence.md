# Article evidence and editorial decisions

This accompanies [the article draft](nativepath-experiment.md). It records the local evidence behind the text; the links here are local review links, not public download URLs. The article does not depend on the capacity fixes made during this chat, and no new measurements are substituted for the supplied runs.

## Original framing

- [Consolidated project, research, and publication notes](/Users/romanbotnari/Downloads/NativePath_Project_Notes.md): section 7 supplies the working title and controlled-experiment narrative; section 7.3 records the Pragmatic Engineering pitch context. These are planning material, not independent evidence or new instructions.
- [Original EnclaveHttp project](/Users/romanbotnari/.gemini/antigravity/scratch/EnclaveHttp): found in Antigravity's scratch folder.
- [Client performance findings](/Users/romanbotnari/.gemini/antigravity/scratch/EnclaveHttp/docs/CLIENT_PERFORMANCE_FINDINGS.md): performance investigation, incorrect hypotheses, and benchmark traps.
- [Follow-up JMH report](/Users/romanbotnari/.gemini/antigravity/scratch/EnclaveHttp/docs/JMH_REPORT_after_fix.md): intermediate full-body copy, zeroizing allocator, expensive byte-wise wipe, and bulk-wipe follow-up. Historical measurements are not mixed into the October 20 tables.
- [Secret-mode and Spring design](/Users/romanbotnari/.gemini/antigravity/scratch/EnclaveHttp/docs/SECRET_MODE_AND_SPRING_INTEGRATION.md): connection-buffer residue, isolated secret-header requests, Java conversion boundaries, and scanner controls.
- [WireKeep migration notes](/Users/romanbotnari/.gemini/antigravity/scratch/EnclaveHttp/docs/WIREKEEP_MIGRATION.md): historical naming and scope. The current NativePath repository supersedes the proposed WireKeep name and planned module statuses.

## October 20 measurements used

Main run: `20261020-124239-run`, release label `0.1.2`.

| Article claim | Local evidence | Qualification |
|---|---|---|
| Machine/setup description | [machine record](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/throughput/machines.txt), [run settings](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/run.json) | Two VMs, separate server/client; CPU model and precise source SHA absent. Four assigned/pinned cores is not proof of exclusive physical hardware. |
| Memory suite and 44/64 control matches | [memory log](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/memory.log) | Eight scenarios; counts are detected matches, not an inventory of unique allocations. |
| Fifteen under-load scans | [scan log](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/scan.log), [results](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/results.json) | Three cycles, five scenarios each; controls detected markers, protected scenarios reported none. |
| Client throughput and heap allocation | [raw client measurements](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/client/client.jsonl), [results](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/results.json) | Arithmetic means across three rounds. Ordinary HTTP data paths; same client JVM, not independent forks. Heap allocation is not total native/process allocation. |
| Raw NativePath/Java versus raw Netty | [raw throughput measurements](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/throughput/two_hosts.jsonl) | `engine_java` versus `raw_netty`; same named scenarios, three rounds. Raw JSON paths echo bytes. This comparison does not use Spring Boot server selection. |
| Soak requests, errors, latency, scans | [soak log](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/soak.log), [soak load result](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/soak/soak-load.json) | 150,421,941 requests, zero errors; p99 5,852 µs, p99.9 1,617,512 µs; response-content verification disabled. No claim that scans or GC caused stalls. |
| RSS stabilization | [results](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/results.json) | Start 500.7 MB; end 664.4 MB; max 666.1 MB. The +0.325945 MB/hour regression uses the final hour. |
| Correctness | [correctness log](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/correct.log), [results](/Users/romanbotnari/Downloads/Archive/20261020-124239-run/results.json) | 5,525,104 verified responses across both configurations. Includes warm-up verifications; not 5.5 million NativePath-only requests. Comparison server identity is unverified; valid as exercised-response checks, not named-engine performance evidence. |
| Earlier database losses | [versioned results](/Users/romanbotnari/Development/bitboy.ro/nativepath/docs/0.1/results.md), [original database results](/Users/romanbotnari/.gemini/antigravity/scratch/EnclaveHttp/docs/results/2026-10-18/db_driver_bench.table.txt) | Apple M1, Docker, October 18. Explicitly separate from the Linux archive. |

Selected run-to-run throughput spreads, `(max − min) / mean`: client 256 B/16 threads, JDK 3.8%, NativePath 7.4%; client 8 KB/16 threads, JDK 4.3%, NativePath 7.3%; client 512 KB/16 threads, JDK 1.0%, NativePath 2.5%. Raw hello/64 connections: NativePath/Java 5.1%, Netty 4.0%. These describe variation within three rounds, not statistical confidence or independent replication.

## Excluded claims

1. **Capacity:** the second run encountered incomparable connection policies; reaching 30,000 also reached the search cap. No capacity ranking or maximum appears in the article.
2. **Spring Netty comparisons:** the historical benchmark has both embedded servers on its classpath and no explicit Netty factory selection. The retained archive does not prove the actual server identity. Exclude relative `spring_netty`, `netty_jdk_tls`, and `netty_openssl_tls` performance claims.
3. **Native TLS baseline:** a configuration name is not proof that OpenSSL/BoringSSL was actually loaded. No verified provider log accompanies these archived results.
4. **Large-body 45–80% headline:** this belongs to older laptop experiments and did not reproduce in the 512 KB, 16-thread Linux case. Use the current +3% observation without claiming a meaningful large-body speed advantage.
5. **End-to-end HTTP/database protection:** neither supplied run includes a database experiment or the full client → server → database comparison described in the planning notes.
6. **Absolute security, novelty, and HTTP strictness:** scans establish observations at specific scan points; no audit, novel-primitive claim, hardware enclave, full protocol conformance, or wiping-disabled end-to-end control is established.

## External references checked

- [Oracle Java engineering: FFM introduced in JDK 22](https://inside.java/2024/09/17/jdk-23-available/).
- [Pridgen, Garfinkel, and Wallach: Present but Unreachable](https://www.cs.rice.edu/~dwallach/pub/persistent-but-reachable.pdf), author-hosted paper.
- [DWP secure-strings](https://github.com/dwp/secure-strings), project README and its `sealString`/`revealString` example.
- [RustCrypto zeroize](https://docs.rs/zeroize/latest/zeroize/), limits around temporary copies and reallocations. Cited as prior art; NativePath uses its own bulk wipe.

## Publication handoff

The prose and tables are prepared for review. Before external publication, attach the retained raw data and run settings at stable public URLs and link the matching release/source snapshot. Keep the server-identity exclusions if no new measurements are available. Confirm that first-person authorship matches the intended author. This draft does not claim production readiness or independently verified security.

## October 21 code audit and extended article

[Extended article](nativepath-experiment-ultra.md). Reviewed working-tree base commit: `40ce8dbffa28f63616d34a87e0fa31f18c8bd905`, including the uncommitted benchmark-identity/connection-policy edits already described. The October 20 archive supplies a release label rather than this exact hash; the review does not assert binary identity between the local tree and the archived image.

| Finding | Source | How the article now treats it |
|---|---|---|
| Heap-copy guards are optional; default constructor/allocation allows copies | [NativeSecret](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-binding/src/main/java/ro/bitboy/nativepath/NativeSecret.java:43) | The class name alone is not a secret-mode guarantee. |
| A returned raw segment is not revoked by wrapper close | [segment construction](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-binding/src/main/java/ro/bitboy/nativepath/NativeSecret.java:49), [close](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-binding/src/main/java/ro/bitboy/nativepath/NativeSecret.java:177) | Source-level lifetime hazard; no deliberate use-after-free executed. No claim of a proven exploit or safe concurrent borrowing. |
| Rust allocations wiped on free/move, not arbitrary JVM/foreign allocations | [allocator](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-memory/src/alloc.rs:34), [registration](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-ffi/src/lib.rs:20) | No universal physical-memory erasure guarantee; implementation differs from stale zeroize comments. |
| Sensitive outbound headers or body trigger isolation | [request path](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-http-client/src/lib.rs:301) | Response secret mode is not described as automatically disabling pooling for every request. |
| Server body is wiped before reachability-based free | [owned view](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-binding/src/main/java/ro/bitboy/nativepath/ServerExchange.java:102), [wipe](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-binding/src/main/java/ro/bitboy/nativepath/ServerExchange.java:328) | Distinguishes plaintext lifetime from allocation/RSS lifetime, and from NativeSecret's immediate free. |
| Direct client outputs differ | [benchmark calls](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-benchmarks/src/main/java/ro/bitboy/nativepath/benchmarks/ClientBench.java:63) | JDK byte array versus NativePath native body size. The Spring byte-array comparison is included separately. |
| Control strings intentionally retained | [control retention](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-benchmarks/src/main/java/ro/bitboy/nativepath/benchmarks/ScanServerApp.java:53) | Scanner sensitivity control, not an experiment on natural GC retention time. |
| Injection success is not enforced by under-load/soak scripts | [scan injection](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-benchmarks/scripts/scan_load.sh:60), [soak injection](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-benchmarks/scripts/soak.sh:51) | Zero-marker observations retained; complete per-scenario failure-path coverage not claimed. |
| Combined positive-control threshold can pass with only one marker | [control verdict](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-benchmarks/scripts/scan_load.sh:65) | Actual archived controls found both; rule weakness still documented. |
| Linux scanner skips failed reads and selected mappings | [scanner](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-memory/src/scan.rs:180) | Whole-process enumeration, not complete or atomic coverage; exact-byte searches only. |
| Scan handler performs one native scan synchronously | [scan endpoint](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-benchmarks/src/main/java/ro/bitboy/nativepath/benchmarks/ScanServerApp.java:91) | Plausible serving-lane interference; not proof of sole latency cause. |
| WebFlux adapter buffers response chunks | [response adapter](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-http-spring-starter/src/main/java/ro/bitboy/nativepath/spring/server/NativePathServerHttpResponse.java:42) | No general streaming/SSE-equivalence claim. |
| PostgreSQL batches execute statements in a loop | [executeBatch](/Users/romanbotnari/Development/bitboy.ro/nativepath/np-db/src/main/java/ro/bitboy/nativepath/db/pg/PgPreparedStatement.java:86) | Supports identifying an optimization opportunity, not a causal decomposition of historical benchmark time. |

### Reproduced timing analysis

Run:

```sh
python3 docs/articles/analysis/soak_scan_timing.py /path/to/20261020-124239-run/events.jsonl
```

[Derived output](analysis/soak-scan-timing.json) records 7,200 measurement ticks and eight scans. Association windows are inferred scan start minus 1 second through recorded scan end plus 10 seconds; 113 ticks fall in these windows. All 27 ticks with p99 above one second are in the windows; 32 of 33 above 100 ms are in them. Outside-window maximum tick p99 is 153,496 µs.

The windows include the retained-string positive control and seven protected scans. They are deliberately broad, selected for descriptive association, and are not causal estimates. Tick p99 is not individual request latency; removing those ticks cannot reconstruct an aggregate request p99.9. The draft preserves the original aggregate latency and requests a matched scan-disabled run before causal attribution.

No product code changed during this article update and no new benchmark data was generated. Validation consisted of reading the cited code, recomputing figures from retained JSON, executing the read-only timing analysis, checking source-link locations, and checking patch whitespace.
