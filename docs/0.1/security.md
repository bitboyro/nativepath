# Secret mode: what it protects and what it cannot

The guarantees, the evidence and the limits. Read this before relying on NativePath for secrets.

## 1. What "secret mode" guarantees

Build the client with `NativePathHttpClient.builder().secretMode(true)` (or
`NativePathClientHttpRequestFactory.builder().secretMode(true)`). Then:

| Area | Guarantee |
|---|---|
| Response body | Reaches Java only as a `NativeSecret` or as a native chunk (`NativePathStream.nextChunk()` returns a `MemorySegment`). `toByteArray()`, `toUtf8String()`, `NativePathResponse.asString()` and `NativePathStream.read(byte[])` throw. |
| Request secrets | `NativePathRequest.secretHeader(name, prefix, NativeSecret)` takes the value from native memory. Rust copies it into its own wiped buffer and marks the header sensitive. No `String` is ever built. |
| Rust memory | A global allocator zeroes every freed block, including hyper, rustls and reqwest buffers. |
| Connections | A request that carries a secret header always gets a connection of its own, closed when it finishes. A kept-alive connection retains the last request's header bytes in its reused write buffer (found by the scan test, see below); closing it frees and zeroes them. Requests without secret headers keep using pooled connections: the scan found no response data left in a pooled connection (3 responses of 300 KB). `isolateConnections(true)` applies the same isolation to every request. |
| Typed objects | `NativePathSecretClient.getJson(...)` copies the named top-level string fields native to native and maps only the remaining, public JSON with Jackson. |

### Spring Boot configuration

Add `np-http-spring-starter`; the auto-configuration (`NativePathHttpAutoConfiguration`) is picked up
automatically. It creates the `NativePathHttpClient`, an `NativePathClientHttpRequestFactory`, a
`RestClientCustomizer` (so every `RestClient.Builder` Spring Boot injects uses NativePath), and, in
secret mode with Jackson present, an `NativePathSecretClient`. Any bean you define yourself wins.

| Property | Default | Meaning |
|---|---|---|
| `nativepath.http.enabled` | `true` | Turn the whole integration off |
| `nativepath.http.secret-mode` | `false` | Keep response data off the JVM heap; `RestClient` bodies become unreadable by design |
| `nativepath.http.isolate-connections` | `false` | Give every request its own connection (secret-header requests always have one) |
| `nativepath.http.streaming` | `true` | Serve `RestClient` bodies as a native-backed stream instead of buffering |
| `nativepath.http.max-body-bytes` | `268435456` | Limit for buffered bodies (streams have none) |
| `nativepath.http.trust-self-signed` | `false` | Accept self-signed certificates (development only) |

Covered by `NativePathHttpAutoConfigurationTest` (defaults, a real request through the customised
`RestClient`, property binding, secret mode refusing a heap body, switching off, user override).

## 2. Evidence: process memory scan

`ResidueScanTest` scans the test JVM's own memory (JVM heap, Rust heap, stacks; every readable
region of the process) for known values after requests complete. The real values exist only in a
separate Python server; the test holds them XOR-masked and compares on the fly, so the plain text
is never assembled in the client process. macOS only (Mach VM calls). `-Xmx64m`, loopback.

| Scenario | Check | Result |
|---|---|---|
| heap-control | body copied to a Java `String` | **found, 2 copies on the Java heap** (the scanner works) |
| native-only | live body in native memory | found 1 (control); **0 after close**, with the client open and after it is closed |
| secret-stream | request credential and extracted token alive | found (controls); **0 for both after close**, client open and closed |
| spring-secret | typed object through `NativePathSecretClient` | token held natively (control); **0 after close**; server accepted the secret header |
| keychain | macOS keychain item (created with `security`, deleted afterwards) sent as a secret header | 1 copy while alive (control); server accepted it; **0 after close**, client open and closed |
| vault | `VaultSecretSource` KV v2 read, token from a file source | value held natively (control); **0 for value and token after close**, client open and closed |
| facade | `NativePath` call: bearer from a file source, `fetchField` of the response token | token held natively (control); server accepted the credential; **0 for both after close**, client open and closed |
| guards | secret mode refuses heap copies | `toByteArray`, `asString`, `read(byte[])` all throw |

Before the connection change, `secret-stream` and `spring-secret` **failed**: the request credential
stayed in the live connection's write buffer (1 match after everything was closed, gone only when
the client closed). That is why a request with a secret header gets its own connection.
`pooled-big` checks the other direction: three 300 KB responses on one pooled connection leave nothing behind.

The older `MemoryDumpTest` (JVM heap dump) still reports zero residue for NativePath, but its Java
control reports "clean (GC overwrote before dump)", so it does not prove the standard client leaks.
The scan test above is the stronger evidence.

Waiting 300 ms before the post-close scans matters: connection teardown runs on the Rust runtime
asynchronously.

## 2b. What it costs

Sequential requests to an external server, tiny body, median of 3 rounds of 1,500 (macOS, loopback):

| Request kind | Per request |
|---|---|
| pooled connection, no secret header | 83.9 µs |
| secret header (own connection) | 159.6 µs |
| strict isolation (`isolateConnections(true)`), no secret header | 150.7 µs |

- An isolated request costs about 75 µs more here (a TCP handshake on loopback); over a real network, and with TLS, the difference is a full handshake round trip or more.
- Isolated connections end in `TIME_WAIT`: 9,000 isolated requests left 12,003 sockets. macOS has about 16,000 ephemeral ports, so a sustained rate above roughly 1,000 isolated requests per second to one host will start failing with "error sending request". We hit exactly that when a benchmark sent isolated requests flat out. Secret headers suit token exchanges and similar low-rate calls; keep the hot path on pooled, non-secret requests (for example a short-lived access token passed as a normal header after it has been exchanged), or accept the rate limit.
- Large bodies are unaffected: a secret-mode stream of 4 GB ran at 4.6 GB/s against 4.9 GB/s for the plain zero-copy client (one connection either way).

## 3. What it cannot protect (be explicit about these)

1. **The URL** is a Java `String`. Never put tokens in the URL or query string; use a secret header.
2. **Your own code.** Once code calls `new String(...)`, copies a chunk into a `byte[]`, or logs a
   value, the protection is gone. Secret mode removes the library's ability to do this for you; it
   cannot stop application code that reads the native memory and copies it.
3. **Non-secret fields.** `SecretJson` treats every field you do not name as public; those go to the heap.
4. **JCA and other libraries.** `segment.asByteBuffer()` gives a direct buffer, but many providers
   copy to heap arrays internally. Check the specific provider before assuming it is safe.
5. **Swap, hibernation and core dumps.** Native pages can be swapped. macOS encrypts swap by default;
   disable core dumps for the process (`ulimit -c 0`) and heap dumps (`-XX:-HeapDumpOnOutOfMemoryError`,
   `-XX:+DisableAttachMechanism`).
6. **Stack and register copies** of values while Rust code is running, and anything the OS holds in
   socket buffers (kernel memory outside the process).
7. **TLS session keys** stay in Rust memory while a connection is alive (zeroed when it closes); secret
   mode closes connections after each request, which also limits this window.
8. **Other platforms.** The scans run on macOS and Linux (`np-bench`); Windows is not supported yet. On macOS the allocator clears small freed blocks itself, so a zero there proves no live copy remains; the Linux run is the one that credits the zeroizing allocator.
9. **Escapes in secret JSON strings** (for example `é`) are rejected, not decoded, by `SecretJson.extract`.
   `SecretJson.extractAt` (used by the vault source) decodes them natively.
10. **Secret sources** (section 9): the keychain is macOS only; vault supports token auth and KV v1/v2
    only; a source cannot protect a value before it reaches the backend (for example a keychain item
    created with `security -w <value>` on a command line).

## 4. mlock decision

Not implemented. `mlock` would stop secret pages from being swapped, but:
- default per-process limits are small (often 64 KB on Linux, 8 MB or less on macOS without privileges), so large or numerous secrets would fail to lock or cause errors elsewhere;
- locking every body buffer would be wrong for non-secret data;
- macOS already encrypts swap.

Recommendation: do nothing by default; add an opt-in `lockSecrets(true)` later that locks only
`NativeSecret` allocations, fails soft with a warning if the limit is hit, and is documented with the
limit it needs. Treat it as defence in depth, not as a replacement for disabling core dumps.
