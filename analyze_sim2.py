#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""获取仿真状态并分析烟雾蔓延和人员疏散"""
import json, urllib.request, time

def get_state():
    with urllib.request.urlopen('http://localhost:8090/state', timeout=10) as r:
        return json.loads(r.read().decode('utf-8'))

print("=== 获取仿真状态 ===")
state = get_state()
print(f"step: {state['step']}, time: {state['currentTime']}s")
print(f"active: {state['active']}, escaped: {state['escaped']}, deadFire: {state['deadFire']}, deadSmoke: {state['deadSmoke']}")
print(f"火源: ({state['fireSources'][0]['x']},{state['fireSources'][0]['y']},{state['fireSources'][0]['z']}), heatRelease={state['fireSources'][0]['heatRelease']:.2f}")

# 烟雾蔓延分析 - 结构 smoke[z][x][y], stride=2
smoke = state['smoke']
d_smoke = len(smoke)
w_smoke = len(smoke[0])
h_smoke = len(smoke[0][0])
stride = state.get('stride', 2)
print(f"\n=== 烟雾蔓延分析 (烟雾网格 {d_smoke}x{w_smoke}x{h_smoke}, stride={stride}) ===")

smoke_by_z = {}
total_smoke = 0.0
max_smoke = 0.0
smoke_cells = 0
for z in range(d_smoke):
    layer_sum = 0.0
    layer_cells = 0
    for x in range(w_smoke):
        for y in range(h_smoke):
            v = smoke[z][x][y]
            if v > 0.01:
                layer_sum += v
                layer_cells += 1
                if v > max_smoke:
                    max_smoke = v
    if layer_cells > 0:
        smoke_by_z[z] = (layer_sum, layer_cells)
        total_smoke += layer_sum
        smoke_cells += layer_cells

print(f"有烟雾的格数(下采样): {smoke_cells}, 总浓度: {total_smoke:.1f}, 最大浓度: {max_smoke:.3f}")
print(f"有烟雾的楼层: {len(smoke_by_z)}/{d_smoke} 层")
print(f"{'楼层z':<8}{'格数':<10}{'总浓度':<12}{'平均浓度':<10}")
for z in sorted(smoke_by_z.keys()):
    s, c = smoke_by_z[z]
    print(f"z={z:<5}{c:<10}{s:<12.1f}{s/c:<10.4f}")

# 人员疏散分析
print(f"\n=== 人员疏散分析 ===")
persons = state['persons']
floor_count = {}
for p in persons:
    z = p[2]
    floor_count[z] = floor_count.get(z, 0) + 1
print(f"剩余活跃人员({len(persons)}人)楼层分布:")
for z in sorted(floor_count.keys()):
    print(f"  z={z}: {floor_count[z]}人")

# 性能基准
print(f"\n=== 性能基准 (5秒) ===")
t0 = time.time()
s0 = state['step']
time.sleep(5)
state2 = get_state()
t1 = time.time()
s1 = state2['step']
real_time = t1 - t0
sim_time = (s1 - s0) * 0.5
speed = sim_time / real_time if real_time > 0 else 0
print(f"真实时间: {real_time:.2f}s, 仿真时间: {sim_time:.1f}s, 速度: {speed:.2f}x")
print(f"step/秒: {(s1-s0)/real_time:.1f}, 每step耗时: {1000*real_time/(s1-s0):.1f}ms")
print(f"\n最终状态: step={state2['step']}, time={state2['currentTime']}s, escaped={state2['escaped']}, active={state2['active']}")
