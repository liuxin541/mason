"""检查高门的位置分布"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

DOOR = 3

# 找出所有高度=8的门位置
tall_doors = set()
for z in range(0, 8):
    doors = np.argwhere(data[z] == DOOR)
    for y, x in doors:
        # 检查是否z=0和z=7都是门
        if data[0, y, x] == DOOR and data[7, y, x] == DOOR:
            tall_doors.add((int(x), int(y)))

print(f"高度=8体素的门位置数: {len(tall_doors)}")

# 按 y 分组
by_y = {}
for x, y in tall_doors:
    if y not in by_y:
        by_y[y] = []
    by_y[y].append(x)

print("\n按 y 分布:")
for y in sorted(by_y.keys()):
    xs = sorted(by_y[y])
    print(f"  y={y}: x={min(xs)}~{max(xs)}, 共{len(xs)}个")

# 检查这些位置在 z=0 层的周围
print("\nz=0 层高门区域( y=4~8, x=28~40 ):")
for y in range(4, 9):
    row = []
    for x in range(28, 41):
        t = data[0, y, x]
        name = {0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t, '?')
        row.append(name)
    print(f"  y={y}: {''.join(row)}")

# 检查这些位置是否是出口附近
print("\n这些高门位置是否有出口相邻:")
exit_adjacent = 0
for x, y in tall_doors:
    for dy in range(-2, 3):
        for dx in range(-2, 3):
            ny, nx = y+dy, x+dx
            if 0<=ny<38 and 0<=nx<80 and data[0, ny, nx] == 4:
                exit_adjacent += 1
                break
        else:
            continue
        break
print(f"  与出口相邻的高门: {exit_adjacent}/{len(tall_doors)}")
