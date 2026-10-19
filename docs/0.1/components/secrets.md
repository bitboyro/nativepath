# Secret sources and the `NativePath` facade

How to get a credential into native memory and onto the wire without it ever becoming a Java `String` or `byte[]`.

## 1. Sources

```java
SecretSource fromFile = SecretSource.file(Path.of("/var/run/secrets/billing/api-key"));  // trailing newline dropped
SecretSource fromKeychain = SecretSource.keychain("billing-api", "prod");                // macOS only

SecretSource fromVault = VaultSecretSource.builder(secretModeClient)                     // client must be in secret mode
        .address("https://vault.internal:8200")
        .path("billing/db")                       // KV v2: GET /v1/secret/data/billing/db
        .field("password")
        .token(SecretSource.file(Path.of(System.getProperty("user.home"), ".vault-token")))
        .build();
```

Vault options: `.mount("kv").kvVersion(1)` for KV v1, `.namespace("team-a")` for Vault Enterprise,
`.token(SecretSource.keychain("vault", "token"))` to keep the token in the keychain.

Create a keychain item so the value stays out of shell history (`-w` without a value prompts):

```sh
security add-generic-password -s billing-api -a prod -w
```

The first read by a new program can show a macOS access dialog; grant `java` in advance with
`-T "$(/usr/libexec/java_home)/bin/java"` or in Keychain Access.

## 2. The `NativePath` facade (recommended)

```java
try (NativePath nativepath = NativePath.create()) {        // own secret-mode client; NativePath.builder(client) borrows one

    // Authenticated request, status only
    int status = nativepath.post(url).bearer(fromKeychain).send();

    // JSON body with a secret value (escaped natively); LinkedHashMap keeps field order
    Map<String, Object> charge = new LinkedHashMap<>();
    charge.put("amount", 1200);
    charge.put("card", cardSource);
    nativepath.post(chargesUrl).apiKey("X-Api-Key", fromFile).json(charge).send();

    // OAuth client credentials: Basic auth + form body, one field back as a NativeSecret
    try (NativeSecret token = nativepath.post(tokenUrl)
            .basic("billing", clientSecretSource)
            .form(Map.of("grant_type", "client_credentials"))
            .fetchField("access_token")) {
        nativepath.get(apiUrl).bearer(token).send(stream -> {          // stream closed afterwards
            MemorySegment chunk;
            while ((chunk = stream.nextChunk()) != null) { /* process natively */ }
            return null;
        });
    }

    // Nested JSON field (escapes such as < decoded natively)
    try (NativeSecret v = nativepath.get(url).fetchField("data", "data", "password")) { ... }

    // Whole body as one secret (default limit 1 MB)
    try (NativeSecret doc = nativepath.get(url).maxBytes(64 * 1024).fetchSecret()) { ... }

    // Load, use, wipe
    nativepath.withSecret(fromFile, key -> signer.sign(key.asMemorySegment()));
}
```

Typed JSON with secret fields needs a mapper:

```java
record Token(String token_type, long expires_in, String access_token) {}   // access_token arrives as ""

NativePath nativepath = NativePath.builder(client).jsonMapper(objectMapper::readValue).build();
try (JsonResult<Token> r = nativepath.post(tokenUrl).basic("billing", secret)
        .form(Map.of("grant_type", "client_credentials"))
        .fetchJson(Token.class, "access_token")) {
    long ttl = r.value().expires_in();                 // heap, public
    NativeSecret token = r.secret("access_token");     // native, closed with r
}
```

Errors: a non-2xx status from a `fetch...` method throws `NativePathHttpException` (`statusCode()`,
method and URL, never body data). `send()` and `send(handler)` return whatever status came back.

A `Call` holds no secret values, so it can be kept and sent again (each send loads fresh):

```java
NativePath.Call heartbeat = nativepath.get(healthUrl).apiKey("X-Api-Key", fromFile);
heartbeat.send();
heartbeat.send();
```

## 3. Vault auth methods other than a token file

Only token authentication is built in, but other methods are a login request away. AppRole, with
the token loaded natively:

```java
SecretSource appRoleToken = () -> nativepath.post(vault + "/v1/auth/approle/login")
        .json(Map.of("role_id", "billing", "secret_id", secretIdSource))
        .fetchField("auth", "client_token");

SecretSource db = nativepath.vault().address(vault).path("billing/db").field("password")
        .token(appRoleToken).build();
```

This logs in again on every read; for frequent reads, cache the token deliberately (and close it).
Kubernetes auth is the same shape: `json(Map.of("role", "billing", "jwt", SecretSource.file(serviceAccountTokenPath)))`
against `/v1/auth/kubernetes/login`.

## 4. Spring Boot

```yaml
nativepath:
  secret-mode: true
  secret-sources:
    vault-token: { type: file, path: /var/run/secrets/vault-token }
    stripe-key:  { type: keychain, service: stripe, account: live }
    db-password:
      type: vault
      address: https://vault.internal:8200
      path: billing/db            # mount "secret", kv-version 2 by default
      field: password
      token-source: vault-token   # must be a file or keychain source
```

```java
@Service
class Billing {
    private final NativePath nativepath;
    Billing(NativePath nativepath) { this.nativepath = nativepath; }   // exists when secret-mode=true

    void charge(Map<String, Object> body) throws IOException {
        nativepath.post("https://api.stripe.com/v1/charges").bearer(nativepath.secret("stripe-key")).form(body).send();
    }
}
```

`SecretSources` is also a bean (`sources.load("db-password")`, `sources.get(name)`). Both exist only
when configured: `SecretSources` when at least one source is listed, `NativePath` when
`secret-mode=true`. The `NativePath` bean uses the shared client and Spring's `ObjectMapper`.

## 5. Writing your own source

The contract: write the value straight into native memory, fail instead of falling back to a heap
copy, keep values out of exception messages and `toString`.

```java
SecretSource hsm = () -> {
    NativeSecret s = NativeSecret.allocate(32);
    try {
        myHsm.unwrapInto(s.asMemorySegment());
        return s;
    } catch (RuntimeException e) {
        s.close();
        throw e;
    }
};
```

## 6. Do not

```java
String pw = new String(bytes);                                   // the value is now on the heap for good
@Value("${db.password}") String pw;                              // a String before NativePath sees it
restClient.get().header("X-Vault-Token", token)...               // refused in secret mode
nativepath.get(url).header("Authorization", "Bearer " + token)...   // header() is for public values only
NativeSecret s = source.load();  /* never closed */              // wiped only whenever the GC runs the Cleaner
```

## 7. Platform support

| Capability | macOS | Linux | Windows |
|---|---|---|---|
| Native library loads | yes (`.dylib`) | yes (`.so`), not tested | **no**: `ForeignBridge` does not look for `.dll` |
| Wiping allocator, `NativeSecret`, `SecretBody`, `SecretJson` | yes, verified by the scan | portable code, not verified | portable code, not built |
| `SecretSource.file` | yes | yes | should work once the library loads |
| `SecretSource.keychain` | yes (login keychain, generic passwords) | `UnsupportedOperationException` | `UnsupportedOperationException` |
| `VaultSecretSource`, `NativePath` facade | yes | yes (pure NativePath) | once the library loads |
| Memory-scan test (`ResidueScanTest`) | yes (Mach VM) | returns "unsupported" | returns "unsupported" |
| Swap protection | macOS encrypts swap; no `mlock` | no `mlock`; swap may be plaintext | no `VirtualLock`; pagefile may be plaintext |
