---
name: np-install
description: Install NativePath into a Java project: choose the release, fetch the native library for this platform, add the dependencies, set the JVM flags, and run a smoke check. Use when a user wants to start using NativePath or when the native library cannot be found.
---

# Install NativePath

Goal: the application starts with NativePath available, and a check proves it. Change nothing about
application behaviour yet (that is `np-migrate-app`).

1. **Check the environment.** `java -version` must be 22 or newer; Spring Boot 3.x for the starter.
   Platform: Linux x86_64 or aarch64, macOS arm64. Windows is not supported: stop and say so.
2. **Choose the release.** Use the latest GitHub release of the NativePath repository unless the
   user names a version. Read its `CHANGELOG.md` and `docs/<version>/index.md`.
3. **Fetch the bundle for this platform** (`np-<version>-<os>-<arch>.tar.gz`), verify its `.sha256`,
   unpack it outside the project (for example `/opt/nativepath`). It holds `target/release/libnativepath.*`,
   `jars/` (`np-binding`, `np-http-spring-starter`, `np-db`), `docs/`, `skills/`, `np-bench`.
4. **Add the dependencies** the user needs, group `ro.bitboy.nativepath`: `np-http-spring-starter`
   (includes `np-binding`), `np-db` only if they want the PostgreSQL driver. If no Maven repository
   serves the release, install the jars from `jars/` into the local repository or reference them as
   files, and tell the user which you chose.
5. **Point the JVM at the library:** `-Dnativepath.lib=/opt/nativepath/target/release/libnativepath.<so|dylib>`
   and `--enable-native-access=ALL-UNNAMED`. Put them where the app is launched (script, container
   entrypoint, `JAVA_TOOL_OPTIONS`, Maven/Gradle run config), not only in your shell.
6. **Smoke check.** Start the application (or a one-line `NativePathHttpClient.builder().build()`
   main) and confirm it starts without `UnsatisfiedLinkError` / "points to a missing file". Optionally
   run `/opt/nativepath/np-bench check --only memory` to prove the library works on this machine.
7. **Report:** version installed, library path, flags added, dependencies added, check result.
   Do not enable secret mode or the server: those are separate, opt-in steps.

Never commit or push the user's repository changes without asking.
