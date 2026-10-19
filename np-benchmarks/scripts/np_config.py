#!/usr/bin/env python3
"""np-bench settings: the schema in bench.config.json, merged with a --config file and --set
overrides, validated, and printed as JSON or as shell exports.

  np_config.py schema
  np_config.py resolve --mode check|run [--config FILE] [--set exp.key=value ...]
  np_config.py env     --mode check|run [--config FILE] [--set exp.key=value ...]

`env` prints `export NP_<EXPERIMENT>_<KEY>='value'` for every setting (and NP_CORES for the global
ones), which is how the shell scripts receive them. A bad key or value exits with status 2.
"""
import argparse, json, os, shlex, sys

SCHEMA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bench.config.json")


def load_schema():
    with open(SCHEMA) as f:
        return json.load(f)


def coerce(p, raw, where):
    t = p["type"]
    try:
        if t == "int":
            v = int(float(raw))
        elif t == "float":
            v = float(raw)
        elif t == "bool":
            if isinstance(raw, bool):
                v = raw
            elif str(raw).lower() in ("1", "true", "yes", "on"):
                v = True
            elif str(raw).lower() in ("0", "false", "no", "off"):
                v = False
            else:
                raise ValueError("not a boolean")
        else:
            v = str(raw)
    except (TypeError, ValueError):
        sys.exit(f"np-bench: {where}: {raw!r} is not a valid {t}")
    if t in ("int", "float"):
        if "min" in p and v < p["min"]:
            sys.exit(f"np-bench: {where}: {v} is below the minimum {p['min']}")
        if "max" in p and v > p["max"]:
            sys.exit(f"np-bench: {where}: {v} is above the maximum {p['max']}")
    return v


def resolve(mode, config_file=None, sets=()):
    schema = load_schema()
    defs = {"global": {p["key"]: p for p in schema["global"]}}
    for e in schema["experiments"]:
        defs[e["id"]] = {p["key"]: p for p in e["params"]}
    values = {sec: {k: (p["check"] if mode == "check" and "check" in p else p["default"]) for k, p in ps.items()} for sec, ps in defs.items()}
    given = []
    if config_file:
        with open(config_file) as f:
            for sec, kv in json.load(f).items():
                for k, v in (kv or {}).items():
                    given.append((sec, k, v, f"{config_file}: {sec}.{k}"))
    for s in sets:
        if "=" not in s or "." not in s.split("=", 1)[0]:
            sys.exit(f"np-bench: --set wants experiment.key=value, got {s!r}")
        left, v = s.split("=", 1)
        sec, k = left.split(".", 1)
        given.append((sec, k, v, f"--set {s}"))
    for sec, k, v, where in given:
        if sec not in defs:
            sys.exit(f"np-bench: {where}: unknown experiment {sec!r} (known: {', '.join(defs)})")
        if k not in defs[sec]:
            sys.exit(f"np-bench: {where}: {sec} has no setting {k!r} (known: {', '.join(defs[sec]) or 'none'})")
        values[sec][k] = coerce(defs[sec][k], v, where)
    for sec in values:
        for k, p in defs[sec].items():
            values[sec][k] = coerce(p, values[sec][k], f"{sec}.{k}")
    return values


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["schema", "resolve", "env"])
    ap.add_argument("--mode", default="run", choices=["check", "run"])
    ap.add_argument("--config")
    ap.add_argument("--set", action="append", default=[])
    a = ap.parse_args()
    if a.cmd == "schema":
        json.dump(load_schema(), sys.stdout, indent=1)
        print()
        return
    v = resolve(a.mode, a.config, a.set)
    if a.cmd == "resolve":
        json.dump(v, sys.stdout, indent=1)
        print()
        return
    for sec, kv in v.items():
        for k, val in kv.items():
            name = ("NP_" + k if sec == "global" else f"NP_{sec}_{k}").upper()
            out = ("1" if val else "0") if isinstance(val, bool) else str(val)
            print(f"export {name}={shlex.quote(out)}")


if __name__ == "__main__":
    main()
