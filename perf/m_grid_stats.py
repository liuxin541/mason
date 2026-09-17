# -*- coding: utf-8 -*-
"""grid_only.csv 各档统计，确认与论文表5网格行一致"""
import csv, statistics

rows = list(csv.DictReader(open(r'E:\xuexi\MASON\_exp_work\results\grid_only.csv', encoding='utf-8')))
for delta in ['0.50', '0.30', '0.25', '0.20']:
    gg = [r for r in rows if r['level'] == delta]
    evac = [float(r['evacRate']) * 100 for r in gg]
    dead = [float(r['deadS']) / int(r['N']) * 100 for r in gg]
    et = [float(r['meanEvacTime']) for r in gg]
    t95 = [float(r['t95Evac']) for r in gg]
    n = len(gg)
    def ms(v):
        return statistics.mean(v), statistics.stdev(v)
    me, se = ms(evac); md, sd = ms(dead); mt, st = ms(et); mt95, st95 = ms(t95)
    print('delta=%s n=%d 疏散率 %.2f±%.2f 死亡率 %.2f±%.2f 均疏散 %.2f±%.2f T95 %.1f±%.1f' % (
        delta, n, me, se, md, sd, mt, st, mt95, st95))
