#!/bin/bash
# HTTP and HTTPS server comparison across two machines: the server under test on one, the load
# generator on the other, each pinned to its own cores, talking over the real network. Removes the
# two limits of single-machine runs: the load generator stealing the server's CPU, and one
# machine's client ports limiting handshake tests.
#
# Prepare both machines once with setup-ubuntu.sh, then from the repo root on your own machine:
#
#   np-benchmarks/remote/two_hosts.sh --server user@10.0.0.5 --client user@10.0.0.6 \
#       --server-ip 10.0.0.5 [--cores 4] [--rounds 3] [--seconds 10] [--port 18600] <out-dir>
#
# --server-ip is the address the client reaches the server on (a private IP if both machines are
# in one network). Hosts may be "local" for a dry run on this machine.
#
# Configurations: Spring WebFlux on NativePath and on Netty over HTTP; over HTTPS NativePath,
# Netty with JDK TLS and Netty with BoringSSL. Writes <out-dir>/two_hosts.jsonl (print it with
# np-benchmarks/scripts/server_layers_table.py) and <out-dir>/machines.txt.
set -eu

CORES=4 ROUNDS=${ROUNDS:-3} SECONDS_PER_RUN=${SECONDS_PER_RUN:-10} PORT=18600 WARMUP_S=${WARMUP_S:-5}
SERVER="" CLIENT="" SERVER_IP=""
while [ $# -gt 1 ]; do
  case $1 in
    --server) SERVER=$2; shift 2 ;;
    --client) CLIENT=$2; shift 2 ;;
    --server-ip) SERVER_IP=$2; shift 2 ;;
    --cores) CORES=$2; shift 2 ;;
    --rounds) ROUNDS=$2; shift 2 ;;
    --seconds) SECONDS_PER_RUN=$2; shift 2 ;;
    --port) PORT=$2; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
OUT_DIR=${1:?out-dir}
[ -n "$SERVER" ] && [ -n "$CLIENT" ] && [ -n "$SERVER_IP" ] || { echo "need --server, --client and --server-ip" >&2; exit 2; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/remote/hosts.sh"   # on, sync_tree, tree_dir (and lib.sh)
mkdir -p "$OUT_DIR"
OUT=$OUT_DIR/two_hosts.jsonl
: > "$OUT"
SDIR=$(tree_dir "$SERVER")
CDIR=$(tree_dir "$CLIENT")

echo "== sync and build"
sync_tree "$SERVER"
[ "$CLIENT" != "$SERVER" ] && sync_tree "$CLIENT"
on "$SERVER" "[ -f .prebuilt ] || { cargo build -q --release -p np-ffi && mvn -B -q -DskipTests compile && fetch_tcnative; } && bench_classpath \$PWD > bench.cp"
on "$CLIENT" "[ -f .prebuilt ] || cargo build -q --release -p np-http-server --example loadgen"

echo "== certificate for $SERVER_IP"
on "$SERVER" "$(bench_cert_cmd)"
host_get "$SERVER" bench-cert.pem "$OUT_DIR/bench-cert.pem"
[ "$CLIENT" != "$SERVER" ] && host_put "$CLIENT" "$OUT_DIR/bench-cert.pem" bench-cert.pem

describe_hosts | tee "$OUT_DIR/machines.txt"

# Client-side load generator (run through on "$CLIENT").
LG="target/release/examples/loadgen"
stats() { # scheme port
  local s
  s=$(on "$CLIENT" "curl -sf --cacert bench-cert.pem $1://$SERVER_IP:$2/__stats" 2>/dev/null || true)
  case "$s" in \{*) echo "$s" ;; *) echo null ;; esac
}

measure() { # config round pid scheme port scenario path conns new_conn [body]
  local s0 s1 before after line
  s0=$(stats "$4" "$5")
  before=$(on "$SERVER" "cpu_s $3")
  line=$(on "$CLIENT" "LOADGEN_CA=bench-cert.pem LOADGEN_NEW_CONN=$9 LOADGEN_EVENTS=${NP_EVENTS:+-} LOADGEN_TAG='\"exp\":\"throughput\",\"config\":\"$1\",\"round\":$2,\"scenario\":\"$6\"' pinned $CORES $LG $4://$SERVER_IP:$5$7 $8 $SECONDS_PER_RUN ${10:-}")
  after=$(on "$SERVER" "cpu_s $3")
  s1=$(stats "$4" "$5")
  local m="{\"exp\":\"throughput\",\"config\":\"$1\",\"round\":$2,\"scenario\":\"$6\",\"connections\":$8,\"server_cpu_s\":$(minus "$after" "$before"),\"stats_before\":$s0,\"stats_after\":$s1,\"result\":$line}"
  echo "$m" | tee -a "$OUT"
  np_measurement "$m"
}

run_config() { # name round
  local name=$1 round=$2 server scheme tls="" port=$PORT extra_cp=""
  case $name in
    spring_nativepath)    server=nativepath; scheme=http ;;
    spring_netty)      server=netty;   scheme=http ;;
    np_tls)       server=nativepath; scheme=https; tls=tls ;;
    netty_jdk_tls)     server=netty;   scheme=https; tls=tls ;;
    netty_openssl_tls) server=netty;   scheme=https; tls=tls; extra_cp=":\$(tcnative_cp)" ;;
  esac
  local pid
  on "$SERVER" "require_free_port $port" || exit 1
  # exec_pinned replaces the shell, so $! is the JVM itself (its CPU time is what we read).
  pid=$(on "$SERVER" "cd np-benchmarks
cp=\$(cat ../bench.cp)$extra_cp
BENCH_BIND=0.0.0.0 BENCH_CERT=\$PWD/../bench-cert.pem BENCH_KEY=\$PWD/../bench-key.pem \\
  nohup bash -c \". scripts/lib.sh; exec_pinned $CORES java --enable-native-access=ALL-UNNAMED -Xms512m -Xmx512m -cp \$cp ro.bitboy.nativepath.benchmarks.ServerBenchApp $server $port $tls\" < /dev/null > ../server.log 2>&1 &
echo \$!")
  for _ in $(seq 1 160); do
    on "$CLIENT" "curl -sf --cacert bench-cert.pem -o /dev/null $scheme://$SERVER_IP:$port/hello" 2>/dev/null && break
    sleep 0.25
  done
  on "$CLIENT" "LOADGEN_CA=bench-cert.pem pinned $CORES $LG $scheme://$SERVER_IP:$port/hello 64 $WARMUP_S" > /dev/null   # warm-up
  measure "$name" "$round" "$pid" $scheme $port hello_c16 /hello 16 0
  measure "$name" "$round" "$pid" $scheme $port hello_c64 /hello 64 0
  measure "$name" "$round" "$pid" $scheme $port payload64k_c16 "/payload?size=65536" 16 0
  measure "$name" "$round" "$pid" $scheme $port json_c16 /json 16 0 @np-benchmarks/scripts/order.json
  if [ $scheme = https ]; then
    measure "$name" "$round" "$pid" $scheme $port payload512k_c16 "/payload?size=524288" 16 0
    measure "$name" "$round" "$pid" $scheme $port handshake_c1 /hello 1 1
    measure "$name" "$round" "$pid" $scheme $port handshake_c16 /hello 16 1
  fi
  on "$SERVER" "kill $pid; for _ in \$(seq 1 40); do kill -0 $pid 2>/dev/null || break; sleep 0.25; done; kill -9 $pid 2>/dev/null || true"
}

CONFIGS=${CONFIGS:-"spring_nativepath spring_netty np_tls netty_jdk_tls netty_openssl_tls"}
REVERSED=$(reverse $CONFIGS | tr '\n' ' ')
for round in $(seq 1 "$ROUNDS"); do
  if [ $((round % 2)) = 1 ]; then order=$CONFIGS; else order=$REVERSED; fi
  for c in $order; do
    echo "== round $round: $c"
    run_config "$c" "$round"
  done
done
python3 "$ROOT/np-benchmarks/scripts/server_layers_table.py" "$OUT" | tee "$OUT_DIR/table.txt"
