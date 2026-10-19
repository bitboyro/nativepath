#!/usr/bin/env python3
"""Builds <run-dir>/results.json from <run-dir>/events.jsonl.

results.json is the finished summary the dashboard opens: per experiment a state, a verdict, a
headline, tables and downsampled series, plus the run's settings and machines. It can be built from a
run that is still going (np-bench refreshes it after every experiment) and from one that was
interrupted.

  np_report.py <run-dir>
"""
import json
import os
import sys
from collections import defaultdict

MAX_POINTS = 240


def read_events(path):
    out = []
    try:
        with open(path) as f:
            for line in f:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass  # a line cut off by a crash, or a half-written one
    except OSError:
        pass
    return out


def downsample(xs, ys, n=MAX_POINTS):
    """At most n points: the mean of each bucket, with the first and last point kept as they are."""
    if len(ys) <= n:
        return {"x": xs, "y": ys}
    size = len(ys) / n
    ox, oy = [], []
    for i in range(n):
        lo, hi = int(i * size), max(int((i + 1) * size), int(i * size) + 1)
        seg = [v for v in ys[lo:hi] if v is not None]
        ox.append(xs[lo])
        oy.append(sum(seg) / len(seg) if seg else None)
    return {"x": ox, "y": oy}


def mean(v):
    v = [x for x in v if x is not None]
    return sum(v) / len(v) if v else None


def slope_per_hour(ts_ms, ys):
    """Least-squares slope of ys against time, per hour, over the samples given."""
    pts = [(t / 3.6e6, y) for t, y in zip(ts_ms, ys) if y is not None]
    n = len(pts)
    if n < 3:
        return None
    sx = sum(p[0] for p in pts); sy = sum(p[1] for p in pts)
    sxy = sum(p[0] * p[1] for p in pts); sxx = sum(p[0] * p[0] for p in pts)
    d = n * sxx - sx * sx
    return (n * sxy - sx * sy) / d if d else None


class Report:
    def __init__(self, events):
        self.ev = events
        self.run = next((e for e in events if e.get("type") == "run"), {})
        self.settings = self.run.get("settings", {})
        self.by = defaultdict(list)
        for e in events:
            self.by[e.get("exp") or "_"].append(e)

    def exp_state(self, exp):
        st = {"state": "pending", "rc": None, "started": None, "ended": None}
        for e in self.ev:
            if e.get("type") == "exp" and e.get("exp") == exp:
                if e.get("state") == "start":
                    st.update(state="running", started=e["t"])
                elif e.get("state") == "end":
                    st.update(state="done" if e.get("rc") == 0 else "failed", rc=e.get("rc"), ended=e["t"])
        return st

    # ---- throughput -------------------------------------------------------------------------

    def throughput(self):
        groups = defaultdict(list)
        for e in self.by["throughput"]:
            if e.get("type") == "measurement":
                groups[(e["config"], e["scenario"])].append(e)
        rows, bad = [], 0
        for (config, scenario), runs in groups.items():
            runs.sort(key=lambda r: r.get("round", 0))
            rps = [r["result"]["rps"] for r in runs]
            reqs = sum(r["result"]["requests"] for r in runs)
            m = mean(rps)
            alloc = n = 0
            for r in runs:
                b, a = r.get("stats_before"), r.get("stats_after")
                if b and a and a.get("alloc_bytes"):
                    alloc += a["alloc_bytes"] - b["alloc_bytes"]; n += r["result"]["requests"]
            errors = sum(r["result"]["errors"] for r in runs)
            if reqs == 0 or errors:
                bad += 1
            rows.append({
                "config": config, "scenario": scenario, "rounds": len(runs),
                "rps_mean": m, "rps_min": min(rps), "rps_max": max(rps),
                "spread_pct": 100 * (max(rps) - min(rps)) / m if m else None,
                "p50_us": mean([r["result"]["p50_us"] for r in runs]),
                "p99_us": mean([r["result"]["p99_us"] for r in runs]),
                "p999_us": mean([r["result"]["p999_us"] for r in runs]),
                "cpu_us_req": sum(r["server_cpu_s"] for r in runs) * 1e6 / max(1, reqs),
                "heap_b_req": alloc / n if n else None,
                "mb_s": mean([r["result"].get("mb_per_s") for r in runs]),
                "errors": errors, "shed": sum(r["result"].get("shed", 0) for r in runs)})
        series = {}
        for e in self.by["throughput"]:
            if e.get("type") == "tick" and e.get("round", 1) == 1 and e.get("phase") != "drain":
                k = f'{e["config"]}|{e["scenario"]}'
                s = series.setdefault(k, {"x": [], "rps": [], "p99_us": []})
                s["x"].append(len(s["x"]) + 1); s["rps"].append(e["rps"]); s["p99_us"].append(e["p99_us"])
        series = {k: {"x": v["x"][-120:], "rps": v["rps"][-120:], "p99_us": v["p99_us"][-120:]} for k, v in series.items()}
        best = max(rows, key=lambda r: r["rps_mean"], default=None)
        return {"verdict": "fail" if bad else ("pass" if rows else "none"),
                "headline": (f'{len(rows)} measurements; best {best["config"]} {best["scenario"]} at {best["rps_mean"]:,.0f} req/s' if best else "no measurements"),
                "table": rows, "series": series}

    # ---- ramp ---------------------------------------------------------------------------------

    def ramp(self):
        slo = float(self.settings.get("ramp", {}).get("slo_ms", 10)) * 1000
        groups = defaultdict(list)
        for e in self.by["ramp"]:
            if e.get("type") == "measurement":
                groups[e["config"]].append(e)
        configs = {}
        for config, runs in groups.items():
            closed = [r for r in runs if r["phase"] == "closed"]
            steps = []
            for r in sorted((r for r in runs if r["phase"] == "open"), key=lambda r: r["fraction"]):
                res = r["result"]; offered = r["rate"]; served = res["rps"]
                n = res["requests"] + res.get("shed", 0)
                pct = 100 * served / offered if offered else 0
                ok = pct >= 95 and res["p99_us"] <= slo and res["errors"] == 0
                steps.append({"fraction": r["fraction"], "offered": offered, "served": served, "served_pct": pct,
                              "p50_us": res["p50_us"], "p99_us": res["p99_us"], "p999_us": res["p999_us"],
                              "shed": res.get("shed", 0), "errors": res["errors"],
                              "cpu_us_req": r["server_cpu_s"] * 1e6 / n if n else None, "ok": ok})
            knee = max((s["offered"] for s in steps if s["ok"]), default=None)
            configs[config] = {"peak_rps": closed[0]["result"]["rps"] if closed else None, "steps": steps, "knee": knee}
        series = {}
        for e in self.by["ramp"]:
            if e.get("type") == "tick" and e.get("phase") == "measure":
                s = series.setdefault(e["config"], {"x": [], "offered": [], "served": [], "p99_us": []})
                s["x"].append(len(s["x"]) + 1); s["offered"].append(e.get("target_rps") or None); s["served"].append(e["rps"]); s["p99_us"].append(e["p99_us"])
        series = {k: {kk: downsample(v["x"], v[kk])["y"] for kk in ("offered", "served", "p99_us")} | {"x": downsample(v["x"], v["x"])["x"]} for k, v in series.items()}
        knees = {c: v["knee"] for c, v in configs.items()}
        return {"verdict": "info" if configs else "none",
                "headline": ("; ".join(f'{c}: knee {k:,}/s' if k else f'{c}: below the lowest step' for c, k in knees.items()) or "no steps"),
                "slo_us": slo, "configs": configs, "series": series}

    # ---- scans (memory proofs, scan under load) ---------------------------------------------------

    def scans(self, exp):
        rows = [e for e in self.by[exp] if e.get("type") == "scan"]
        ok = all(e.get("ok") for e in rows) if rows else None
        series = {}
        ticks = [e for e in self.by[exp] if e.get("type") == "tick" and e.get("phase") == "measure"]
        if ticks:
            series = {"rps": [e["rps"] for e in ticks][-240:]}
        return {"verdict": "none" if ok is None else ("pass" if ok else "fail"),
                "headline": (f'{sum(1 for e in rows if e.get("ok"))} of {len(rows)} scans as expected' if rows else "no scans"),
                "scans": [{k: e.get(k) for k in ("t", "scenario", "suite", "label", "cycle", "expected", "found_token", "found_password", "found", "ok", "ms", "detail")} for e in rows],
                "series": series}

    # ---- correctness ---------------------------------------------------------------------------

    def correct(self):
        routes = {}
        for e in self.by["correct"]:
            if e.get("type") == "result":
                routes[(e.get("server", ""), e.get("route", ""))] = e
        rows = []
        for (server, route), e in routes.items():
            rows.append({"server": server, "route": route, "expect": e.get("expect"), "requests": e.get("requests"), "verified": e.get("verified"),
                         "mismatches": e.get("mismatches"), "errors": e.get("errors"), "rps": e.get("rps"), "p99_us": e.get("p99_us"),
                         "first_mismatch": e.get("first_mismatch")})
        bad = sum(1 for r in rows if r["mismatches"] or r["errors"])
        verified = sum(r["verified"] or 0 for r in rows)
        series = {}
        for e in self.by["correct"]:
            if e.get("type") == "tick":
                series.setdefault("verified", []).append(e.get("verified", 0))
        series = {k: v[-240:] for k, v in series.items()}
        return {"verdict": "none" if not rows else ("fail" if bad else "pass"),
                "headline": (f'{verified:,} responses verified, {sum(r["mismatches"] or 0 for r in rows)} mismatches' if rows else "no results"),
                "table": rows, "series": series}

    # ---- soak ----------------------------------------------------------------------------------

    def soak(self):
        s = [e for e in self.by["soak"] if e.get("type") == "sample" and e.get("role", "server") == "server"]
        if not s:
            return {"verdict": "none", "headline": "no samples"}
        t0 = s[0]["t"]
        mins = [round((e["t"] - t0) / 60000, 2) for e in s]
        fields = ["rss_mb", "heap_used_mb", "heap_committed_mb", "nonheap_mb", "other_mb", "cpu_pct", "threads", "fds", "gc_count", "gc_ms"]
        series = {f: downsample(mins, [e.get(f) for e in s]) for f in fields if any(e.get(f) is not None for e in s)}
        ticks = [e for e in self.by["soak"] if e.get("type") == "tick" and e.get("phase") == "measure"]
        if ticks:
            tt0 = ticks[0]["t"]
            tm = [round((e["t"] - tt0) / 60000, 2) for e in ticks]
            series["rps"] = downsample(tm, [e["rps"] for e in ticks])
            series["p99_us"] = downsample(tm, [e["p99_us"] for e in ticks])
        last_hour = [e for e in s if e["t"] >= s[-1]["t"] - 3600 * 1000]
        slope = slope_per_hour([e["t"] - t0 for e in last_hour], [e.get("rss_mb") for e in last_hour])
        limit = float(self.settings.get("soak", {}).get("leak_limit_mb_h", 8))
        # A memory trend needs time: warm-up and the collector's cycles dominate a short run.
        enough = (s[-1]["t"] - s[0]["t"]) >= 10 * 60 * 1000 and len(last_hour) >= 8
        scans = [e for e in self.by["soak"] if e.get("type") == "scan"]
        scans_ok = all(e.get("ok") for e in scans)
        errors = sum(e.get("errors", 0) for e in ticks)
        if not enough:
            verdict = "info"
        else:
            verdict = "pass" if (slope is not None and slope <= limit and scans_ok) else "fail"
        gc = [e.get("gc_ms") for e in s if e.get("gc_ms") is not None]
        return {"verdict": verdict,
                "headline": (f'{(s[-1]["t"] - t0) / 60000:.0f} min, resident memory {"+" if (slope or 0) >= 0 else "-"}{abs(slope or 0):.1f} MB/hour (limit {limit:g})' + ("" if enough else ", too short for a verdict")),
                "slope_mb_h": slope, "limit_mb_h": limit, "errors": errors,
                "rss_start_mb": s[0].get("rss_mb"), "rss_end_mb": s[-1].get("rss_mb"), "rss_max_mb": max((e.get("rss_mb") or 0) for e in s),
                "gc_ms_total": (gc[-1] - gc[0]) if len(gc) > 1 else None,
                "scans": [{k: e.get(k) for k in ("t", "scenario", "label", "found", "ok", "expected", "ms")} for e in scans],
                "series": series}

    # ---- database ------------------------------------------------------------------------------

    def db(self):
        res = {}
        for e in self.by["db"]:
            if e.get("type") == "result":
                res[(e["scenario"], e["driver"])] = e
        scenarios = sorted({k[0] for k in res})
        rows = []
        for sc in scenarios:
            row = {"scenario": sc}
            for d in ("pgjdbc", "nativepath"):
                e = res.get((sc, d))
                if e:
                    row[d] = {k: e.get(k) for k in ("ops_s", "p50_us", "p99_us", "errors", "rows_s", "threads", "pool")}
            if row.get("pgjdbc") and row.get("nativepath") and row["pgjdbc"]["ops_s"]:
                row["ratio"] = row["nativepath"]["ops_s"] / row["pgjdbc"]["ops_s"]
            rows.append(row)
        scans = [e for e in self.by["db"] if e.get("type") == "scan"]
        series = {}
        for e in self.by["db"]:
            if e.get("type") == "tick":
                series.setdefault(f'{e["driver"]}|{e["scenario"]}', []).append(e.get("ops_s"))
        series = {k: v[-120:] for k, v in series.items()}
        errors = sum((r.get(d) or {}).get("errors") or 0 for r in rows for d in ("pgjdbc", "nativepath"))
        scans_ok = all(e.get("ok") for e in scans)
        return {"verdict": "none" if not rows else ("fail" if errors or not scans_ok else "pass"),
                "headline": f'{len(rows)} scenarios' + (f', {errors} errors' if errors else ""),
                "table": rows, "scans": [{k: e.get(k) for k in ("scenario", "label", "found", "ok", "expected")} for e in scans], "series": series}

    def build(self):
        exps = {}
        order = self.run.get("experiments") or []
        for exp in order:
            st = self.exp_state(exp)
            body = {}
            if exp == "throughput": body = self.throughput()
            elif exp == "ramp": body = self.ramp()
            elif exp in ("memory", "scan"): body = self.scans(exp)
            elif exp == "correct": body = self.correct()
            elif exp == "soak": body = self.soak()
            elif exp == "db": body = self.db()
            if st["state"] == "failed" and body.get("verdict") in (None, "none", "pass", "info"):
                body["verdict"] = "fail"
            if exp == "preflight":
                body = {"verdict": "pass" if st["state"] == "done" else ("fail" if st["state"] == "failed" else "none"), "headline": "tools, library and hosts"}
            exps[exp] = {**st, **body}
        machine = next((e.get("text") for e in self.ev if e.get("type") == "machine"), None)
        ended = next((e for e in reversed(self.ev) if e.get("type") == "run_end"), None)
        verdicts = [v.get("verdict") for v in exps.values()]
        return {"version": 1, "run": {k: self.run.get(k) for k in ("id", "mode", "experiments", "settings", "hosts", "commit", "started")},
                "machine": machine, "finished": ended["t"] if ended else None,
                "ok": None if not ended else bool(ended.get("ok")) and "fail" not in verdicts,
                "experiments": exps}


def main():
    d = sys.argv[1]
    report = Report(read_events(os.path.join(d, "events.jsonl"))).build()
    tmp = os.path.join(d, "results.json.tmp")
    with open(tmp, "w") as f:
        json.dump(report, f)
    os.replace(tmp, os.path.join(d, "results.json"))


if __name__ == "__main__":
    main()
