---
name: np-developer
description: Working rules for developing NativePath (Rust engines, Java 22 Panama binding, Spring starter, PostgreSQL driver). Use when changing code in any np-* module, adding a protocol module, touching secrets or memory handling, or preparing a change for review.
---

# NativePath developer

NativePath is a Rust native layer reached from Java through Project Panama. The promise: **drop in,
get faster; opt in, leave nothing behind.** Every change must keep both parts true or say plainly
that it does not.

## Repository rules (these override defaults)

- **Never commit, push or open PRs without the owner's explicit approval of that specific change.**
  Stage and describe; the owner decides what goes in.
- **No attribution lines** in commits or PR descriptions: no `Co-Authored-By`, no "Generated with".
  The repository history starts at one initial commit; keep messages plain, conventional style
  (`feat:`, `fix:`, `docs:`, `ci:`, `perf:`, `chore:`), one line plus a body only when needed.
- **Changelog is laconic.** Every user-visible change adds exactly one short line to `CHANGELOG.md`
  under Unreleased (Features / Fixes / Changes). No paragraphs, no issue prose.
- **Claims need evidence.** Do not write "faster", "safe", "strict" in docs, code comments or the
  README without a measurement or a test that backs it, and put the machine and date next to the
  number (`docs/<version>/results.md`). If a claim does not hold, remove it.

## Layout and names

`np-memory`, `np-http-client`, `np-http-server`, `np-ffi` (Rust workspace, one `cdylib` named
`nativepath`); `np-binding`, `np-http-spring-starter`, `np-db`, `np-benchmarks` (Maven, groupId
`ro.bitboy.nativepath`, packages `ro.bitboy.nativepath.*`); `np-db-postgres` (Rust). Classes are
`NativePath*`. C symbols are `np_<protocol>_*`. Config prefixes: `nativepath.http.*`,
`nativepath.http.server.*`, `nativepath.secrets.*`, `nativepath.db.*`. Docs are versioned in
`docs/<version>/`; update the page of the component you change in the same change.

## Build and test

```sh
cargo build --release -p np-ffi
cargo test --release --workspace
mvn -B verify                       # JDK 22+; modules resolve from the reactor, never ~/.m2
np-benchmarks/np-bench check        # quick pass of every experiment; --only a,b to pick
```
Run `np-bench check --only memory` after any change to memory, secrets, the allocator, TLS buffers or
the FFI. Tests that need PostgreSQL skip themselves without `np-benchmarks/pg_test_db.sh`.

## Secrets and memory (the part that must not regress)

- A secret is a `NativeSecret` or native bytes from creation to the wire. No `String`, `byte[]`,
  `toString()`, log line, exception message or map key may ever hold one.
- Every native buffer that held secret or decrypted data is wiped before it is freed or reused. Use
  the zeroizing allocator and `SecretBuffer`; do not introduce a second allocator.
- New protocol module is "done" only when its residue scan passes and its benchmark against the main
  Java library is published, whatever the result. Add the scan before the feature is called finished.
- Protocol and crypto code comes from established crates (hyper, rustls, `postgres-protocol` for SCRAM,
  russh, ldap3); never hand-roll parsing of untrusted input beyond what the engine already does, and
  never hand-roll crypto.
- Panics must not cross the FFI: every `extern "C"` entry point contains them and returns an error.
- Never close an `Arena.ofShared()` per request (the JVM handshakes every thread; 72 µs at 200 threads).
- Residue-scan canaries are stored XOR-masked in the Java test and in plain text in the helper
  (`secret_server.py`, `pg_test_db.sh`). If you change a canary, regenerate the masked arrays too, or
  every scan fails with "control not found".

## Compatibility and API

- Public signatures and property names are an API once released: add, do not rename. Before the first
  release, rename freely but update docs, tests and `docs/<version>/configuration.md` together.
- Defaults must keep measured behaviour; a setting that did not pay off in a measurement stays off.
- Security limits (timeouts, connection and body caps) must have tests before they are called done.

## Before asking for review

1. Tests and the memory proofs above pass; say which you ran and which you could not (macOS vs Linux).
2. Docs page and `CHANGELOG.md` line updated.
3. `git status` shows only intended files (no `np-results/`, `dist/`, `target/`, scan output).
4. Report honestly: failing or skipped tests, unmeasured performance impact, known gaps.
