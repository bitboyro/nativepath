#!/bin/bash
# Database under sustained load: for each scenario, pgjdbc and the NativePath driver in turn run the
# same SQL on N threads through the same pool for a fixed time (DbLoadTest), then the one-shot memory
# scans of the NativePath driver run against the same database (DbResidueScanTest): the password
# after sign-in, a secret column over plaintext and TLS, and the control that must be found.
#
# Runs on this machine with PostgreSQL in Docker (np-benchmarks/pg_test_db.sh). Docker Desktop's
# port forwarding adds latency to every round trip; use Linux for numbers you will quote.
#
#   np-benchmarks/scripts/db_load.sh <out-dir>
#
# Settings: NP_DB_SECONDS, _THREADS, _POOL, _DRIVERS, _SCENARIOS. NP_PG_PORT (default 55452).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/scripts/lib.sh"
OUT_DIR=$(mkdir -p "${1:?out-dir}" && cd "$1" && pwd)
SECS=${NP_DB_SECONDS:-3}; THREADS=${NP_DB_THREADS:-8}; POOL=${NP_DB_POOL:-16}
DRIVERS=${NP_DB_DRIVERS:-"pgjdbc nativepath"}; SCENARIOS=${NP_DB_SCENARIOS:-"select_pk insert"}
export NP_PG_PORT=${NP_PG_PORT:-55452} NP_PG_DIR=${NP_PG_DIR:-$ROOT/target/pg}

if [ -n "${NP_PG_HOST:-}" ]; then
  # A database that is already running (the Docker setup's postgres service): wait for it, start nothing.
  export NP_PG_PORT=${NP_PG_PORT:-5432}
  for _ in $(seq 1 60); do (exec 3<>"/dev/tcp/$NP_PG_HOST/$NP_PG_PORT") 2>/dev/null && break; sleep 1; done
else
  command -v docker >/dev/null || { echo "docker is needed for the database experiment" >&2; exit 1; }
  trap '"$ROOT/np-benchmarks/pg_test_db.sh" stop >/dev/null 2>&1 || true' EXIT
  "$ROOT/np-benchmarks/pg_test_db.sh" "$NP_PG_DIR"
fi

LIB=$ROOT/target/release/libnativepath.$([ "$(uname -s)" = Darwin ] && echo dylib || echo so)
CP="$(bench_classpath "$ROOT")"
JAVA="java --enable-native-access=ALL-UNNAMED -Dnativepath.lib=$LIB -cp $CP"

wait_quiet
i=0
for sc in $SCENARIOS; do
  # alternate which driver goes first, so warm-up and drift do not favour one
  if [ $((i % 2)) = 0 ]; then order=$DRIVERS; else order=$(reverse $DRIVERS | tr '\n' ' '); fi
  for d in $order; do
    echo "== $sc / $d"
    $JAVA ro.bitboy.nativepath.benchmarks.DbLoadTest "$d" "$sc" "$SECS" "$THREADS" "$POOL"
  done
  i=$((i + 1))
done

failed=0
for mode in disable require; do
  for s in db-control db-password db-secret; do
    echo "== scan $s sslmode=$mode"
    ok=true
    NP_PG_SSLMODE=$mode $JAVA ro.bitboy.nativepath.benchmarks.DbResidueScanTest "$s" "$NP_PG_DIR" 2>&1 | grep -v WARNING || ok=false
    np_event scan "\"exp\":\"db\",\"scenario\":\"$s\",\"label\":\"$s, sslmode=$mode\",\"expected\":\"$([ "$s" = db-control ] && echo min1 || echo 0)\",\"ok\":$ok"
    [ "$ok" = true ] || failed=1
  done
done
exit $failed
