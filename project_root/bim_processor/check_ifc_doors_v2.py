"""检查 IFC 中门的世界坐标"""
import ifcopenshell
import ifcopenshell.util
import ifcopenshell.util.placement
import numpy as np

ifc_path = r'D:\mason\一号办公楼1.ifc'
ifc = ifcopenshell.open(ifc_path)

origin_offset = [-10.2471, -9.6060, -0.7000]
voxel_size = 0.5

doors = ifc.by_type('IfcDoor')
print(f"IFC 中门总数: {len(doors)}")

door_world_positions = []
for door in doors:
    name = door.Name or ''
    # 获取世界坐标矩阵
    if door.ObjectPlacement:
        matrix = ifcopenshell.util.placement.get_local_placement(door.ObjectPlacement)
        # 矩阵的最后一列前三个是平移（世界坐标）
        world_x, world_y, world_z = matrix[0,3], matrix[1,3], matrix[2,3]
        vx = int((world_x - origin_offset[0]) / voxel_size)
        vy = int((world_y - origin_offset[1]) / voxel_size)
        vz = int((world_z - origin_offset[2]) / voxel_size)
        door_world_positions.append({
            'name': name,
            'world': (world_x, world_y, world_z),
            'voxel': (vx, vy, vz)
        })

# 按 z 层分组
print("\n门按体素 z 层分布:")
door_by_z = {}
for d in door_world_positions:
    z = d['voxel'][2]
    door_by_z.setdefault(z, []).append(d)
for z in sorted(door_by_z.keys()):
    doors_z = door_by_z[z]
    print(f"\n  z={z} ({len(doors_z)}个门):")
    for d in doors_z:
        print(f"    体素=({d['voxel'][0]},{d['voxel'][1]},{d['voxel'][2]}), "
              f"世界=({d['world'][0]:.1f},{d['world'][1]:.1f},{d['world'][2]:.1f}), "
              f"名称='{d['name'][:30]}'")

# 检查哪些门在建筑边缘（x<=2 或 x>=77 或 y<=2 或 y>=35）
print("\n" + "=" * 60)
print("可能的外门（在建筑边缘 2 体素内）")
print("=" * 60)
exterior_candidates = []
for d in door_world_positions:
    vx, vy, vz = d['voxel']
    if vx <= 3 or vx >= 76 or vy <= 3 or vy >= 34:
        exterior_candidates.append(d)
print(f"候选外门数量: {len(exterior_candidates)}")
for d in exterior_candidates:
    print(f"  体素={d['voxel']}, 名称='{d['name'][:40]}'")
