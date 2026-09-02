"""检查所有 IFC 门的洞口高度和 bbox"""
import sys
sys.path.insert(0, r'D:\mason\project_root\bim_processor')
import ifcopenshell
from ifc_parser.extractor import _get_opening_bbox, _world_bbox
import ifcopenshell.geom

ifc_file = ifcopenshell.open(r'D:\mason\一号办公楼1.ifc')
settings = ifcopenshell.geom.settings()
try:
    settings.set(settings.USE_WORLD_COORDS, True)
except:
    pass

doors = ifc_file.by_type('IfcDoor')
print(f"IFC 中门总数: {len(doors)}")

tall_openings = []
all_heights = []
for i, door in enumerate(doors):
    try:
        shape = ifcopenshell.geom.create_shape(settings, door)
        bbox = _world_bbox(shape, True)
        if bbox:
            xmin, ymin, zmin, xmax, ymax, zmax = bbox
            door_h = zmax - zmin
            opening_bbox = _get_opening_bbox(door, settings, True)
            if opening_bbox:
                oxmin, oymin, ozmin, oxmax, oymax, ozmax = opening_bbox
                open_h = ozmax - ozmin
                all_heights.append(open_h)
                if open_h > 2.5:
                    tall_openings.append((i, door_h, open_h, ozmin, ozmax, oymin, oymax, oxmin, oxmax))
            else:
                all_heights.append(door_h)
                if door_h > 2.5:
                    tall_openings.append((i, door_h, 0, zmin, zmax, ymin, ymax, xmin, xmax))
    except Exception as e:
        pass

print(f"\n所有门洞口高度范围: {min(all_heights):.2f}~{max(all_heights):.2f}m")
print(f"高度>2.5m的门: {len(tall_openings)}")
for i, door_h, open_h, ozmin, ozmax, oymin, oymax, oxmin, oxmax in tall_openings[:10]:
    print(f"  门{i}: 门高={door_h:.2f}m, 洞口高={open_h:.2f}m, z={ozmin:.2f}~{ozmax:.2f}, y={oymin:.2f}~{oymax:.2f}, x={oxmin:.2f}~{oxmax:.2f}")

# 检查门的名称
print("\n门的名称(前10个):")
for i, door in enumerate(doors[:10]):
    name = getattr(door, 'Name', '未知')
    print(f"  门{i}: {name}")
