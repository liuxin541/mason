"""
定位未连通区域的位置和隔离墙
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

z = 10
layer = data[z]
passable = np.isin(layer, list(passable_types))
visited = np.zeros_like(passable, dtype=bool)

# 找所有连通域
regions = []
for y in range(h):
    for x in range(w):
        if passable[y, x] and not visited[y, x]:
            q = deque([(y, x)])
            visited[y, x] = True
            cells = []
            while q:
                cy, cx = q.popleft()
                cells.append((cy, cx))
                for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
                        visited[ny, nx] = True
                        q.append((ny, nx))
            regions.append(cells)

regions.sort(key=len, reverse=True)
print(f"z={z} 层共 {len(regions)} 个连通域")
for i, cells in enumerate(regions):
    ys = [c[0] for c in cells]
    xs = [c[1] for c in cells]
    has_exit = any(layer[y, x] == EXIT for y, x in cells)
    has_stair = any(layer[y, x] == STAIR for y, x in cells)
    has_door = any(layer[y, x] == DOOR for y, x in cells)
    print(f"\n域{i+1}: {len(cells)}格, y=[{min(ys)}~{max(ys)}], x=[{min(xs)}~{max(xs)}]")
    print(f"  出口={has_exit}, 楼梯={has_stair}, 门={has_door}")

# 分析未连通域（域2）的边界墙
if len(regions) >= 2:
    region2 = set(regions[1])
    print(f"\n{'='*60}")
    print("域2（未连通）的边界墙分析:")
    boundary_walls = {}
    for y, x in region2:
        for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and layer[ny, nx] == WALL:
                key = (ny, nx)
                if key not in boundary_walls:
                    boundary_walls[key] = []
                boundary_walls[key].append((y, x))

    # 按墙的位置分组
    wall_xs = {}
    for (wy, wx), cells in boundary_walls.items():
        wall_xs.setdefault(wx, []).append(wy)

    print(f"域2边界墙涉及的列(x): {sorted(wall_xs.keys())}")
    for wx in sorted(wall_xs.keys()):
        wys = sorted(wall_xs[wx])
        print(f"  x={wx}: 墙 y=[{min(wys)}~{max(wys)}], 共{len(wys)}个墙")
        # 检查这些墙上是否有门
        doors_in_col = [y for y in range(h) if layer[y, wx] == DOOR]
        if doors_in_col:
            print(f"    该列有门: y={doors_in_col}")
        else:
            print(f"    该列无门！")
