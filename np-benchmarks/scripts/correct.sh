#!/bin/bash
# Correctness under load: for each server (NativePath and Netty) and each route, the load generator
# checks every response against what that route must return (status, length, a hash of the body, or
# for an echo that the body is what was posted) while it hammers the route. One wrong response is a
# mismatch; with NP_CORRECT_STOP_ON_MISMATCH=1 the run ends at the first one.
#
# Expected values: /hello and /payload are fixed by the benchmark app. The JSON route's answer is
# taken from one unloaded request first (the golden response), so what is checked there is that
# load changes nothing.
#
#   np-benchmarks/scripts/correct.sh [--server H --client H --server-ip IP --cores N] <out-dir>
#
# Settings: NP_CORRECT_SECONDS (per route), _CONNECTIONS, _SERVERS (nativepath, netty), _TLS (0/1),
# _STOP_ON_MISMATCH.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/remote/hosts.sh"
parse_host_options "$@"
OUT_DIR=$(mkdir -p "${ARGS[0]:?out-dir}" && cd "${ARGS[0]}" && pwd)
SECS=${NP_CORRECT_SECONDS:-3}; CONNS=${NP_CORRECT_CONNECTIONS:-8}; SERVERS=${NP_CORRECT_SERVERS:-nativepath}
TLS=${NP_CORRECT_TLS:-0}; STOP=${NP_CORRECT_STOP_ON_MISMATCH:-1}
PORT=18760
FNV="python3 np-benchmarks/scripts/np_fnv.py"

stop_on_exit
echo "== build" >&2
prepare_server >&2
prepare_client >&2
describe_hosts | tee "$OUT_DIR/machines.txt"
if [ "$TLS" = 1 ]; then
  make_bench_cert "$OUT_DIR"; SCHEME=https; CA="--cacert bench-cert.pem"; LGCA="LOADGEN_CA=bench-cert.pem"; SENV=$TLS_SERVER_ENV; TLSARG=tls
else
  SCHEME=http; CA=""; LGCA="LOADGEN_NOOP=1"; SENV=""; TLSARG=""
fi
SPRING="java --enable-native-access=ALL-UNNAMED -Xms256m -Xmx256m -cp \$cp ro.bitboy.nativepath.benchmarks.ServerBenchApp"

failed=0
# name | method | path | body-arg | expected status | expected "len hash" or "-" | echo (1/0)
check_route() { # server name path body status golden(len hash or -) echo
  local srv=$1 name=$2 path=$3 body=$4 status=$5 golden=$6 echo=$7 env="" out rc=0 label len=""
  env="LOADGEN_EXPECT_STATUS=$status LOADGEN_STOP_ON_MISMATCH=$STOP"
  if [ "$golden" != "-" ]; then
    set -- $golden
    env="$env LOADGEN_EXPECT_LEN=$1 LOADGEN_EXPECT_FNV=$2"; len="$1 B"
  fi
  [ "$echo" = 1 ] && env="$env LOADGEN_EXPECT_ECHO=1"
  label="$status${len:+ · $len}"; [ "$golden" != "-" ] && label="$label · fnv"; [ "$echo" = 1 ] && label="$status · same bytes back"
  out=$(NP_TAG="\"exp\":\"correct\",\"server\":\"$srv\",\"route\":\"$name\",\"expect\":\"$label\"" \
        loadgen $LGCA $env "$SCHEME://$SERVER_IP:$PORT$path" "$CONNS" "$SECS" ${body:+"$body"}) || rc=$?
  printf '%-10s %-14s %s\n' "$srv" "$name" "$(printf '%s' "$out" | python3 -c '
import json, sys
r = json.load(sys.stdin); print("verified %d, mismatches %d, errors %d, %d req/s" % (r["verified"], r["mismatches"], r["errors"], r["rps"]))' 2>/dev/null || echo "no result")"
  if [ "$rc" != 0 ]; then failed=1; [ "$STOP" = 1 ] && { echo "stopping at the first mismatch" >&2; return 1; }; fi
  case $out in *'"mismatches":0'*) ;; *) failed=1 ;; esac
  case $out in *'"errors":0'*) ;; *) failed=1 ;; esac
}

for srv in $SERVERS; do
  echo "== $srv"
  require_server_port $PORT || exit 1
  server_quiet
  pid=$(start_server head "correct-$srv.log" "$SENV" "$SPRING $srv $PORT $TLSARG")
  RUNNING_PID=$pid
  if [ "$TLS" = 1 ]; then wait_up_tls $PORT; else wait_up $PORT; fi || { echo "$srv did not start" >&2; exit 1; }
  loadgen $LGCA "$SCHEME://$SERVER_IP:$PORT/hello" 32 3 > /dev/null   # warm-up
  golden_json=$(on "$CLIENT" "curl -s $CA -X POST -H 'Content-Type: application/json' --data-binary @np-benchmarks/scripts/order.json $SCHEME://$SERVER_IP:$PORT/json | $FNV")
  rc=0
  { check_route "$srv" hello /hello "" 200 "$(on "$CLIENT" "$FNV --text hello")" 0 \
    && check_route "$srv" payload64k "/payload?size=65536" "" 200 "$(on "$CLIENT" "$FNV --repeat A 65536")" 0 \
    && check_route "$srv" payload512k "/payload?size=524288" "" 200 "$(on "$CLIENT" "$FNV --repeat A 524288")" 0 \
    && check_route "$srv" echo4k /echo 4096 200 - 1 \
    && check_route "$srv" json /json @np-benchmarks/scripts/order.json 200 "$golden_json" 0 \
    && check_route "$srv" missing /nope "" 404 - 0; } || rc=$?
  stop_server "$pid"
  [ "$rc" != 0 ] && break
done
exit $failed
