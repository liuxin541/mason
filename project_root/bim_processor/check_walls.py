"""
检查分隔墙位置和门洞是否正确开洞
"""
import numpy as np
import struct

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5)
    ver = struct.unpack('<H', f.read(2))[0]
    w, h, d = struct.unpack('<III', f.read(12))
    vs = struct.unpack('<f', f.read(4))[0]
    f.read(9)
    data = np.frombuffer(f.read(w * h * d), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, FUEL, DOOR, EXIT, STAIR, COLUMN, WINDOW = range(8)

z = 10
layer = data[z]

print("=" * 60)
print(f"z={z} 层墙体分布（每列的墙数量）")
print("=" * 60)

# 统计每列(x)的墙数量
wall_by_x = np.sum(layer == WALL, axis=0)
for x in range(w):
    if wall_by_x[x] > 0:
        print(f"  x={x:2d}: {wall_by_x[x]:2d} 墙", end="")
        if wall_by_x[x] >= 10:
            print("  <-- 可能是纵向分隔墙")
        else:
            print()

print()
print("=" * 60)
print("检查可能的分隔墙上是否有门")
print("=" * 60)

# 找墙数量 >= 10 的列（可能是纵向分隔墙）
separator_cols = [x for x in range(w) if wall_by_x[x] >= 10]
print(f"可能的分隔墙列: {separator_cols}")

for x in separator_cols:
    walls_in_col = np.argwhere(layer[:, x] == WALL).flatten()
    doors_in_col = np.argwhere(layer[:, x] == DOOR).flatten()
    print(f"\n  x={x}: 墙 y={sorted(walls_in_col.tolist())}")
    print(f"         门 y={sorted(doors_in_col.tolist())}")
    # 检查墙是否被门打断
    if len(doors_in_col) > 0:
        print(f"         门上有门，可能连通")
    else:
        print(f"         无门！墙完全封闭，不连通")

print()
print("=" * 60)
print("检查门的 bbox 是否覆盖墙（门范围内是否有墙）")
print("=" * 60)

doors = np.argwhere(layer == DOOR)
door_covers_wall = 0
door_no_wall = 0
for y, x in doors:
    # 检查门的3x3范围内是否有墙
    has_wall = False
    for dy in range(-1, 2):
        for dx in range(-1, 2):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and layer[ny, nx] == WALL:
                has_wall = True
                break
        if has_wall:
            break
    if has_wall:
        door_covers_wall += 1
    else:
        door_no_wall += 1

print(f"门周围有墙: {door_covers_wall}")
print(f"门周围无墙: {door_no_wall} (在房间中间)")
