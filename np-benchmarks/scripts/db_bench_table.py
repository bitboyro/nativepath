#!/usr/bin/env python3
"""Table of DbDriverBenchmark results: pgjdbc vs NativePath per scenario and TLS setting.

Usage: db_bench_table.py <db_driver_bench.json> [more.json ...]
Later files override earlier ones for the same benchmark, driver, TLS setting and mode (for reruns).
"""
import json
import sys


def load(paths):
    rows = {}
    for path in paths:
        for r in json.load(open(path)):
            p = r["params"]
            pm = r["primaryMetric"]
            pc = pm.get("scorePercentiles", {})
            key = (r["benchmark"].split(".")[-1], p["tls"], r["mode"], p["driver"])
            rows[key] = {
                "score": pm["score"],
                "error": pm["scoreError"],
                "unit": pm["scoreUnit"].split("/")[0],
                "alloc": r["secondaryMetrics"].get("gc.alloc.rate.norm", {}).get("score"),
                "p50": pc.get("50.0"),
                "p99": pc.get("99.0"),
            }
    return rows


def kb(v):
    return "-" if v is None else f"{v / 1024:.1f}K"


def main(paths):
    rows = load(paths)
    cases = sorted({k[:3] for k in rows})
    print(f"{'benchmark':16} {'tls':5} {'pgjdbc':>18} {'nativepath':>18} {'np/pg':>6} {'heap/op pg':>11} {'heap/op np':>11}")
    for b, tls, mode in cases:
        if mode != "avgt":
            continue
        pg, np = rows.get((b, tls, mode, "pgjdbc")), rows.get((b, tls, mode, "nativepath"))
        if not pg or not np:
            print(f"{b:16} {tls:5} missing: {'pgjdbc' if not pg else 'nativepath'}")
            continue
        cell = lambda x: f"{x['score']:9.1f}±{x['error']:<5.1f}{x['unit']:>3}"
        print(f"{b:16} {tls:5} {cell(pg)} {cell(np)} {np['score'] / pg['score']:6.2f} {kb(pg['alloc']):>11} {kb(np['alloc']):>11}")
    sampled = [c for c in cases if c[2] == "sample"]
    if sampled:
        print(f"\n{'percentiles':16} {'tls':5} {'p50 pgjdbc':>11} {'p50 np':>9} {'p99 pgjdbc':>11} {'p99 np':>9}   (same unit as above)")
        for b, tls, mode in sampled:
            pg, np = rows.get((b, tls, mode, "pgjdbc")), rows.get((b, tls, mode, "nativepath"))
            if pg and np:
                print(f"{b:16} {tls:5} {pg['p50']:11.1f} {np['p50']:9.1f} {pg['p99']:11.1f} {np['p99']:9.1f}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
