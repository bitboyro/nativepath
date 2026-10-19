# PostgreSQL JDBC driver (`np-db`, `np-db-postgres`)

Phase 1: a JDBC driver for PostgreSQL whose credentials and secret columns stay off the JVM heap. Works with HikariCP and Spring `JdbcClient` / transactions, over plaintext and TLS. Not yet a general replacement for pgjdbc: batches and large result sets are much slower, and Hibernate/JPA is not supported.

## What it guarantees, precisely

- **The password never becomes a Java object** when given as a `NativeSecret`, a `SecretSource` or a
  `passwordFile`. A `SecretSource` or file is loaded for each new connection and wiped as soon as
  authentication ends, so not even native memory keeps a long-lived copy. With SCRAM the password
  never crosses the network either.
- **Secret parameters** (`setObject(i, nativeSecret)`) go from native memory into the protocol
  buffer without a Java copy.
- **Secret columns** (`secretColumns`) refuse every getter that would copy to the heap
  (`getString`, `getBytes`, `getInt`, `getObject(i, String.class)`, ...). They are read with
  `getObject(i, NativeSecret.class)`, a native-to-native copy into a secret that itself refuses heap
  copies; `getObject(i)` on a secret column also returns a `NativeSecret`.
- **No protocol buffer outlives its statement.** The socket read buffer, the current message, the
  outgoing message and, with TLS, every buffer that held decrypted data are zeroed when a statement
  completes, so a secret does not linger in a pooled connection between statements. Freed native
  memory is zeroed by the zeroizing allocator.
- **Error messages leave out the server's DETAIL** by default (`errorDetail=false`): it often quotes
  row values, for example the duplicate key.

What it cannot protect (be explicit about these):

1. **Values the application reads as Java objects.** Non-secret columns, and any secret the
   application turns into a `String`, are on the heap like with any driver.
2. **SQL text.** A secret written into the SQL itself instead of bound as a parameter is a Java
   `String`. Always bind secrets as parameters.
3. **The server side.** PostgreSQL's memory, data files, WAL and backups hold the values in plain
   text, and its logs can record bind parameters (`log_statement = 'all'`, `log_min_duration_statement`,
   `auto_explain`). Column encryption (phase 3) is the answer to this.
4. **Some server error messages quote input**, for example `invalid input syntax for type integer:
   "..."` in the message itself, not only in DETAIL. Bind secrets to text columns.
5. **A password in the JDBC URL or as a `String` property** is already on the heap (it is masked in
   `getURL()` and `toString()`).
6. As for the HTTP side: stack and register copies while native code runs, kernel socket buffers,
   swap and core dumps (see `SECRET_MODE_AND_SPRING_INTEGRATION.md`).

## Architecture

```
 JDBC (np-db, Java)                       native library (np-ffi, np_db_* functions)
 NativePathDriver / NativePathDataSource      np-db-postgres (Rust)
   PgConnection ── one call per statement ──►  Connection: our own protocol v3 loop, text format,
   PgStatement / PgPreparedStatement            extended protocol (Parse/Bind/Describe/Execute/Sync)
   PgResultSet ◄── result rows read in place     results read in full into one native block
                   (no call per cell)            TLS: rustls unbuffered API (decrypts into our buffers)
                                                 auth: SCRAM (postgres-protocol), MD5, cleartext
```

- **Our own wire handling.** Only authentication comes from `postgres-protocol`. Writing the protocol
  loop ourselves is what makes buffer control possible: a full client crate keeps its own buffers.
- **Results are read in full** into one native block, and Java reads cells in place from a flat
  struct (offset and length per cell), so reading a cell costs no native call.
- **TLS on rustls's unbuffered API.** The buffered `rustls::ClientConnection` decrypts records in place
  inside its own receive buffer and, once consumed, only resets a counter: the last plaintext stays
  in memory until the next read. The memory scan caught this (see below). With the unbuffered API
  rustls decrypts inside a buffer we own, which we zero.
- **JDBC base classes are generated** (`np-db/tools/GenJdbcBases.java`): every JDBC method the driver
  does not implement throws `SQLFeatureNotSupportedException` naming itself, instead of silently
  doing nothing.
- `np-db` depends on `np-binding` for `NativeSecret`, `SecretSource` and the native library
  loader. They are planned to move into a shared `np-core` artifact when a second protocol module needs them.

## Connection properties

URL: `jdbc:nativepath:postgresql://host[:port][/database][?key=value&...]`. Properties come from the
URL or the `Properties` object (the URL wins); objects (`NativeSecret`, `SecretSource`, `DbObserver`)
can only be passed in `Properties` or through `NativePathDataSource`.

| Property | Default | Meaning |
|---|---|---|
| `user` | (required) | |
| `password` | none | `NativeSecret` or `SecretSource` (off-heap), or `String` / `char[]` (already on the heap) |
| `passwordFile` | none | File holding the password, read straight into native memory (a trailing newline is dropped) |
| `secretColumns` | none | Comma-separated column names whose values must stay off the heap (see limitations) |
| `sslmode` | `prefer` | `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full`, as in libpq |
| `sslrootcert` | none | PEM file with the CA that signed the server certificate; required for `verify-ca` and `verify-full`. With `require` it makes the driver check the chain (as libpq does) |
| `channelBinding` | `prefer` | `disable`, `prefer` or `require`: SCRAM-SHA-256-PLUS bound to the TLS session. `require` refuses servers that authenticate without it, which keeps the login safe from a man in the middle even when certificates are not verified |
| `applicationName` | `NativePath` | |
| `connectTimeout` / `socketTimeout` | 10 / 0 (none) | Seconds |
| `errorDetail` | `false` | Include the server's DETAIL in exceptions |
| `observer` | none | A `DbObserver` for metrics |

Spring and HikariCP: give Hikari a `NativePathDataSource` (`hikariConfig.setDataSource(ds)`). Hikari's
own driver path stringifies every property, which would turn a `SecretSource` into a string.

```java
NativePathDataSource ds = new NativePathDataSource();
ds.setUrl("jdbc:nativepath:postgresql://db.internal/app?sslmode=verify-full&sslrootcert=/etc/ssl/db-ca.pem&channelBinding=require");
ds.setUser("app");
ds.setPasswordSource(SecretSource.file(Path.of("/run/secrets/db-password")));
ds.setSecretColumns("card_number,api_token");
ds.setObserver(new NativePathDbMetrics(meterRegistry, "orders"));   // optional
```

## Query timeouts and cancel

`Statement.setQueryTimeout(n)` cancels a statement that runs longer on the server, with a
PostgreSQL CancelRequest on a second connection (as psql's Ctrl-C); the statement fails with
`SQLTimeoutException` (SQLSTATE 57014) and **the connection stays usable**. `Statement.cancel()` does
the same from another thread, and `Connection.abort()` cancels and then closes.

A cancel must never hit the next statement. Closing a statement's timer waits for a cancel that is
already being sent; the native cancel returns only once the server has taken the request, and
PostgreSQL ignores a cancel that reaches an idle connection.

## Metrics

`DbObserver` receives connect events (duration, outcome, TLS, channel binding) and statement events
(kind, duration, outcome success/error/timeout/cancelled, SQLSTATE, rows, number of secret
parameters). Events never carry SQL text or values, so secrets cannot reach telemetry and tags stay
low-cardinality. An observer that throws cannot break database access.
`ro.bitboy.nativepath.db.micrometer.NativePathDbMetrics` (Micrometer is an optional dependency) records
`nativepath.db.connect`, `nativepath.db.statements`, `nativepath.db.rows` and `nativepath.db.secret.parameters`.

## Security proof

`DbResidueScanTest` scans the whole process (JVM heap, native heap, stacks) for canary values that
exist only in the files and container created by `np-benchmarks/pg_test_db.sh`; the test holds them
XOR-masked. Run 2026-10-18 on macOS against PostgreSQL 17, release library:

| Scenario | `disable` | `prefer` (TLS) | `require` (TLS) |
|---|---|---|---|
| `db-control`: card read with `getString` on a non-secret connection (must be found) | found | found | found |
| `db-password`: password from `passwordFile`, after SCRAM, connection open; and after close | 0, 0 | 0, 0 | 0, 0 |
| `db-secret`: card written from a `NativeSecret`, read back as one; after INSERT, after result + statement + secret closed (connection open), after close | 0, 0, 0 | 0, 0, 0 | 0, 0, 0 |

Before the switch to rustls's unbuffered API, `db-secret` over TLS found **1 copy** with the
connection open (gone after close): the buffered rustls receive buffer. That is the finding that
drove the change.

Not yet done: a run with the wipe deliberately disabled, to show the scan fails without it (the
`control` scenario only shows that the scanner finds a heap copy). Linux runs come with the CI job.

## Running the tests

```sh
np-benchmarks/pg_test_db.sh                     # PostgreSQL 17 in Docker: SCRAM, TLS on, plaintext allowed
NP_PG_DIR=$PWD/target/pg mvn -pl np-db -am test
NP_PG_PASSWORD_FILE=$PWD/target/pg/password.txt cargo test -p np-db-postgres
np-benchmarks/pg_test_db.sh stop
```

Without the database, the live tests skip themselves; unit tests, including TLS against an
in-process fake server (each `sslmode`, wrong CA, wrong host name, cancel over TLS, channel-binding
refusal), always run.

Memory scans (macOS and Linux), one scenario per JVM; `NP_PG_SSLMODE` picks the mode:

```sh
CP="$PWD/np-db/target/classes:$(. np-benchmarks/scripts/lib.sh && bench_classpath "$PWD")"
NP_PG_SSLMODE=require java --enable-native-access=ALL-UNNAMED -cp "$CP" \
    ro.bitboy.nativepath.benchmarks.DbResidueScanTest db-secret target/pg
```

Benchmark: `np-benchmarks/scripts/db_bench.sh <out-dir>` (waits for a quiet machine; `BENCH=` selects
benchmarks), then `np-benchmarks/scripts/db_bench_table.py <out-dir>/db_driver_bench.json`.

CI: the `db` job in `.github/workflows/bench.yml` runs the scans over plaintext and TLS and the
benchmark on Linux, with the same trigger as the server job (a pushed `v*` tag, or manual).

## Limitations (phase 1)

- **JDBC subset.** Forward-only, read-only result sets read in full (no cursors; `setFetchSize` has
  no effect); no `DatabaseMetaData` catalog queries (tables, columns, keys), arrays, LOBs/streams,
  savepoints, callable statements or `getParameterMetaData`. Enough for HikariCP and Spring
  `JdbcClient` / `JdbcTemplate` and transactions; not for Hibernate/JPA, Flyway or Liquibase.
- **No statement cache, no batch pipelining** (see performance).
- **`secretColumns` matches column names in every table**: `customers.card_number` protects
  `card_number` everywhere. The server reports a table OID per column, not a name; resolving it is
  future work. Over-matching is the safe direction.
- **TLS:** no client certificates (`sslcert`/`sslkey`) and no `sslrootcert=system`, both refused
  explicitly; `sslmode=allow` never retries with TLS.
- **MD5 authentication** is implemented but not tested against a server (the test database uses SCRAM).
- **Text format only.** Parameters are sent as text with type OIDs for typed setters
  (`setTimestamp`, like pgjdbc, sends no type); `bytea` must use hex output (the default).
- Only PostgreSQL; MySQL/MariaDB and SQL Server follow the same shape (see the [roadmap](../roadmap.md)).
