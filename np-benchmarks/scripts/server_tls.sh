#!/bin/bash
# HTTPS: the same Spring WebFlux app (ServerBenchApp ... tls) on three servers, one at a time, on a
# quiet machine, two rounds in reverse order:
#
#   np_tls          NativePath engine, TLS terminated in Rust (rustls on ring)
#   netty_jdk_tls        Netty with its default TLS: the JDK's SSLEngine
#   netty_openssl_tls    Netty with BoringSSL through netty-tcnative (its fastest setup)
#
# The load generator verifies the server certificate (LOADGEN_CA). Scenarios: keep-alive requests
# with small, 64 KB and 512 KB responses, JSON, and "handshake": a new connection, so a full TLS
# handshake, for every request, from one client (handshake cost) and from 16 (under load; needs
# Linux or separate machines: on one macOS machine it measures client port reuse instead). Around each measurement the script reads /__stats (heap and engine
# timings) like server_layers.sh.
#
# From the repo root, after:
#   cargo build --release -p np-ffi && cargo build --release -p np-http-server --examples
#   mvn -DskipTests compile
#
#   np-benchmarks/scripts/server_tls.sh <out-dir>
#
# ROUNDS (default 2) sets the number of rounds; odd rounds run the configurations in order, even
# rounds in reverse.
#
# Writes <out-dir>/tls.jsonl; print it with server_layers_table.py.
set -eu
OUT_DIR=$1
SECONDS_PER_RUN=${SECONDS_PER_RUN:-8}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/scripts/lib.sh"
ROUNDS=${ROUNDS:-2}
LG=$ROOT/target/release/examples/loadgen
BODY=$ROOT/np-benchmarks/scripts/order.json
export LOADGEN_CA=$ROOT/np-http-server/tests/fixtures/test-cert.pem
OUT=$OUT_DIR/tls.jsonl
mkdir -p "$OUT_DIR"
: > "$OUT"

CP=$(bench_classpath "$ROOT")
# BoringSSL for Netty, only in the netty_openssl_tls configuration (Netty picks it up automatically).
TCNATIVE=$(tcnative_cp)
JAVA_OPTS="--enable-native-access=ALL-UNNAMED -Xms256m -Xmx256m"





stats() { # port -> JSON from /__stats, or null
  local s
  s=$(curl -sf --cacert "$LOADGEN_CA" "https://127.0.0.1:$1/__stats" || true)
  case "$s" in \{*) echo "$s" ;; *) echo null ;; esac
}

measure() { # config round pid port scenario path conns new_conn [body]
  local before after line s0 s1
  s0=$(stats "$4")
  before=$(cpu_s "$3")
  line=$(LOADGEN_NEW_CONN=$8 LOADGEN_EVENTS=${NP_EVENTS:+-} LOADGEN_TAG="\"exp\":\"throughput\",\"config\":\"$1\",\"round\":$2,\"scenario\":\"$5\"" "$LG" "https://127.0.0.1:$4$6" "$7" "$SECONDS_PER_RUN" ${9:-})
  after=$(cpu_s "$3")
  s1=$(stats "$4")
  local m="{\"exp\":\"throughput\",\"config\":\"$1\",\"round\":$2,\"scenario\":\"$5\",\"connections\":$7,\"server_cpu_s\":$(minus "$after" "$before"),\"stats_before\":$s0,\"stats_after\":$s1,\"result\":$line}"
  echo "$m" | tee -a "$OUT"
  np_measurement "$m"
}

run_config() { # name round
  local name=$1 round=$2 port pid cp server
  case $name in
    np_tls)       port=18443; cp=$CP; server=nativepath ;;
    netty_jdk_tls)     port=18444; cp=$CP; server=netty ;;
    netty_openssl_tls) port=18445; cp=$CP:$TCNATIVE; server=netty ;;
  esac
  require_free_port $port || exit 1
  (cd "$ROOT/np-benchmarks" && exec java $JAVA_OPTS -cp "$cp" ro.bitboy.nativepath.benchmarks.ServerBenchApp $server $port tls) > "$OUT_DIR/$name.log" 2>&1 &
  pid=$!
  for _ in $(seq 1 120); do curl -sf --cacert "$LOADGEN_CA" -o /dev/null "https://127.0.0.1:$port/hello" && break; sleep 0.25; done
  grep -h 'netty TLS provider' "$OUT_DIR/$name.log" || true
  "$LG" "https://127.0.0.1:$port/hello" 64 5 > /dev/null   # warm-up
  measure "$name" "$round" "$pid" "$port" hello_c16 /hello 16 0
  measure "$name" "$round" "$pid" "$port" hello_c64 /hello 64 0
  measure "$name" "$round" "$pid" "$port" payload64k_c16 "/payload?size=65536" 16 0
  measure "$name" "$round" "$pid" "$port" payload512k_c16 "/payload?size=524288" 16 0
  measure "$name" "$round" "$pid" "$port" json_c16 /json 16 0 "@$BODY"
  wait_time_wait
  measure "$name" "$round" "$pid" "$port" handshake_c1 /hello 1 1
  wait_time_wait
  measure "$name" "$round" "$pid" "$port" handshake_c16 /hello 16 1
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

CONFIGS="np_tls netty_jdk_tls"
if [ -n "$TCNATIVE" ]; then
  CONFIGS="$CONFIGS netty_openssl_tls"
else
  echo "netty-tcnative $TCNATIVE_VERSION ($(tcnative_classifier)) not in ~/.m2: skipping netty_openssl_tls (fetch_tcnative in lib.sh gets it)"
fi
CONFIGS=$(np_filter_configs $CONFIGS)
REVERSED=$(reverse $CONFIGS | tr '\n' ' ')
for round in $(seq 1 "$ROUNDS"); do
  if [ $((round % 2)) = 1 ]; then order=$CONFIGS; else order=$REVERSED; fi
  for c in $order; do
    echo "== round $round: $c"
    wait_quiet
    run_config "$c" "$round"
  done
done
echo "done: $OUT"
