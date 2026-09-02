"""检查 IFC 中门的世界坐标（毫米转米）"""
import ifcopenshell
import ifcopenshell.util.placement

ifc_path = r'D:\mason\一号办公楼1.ifc'
ifc = ifcopenshell.open(ifc_path)

origin_offset = [-10.2471, -9.6060, -0.7000]
voxel_size = 0.5

doors = ifc.by_type('IfcDoor')
print(f"IFC 中门总数: {len(doors)}")

door_world_positions = []
for door in doors:
    name = door.Name or ''
    if door.ObjectPlacement:
        matrix = ifcopenshell.util.placement.get_local_placement(door.ObjectPlacement)
        # IFC 坐标是毫米，转换为米
        world_x = matrix[0,3] / 1000.0
        world_y = matrix[1,3] / 1000.0
        world_z = matrix[2,3] / 1000.0
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
              f"世界=({d['world'][0]:.2f},{d['world'][1]:.2f},{d['world'][2]:.2f})m, "
              f"名称='{d['name'][:35]}'")

# 找出特殊门（玻璃门、旋转门）
print("\n" + "=" * 60)
print("特殊门（玻璃门/旋转门，可能是外门）")
print("=" * 60)
special_doors = [d for d in door_world_positions if '玻璃' in d['name'] or '旋转' in d['name']]
for d in special_doors:
    print(f"  体素=({d['voxel'][0]},{d['voxel'][1]},{d['voxel'][2]}), "
          f"世界=({d['world'][0]:.2f},{d['world'][1]:.2f},{d['world'][2]:.2f})m, "
          f"名称='{d['name']}'")
