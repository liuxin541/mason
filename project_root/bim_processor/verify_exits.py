"""验证新出口识别结果"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5); version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little'); h = int.from_bytes(f.read(4), 'little'); d = int.from_bytes(f.read(4), 'little')
    f.read(13)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7

print("=" * 60)
print("新出口识别结果验证")
print("=" * 60)

exit_positions = np.argwhere(data == EXIT)
print(f"出口总数: {len(exit_positions)}")
print(f"出口 z 范围: {exit_positions[:,0].min()}~{exit_positions[:,0].max()}")
print(f"出口 x 范围: {exit_positions[:,2].min()}~{exit_positions[:,2].max()}")
print(f"出口 y 范围: {exit_positions[:,1].min()}~{exit_positions[:,1].max()}")

# 按 z 层统计
print("\n出口按楼层分布:")
exit_by_z = {}
for z, y, x in exit_positions:
    exit_by_z.setdefault(z, []).append((x, y))
for z in sorted(exit_by_z.keys()):
    positions = exit_by_z[z]
    xs = [p[0] for p in positions]
    ys = [p[1] for p in positions]
    print(f"  z={z}: {len(positions)}个出口, x={min(xs)}~{max(xs)}, y={min(ys)}~{max(ys)}")

# 检查出口是否在建筑最外层轮廓
print("\n出口是否在建筑最外层轮廓:")
for z in sorted(exit_by_z.keys())[:4]:
    layer = data[z]
    non_empty = layer != 0
    # 从边界洪水填充空气
    outside_air = np.zeros((h, w), dtype=bool)
    q = deque()
    for x in range(w):
        if layer[0, x] == EMPTY: q.append((0, x)); outside_air[0, x] = True
        if layer[h-1, x] == EMPTY: q.append((h-1, x)); outside_air[h-1, x] = True
    for y in range(h):
        if layer[y, 0] == EMPTY: q.append((y, 0)); outside_air[y, 0] = True
        if layer[y, w-1] == EMPTY: q.append((y, w-1)); outside_air[y, w-1] = True
    while q:
        cy, cx = q.popleft()
        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny, nx = cy+dy, cx+dx
            if 0 <= ny < h and 0 <= nx < w and layer[ny, nx] == EMPTY and not outside_air[ny, nx]:
                outside_air[ny, nx] = True
                q.append((ny, nx))
    # 最外层轮廓
    outline = np.zeros((h, w), dtype=bool)
    for y in range(h):
        for x in range(w):
            if non_empty[y, x]:
                for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                    ny, nx = y+dy, x+dx
                    if 0 <= ny < h and 0 <= nx < w and outside_air[ny, nx]:
                        outline[y, x] = True
                        break
    exits_on_outline = sum(1 for x, y in exit_by_z[z] if outline[y, x])
    total = len(exit_by_z[z])
    print(f"  z={z}: 出口在最外层轮廓上 {exits_on_outline}/{total} ({exits_on_outline/total*100:.0f}%)")

# 检查出口外侧体素类型
print("\n出口外侧体素类型:")
outside_types = {}
for z, y, x in exit_positions:
    for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
        ny, nx = y+dy, x+dx
        if 0 <= ny < h and 0 <= nx < w:
            t = data[z, ny, nx]
            outside_types[t] = outside_types.get(t, 0) + 1
type_names = {0:'空气', 1:'墙', 3:'门', 4:'出口', 5:'楼梯', 7:'窗'}
for t, count in sorted(outside_types.items()):
    print(f"  {type_names.get(t, f'类型{t}')}: {count}次")

# 检查出口北侧是否有墙
print("\n出口北侧墙体检查（抽样）:")
z_sample = sorted(exit_by_z.keys())[0]
for x, y in exit_by_z[z_sample][:3]:
    wall_count = 0
    for ny in range(y-1, -1, -1):
        if data[z_sample, ny, x] == WALL:
            wall_count += 1
        else:
            break
    print(f"  出口 at (x={x}, y={y}, z={z_sample}): 向北连续墙={wall_count}层")
