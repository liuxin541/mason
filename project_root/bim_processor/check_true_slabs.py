"""用正确阈值检查真正楼板层，以及F4楼梯问题"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7
d, h, w = data.shape

print("【各层非空气占比】")
true_slabs = []
for z in range(d):
    non_empty = np.sum(data[z] != EMPTY)
    ratio = non_empty / (w*h)
    marker = " ← 楼板层(>80%)" if ratio > 0.80 else ""
    if ratio > 0.80:
        true_slabs.append(z)
    if z < 36:
        print(f"  z={z:2d}: {ratio:.1%}{marker}")

print(f"\n真正楼板层(>80%): {true_slabs}")

print("\n【真正楼板层的门/窗穿透检查】")
for z in true_slabs:
    doors = np.sum(data[z] == DOOR)
    wins = np.sum(data[z] == WINDOW)
    exits = np.sum(data[z] == EXIT)
    stairs = np.sum(data[z] == STAIR)
    status = []
    if doors > 0: status.append(f"{doors}门⚠")
    if wins > 0: status.append(f"{wins}窗⚠")
    if exits > 0: status.append(f"{exits}出口")
    if stairs > 0: status.append(f"{stairs}楼梯(正常)")
    print(f"  z={z}: {', '.join(status) if status else '无穿透✓'}")

print("\n【F4楼梯检查】")
# F4 z=25~28
for z in range(25, 29):
    stairs = np.sum(data[z] == STAIR)
    print(f"  z={z}: 楼梯={stairs}")
print(f"  楼梯总z范围: {np.where(np.any(data==STAIR, axis=(1,2)))[0]}")
print("  F4(z=25~28)无楼梯，楼梯只到z=24(F3层顶部)")

print("\n【z=10,17,24门的位置（楼板上方第一层）】")
for z in [10, 17, 24]:
    doors = np.argwhere(data[z] == DOOR)
    if len(doors) > 0:
        print(f"  z={z}: {[(int(x),int(y)) for y,x in doors]}")
        for y, x in doors:
            below = data[z-1, y, x]
            above = data[z+1, y, x]
            name = {0:'空',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}
            print(f"    ({x},{y}): 下层z={z-1}={name.get(below,below)}, 上层z={z+1}={name.get(above,above)}")
