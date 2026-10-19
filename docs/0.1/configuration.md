# Configuration reference

## Spring Boot properties

Client and shared:

| Property | Default | Meaning |
|---|---|---|
| `nativepath.http.enabled` | `true` | Turn the whole integration off |
| `nativepath.http.secret-mode` | `false` | Keep response data off the JVM heap; `RestClient` bodies become unreadable by design |
| `nativepath.http.isolate-connections` | `false` | Give every request its own connection (requests with secret headers always have one) |
| `nativepath.http.streaming` | `true` | Serve `RestClient` bodies as a native-backed stream instead of buffering |
| `nativepath.http.max-body-bytes` | `268435456` | Limit for buffered bodies (streams have none) |
| `nativepath.http.trust-self-signed` | `false` | Accept self-signed certificates (development only) |
| `nativepath.http.forbidden-headers` | `authorization,proxy-authorization,cookie,x-api-key,x-vault-token` | Headers refused through plain `RestClient` in secret mode |
| `nativepath.http.forbid-request-bodies` | `true` | Refuse `RestClient` request bodies in secret mode |

Server (`nativepath.http.server.*`):

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Serve on the NativePath engine instead of Netty (WebFlux) or Tomcat's transport (MVC) |
| `worker-threads` | `0` | One per lane (event loop); `0` = one per core |
| `dispatch` | `lane` (WebFlux), `virtual` (MVC) | `lane`: handlers run on the lane thread and must not block. `virtual`: a virtual thread per request |
| `max-in-flight` | `0` (none) | With `virtual`: requests running at once |
| `max-queued` | `0` (none) | Requests waiting per lane; beyond it, `503` with `Retry-After: 1` |
| `max-connections` | `10000` | Open connections; beyond it, `503` and close |
| `max-body-bytes` | `16777216` | Larger request bodies get `413` |
| `max-header-bytes` | `65536` | |
| `max-inflight-body-bytes` | `268435456` | |
| `header-read-timeout` | `10s` | |
| `idle-timeout` | `60s` | |
| `body-read-timeout` | `30s` | |
| `tls-handshake-timeout` | `10s` | |
| `secret-headers` | empty | Request headers that never become Java strings |
| `batch`, `spin`, `work-stealing` | `1`, `0`, `false` | Lane tuning, see [http-server](components/http-server.md); change only after measuring |

Secret sources (`nativepath.secrets.<name>.*`): `type` (`file`, `keychain`, `vault`), then per type:
`path`; `service`, `account`; `address`, `mount` (`secret`), `path`, `field`, `kv-version` (`2`),
`namespace`, `token-source` (the name of a file or keychain source). A `SecretSources` bean exists only
when at least one is configured. Details: [secrets](components/secrets.md).

Standard Spring properties that apply to the server: `server.port`, `server.address`,
`server.ssl.certificate`, `server.ssl.certificate-private-key`. `server.tomcat.*` is ignored.

## JVM

| Setting | Meaning |
|---|---|
| `-Dnativepath.lib=PATH` / `NATIVEPATH_LIB` | The native library |
| `--enable-native-access=ALL-UNNAMED` | Allow Panama downcalls |
| `-XX:-HeapDumpOnOutOfMemoryError`, `ulimit -c 0` | Keep native secrets out of dumps ([security](security.md)) |

## Environment variables for the benchmark servers

`NATIVEPATH_SERVER_BATCH`, `NATIVEPATH_SERVER_SPIN`, `NATIVEPATH_SERVER_WORK_STEALING`,
`NATIVEPATH_SERVER_MAX_QUEUED`: the lane settings for `ServerBenchApp`. Not for applications.

## JDBC

URL `jdbc:nativepath:postgresql://host[:port]/db?key=value`; properties are in
[db](components/db.md#connection-properties).

## Benchmark settings

`np-bench` has its own settings (rounds, durations, connections, limits, which experiments): [how to run the benchmark](how-to-run-the-benchmark.md#3-settings).
