#!/bin/bash
# Spring MVC on Tomcat against Spring WebFlux on NativePath and Netty, over HTTP and HTTPS, in
# one session on a quiet machine, two rounds in reverse order:
#
#   mvc_tomcat           Spring MVC on standard Tomcat 10.1 (NIO connector, maxThreads=200)
#   mvc_nativepath          Spring MVC on Tomcat's Catalina with the NativePath engine as transport (spike),
#                        requests on virtual threads
#   mvc_nativepath_lane     the same, requests on the engine's lane threads (no hand-off; blocks the lane)
#   webflux_nativepath      Spring WebFlux on NativePath (ServerBenchApp nativepath)
#   webflux_netty        Spring WebFlux on Netty (ServerBenchApp netty)
#   mvc_tomcat_tls       standard Tomcat, its default TLS: the JDK's SSLEngine
#   mvc_nativepath_tls      Catalina on NativePath, TLS in Rust (rustls on ring)
#   webflux_nativepath_tls  WebFlux on NativePath, TLS in Rust
#   netty_jdk_tls        WebFlux on Netty with the JDK's SSLEngine
#   netty_openssl_tls    WebFlux on Netty with BoringSSL through netty-tcnative
#
# Scenarios: keep-alive "hello" at 16 and 64 connections, 64 KB and 512 KB responses, JSON at 16
# connections; for HTTPS also "handshake": one sequential client, a new connection (full TLS
# handshake) per request, started after TIME_WAIT has drained. Around each measurement the script
# reads /__stats (JVM heap allocated, engine timings) and the server's CPU time. The Spring MVC
# configurations also run "slow5_c64" (each request blocks its thread for 5 ms, a stand-in for a
# database call) and "deferred5_c64" (a DeferredResult completed 5 ms later: async Servlet).
#
# Before each configuration it waits until no other benchmark process runs and the CPU is at least
# QUIET_IDLE % idle. The load generator verifies the server certificate (LOADGEN_CA).
#
# From the repo root, after:
#   cargo build --release -p np-ffi && cargo build --release -p np-http-server --examples
#   mvn -DskipTests compile
#
#   np-benchmarks/scripts/server_tomcat.sh <out-dir>
#
# ROUNDS (default 2) sets the number of rounds; odd rounds run the configurations in order, even
# rounds in reverse. On Linux the HTTPS configurations also run "handshake_c16" (16 clients, a new
# connection per request); on one macOS machine that would measure client port reuse instead.
#
# Writes <out-dir>/tomcat.jsonl; print it with server_layers_table.py.
set -eu
OUT_DIR=$1
OTHERS_MAX_WAIT_S=${OTHERS_MAX_WAIT_S:-7200}
SECONDS_PER_RUN=${SECONDS_PER_RUN:-8}
HANDSHAKE_SECONDS=${HANDSHAKE_SECONDS:-4}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/scripts/lib.sh"
ROUNDS=${ROUNDS:-2}
LG=$ROOT/target/release/examples/loadgen
BODY=$ROOT/np-benchmarks/scripts/order.json
export LOADGEN_CA=$ROOT/np-http-server/tests/fixtures/test-cert.pem
OUT=$OUT_DIR/tomcat.jsonl
mkdir -p "$OUT_DIR"
: > "$OUT"

CP=$(bench_classpath "$ROOT")
# BoringSSL for Netty, only in the netty_openssl_tls configuration (Netty picks it up automatically).
TCNATIVE=$(tcnative_cp)
JAVA_OPTS="--enable-native-access=ALL-UNNAMED -Xms256m -Xmx256m"
echo "java: $(java -version 2>&1 | head -1)"

OTHERS='ServerBenchApp|MvcBenchApp|loadgen|engine_bench|hyper_direct|lanes_test'

# Another benchmark on the same machine (a second agent, say) must not overlap ours.
wait_others() {
  local waited=0 others
  while :; do
    others=$(pgrep -fl "$OTHERS" || true)
    [ -z "$others" ] && return 0
    if [ "$waited" -ge "$OTHERS_MAX_WAIT_S" ]; then
      echo "ABORT: other benchmark processes still running after ${OTHERS_MAX_WAIT_S}s:" >&2
      echo "$others" | cut -c1-120 >&2
      exit 1
    fi
    [ $((waited % 60)) = 0 ] && echo "   waiting for other benchmarks to finish: $(echo "$others" | cut -c1-80 | paste -sd'|' -)"
    sleep 10
    waited=$((waited + 10))
  done
}

# A full disk turns every later result line into garbage; stop instead.
require_disk() {
  local free_kb
  free_kb=$(df -Pk "$OUT_DIR" | awk 'NR == 2 { print $4 }')
  if [ "$free_kb" -lt 1048576 ]; then
    echo "ABORT: less than 1 GB free on the disk holding $OUT_DIR" >&2
    exit 1
  fi
}

# The single-client handshake run needs only a few hundred ports; this keeps leftovers from earlier
# runs from turning it into a measurement of port reuse.
wait_time_wait_low() {
  local n
  for _ in $(seq 1 60); do
    n=$(tw_count)
    [ "$n" -lt 500 ] && { echo "   TIME_WAIT: $n"; return 0; }
    echo "   waiting for TIME_WAIT to drain ($n connections)"
    sleep 5
  done
}

stats() { # base-url -> JSON from /__stats, or null
  local s
  s=$(curl -sf --cacert "$LOADGEN_CA" "$1/__stats" || true)
  case "$s" in \{*) echo "$s" ;; *) echo null ;; esac
}

measure() { # config round pid base scenario path conns new_conn seconds [body]
  local before after line s0 s1 others
  s0=$(stats "$4")
  before=$(cpu_s "$3")
  line=$(LOADGEN_NEW_CONN=$8 "$LG" "$4$6" "$7" "$9" ${10:-})
  after=$(cpu_s "$3")
  s1=$(stats "$4")
  # Anything else benchmarking right after the measurement (another agent may have started)?
  others=$(pgrep -f "$OTHERS" | grep -vx "$3" | wc -l | tr -d ' ')
  [ "$others" != 0 ] && echo "   WARNING: $others other benchmark process(es) running after $1 $5" >&2
  echo "{\"config\":\"$1\",\"round\":$2,\"scenario\":\"$5\",\"connections\":$7,\"server_cpu_s\":$(minus "$after" "$before"),\"other_benchmarks\":$others,\"stats_before\":$s0,\"stats_after\":$s1,\"result\":$line}" | tee -a "$OUT"
}

run_config() { # name round
  local name=$1 round=$2 port cp=$CP main args scheme=http tls=""
  case $name in
    mvc_tomcat)          port=18300; main=MvcBenchApp; args="" ;;
    mvc_nativepath)         port=18301; main=MvcBenchApp; args="--bench.transport=nativepath" ;;
    mvc_nativepath_lane)    port=18304; main=MvcBenchApp; args="--bench.transport=nativepath --bench.dispatch=lane" ;;
    webflux_nativepath)     port=18302; main=ServerBenchApp; args=nativepath ;;
    webflux_netty)       port=18303; main=ServerBenchApp; args=netty ;;
    mvc_tomcat_tls)      port=18310; main=MvcBenchApp; args=""; tls=1 ;;
    mvc_nativepath_tls)     port=18311; main=MvcBenchApp; args="--bench.transport=nativepath"; tls=1 ;;
    webflux_nativepath_tls) port=18312; main=ServerBenchApp; args=nativepath; tls=1 ;;
    netty_jdk_tls)       port=18313; main=ServerBenchApp; args=netty; tls=1 ;;
    netty_openssl_tls)   port=18314; main=ServerBenchApp; args=netty; tls=1; cp=$CP:$TCNATIVE ;;
  esac
  local argv
  if [ "$main" = MvcBenchApp ]; then
    argv="$port ${tls:+tls} $args"
  else
    argv="$args $port ${tls:+tls}"
  fi
  [ -n "$tls" ] && scheme=https
  local base=$scheme://127.0.0.1:$port
  require_free_port $port || exit 1
  (cd "$ROOT/np-benchmarks" && exec java $JAVA_OPTS -cp "$cp" ro.bitboy.nativepath.benchmarks.$main $argv) > "$OUT_DIR/$name.log" 2>&1 &
  local pid=$!
  for _ in $(seq 1 120); do curl -sf --cacert "$LOADGEN_CA" -o /dev/null "$base/hello" && break; sleep 0.25; done
  grep -h 'netty TLS provider\|Tomcat started\|NativePath.*started' "$OUT_DIR/$name.log" || true
  "$LG" "$base/hello" 64 5 > /dev/null   # warm-up
  measure "$name" "$round" "$pid" "$base" hello_c16 /hello 16 0 "$SECONDS_PER_RUN"
  measure "$name" "$round" "$pid" "$base" hello_c64 /hello 64 0 "$SECONDS_PER_RUN"
  measure "$name" "$round" "$pid" "$base" payload64k_c16 "/payload?size=65536" 16 0 "$SECONDS_PER_RUN"
  measure "$name" "$round" "$pid" "$base" payload512k_c16 "/payload?size=524288" 16 0 "$SECONDS_PER_RUN"
  measure "$name" "$round" "$pid" "$base" json_c16 /json 16 0 "$SECONDS_PER_RUN" "@$BODY"
  if [ "$main" = MvcBenchApp ]; then
    measure "$name" "$round" "$pid" "$base" slow5_c64 "/slow?ms=5" 64 0 "$SECONDS_PER_RUN"
    measure "$name" "$round" "$pid" "$base" deferred5_c64 "/deferred?ms=5" 64 0 "$SECONDS_PER_RUN"
  fi
  if [ -n "$tls" ]; then
    wait_time_wait_low
    measure "$name" "$round" "$pid" "$base" handshake_c1 /hello 1 1 "$HANDSHAKE_SECONDS"
    if [ "$OS" = Linux ]; then
      wait_time_wait
      measure "$name" "$round" "$pid" "$base" handshake_c16 /hello 16 1 "$SECONDS_PER_RUN"
    fi
  fi
  kill "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

DEFAULT_CONFIGS="mvc_tomcat mvc_nativepath mvc_nativepath_lane webflux_nativepath webflux_netty mvc_tomcat_tls mvc_nativepath_tls webflux_nativepath_tls netty_jdk_tls"
if [ -n "$TCNATIVE" ]; then
  DEFAULT_CONFIGS="$DEFAULT_CONFIGS netty_openssl_tls"
else
  echo "netty-tcnative $TCNATIVE_VERSION ($(tcnative_classifier)) not in ~/.m2: skipping netty_openssl_tls (fetch_tcnative in lib.sh gets it)"
fi
CONFIGS=${CONFIGS:-$DEFAULT_CONFIGS}
REVERSED=$(reverse $CONFIGS | tr '\n' ' ')
for round in $(seq 1 "$ROUNDS"); do
  if [ $((round % 2)) = 1 ]; then order=$CONFIGS; else order=$REVERSED; fi
  for c in $order; do
    echo "== round $round: $c ($(date +%H:%M:%S))"
    require_disk
    wait_others
    wait_quiet
    run_config "$c" "$round"
  done
done
echo "done: $OUT"
