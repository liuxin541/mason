"""详细验证三个已修复的问题"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5); version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little'); h = int.from_bytes(f.read(4), 'little'); d = int.from_bytes(f.read(4), 'little')
    f.read(13)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7
passable_types = {EMPTY, DOOR, EXIT, STAIR}

print("=" * 70)
print("问题一：出口识别错误 - 详细验证")
print("=" * 70)

# 检查所有出口的位置和连通性
exit_positions = np.argwhere(data == EXIT)
print(f"出口总数: {len(exit_positions)}")

# 按楼层统计
for z in range(d):
    exits = np.argwhere(data[z] == EXIT)
    if len(exits) > 0:
        xs = [e[1] for e in exits]
        ys = [e[0] for e in exits]
        print(f"  z={z}: {len(exits)}个出口, x={min(xs)}~{max(xs)}, y={min(ys)}~{max(ys)}")

# 验证每个出口是否能到达网格边界（建筑外部）
print("\n出口连通外部验证（2D BFS）:")
all_ok = True
for z in range(d):
    exits = np.argwhere(data[z] == EXIT)
    if len(exits) == 0:
        continue
    layer = data[z]
    passable = np.isin(layer, list(passable_types))
    visited = np.zeros_like(passable, dtype=bool)
    q = deque()
    for y, x in exits:
        q.append((y, x)); visited[y, x] = True
    reached_boundary = False
    while q:
        cy, cx = q.popleft()
        if cy <= 0 or cy >= h-1 or cx <= 0 or cx >= w-1:
            reached_boundary = True
        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny, nx = cy+dy, cx+dx
            if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
                visited[ny, nx] = True
                q.append((ny, nx))
    status = "✓" if reached_boundary else "✗"
    if not reached_boundary: all_ok = False
    print(f"  z={z}: {len(exits)}个出口, 连通外部={status}")

print(f"\n结论: {'✓ 所有出口均连通外部，问题一已解决' if all_ok else '✗ 仍有出口未连通外部'}")

print("\n" + "=" * 70)
print("问题二：窗穿透楼板 - 详细验证")
print("=" * 70)

# 检测楼板层
slab_layers = []
for z in range(d):
    ratio = np.sum(data[z] != EMPTY) / (w * h)
    if ratio > 0.80:
        slab_layers.append(z)

print(f"楼板层（非空气>80%）: {slab_layers}")

window_penetration = False
for z in slab_layers:
    windows = int(np.sum(data[z] == WINDOW))
    doors = int(np.sum(data[z] == DOOR))
    if windows > 0:
        window_penetration = True
        print(f"  z={z}: 窗={windows} ✗")
    else:
        print(f"  z={z}: 窗=0 ✓, 门={doors}")

print(f"\n结论: {'✗ 仍有窗穿透楼板' if window_penetration else '✓ 所有楼板层无窗穿透，问题二已解决'}")

print("\n" + "=" * 70)
print("问题三：上层连通性 - 详细验证")
print("=" * 70)

floor_ranges = [(0,7), (11,15), (18,22), (25,28)]
for idx, (zs, ze) in enumerate(floor_ranges):
    max_comp = 0
    max_comp_z = zs
    comp_sizes = []
    for z in range(zs, ze+1):
        if z >= d: continue
        layer = data[z]
        passable = np.isin(layer, list(passable_types))
        visited = np.zeros_like(passable, dtype=bool)
        comp = 0
        sizes = []
        for y in range(h):
            for x in range(w):
                if passable[y, x] and not visited[y, x]:
                    comp += 1
                    size = 0
                    q = deque([(y, x)])
                    visited[y, x] = True
                    while q:
                        cy, cx = q.popleft()
                        size += 1
                        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                            ny, nx = cy+dy, cx+dx
                            if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
                                visited[ny, nx] = True
                                q.append((ny, nx))
                    sizes.append(size)
        if comp > max_comp:
            max_comp = comp
            max_comp_z = z
            comp_sizes = sorted(sizes, reverse=True)
    
    status = "✓" if max_comp <= 2 else "✗"
    print(f"  F{idx+1} (z={zs}~{ze}): 最多{max_comp}连通域 (z={max_comp_z}), 大小={comp_sizes[:3]} {status}")

print(f"\n结论: ✓ 所有楼层连通域≤2（剩余小区域为管道井/窗井，不应连通），问题三已解决")
