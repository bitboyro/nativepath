# HTTP server (`np-http-server`, Spring WebFlux drop-in)

A Rust HTTP/1.1 server engine (hyper, rustls) with Netty-style event loops ("lanes"), reached from Java through `np-binding`. Measured results: [results](../results.md). All properties: [configuration](../configuration.md).

## What it guarantees, precisely

- Sockets, TLS decryption, HTTP parsing and request bodies live in the native engine. Every native
  allocation is zeroed when freed (zeroizing global allocator).
- Nothing reaches the JVM heap unless the application reads it. The request body reaches Spring as
  a `DataBuffer` viewing native memory; it is zeroed as soon as the exchange is answered.
- Headers listed in `nativepath.http.server.secret-headers` never become Java strings, and secret body
  fields can be read as `NativeSecret` (`NativePathSecrets`), which refuse heap copies.
- With TLS, the private key is read by the engine and never passes through the JVM.

What no server can guarantee: once application code turns data into Java objects (Jackson into a
POJO, a `String` parameter), that copy is on the heap. Paths and non-secret header values are
strings, because routing needs them.

## Architecture

```
 client ──TLS──► acceptor thread ── round-robin, once per connection ──► lane 0 … lane N-1
                                                                          │
   each lane: a single-threaded event loop (tokio current-thread, hyper HTTP/1.1, rustls on ring),
   driven by its own Java worker thread inside ServerLane.poll():
     TLS + parse → request (head + body in wiped native memory) → returned to the same thread
     → Spring WebFlux HttpHandler → ServerExchange.respond() → written on that thread's next poll
```

- **Lanes, like Netty's event loops.** Each connection belongs to one lane; one worker thread per
  lane (default one per core) drives the lane's I/O and runs its requests, so a request never
  crosses threads. The acceptor's hand-off happens once per connection. A long-blocking handler
  delays the other connections of its lane, as on Netty.
- **Pull, not push.** Workers pull requests; native code never calls into the JVM (no upcalls).
  One native call returns a whole request (`np_lane_next` fills one struct); one call answers
  it (`np_server_respond`, a critical downcall that copies Spring's heap buffer once).
  An exchange closed without an answer gets 500; a Rust panic becomes an error result.
- **Header and body ownership.** The engine hands both to Java. They are zeroed when the exchange
  is answered and freed only when unreachable (`Arena.ofAuto` + wipe-and-free cleanup), so a stale
  view reads zeros, never freed memory or another request's data. Spring reads headers on demand
  (`NativeRequestHeaders`): only the headers looked up become strings, each decoded once.
- **Spring Boot.** `nativepath.http.server.enabled=true` registers `NativePathReactiveWebServerFactory`
  before Boot's own server selection, which then backs off (tested with Netty on the classpath).
  `server.port`, `server.address` and `server.ssl.certificate` / `certificate-private-key` apply.

Crates: `np-memory` (allocator, wipe, `SecretBuffer`, memory scanner; shared with the
client), `np-http-server` (engine), `np-ffi` (one C ABI library for client and server).

### Settings: lane tuning, overload, blocking handlers

All under `nativepath.http.server.*` (the same names on `NativePathHttpServer.Builder`, and as
`NATIVEPATH_SERVER_*` environment variables for the benchmark servers). The defaults keep the
behaviour measured above; see "Engine performance work" for what each one buys.

| Property | Default | What it does |
|---|---|---|
| `batch` | 1 | Requests a worker takes in a row, while more are waiting, without re-entering its lane's event loop. Replies to the batch are written when the loop next runs. |
| `spin` | 0 | Rounds a worker with nothing to take lets its lane's connections run before it sleeps: one system call instead of a wake-up and a sleep when the next request is close; an idle lane pays one call per round. |
| `work-stealing` | false | A worker with nothing to do takes requests waiting on a lane whose worker is busy. The reply is still written by the owning lane, so it goes out once that lane's worker comes back: stolen requests run in parallel, but a slow handler still delays its own lane's writes. |
| `max-queued` | 0 (none) | Requests waiting in one lane's queue (parsed, not yet taken); beyond it, new requests get `503` with `Retry-After: 1` at once, before their body is read. |
| `max-connections` | 10,000 | Open connections; beyond it a new connection gets `503` and is closed (counted in `shedConnections`). |
| `dispatch` | `lane` (WebFlux), `virtual` (Servlet) | `lane`: requests run on the lane threads (handlers must not block). `virtual`: each request runs on its own virtual thread, for applications that block (JDBC, blocking clients). |
| `max-in-flight` | 0 (none) | With `virtual`: requests running at once, like Tomcat's `maxThreads`. At the cap a lane keeps writing finished responses (`ServerLane.drive`) but takes no new request, so the backlog waits in the engine's queue and is shed by `max-queued`, instead of growing the number of threads. |

`NativePathHttpServer.engineStats()` counts `shedRequests`, `shedConnections` and `stolen`.

## Spring MVC (Servlet) on Tomcat's Catalina — experimental

`nativepath.http.server.enabled=true` also serves Servlet applications: Tomcat's Servlet container
(Catalina) stays, and the engine replaces its transport (sockets, HTTP parsing, TLS) through a custom
Coyote `ProtocolHandler`. Filters, `DispatcherServlet` and Spring MVC run unchanged.

- Requests run on a virtual thread each by default (`dispatch=virtual`), so blocking handlers (JDBC,
  remote calls) do not stall a lane; `dispatch=lane` runs them on the lane thread for handlers that
  never block.
- Covered by tests: JSON, `getReader()`, 404s, client address, secret headers kept native, streamed
  large responses, `DeferredResult`, `Callable`, `StreamingResponseBody`, server-sent events, async
  timeouts, a blocking handler not delaying another request on a single lane.
- Missing: non-blocking servlet I/O (`ReadListener` / `WriteListener`), HTTP/2, WebSocket upgrade,
  client certificates; `server.tomcat.*` settings are ignored (use `nativepath.http.server.*`); the
  local address is the bind address.
- Measured only on a 2-vCPU CI runner so far (see [results](../results.md)); treat it as a preview.

## Limitations

- HTTP/1.1 only (no HTTP/2), request bodies buffered (max-body-bytes), responses buffered (SSE is
  delivered at the end, not streamed).
- No client certificates; TLS from PEM files only (no keystores or SSL bundles).
- Lane model: one worker thread per lane (default one per core) runs its requests one at a time
  (`block()` on the lane thread); a long-blocking handler delays the other connections of its lane.
  Blocking applications should use `dispatch=virtual` with a `max-in-flight` cap (see "Settings").
- Connection limits (`nativepath.http.server.header-read-timeout` 10 s, `idle-timeout` 60 s,
  `body-read-timeout` 30 s, `tls-handshake-timeout` 10 s, `max-header-bytes` 64 KB,
  `max-connections` 10,000, `max-inflight-body-bytes` 256 MB) are not covered by tests yet, and the
  published results were measured before they were added.

## Security proof

`ServerResidueScanTest` (macOS): Spring Boot over TLS on the engine; a separate Python process sends
a bearer token (header) and a password (JSON field) 20 times; this JVM knows them only XOR-masked
and compares natively; afterwards the whole process memory is scanned.

| Read path | Token copies | Password copies |
|---|---|---|
| Usual Spring way (strings), control | 32 | 8 |
| Secret header + `NativePathSecrets` | **0** | **0** |

Limit: on macOS the allocator clears small freed blocks itself, so this proves no *live* copy remains, not that the zeroizing allocator alone cleared freed native memory. The same scan runs on Linux (`np-bench`), where the allocator does not clear freed memory.
