"""检查门 bbox 的实际 z 范围和体素索引"""
import sys
sys.path.insert(0, r'D:\mason\project_root\bim_processor')
import ifcopenshell
from ifc_parser.extractor import _get_opening_bbox, _world_bbox
import ifcopenshell.geom
import math

ifc_file = ifcopenshell.open(r'D:\mason\一号办公楼1.ifc')
settings = ifcopenshell.geom.settings()
try:
    settings.set(settings.USE_WORLD_COORDS, True)
except:
    pass

origin_shift = [-10.2471, -9.6060, -0.7000]
voxel_size = 0.5
OPENING_PAD = 1.5
pad = OPENING_PAD * voxel_size

doors = ifc_file.by_type('IfcDoor')
print("F1 层门的 bbox z 范围和体素索引:")
print(f"origin_shift z = {origin_shift[2]}, voxel_size = {voxel_size}, pad = {pad}")
print()

f1_doors = []
for i, door in enumerate(doors):
    try:
        shape = ifcopenshell.geom.create_shape(settings, door)
        bbox = _world_bbox(shape, True)
        if bbox:
            xmin, ymin, zmin, xmax, ymax, zmax = bbox
            if zmin < 3.5 and zmax > -1:  # F1 层
                opening_bbox = _get_opening_bbox(door, settings, True)
                if opening_bbox:
                    oxmin, oymin, ozmin, oxmax, oymax, ozmax = opening_bbox
                    # 计算体素索引
                    vz_start = max(0, int(math.floor((ozmin - pad - origin_shift[2]) / voxel_size)))
                    vz_end = min(35, int(math.floor((ozmax + pad - origin_shift[2]) / voxel_size)))
                    f1_doors.append((i, ozmin, ozmax, vz_start, vz_end))
                    if len(f1_doors) <= 10:
                        print(f"  门{i}: 洞口z={ozmin:.2f}~{ozmax:.2f}m (高{ozmax-ozmin:.2f}m), 体素z={vz_start}~{vz_end} (高{vz_end-vz_start+1}体素)")
    except:
        pass

print(f"\nF1 层门总数: {len(f1_doors)}")
if f1_doors:
    vz_starts = [d[3] for d in f1_doors]
    vz_ends = [d[4] for d in f1_doors]
    print(f"体素 z 起始范围: {min(vz_starts)}~{max(vz_starts)}")
    print(f"体素 z 结束范围: {min(vz_ends)}~{max(vz_ends)}")
    print(f"最大门高度(体素): {max(d[4]-d[3]+1 for d in f1_doors)}")
    tall = [d for d in f1_doors if d[4]-d[3]+1 >= 6]
    print(f"高度>=6体素的门: {len(tall)}")
