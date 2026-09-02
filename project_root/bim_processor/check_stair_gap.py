"""检查楼梯顶部到F4层的断层区域"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

STAIR, EMPTY, WALL, DOOR, EXIT = 5, 0, 1, 3, 4

# 找到楼梯的 x,y 位置
stair_positions = set()
for z in range(20, 25):
    for y, x in np.argwhere(data[z] == STAIR):
        stair_positions.add((int(x), int(y)))

print(f"楼梯顶部(z=20~24)的位置数: {len(stair_positions)}")
print(f"位置: {sorted(stair_positions)[:20]}...")

# 检查这些位置在 z=24~28 的类型
print("\n楼梯位置在 z=24~28 的类型:")
for z in range(24, 29):
    types = {}
    for x, y in stair_positions:
        t = data[z, y, x]
        types[t] = types.get(t, 0) + 1
    type_names = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}
    print(f"  z={z}: {', '.join(f'{type_names.get(k,k)}={v}' for k,v in sorted(types.items()))}")

# 检查 z=24,25 的详细情况
print("\nz=24 楼梯位置详细:")
for x, y in sorted(stair_positions)[:10]:
    t = data[24, y, x]
    name = {0:'空',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, str(t))
    print(f"  ({x},{y}): {name}")

print("\nz=25 楼梯位置详细:")
for x, y in sorted(stair_positions)[:10]:
    t = data[25, y, x]
    name = {0:'空',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, str(t))
    print(f"  ({x},{y}): {name}")

# 检查 F4 层(z=25~28)的整体布局
print("\nF4层(z=26)非空气占比:", np.sum(data[26] != EMPTY) / (38*80))
print("F4层(z=26)楼梯数:", np.sum(data[26] == STAIR))
print("F4层(z=26)空气数:", np.sum(data[26] == EMPTY))
