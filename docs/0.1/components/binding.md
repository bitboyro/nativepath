# Java binding (`np-binding`, Java 22+)

Project Panama (`java.lang.foreign`) bindings over the native library. No JNI, no generated glue.

| Type | What |
|---|---|
| `NativePathHttpClient` | The client: `builder().secretMode(..).isolateConnections(..).trustSelfSigned(..)` |
| `NativePathRequest`, `NativePathResponse`, `NativePathStream` | Requests, buffered responses, native-backed streams (`nextChunk()` returns a `MemorySegment`) |
| `NativePathHttpServer`, `ServerLane`, `ServerExchange` | The server and its lanes; an exchange is one request/response |
| `NativeSecret` | A secret in native memory; refuses every copy to the heap; wiped on close |
| `SecretSource` | `file`, `keychain`, `VaultSecretSource`: a location, never a value |
| `SecretBody`, `SecretJson` | Secret request bodies and JSON fields handled natively |
| `NativePath` | The facade: sources + secret-mode client + a fluent call builder ([secrets](secrets.md)) |

Ownership rule: a `NativeSecret` you create or fetch is yours to close; one you hand to the facade
is borrowed.
