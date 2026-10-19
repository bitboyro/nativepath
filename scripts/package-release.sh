#!/bin/bash
# Builds the release bundle for THIS platform: native library, load generator, jars, np-bench with
# its scripts, docs and skills. The bundle mirrors the source layout (target/release, np-benchmarks/)
# plus a .prebuilt marker, so the benchmark scripts run from it without Rust or Maven.
#
#   scripts/package-release.sh <version> [out-dir]      (default out-dir: dist)
#
# Writes <out-dir>/np-<version>-<os>-<arch>.tar.gz and a .sha256 next to it. Run it on each platform
# you release for (the release workflow does).
set -euo pipefail

VERSION=${1:?version, for example 0.1.0}
OUT=${2:-dist}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) PLATFORM=linux-x86_64; LIBEXT=so ;;
  Linux-aarch64|Linux-arm64) PLATFORM=linux-aarch64; LIBEXT=so ;;
  Darwin-arm64) PLATFORM=macos-arm64; LIBEXT=dylib ;;
  *) echo "unsupported platform $(uname -sm)" >&2; exit 1 ;;
esac
NAME=np-$VERSION-$PLATFORM
STAGE=$(mktemp -d)/$NAME
mkdir -p "$STAGE/lib" "$STAGE/jars" "$STAGE/target/release/examples" "$STAGE/np-benchmarks" "$OUT"
OUT=$(cd "$OUT" && pwd)

echo "== native"
cargo build -q --release -p np-ffi
cargo build -q --release -p np-http-server --examples
cp "target/release/libnativepath.$LIBEXT" "$STAGE/target/release/"
for ex in loadgen engine_bench hyper_direct hyper_direct_sysalloc; do
  cp "target/release/examples/$ex" "$STAGE/target/release/examples/"
done

echo "== jars"
# Each module copies its own dependencies to its own target/deps; only np-benchmarks' set (which has
# the other modules' jars and one version of every third-party jar) goes into the bundle.
mvn -B -q -DskipTests package dependency:copy-dependencies -pl np-benchmarks -am \
    '-DoutputDirectory=${project.build.directory}/deps' -DincludeScope=runtime
cp np-benchmarks/target/deps/*.jar "$STAGE/lib/"
cp np-benchmarks/target/np-benchmarks-*.jar "$STAGE/lib/"
rm -f "$STAGE"/lib/*-sources.jar "$STAGE"/lib/*-tests.jar
for m in np-binding np-http-spring-starter np-db; do
  cp "$m"/target/"$m"-*.jar "$STAGE/jars/"
done
. np-benchmarks/scripts/lib.sh
fetch_tcnative
for j in $(OS=$OS; tcnative_cp | tr ':' ' '); do cp "$j" "$STAGE/lib/"; done

echo "== scripts, docs, skills"
cp -R np-benchmarks/scripts np-benchmarks/remote np-benchmarks/dashboard "$STAGE/np-benchmarks/"
cp np-benchmarks/np-bench np-benchmarks/bench.config.json np-benchmarks/pg_test_db.sh np-benchmarks/*.py "$STAGE/np-benchmarks/"
mkdir -p "$STAGE/np-http-server/tests/fixtures"
cp np-http-server/tests/fixtures/*.pem "$STAGE/np-http-server/tests/fixtures/"
cat > "$STAGE/np-bench" <<'LAUNCH'
#!/bin/bash
exec "$(cd "$(dirname "$0")" && pwd)/np-benchmarks/np-bench" "$@"
LAUNCH
chmod +x "$STAGE/np-bench" "$STAGE"/np-benchmarks/np-bench "$STAGE"/np-benchmarks/scripts/*.sh \
         "$STAGE"/np-benchmarks/remote/*.sh "$STAGE"/np-benchmarks/pg_test_db.sh
cp -R docs skills CHANGELOG.md LICENSE "$STAGE/"
cp README.md "$STAGE/README.md"
echo "$VERSION" > "$STAGE/VERSION"
: > "$STAGE/.prebuilt"

echo "== archive"
tar -C "$(dirname "$STAGE")" -czf "$OUT/$NAME.tar.gz" "$NAME"
(cd "$OUT" && { shasum -a 256 "$NAME.tar.gz" 2>/dev/null || sha256sum "$NAME.tar.gz"; } > "$NAME.tar.gz.sha256")
rm -rf "$(dirname "$STAGE")"
echo "$OUT/$NAME.tar.gz"
