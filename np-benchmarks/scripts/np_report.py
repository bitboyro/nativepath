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


GEN_BOUND_PCT = 85  # the load generator used this much of its machine: the run measured the generator


def bucket_lo_ns(i):
    """Lower edge of an engine phase-histogram bucket (four per power of two, see engineHistograms())."""
    return float(i) if i < 4 else float((4 + i % 4) << (i // 4 - 1))


def phase_tails(runs):
    """Per engine phase, p50/p99/p99.9 in microseconds of what the runs added (after minus before)."""
    delta = {}
    for r in runs:
        b, a = (r.get("stats_before") or {}).get("phase_hist"), (r.get("stats_after") or {}).get("phase_hist")
        if not (b and a):
            continue
        for phase, pairs in a.items():
            d = delta.setdefault(phase, defaultdict(int))
            for i, c in pairs:
                d[i] += c
            for i, c in b.get(phase, []):
                d[i] -= c
    out = {}
    for phase, d in delta.items():
        items = sorted((i, c) for i, c in d.items() if c > 0)
        total = sum(c for _, c in items)
        if not total:
            continue

        def pct(q):
            want, seen = total * q, 0
            for i, c in items:
                seen += c
                if seen >= want:
                    return round(bucket_lo_ns(i) * 1.1 / 1000, 1)  # middle of the bucket
            return None
        out[phase] = {"n": total, "p50_us": pct(0.5), "p99_us": pct(0.99), "p999_us": pct(0.999)}
    return out


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

    def throughput(self, exp="throughput"):
        groups = defaultdict(list)
        for e in self.by[exp]:
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
                "gen_util_pct": mean([r["result"].get("gen_util_pct") for r in runs]),
                "gen_cpu_us_req": mean([r["result"].get("gen_cpu_us_per_req") for r in runs]),
                "gen_engine": next((r["result"].get("engine") for r in runs if r["result"].get("engine")), None),
                "gen_bound": any((r["result"].get("gen_util_pct") or 0) >= GEN_BOUND_PCT for r in runs),
                "phases": phase_tails(runs),
                "errors": errors, "shed": sum(r["result"].get("shed", 0) for r in runs)})
        series = {}
        for e in self.by[exp]:
            if e.get("type") == "tick" and e.get("round", 1) == 1 and e.get("phase") != "drain":
                k = f'{e["config"]}|{e["scenario"]}'
                s = series.setdefault(k, {"x": [], "rps": [], "p99_us": []})
                s["x"].append(len(s["x"]) + 1); s["rps"].append(e["rps"]); s["p99_us"].append(e["p99_us"])
        series = {k: {"x": v["x"][-120:], "rps": v["rps"][-120:], "p99_us": v["p99_us"][-120:]} for k, v in series.items()}
        best = max(rows, key=lambda r: r["rps_mean"], default=None)
        bound = sorted({r["config"] for r in rows if r["gen_bound"]})
        profiles = {e.get("config"): e.get("profile") for e in self.by[exp] if e.get("type") == "profile"}
        return {"verdict": "fail" if bad else ("pass" if rows else "none"),
                "warnings": ([f'load generator at {GEN_BOUND_PCT}%+ of its machine for {", ".join(bound)}: those numbers are the generator\'s limit, not the server\'s'] if bound else []),
                "headline": (f'{len(rows)} measurements; best {best["config"]} {best["scenario"]} at {best["rps_mean"]:,.0f} req/s' if best else "no measurements"),
                "table": rows, "series": series, "profiles": profiles}

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
                              "cpu_us_req": r["server_cpu_s"] * 1e6 / n if n else None, "ok": ok,
                              "gen_util_pct": res.get("gen_util_pct")})
            knee = max((s["offered"] for s in steps if s["ok"]), default=None)
            peak = closed[0]["result"] if closed else {}
            configs[config] = {"peak_rps": peak.get("rps"), "steps": steps, "knee": knee,
                               "gen_util_pct": peak.get("gen_util_pct"),
                               "gen_bound": (peak.get("gen_util_pct") or 0) >= GEN_BOUND_PCT or any((s["gen_util_pct"] or 0) >= GEN_BOUND_PCT for s in steps)}
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

    # ---- capacity and break ----------------------------------------------------------------------

    def capacity(self):
        st = self.settings.get("capacity", {})
        planned = str(st.get("variants", "")).split()
        levels, info = defaultdict(list), {}
        for e in self.by["capacity"]:
            if e.get("type") == "measurement":
                r = e.get("result") or {}
                started, ended = e.get("started"), e.get("t")
                wall = max(1.0, ((ended or 0) - (started or 0)) / 1000)
                levels[e["config"]].append({
                    "stage": e.get("stage"), "users": e.get("users"), "started": started, "ended": ended,
                    "pass": bool(e.get("pass")), "reason": e.get("reason") or "",
                    "p50_us": r.get("p50_us"), "p95_us": r.get("p95_us"), "p99_us": r.get("p99_us"),
                    "err_pct": e.get("error_pct"), "rps": r.get("rps"), "rss_mb": e.get("rss_mb"),
                    "cpu_pct": 100 * (e.get("server_cpu_s") or 0) / wall})
            elif e.get("type") == "variant":
                i = info.setdefault(e["config"], {})
                if e.get("state") == "start":
                    i["started"] = e["t"]
                else:
                    i.update(ended=e["t"], max_users=e.get("max_users"), recovered=e.get("recovered"), began=e.get("started", True))
        order = planned + [c for c in info if c not in planned]
        configs, best = {}, None
        for c in order:
            ls = sorted(levels.get(c, []), key=lambda l: l["started"] or 0)
            i = info.get(c, {})
            passed = [l for l in ls if l["stage"] == "search" and l["pass"]]
            top = max(passed, key=lambda l: l["users"], default=None)
            over = [l for l in ls if l["stage"] == "overload"]
            rec = next((l for l in ls if l["stage"] == "recover"), None)
            fail = next((l for l in sorted(ls, key=lambda l: l["users"]) if l["stage"] == "search" and not l["pass"]), None)
            configs[c] = {
                "state": "done" if "ended" in i else ("running" if "started" in i else "queued"),
                "started": i.get("started"), "ended": i.get("ended"),
                "max_users": top["users"] if top else (0 if "ended" in i else None),
                "at_max": top, "first_fail": fail,
                "recovered": (rec["pass"] if rec else (False if over and i.get("recovered") is False else None)),
                "overload": [{k: l[k] for k in ("users", "pass", "reason", "err_pct", "p99_us", "rps", "rss_mb")} for l in over],
                "levels": ls}
            if top and (best is None or top["users"] > best[1]):
                best = (c, top["users"])
        done = [c for c in order if configs[c]["state"] == "done"]
        times = [l["started"] for v in configs.values() for l in v["levels"] if l["started"]] + [v["started"] for v in configs.values() if v["started"]]
        ends = [l["ended"] for v in configs.values() for l in v["levels"] if l["ended"]]
        return {"verdict": "info" if done else "none",
                "headline": ("; ".join(f'{c} {configs[c]["max_users"]:,} users' for c in done if configs[c]["max_users"] is not None) or "no variant finished yet"),
                "best": {"config": best[0], "users": best[1]} if best else None,
                "sla": {"p95_ms": float(st.get("p95_ms", 500)), "p99_ms": float(st.get("p99_ms", 1000)), "error_pct": float(st.get("error_pct", 1)),
                        "memory_mb": float(st.get("memory_mb", 2048)), "cores": int(st.get("server_cores", 1)), "think": st.get("think", "3000-10000"),
                        "seconds": int(st.get("seconds", 300))},
                "t0": min(times) if times else None, "t1": max(ends) if ends else None,
                "order": order, "configs": configs}

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
            elif exp == "client": body = self.throughput("client")
            elif exp == "capacity": body = self.capacity()
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
