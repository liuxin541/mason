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

print('=== 楼梯(STAIR)各层位置 ===')
stair_positions_by_layer = {}
for z in range(d):
    stairs = np.argwhere(data[z] == STAIR)
    if len(stairs) > 0:
        xs = set(int(p[1]) for p in stairs)
        ys = set(int(p[0]) for p in stairs)
        stair_positions_by_layer[z] = (xs, ys)
        print(f'  z={z:2d}: {len(stairs)}个, x={sorted(xs)}, y={sorted(ys)}')

print()
print('=== 楼梯垂直连通性检查 ===')
# 检查相邻层的楼梯 x,y 是否有重叠（人员可以上下楼）
layers = sorted(stair_positions_by_layer.keys())
for i in range(len(layers) - 1):
    z1 = layers[i]
    z2 = layers[i + 1]
    xs1, ys1 = stair_positions_by_layer[z1]
    xs2, ys2 = stair_positions_by_layer[z2]
    x_overlap = xs1 & xs2
    y_overlap = ys1 & ys2
    gap = z2 - z1
    status = '连通' if x_overlap and y_overlap else '不连通'
    print(f'  z={z1} -> z={z2} (间隔{gap}层): x重叠={sorted(x_overlap)}, y重叠={sorted(y_overlap)} => {status}')

print()
print('=== 出口位置 ===')
exits = np.argwhere(data == EXIT)
if len(exits) > 0:
    zs = set(int(p[0]) for p in exits)
    xs = set(int(p[2]) for p in exits)
    ys = set(int(p[1]) for p in exits)
    print(f'  出口层: {sorted(zs)}')
    print(f'  出口x范围: {min(xs)}~{max(xs)}')
    print(f'  出口y范围: {min(ys)}~{max(ys)}')
    print(f'  出口数量: {len(exits)}')
