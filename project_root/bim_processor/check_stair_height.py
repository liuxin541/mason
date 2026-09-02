"""检查 IFC 楼梯实际高度和体素化后楼梯分布"""
import sys
sys.path.insert(0, r'D:\mason\project_root\bim_processor')
import ifcopenshell
from ifc_parser.extractor import _world_bbox
import ifcopenshell.geom
import numpy as np

ifc_file = ifcopenshell.open(r'D:\mason\一号办公楼1.ifc')
settings = ifcopenshell.geom.settings()
try:
    settings.set(settings.USE_WORLD_COORDS, True)
except:
    pass

stairs = ifc_file.by_type('IfcStair') + ifc_file.by_type('IfcStairFlight')
print(f"IFC 中楼梯数量: {len(stairs)}")
print()

origin_z = -0.7000
voxel_size = 0.5

for i, stair in enumerate(stairs):
    try:
        shape = ifcopenshell.geom.create_shape(settings, stair)
        bbox = _world_bbox(shape, True)
        if bbox:
            xmin, ymin, zmin, xmax, ymax, zmax = bbox
            vzmin = int((zmin - origin_z) / voxel_size)
            vzmax = int((zmax - origin_z) / voxel_size)
            name = getattr(stair, 'Name', '未知')
            print(f"楼梯{i}({name}):")
            print(f"  世界z: {zmin:.2f}~{zmax:.2f}m (高{zmax-zmin:.2f}m)")
            print(f"  体素z: {vzmin}~{vzmax}")
    except Exception as e:
        print(f"楼梯{i}: 错误 {e}")

# 检查体素化后楼梯分布
print("\n体素化后楼梯分布:")
with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

STAIR = 5
for z in range(36):
    cnt = np.sum(data[z] == STAIR)
    if cnt > 0:
        print(f"  z={z}: {cnt}个楼梯")

# 检查 F4 层(z=25~28)的楼梯位置
print("\nF4层(z=25~28)楼梯位置:")
for z in range(25, 29):
    positions = np.argwhere(data[z] == STAIR)
    if len(positions) > 0:
        print(f"  z={z}: {[(int(x),int(y)) for y,x in positions]}")
    else:
        print(f"  z={z}: 无楼梯")
