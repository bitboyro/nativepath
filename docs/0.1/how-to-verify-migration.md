# How to verify a migration

Three questions: does it still work, is it as fast or faster, and are the secrets really off the heap?

## 1. Still works

- Your existing test suite, unchanged, with NativePath on and then off (`nativepath.http.enabled=false`):
  the same tests must pass both ways.
- Contract check on the wire: record a few real requests/responses before and after (status, headers
  you rely on, body) and diff them. Header order and casing may differ; semantics must not.
- Error paths: timeouts, connection refused, 4xx/5xx bodies, oversized bodies, slow clients.
- Server side: run your existing integration tests against the server with
  `nativepath.http.server.enabled=true`; then confirm the limits (`max-connections`, timeouts)
  suit your traffic.

## 2. Performance

Use the same load script, hardware and JVM flags as the baseline in [how to migrate](how-to-migrate.md#step-0-baseline).
Compare p50, p99 and p99.9, requests per second, CPU per request and heap allocation per request
(`-prof gc` in JMH, or GC logs).

Expect gains mostly with large bodies, many threads and TLS-heavy traffic, and little change for
small plain-HTTP requests ([results](results.md)). Differences under about 20% on a laptop are noise:
repeat on a quiet Linux machine, with the load generator on another machine
([how to run the benchmark](how-to-run-the-benchmark.md)).

## 3. Secrets

Only for the paths you moved to secret mode.

1. Run the application with a canary value in the secret (a long, unique string) and send traffic.
2. Take a heap dump (`jcmd <pid> GC.heap_dump`) and search it for the canary: it must not be there
   for protected paths. (A canary you logged, or put in a URL, will be there; that is your code.)
3. For the process as a whole, `np-benchmarks` contains the scanner used by the project's own proofs
   (`ResidueScanTest`, `ServerResidueScanTest`, `DbResidueScanTest`); `np-bench check --only memory`
   runs them against the library to show the scanner and the library work on your machine.
4. Check limits in [security](security.md): swap, core dumps and logging are yours to handle.

## 4. Record it

Keep the before/after table with the machine description. A migration is verified when the three
sections above are done for every step you adopted.
