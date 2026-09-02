"""
分析体素数据中各层的分布，确认实际楼层数
"""
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

print("=" * 60)
print("各层(z)体素分布（非空气体素数）")
print("=" * 60)
print(f"网格尺寸: {w}x{h}x{d}, voxel_size={vs}m")
print(f"建筑高度: {d * vs:.1f}m")
print()

for z in range(d):
    layer = data[z]
    non_empty = int(np.sum(layer != EMPTY))
    walls = int(np.sum(layer == WALL))
    doors = int(np.sum(layer == DOOR))
    exits = int(np.sum(layer == EXIT))
    stairs = int(np.sum(layer == STAIR))
    windows = int(np.sum(layer == WINDOW))
    columns = int(np.sum(layer == COLUMN))
    air = int(np.sum(layer == EMPTY))
    pct = non_empty / (w * h) * 100

    # 标记楼层类型
    if non_empty == 0:
        tag = "空层"
    elif pct < 10:
        tag = "稀疏层(屋顶/外部)"
    elif pct > 80:
        tag = "楼板层"
    elif stairs > 0:
        tag = "楼梯层"
    elif doors > 0 or exits > 0:
        tag = "楼层(有门)"
    else:
        tag = "楼层"

    print(f"  z={z:2d} (高度{z*vs:5.1f}m): 非空{non_empty:4d}({pct:5.1f}%) 墙{walls:3d} 门{doors:3d} 出口{exits:2d} 楼梯{stairs:2d} 窗{windows:3d} 柱{columns:3d} 空气{air:4d}  [{tag}]")

print()
print("=" * 60)
print("IFC 楼层标高与体素 z 层对应关系")
print("=" * 60)
floor_elevations = [0.0, 3.9, 7.5, 11.1, 14.4]
origin_z = -0.7  # 从 semantic_info.json
for i, elev in enumerate(floor_elevations):
    z_layer = int((elev - origin_z) / vs)
    print(f"  {i+1}F: 标高{elev}m -> 体素 z≈{z_layer} (高度{z_layer*vs:.1f}m)")
