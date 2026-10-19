#!/bin/bash
# Same Spring WebFlux app (ServerBenchApp) on Netty and on NativePath, each in its own JVM, driven
# by the Rust load generator from a third process. Two rounds in alternating order.
#
# From the repo root, after `cargo build --release -p np-ffi` and
# `cargo build --release -p np-http-server --example loadgen` and `mvn -DskipTests compile`:
#
#   np-benchmarks/scripts/server_bench.sh <out-dir>
#
# Writes <out-dir>/server_results.jsonl; print it with server_results_table.py.
set -eu
OUT_DIR=$1
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LG=$ROOT/target/release/examples/loadgen
BODY=$ROOT/np-benchmarks/scripts/order.json
OUT=$OUT_DIR/server_results.jsonl
mkdir -p "$OUT_DIR"
: > "$OUT"

CP=$(cd "$ROOT/np-benchmarks" && mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout | tr ':' '\n' | grep -v '/ro/bitboy/nativepath/' | paste -sd: -)
CP=$ROOT/np-binding/target/classes:$ROOT/np-http-spring-starter/target/classes:$ROOT/np-benchmarks/target/classes:$CP

port_of() { if [ "$1" = nativepath ]; then echo 18081; else echo 18082; fi; }
pid_of() { cat "$OUT_DIR/srv_$1.pid"; }

for srv in nativepath netty; do
  (cd "$ROOT/np-benchmarks" && exec java --enable-native-access=ALL-UNNAMED -Xms256m -Xmx256m -cp "$CP" \
      ro.bitboy.nativepath.benchmarks.ServerBenchApp $srv "$(port_of $srv)") > "$OUT_DIR/srv_$srv.log" 2>&1 &
  echo $! > "$OUT_DIR/srv_$srv.pid"
done
trap 'kill $(pid_of nativepath) $(pid_of netty) 2>/dev/null || true' EXIT
for _ in $(seq 1 60); do
  curl -sf -o /dev/null "http://127.0.0.1:18081/hello" && curl -sf -o /dev/null "http://127.0.0.1:18082/hello" && break
  sleep 0.5
done
for srv in nativepath netty; do echo "$srv idle RSS: $(ps -o rss= -p "$(pid_of $srv)") KB"; done

scenario() { # name url conns [body]
  for srv in $ORDER; do
    url=${2/PORT/$(port_of $srv)}
    line=$("$LG" "$url" "$3" 10 ${4:-})
    rss=$(ps -o rss= -p "$(pid_of $srv)" | tr -d ' ')
    echo "{\"round\":$ROUND,\"server\":\"$srv\",\"scenario\":\"$1\",\"rss_kb_after\":$rss,\"result\":$line}" | tee -a "$OUT"
  done
}

for srv in nativepath netty; do "$LG" "http://127.0.0.1:$(port_of $srv)/hello" 64 8 > /dev/null; done   # JIT warm-up
for ROUND in 1 2; do
  if [ $ROUND = 1 ]; then ORDER="nativepath netty"; else ORDER="netty nativepath"; fi
  scenario hello_c1       "http://127.0.0.1:PORT/hello" 1
  scenario hello_c16      "http://127.0.0.1:PORT/hello" 16
  scenario hello_c64      "http://127.0.0.1:PORT/hello" 64
  scenario payload64k_c16 "http://127.0.0.1:PORT/payload?size=65536" 16
  scenario echo16k_c16    "http://127.0.0.1:PORT/echo" 16 16384
  scenario json_c16       "http://127.0.0.1:PORT/json" 16 "@$BODY"
done
