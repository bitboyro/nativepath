"""Prints server_ramp.sh results: per configuration, the closed-loop peak, every open-loop step
(offered and served rate, latency percentiles counted from when requests were due, shed and
failed requests, server CPU per request) and the knee.

Usage: python3 server_ramp_table.py <out-dir>/ramp.jsonl [slo_ms=10]
"""
import json
import sys
from collections import defaultdict

ORDER = ["engine", "spring_nativepath", "spring_nativepath_tuned", "spring_nativepath_shed", "spring_netty"]
SLO_MS = float(sys.argv[2]) if len(sys.argv) > 2 else 10.0
rows = [json.loads(line) for line in open(sys.argv[1])]
groups = defaultdict(list)
for r in rows:
    groups[r["config"]].append(r)


def cpu_us(r):
    n = r["result"]["requests"] + r["result"].get("shed", 0)
    return r["server_cpu_s"] * 1e6 / n if n else 0.0


knees = {}
for config in sorted(groups, key=lambda c: ORDER.index(c) if c in ORDER else len(ORDER)):
    runs = groups[config]
    closed = [r for r in runs if r["phase"] == "closed"]
    steps = sorted((r for r in runs if r["phase"] == "open"), key=lambda r: r["fraction"])
    peak = closed[0]["result"]["rps"] if closed else 0
    print(f"\n{config}: closed-loop peak {peak:,} req/s"
          + (f" (p99 {closed[0]['result']['p99_us'] / 1000:.1f} ms, CPU {cpu_us(closed[0]):.1f} us/req)" if closed else ""))
    print(f"  {'offered':>9} {'served':>9} {'served %':>8} {'p50 ms':>7} {'p99 ms':>7} {'p99.9 ms':>8} {'shed':>7} {'errors':>6} {'CPU us/req':>10}")
    knee = None
    for r in steps:
        res = r["result"]
        offered = r["rate"]
        served = res["rps"]
        pct = 100 * served / offered if offered else 0
        p99 = res["p99_us"] / 1000
        ok = pct >= 95 and p99 <= SLO_MS and res["errors"] == 0
        if ok:
            knee = offered
        print(f"  {offered:>9,} {served:>9,} {pct:>7.0f}% {res['p50_us'] / 1000:>7.2f} {p99:>7.2f} {res['p999_us'] / 1000:>8.2f}"
              f" {res.get('shed', 0):>7,} {res['errors']:>6} {cpu_us(r):>10.1f}" + ("" if ok else "  over"))
    knees[config] = knee
    print(f"  knee: {knee:,} req/s" if knee else "  knee: below the lowest step")

print(f"\nknee: the highest offered rate served at >= 95 % with p99 <= {SLO_MS:g} ms and no errors.")
print("Open loop: latency counts from when each request was due, so a server that falls behind shows")
print("its backlog. shed: requests answered 503 by the queue limit (they count in neither served nor errors).")
for config, knee in knees.items():
    print(f"  {config:22} {knee:>9,}" if knee else f"  {config:22} {'-':>9}")
