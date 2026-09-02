"""调试门高度问题"""
import sys
sys.path.insert(0, r'D:\mason\project_root\bim_processor')
import ifcopenshell
from ifc_parser.extractor import extract_elements, _get_opening_bbox, _world_bbox
import ifcopenshell.geom

ifc_file = ifcopenshell.open(r'D:\mason\一号办公楼1.ifc')
settings = ifcopenshell.geom.settings()
try:
    settings.set(settings.USE_WORLD_COORDS, True)
except:
    pass

doors = ifc_file.by_type('IfcDoor')
print(f"IFC 中门总数: {len(doors)}")

# 检查前10个门的 bbox
tall_doors = []
for i, door in enumerate(doors[:20]):
    try:
        shape = ifcopenshell.geom.create_shape(settings, door)
        bbox = _world_bbox(shape, True)
        if bbox:
            xmin, ymin, zmin, xmax, ymax, zmax = bbox
            height = zmax - zmin
            opening_bbox = _get_opening_bbox(door, settings, True)
            if opening_bbox:
                oxmin, oymin, ozmin, oxmax, oymax, ozmax = opening_bbox
                oheight = ozmax - ozmin
                if height > 2.5 or oheight > 2.5:
                    tall_doors.append((i, height, oheight, zmin, ozmin))
                    print(f"  门{i}: 门bbox高={height:.2f}m(z={zmin:.2f}~{zmax:.2f}), 洞口bbox高={oheight:.2f}m(z={ozmin:.2f}~{ozmax:.2f})")
    except Exception as e:
        print(f"  门{i}: 错误 {e}")

print(f"\n高门(>2.5m)数量(前20个中): {len(tall_doors)}")

# 检查 F1 层的门（z 范围约 -0.7 ~ 3.3m）
print("\nF1 层门的 z 范围:")
f1_doors = []
for i, door in enumerate(doors):
    try:
        shape = ifcopenshell.geom.create_shape(settings, door)
        bbox = _world_bbox(shape, True)
        if bbox:
            xmin, ymin, zmin, xmax, ymax, zmax = bbox
            if zmin < 3.5 and zmax > 0:  # F1 层
                opening_bbox = _get_opening_bbox(door, settings, True)
                if opening_bbox:
                    oxmin, oymin, ozmin, oxmax, oymax, ozmax = opening_bbox
                    f1_doors.append((i, zmax-zmin, ozmax-ozmin, ozmin, ozmax))
    except:
        pass

print(f"  F1 层门数量: {len(f1_doors)}")
if f1_doors:
    heights = [d[2] for d in f1_doors]  # 洞口高度
    print(f"  洞口高度范围: {min(heights):.2f}~{max(heights):.2f}m")
    print(f"  高度>2.2m的门: {sum(1 for h in heights if h > 2.2)}")
    print(f"  高度>3m的门: {sum(1 for h in heights if h > 3)}")
    # 显示前5个高门
    tall = [d for d in f1_doors if d[2] > 2.2][:5]
    for i, door_h, open_h, ozmin, ozmax in tall:
        print(f"    门{i}: 洞口高={open_h:.2f}m, z={ozmin:.2f}~{ozmax:.2f}")
