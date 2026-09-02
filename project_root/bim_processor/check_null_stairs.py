"""检查 NULL 楼梯的属性，以及 fix_stair_vertical_connectivity 逻辑"""
import sys
sys.path.insert(0, r'D:\mason\project_root\bim_processor')
import ifcopenshell

ifc_file = ifcopenshell.open(r'D:\mason\一号办公楼1.ifc')

stairs = ifc_file.by_type('IfcStair') + ifc_file.by_type('IfcStairFlight')
print("所有楼梯的属性:")
for i, stair in enumerate(stairs):
    name = getattr(stair, 'Name', '未知')
    has_rep = hasattr(stair, 'Representation') and stair.Representation is not None
    # 检查是否有楼梯段作为部分
    parts = []
    if hasattr(stair, 'IsDecomposedBy'):
        for rel in stair.IsDecomposedBy:
            for part in rel.RelatedObjects:
                parts.append(part.is_a())
    print(f"  楼梯{i}: {name}, 有几何={has_rep}, 部分={parts}")

# 检查 fix_stair_vertical_connectivity 函数
print("\nfix_stair_vertical_connectivity 函数:")
with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'r', encoding='utf-8') as f:
    content = f.read()

import re
match = re.search(r'def fix_stair_vertical_connectivity.*?(?=\ndef |\Z)', content, re.DOTALL)
if match:
    print(match.group()[:2000])
