"""
分析门与墙的位置关系，找到门不在墙上的根因
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

print("=" * 60)
print("分析 z=10 层门的位置和周围环境")
print("=" * 60)

z = 10
doors = np.argwhere(data[z] == DOOR)
print(f"z={z} 层门总数: {len(doors)}")

# 找不在墙上的门
not_on_wall = []
for y, x in doors:
    has_wall = False
    for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
        ny, nx = y + dy, x + dx
        if 0 <= ny < h and 0 <= nx < w and data[z, ny, nx] in (WALL, COLUMN):
            has_wall = True
            break
    if not has_wall:
        not_on_wall.append((y, x))

print(f"不在墙上的门: {len(not_on_wall)}")
print()

# 分析前5个不在墙上的门，看周围5x5区域
print("不在墙上的门周围5x5区域（0=空气,1=墙,3=门,6=柱,7=窗）:")
for i, (y, x) in enumerate(not_on_wall[:5]):
    print(f"\n门{i}: (y={y}, x={x})")
    for dy in range(-2, 3):
        row = []
        for dx in range(-2, 3):
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w:
                row.append(str(int(data[z, ny, nx])))
            else:
                row.append('-')
        print(f"  {' '.join(row)}")

print()
print("=" * 60)
print("分析门的分布：门是否集中在某些区域")
print("=" * 60)

# 看门的x,y分布
xs = [int(x) for y, x in doors]
ys = [int(y) for y, x in doors]
print(f"门 x 范围: {min(xs)}~{max(xs)}")
print(f"门 y 范围: {min(ys)}~{max(ys)}")

# 看墙的分布
walls = np.argwhere(data[z] == WALL)
wxs = [int(x) for y, x in walls]
wys = [int(y) for y, x in walls]
print(f"墙 x 范围: {min(wxs)}~{max(wxs)}")
print(f"墙 y 范围: {min(wys)}~{max(wys)}")

# 看门和墙是否在同一区域
print()
print("门的x坐标分布（每10格统计）:")
for x_start in range(0, w, 10):
    count = sum(1 for x in xs if x_start <= x < x_start + 10)
    if count > 0:
        print(f"  x=[{x_start:2d},{x_start+9:2d}): {count} 个门")

print()
print("墙的x坐标分布（每10格统计）:")
for x_start in range(0, w, 10):
    count = sum(1 for x in wxs if x_start <= x < x_start + 10)
    if count > 0:
        print(f"  x=[{x_start:2d},{x_start+9:2d}): {count} 个墙")
