---
name: np-switch-variant
description: Switch an application that already uses NativePath between its variants (speed only, secret mode, NativePath server for WebFlux or MVC preview, PostgreSQL driver) or back out of one. Use when the user wants to turn a NativePath feature on or off or compare variants.
---

# Switch NativePath variants

Variants and their switches (property names from `docs/<version>/configuration.md`; check the
installed version's page before changing anything):

| Variant | On | Off |
|---|---|---|
| Speed only (client) | starter on classpath | `nativepath.http.enabled=false` |
| Secret mode (outbound) | `nativepath.http.secret-mode=true` + facade / `NativePathSecretClient` | `secret-mode=false` |
| Secret sources | `nativepath.secrets.<name>.*` | remove the entries |
| Inbound secrets | `nativepath.http.server.secret-headers=...` + `NativePathSecrets` | empty list |
| NativePath server (WebFlux) | `nativepath.http.server.enabled=true` | `false` (Netty returns) |
| NativePath server (MVC, preview) | same, Tomcat on the classpath | `false` (Tomcat's own transport returns) |
| PostgreSQL driver | `jdbc:nativepath:postgresql:` + `NativePathDataSource` | the old URL and driver |

Procedure:
1. State the current variant by reading the configuration; confirm the target with the user.
2. Change exactly one variant. Turning **on** secret mode makes code that reads bodies through a plain
   `RestClient` fail on purpose: list those call sites and convert them (see `np-migrate-app` step 4).
3. Turning **off** a variant: remove the extra properties it needed, and any code that only worked
   with it (facade calls need secret mode's client; `NativePathSecrets` needs the NativePath server).
4. Run the tests and the checks of `how-to-verify-migration.md` for the changed variant only.
5. For a comparison between variants, use the same load script and machine for each, quiet machine,
   odd/even order, and report spread; differences under about 20% on a laptop are noise. Do not
   claim a variant is faster without those numbers.

Never commit without the user's approval; never expose secret values in output.
