import sys
sys.path.insert(0, r'D:\mason\project_root\bim_processor')
from ifc_parser.extractor import extract_elements
import ifcopenshell

ifc = ifcopenshell.open(r'D:\mason\一号办公楼1.ifc')
elements = extract_elements(ifc)

doors = elements.get('doors', [])
print(f'提取到 {len(doors)} 个门')
print()
for i, door in enumerate(doors[:10]):
    bbox = door.get('bbox')
    if bbox:
        xmin, ymin, zmin, xmax, ymax, zmax = bbox
        print(f'门{i}: z=[{zmin:.1f}, {zmax:.1f}] 高度={zmax-zmin:.1f}m, x=[{xmin:.1f},{xmax:.1f}], y=[{ymin:.1f},{ymax:.1f}]')
    else:
        print(f'门{i}: 无 bbox')

# 统计所有门的 z 范围
print()
zmins = [d['bbox'][2] for d in doors if d.get('bbox')]
zmaxs = [d['bbox'][5] for d in doors if d.get('bbox')]
heights = [d['bbox'][5]-d['bbox'][2] for d in doors if d.get('bbox')]
print(f'门 zmin 范围: {min(zmins):.1f} ~ {max(zmins):.1f}')
print(f'门 zmax 范围: {min(zmaxs):.1f} ~ {max(zmaxs):.1f}')
print(f'门高度范围: {min(heights):.1f} ~ {max(heights):.1f}')
print(f'高度 > 2.2m 的门: {sum(1 for h in heights if h > 2.2)}')
