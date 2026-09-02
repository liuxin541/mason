"""深入检查出口位置"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5); version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little'); h = int.from_bytes(f.read(4), 'little'); d = int.from_bytes(f.read(4), 'little')
    f.read(13)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7

print("=" * 60)
print("出口位置深入检查")
print("=" * 60)

# 检查 x=74, y=14 位置（z=0~29 都有出口）
print("\n1. x=74, y=14 位置的垂直剖面:")
for z in range(0, 32):
    t = data[z, 14, 74]
    name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
    print(f"  z={z}: {name}({t})")

# 检查 x=74, y=14 位置的东侧（x=75~79）
print("\n2. x=74, y=14 位置东侧（x=75~79, z=2）:")
for x in range(74, 80):
    t = data[2, 14, x]
    name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
    print(f"  x={x}: {name}({t})")

# 检查 z=2 层 x=70~79, y=10~20 区域
print("\n3. z=2 层 x=70~79, y=10~20 区域:")
for y in range(10, 21):
    row = []
    for x in range(70, 80):
        t = data[2, y, x]
        c = {0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t, '?')
        row.append(c)
    print(f"  y={y}: {''.join(row)}")

# 检查 z=2 层出口的具体位置
print("\n4. z=2 层出口位置:")
exits = np.argwhere(data[2] == EXIT)
for y, x in exits:
    print(f"  (x={x}, y={y})")

# 检查这些出口的东侧是什么
print("\n5. z=2 层出口东侧体素:")
for y, x in exits:
    east = []
    for dx in range(1, 6):
        nx = x + dx
        if nx >= w:
            east.append('边界')
            break
        t = data[2, y, nx]
        name = {0:'空',1:'墙',3:'门',4:'出',5:'楼',7:'窗'}.get(t, '?')
        east.append(f'{name}')
    print(f"  出口(x={x},y={y}) 东侧: {' '.join(east)}")
