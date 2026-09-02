import ifcopenshell
import numpy as np

ifc = ifcopenshell.open(r'D:\mason\一号办公楼1.ifc')
settings = ifcopenshell.geom.settings()
settings.set(settings.USE_WORLD_COORDS, True)

doors = ifc.by_type('IfcDoor')
print(f'检查前 10 个门的 bbox:')
for i, door in enumerate(doors[:10]):
    try:
        shape = ifcopenshell.geom.create_shape(settings, door)
        verts = shape.geometry.verts
        verts_array = np.array(verts).reshape(-1, 3)
        zmin, zmax = verts_array[:,2].min(), verts_array[:,2].max()
        has_opening = hasattr(door, 'FillsVoids') and door.FillsVoids
        opening_z = None
        if has_opening:
            for rel in door.FillsVoids:
                opening = rel.RelatingOpeningElement
                try:
                    oshape = ifcopenshell.geom.create_shape(settings, opening)
                    overts = np.array(oshape.geometry.verts).reshape(-1, 3)
                    opening_z = (overts[:,2].min(), overts[:,2].max())
                except:
                    pass
        print(f'  门{i}: z=[{zmin:.1f}, {zmax:.1f}] 高度={zmax-zmin:.1f}m, 洞口={"有" if opening_z else "无"}')
        if opening_z:
            print(f'       洞口 z=[{opening_z[0]:.1f}, {opening_z[1]:.1f}] 高度={opening_z[1]-opening_z[0]:.1f}m')
    except Exception as e:
        print(f'  门{i}: 错误 {e}')

# 统计所有门的 z 范围
print()
print('所有门的 z 范围统计:')
z_ranges = []
for door in doors:
    try:
        shape = ifcopenshell.geom.create_shape(settings, door)
        verts = np.array(shape.geometry.verts).reshape(-1, 3)
        z_ranges.append((verts[:,2].min(), verts[:,2].max()))
    except:
        pass
zmins = [z[0] for z in z_ranges]
zmaxs = [z[1] for z in z_ranges]
print(f'  zmin 范围: {min(zmins):.1f} ~ {max(zmins):.1f}')
print(f'  zmax 范围: {min(zmaxs):.1f} ~ {max(zmaxs):.1f}')
print(f'  门数量: {len(z_ranges)}')
