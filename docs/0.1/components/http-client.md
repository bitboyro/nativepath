# HTTP client (`np-http-client`, Rust)

The client engine behind `NativePathHttpClient`: reqwest and rustls, connection pooling, streaming
bodies, with all native memory handled by the zeroizing allocator.

- **Secret mode:** response bodies reach Java only as a `NativeSecret` or native chunks; request
  secrets are taken from native memory into a wiped Rust buffer and never become a `String`. A
  request carrying a secret header always gets a connection of its own. See [security](../security.md).
- **Streaming:** large bodies are served as a native-backed stream; fixed-length streaming and
  chunked bodies allocate megabytes on the JVM where the JDK client allocates gigabytes
  ([results](../results.md)).
- **TLS:** rustls, WebPKI roots; `trust-self-signed` for development only.

Used through the [binding](binding.md) or, in Spring, the [starter](spring-starter.md).
Not built: HTTP/2, HTTP/3, per-host trust rules, certificate hot-reloading ([roadmap](../roadmap.md)).
