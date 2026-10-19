#!/bin/bash
# Scan under load: ordinary load keeps the TLS server busy while planted secrets are sent in
# different ways (normally, into a handler that fails, a request dropped before its body is complete,
# a request that stalls until the server gives up) and the server then scans its own process for them.
# The control sends the same secrets the usual Spring way and must be found: a control that finds
# nothing means the scanner is blind. Every other scenario must find none.
#
#   np-benchmarks/scripts/scan_load.sh [--server H --client H --server-ip IP --cores N] <out-dir>
#
# Settings: NP_SCAN_CONNECTIONS (background load, 0 = idle server), NP_SCAN_CYCLES, NP_SCAN_SCENARIOS.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/remote/hosts.sh"
. "$ROOT/np-benchmarks/scripts/canaries.sh"
parse_host_options "$@"
OUT_DIR=$(mkdir -p "${ARGS[0]:?out-dir}" && cd "${ARGS[0]}" && pwd)
CONNS=${NP_SCAN_CONNECTIONS:-16}; CYCLES=${NP_SCAN_CYCLES:-1}; SCENARIOS=${NP_SCAN_SCENARIOS:-control secure}
PORT=18740

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
pid=$(start_server head scan-server.log "$TLS_SERVER_ENV" "$SPRING $PORT")
RUNNING_PID=$pid
wait_up_tls $PORT || { echo "the server did not start (scan-server.log on the server host)" >&2; exit 1; }

# Background load in 20-second pieces until told to stop, so it lasts as long as the scans do.
STOP=$OUT_DIR/.stop
rm -f "$STOP"
if [ "$CONNS" -gt 0 ]; then
  (
    while [ ! -e "$STOP" ]; do
      NP_TAG='"exp":"scan","scenario":"background"' loadgen LOADGEN_CA=bench-cert.pem "$URL/hello" "$CONNS" 20 > /dev/null || true
    done
  ) &
  LOAD=$!
fi

failed=0
run_scenario() { # name cycle
  local name=$1 cycle=$2 mode set exp requests label
  case $name in
    control) mode=control; set=control; exp=min1; requests=10; label="control: value read as a String" ;;
    secure)  mode=secure;  set=secure;  exp=0;    requests=10; label="secure: token and password" ;;
    error)   mode=error;   set=secure;  exp=0;    requests=10; label="after a handler that fails" ;;
    abort)   mode=abort;   set=secure;  exp=0;    requests=10; label="after requests dropped mid-body" ;;
    timeout) mode=timeout; set=secure;  exp=0;    requests=2;  label="after requests the server timed out" ;;
    *) echo "unknown scenario $name" >&2; failed=1; return ;;
  esac
  local t h
  if [ "$set" = control ]; then t=$CONTROL_TOKEN_HEX; h=$CONTROL_PASSWORD_HEX; else t=$SECURE_TOKEN_HEX; h=$SECURE_PASSWORD_HEX; fi
  on "$CLIENT" "python3 np-benchmarks/scan_client.py $URL bench-cert.pem $mode $t $h $CANARY_MASK $requests" > /dev/null || true
  local res found
  res=$(on "$CLIENT" "curl -s --cacert bench-cert.pem '$URL/__scan?set=$set'")
  found=$(printf '%s' "$res" | python3 -c '
import json, sys
r = json.load(sys.stdin); exp = sys.argv[1]; found = r["token"] + r["password"]
ok = found >= 1 if exp == "min1" else found == 0
print(json.dumps({"found_token": r["token"], "found_password": r["password"], "found": found, "ms": r["ms"], "ok": ok}))' "$exp")
  np_event scan "\"exp\":\"scan\",\"scenario\":\"$name\",\"label\":\"$label\",\"cycle\":$cycle,\"expected\":\"$exp\",$(printf '%s' "$found" | sed 's/^{//; s/}$//')"
  printf 'cycle %s %-8s %s\n' "$cycle" "$name" "$found"
  case $found in *'"ok": false'*) failed=1 ;; esac
}

for cycle in $(seq 1 "$CYCLES"); do
  for s in $SCENARIOS; do run_scenario "$s" "$cycle"; done
done
touch "$STOP"
[ -n "${LOAD:-}" ] && wait "$LOAD" || true
stop_server "$pid"
rm -f "$STOP"
exit $failed
