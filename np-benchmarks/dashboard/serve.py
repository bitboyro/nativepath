#!/usr/bin/env python3
"""The np-bench dashboard server: one static page, a few JSON endpoints, and a way to start runs.

  np-bench serve [--port 8765] [--bind 127.0.0.1] [--results np-results]

  GET  /                          the dashboard page
  GET  /api/status                what is running, where the results are kept
  GET  /api/config                every setting of every experiment (bench.config.json) and its default
  GET  /api/runs                  the runs found in the results directory
  GET  /api/runs/<id>/events      new events since ?from=<byte offset>: {"events": [...], "next": offset}
  GET  /api/runs/<id>/results     results.json (built from the events when it is missing)
  GET  /api/runs/<id>/log/<exp>   what an experiment printed
  POST /api/run                   start a run: {"mode", "experiments", "settings", "hosts"}
  POST /api/stop                  stop the run this server started

Starting and stopping need the session token the server prints (the page gets it when it is opened from
this machine); everything else is read-only. By default the server listens on 127.0.0.1 only. Pass
--bind 0.0.0.0 to watch from another machine: those clients see everything and can start nothing.

Standard library only.
"""
import argparse, json, os, re, secrets, signal, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
ID_RE = re.compile(r"^[A-Za-z0-9._-]+$")
MODES = ("check", "run")
EXPERIMENTS = ("preflight", "memory", "throughput", "ramp", "scan", "correct", "soak", "db")
HOST_RE = re.compile(r"^[A-Za-z0-9._@:-]+$")


class State:
    def __init__(self, root, results, token, follow, trust_bind=False):
        self.trust_bind = trust_bind
        self.root = root
        self.results = os.path.abspath(results)
        self.token = token
        self.follow = follow
        self.proc = None
        self.run_id = None
        self.lock = threading.Lock()
        os.makedirs(self.results, exist_ok=True)
        sys.path.insert(0, os.path.join(root, "np-benchmarks", "scripts"))

    def running(self):
        return self.proc is not None and self.proc.poll() is None

    def run_dir(self, run_id):
        return os.path.join(self.results, run_id)


def read_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def run_summary(state, run_id):
    d = state.run_dir(run_id)
    meta = read_json(os.path.join(d, "run.json")) or {}
    ev = os.path.join(d, "events.jsonl")
    try:
        mtime = os.path.getmtime(ev)
        with open(ev, "rb") as f:
            f.seek(max(0, os.path.getsize(ev) - 4096))
            tail = f.read().decode("utf-8", "replace")
        finished = '"type": "run_end"' in tail or '"type":"run_end"' in tail
    except OSError:
        mtime, finished = 0, False
    res = read_json(os.path.join(d, "results.json")) or {}
    live = (state.running() and state.run_id == run_id) or (not finished and time.time() - mtime < 120 and mtime > 0)
    return {"id": run_id, "mode": meta.get("mode"), "experiments": meta.get("experiments"), "started": meta.get("started"),
            "hosts": meta.get("hosts"), "commit": meta.get("commit"), "finished": finished, "running": live,
            "ok": res.get("ok"), "verdicts": {k: v.get("verdict") for k, v in (res.get("experiments") or {}).items()}}


def list_runs(state):
    out = []
    for name in sorted(os.listdir(state.results), reverse=True):
        if ID_RE.match(name) and os.path.isdir(state.run_dir(name)) and os.path.exists(os.path.join(state.run_dir(name), "events.jsonl")):
            out.append(run_summary(state, name))
    return out


def read_events(path, offset, limit=4 << 20):
    """Complete lines after a byte offset; a line still being written is left for the next call."""
    try:
        size = os.path.getsize(path)
    except OSError:
        return [], offset, 0
    if offset > size:
        offset = 0
    with open(path, "rb") as f:
        f.seek(offset)
        data = f.read(limit)
    end = data.rfind(b"\n")
    if end < 0:
        return [], offset, size
    events = []
    for line in data[: end + 1].splitlines():
        try:
            events.append(json.loads(line))
        except ValueError:
            pass
    return events, offset + end + 1, size


def build_results(state, run_id):
    d = state.run_dir(run_id)
    path = os.path.join(d, "results.json")
    ev = os.path.join(d, "events.jsonl")
    try:
        fresh = os.path.getmtime(path) >= os.path.getmtime(ev)
    except OSError:
        fresh = False
    if not fresh:
        try:
            import np_report
            report = np_report.Report(np_report.read_events(ev)).build()
            tmp = path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(report, f)
            os.replace(tmp, path)
        except Exception:
            pass
    return read_json(path)


def start_run(state, body):
    import np_config
    mode = body.get("mode")
    if mode not in MODES:
        raise ValueError("mode must be check or run")
    exps = body.get("experiments") or []
    if not exps or any(e not in EXPERIMENTS for e in exps):
        raise ValueError("experiments: pick at least one of " + ", ".join(EXPERIMENTS))
    sets = []
    for sec, kv in (body.get("settings") or {}).items():
        for k, v in (kv or {}).items():
            sets.append(f"{sec}.{k}={v}")
    try:
        np_config.resolve(mode, None, sets)  # validates names, types and ranges
    except SystemExit as e:
        raise ValueError(str(e).replace("np-bench: ", ""))
    hosts = body.get("hosts") or {}
    argv = [os.path.join(state.root, "np-benchmarks", "np-bench"), mode, "--only", ",".join(exps), "--results", state.results]
    for flag, key in (("--server", "server"), ("--client", "client"), ("--server-ip", "server_ip")):
        v = (hosts.get(key) or "").strip()
        if v:
            if not HOST_RE.match(v):
                raise ValueError(f"{key}: {v!r} is not a host name or address")
            argv += [flag, v]
    for s in sets:
        argv += ["--set", s]
    run_id = time.strftime("%Y%m%d-%H%M%S") + "-" + mode
    out = os.path.join(state.results, run_id)
    argv += ["--out", out]
    os.makedirs(out, exist_ok=True)
    log = open(os.path.join(out, "np-bench.log"), "w")
    proc = subprocess.Popen(argv, cwd=state.root, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    state.proc, state.run_id = proc, run_id
    return run_id


def stop_run(state):
    p = state.proc
    if not p or p.poll() is not None:
        return False
    try:
        os.killpg(p.pid, signal.SIGTERM)
    except OSError:
        return False

    def reap():
        try:
            p.wait(8)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(p.pid, signal.SIGKILL)
            except OSError:
                pass
    threading.Thread(target=reap, daemon=True).start()
    return True


class Handler(BaseHTTPRequestHandler):
    server_version = "np-bench"
    state = None

    def log_message(self, fmt, *args):
        pass

    def send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else (body if isinstance(body, str) else json.dumps(body)).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype + ("; charset=utf-8" if ctype.startswith("text") or ctype == "application/json" else ""))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def local(self):
        # --trust-bind: behind a port mapping that only the host's loopback can reach (the Docker setup),
        # every client the server sees is the host itself, whatever address the mapping shows.
        return self.state.trust_bind or self.client_address[0] in ("127.0.0.1", "::1")

    def authorized(self):
        return self.headers.get("X-NP-Token") == self.state.token

    def do_GET(self):
        st = self.state
        u = urlparse(self.path)
        parts = [p for p in u.path.split("/") if p]
        q = parse_qs(u.query)
        try:
            if not parts or parts == ["index.html"]:
                with open(os.path.join(HERE, "index.html"), "rb") as f:
                    html = f.read().decode()
                token = st.token if self.local() else ""
                # The page is written as a fragment (title, fonts, style, then the body), the way the
                # artifact viewer takes it; wrap it into a proper document here.
                cut = html.index('<div class="app">')
                head, body = html[:cut], html[cut:]
                doc = ('<!doctype html><html lang="en"><head><meta charset="utf-8">'
                       '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">'
                       f'<meta name="np-token" content="{token}">'
                       '<style>:root{color-scheme:light}body{margin:0;font:14px system-ui,sans-serif}[hidden]{display:none!important}</style>'
                       f'{head}</head><body>{body}</body></html>')
                return self.send(200, doc, "text/html")
            if parts[:2] == ["api", "status"]:
                return self.send(200, {"serving": True, "can_start": self.local(), "running": st.running(), "run": st.run_id if st.running() else None,
                                       "follow": st.follow, "results": st.results})
            if parts[:2] == ["api", "config"]:
                schema = read_json(os.path.join(st.root, "np-benchmarks", "bench.config.json"))
                last = (list_runs(st) or [{}])[0].get("hosts") or {}
                return self.send(200, {"schema": schema, "last_hosts": last})
            if parts[:2] == ["api", "runs"] and len(parts) == 2:
                return self.send(200, {"runs": list_runs(st)})
            if parts[:2] == ["api", "runs"] and len(parts) >= 4 and ID_RE.match(parts[2]):
                rid, what = parts[2], parts[3]
                d = st.run_dir(rid)
                if not os.path.isdir(d):
                    return self.send(404, {"error": "no such run"})
                if what == "events":
                    events, nxt, size = read_events(os.path.join(d, "events.jsonl"), int(q.get("from", ["0"])[0]))
                    return self.send(200, {"events": events, "next": nxt, "size": size, "done": run_summary(st, rid)["finished"]})
                if what == "results":
                    r = build_results(st, rid)
                    return self.send(200 if r else 404, r or {"error": "no results yet"})
                if what == "log" and len(parts) == 5 and ID_RE.match(parts[4]):
                    p = os.path.join(d, parts[4] + ".log")
                    try:
                        with open(p, "rb") as f:
                            f.seek(max(0, os.path.getsize(p) - 200_000))
                            return self.send(200, f.read(), "text/plain")
                    except OSError:
                        return self.send(404, {"error": "no log"})
            return self.send(404, {"error": "not found"})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:  # a bad request must not kill the server
            self.send(500, {"error": str(e)})

    def do_POST(self):
        st = self.state
        parts = [p for p in urlparse(self.path).path.split("/") if p]
        if not (self.local() and self.authorized()):
            return self.send(403, {"error": "starting and stopping runs needs the session token, from this machine"})
        try:
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n) or b"{}") if n else {}
            with st.lock:
                if parts == ["api", "run"]:
                    if st.running():
                        return self.send(409, {"error": "a run is already going"})
                    try:
                        return self.send(200, {"id": start_run(st, body)})
                    except ValueError as e:
                        return self.send(400, {"error": str(e)})
                if parts == ["api", "stop"]:
                    return self.send(200, {"stopped": stop_run(st)})
            return self.send(404, {"error": "not found"})
        except Exception as e:
            self.send(500, {"error": str(e)})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.abspath(os.path.join(HERE, "..", "..")))
    ap.add_argument("--results", default="np-results")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--follow")
    ap.add_argument("--trust-bind", action="store_true", help="treat every client as this machine (only behind a loopback-only port mapping)")
    a = ap.parse_args()
    token = os.environ.get("NP_TOKEN") or secrets.token_urlsafe(12)
    Handler.state = State(a.root, a.results, token, a.follow, a.trust_bind)
    srv = ThreadingHTTPServer((a.bind, a.port), Handler)
    host = "127.0.0.1" if a.bind in ("0.0.0.0", "::") else a.bind
    print(f"np-bench dashboard: http://{host}:{a.port}/   (results in {Handler.state.results})", flush=True)
    if a.bind not in ("127.0.0.1", "::1", "localhost"):
        print(f"listening on {a.bind}: " + ("every client is trusted, keep the port on loopback" if a.trust_bind else "other machines can watch, only this machine can start runs"), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
