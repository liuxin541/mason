import math
import numpy as np
from voxelizer.voxel_grid import VoxelGrid

# 体素类型名称 -> VoxelGrid 常量
TYPE_NAME_TO_CONST = {
    'WALL':   VoxelGrid.WALL,
    'COLUMN': VoxelGrid.COLUMN,
    'DOOR':   VoxelGrid.DOOR,
    'STAIR':  VoxelGrid.STAIR,
    'WINDOW': VoxelGrid.WINDOW,
}

# 实体类型需要内部填充；薄片类型(DOOR)不需要
SOLID_TYPES = {VoxelGrid.WALL, VoxelGrid.COLUMN, VoxelGrid.STAIR}

# 处理顺序（后写覆盖先写）: (分类键, 是否内部填充)
PROCESS_ORDER = [
    ('slabs',        True),   # 楼板基底
    ('walls',        True),   # 墙体（create_shape 已自动减去洞口）
    ('columns',      True),   # 柱（不可通行实体）
    ('beams',        True),   # 梁
    ('members',      True),   # Member
    ('plates',       True),   # Plate
    ('railings',     True),   # 栏杆
    ('curtainwalls', True),   # 幕墙
    ('stairs',       True),   # 楼梯（可通行垂直通道）
    ('doors',        False),  # 门（薄片，覆盖墙上门洞为可通行）
    ('windows',      False),  # 窗（薄片，按 WINDOW 写入并封闭窗洞）
]


def _voxelize_mesh(grid, verts, faces, origin_shift, voxel_type, fill_interior):
    """体素化一个构件的三角网格。

    1. 表面体素化：对每个三角形做重心坐标网格采样，采样间距 = voxel_size/2
    2. 内部填充：对每列(x,y)，在表面体素的 min_z~max_z 之间填充（仅实体类型）

    create_shape 默认应用 IfcOpeningElement，墙几何已减去门洞窗洞，
    因此门洞位置无表面体素，内部填充不会错误填实门洞。
    """
    ox, oy, oz = origin_shift
    voxel_size = grid.voxel_size
    surface_voxels = {}  # {(x,y): set(z)} 用于内部填充

    for face in faces:
        p0 = verts[face[0]]
        p1 = verts[face[1]]
        p2 = verts[face[2]]

        # 转为体素浮点索引
        v0 = np.array([(p0[0] - ox) / voxel_size,
                       (p0[1] - oy) / voxel_size,
                       (p0[2] - oz) / voxel_size])
        v1 = np.array([(p1[0] - ox) / voxel_size,
                       (p1[1] - oy) / voxel_size,
                       (p1[2] - oz) / voxel_size])
        v2 = np.array([(p2[0] - ox) / voxel_size,
                       (p2[1] - oy) / voxel_size,
                       (p2[2] - oz) / voxel_size])

        e1 = v1 - v0
        e2 = v2 - v0
        len1 = float(np.linalg.norm(e1))
        len2 = float(np.linalg.norm(e2))

        # 采样数：确保间距 <= voxel_size/2，至少 2 段
        n1 = max(2, int(math.ceil(len1 * 2)))
        n2 = max(2, int(math.ceil(len2 * 2)))

        # numpy 向量化重心坐标网格采样
        u_vals = np.linspace(0, 1, n1 + 1)
        v_vals = np.linspace(0, 1, n2 + 1)
        u_grid, v_grid = np.meshgrid(u_vals, v_vals, indexing='ij')
        mask = u_grid + v_grid <= 1.0

        fx = v0[0] + u_grid * e1[0] + v_grid * e2[0]
        fy = v0[1] + u_grid * e1[1] + v_grid * e2[1]
        fz = v0[2] + u_grid * e1[2] + v_grid * e2[2]

        vx = np.floor(fx[mask]).astype(np.int32)
        vy = np.floor(fy[mask]).astype(np.int32)
        vz = np.floor(fz[mask]).astype(np.int32)

        # 批量边界检查
        valid = ((vx >= 0) & (vx < grid.width) &
                 (vy >= 0) & (vy < grid.height) &
                 (vz >= 0) & (vz < grid.depth))
        vx, vy, vz = vx[valid], vy[valid], vz[valid]

        # 直接写入 grid.data（重复赋值是幂等的）
        grid.data[vz, vy, vx] = voxel_type

        # 记录表面体素用于内部填充
        if fill_interior:
            for i in range(len(vx)):
                key = (int(vx[i]), int(vy[i]))
                s = surface_voxels.get(key)
                if s is None:
                    s = set()
                    surface_voxels[key] = s
                s.add(int(vz[i]))

    # 内部填充：对每列(x,y)，在 min_z~max_z 之间填充
    if fill_interior and surface_voxels:
        for (x, y), z_set in surface_voxels.items():
            if len(z_set) < 2:
                continue
            z_min = min(z_set)
            z_max = max(z_set)
            if z_max > z_min:
                grid.data[z_min:z_max + 1, y, x] = voxel_type


def _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type):
    """将世界坐标 AABB 填充为指定体素类型（网格提取失败时的 fallback）。"""
    ox, oy, oz = origin_shift
    xmin, ymin, zmin, xmax, ymax, zmax = bbox

    vx_start = max(0, int(math.floor((xmin - ox) / voxel_size)))
    vy_start = max(0, int(math.floor((ymin - oy) / voxel_size)))
    vz_start = max(0, int(math.floor((zmin - oz) / voxel_size)))
    vx_end = min(grid.width - 1, int(math.floor((xmax - ox) / voxel_size)))
    vy_end = min(grid.height - 1, int(math.floor((ymax - oy) / voxel_size)))
    vz_end = min(grid.depth - 1, int(math.floor((zmax - oz) / voxel_size)))

    if vx_end < vx_start or vy_end < vy_start or vz_end < vz_start:
        return
    grid.data[vz_start:vz_end + 1, vy_start:vy_end + 1, vx_start:vx_end + 1] = voxel_type


def build_from_elements(grid, elements, origin_shift=(0.0, 0.0, 0.0)):
    """真实场景：将 IFC 提取的三角网格体素化。

    统一坐标转换公式（与 run.py / 前端 / Java 保持一致）：
        voxel_index = floor((world_coord - origin_offset) / voxel_size)

    处理顺序（后写覆盖先写）：
        slabs → walls → columns → beams/members/plates/railings/curtainwalls
        → stairs → doors → windows
    门最后写入为 DOOR（可通行）；窗最后写入为 WINDOW（围护边界、不可通行）。
    """
    voxel_size = grid.voxel_size

    for category, fill_interior in PROCESS_ORDER:
        elem_list = elements.get(category, [])
        if not elem_list:
            continue

        for elem in elem_list:
            voxel_type_name = elem.get('voxel_type', 'WALL')
            voxel_type = TYPE_NAME_TO_CONST.get(voxel_type_name, VoxelGrid.WALL)
            verts = elem.get('verts')
            faces = elem.get('faces')
            bbox = elem.get('bbox')

            if verts is not None and faces is not None and len(faces) > 0:
                _voxelize_mesh(grid, verts, faces, origin_shift, voxel_type, fill_interior)
            elif bbox is not None:
                # fallback: AABB 填充（无法区分门洞，但保证不遗漏构件）
                _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type)

    print(f"[体素化] 处理完成，网格尺寸: {grid.width}x{grid.height}x{grid.depth}")


def build_test_map(grid):
    """测试场景：直接硬编码构建 3 层迷宫体素。
    用于开发者不依赖外部 IFC 文件时，快速测试后端 Java。
    """
    map2d = [
        [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1],
        [1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1],
        [1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,1,1,1,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,0,0,0,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,0,0,0,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,0,0,0,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,1,1,1,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,0,0,0,0,0,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,0,0,0,0,0,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,0,0,0,0,0,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,0,0,0,0,0,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,1,1,1,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,0,0,0,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,0,0,0,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,0,0,0,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,0,0,0,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,1,1,1,1,1,0,0,5,0,0,0,0,0,0,0,1],
        [1,0,0,0,0,0,0,0,0,0,0,5,0,0,0,0,0,0,4,1],
        [1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1]
    ]

    for x in range(len(map2d)):
        for y in range(len(map2d[0])):
            val = map2d[x][y]
            if val == VoxelGrid.WALL:
                for z in range(grid.depth):
                    grid.set_voxel(x, y, z, VoxelGrid.WALL)
            elif val == VoxelGrid.STAIR:
                for z in range(grid.depth):
                    grid.set_voxel(x, y, z, VoxelGrid.STAIR)
            elif val == VoxelGrid.EXIT:
                grid.set_voxel(x, y, 0, VoxelGrid.EXIT)

    # 模拟楼板 (Z=8 和 Z=16 处)
    floor_heights = [8, 16]
    for z in floor_heights:
        for x in range(grid.width):
            for y in range(grid.height):
                if grid.get_voxel(x, y, z) != VoxelGrid.STAIR and grid.get_voxel(x, y, z) != VoxelGrid.WALL:
                    grid.set_voxel(x, y, z, VoxelGrid.WALL)
