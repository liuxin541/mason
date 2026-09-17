# -*- coding: utf-8 -*-
"""核查 grid 组数据口径"""
import csv, statistics

rows = []
with open(r'E:\xuexi\MASON\_exp_work\results\summary.csv', encoding='utf-8') as f:
    for r in csv.DictReader(f):
        rows.append(r)

g = [r for r in rows if r['factor'] == 'grid']
print('grid 组样本数:', len(g))
for r in g[:8]:
    print('delta=%s steps=%s endTime=%s meanEvacTime=%s t95=%s' % (
        r['delta'], r['steps'], r['endTime'], r['meanEvacTime'], r['t95Evac']))

for delta in ['0.30', '0.25', '0.20']:
    gg = [r for r in g if r['delta'] == delta]
    et = [float(r['meanEvacTime']) for r in gg]
    steps = [int(r['steps']) for r in gg]
    et2 = [float(r['endTime']) for r in gg]
    print('delta=%s: meanEvac均值=%.1f endTime均值=%.1f 步数均值=%.0f 步数x0.5=%.1f 步数x(delta/0.5)=%.1f' % (
        delta, statistics.mean(et), statistics.mean(et2), statistics.mean(steps),
        statistics.mean(steps) * 0.5, statistics.mean(steps) * float(delta) / 0.5))

# 也看 0.50 档对照
g50 = [r for r in rows if r['factor'] == 'firecount' and r['level'] == '3']
et50 = [float(r['meanEvacTime']) for r in g50]
print('firecount-3 (delta=0.50) meanEvac 均值=%.2f' % statistics.mean(et50))
