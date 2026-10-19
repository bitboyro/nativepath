"""Prints server_layers.sh results: per configuration and scenario, both rounds and their mean,
server CPU and heap allocation per request, and where engine time goes.

Usage: python3 server_layers_table.py <out-dir>/layers.jsonl   (or tls.jsonl from server_tls.sh,
tomcat.jsonl from server_tomcat.sh, ab.jsonl from ab_lanes.sh)
"""
import json
import sys
from collections import defaultdict

ORDER = ["hyper_sysalloc", "hyper_zeroizing", "engine", "engine_java", "spring_nativepath", "spring_netty",
         "np_tls", "netty_jdk_tls", "netty_openssl_tls",
         "mvc_tomcat", "mvc_nativepath", "mvc_nativepath_lane", "webflux_nativepath", "webflux_netty",
         "mvc_tomcat_tls", "mvc_nativepath_tls", "webflux_nativepath_tls",
         "engine@base", "engine_batch4", "engine_spin1", "engine_batch4_spin1", "engine_work20",
         "engine_work20_steal", "spring@base", "spring", "spring_batch4_spin1"]
rows = [json.loads(line) for line in open(sys.argv[1])]
groups = defaultdict(list)
for r in rows:
    groups[(r["config"], r["scenario"])].append(r)

def per_request(runs):
    """Heap bytes and engine microseconds per request, from /__stats before and after each run."""
    alloc = reqs = 0
    engine = [0] * 6
    have_alloc = have_engine = False
    for r in runs:
        b, a = r.get("stats_before"), r.get("stats_after")
        if not b or not a:
            continue
        if a.get("alloc_bytes"):
            alloc += a["alloc_bytes"] - b["alloc_bytes"]
            reqs += r["result"]["requests"]
            have_alloc = True
        if a.get("engine") and b.get("engine"):
            for i in range(6):
                engine[i] += a["engine"][i] - b["engine"][i]
            have_engine = True
    heap = f"{alloc / reqs:,.0f}" if have_alloc and reqs else "-"
    if have_engine and engine[0]:
        parts = [f"{engine[i] / engine[0] / 1000:.1f}" for i in range(1, 6)]
    else:
        parts = ["-"] * 5
    return heap, parts


print(f"{'config':20} {'scenario':15} {'rounds':>6} {'req/s min':>9} {'max':>8} {'mean':>8} {'spread':>6} {'p50 us':>7} {'p99.9 us':>9} {'CPU us/req':>10} {'err':>4}"
      f" {'heap B/req':>10} {'read':>6} {'queue':>6} {'handler':>7} {'respond':>7} {'reply':>6}")
for (config, scenario), runs in sorted(groups.items(), key=lambda kv: (ORDER.index(kv[0][0]) if kv[0][0] in ORDER else len(ORDER), kv[0])):
    runs.sort(key=lambda r: r["round"])
    rps = [r["result"]["rps"] for r in runs]
    cpu = sum(r["server_cpu_s"] for r in runs) * 1e6 / max(1, sum(r["result"]["requests"] for r in runs))
    p50 = sum(r["result"]["p50_us"] for r in runs) / len(runs)
    p999 = sum(r["result"]["p999_us"] for r in runs) / len(runs)
    errors = sum(r["result"]["errors"] for r in runs)
    mean = sum(rps) / len(rps)
    spread = f"{100 * (max(rps) - min(rps)) / mean:.0f}%" if mean else "-"
    heap, eng = per_request(runs)
    print(f"{config:20} {scenario:15} {len(rps):>6} {min(rps):>9} {max(rps):>8} {mean:>8.0f} {spread:>6} {p50:>7.0f} {p999:>9.0f} {cpu:>10.1f} {errors:>4}"
          f" {heap:>10} {eng[0]:>6} {eng[1]:>6} {eng[2]:>7} {eng[3]:>7} {eng[4]:>6}")
print("\nspread: (max - min) / mean of requests per second across rounds; differences between servers")
print("smaller than the spread are not evidence.")
print("heap B/req: JVM heap allocated per request (all threads). Engine columns: microseconds per request")
print("inside the engine (read: body and headers; queue: waiting for the worker; handler: Java side;")
print("respond: header check and body copy; reply: answered until picked up for writing).")
