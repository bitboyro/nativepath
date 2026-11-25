"""Describe temporal association; does not establish causation or recompute request percentiles.

Usage: python3 soak_scan_timing.py /path/to/events.jsonl
Windows: recorded scan end minus reported duration minus 1 s, through end plus 10 s.
"""
import json
import sys
from pathlib import Path


def analyze(path):
    events = [json.loads(line) for line in Path(path).read_text().splitlines() if line.strip()]
    events = [e for e in events if e.get('exp') == 'soak']
    scans = [e for e in events if e.get('type') == 'scan']
    ticks = [e for e in events if e.get('type') == 'tick' and e.get('phase') == 'measure']
    windows = [(e['t'] - e['ms'] - 1000, e['t'] + 10000) for e in scans]
    def near(tick):
        return any(start <= tick['t'] <= end for start, end in windows)
    result = {'measurement_ticks': len(ticks), 'recorded_scans': len(scans),
              'window_before_start_ms': 1000, 'window_after_end_ms': 10000,
              'ticks_in_windows': sum(near(t) for t in ticks),
              'max_tick_p99_us_outside_windows': max((t['p99_us'] for t in ticks if not near(t)), default=None),
              'thresholds': []}
    for threshold in [100000, 1000000]:
        selected = [t for t in ticks if t.get('p99_us', 0) > threshold]
        result['thresholds'].append({'tick_p99_above_us': threshold, 'ticks': len(selected),
                                     'ticks_in_windows': sum(near(t) for t in selected)})
    return result


if __name__ == '__main__':
    print(json.dumps(analyze(sys.argv[1]), indent=2))
