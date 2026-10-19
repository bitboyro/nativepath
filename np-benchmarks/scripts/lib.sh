# Shared helpers for the benchmark scripts; works on macOS and Linux. Source it, do not run it.
#
# Settings read from the environment: QUIET_IDLE (default 85), QUIET_MAX_WAIT_S (default 600).

# The tree these scripts live in. A release bundle carries a .prebuilt marker, jars in lib/ and the
# native library and load generator under target/release: nothing is built, and neither Rust nor
# Maven needs to be installed.
NP_HOME=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
prebuilt() { [ -f "${1:-$NP_HOME}/.prebuilt" ]; }

QUIET_IDLE=${QUIET_IDLE:-85}
QUIET_MAX_WAIT_S=${QUIET_MAX_WAIT_S:-600}
OS=$(uname -s)

# Events for the dashboard: JSON lines on stderr prefixed NPEV, which np-bench collects into
# events.jsonl (also over ssh, which forwards stderr). Silent unless np-bench set NP_EVENTS.
np_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
np_event() { # type [json fields without braces]
  [ -n "${NP_EVENTS:-}" ] || return 0
  printf 'NPEV {"t":%s,"type":"%s"%s}\n' "$(np_ms)" "$1" "${2:+,$2}" >&2
}
np_measurement() { # one measurement as a JSON object
  [ -n "${NP_EVENTS:-}" ] || return 0
  printf 'NPEV {"t":%s,"type":"measurement",%s\n' "$(np_ms)" "${1#\{}" >&2
}
# The configurations named in NP_THROUGHPUT_CONFIGS among those given (all of them if it is empty).
np_filter_configs() {
  local want=${NP_THROUGHPUT_CONFIGS:-} c out=""
  [ -z "$want" ] && { echo "$@"; return; }
  for c in "$@"; do case " $want " in *" $c "*) out="$out$c " ;; esac; done
  echo "$out"
}

# Percent of CPU idle over a fresh one-second sample (not the load average, which lags by minutes).
idle_pct() {
  if [ "$OS" = Darwin ]; then
    top -l 2 -n 0 -s 1 | grep 'CPU usage' | tail -1 | sed -E 's/.* ([0-9.]+)% idle.*/\1/'
  else
    local a b
    a=$(head -1 /proc/stat); sleep 1; b=$(head -1 /proc/stat)
    # cpu user nice system idle iowait irq softirq steal ...: idle share of the ticks in between
    awk -v a="$a" -v b="$b" 'BEGIN {
      n = split(a, x, " "); split(b, y, " "); total = 0
      for (i = 2; i <= n; i++) total += y[i] - x[i]
      idle = (y[5] - x[5]) + (y[6] - x[6])
      # parentheses: a bare ">" in a printf argument list is output redirection in awk
      printf "%.2f", (total > 0 ? 100 * idle / total : 100) }'
  fi
}

# Waits until the CPU is at least QUIET_IDLE % idle; gives up (exit 1) after QUIET_MAX_WAIT_S.
wait_quiet() {
  local waited=0 idle
  while :; do
    idle=$(idle_pct)
    if awk -v i="$idle" -v q="$QUIET_IDLE" 'BEGIN { exit !(i >= q) }'; then
      echo "   machine ${idle}% idle"
      return 0
    fi
    if [ "$waited" -ge "$QUIET_MAX_WAIT_S" ]; then
      echo "ABORT: machine still busy (${idle}% idle) after ${QUIET_MAX_WAIT_S}s. Busiest processes:" >&2
      ps -eo pcpu,comm --sort=-pcpu 2>/dev/null | head -6 >&2 || ps -Ao pcpu,comm -r | head -6 >&2
      exit 1
    fi
    echo "   waiting for a quiet machine (${idle}% idle, need ${QUIET_IDLE}%)"
    sleep 10
    waited=$((waited + 10))
  done
}

# CPU seconds a process has used so far (all threads).
cpu_s() {
  if [ "$OS" = Linux ] && [ -r "/proc/$1/stat" ]; then
    # utime and stime (fields 14, 15) in clock ticks; the command name may contain spaces, so
    # count fields after the closing parenthesis.
    awk -v hz="$(getconf CLK_TCK)" '{ sub(/.*\) /, ""); printf "%.2f", ($12 + $13) / hz }' "/proc/$1/stat"
  else
    ps -o time= -p "$1" | awk -F: '{ if (NF == 3) print $1*3600 + $2*60 + $3; else print $1*60 + $2 }'
  fi
}

# a - b, for the CPU seconds above.
minus() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.2f", a - b }'; }

# Connections in TIME_WAIT (each holds a client port for a while after closing).
tw_count() {
  if [ "$OS" = Darwin ]; then
    netstat -an -p tcp | grep -c TIME_WAIT || true
  else
    ss -tan state time-wait 2>/dev/null | tail -n +2 | wc -l | tr -d ' '
  fi
}

# Waits for TIME_WAIT connections to drain below 2000 (up to 5 minutes).
wait_time_wait() {
  local n
  for _ in $(seq 1 60); do
    n=$(tw_count)
    [ "$n" -lt 2000 ] && return 0
    echo "   waiting for TIME_WAIT to drain ($n connections)"
    sleep 5
  done
}

# Prints its arguments in reverse order, one per line.
reverse() { printf '%s\n' "$@" | awk '{ a[NR] = $0 } END { for (i = NR; i > 0; i--) print a[i] }'; }

# Classpath for Netty's BoringSSL (netty-tcnative 2.0.70) on this platform, or nothing if its jars
# are not in ~/.m2 (fetch them with fetch_tcnative).
TCNATIVE_VERSION=2.0.70.Final
tcnative_classifier() {
  local arch
  arch=$(uname -m)
  case "$OS-$arch" in
    Darwin-arm64) echo osx-aarch_64 ;;
    Darwin-x86_64) echo osx-x86_64 ;;
    Linux-x86_64) echo linux-x86_64 ;;
    Linux-aarch64) echo linux-aarch_64 ;;
  esac
}
tcnative_cp() {
  if prebuilt; then ls "$NP_HOME"/lib/netty-tcnative-*.jar 2>/dev/null | paste -sd: -; return 0; fi
  local base=$HOME/.m2/repository/io/netty c
  c=$(tcnative_classifier)
  local jars="$base/netty-tcnative-classes/$TCNATIVE_VERSION/netty-tcnative-classes-$TCNATIVE_VERSION.jar"
  jars="$jars:$base/netty-tcnative-boringssl-static/$TCNATIVE_VERSION/netty-tcnative-boringssl-static-$TCNATIVE_VERSION.jar"
  jars="$jars:$base/netty-tcnative-boringssl-static/$TCNATIVE_VERSION/netty-tcnative-boringssl-static-$TCNATIVE_VERSION-$c.jar"
  local j
  for j in $(echo "$jars" | tr ':' ' '); do [ -f "$j" ] || return 0; done
  echo "$jars"
}
fetch_tcnative() {
  prebuilt && return 0
  mvn -q dependency:get -Dartifact=io.netty:netty-tcnative-classes:$TCNATIVE_VERSION
  mvn -q dependency:get -Dartifact=io.netty:netty-tcnative-boringssl-static:$TCNATIVE_VERSION
  mvn -q dependency:get -Dartifact=io.netty:netty-tcnative-boringssl-static:$TCNATIVE_VERSION:jar:"$(tcnative_classifier)"
}

# Classpath for the benchmark JVMs: this tree's compiled modules (never ~/.m2's nativepath jars,
# which another worktree may have installed) plus the third-party jars.
bench_classpath() { # repo root
  if prebuilt "$1"; then ls "$1"/lib/*.jar | grep -v netty-tcnative | paste -sd: -; return 0; fi
  local f third
  f=$(mktemp)
  # Through the reactor (-pl np-benchmarks -am), so our own modules resolve from this build and not
  # from ~/.m2, which on a fresh machine (a CI runner) has never had them installed. Into a file,
  # not /dev/stdout (that came back empty on GitHub's Linux runners); the last module built,
  # benchmarks, writes it last.
  # `package`, not `compile`: the classpath then holds jars, not target/classes. An IDE's Java
  # language server (VS Code's, say) recompiles the sources with the Eclipse compiler into
  # target/classes whenever it likes, and what it writes there can carry "Unresolved compilation
  # problem" errors for classes that Maven compiled fine; it does not touch jars.
  (cd "$1" && mvn -q -pl np-benchmarks -am -DskipTests package dependency:build-classpath -Dmdep.outputFile="$f") >&2
  third=$(tr ':' '\n' < "$f" | grep -v '/ro/bitboy/nativepath/' | paste -sd: -)
  rm -f "$f"
  if ! echo "$third" | grep -q 'spring-boot'; then
    echo "bench_classpath: Maven returned no third-party jars (spring-boot missing)" >&2
    return 1
  fi
  echo "$(ls "$1"/np-benchmarks/target/np-benchmarks-*.jar | head -1):$third"
}

# Runs a command pinned to cores 0..N-1 where taskset exists (Linux), unpinned elsewhere.
pinned() { # cores command...
  local cores=$1
  shift
  if command -v taskset >/dev/null 2>&1; then
    taskset -c "0-$((cores - 1))" "$@"
  else
    "$@"
  fi
}

# Like pinned, but replaces the current shell, so the caller's $! / PID is the command itself.
exec_pinned() { # cores command...
  local cores=$1
  shift
  if command -v taskset >/dev/null 2>&1; then
    exec taskset -c "0-$((cores - 1))" "$@"
  else
    exec "$@"
  fi
}

# Fails (with a message) if anything already accepts connections on the port. A leftover or
# unrelated server there (a Docker container, say) would otherwise answer the benchmark's requests.
require_free_port() { # port
  if (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; then
    echo "ABORT: something already listens on port $1:" >&2
    (lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null || ss -ltnp "sport = :$1" 2>/dev/null) >&2
    return 1
  fi
}
