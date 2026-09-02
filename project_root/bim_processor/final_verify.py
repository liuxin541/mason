"""综合验证所有修复效果"""
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
print("BIM 建模转换修复综合验证报告")
print("=" * 70)

# 1. 体素类型统计
print("\n【1. 体素类型统计】")
types = {0:'空气', 1:'墙', 3:'门', 4:'出口', 5:'楼梯', 6:'柱', 7:'窗'}
for t, name in sorted(types.items()):
    count = int(np.sum(data == t))
    print(f"  {name}: {count} 体素 ({count/(w*h*d)*100:.1f}%)")

# 2. 出口连通性
print("\n【2. 出口连通性验证】")
exit_count = int(np.sum(data == EXIT))
print(f"  出口总数: {exit_count}")
all_reachable = True
for z in [2, 5, 12, 20, 27]:
    if z >= d: continue
    layer = data[z]
    passable = np.isin(layer, list(passable_types))
    exits = np.argwhere(layer == EXIT)
    if len(exits) == 0:
        print(f"  z={z}: 无出口")
        continue
    visited = np.zeros_like(passable, dtype=bool)
    q = deque()
    for y, x in exits:
        q.append((y, x)); visited[y, x] = True
    reached = False
    while q:
        cy, cx = q.popleft()
        if cy <= 0 or cy >= h-1 or cx <= 0 or cx >= w-1:
            reached = True
        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny, nx = cy+dy, cx+dx
            if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
                visited[ny, nx] = True
                q.append((ny, nx))
    status = "✓ 连通外部" if reached else "⚠ 未连通"
    if not reached: all_reachable = False
    print(f"  z={z}: 出口={len(exits)}, {status}")
print(f"  总体: {'✓ 所有出口连通外部' if all_reachable else '⚠ 部分出口未连通'}")

# 3. 楼板层窗/门穿透
print("\n【3. 楼板层穿透验证】")
slab_layers = [z for z in range(d) if np.sum(data[z] != 0)/(w*h) > 0.8]
penetration_ok = True
for z in slab_layers[:10]:
    door = int(np.sum(data[z] == DOOR))
    win = int(np.sum(data[z] == WINDOW))
    if door > 0 or win > 0:
        penetration_ok = False
        print(f"  z={z}: 门={door}, 窗={win} ⚠")
print(f"  总体: {'✓ 楼板层无门/窗穿透' if penetration_ok else '⚠ 仍有穿透'}")

# 4. 楼层连通性
print("\n【4. 楼层连通性验证】")
floor_ranges = [(0,7), (11,15), (18,22), (25,28)]
for idx, (zs, ze) in enumerate(floor_ranges):
    max_comp = 0
    for z in range(zs, ze+1):
        if z >= d: continue
        layer = data[z]
        passable = np.isin(layer, list(passable_types))
        visited = np.zeros_like(passable, dtype=bool)
        comp = 0
        for y in range(h):
            for x in range(w):
                if passable[y, x] and not visited[y, x]:
                    comp += 1
                    q = deque([(y, x)])
                    visited[y, x] = True
                    while q:
                        cy, cx = q.popleft()
                        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                            ny, nx = cy+dy, cx+dx
                            if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
                                visited[ny, nx] = True
                                q.append((ny, nx))
        max_comp = max(max_comp, comp)
    print(f"  F{idx+1} (z={zs}~{ze}): 最多 {max_comp} 连通域 {'✓' if max_comp <= 2 else '⚠'}")

# 5. 楼板层缺口
print("\n【5. 楼板层缺口验证】")
for z in [8, 9, 16, 23, 29]:
    if z >= d: continue
    air = int(np.sum(data[z] == EMPTY))
    stair = 0
    for y, x in np.argwhere(data[z] == EMPTY):
        for dz in [-1, 0, 1]:
            for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                nz, ny, nx = z+dz, y+dy, x+dx
                if 0 <= nz < d and 0 <= ny < h and 0 <= nx < w and data[nz, ny, nx] == STAIR:
                    stair += 1
                    break
            else: continue
            break
    gap = air - stair
    print(f"  z={z}: 空气={air}, 楼梯井={stair}, 缺口={gap} {'✓' if gap < 100 else '⚠'}")

print("\n" + "=" * 70)
print("验证完成")
print("=" * 70)
