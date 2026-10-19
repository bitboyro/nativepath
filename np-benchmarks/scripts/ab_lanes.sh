#!/bin/bash
# A/B of the lane machinery: a base commit (default origin/main) against this tree, and this tree's
# lane settings against its defaults, alternating on the same machine(s). Each configuration runs
# in its own process, one at a time, driven by the Rust load generator (closed loop); rounds
# alternate the order (odd rounds in order, even rounds reversed), and the server's CPU idle gate
# runs before each one.
#
#   engine@base          base commit's engine, Rust workers ("hello")
#   engine               this tree, default settings
#   engine_batch4        NATIVEPATH_SERVER_BATCH=4
#   engine_spin1         NATIVEPATH_SERVER_SPIN=1
#   engine_batch4_spin1  both
#   engine_work20        each answer burns 20 us of CPU first (ENGINE_WORK_US=20)
#   engine_work20_steal  the same with work stealing
#   spring@base          base commit's Spring WebFlux on NativePath
#   spring               this tree's, default settings
#   spring_batch4_spin1  this tree's with BATCH=4 and SPIN=1
#   spring_netty         Spring WebFlux on Netty (reference)
#
# One machine (CI, a laptop): from the repo root,
#   np-benchmarks/scripts/ab_lanes.sh <out-dir>
# Two machines (release measurements; prepare both once with np-benchmarks/remote/setup-ubuntu.sh):
#   np-benchmarks/scripts/ab_lanes.sh --server user@10.0.0.5 --client user@10.0.0.6 \
#       --server-ip 10.0.0.5 --cores 4 <out-dir>
# (see np-benchmarks/remote/hosts.sh). The base commit is checked out locally into
# <out-dir>/base-src (a git worktree), copied to the server and built there. engine@base needs a
# base whose engine_bench reads BENCH_BIND; for an older base it is skipped on a remote server.
#
# AB_BASE (default origin/main) picks the base commit; AB_CONFIGS a subset of the names above;
# ROUNDS (default 3), SECONDS_PER_RUN (default 8). Writes <out-dir>/ab.jsonl (print it with
# server_layers_table.py), refs.txt and machines.txt.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/remote/hosts.sh"
parse_host_options "$@"
OUT_DIR=$(mkdir -p "${ARGS[0]:?out-dir}" && cd "${ARGS[0]}" && pwd)
SECONDS_PER_RUN=${SECONDS_PER_RUN:-8}
ROUNDS=${ROUNDS:-3}
AB_BASE=${AB_BASE:-origin/main}
OUT=$OUT_DIR/ab.jsonl
: > "$OUT"

BASE_TREE=$OUT_DIR/base-src
remove_base() { git -C "$ROOT" worktree remove --force "$BASE_TREE" >&2 || true; }
stop_on_exit remove_base
if [ ! -d "$BASE_TREE" ]; then
  git -C "$ROOT" worktree add --detach "$BASE_TREE" "$AB_BASE" >&2
fi
echo "base: $AB_BASE = $(git -C "$BASE_TREE" rev-parse --short HEAD); head: $(git -C "$ROOT" rev-parse --short HEAD)" | tee "$OUT_DIR/refs.txt"

echo "== build" >&2
prepare_server head >&2
prepare_server base >&2
prepare_client >&2
describe_hosts | tee "$OUT_DIR/machines.txt"

CONFIGS=${AB_CONFIGS:-"engine@base engine engine_batch4 engine_spin1 engine_batch4_spin1 engine_work20 engine_work20_steal spring@base spring spring_batch4_spin1 spring_netty"}
if [ "$SERVER" != local ] && ! grep -q BENCH_BIND "$BASE_TREE/np-http-server/examples/engine_bench.rs"; then
  echo "engine@base skipped: the base's engine_bench listens on 127.0.0.1 only" | tee -a "$OUT_DIR/refs.txt"
  CONFIGS=$(echo "$CONFIGS" | tr ' ' '\n' | grep -vx 'engine@base' | tr '\n' ' ')
fi

measure() { # config round pid port scenario path conns
  local before after line s0 s1
  s0=$(server_stats "$4")
  before=$(server_cpu "$3")
  line=$(loadgen "$(server_url "$4")$6" "$7" "$SECONDS_PER_RUN")
  after=$(server_cpu "$3")
  s1=$(server_stats "$4")
  echo "{\"config\":\"$1\",\"round\":$2,\"scenario\":\"$5\",\"connections\":$7,\"server_cpu_s\":$(minus "$after" "$before"),\"stats_before\":$s0,\"stats_after\":$s1,\"result\":$line}" | tee -a "$OUT"
}

PORT=18620
JAVA="java --enable-native-access=ALL-UNNAMED -Xms256m -Xmx256m -cp \$cp"
ENGINE="../target/release/examples/engine_bench $PORT"
SPRING="$JAVA ro.bitboy.nativepath.benchmarks.ServerBenchApp"
S=NATIVEPATH_SERVER_

start_config() { # name -> prints the server's PID
  case $1 in
    engine@base)         start_server base "$1.log" "" "$ENGINE" ;;
    engine)              start_server head "$1.log" "" "$ENGINE" ;;
    engine_batch4)       start_server head "$1.log" "${S}BATCH=4" "$ENGINE" ;;
    engine_spin1)        start_server head "$1.log" "${S}SPIN=1" "$ENGINE" ;;
    engine_batch4_spin1) start_server head "$1.log" "${S}BATCH=4 ${S}SPIN=1" "$ENGINE" ;;
    engine_work20)       start_server head "$1.log" "ENGINE_WORK_US=20" "$ENGINE" ;;
    engine_work20_steal) start_server head "$1.log" "ENGINE_WORK_US=20 ${S}WORK_STEALING=true" "$ENGINE" ;;
    spring@base)         start_server base "$1.log" "" "$SPRING nativepath $PORT" ;;
    spring)              start_server head "$1.log" "" "$SPRING nativepath $PORT" ;;
    spring_batch4_spin1) start_server head "$1.log" "${S}BATCH=4 ${S}SPIN=1" "$SPRING nativepath $PORT" ;;
    spring_netty)        start_server head "$1.log" "" "$SPRING netty $PORT" ;;
    *) echo "unknown configuration $1" >&2; exit 1 ;;
  esac
}

run_config() { # name round
  local pid
  require_server_port $PORT || exit 1
  pid=$(start_config "$1")
  RUNNING_PID=$pid
  wait_up $PORT || { echo "$1 did not start (see its log on the server)" >&2; stop_server "$pid"; exit 1; }
  loadgen "$(server_url $PORT)/hello" 64 5 > /dev/null   # warm-up (JIT for the JVM ones)
  measure "$1" "$2" "$pid" $PORT hello_c16 /hello 16
  measure "$1" "$2" "$pid" $PORT hello_c64 /hello 64
  stop_server "$pid"
}

REVERSED=$(reverse $CONFIGS | tr '\n' ' ')
for round in $(seq 1 "$ROUNDS"); do
  if [ $((round % 2)) = 1 ]; then order=$CONFIGS; else order=$REVERSED; fi
  for c in $order; do
    echo "== round $round: $c"
    server_quiet
    run_config "$c" "$round"
  done
done
python3 "$ROOT/np-benchmarks/scripts/server_layers_table.py" "$OUT" > "$OUT_DIR/ab.table.txt" || true
echo "done: $OUT"
