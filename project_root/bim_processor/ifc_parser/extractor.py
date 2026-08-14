import ifcopenshell
import ifcopenshell.geom


def _world_bbox(shape):
    """从 ifcopenshell 几何形状计算世界坐标轴对齐包围盒 (AABB)。

    shape.geometry.verts 为局部表示坐标，需通过 shape.transformation.matrix
    (4x4 行优先，行向量约定 world = local · M) 变换到世界坐标。
    为提升效率，先求局部 AABB 的 8 个角点，变换后取整体 min/max。
    返回 [xmin, ymin, zmin, xmax, ymax, zmax] 或 None。
    """
    verts = shape.geometry.verts
    if not verts:
        return None

    # 1. 局部坐标 AABB
    xs = verts[0::3]
    ys = verts[1::3]
    zs = verts[2::3]
    lx_min, lx_max = min(xs), max(xs)
    ly_min, ly_max = min(ys), max(ys)
    lz_min, lz_max = min(zs), max(zs)

    # 2. 变换矩阵 (16 个浮点数, 行优先, 行向量约定)
    m = shape.transformation.matrix

    # 3. 变换局部 AABB 的 8 个角点到世界坐标, 并求世界 AABB
    wxs, wys, wzs = [], [], []
    for cx in (lx_min, lx_max):
        for cy in (ly_min, ly_max):
            for cz in (lz_min, lz_max):
                wxs.append(cx * m[0] + cy * m[4] + cz * m[8] + m[12])
                wys.append(cx * m[1] + cy * m[5] + cz * m[9] + m[13])
                wzs.append(cx * m[2] + cy * m[6] + cz * m[10] + m[14])

    return [min(wxs), min(wys), min(wzs), max(wxs), max(wys), max(wzs)]


def extract_elements(ifc_file):
    """从 IFC 文件中提取墙、楼梯、楼板等建筑构件的包围盒"""
    elements = {'walls': [], 'stairs': [], 'slabs': [], 'doors': [], 'windows': []}

    settings = ifcopenshell.geom.settings()

    def extract_by_type(ifc_type, key):
        entities = ifc_file.by_type(ifc_type)
        for ent in entities:
            try:
                shape = ifcopenshell.geom.create_shape(settings, ent)
                if shape:
                    bbox = _world_bbox(shape)
                    if bbox:
                        elements[key].append({'bbox': bbox, 'id': ent.id()})
            except Exception:
                continue

    # 1. 墙体 (兼容 IfcWall 和 IfcWallStandardCase)
    extract_by_type('IfcWall', 'walls')
    extract_by_type('IfcWallStandardCase', 'walls')

    # 2. 楼板 (IfcSlab) 作为补充，防止建筑没有楼板结构
    extract_by_type('IfcSlab', 'slabs')

    # 3. 楼梯 (IfcStair)
    extract_by_type('IfcStair', 'stairs')

    print(f"[提取结果] 墙:{len(elements['walls'])} 面, 楼板:{len(elements['slabs'])} 块, 楼梯:{len(elements['stairs'])} 部")
    return elements
