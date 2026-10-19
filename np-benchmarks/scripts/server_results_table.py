import json, sys
rows = [json.loads(l) for l in open(sys.argv[1])]
print(f"{'scenario':16} {'round':>5} {'server':8} {'rps':>8} {'p50us':>7} {'p99us':>7} {'p999us':>8} {'err':>4} {'rssMB':>6}")
for r in rows:
    x = r['result']
    print(f"{r['scenario']:16} {r['round']:>5} {r['server']:8} {x['rps']:>8} {x['p50_us']:>7} {x['p99_us']:>7} {x['p999_us']:>8} {x['errors']:>4} {r['rss_kb_after']//1024:>6}")
