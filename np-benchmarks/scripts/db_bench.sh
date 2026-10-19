#!/bin/bash
# pgjdbc vs the NativePath driver (DbDriverBenchmark): the same SQL through the same HikariCP pool,
# TLS off and on, with the release native library and -prof gc (JVM allocation per operation),
# on a quiet machine.
#
# From the repo root, after:
#   cargo build --release -p np-ffi
#   np-benchmarks/pg_test_db.sh
#
#   np-benchmarks/scripts/db_bench.sh <out-dir> [JMH arguments, for example: -p tls=false]
#
# BENCH (default DbDriverBenchmark) is the JMH include pattern, for example
# BENCH='DbDriverBenchmark.(selectByPk|insertOne)'. Writes <out-dir>/db_driver_bench.json.
#
# On one macOS machine, openConnection opens thousands of connections through Docker's port
# forwarding and leaves client ports in TIME_WAIT; benchmarks that run right after it can then fail
# to connect. Run it on its own (BENCH='DbDriverBenchmark.openConnection'), or on Linux.
set -eu
OUT_DIR=$1
shift
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/scripts/lib.sh"
mkdir -p "$OUT_DIR"

LIB="$ROOT/target/release/libnativepath.dylib"
[ -f "$LIB" ] || LIB="$ROOT/target/release/libnativepath.so"
[ -f "$LIB" ] || { echo "no release native library; run: cargo build --release -p np-ffi" >&2; exit 1; }

# bench_classpath leaves out this repo's modules from ~/.m2; np-db is one of them.
CP="$(bench_classpath "$ROOT")"
export NP_PG_DIR=${NP_PG_DIR:-$ROOT/target/pg}

wait_quiet
java --enable-native-access=ALL-UNNAMED -Dnativepath.lib="$LIB" -cp "$CP" org.openjdk.jmh.Main "${BENCH:-DbDriverBenchmark}" \
    -jvmArgsAppend "-Dnativepath.lib=$LIB" -prof gc -rf json -rff "$OUT_DIR/db_driver_bench.json" "$@"
