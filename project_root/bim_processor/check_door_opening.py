"""
检查 IFC 中门与洞口(IfcOpeningElement)的关系
"""
import sys
sys.path.insert(0, r'D:\mason\project_root\bim_processor')
from ifc_parser.loader import load_ifc

ifc = load_ifc(r'D:\mason\一号办公楼1.ifc')

doors = ifc.by_type('IfcDoor')
print(f"门总数: {len(doors)}")
print()

# 检查前5个门的洞口关系
for i, door in enumerate(doors[:5]):
    print(f"门 {i}: {door.Name if hasattr(door, 'Name') else 'unnamed'}")

    # IFC2X3: IfcDoor.FillsVoids -> IfcRelFillsElement
    # IfcRelFillsElement: RelatedBuildingElement=门, RelatingOpeningElement=洞口
    has_opening = False
    if hasattr(door, 'FillsVoids') and door.FillsVoids:
        for rel in door.FillsVoids:
            opening = rel.RelatingOpeningElement
            print(f"  洞口类型: {opening.is_a()}")
            print(f"  洞口名: {opening.Name if hasattr(opening, 'Name') else 'unnamed'}")
            has_opening = True

            # 洞口与墙的关系: IfcOpeningElement.VoidsElements -> IfcRelVoidsElement
            if hasattr(opening, 'VoidsElements') and opening.VoidsElements:
                for void_rel in opening.VoidsElements:
                    wall = void_rel.RelatingBuildingElement
                    print(f"  所属墙: {wall.is_a()} - {wall.Name if hasattr(wall, 'Name') else 'unnamed'}")

    if not has_opening:
        print(f"  无洞口关系（直接放置在墙上？）")

    print()

# 统计有多少门有洞口关系
with_opening = 0
for door in doors:
    if hasattr(door, 'FillsVoids') and door.FillsVoids:
        with_opening += 1
print(f"有洞口关系的门: {with_opening}/{len(doors)}")
