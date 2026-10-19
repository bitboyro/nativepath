#!/bin/bash
# Capacity under an SLA, then past it. Virtual users (each one pauses between requests, like a person)
# of a mixed workload hit one server on a small fixed box; for each server variant the script finds the
# most users it sustains for the whole level with
#
#     p95 <= P95_MS, p99 <= P99_MS, errors (including 503s) < ERROR_PCT %, memory under MEMORY_MB, process alive
#
# by doubling the users until a level fails and then bisecting between the last pass and the first
# fail. Then the break phase: BREAK_FACTORS times that many users for BREAK_SECONDS (does it shed,
# fail, die?), and back at the passing level for RECOVER_SECONDS (does it come back?).
#
#   np-benchmarks/scripts/capacity.sh [--server H --client H --server-ip IP --cores N] <out-dir>
#
# The server gets SERVER_CORES cores (pinned) and a JVM heap of half of MEMORY_MB; the memory limit is
# checked against the process's resident size, not enforced by the kernel. NativePath servers get
# MAX_CONNECTIONS (their default of 10,000 would otherwise be the limit being measured); Tomcat and
# Netty keep their own defaults.
#
# Settings: NP_CAPACITY_VARIANTS, _SECONDS, _START_USERS, _MAX_USERS, _THINK, _RAMP_S, _P95_MS, _P99_MS,
# _ERROR_PCT, _SERVER_CORES, _MEMORY_MB, _MAX_BISECT, _BREAK, _BREAK_FACTORS, _BREAK_SECONDS,
# _RECOVER_SECONDS, _MAX_CONNECTIONS, _MIX.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
. "$ROOT/np-benchmarks/remote/hosts.sh"
parse_host_options "$@"
OUT_DIR=$(mkdir -p "${ARGS[0]:?out-dir}" && cd "${ARGS[0]}" && pwd)
VARIANTS=${NP_CAPACITY_VARIANTS:-engine_java raw_netty raw_tomcat spring_nativepath spring_netty}
SECONDS_PER_LEVEL=${NP_CAPACITY_SECONDS:-300}
START_USERS=${NP_CAPACITY_START_USERS:-500}; MAX_USERS=${NP_CAPACITY_MAX_USERS:-30000}
THINK=${NP_CAPACITY_THINK:-3000-10000}; RAMP_S=${NP_CAPACITY_RAMP_S:-30}
P95_MS=${NP_CAPACITY_P95_MS:-500}; P99_MS=${NP_CAPACITY_P99_MS:-1000}; ERROR_PCT=${NP_CAPACITY_ERROR_PCT:-1}
SERVER_CORES=${NP_CAPACITY_SERVER_CORES:-1}; MEMORY_MB=${NP_CAPACITY_MEMORY_MB:-2048}
MAX_BISECT=${NP_CAPACITY_MAX_BISECT:-4}
BREAK=${NP_CAPACITY_BREAK:-1}; BREAK_FACTORS=${NP_CAPACITY_BREAK_FACTORS:-1.5 2}
BREAK_SECONDS=${NP_CAPACITY_BREAK_SECONDS:-60}; RECOVER_SECONDS=${NP_CAPACITY_RECOVER_SECONDS:-60}
MAXC=${NP_CAPACITY_MAX_CONNECTIONS:-50000}
MIX=${NP_CAPACITY_MIX:-GET /hello 50; GET /payload?size=4096 30; POST /json @np-benchmarks/scripts/order.json 20}
PORT=18790
HEAP=$((MEMORY_MB / 2))
JVM="java --enable-native-access=ALL-UNNAMED -Xms${HEAP}m -Xmx${HEAP}m -cp \$cp ro.bitboy.nativepath.benchmarks"

stop_on_exit
echo "== build" >&2
prepare_server >&2
prepare_client >&2
describe_hosts | tee "$OUT_DIR/machines.txt"

start_variant() { # variant -> pid
  local env="" cmd saved=$CORES pid
  case $1 in
    engine_java)      cmd="$JVM.RawServerBench $PORT"; env="NATIVEPATH_SERVER_MAX_CONNECTIONS=$MAXC" ;;
    raw_netty)        cmd="$JVM.RawNettyServer $PORT" ;;
    raw_tomcat)       cmd="$JVM.RawTomcatServer $PORT" ;;
    spring_nativepath) cmd="$JVM.ServerBenchApp nativepath $PORT"; env="NATIVEPATH_SERVER_MAX_CONNECTIONS=$MAXC" ;;
    spring_netty)     cmd="$JVM.ServerBenchApp netty $PORT" ;;
    *) echo "unknown variant $1" >&2; exit 1 ;;
  esac
  CORES=$SERVER_CORES
  pid=$(start_server head "capacity-$1.log" "$env" "$cmd")
  CORES=$saved
  echo "$pid"
}

alive() { on "$SERVER" "kill -0 $1 2>/dev/null"; }
rss_mb() { on "$SERVER" "ps -o rss= -p $1 2>/dev/null | awk '{printf \"%d\", \$1/1024}'"; }

# One level: ramps users in, holds them for $seconds, judges. Sets LV_PASS (1/0) and LV_REASON.
level() { # variant stage users seconds pid
  local v=$1 stage=$2 users=$3 secs=$4 pid=$5 started cpu0 cpu1 peak=0 r out="$OUT_DIR/.level.json" lg_pid
  started=$(np_ms)
  cpu0=$(server_cpu "$pid")
  : > "$OUT_DIR/.rss"
  (
    NP_TAG="\"exp\":\"capacity\",\"config\":\"$v\",\"stage\":\"$stage\",\"users\":$users" \
    loadgen "LOADGEN_THINK=$THINK" "LOADGEN_RAMP_S=$RAMP_S" "LOADGEN_WARMUP_S=$((RAMP_S + 3))" "LOADGEN_EXPECT_STATUS=200" \
      "LOADGEN_MIX='$MIX'" "http://$SERVER_IP:$PORT/" "$users" "$secs" > "$out" || true
  ) &
  lg_pid=$!
  while kill -0 $lg_pid 2>/dev/null; do
    r=$(rss_mb "$pid" || echo 0); r=${r:-0}
    [ "$r" -gt "$peak" ] && peak=$r
    sleep 4
  done
  wait $lg_pid || true
  cpu1=$(server_cpu "$pid" || echo 0)
  local up=1; alive "$pid" || up=0
  local judged
  judged=$(python3 - "$out" "$v" "$stage" "$users" "$secs" "$started" "$peak" "$up" "$(minus "$cpu1" "$cpu0")" <<PY
import json, sys
path, v, stage, users, secs, started, rss, up, cpu = sys.argv[1:10]
users, secs, rss, up = int(users), int(secs), int(rss), int(up)
try:
    res = json.loads(open(path).read().strip().splitlines()[-1])
except Exception:
    res = None
reason = []
if res is None: reason.append("no result")
else:
    total = res["requests"] + res["errors"] + res.get("shed", 0)
    err_pct = 100.0 * (res["errors"] + res.get("shed", 0)) / total if total else 100.0
    if res["p95_us"] > $P95_MS * 1000: reason.append("p95 %.0f ms > $P95_MS" % (res["p95_us"] / 1000))
    if res["p99_us"] > $P99_MS * 1000: reason.append("p99 %.0f ms > $P99_MS" % (res["p99_us"] / 1000))
    if err_pct >= $ERROR_PCT: reason.append("%.1f%% errors" % err_pct)
if rss > $MEMORY_MB: reason.append("memory %d MB > $MEMORY_MB" % rss)
if not up: reason.append("process died")
m = {"exp": "capacity", "config": v, "stage": stage, "users": users, "seconds": secs, "started": int(started),
     "pass": not reason, "reason": "; ".join(reason), "rss_mb": rss, "server_cpu_s": float(cpu or 0),
     "error_pct": None if res is None else round(err_pct, 2),
     "sla": {"p95_ms": $P95_MS, "p99_ms": $P99_MS, "error_pct": $ERROR_PCT, "memory_mb": $MEMORY_MB},
     "result": res}
print(json.dumps(m))
PY
)
  echo "$judged" | tee -a "$OUT_DIR/capacity.jsonl"
  np_measurement "$judged"
  case $judged in *'"pass": true'*) LV_PASS=1; LV_REASON="" ;; *) LV_PASS=0; LV_REASON=$(echo "$judged" | python3 -c 'import json,sys; print(json.load(sys.stdin)["reason"])') ;; esac
}

failed=0
for v in $VARIANTS; do
  echo "== $v"
  np_event variant "\"exp\":\"capacity\",\"config\":\"$v\",\"state\":\"start\""
  require_server_port $PORT || exit 1
  server_quiet
  pid=$(start_variant "$v")
  RUNNING_PID=$pid
  wait_up $PORT || { echo "$v did not start" >&2; failed=1; np_event variant "\"exp\":\"capacity\",\"config\":\"$v\",\"state\":\"end\",\"max_users\":0,\"started\":false"; continue; }
  loadgen "http://$SERVER_IP:$PORT/hello" 32 5 > /dev/null   # warm-up (JIT)

  # Search: double until a level fails, then bisect.
  last_pass=0; first_fail=0; users=$START_USERS
  while :; do
    level "$v" search "$users" "$SECONDS_PER_LEVEL" "$pid"
    if [ "$LV_PASS" = 1 ]; then
      last_pass=$users
      [ "$users" -ge "$MAX_USERS" ] && break
      users=$((users * 2)); [ "$users" -gt "$MAX_USERS" ] && users=$MAX_USERS
    else
      first_fail=$users; echo "   $users users failed: $LV_REASON"
      break
    fi
    alive "$pid" || break
  done
  steps=0
  while [ "$first_fail" -gt 0 ] && alive "$pid" && [ "$steps" -lt "$MAX_BISECT" ] \
        && [ $((first_fail - last_pass)) -gt $(( last_pass / 10 > 50 ? last_pass / 10 : 50 )) ]; do
    users=$(( (last_pass + first_fail) / 2 ))
    level "$v" search "$users" "$SECONDS_PER_LEVEL" "$pid"
    if [ "$LV_PASS" = 1 ]; then last_pass=$users; else first_fail=$users; fi
    steps=$((steps + 1))
  done
  echo "   $v sustains $last_pass users"

  recovered=null
  if [ "$BREAK" = 1 ] && [ "$last_pass" -gt 0 ] && alive "$pid"; then
    for f in $BREAK_FACTORS; do
      alive "$pid" || break
      level "$v" overload "$(python3 -c "print(int($last_pass * $f))")" "$BREAK_SECONDS" "$pid"
    done
    if alive "$pid"; then
      level "$v" recover "$last_pass" "$RECOVER_SECONDS" "$pid"
      [ "$LV_PASS" = 1 ] && recovered=true || recovered=false
    else
      recovered=false
    fi
  fi
  np_event variant "\"exp\":\"capacity\",\"config\":\"$v\",\"state\":\"end\",\"max_users\":$last_pass,\"recovered\":$recovered,\"started\":true"
  stop_server "$pid"
  [ "$last_pass" -gt 0 ] || failed=1
done
exit $failed
