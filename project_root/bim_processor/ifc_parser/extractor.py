import ifcopenshell
import ifcopenshell.geom
import numpy as np

# IFC 类型 -> (分类键, 体素类型常量名) 映射
# 体素类型: WALL=1, DOOR=3, STAIR=5, COLUMN=6, WINDOW=7（与 VoxelGrid 保持一致）
TYPE_MAP = {
    'IfcWall':              ('walls',        'WALL'),
    'IfcWallStandardCase':  ('walls',        'WALL'),
    'IfcSlab':              ('slabs',        'WALL'),
    'IfcColumn':            ('columns',      'COLUMN'),
    'IfcBeam':              ('beams',        'WALL'),
    'IfcRailing':           ('railings',     'WALL'),
    'IfcCurtainWall':       ('curtainwalls', 'WALL'),
    'IfcMember':            ('members',      'WALL'),
    'IfcPlate':             ('plates',       'WALL'),
    'IfcStair':             ('stairs',       'STAIR'),
    'IfcStairFlight':       ('stairs',       'STAIR'),
    'IfcDoor':              ('doors',        'DOOR'),
    # 窗保留独立语义；后端将其作为围护实体，但不会允许人员通行。
    'IfcWindow':            ('windows',      'WINDOW'),
}


def _world_bbox(shape):
    """从 ifcopenshell 几何形状计算世界坐标 AABB。
    shape.transformation.matrix 为 4x4 行优先矩阵（行向量约定 world = local · M）。
    返回 [xmin, ymin, zmin, xmax, ymax, zmax] 或 None。
    """
    verts = shape.geometry.verts
    if not verts:
        return None

    xs = verts[0::3]
    ys = verts[1::3]
    zs = verts[2::3]
    lx_min, lx_max = min(xs), max(xs)
    ly_min, ly_max = min(ys), max(ys)
    lz_min, lz_max = min(zs), max(zs)

    m = shape.transformation.matrix
    wxs, wys, wzs = [], [], []
    for cx in (lx_min, lx_max):
        for cy in (ly_min, ly_max):
            for cz in (lz_min, lz_max):
                wxs.append(cx * m[0] + cy * m[4] + cz * m[8]  + m[12])
                wys.append(cx * m[1] + cy * m[5] + cz * m[9]  + m[13])
                wzs.append(cx * m[2] + cy * m[6] + cz * m[10] + m[14])

    return [min(wxs), min(wys), min(wzs), max(wxs), max(wys), max(wzs)]


def _extract_mesh(shape):
    """从 ifcopenshell shape 提取世界坐标三角网格。

    create_shape 默认应用 IfcOpeningElement，墙几何已自动减去洞口。
    返回 (verts_world: np.array[N,3], faces: np.array[M,3]) 或 None。
    """
    geo = shape.geometry
    verts_local = geo.verts
    if not verts_local or len(verts_local) < 9:
        return None

    m = shape.transformation.matrix
    n = len(verts_local) // 3
    arr = np.asarray(verts_local[:n * 3], dtype=np.float64).reshape(-1, 3)

    # 行向量约定: world = local · M （M 为 4x4 行优先）
    verts_world = np.column_stack([
        arr[:, 0] * m[0] + arr[:, 1] * m[4] + arr[:, 2] * m[8]  + m[12],
        arr[:, 0] * m[1] + arr[:, 1] * m[5] + arr[:, 2] * m[9]  + m[13],
        arr[:, 0] * m[2] + arr[:, 1] * m[6] + arr[:, 2] * m[10] + m[14],
    ])

    # 面索引：ifcopenshell 的 faces 为扁平化三角形索引 [i0,i1,i2, ...]
    faces_raw = getattr(geo, 'faces', None)
    if faces_raw and len(faces_raw) >= 3:
        faces = np.asarray(faces_raw, dtype=np.int32).reshape(-1, 3)
        # 过滤越界索引
        valid = (faces[:, 0] < n) & (faces[:, 1] < n) & (faces[:, 2] < n)
        faces = faces[valid]
        if len(faces) == 0:
            return None
    elif n % 3 == 0:
        # 无 faces 字段，假设 verts 已是三角形序列
        faces = np.arange(n, dtype=np.int32).reshape(-1, 3)
    else:
        return None

    return verts_world, faces


def extract_elements(ifc_file):
    """从 IFC 文件提取全部建筑构件的三角网格与包围盒。

    返回 dict，key 为分类键 (walls/slabs/columns/...)，value 为元素列表。
    每个元素: {'id', 'voxel_type', 'verts', 'faces', 'bbox'}
    若 create_shape 失败则回退到 AABB 模式 (verts/faces 为 None)。
    """
    elements = {key: [] for key in set(v[0] for v in TYPE_MAP.values())}
    settings = ifcopenshell.geom.settings()

    stats = {}
    for ifc_type, (category, voxel_type) in TYPE_MAP.items():
        entities = ifc_file.by_type(ifc_type)
        if not entities:
            continue
        count = 0
        for ent in entities:
            try:
                shape = ifcopenshell.geom.create_shape(settings, ent)
                if not shape:
                    continue
                bbox = _world_bbox(shape)
                if not bbox:
                    continue
                mesh = _extract_mesh(shape)
                if mesh is not None:
                    verts, faces = mesh
                    elements[category].append({
                        'id': ent.id(),
                        'voxel_type': voxel_type,
                        'verts': verts,
                        'faces': faces,
                        'bbox': bbox,
                    })
                else:
                    # 回退: 仅 AABB（无网格数据）
                    elements[category].append({
                        'id': ent.id(),
                        'voxel_type': voxel_type,
                        'verts': None,
                        'faces': None,
                        'bbox': bbox,
                    })
                count += 1
            except Exception:
                continue
        if count > 0:
            stats[ifc_type] = count

    summary = ", ".join(f"{k}:{v}" for k, v in sorted(stats.items()))
    print(f"[提取结果] {summary}")
    return elements
