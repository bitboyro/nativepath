#!/bin/bash
# Load ramp: how many requests per second each server sustains before latency breaks, and what
# happens past that point. Per configuration: a closed-loop run finds the peak throughput P, then
# open-loop runs offer fixed rates (fractions of P, see RAMP_STEPS) on RAMP_CONNECTIONS
# connections, with latency counted from when each request was due (loadgen's LOADGEN_RATE).
# server_ramp_table.py reads the knee off the result: the highest offered rate still served in
# full with p99 under the SLO.
#
#   engine               NativePath engine with Rust workers (the ceiling of the design)
#   spring_nativepath       Spring WebFlux on NativePath, default settings
#   spring_nativepath_tuned the same with RAMP_TUNED (default BATCH=4, SPIN=1)
#   spring_nativepath_shed  the same with a per-lane queue limit (RAMP_MAX_QUEUED, default 16): past
#                        the knee, excess requests get a fast 503 instead of waiting
#   spring_netty         Spring WebFlux on Netty (reference)
#
# One machine: from the repo root,
#   np-benchmarks/scripts/server_ramp.sh <out-dir>
# Two machines (release measurements; prepare both once with np-benchmarks/remote/setup-ubuntu.sh):
#   np-benchmarks/scripts/server_ramp.sh --server user@10.0.0.5 --client user@10.0.0.6 \
#       --server-ip 10.0.0.5 --cores 4 <out-dir>
# (see np-benchmarks/remote/hosts.sh). Meant for Linux: on macOS, timer coalescing and wake-up latency
# of idle threads add milliseconds of tail to any server, plain hyper included.
#
# Settings: RAMP_CONFIGS (subset of the names above), RAMP_STEPS (default
# "0.3 0.5 0.7 0.8 0.9 1.0 1.1 1.3"), RAMP_CONNECTIONS (default 128; the listen backlog is 128, so
# more cannot all connect at once), SECONDS_PER_RUN (default 8). Writes <out-dir>/ramp.jsonl and
# machines.txt.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/remote/hosts.sh"
parse_host_options "$@"
OUT_DIR=$(mkdir -p "${ARGS[0]:?out-dir}" && cd "${ARGS[0]}" && pwd)
SECONDS_PER_RUN=${SECONDS_PER_RUN:-8}
RAMP_STEPS=${RAMP_STEPS:-"0.3 0.5 0.7 0.8 0.9 1.0 1.1 1.3"}
RAMP_CONNECTIONS=${RAMP_CONNECTIONS:-128}
RAMP_TUNED=${RAMP_TUNED:-"NATIVEPATH_SERVER_BATCH=4 NATIVEPATH_SERVER_SPIN=1"}
RAMP_MAX_QUEUED=${RAMP_MAX_QUEUED:-16}
OUT=$OUT_DIR/ramp.jsonl
: > "$OUT"

stop_on_exit
echo "== build" >&2
prepare_server >&2
prepare_client >&2
describe_hosts | tee "$OUT_DIR/machines.txt"

PORT=18630
SPRING="java --enable-native-access=ALL-UNNAMED -Xms256m -Xmx256m -cp \$cp ro.bitboy.nativepath.benchmarks.ServerBenchApp"

start_config() { # name -> prints the server's PID
  case $1 in
    engine)               start_server head "$1.log" "" "../target/release/examples/engine_bench $PORT" ;;
    spring_nativepath)       start_server head "$1.log" "" "$SPRING nativepath $PORT" ;;
    spring_nativepath_tuned) start_server head "$1.log" "$RAMP_TUNED" "$SPRING nativepath $PORT" ;;
    spring_nativepath_shed)  start_server head "$1.log" "NATIVEPATH_SERVER_MAX_QUEUED=$RAMP_MAX_QUEUED" "$SPRING nativepath $PORT" ;;
    spring_netty)         start_server head "$1.log" "" "$SPRING netty $PORT" ;;
    *) echo "unknown configuration $1" >&2; exit 1 ;;
  esac
}

record() { # config phase fraction rate pid loadgen-arguments...
  local config=$1 phase=$2 fraction=$3 rate=$4 pid=$5 before after line
  shift 5
  before=$(server_cpu "$pid")
  line=$(NP_TAG="\"exp\":\"ramp\",\"config\":\"$config\",\"phase\":\"$phase\",\"fraction\":$fraction,\"rate\":$rate" loadgen "$@")
  after=$(server_cpu "$pid")
  local m="{\"exp\":\"ramp\",\"config\":\"$config\",\"phase\":\"$phase\",\"fraction\":$fraction,\"rate\":$rate,\"server_cpu_s\":$(minus "$after" "$before"),\"result\":$line}"
  echo "$m" | tee -a "$OUT"
  np_measurement "$m"
}

run_config() { # name
  local pid peak rate url
  url="$(server_url $PORT)/hello"
  require_server_port $PORT || exit 1
  pid=$(start_config "$1")
  RUNNING_PID=$pid
  wait_up $PORT || { echo "$1 did not start (see its log on the server)" >&2; stop_server "$pid"; exit 1; }
  loadgen "$url" 64 5 > /dev/null   # warm-up (JIT for the JVM ones)
  record "$1" closed 0 0 "$pid" "$url" 64 "$SECONDS_PER_RUN"
  peak=$(tail -1 "$OUT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["rps"])')
  for f in $RAMP_STEPS; do
    rate=$(awk -v p="$peak" -v f="$f" 'BEGIN { printf "%d", p * f }')
    record "$1" open "$f" "$rate" "$pid" LOADGEN_RATE="$rate" "$url" "$RAMP_CONNECTIONS" "$SECONDS_PER_RUN"
  done
  stop_server "$pid"
}

for c in ${RAMP_CONFIGS:-engine spring_nativepath spring_nativepath_tuned spring_nativepath_shed spring_netty}; do
  echo "== $c"
  server_quiet
  run_config "$c"
done
python3 "$ROOT/np-benchmarks/scripts/server_ramp_table.py" "$OUT" > "$OUT_DIR/ramp.table.txt" || true
echo "done: $OUT"
