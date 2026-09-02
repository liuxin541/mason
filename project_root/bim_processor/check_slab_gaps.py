"""检查楼板层空气缺口"""
import numpy as np

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5); version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little'); h = int.from_bytes(f.read(4), 'little'); d = int.from_bytes(f.read(4), 'little')
    f.read(13)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7

print("=" * 60)
print("楼板层空气缺口检查")
print("=" * 60)

# 检测楼板层
slab_layers = []
for z in range(d):
    ratio = np.sum(data[z] != 0) / (w * h)
    if ratio > 0.8:
        slab_layers.append(z)

print(f"楼板层: {slab_layers}")

for z in slab_layers[:8]:  # 只看前8个
    air_count = int(np.sum(data[z] == EMPTY))
    if air_count == 0:
        print(f"  z={z}: 无空气缺口 ✓")
        continue
    
    # 检查空气是否与楼梯相邻
    stair_adjacent = 0
    air_positions = np.argwhere(data[z] == EMPTY)
    for y, x in air_positions:
        for dz in [-1, 0, 1]:
            for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                nz, ny, nx = z+dz, y+dy, x+dx
                if 0 <= nz < d and 0 <= ny < h and 0 <= nx < w and data[nz, ny, nx] == STAIR:
                    stair_adjacent += 1
                    break
            else:
                continue
            break
    
    gap_count = air_count - stair_adjacent
    status = "✓" if gap_count < 20 else "⚠"
    print(f"  z={z}: 空气={air_count}, 楼梯井={stair_adjacent}, 缺口={gap_count} {status}")
    
    # 显示缺口的位置分布
    if gap_count > 20:
        gap_positions = []
        for y, x in air_positions:
            is_stair = False
            for dz in [-1, 0, 1]:
                for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                    nz, ny, nx = z+dz, y+dy, x+dx
                    if 0 <= nz < d and 0 <= ny < h and 0 <= nx < w and data[nz, ny, nx] == STAIR:
                        is_stair = True
                        break
                if is_stair:
                    break
            if not is_stair:
                gap_positions.append((y, x))
        if gap_positions:
            ys = [p[0] for p in gap_positions]
            xs = [p[1] for p in gap_positions]
            print(f"    缺口范围: x={min(xs)}~{max(xs)}, y={min(ys)}~{max(ys)}")
