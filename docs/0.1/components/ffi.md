# C ABI (`np-ffi`)

One `cdylib`, `libnativepath.{so,dylib}`, for every protocol: client and server HTTP today, the
PostgreSQL driver as `np_db_*`, later SSH, LDAP and others.

- Opaque handles, bounds-checked lengths, every entry point contains Rust panics and turns them into
  error results.
- Symbols are `np_*` (HTTP) and `np_db_*` (database): the protocol is in the name.
- The server is pull-based: workers pull requests with one call (`np_lane_next`); native code never
  calls into the JVM (no upcalls).
- Built with `cargo build --release -p np-ffi`. Releases ship it per platform; the JVM finds it with
  `-Dnativepath.lib` ([how to use](../how-to-use.md#1-get-the-native-library)).
