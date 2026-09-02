"""调试出口识别：在 seal_exterior 之前检查 y=0~10 的类型"""
import numpy as np
from collections import deque
import sys
sys.path.insert(0, r'D:\mason\project_root\bim_processor')
from voxelizer.voxel_grid import VoxelGrid
from ifc_parser.extractor import extract_elements
from voxelizer.builder import build_from_elements, _identify_exits_from_voxels

# 加载 IFC 并体素化（不调用 seal_exterior）
ifc_path = r'D:\mason\一号办公楼1.ifc'
print("加载 IFC...")
import ifcopenshell
ifc_file = ifcopenshell.open(ifc_path)
elements = extract_elements(ifc_file)

# 计算网格尺寸
all_verts = []
for cat, elems in elements.items():
    for e in elems:
        if e.get('verts') is not None:
            all_verts.append(e['verts'])
        if e.get('bbox') is not None:
            xmin, ymin, zmin, xmax, ymax, zmax = e['bbox']
            all_verts.append(np.array([[xmin, ymin, zmin], [xmax, ymax, zmax]]))
all_verts = np.vstack(all_verts)
vmin = all_verts.min(axis=0)
vmax = all_verts.max(axis=0)
voxel_size = 0.5
origin_offset = vmin - 1.0
grid_w = int(np.ceil((vmax[0] - origin_offset[0]) / voxel_size)) + 2
grid_h = int(np.ceil((vmax[1] - origin_offset[1]) / voxel_size)) + 2
grid_d = int(np.ceil((vmax[2] - origin_offset[2]) / voxel_size)) + 2
print(f"网格尺寸: {grid_w}x{grid_h}x{grid_d}")
print(f"origin_offset: {origin_offset}")

grid = VoxelGrid(grid_w, grid_h, grid_d, voxel_size)
build_from_elements(grid, elements, origin_shift=(-origin_offset[0], -origin_offset[1], -origin_offset[2]))

# 此时出口已识别，但还没 seal_exterior
data = grid.data
print("\n" + "=" * 60)
print("出口识别后、seal_exterior 前的状态")
print("=" * 60)

# 检查 z=2 层 y=0~10, x=30~45
z = 2
print(f"\nz={z} 层 y=0~10, x=30~45:")
for y in range(0, 11):
    row = []
    for x in range(30, 46):
        t = data[z, y, x]
        c = {0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t, '?')
        row.append(c)
    print(f"  y={y}: {''.join(row)}")

# 检查 y=5 的门是从哪个方向识别的
print("\n" + "=" * 60)
print("手动检查 y=5, x=37 的门")
print("=" * 60)
y, x = 5, 37
print(f"当前类型: {data[z, y, x]} ({'门' if data[z,y,x]==3 else '出口' if data[z,y,x]==4 else '其他'})")

# 从边界洪水填充外部空气
layer = data[z]
outside_air = np.zeros_like(layer, dtype=bool)
q = deque()
for xx in range(grid_w):
    if layer[0, xx] == 0: q.append((0, xx)); outside_air[0, xx] = True
    if layer[grid_h-1, xx] == 0: q.append((grid_h-1, xx)); outside_air[grid_h-1, xx] = True
for yy in range(grid_h):
    if layer[yy, 0] == 0: q.append((yy, 0)); outside_air[yy, 0] = True
    if layer[yy, grid_w-1] == 0: q.append((yy, grid_w-1)); outside_air[yy, grid_w-1] = True
while q:
    cy, cx = q.popleft()
    for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
        ny, nx = cy+dy, cx+dx
        if 0 <= ny < grid_h and 0 <= nx < grid_w and layer[ny, nx] == 0 and not outside_air[ny, nx]:
            outside_air[ny, nx] = True
            q.append((ny, nx))

# 检查四个方向
for dy, dx, dir_name in [(-1,0,'北'),(1,0,'南'),(0,-1,'西'),(0,1,'东')]:
    print(f"\n  向{dir_name}扫描:")
    for dist in range(1, 6):
        ny, nx = y + dy*dist, x + dx*dist
        if ny < 0 or ny >= grid_h or nx < 0 or nx >= grid_w:
            print(f"    dist={dist}: 越界")
            break
        t = layer[ny, nx]
        is_outside = outside_air[ny, nx]
        name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
        print(f"    dist={dist}: ({ny},{nx}) = {name}, 外部空气={is_outside}")
        if is_outside:
            print(f"    → 到达外部空气！")
            break
        if t not in (1, 7):  # 不是墙/窗
            print(f"    → 遇到非墙/窗，停止")
            break
