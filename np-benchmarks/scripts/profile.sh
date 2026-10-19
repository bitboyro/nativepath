#!/usr/bin/env bash
# Where a running server's CPU goes: perf samples one process for a while and prints a flat profile
# and the same grouped into categories (syscalls, kernel network, TLS and crypto, HTTP parsing,
# async runtime, allocation, JVM).
#
#   profile.sh <pid> [seconds=20] [out-dir=.]       with the load already running
#
# Linux only, needs `perf`, and permission to sample: root, or a container started with
# --cap-add SYS_ADMIN (or PERFMON) and kernel.perf_event_paranoid <= 1. For a JVM, run it with the
# JDK's `jcmd` on the path: the JIT-compiled Java frames get names through the perf map.
# Writes <out-dir>/profile-<pid>.json and, with NP_EVENTS set, emits it as a "profile" event.
set -euo pipefail
PID=${1:?usage: profile.sh <pid> [seconds] [out-dir]}
SECS=${2:-20}
OUT=${3:-.}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
. "$HERE/lib.sh"
command -v perf >/dev/null || { echo "profile.sh: perf is not installed (dnf/apt install perf)" >&2; exit 1; }
[ "$OS" = Linux ] || { echo "profile.sh: Linux only" >&2; exit 1; }
mkdir -p "$OUT"

if grep -q '^java$\|libjvm' "/proc/$PID/comm" "/proc/$PID/maps" 2>/dev/null && command -v jcmd >/dev/null; then
  jcmd "$PID" Compiler.perfmap > /dev/null 2>&1 || true     # writes /tmp/perf-<pid>.map
fi
DATA=$OUT/perf-$PID.data
perf record -q -F 999 -g -p "$PID" -o "$DATA" -- sleep "$SECS" > /dev/null 2>&1
[ -f "/proc/$PID/comm" ] && command -v jcmd >/dev/null && jcmd "$PID" Compiler.perfmap > /dev/null 2>&1 || true
perf report -i "$DATA" --no-children --sort sym --stdio -F overhead,sym 2>/dev/null > "$OUT/perf-$PID.txt" || true
python3 "$HERE/np_profile.py" "$OUT/perf-$PID.txt" "$PID" "$SECS" > "$OUT/profile-$PID.json"
cat "$OUT/profile-$PID.json"
if [ -n "${NP_EVENTS:-}" ]; then
  printf 'NPEV {"t":%s,"type":"profile","exp":"%s","config":"%s","profile":%s}\n' \
    "$(np_ms)" "${NP_PROFILE_EXP:-profile}" "${NP_PROFILE_CONFIG:-$PID}" "$(tr -d '\n' < "$OUT/profile-$PID.json")" >&2
fi
