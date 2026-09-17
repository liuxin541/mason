# -*- coding: utf-8 -*-
"""汇总 M2/M4/M5 所需统计量"""
import csv, statistics
from math import sqrt

# ---- 表5/表7 数据源：summary.csv（BatchExperiment 570次） ----
rows = []
with open(r'E:\xuexi\MASON\_exp_work\results\summary.csv', encoding='utf-8') as f:
    for r in csv.DictReader(f):
        rows.append(r)

# M2: 表5 各因子水平 30 次重复 均值±SD
def stats(key_levels, label_map):
    print(f'===== {label_map} =====')
    for fl in key_levels:
        factor, level = fl.split(',')
        g = [r for r in rows if r['factor'] == factor and r['level'] == level]
        if not g:
            print(f'{factor}/{level}: 无数据'); continue
        evac = [float(r['evacRate'])*100 for r in g]
        mort = [(float(r['deadF'])+float(r['deadS']))/int(r['N'])*100 for r in g]
        et = [float(r['meanEvacTime']) for r in g]
        t95 = [float(r['t95Evac']) for r in g]
        def m_sd(v):
            m = statistics.mean(v); sd = statistics.stdev(v)
            return m, sd
        me, se = m_sd(evac); mm, sm = m_sd(mort); mt, st = m_sd(et); mt95, st95 = m_sd(t95)
        print(f'{factor:10s} {level:6s} 疏散率 {me:6.2f}±{se:4.2f}  死亡率 {mm:6.2f}±{sm:4.2f}  均疏散 {mt:6.2f}±{st:4.2f}  T95 {mt95:6.1f}±{st95:4.1f}')
stats(['firecount,1','firecount,3','firecount,5','density,60','density,120','density,180',
       'risk,seeking','risk,neutral','risk,averse','smoke,off','smoke,vis','smoke,full',
       'firepos,far','firepos,center','firepos,near','grid,0.50','grid,0.30','grid,0.25','grid,0.20'], '表5 统计')

# M4: 死亡人次分层
tot_death = sum(int(r['deadF'])+int(r['deadS']) for r in rows)
d50 = sum(int(r['deadF'])+int(r['deadS']) for r in rows if float(r['delta']) >= 0.499)
dfine = sum(int(r['deadF'])+int(r['deadS']) for r in rows if float(r['delta']) < 0.499)
n50 = len([r for r in rows if float(r['delta']) >= 0.499])
nfine = len([r for r in rows if float(r['delta']) < 0.499])
print(f'\nM4 死亡人次: 总 {tot_death} (Δ=0.50: {d50} / {n50}次, 细网格: {dfine} / {nfine}次)')
deadF_tot = sum(int(r['deadF']) for r in rows)
print(f'高温死亡合计: {deadF_tot}')

# ---- M5: 表7 幸存者平均 FED（ca_compare.csv） ----
print('\n===== M5 表7 幸存者平均FED（30次均值） =====')
ca = []
with open(r'E:\xuexi\MASON\_exp_work\results\ca_compare.csv', encoding='utf-8') as f:
    for r in csv.DictReader(f):
        ca.append(r)
for g in ['staticCA', 'naiveDist', 'dynamic']:
    for N in ['60', '120', '180']:
        gg = [r for r in ca if r['group'] == g and r['density'] == N]
        fed = [float(r['meanSurvFED']) for r in gg]
        evac = [float(r['evacRate'])*100 for r in gg]
        deadS = [int(r['deadS']) for r in gg]
        et = [float(r['meanEvac']) for r in gg]
        fp = [float(r['flowPeak']) for r in gg]
        print(f'{g:9s} N={N:3s} 疏散率 {statistics.mean(evac):6.2f} 死亡 {statistics.mean(deadS)/int(N)*100:5.1f}% '
              f'均疏散 {statistics.mean(et):6.2f} 流量峰 {statistics.mean(fp):5.2f} 幸存FED {statistics.mean(fed):.3f}±{statistics.stdev(fed):.3f}')
