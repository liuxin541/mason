"""检查 IFC 中门的原始位置和属性"""
import ifcopenshell
import numpy as np

ifc_path = r'D:\mason\一号办公楼1.ifc'
ifc = ifcopenshell.open(ifc_path)

print("=" * 60)
print("IFC 中门的原始位置")
print("=" * 60)

doors = ifc.by_type('IfcDoor')
print(f"IFC 中门总数: {len(doors)}")

# origin_offset 和 voxel_size
origin_offset = [-10.2471, -9.6060, -0.7000]
voxel_size = 0.5

door_info = []
for door in doors:
    name = door.Name or ''
    desc = door.Description or ''
    predefined = ''
    try:
        predefined = door.PredefinedType or ''
    except AttributeError:
        pass
    
    # 获取门的位置
    if door.ObjectPlacement:
        placement = door.ObjectPlacement
        if hasattr(placement, 'RelativePlacement'):
            loc = placement.RelativePlacement.Location
            if loc:
                x, y, z = loc.Coordinates
                # 转换为体素坐标
                vx = int((x - origin_offset[0]) / voxel_size)
                vy = int((y - origin_offset[1]) / voxel_size)
                vz = int((z - origin_offset[2]) / voxel_size)
                door_info.append({
                    'name': name,
                    'desc': desc,
                    'predefined': predefined,
                    'world': (x, y, z),
                    'voxel': (vx, vy, vz)
                })

# 按楼层分组
print("\n门按体素 z 层分布:")
door_by_z = {}
for d in door_info:
    z = d['voxel'][2]
    door_by_z.setdefault(z, []).append(d)
for z in sorted(door_by_z.keys()):
    doors_z = door_by_z[z]
    print(f"\n  z={z} ({len(doors_z)}个门):")
    for d in doors_z[:5]:
        print(f"    名称='{d['name']}', 预定义='{d['predefined']}', "
              f"世界坐标=({d['world'][0]:.1f},{d['world'][1]:.1f},{d['world'][2]:.1f}), "
              f"体素=({d['voxel'][0]},{d['voxel'][1]},{d['voxel'][2]})")
    if len(doors_z) > 5:
        print(f"    ... 还有 {len(doors_z)-5} 个")

# 检查是否有外门属性
print("\n" + "=" * 60)
print("门的属性分析")
print("=" * 60)
exterior_keywords = ['exit', 'exterior', '外', '出口', '防火', '疏散']
exterior_doors = []
for d in door_info:
    text = (d['name'] + ' ' + d['desc'] + ' ' + d['predefined']).lower()
    if any(kw in text for kw in exterior_keywords):
        exterior_doors.append(d)
print(f"包含外门/出口关键词的门: {len(exterior_doors)}")
for d in exterior_doors:
    print(f"  名称='{d['name']}', 描述='{d['desc']}', 体素={d['voxel']}")

# 检查门所在的墙是否为外墙
print("\n" + "=" * 60)
print("门所在墙的属性")
print("=" * 60)
walls = ifc.by_type('IfcWall') + ifc.by_type('IfcWallStandardCase')
print(f"墙总数: {len(walls)}")
exterior_walls = []
for wall in walls:
    name = wall.Name or ''
    predefined = wall.PredefinedType or ''
    desc = wall.Description or ''
    text = (name + ' ' + predefined + ' ' + desc).lower()
    if any(kw in text for kw in ['exterior', '外', '外墙']):
        exterior_walls.append(wall)
print(f"包含外墙关键词的墙: {len(exterior_walls)}")
for wall in exterior_walls[:5]:
    print(f"  名称='{wall.Name}', 预定义='{wall.PredefinedType}'")
