#!/bin/bash
# Server cost by layer, on a quiet machine. Each configuration runs in its own process, one at a
# time, driven by the Rust load generator; two rounds, the second in reverse order. Then a JMH run
# of the worker side with no network (ServerPathBenchmark), step by step.
#
#   L1 hyper_sysalloc   plain hyper, system allocator           (machine floor)
#   L2 hyper_zeroizing  plain hyper, zeroizing allocator        (L2-L1: cost of wiping on free)
#   L3 engine           NativePath lanes, Rust workers         (L3-L2: cost of the lane design)
#   L4 engine_java      + Java workers, no Spring               (L4-L3: crossing into the JVM)
#   L5 spring_nativepath   + Spring WebFlux                        (L5-L4: Spring and our adapter)
#   L6 spring_netty     Spring WebFlux on Netty                 (reference)
#
# Around each measurement the script reads the server's /__stats (JVM heap allocated, engine
# timings), so the table can show heap bytes per request and where engine time goes.
#
# Before each configuration (and the JMH run) the script waits until the CPU is at least QUIET_IDLE % idle (a fresh
# one-second sample, not the load average), so other work on the machine cannot skew a run.
#
# From the repo root, after:
#   cargo build --release -p np-ffi && cargo build --release -p np-http-server --examples
#   mvn -DskipTests compile
#
#   np-benchmarks/scripts/server_layers.sh <out-dir>
#
# ROUNDS (default 2) sets the number of rounds; odd rounds run the configurations in order, even
# rounds in reverse.
#
# Writes <out-dir>/layers.jsonl (print it with server_layers_table.py) and
# <out-dir>/server_path_jmh.txt.
set -eu
OUT_DIR=$1
SECONDS_PER_RUN=${SECONDS_PER_RUN:-8}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/scripts/lib.sh"
ROUNDS=${ROUNDS:-2}
EX=$ROOT/target/release/examples
LG=$EX/loadgen
BODY=$ROOT/np-benchmarks/scripts/order.json
OUT=$OUT_DIR/layers.jsonl
mkdir -p "$OUT_DIR"
: > "$OUT"

CP=$(bench_classpath "$ROOT")
JAVA="java --enable-native-access=ALL-UNNAMED -Xms256m -Xmx256m -cp $CP"




stats() { # port -> JSON from /__stats, or null where the server has none
  local s
  s=$(curl -sf "http://127.0.0.1:$1/__stats" || true)
  case "$s" in \{*) echo "$s" ;; *) echo null ;; esac
}

measure() { # config round pid port scenario path conns [body]
  local before after line s0 s1
  s0=$(stats "$4")
  before=$(cpu_s "$3")
  line=$(LOADGEN_EVENTS=${NP_EVENTS:+-} LOADGEN_TAG="\"exp\":\"throughput\",\"config\":\"$1\",\"round\":$2,\"scenario\":\"$5\"" "$LG" "http://127.0.0.1:$4$6" "$7" "$SECONDS_PER_RUN" ${8:-})
  after=$(cpu_s "$3")
  s1=$(stats "$4")
  local m="{\"exp\":\"throughput\",\"config\":\"$1\",\"round\":$2,\"scenario\":\"$5\",\"connections\":$7,\"server_cpu_s\":$(minus "$after" "$before"),\"stats_before\":$s0,\"stats_after\":$s1,\"result\":$line}"
  echo "$m" | tee -a "$OUT"
  np_measurement "$m"
}

run_config() { # name round
  local name=$1 round=$2 port pid
  case $name in
    hyper_sysalloc) port=18094 ;; hyper_zeroizing) port=18095 ;; engine) port=18096 ;;
    engine_java) port=18097 ;; spring_nativepath) port=18098 ;; spring_netty) port=18099 ;;
  esac
  require_free_port $port || exit 1
  case $name in
    hyper_sysalloc)  port=18094; "$EX/hyper_direct_sysalloc" $port > "$OUT_DIR/$name.log" 2>&1 & pid=$! ;;
    hyper_zeroizing) port=18095; "$EX/hyper_direct" $port > "$OUT_DIR/$name.log" 2>&1 & pid=$! ;;
    engine)          port=18096; "$EX/engine_bench" $port > "$OUT_DIR/$name.log" 2>&1 & pid=$! ;;
    engine_java)     port=18097; (cd "$ROOT/np-benchmarks" && exec $JAVA ro.bitboy.nativepath.benchmarks.RawServerBench $port) > "$OUT_DIR/$name.log" 2>&1 & pid=$! ;;
    spring_nativepath)  port=18098; (cd "$ROOT/np-benchmarks" && exec $JAVA ro.bitboy.nativepath.benchmarks.ServerBenchApp nativepath $port) > "$OUT_DIR/$name.log" 2>&1 & pid=$! ;;
    spring_netty)    port=18099; (cd "$ROOT/np-benchmarks" && exec $JAVA ro.bitboy.nativepath.benchmarks.ServerBenchApp netty $port) > "$OUT_DIR/$name.log" 2>&1 & pid=$! ;;
  esac
  for _ in $(seq 1 120); do curl -sf -o /dev/null "http://127.0.0.1:$port/hello" && break; sleep 0.25; done
  "$LG" "http://127.0.0.1:$port/hello" 64 5 > /dev/null   # warm-up (JIT for the JVM ones)
  measure "$name" "$round" "$pid" "$port" hello_c16 /hello 16
  measure "$name" "$round" "$pid" "$port" hello_c64 /hello 64
  case $name in spring_*)
    measure "$name" "$round" "$pid" "$port" payload64k_c16 "/payload?size=65536" 16
    measure "$name" "$round" "$pid" "$port" json_c16 /json 16 "@$BODY" ;;
  esac
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

CONFIGS=$(np_filter_configs hyper_sysalloc hyper_zeroizing engine engine_java spring_nativepath spring_netty)
REVERSED=$(reverse $CONFIGS | tr '\n' ' ')
for round in $(seq 1 "$ROUNDS"); do
  if [ $((round % 2)) = 1 ]; then order=$CONFIGS; else order=$REVERSED; fi
  for c in $order; do
    echo "== round $round: $c"
    wait_quiet
    run_config "$c" "$round"
  done
done
if [ "${SKIP_JMH:-0}" != 1 ]; then
  echo "== JMH: worker side with no network (ServerPathBenchmark)"
  wait_quiet
  (cd "$ROOT/np-benchmarks" && java -cp "$CP" org.openjdk.jmh.Main 'ServerPathBenchmark' -prof gc) > "$OUT_DIR/server_path_jmh.txt" 2>&1
  grep -E '^ServerPathBenchmark\.[a-zA-Z_0-9]+(:gc\.alloc\.rate\.norm)? ' "$OUT_DIR/server_path_jmh.txt" || true
fi
echo "done: $OUT"
