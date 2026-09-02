"""检查真正外门位置的体素类型"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5); version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little'); h = int.from_bytes(f.read(4), 'little'); d = int.from_bytes(f.read(4), 'little')
    f.read(13)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7

print("=" * 60)
print("真正外门位置（IFC: y=6~7, x=33~40, z=1）的体素类型")
print("=" * 60)

# 检查 z=1~7 层 y=4~10, x=30~45 区域
for z in [1, 2, 3, 5, 7]:
    print(f"\nz={z} 层 y=4~10, x=30~45:")
    for y in range(4, 11):
        row = []
        for x in range(30, 46):
            t = data[z, y, x]
            c = {0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t, '?')
            row.append(c)
        print(f"  y={y}: {''.join(row)}")

# 统计 y=6~7 区域的门/出口数量
print("\n" + "=" * 60)
print("y=6~7 区域门/出口统计")
print("=" * 60)
for z in range(0, 10):
    door_count = int(np.sum(data[z, 5:9, 30:45] == DOOR))
    exit_count = int(np.sum(data[z, 5:9, 30:45] == EXIT))
    wall_count = int(np.sum(data[z, 5:9, 30:45] == WALL))
    print(f"  z={z}: 门={door_count}, 出口={exit_count}, 墙={wall_count}")

# 检查 y=5 区域（当前出口位置）
print("\n" + "=" * 60)
print("y=5 区域（当前出口位置）统计")
print("=" * 60)
for z in range(0, 10):
    door_count = int(np.sum(data[z, 5, 30:45] == DOOR))
    exit_count = int(np.sum(data[z, 5, 30:45] == EXIT))
    wall_count = int(np.sum(data[z, 5, 30:45] == WALL))
    print(f"  z={z}: 门={door_count}, 出口={exit_count}, 墙={wall_count}")
