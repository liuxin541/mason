import math
from voxelizer.voxel_grid import VoxelGrid

def _fill_bbox_as_wall(grid, bbox, origin_shift, voxel_size):
    """将一个世界坐标 AABB 包围盒填充为 WALL 体素。
    统一公式: voxel_index = floor((world_coord - origin_offset) / voxel_size)
    """
    ox, oy, oz = origin_shift
    xmin, ymin, zmin, xmax, ymax, zmax = bbox

    vx_start = int(math.floor((xmin - ox) / voxel_size))
    vy_start = int(math.floor((ymin - oy) / voxel_size))
    vz_start = int(math.floor((zmin - oz) / voxel_size))
    vx_end   = int(math.floor((xmax - ox) / voxel_size))
    vy_end   = int(math.floor((ymax - oy) / voxel_size))
    vz_end   = int(math.floor((zmax - oz) / voxel_size))

    # 钳制边界，防止数组越界
    vx_start = max(0, vx_start)
    vy_start = max(0, vy_start)
    vz_start = max(0, vz_start)
    vx_end   = min(grid.width - 1, vx_end)
    vy_end   = min(grid.height - 1, vy_end)
    vz_end   = min(grid.depth - 1, vz_end)

    for z in range(vz_start, vz_end + 1):
        for y in range(vy_start, vy_end + 1):
            for x in range(vx_start, vx_end + 1):
                grid.set_voxel(x, y, z, VoxelGrid.WALL)


def build_from_elements(grid, elements, origin_shift=(0.0, 0.0, 0.0)):
    """
    真实场景：将 IFC 提取出的包围盒转换到体素坐标。

    统一坐标转换公式（与 run.py / 前端 / Java 保持一致）：
        voxel_index = floor((world_coord - origin_offset) / voxel_size)
    其中 origin_offset = 建筑世界包围盒最小点 (xmin, ymin, zmin)，
    体素网格 (0,0,0) 对应世界坐标 origin_offset。
    使用 floor 保证边界对齐：体素 i 覆盖世界坐标区间
    [origin_offset + i*voxel_size, origin_offset + (i+1)*voxel_size)。

    处理元素：
        walls  : 墙体 → WALL（人员/火焰阻挡）
        slabs  : 楼板/屋顶 → WALL（楼层分隔，防止人员穿层、出现在屋顶上）
        stairs : 楼梯 → STAIR（暂留接口）
    """
    ox, oy, oz = origin_shift

    # 1. 墙体
    for wall in elements.get('walls', []):
        if 'bbox' not in wall:
            continue
        _fill_bbox_as_wall(grid, wall['bbox'], origin_shift, grid.voxel_size)

    # 2. 楼板/屋顶（与墙同类型 WALL，用于分隔楼层和封顶）
    #    若不体素化 slabs，楼板位置为 EMPTY 会被 VoxelLoader 标记为 inside=1，
    #    导致人员生成在屋顶上、楼层间无分隔、火焰垂直贯通。
    slab_count = 0
    for slab in elements.get('slabs', []):
        if 'bbox' not in slab:
            continue
        _fill_bbox_as_wall(grid, slab['bbox'], origin_shift, grid.voxel_size)
        slab_count += 1

    # 3. 楼梯标记 (等待后续语义解析完善)
    #    楼梯目前仍按 STAIR 处理（贯穿楼层），待 IfcStair 几何提取完善后补充。


def build_test_map(grid):
    """
    测试场景：直接硬编码构建 3 层迷宫体素
    用于开发者不依赖外部 IFC 文件时，快速测试后端 Java
    """
    # 复制之前的 20x20 地图
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