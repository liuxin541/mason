#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json, urllib.request, time
with urllib.request.urlopen('http://localhost:8095/state', timeout=10) as r:
    state = json.loads(r.read().decode('utf-8'))
print('step:', state['step'], 'time:', state['currentTime'], 's')
print('escaped:', state['escaped'], 'active:', state['active'], 'dead:', state['deadFire']+state['deadSmoke'])
print('火源heatRelease:', round(state['fireSources'][0]['heatRelease'], 2))
smoke = state['smoke']
d, w, h = len(smoke), len(smoke[0]), len(smoke[0][0])
smoke_by_z = {}
total = 0; cells = 0; maxv = 0
for z in range(d):
    ls = 0; lc = 0
    for x in range(w):
        for y in range(h):
            v = smoke[z][x][y]
            if v > 0.01:
                ls += v; lc += 1
                if v > maxv: maxv = v
    if lc > 0:
        smoke_by_z[z] = (ls, lc)
        total += ls; cells += lc
print('烟雾格数:', cells, '总浓度:', round(total,1), '最大浓度:', round(maxv,3))
print('有烟雾的楼层:', len(smoke_by_z), '/', d, '层')
for z in sorted(smoke_by_z.keys()):
    s, c = smoke_by_z[z]
    print(f'  z={z}: {c}格, 浓度={s:.1f}, 平均={s/c:.3f}')
