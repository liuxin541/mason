"""检查 F1 层门的实际体素分布"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

DOOR = 3

# 统计 F1 层(z=0~7)每个(x,y)位置的门高度
door_heights = {}
for z in range(0, 8):
    doors = np.argwhere(data[z] == DOOR)
    for y, x in doors:
        key = (int(x), int(y))
        if key not in door_heights:
            door_heights[key] = [z, z]
        else:
            door_heights[key][1] = z

# 按高度分组
height_groups = {}
for key, (zmin, zmax) in door_heights.items():
    h = zmax - zmin + 1
    if h not in height_groups:
        height_groups[h] = []
    height_groups[h].append((key, zmin, zmax))

print("F1 层门高度分布:")
for h in sorted(height_groups.keys()):
    positions = height_groups[h]
    print(f"  高度={h}体素({h*0.5:.1f}m): {len(positions)}个门")
    if h >= 6:
        print(f"    示例(前5个): {[(p[0], p[1], p[2]) for p in positions[:5]]}")

# 检查一个高门的垂直剖面
if 8 in height_groups:
    pos = height_groups[8][0][0]
    x, y = pos
    print(f"\n高门位置({x},{y})垂直剖面:")
    for z in range(0, 10):
        t = data[z, y, x]
        name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
        print(f"  z={z}: {name}")
