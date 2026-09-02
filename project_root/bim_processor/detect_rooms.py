"""
深入检测房间连通性：分析门是否正确开在墙上，哪些区域被隔离
"""
import numpy as np
import struct
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5)
    ver = struct.unpack('<H', f.read(2))[0]
    w, h, d = struct.unpack('<III', f.read(12))
    vs = struct.unpack('<f', f.read(4))[0]
    f.read(9)
    data = np.frombuffer(f.read(w * h * d), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, FUEL, DOOR, EXIT, STAIR, COLUMN, WINDOW = range(8)
passable_types = {EMPTY, DOOR, EXIT, STAIR}

print("=" * 60)
print("检测1: 门是否在墙上（门的4邻居是否有墙）")
print("=" * 60)

for z in [2, 10, 18, 25]:
    doors = np.argwhere(data[z] == DOOR)
    if len(doors) == 0:
        continue
    on_wall = 0
    not_on_wall = 0
    for y, x in doors:
        has_wall = False
        for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and data[z, ny, nx] in (WALL, COLUMN):
                has_wall = True
                break
        if has_wall:
            on_wall += 1
        else:
            not_on_wall += 1
    print(f"  z={z}: 门在墙上={on_wall}, 不在墙上={not_on_wall} (总{len(doors)})")

print()
print("=" * 60)
print("检测2: 各层连通域分析（空气+门+楼梯+出口的连通区域）")
print("=" * 60)

for z in [2, 10, 18, 25]:
    layer = data[z]
    passable = np.isin(layer, list(passable_types))
    visited = np.zeros_like(passable, dtype=bool)
    regions = []

    for y in range(h):
        for x in range(w):
            if passable[y, x] and not visited[y, x]:
                # BFS
                q = deque([(y, x)])
                visited[y, x] = True
                size = 0
                has_exit = False
                has_door = False
                has_stair = False
                while q:
                    cy, cx = q.popleft()
                    size += 1
                    if layer[cy, cx] == EXIT:
                        has_exit = True
                    if layer[cy, cx] == DOOR:
                        has_door = True
                    if layer[cy, cx] == STAIR:
                        has_stair = True
                    for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                        ny, nx = cy + dy, cx + dx
                        if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
                            visited[ny, nx] = True
                            q.append((ny, nx))
                regions.append((size, has_exit, has_door, has_stair))

    regions.sort(reverse=True)
    print(f"  z={z}: 共 {len(regions)} 个连通域")
    for i, (size, has_exit, has_door, has_stair) in enumerate(regions[:5]):
        tags = []
        if has_exit:
            tags.append("出口")
        if has_door:
            tags.append("门")
        if has_stair:
            tags.append("楼梯")
        print(f"    域{i+1}: {size}格, {','.join(tags) if tags else '纯空气(被隔离)'}")

print()
print("=" * 60)
print("检测3: 出口所在连通域的大小（人员能否到达出口）")
print("=" * 60)

for z in [2, 3, 4, 5, 6]:
    exits = np.argwhere(data[z] == EXIT)
    if len(exits) == 0:
        continue
    # 从出口BFS，看连通域大小
    layer = data[z]
    passable = np.isin(layer, list(passable_types))
    visited = np.zeros_like(passable, dtype=bool)
    q = deque()
    for y, x in exits:
        if not visited[y, x]:
            visited[y, x] = True
            q.append((y, x))
    size = 0
    while q:
        cy, cx = q.popleft()
        size += 1
        for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            ny, nx = cy + dy, cx + dx
            if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
                visited[ny, nx] = True
                q.append((ny, nx))
    total_passable = int(np.sum(passable))
    print(f"  z={z}: 出口连通域={size}格, 该层总可通行={total_passable}格, 占比={size/total_passable*100:.0f}%")

print()
print("=" * 60)
print("检测4: z=8->z=9 楼梯断点详细分析")
print("=" * 60)

z8_stairs = np.argwhere(data[8] == STAIR)
z9_stairs = np.argwhere(data[9] == STAIR)
print(f"  z=8 楼梯位置: {[(int(y),int(x)) for y,x in z8_stairs]}")
print(f"  z=9 楼梯位置: {[(int(y),int(x)) for y,x in z9_stairs]}")

# 检查 z=8 楼梯正下方 z=9 是什么
print("  z=8 楼梯正下方(z=9)的体素类型:")
for y, x in z8_stairs:
    t = int(data[9, y, x])
    type_name = {0: "空气", 1: "墙", 3: "门", 5: "楼梯", 7: "窗"}.get(t, str(t))
    print(f"    ({y},{x}): {type_name}({t})")

# 检查 z=9 楼梯正上方 z=8 是什么
print("  z=9 楼梯正上方(z=8)的体素类型:")
for y, x in z9_stairs:
    t = int(data[8, y, x])
    type_name = {0: "空气", 1: "墙", 3: "门", 5: "楼梯", 7: "窗"}.get(t, str(t))
    print(f"    ({y},{x}): {type_name}({t})")
