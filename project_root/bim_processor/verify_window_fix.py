"""验证楼板层窗穿透是否修复"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5); version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little'); h = int.from_bytes(f.read(4), 'little'); d = int.from_bytes(f.read(4), 'little')
    f.read(13)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7

print("=" * 60)
print("楼板层窗穿透验证")
print("=" * 60)

# 检测楼板层（非空气占比 > 80%）
slab_layers = []
for z in range(d):
    ratio = np.sum(data[z] != 0) / (w * h)
    if ratio > 0.8:
        slab_layers.append(z)

print(f"楼板层: {slab_layers}")
print("\n各楼板层门/窗数量:")
for z in slab_layers:
    door_count = int(np.sum(data[z] == DOOR))
    win_count = int(np.sum(data[z] == WINDOW))
    exit_count = int(np.sum(data[z] == EXIT))
    status = "✓" if (door_count == 0 and win_count == 0) else "⚠"
    print(f"  z={z}: 门={door_count}, 窗={win_count}, 出口={exit_count} {status}")

# 检查出口连通性
print("\n" + "=" * 60)
print("出口连通性验证")
print("=" * 60)
from collections import deque
passable_types = {EMPTY, DOOR, EXIT, STAIR}
for z in [2, 5, 12, 20, 27]:
    if z >= d:
        continue
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
    print(f"  z={z}: 出口={len(exits)}, 到达外部={'✓' if reached_boundary else '⚠'}")
