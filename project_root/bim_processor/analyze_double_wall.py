"""
深入分析双墙结构和外门位置
为修复方案提供依据
"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5); version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little'); h = int.from_bytes(f.read(4), 'little'); d = int.from_bytes(f.read(4), 'little')
    f.read(13)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7

print("=" * 60)
print("双墙结构深入分析")
print("=" * 60)

# 分析 z=2 层（出口所在层）的北侧墙体布局
z = 2
print(f"\nz={z} 层北侧墙体布局（y=0~10）:")
for y in range(11):
    row = data[z, y, :]
    walls = np.where(row == WALL)[0]
    doors = np.where(row == DOOR)[0]
    exits = np.where(row == EXIT)[0]
    air = np.where(row == EMPTY)[0]
    print(f"  y={y}: 墙={len(walls)}个(x={min(walls) if len(walls)>0 else '-'}-{max(walls) if len(walls)>0 else '-'}), "
          f"门={len(doors)}个, 出口={len(exits)}个, 空气={len(air)}个")

# 检查 x=37 列（出口中间位置）的北侧剖面
print(f"\nx=37 列北侧剖面（y=0~10, z={z}）:")
for y in range(11):
    t = data[z, y, 37]
    name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
    print(f"  y={y}: {name}({t})")

# 检查所有门的位置，找出可能的外门
print("\n" + "=" * 60)
print("所有门的位置分布（z=2层）")
print("=" * 60)
doors = np.argwhere(data[z] == DOOR)
print(f"门总数: {len(doors)}")
# 按 y 分组
door_by_y = {}
for y, x in doors:
    door_by_y.setdefault(y, []).append(x)
for y in sorted(door_by_y.keys()):
    xs = door_by_y[y]
    print(f"  y={y}: {len(xs)}个门, x={min(xs)}~{max(xs)}")

# 检查建筑最外层墙的位置
print("\n" + "=" * 60)
print("建筑最外层墙位置（从边界扫描）")
print("=" * 60)
z = 2
# 北侧：从 y=0 往南找第一个非空气
for x in [10, 20, 30, 37, 50, 60, 70]:
    for y in range(h):
        if data[z, y, x] != EMPTY:
            t = data[z, y, x]
            name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
            print(f"  x={x}: 最北非空气在 y={y}, 类型={name}")
            break

# 南侧
print("\n南侧（从 y=h-1 往北）:")
for x in [10, 20, 30, 37, 50, 60, 70]:
    for y in range(h-1, -1, -1):
        if data[z, y, x] != EMPTY:
            t = data[z, y, x]
            name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
            print(f"  x={x}: 最南非空气在 y={y}, 类型={name}")
            break

# 检查 IFC 语义信息中的门属性
print("\n" + "=" * 60)
print("IFC 语义信息中的门属性")
print("=" * 60)
import json
with open(r'D:\mason\ifc_output\semantic_info.json', 'r', encoding='utf-8') as f:
    semantic = json.load(f)
print("语义信息键:", list(semantic.keys()))
if 'doors' in semantic:
    print(f"门数量: {len(semantic['doors'])}")
    for door in semantic['doors'][:5]:
        print(f"  {door}")
