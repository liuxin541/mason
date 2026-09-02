"""验证并修复上层房间连通性"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5); version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little'); h = int.from_bytes(f.read(4), 'little'); d = int.from_bytes(f.read(4), 'little')
    f.read(13)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7
passable_types = {EMPTY, DOOR, EXIT, STAIR}

print("=" * 60)
print("当前楼层连通性")
print("=" * 60)

# 活动楼层范围
floor_ranges = [(0,7), (11,15), (18,22), (25,28)]
floor_names = ['F1', 'F2', 'F3', 'F4']

for idx, (z_start, z_end) in enumerate(floor_ranges):
    max_components = 0
    max_z = z_start
    all_sizes = []
    for z in range(z_start, z_end+1):
        if z >= d:
            continue
        layer = data[z]
        passable = np.isin(layer, list(passable_types))
        visited = np.zeros_like(passable, dtype=bool)
        components = 0
        sizes = []
        for y in range(h):
            for x in range(w):
                if passable[y, x] and not visited[y, x]:
                    components += 1
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
        if components > max_components:
            max_components = components
            max_z = z
            all_sizes = sizes
    print(f"\n{floor_names[idx]} (z={z_start}~{z_end}): 最多 {max_components} 连通域 (z={max_z})")
    if max_components > 1:
        print(f"  各域大小: {sorted(all_sizes, reverse=True)}")
        # 找小连通域的位置
        layer = data[max_z]
        passable = np.isin(layer, list(passable_types))
        visited = np.zeros_like(passable, dtype=bool)
        for y in range(h):
            for x in range(w):
                if passable[y, x] and not visited[y, x]:
                    comp = []
                    q = deque([(y, x)])
                    visited[y, x] = True
                    while q:
                        cy, cx = q.popleft()
                        comp.append((cy, cx))
                        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                            ny, nx = cy+dy, cx+dx
                            if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
                                visited[ny, nx] = True
                                q.append((ny, nx))
                    if len(comp) < 100:
                        ys = [p[0] for p in comp]
                        xs = [p[1] for p in comp]
                        print(f"  小连通域: {len(comp)}体素, 中心(x={np.mean(xs):.0f}, y={np.mean(ys):.0f}), 范围 x={min(xs)}~{max(xs)}, y={min(ys)}~{max(ys)}")
                        # 找与主连通域之间最薄的墙
                        for cy, cx in comp:
                            for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                                ny, nx = cy+dy, cx+dx
                                if 0 <= ny < h and 0 <= nx < w and layer[ny, nx] == WALL:
                                    # 检查墙另一侧是否是主连通域（大区域）
                                    for dy2, dx2 in [(-1,0),(1,0),(0,-1),(0,1)]:
                                        ny2, nx2 = ny+dy2, nx+dx2
                                        if 0 <= ny2 < h and 0 <= nx2 < w and passable[ny2, nx2] and (ny2, nx2) not in comp:
                                            print(f"    可开门位置: 墙 at (x={nx}, y={ny}), 另一侧 (x={nx2}, y={ny2})")
                                            break
                                    break
                            else:
                                continue
                            break
