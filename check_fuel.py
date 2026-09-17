#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import struct
from collections import Counter

with open(r'E:\xuexi\MASON\mason\ifc_output\voxel_grid.voxel','rb') as f:
    data = f.read()

# 解析头部: VOXEL(5) + version(2) + w(4) + h(4) + d(4) + voxel_size(4) = 23字节
magic = data[0:5].decode('ascii')
version = struct.unpack_from('<H', data, 5)[0]
w = struct.unpack_from('<I', data, 7)[0]
h = struct.unpack_from('<I', data, 11)[0]
d = struct.unpack_from('<I', data, 15)[0]
voxel_size = struct.unpack_from('<f', data, 19)[0]
print(f'格式: {magic} v{version}, 网格={w}x{h}x{d}, voxel_size={voxel_size}m')

body_offset = 23
body = data[body_offset:body_offset + w*h*d]
print(f'体素数据: {len(body)}字节')

types = Counter()
fuel_positions = []
for i in range(len(body)):
    t = body[i]
    types[t] += 1
    if t == 2:  # TYPE_FUEL
        z = i // (w*h)
        y = (i % (w*h)) // w
        x = i % w
        fuel_positions.append((x,y,z))

names = {0:'EMPTY空气',1:'WALL墙',2:'FUEL可燃物',3:'DOOR门',4:'EXIT出口',5:'STAIR楼梯',6:'WINDOW窗'}
print('\n类型分布:')
for t in sorted(types.keys()):
    print(f'  {t}={names.get(t,"?")}: {types[t]}')

print(f'\n可燃物格数: {len(fuel_positions)}')
if fuel_positions:
    zs = sorted(set(p[2] for p in fuel_positions))
    print(f'可燃物楼层(z): {zs}')
    print(f'可燃物示例(前5): {fuel_positions[:5]}')
else:
    print('警告: BIM模型中没有标记任何可燃物! 火焰只能在固定火源处燃烧,无法蔓延到其他区域!')
