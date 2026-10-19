#!/usr/bin/env python3
"""Samples one process (and its machine) every few seconds and prints one event per sample.

  np_sampler.py --pid PID [--interval 5] [--role server] [--tag '"exp":"soak"']
                [--stats-url https://127.0.0.1:8080/__stats] [--cacert PEM] [--events FILE|-]

With --events - (the default) each line goes to stderr prefixed NPEV, which is how np-bench collects
events from other machines through ssh. Samples: CPU of the process (percent of one core), resident
memory, threads, open file descriptors (Linux), and the machine's CPU use. With --stats-url, the JVM
figures of the benchmark server's /__stats (heap used and committed, non-heap, collections, live
threads) are added, and `other_mb` is what is left of the resident memory after the heap in use and the
non-heap (native memory, libraries, thread stacks, garbage not yet collected): an estimate.

Runs until the process ends or it is killed. Linux and macOS.
"""
import argparse, json, os, ssl, subprocess, sys, time, urllib.request

LINUX = sys.platform.startswith("linux")
HZ = os.sysconf("SC_CLK_TCK") if LINUX else 100
PAGE = os.sysconf("SC_PAGE_SIZE") if LINUX else 4096
NCPU = os.cpu_count() or 1


def cpu_seconds(pid):
    if LINUX:
        with open(f"/proc/{pid}/stat") as f:
            rest = f.read().rsplit(")", 1)[1].split()
        return (int(rest[11]) + int(rest[12])) / HZ
    out = subprocess.run(["ps", "-o", "time=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
    h = 0.0
    for part in out.split(":"):
        h = h * 60 + float(part)
    return h


def proc(pid):
    """(rss_mb, threads, fds) or None when the process is gone."""
    try:
        if LINUX:
            with open(f"/proc/{pid}/statm") as f:
                rss = int(f.read().split()[1]) * PAGE / 2**20
            with open(f"/proc/{pid}/status") as f:
                threads = next(int(l.split()[1]) for l in f if l.startswith("Threads:"))
            try:
                fds = len(os.listdir(f"/proc/{pid}/fd"))
            except OSError:
                fds = None
            return rss, threads, fds
        out = subprocess.run(["ps", "-o", "rss=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
        if not out:
            return None
        th = subprocess.run(["ps", "-M", "-p", str(pid)], capture_output=True, text=True).stdout.strip().splitlines()
        return int(out) / 1024, max(0, len(th) - 1), None
    except (OSError, StopIteration, ValueError):
        return None


class Machine:
    """CPU use of the whole machine since the last call, percent of all cores."""
    def __init__(self):
        self.prev = self.read()

    def read(self):
        if LINUX:
            with open("/proc/stat") as f:
                v = [int(x) for x in f.readline().split()[1:]]
            return sum(v), v[3] + (v[4] if len(v) > 4 else 0)
        out = subprocess.run(["ps", "-A", "-o", "%cpu="], capture_output=True, text=True).stdout.split()
        return None, sum(float(x) for x in out) / NCPU

    def pct(self):
        cur = self.read()
        if LINUX:
            dt, di = cur[0] - self.prev[0], cur[1] - self.prev[1]
            self.prev = cur
            return round(100 * (dt - di) / dt, 1) if dt else None
        return round(min(100.0, cur[1]), 1)


def fetch_stats(url, cafile):
    ctx = ssl.create_default_context(cafile=cafile) if cafile else None
    try:
        with urllib.request.urlopen(url, context=ctx, timeout=5) as r:
            return json.loads(r.read())
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pid", type=int, required=True)
    ap.add_argument("--interval", type=float, default=5)
    ap.add_argument("--role", default="server")
    ap.add_argument("--tag", default="")
    ap.add_argument("--stats-url")
    ap.add_argument("--cacert")
    ap.add_argument("--events", default="-")
    a = ap.parse_args()
    sink = None if a.events == "-" else open(a.events, "a")
    machine = Machine()
    last_cpu, last_t = None, None
    while True:
        p = proc(a.pid)
        if p is None:
            return
        try:
            cpu = cpu_seconds(a.pid)
        except (OSError, ValueError, IndexError):
            return
        now = time.time()
        ev = {"t": int(now * 1000), "type": "sample", "src": "sampler", "role": a.role, "pid": a.pid}
        if a.tag:
            ev.update(json.loads("{" + a.tag + "}"))
        ev["rss_mb"] = round(p[0], 1)
        ev["threads"] = p[1]
        if p[2] is not None:
            ev["fds"] = p[2]
        if last_cpu is not None and now > last_t:
            ev["cpu_pct"] = round(100 * (cpu - last_cpu) / (now - last_t), 1)
        ev["machine_cpu_pct"] = machine.pct()
        if a.stats_url:
            st = fetch_stats(a.stats_url, a.cacert)
            if st:
                for k in ("heap_used_mb", "heap_committed_mb", "nonheap_mb", "gc_count", "gc_ms", "jvm_threads"):
                    if k in st:
                        ev[k] = st[k]
                if "heap_used_mb" in st:
                    # an estimate: resident memory less the heap in use and the non-heap (native
                    # memory, libraries, thread stacks, garbage not yet collected)
                    ev["other_mb"] = round(max(0.0, p[0] - st["heap_used_mb"] - st.get("nonheap_mb", 0)), 1)
        line = json.dumps(ev)
        if sink:
            sink.write(line + "\n"); sink.flush()
        else:
            print("NPEV " + line, file=sys.stderr, flush=True)
        last_cpu, last_t = cpu, now
        time.sleep(max(0.2, a.interval - (time.time() - now)))


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
