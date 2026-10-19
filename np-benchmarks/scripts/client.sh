#!/bin/bash
# The HTTP clients against a server that is not in their process: java.net.http, Spring RestClient on
# the JDK, Spring RestClient on NativePath and the NativePath client itself, for each response size
# and number of threads. Reports requests per second, latency, CPU and heap allocation per request,
# all of the client's JVM. The server (plain Netty, the same for every client) runs on the server
# host; the client JVM runs on the client host.
#
#   np-benchmarks/scripts/client.sh [--server H --client H --server-ip IP --cores N] <out-dir>
#
# Settings: NP_CLIENT_SECONDS (per measurement), _ROUNDS, _CLIENTS, _SIZES, _THREADS.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/remote/hosts.sh"
parse_host_options "$@"
OUT_DIR=$(mkdir -p "${ARGS[0]:?out-dir}" && cd "${ARGS[0]}" && pwd)
SECS=${NP_CLIENT_SECONDS:-10}; ROUNDS=${NP_CLIENT_ROUNDS:-3}
CLIENTS=${NP_CLIENT_CLIENTS:-jdk,rest_jdk,rest_nativepath,nativepath}
SIZES=${NP_CLIENT_SIZES:-256,8192,65536,524288}; THREADS=${NP_CLIENT_THREADS:-1,16}
CLIENTS=${CLIENTS// /,}; SIZES=${SIZES// /,}; THREADS=${THREADS// /,}
PORT=18770

stop_on_exit
echo "== build" >&2
prepare_server >&2
prepare_client >&2
describe_hosts | tee "$OUT_DIR/machines.txt"
require_server_port $PORT || exit 1
server_quiet
pid=$(start_server head "client-server.log" "" "java --enable-native-access=ALL-UNNAMED -Xms256m -Xmx256m -cp \$cp ro.bitboy.nativepath.benchmarks.RawNettyServer $PORT")
RUNNING_PID=$pid
wait_up $PORT || { echo "the server did not start" >&2; exit 1; }

# The client JVM gets the same cores a load generator would; -Xmx is the same for every client.
pin=""; [ -n "$CORES" ] && pin="pinned $CORES"
on "$CLIENT" "cd np-benchmarks && $pin java --enable-native-access=ALL-UNNAMED -Xms512m -Xmx512m -cp \$(cat ../bench.cp) \
  ro.bitboy.nativepath.benchmarks.ClientBench http://$SERVER_IP:$PORT $CLIENTS $SIZES $THREADS $SECS $ROUNDS" | tee "$OUT_DIR/client.jsonl"
stop_server "$pid"
python3 - "$OUT_DIR/client.jsonl" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.startswith("{")]
bad = [r for r in rows if r["result"]["errors"] or not r["result"]["requests"]]
print(f"{len(rows)} measurements, {len(bad)} with errors")
sys.exit(1 if bad or not rows else 0)
PY
