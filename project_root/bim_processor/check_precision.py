import numpy as np
import struct
import json
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5)
    ver = struct.unpack('<H', f.read(2))[0]
    w, h, d = struct.unpack('<III', f.read(12))
    vs = struct.unpack('<f', f.read(4))[0]
    f.read(9)
    data = np.frombuffer(f.read(w * h * d), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, FUEL, DOOR, EXIT, STAIR, COLUMN, WINDOW = range(8)

print('=== 问题A: 出口(EXIT)识别 ===')
print(f'  EXIT体素数量: {int(np.sum(data == EXIT))}')
print(f'  (0 = 没有识别出口，人员无法找到逃生出口)')

print()
print('=== 问题B: 各层DOOR数量 ===')
for z in range(d):
    cnt = int(np.sum(data[z] == DOOR))
    if cnt > 0:
        print(f'    z={z:2d}: {cnt}')

print()
print('=== 问题C: 第一层外墙缺口 ===')
z = 0
layer = data[z]
passable = (layer == EMPTY)
visited = np.zeros_like(passable, dtype=bool)
q = deque()
for x in range(w):
    if passable[0, x]:
        q.append((0, x)); visited[0, x] = True
    if passable[h - 1, x]:
        q.append((h - 1, x)); visited[h - 1, x] = True
for y in range(h):
    if passable[y, 0]:
        q.append((y, 0)); visited[y, 0] = True
    if passable[y, w - 1]:
        q.append((y, w - 1)); visited[y, w - 1] = True
while q:
    cy, cx = q.popleft()
    for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
        ny, nx = cy + dy, cx + dx
        if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
            visited[ny, nx] = True
            q.append((ny, nx))

outside_air = visited & passable
print(f'  z=0 层与外部连通的空气: {int(np.sum(outside_air))}')

door_adjacent_outside = 0
gap_positions = []
for y in range(h):
    for x in range(w):
        if outside_air[y, x]:
            for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                ny, nx = y + dy, x + dx
                if 0 <= ny < h and 0 <= nx < w and layer[ny, nx] == DOOR:
                    door_adjacent_outside += 1
                    gap_positions.append((y, x))
                    break
print(f'  其中紧邻DOOR的外部空气(入口缺口): {door_adjacent_outside}')
if gap_positions:
    ys = [p[0] for p in gap_positions]
    xs = [p[1] for p in gap_positions]
    print(f'  缺口位置范围: y=[{min(ys)},{max(ys)}], x=[{min(xs)},{max(xs)}]')

print()
print('=== 问题D: semantic_info ===')
with open(r'D:\mason\ifc_output\semantic_info.json', 'r', encoding='utf-8') as f:
    info = json.load(f)
print(f'  floors={info.get("floors")} (实际建筑约4层)')
print(f'  stair_positions={info.get("stair_positions")}')
print(f'  grid_dims={info.get("grid_dims")}')

print()
print('=== 问题E: 楼梯(STAIR)分布 ===')
for z in range(d):
    cnt = int(np.sum(data[z] == STAIR))
    if cnt > 0:
        print(f'    z={z:2d}: {cnt}')

print()
print('=== 问题F: 窗(WINDOW)是否被正确放置在墙上 ===')
# 检查 WINDOW 体素的水平邻居是否有墙（窗应该在墙上）
window_positions = np.argwhere(data == WINDOW)
window_in_wall = 0
for z, y, x in window_positions:
    has_wall_neighbor = False
    for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
        ny, nx = y + dy, x + dx
        if 0 <= ny < h and 0 <= nx < w and data[z, ny, nx] in (WALL, COLUMN):
            has_wall_neighbor = True
            break
    if has_wall_neighbor:
        window_in_wall += 1
print(f'  WINDOW体素总数: {len(window_positions)}')
print(f'  其中水平方向紧邻墙/柱: {window_in_wall}')
print(f'  无墙邻居的窗(可能在房间中间，异常): {len(window_positions) - window_in_wall}')
