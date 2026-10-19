#!/bin/bash
# Soak: a steady open-loop load on a TLS server for a long time, while the server's process is
# sampled (resident memory, Java heap and non-heap, native remainder, GC, threads, CPU) and the
# process is scanned for planted secrets at intervals. Whether memory grows is decided afterwards from
# the samples (np_report.py): a least-squares slope of resident memory over the last hour, against
# the leak limit.
#
#   np-benchmarks/scripts/soak.sh [--server H --client H --server-ip IP --cores N] <out-dir>
#
# Settings (set by np-bench from bench.config.json): NP_SOAK_MINUTES, _SAMPLE_S, _CONNECTIONS,
# _LOAD_PCT (of the closed-loop peak measured first), _SCAN_EVERY_MIN (0: no scans).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/remote/hosts.sh"
. "$ROOT/np-benchmarks/scripts/canaries.sh"
parse_host_options "$@"
OUT_DIR=$(mkdir -p "${ARGS[0]:?out-dir}" && cd "${ARGS[0]}" && pwd)
MINUTES=${NP_SOAK_MINUTES:-2}; SAMPLE_S=${NP_SOAK_SAMPLE_S:-10}; CONNS=${NP_SOAK_CONNECTIONS:-64}
LOAD_PCT=${NP_SOAK_LOAD_PCT:-60}; SCAN_EVERY=${NP_SOAK_SCAN_EVERY_MIN:-15}
DUR=$(awk -v m="$MINUTES" 'BEGIN { printf "%d", m * 60 }')
PORT=18720

stop_on_exit
echo "== build" >&2
prepare_server >&2
prepare_client >&2
make_bench_cert "$OUT_DIR"
describe_hosts | tee "$OUT_DIR/machines.txt"

SPRING="java --enable-native-access=ALL-UNNAMED -Xms512m -Xmx512m -cp \$cp ro.bitboy.nativepath.benchmarks.ScanServerApp"
URL=https://$SERVER_IP:$PORT
require_server_port $PORT || exit 1
server_quiet
pid=$(start_server head soak-server.log "$TLS_SERVER_ENV" "$SPRING $PORT")
RUNNING_PID=$pid
wait_up_tls $PORT || { echo "the server did not start (soak-server.log on the server host)" >&2; exit 1; }

# The load: a share of what the server does flat out, so it is busy but not queueing.
loadgen LOADGEN_CA=bench-cert.pem "$URL/hello" 64 5 > /dev/null
peak=$(loadgen LOADGEN_CA=bench-cert.pem "$URL/hello" 64 8 | python3 -c 'import json,sys; print(json.load(sys.stdin)["rps"])')
rate=$(awk -v p="$peak" -v f="$LOAD_PCT" 'BEGIN { printf "%d", p * f / 100 }')
echo "closed-loop peak $peak req/s; holding $rate req/s ($LOAD_PCT%) on $CONNS connections for $MINUTES min"

start_sampler "$pid" server '"exp":"soak"' "https://localhost:$PORT/__stats" "$SAMPLE_S"
NP_TAG='"exp":"soak","scenario":"steady"' loadgen LOADGEN_CA=bench-cert.pem LOADGEN_RATE="$rate" "$URL/hello" "$CONNS" "$DUR" > "$OUT_DIR/soak-load.json" &
LG=$!

failed=0
scan() { # label set mode token-hex password-hex expected(min1|0) requests
  local label=$1 set=$2 mode=$3 res found
  on "$CLIENT" "python3 np-benchmarks/scan_client.py $URL bench-cert.pem $mode $4 $5 $CANARY_MASK $7" > /dev/null || true
  res=$(on "$CLIENT" "curl -s --cacert bench-cert.pem '$URL/__scan?set=$set'")
  found=$(printf '%s' "$res" | python3 -c '
import json, sys
r = json.load(sys.stdin); exp = sys.argv[1]; found = r["token"] + r["password"]
ok = found >= 1 if exp == "min1" else found == 0
print(json.dumps({"found_token": r["token"], "found_password": r["password"], "found": found, "ms": r["ms"], "ok": ok}))' "$6")
  np_event scan "\"exp\":\"soak\",\"scenario\":\"$label\",\"expected\":\"$6\",$(printf '%s' "$found" | sed 's/^{//; s/}$//')"
  printf '%s -> %s\n' "$label" "$found"
  case $found in *'"ok": false'*) failed=1 ;; esac
}

if [ "$(awk -v s="$SCAN_EVERY" 'BEGIN { print (s > 0) }')" = 1 ]; then
  sleep 5
  scan "control: value read as a String" control control "$CONTROL_TOKEN_HEX" "$CONTROL_PASSWORD_HEX" min1 5
  every=$(awk -v m="$SCAN_EVERY" 'BEGIN { printf "%d", m * 60 }')
  next=$(( $(date +%s) + every ))
  while kill -0 "$LG" 2>/dev/null; do
    if [ "$(date +%s)" -ge "$next" ]; then
      scan "secure: token and password" secure secure "$SECURE_TOKEN_HEX" "$SECURE_PASSWORD_HEX" 0 5
      next=$(( $(date +%s) + every ))
    fi
    sleep 2
  done
fi
wait "$LG" || { echo "the load generator failed" >&2; failed=1; }
kill "$SAMPLER_PID" 2>/dev/null || true
stop_server "$pid"
cat "$OUT_DIR/soak-load.json"
exit $failed
