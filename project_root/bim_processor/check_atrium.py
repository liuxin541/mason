"""检查楼板缺口区域的上下层，判断是否为中庭"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5); version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little'); h = int.from_bytes(f.read(4), 'little'); d = int.from_bytes(f.read(4), 'little')
    f.read(13)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7

print("=" * 60)
print("楼板缺口区域上下层检查")
print("=" * 60)

# 检查 z=8,9 缺口区域 (x=14~79, y=14~33)
z_slab = 8
x1, x2 = 14, 79
y1, y2 = 14, 33

print(f"\n检查 z={z_slab} 缺口区域 (x={x1}~{x2}, y={y1}~{y2}):")
for z in [z_slab-2, z_slab-1, z_slab, z_slab+1, z_slab+2, z_slab+3]:
    if z < 0 or z >= d:
        continue
    region = data[z, y1:y2+1, x1:x2+1]
    air = int(np.sum(region == EMPTY))
    wall = int(np.sum(region == WALL))
    door = int(np.sum(region == DOOR))
    stair = int(np.sum(region == STAIR))
    total = region.size
    print(f"  z={z}: 空气={air}({air/total*100:.0f}%), 墙={wall}, 门={door}, 楼梯={stair}")

# 检查这个区域是否是中庭（上下层都是空气）
print(f"\n缺口区域垂直剖面（x=40, y=20）:")
for z in range(0, 32):
    t = data[z, 20, 40]
    name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
    print(f"  z={z}: {name}")

# 检查 z=10 缺口区域 (x=64~79, y=4~24)
print(f"\n检查 z=10 缺口区域 (x=64~79, y=4~24):")
x1, x2 = 64, 79
y1, y2 = 4, 24
for z in [8, 9, 10, 11, 12]:
    if z < 0 or z >= d:
        continue
    region = data[z, y1:y2+1, x1:x2+1]
    air = int(np.sum(region == EMPTY))
    wall = int(np.sum(region == WALL))
    total = region.size
    print(f"  z={z}: 空气={air}({air/total*100:.0f}%), 墙={wall}")
