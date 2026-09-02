"""检查高门区域是否有楼梯，以及原始体素化（无修复）的门高度"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

DOOR, STAIR = 3, 5

# 检查高门区域的楼梯
print("高门区域的楼梯分布:")
tall_door_regions = [
    ("北侧入口 y=6~8, x=31~44", 6, 8, 31, 44),
    ("中部 y=14~22, x=3~72", 14, 22, 3, 72),
]
for name, y1, y2, x1, x2 in tall_door_regions:
    stairs = 0
    for z in range(0, 8):
        for y in range(y1, y2+1):
            for x in range(x1, x2+1):
                if data[z, y, x] == STAIR:
                    stairs += 1
    print(f"  {name}: 楼梯={stairs}个")

# 检查高门位置的垂直剖面，看看是否是楼梯扩展导致的
print("\n高门位置(50,18)垂直剖面:")
for z in range(0, 12):
    t = data[z, 18, 50]
    name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
    print(f"  z={z}: {name}")

# 检查 _fill_bbox 是否会把 bbox 填充到整个楼层
# 方法：检查门的 bbox 在体素化前的 z 范围
# 由于无法直接检查，我们检查高门位置是否在所有 z 层都是门
print("\n高门位置(50,18)所有层类型:")
for z in range(36):
    t = data[z, 18, 50]
    if t != 1:  # 只显示非墙
        name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
        print(f"  z={z}: {name}")
