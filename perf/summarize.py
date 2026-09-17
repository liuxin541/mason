# -*- coding: utf-8 -*-
import csv, statistics

rows = []
with open(r'E:\xuexi\MASON\_exp_work\perf_results.csv', encoding='utf-8') as f:
    for r in csv.DictReader(f):
        rows.append({k: (float(v) if k not in ('N', 'seed') else int(v)) for k, v in r.items()})

groups = {}
for r in rows:
    groups.setdefault(r['N'], []).append(r)

print(f"{'N':>5} {'中位perStep_ms':>14} {'均值perStep_ms':>14} {'范围perStep_ms':>22} {'中位加速比':>10} {'加速比范围':>16}")
for N in sorted(groups):
    g = groups[N]
    ps = sorted(x['perStep_ms'] for x in g)
    sp = sorted(x['speedupX'] for x in g)
    med_ps = statistics.median(ps)
    mean_ps = statistics.mean(ps)
    med_sp = statistics.median(sp)
    print(f"{N:>5} {med_ps:>14.3f} {mean_ps:>14.3f} {f'{ps[0]:.3f}-{ps[-1]:.3f}':>22} {med_sp:>10.1f} {f'{sp[0]:.1f}-{sp[-1]:.1f}':>16}")

# 60 人剔除离群后的口径
g60 = sorted(x['perStep_ms'] for x in groups[60])
g60b = [v for v in g60 if v < 3.0]
print()
print('60人剔除离群(>3ms)后:', [f'{v:.3f}' for v in g60b], '中位数', round(statistics.median(g60b), 3))
