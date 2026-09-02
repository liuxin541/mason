import math
from collections import deque
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

# 实体类型需要内部填充；薄片类型(DOOR/WINDOW)不需要（改用 bbox 洞口填充）
SOLID_TYPES = {VoxelGrid.WALL, VoxelGrid.COLUMN, VoxelGrid.STAIR}

# 处理顺序（后写覆盖先写）: (分类键, 是否内部填充, 是否用 bbox 洞口填充)
PROCESS_ORDER = [
    ('slabs',        True,  False),  # 楼板基底
    ('walls',        True,  False),  # 墙体（create_shape 已自动减去洞口）
    ('columns',      True,  False),  # 柱（不可通行实体）
    ('beams',        True,  False),  # 梁
    ('members',      True,  False),  # Member
    ('plates',       True,  False),  # Plate
    ('railings',     True,  False),  # 栏杆
    ('curtainwalls', True,  False),  # 幕墙
    ('stairs',       True,  False),  # 楼梯（可通行垂直通道）
    ('doors',        False, True),   # 门：用 bbox 填充整个门洞为 DOOR（可通行）
    ('windows',      False, True),   # 窗：用 bbox 填充整个窗洞为 WINDOW（围护、不可通行）
]

# 采样密度：每体素长度的采样段数（越大越密，4 = 间距 0.25 体素）
SAMPLES_PER_VOXEL = 4
# 内部填充分段阈值：z 方向间隙超过该值则断开（视为洞口）
FILL_GAP_THRESHOLD = 1
# 门窗 bbox 膨胀量（体素单位），确保洞口完全覆盖
# 建筑为双墙结构（两道相邻墙，间距约1体素），需膨胀 1.5 体素才能覆盖双墙厚度
# 1.5 体素 = 0.75m（voxel_size=0.5时），确保门同时开在两道墙上
OPENING_PAD = 1.5
# 门最大高度（米）：超过此高度的门bbox视为包含过梁，仅填充底部 DOOR_MAX_HEIGHT 米
DOOR_MAX_HEIGHT = 2.2
# 外墙判定容差（米）：门bbox与建筑外轮廓距离小于此值视为外门（出口）
EXTERIOR_MARGIN = 0.5


def _sample_triangle_surface(v0, v1, v2):
    """对三角形做重心坐标网格采样，返回浮点体素坐标数组 (N,3)。

    采样间距 = 1/SAMPLES_PER_VOXEL 体素，确保小三角形和薄构件边缘被覆盖。
    """
    e1 = v1 - v0
    e2 = v2 - v0
    len1 = float(np.linalg.norm(e1))
    len2 = float(np.linalg.norm(e2))

    # 采样段数：确保间距 <= 1/SAMPLES_PER_VOXEL 体素，至少 3 段
    n1 = max(3, int(math.ceil(len1 * SAMPLES_PER_VOXEL)))
    n2 = max(3, int(math.ceil(len2 * SAMPLES_PER_VOXEL)))

    u_vals = np.linspace(0, 1, n1 + 1)
    v_vals = np.linspace(0, 1, n2 + 1)
    u_grid, v_grid = np.meshgrid(u_vals, v_vals, indexing='ij')
    mask = u_grid + v_grid <= 1.0

    fx = v0[0] + u_grid * e1[0] + v_grid * e2[0]
    fy = v0[1] + u_grid * e1[1] + v_grid * e2[1]
    fz = v0[2] + u_grid * e1[2] + v_grid * e2[2]

    return np.column_stack([fx[mask], fy[mask], fz[mask]])


def _sample_triangle_edges(v0, v1, v2):
    """对三角形的三条边做线采样，确保相邻三角形共享边连续无孔洞。

    返回浮点体素坐标数组 (N,3)。
    """
    edges = [(v0, v1), (v1, v2), (v2, v0)]
    all_points = []
    for pa, pb in edges:
        dist = float(np.linalg.norm(pb - pa))
        n = max(3, int(math.ceil(dist * SAMPLES_PER_VOXEL)))
        t = np.linspace(0, 1, n + 1)
        points = pa[np.newaxis, :] + t[:, np.newaxis] * (pb - pa)[np.newaxis, :]
        all_points.append(points)
    return np.vstack(all_points)


def _write_points_to_grid(grid, points, voxel_type, surface_voxels=None):
    """将浮点体素坐标点批量写入网格，返回有效点数。

    points: (N,3) 浮点体素索引坐标
    surface_voxels: 若提供，则记录 (x,y)->set(z) 用于内部填充
    """
    vx = np.floor(points[:, 0]).astype(np.int32)
    vy = np.floor(points[:, 1]).astype(np.int32)
    vz = np.floor(points[:, 2]).astype(np.int32)

    valid = ((vx >= 0) & (vx < grid.width) &
             (vy >= 0) & (vy < grid.height) &
             (vz >= 0) & (vz < grid.depth))
    vx, vy, vz = vx[valid], vy[valid], vz[valid]

    if len(vx) == 0:
        return 0

    grid.data[vz, vy, vx] = voxel_type

    if surface_voxels is not None:
        for i in range(len(vx)):
            key = (int(vx[i]), int(vy[i]))
            s = surface_voxels.get(key)
            if s is None:
                s = set()
                surface_voxels[key] = s
            s.add(int(vz[i]))

    return len(vx)


def _fill_interior_segmented(grid, surface_voxels, voxel_type):
    """分段内部填充：对每列(x,y)，在表面体素的连续 z 段内填充。

    间隙超过 FILL_GAP_THRESHOLD 体素则断开，避免把门洞/窗洞填满。
    这是对原 min~max 粗暴填充的关键改进。
    """
    if not surface_voxels:
        return

    for (x, y), z_set in surface_voxels.items():
        if len(z_set) < 2:
            continue
        z_sorted = sorted(z_set)
        # 切分连续段
        segments = []
        seg_start = z_sorted[0]
        prev = z_sorted[0]
        for z in z_sorted[1:]:
            if z - prev > FILL_GAP_THRESHOLD:
                segments.append((seg_start, prev))
                seg_start = z
            prev = z
        segments.append((seg_start, prev))

        for z_min, z_max in segments:
            if z_max >= z_min:
                grid.data[z_min:z_max + 1, y, x] = voxel_type


def _voxelize_mesh(grid, verts, faces, origin_shift, voxel_type, fill_interior):
    """体素化一个构件的三角网格。

    精度改进：
    1. 表面采样密度提升至 0.25 体素间距（原 0.5）
    2. 新增三角形边采样，确保共享边连续无孔洞
    3. 内部分段填充，间隙 >1 体素断开（不再粗暴填满门洞）

    create_shape 默认应用 IfcOpeningElement，墙几何已减去门洞窗洞。
    """
    ox, oy, oz = origin_shift
    voxel_size = grid.voxel_size
    surface_voxels = {} if fill_interior else None

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

        # 1. 三角形表面采样
        surf_pts = _sample_triangle_surface(v0, v1, v2)
        _write_points_to_grid(grid, surf_pts, voxel_type, surface_voxels)

        # 2. 三角形边采样（确保相邻三角形共享边连续，消除"筛子孔"）
        edge_pts = _sample_triangle_edges(v0, v1, v2)
        _write_points_to_grid(grid, edge_pts, voxel_type, surface_voxels)

    # 3. 分段内部填充（仅实体类型）
    if fill_interior:
        _fill_interior_segmented(grid, surface_voxels, voxel_type)


def _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type, pad_voxels=0.0, pad_z=True):
    """将世界坐标 AABB 填充为指定体素类型。

    pad_voxels: 各方向膨胀量（体素单位），用于门窗洞口确保完全覆盖。
    pad_z: 是否在 z 方向应用膨胀。门的 z 方向不应膨胀，避免门高超过实际高度。
    """
    ox, oy, oz = origin_shift
    xmin, ymin, zmin, xmax, ymax, zmax = bbox

    pad = pad_voxels * voxel_size
    z_pad = pad if pad_z else 0
    vx_start = max(0, int(math.floor((xmin - pad - ox) / voxel_size)))
    vy_start = max(0, int(math.floor((ymin - pad - oy) / voxel_size)))
    vz_start = max(0, int(math.floor((zmin - z_pad - oz) / voxel_size)))
    vx_end = min(grid.width - 1, int(math.floor((xmax + pad - ox) / voxel_size)))
    vy_end = min(grid.height - 1, int(math.floor((ymax + pad - oy) / voxel_size)))
    vz_end = min(grid.depth - 1, int(math.floor((zmax + z_pad - oz) / voxel_size)))

    if vx_end < vx_start or vy_end < vy_start or vz_end < vz_start:
        return
    grid.data[vz_start:vz_end + 1, vy_start:vy_end + 1, vx_start:vx_end + 1] = voxel_type


def _identify_exits_from_voxels(grid):
    """基于体素数据识别外门为出口（适应厚墙/双墙结构）。

    算法：
    1. 从网格边界洪水填充空气，标记"外部空气"（建筑外部的空气）
    2. 对每个门，检查四个方向，如果在 MAX_GAP 体素内到达外部空气，
       且中间只经过墙/窗（围护结构），则该门是通向外部的外门
    3. 厚墙结构中，门可能在厚墙内侧，中间经过厚墙到达外部
    必须在 seal_exterior 之前调用。
    """
    data = grid.data
    d, h, w = data.shape
    MAX_GAP = 3  # 门与外部空气之间最大间隙（1.5m，适应厚墙）
    exit_count = 0

    for z in range(d):
        layer = data[z]

        # 步骤1：从四条边界洪水填充，标记外部空气
        outside_air = np.zeros((h, w), dtype=bool)
        q = deque()
        for x in range(w):
            if layer[0, x] == VoxelGrid.EMPTY:
                q.append((0, x)); outside_air[0, x] = True
            if layer[h - 1, x] == VoxelGrid.EMPTY:
                q.append((h - 1, x)); outside_air[h - 1, x] = True
        for y in range(h):
            if layer[y, 0] == VoxelGrid.EMPTY:
                q.append((y, 0)); outside_air[y, 0] = True
            if layer[y, w - 1] == VoxelGrid.EMPTY:
                q.append((y, w - 1)); outside_air[y, w - 1] = True

        while q:
            cy, cx = q.popleft()
            for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                ny, nx = cy + dy, cx + dx
                if 0 <= ny < h and 0 <= nx < w:
                    if layer[ny, nx] == VoxelGrid.EMPTY and not outside_air[ny, nx]:
                        outside_air[ny, nx] = True
                        q.append((ny, nx))

        # 步骤2：对每个门，检查是否在 MAX_GAP 内能到达外部空气
        for y in range(h):
            for x in range(w):
                if layer[y, x] != VoxelGrid.DOOR:
                    continue
                # 检查四个方向
                found = False
                exit_path = []
                for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                    path = []
                    for dist in range(1, MAX_GAP + 1):
                        ny, nx = y + dy * dist, x + dx * dist
                        if ny < 0 or ny >= h or nx < 0 or nx >= w:
                            break
                        if outside_air[ny, nx]:
                            found = True
                            break
                        path.append((ny, nx))
                        # 中间只允许墙/窗（围护结构），遇到门/楼梯/内部空气则停止
                        if layer[ny, nx] not in (VoxelGrid.WALL, VoxelGrid.WINDOW):
                            break
                    if found:
                        exit_path = path
                        break
                if found:
                    # 标记门为出口
                    layer[y, x] = VoxelGrid.EXIT
                    exit_count += 1
                    # 打通中间的墙/窗（也标记为出口），确保人员能逃出
                    for py, px in exit_path:
                        if layer[py, px] in (VoxelGrid.WALL, VoxelGrid.WINDOW):
                            layer[py, px] = VoxelGrid.EXIT
                            exit_count += 1

    return exit_count


def build_from_elements(grid, elements, origin_shift=(0.0, 0.0, 0.0)):
    """真实场景：将 IFC 提取的三角网格体素化。

    统一坐标转换公式（与 run.py / 前端 / Java 保持一致）：
        voxel_index = floor((world_coord - origin_offset) / voxel_size)

    处理顺序（后写覆盖先写）：
        slabs → walls → columns → beams/members/plates/railings/curtainwalls
        → stairs → doors → windows

    精度改进：
    - 实体构件：高密度表面采样 + 边采样 + 分段内部填充
    - 门：bbox 填充洞口，门高限制为 DOOR_MAX_HEIGHT（避免覆盖过梁）
    - 窗：bbox 填充洞口，微量膨胀避免边缘残留
    - 出口识别：基于每层体素轮廓，外门自动标记为 EXIT
    """
    voxel_size = grid.voxel_size

    for category, fill_interior, use_bbox_opening in PROCESS_ORDER:
        elem_list = elements.get(category, [])
        if not elem_list:
            continue

        for elem in elem_list:
            voxel_type_name = elem.get('voxel_type', 'WALL')
            voxel_type = TYPE_NAME_TO_CONST.get(voxel_type_name, VoxelGrid.WALL)
            verts = elem.get('verts')
            faces = elem.get('faces')
            bbox = elem.get('bbox')

            if use_bbox_opening and bbox is not None:
                # ★ 门高限制：超过 DOOR_MAX_HEIGHT 的门 bbox 视为包含过梁，
                #   仅填充底部 DOOR_MAX_HEIGHT 米，上方保留墙体
                if voxel_type == VoxelGrid.DOOR:
                    xmin, ymin, zmin, xmax, ymax, zmax = bbox
                    if zmax - zmin > DOOR_MAX_HEIGHT:
                        bbox = (xmin, ymin, zmin, xmax, ymax,
                                zmin + DOOR_MAX_HEIGHT)

                # 门/窗：用 bbox 填充洞口，x-y 方向微量膨胀避免边缘残留墙体
                # 门的 z 方向不膨胀，避免门高超过实际高度（2.1m门+0.75m膨胀=3.6m，覆盖整层）
                _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type,
                           pad_voxels=OPENING_PAD, pad_z=(voxel_type != VoxelGrid.DOOR))
            elif verts is not None and faces is not None and len(faces) > 0:
                _voxelize_mesh(grid, verts, faces, origin_shift, voxel_type, fill_interior)
            elif bbox is not None:
                # fallback: AABB 填充（无法区分洞口，但保证不遗漏构件）
                _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type)

    # ★ 出口识别：基于每层体素轮廓，外门标记为 EXIT（必须在 seal_exterior 之前）
    exit_count = _identify_exits_from_voxels(grid)

    print(f"[体素化] 处理完成，网格尺寸: {grid.width}x{grid.height}x{grid.depth}"
          f"，识别出口 {exit_count} 个")


def seal_exterior(grid):
    """封闭建筑外部空间：自适应按层包围盒 + 盒内洪水填充。

    根本原因：Java 端 insideGrid 逻辑是"非墙非楼梯=内部"，导致建筑外部的
    空气（含网格边距、建筑凹进区域）全部被误判为内部，人员因此在楼外生成。

    算法（每层独立，自适应）：
    1. 找该层非空气体素的 2D 包围盒
    2. 包围盒外的空气 => 外部 => 设为墙
    3. 包围盒内从四条边界洪水填充空气（只通过空气，不通过门/墙）
    4. 若未被填充的内部空气 >= 包围盒面积 5%，说明墙体闭合正常：
       与边界连通的空气（凹进区域/入口缺口）=> 外部 => 设为墙
    5. 若内部空气 < 5%，说明该层外墙不闭合（如入口层大开间），洪水填充
       会把内部也误封 => 回退：只保留包围盒封闭，不做盒内洪水填充

    门(DOOR)不是空气，洪水填充不通过门，因此门内房间空气不会被误封。
    """
    data = grid.data
    d, h, w = data.shape
    total_sealed = 0

    # 预计算：标记与出口相邻的空气体素（出口通道，seal_exterior 不应封闭）
    # 出口识别后，出口外侧可能是外部空气，这些空气是人员逃出的通道
    # 保护范围扩大到 10 体素，确保通道能到达网格边界
    exit_adjacent_air = np.zeros_like(data, dtype=bool)
    exit_positions = np.argwhere(data == VoxelGrid.EXIT)
    for z, y, x in exit_positions:
        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            for dist in range(1, 11):  # 出口外侧 10 体素内的空气都保护
                ny, nx = y + dy*dist, x + dx*dist
                if 0 <= ny < h and 0 <= nx < w:
                    if data[z, ny, nx] == VoxelGrid.EMPTY:
                        exit_adjacent_air[z, ny, nx] = True
                    else:
                        break  # 遇到非空气，停止

    for z in range(d):
        layer = data[z]
        non_empty = np.argwhere(layer != VoxelGrid.EMPTY)
        non_empty_count = len(non_empty)
        layer_area = h * w

        # 该层非空气体素占比过低（< 15%），视为屋顶以上/纯外部层（如女儿墙层）
        # 正常办公楼楼层非空气占比约 25-30%（房间内部大部分是空气），
        # 楼板层约 88%，屋顶以上层 < 10%。15% 阈值只封真正的外部层。
        if non_empty_count < layer_area * 0.15:
            # 只把空气设为墙，保留门/出口/楼梯/窗等有意义的体素类型
            # 但保护出口相邻的空气（出口通道）
            air_mask = (layer == VoxelGrid.EMPTY) & (~exit_adjacent_air[z])
            count = int(np.sum(air_mask))
            layer[air_mask] = VoxelGrid.WALL
            total_sealed += count
            continue

        y_min, x_min = non_empty.min(axis=0)
        y_max, x_max = non_empty.max(axis=0)
        bbox_area = (y_max - y_min + 1) * (x_max - x_min + 1)

        # 第一步：包围盒外的空气设为墙（保护出口相邻的空气）
        outside_bbox = np.ones_like(layer, dtype=bool)
        outside_bbox[y_min:y_max + 1, x_min:x_max + 1] = False
        outside_air_bbox = outside_bbox & (layer == VoxelGrid.EMPTY) & (~exit_adjacent_air[z])
        sealed_bbox = int(np.sum(outside_air_bbox))
        layer[outside_air_bbox] = VoxelGrid.WALL

        # 第二步：包围盒内从边界洪水填充空气
        passable = (layer == VoxelGrid.EMPTY)  # 此时盒外已设为墙
        visited = np.zeros_like(passable, dtype=bool)
        q = deque()

        for x in range(x_min, x_max + 1):
            if passable[y_min, x]:
                q.append((y_min, x)); visited[y_min, x] = True
            if passable[y_max, x]:
                q.append((y_max, x)); visited[y_max, x] = True
        for y in range(y_min, y_max + 1):
            if passable[y, x_min]:
                q.append((y, x_min)); visited[y, x_min] = True
            if passable[y, x_max]:
                q.append((y, x_max)); visited[y, x_max] = True

        while q:
            cy, cx = q.popleft()
            for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                ny, nx = cy + dy, cx + dx
                if (y_min <= ny <= y_max and x_min <= nx <= x_max
                        and passable[ny, nx] and not visited[ny, nx]):
                    visited[ny, nx] = True
                    q.append((ny, nx))

        inside_air_count = int(np.sum(passable & ~visited))

        if inside_air_count >= bbox_area * 0.05:
            # 墙体闭合正常：与边界连通的空气（凹进区域）设为墙（保护出口相邻的空气）
            outside_air_inner = visited & passable & (~exit_adjacent_air[z])
            sealed_inner = int(np.sum(outside_air_inner))
            layer[outside_air_inner] = VoxelGrid.WALL
            total_sealed += sealed_bbox + sealed_inner
        else:
            # 外墙不闭合（入口层）：回退，不做盒内洪水填充，保留内部空间
            total_sealed += sealed_bbox

    print(f"[封闭外部] 自适应包围盒+盒内洪水填充, 共封闭外部空气 {total_sealed} 体素")


def fix_stair_vertical_connectivity(grid):
    """修复楼梯 z 方向连通性。

    根本原因：楼梯是斜面，体素化后各层的 x,y 位置会偏移，导致相邻层楼梯
    没有 (x,y) 重叠。Java 端要求"当前位置必须是楼梯才能上下移动"，因此
    人员在楼板层被卡住，无法上下楼。

    修复：对每个楼梯体素，在 z 方向上下各扩展 3 层（同一 x,y 位置），
    把墙/空气设为楼梯。扩展3层确保楼梯连接到上方活动楼层（如F3→F4），
    因为 IFC 楼梯段可能只到楼层底部，需要延伸到楼层内部。
    """
    data = grid.data
    depth, height, width = data.shape
    stair_mask = (data == VoxelGrid.STAIR)

    # 向上扩展3层
    expand_up = np.zeros_like(stair_mask)
    for offset in range(1, 4):
        if offset < depth:
            expand_up[offset:] |= stair_mask[:-offset]

    # 向下扩展3层
    expand_down = np.zeros_like(stair_mask)
    for offset in range(1, 4):
        if offset < depth:
            expand_down[:-offset] |= stair_mask[offset:]

    expand = expand_up | expand_down
    mask = expand & ((data == VoxelGrid.EMPTY) | (data == VoxelGrid.WALL))
    count = int(np.sum(mask))
    data[mask] = VoxelGrid.STAIR

    print(f"[楼梯修复] z 方向扩展 {count} 体素(上下各3层)，确保各层楼梯连通")


def clip_penetrating_elements(grid):
    """清除穿透楼板的墙和窗，防止上层构件错误延伸到屋顶层。

    算法：
    1. 检测楼板层（非空气占比 > 80%）
    2. 合并连续楼板层为楼板组
    3. 对每个楼板组，检查上方第一层的墙/窗是否与下方层位置相同
       （且楼板层对应位置也是墙），若是则判定为穿透，清除上方的墙/窗
    4. 保留楼梯、门、出口
    """
    data = grid.data
    depth, height, width = data.shape

    # 1. 检测楼板层
    slab_layers = []
    for z in range(depth):
        layer = data[z]
        non_empty = int(np.sum(layer != VoxelGrid.EMPTY))
        ratio = non_empty / (width * height)
        if ratio > 0.80:
            slab_layers.append(z)

    if not slab_layers:
        print("[楼板隔断] 未检测到楼板层，跳过")
        return

    # 2. 合并连续楼板层
    slab_groups = []
    current_group = [slab_layers[0]]
    for i in range(1, len(slab_layers)):
        if slab_layers[i] - slab_layers[i-1] <= 1:
            current_group.append(slab_layers[i])
        else:
            slab_groups.append(current_group)
            current_group = [slab_layers[i]]
    slab_groups.append(current_group)

    total_clipped = 0
    # 3. 对每个楼板组，清除穿透楼板的构件
    for group_idx, group in enumerate(slab_groups):
        slab_top = group[-1]  # 楼板组的最上层
        slab_bottom = group[0]  # 楼板组的最下层
        above_z = slab_top + 1  # 楼板组的上方第一层

        if above_z >= depth:
            continue

        # 用楼板层中的门作为参考（门穿透楼板时，楼板层中也会有门）
        slab_doors = np.zeros((height, width), dtype=bool)
        for sz in group:
            slab_doors |= (data[sz] == VoxelGrid.DOOR)

        # 先清除楼板层中的门和窗（楼板不应该有门/窗穿透）
        for sz in group:
            door_in_slab = data[sz] == VoxelGrid.DOOR
            count = int(np.sum(door_in_slab))
            if count > 0:
                data[sz][door_in_slab] = VoxelGrid.EMPTY
                total_clipped += count
            # ★ 新增：清除楼板层中的窗（窗穿透楼板）
            window_in_slab = data[sz] == VoxelGrid.WINDOW
            count_win = int(np.sum(window_in_slab))
            if count_win > 0:
                data[sz][window_in_slab] = VoxelGrid.EMPTY
                total_clipped += count_win

        # 只清除楼板上方第一层中与楼板层门重叠的门（防止门穿透楼板）
        # 不清除更高层的门，因为建筑各层布局相同，门位置可能正常重叠
        above_layer = data[above_z]
        door_penetrating = (above_layer == VoxelGrid.DOOR) & slab_doors
        count = int(np.sum(door_penetrating))
        if count > 0:
            data[above_z][door_penetrating] = VoxelGrid.EMPTY
            total_clipped += count
            print(f"[楼板隔断] z={above_z} 层清除 {count} 个穿透门 (楼板组 z={group})")

        # 墙/窗：清除楼板上方第一层与下层重叠的
        if slab_bottom > 0:
            below_layer = data[slab_bottom - 1]
            slab_layer = data[slab_top]
            wall_penetrating = (
                ((above_layer == VoxelGrid.WALL) | (above_layer == VoxelGrid.WINDOW)) &
                ((below_layer == VoxelGrid.WALL) | (below_layer == VoxelGrid.WINDOW)) &
                (slab_layer != VoxelGrid.EMPTY)
            )
            count = int(np.sum(wall_penetrating))
            if count > 0:
                data[above_z][wall_penetrating] = VoxelGrid.EMPTY
                total_clipped += count
                print(f"[楼板隔断] z={above_z} 层清除 {count} 个穿透墙/窗 (楼板组 z={group})")

            # ★ 新增：楼板上方第一层的所有窗都清除（窗不应在楼板层）
            # 因为 seal_exterior 后该层可能变成楼板层，残留的窗会穿透楼板
            above_windows = above_layer == VoxelGrid.WINDOW
            win_count = int(np.sum(above_windows))
            if win_count > 0:
                data[above_z][above_windows] = VoxelGrid.EMPTY
                total_clipped += win_count
                print(f"[楼板隔断] z={above_z} 层清除 {win_count} 个残留窗 (楼板上方第一层)")

    print(f"[楼板隔断] 共清除 {total_clipped} 个穿透楼板的构件")


def fix_room_connectivity(grid):
    """修复房间连通性：对小连通域（<100体素）在与主区域之间的墙上开门。

    双墙结构下，某些小房间（卫生间、楼梯间等）的门可能未正确打通，
    导致人员被困。本函数检测小连通域并自动开门连通。
    """
    data = grid.data
    depth, height, width = data.shape
    passable_types = {VoxelGrid.EMPTY, VoxelGrid.DOOR, VoxelGrid.EXIT, VoxelGrid.STAIR}
    total_doors_added = 0

    for z in range(depth):
        layer = data[z]
        passable = np.isin(layer, list(passable_types))
        visited = np.zeros_like(passable, dtype=bool)
        components = []

        # 找所有连通域
        for y in range(height):
            for x in range(width):
                if passable[y, x] and not visited[y, x]:
                    comp = []
                    q = deque([(y, x)])
                    visited[y, x] = True
                    while q:
                        cy, cx = q.popleft()
                        comp.append((cy, cx))
                        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                            ny, nx = cy+dy, cx+dx
                            if 0 <= ny < height and 0 <= nx < width and passable[ny, nx] and not visited[ny, nx]:
                                visited[ny, nx] = True
                                q.append((ny, nx))
                    components.append(comp)

        if len(components) <= 1:
            continue

        # 找最大连通域（主区域）
        components.sort(key=len, reverse=True)
        main_comp = components[0]
        main_set = set(main_comp)

        # 对每个小连通域，找与主区域之间最薄的墙并开门
        for comp in components[1:]:
            if len(comp) >= 100:
                continue  # 只修补小房间

            comp_set = set(comp)
            best_wall = None
            best_thickness = 999

            # 从小连通域边缘出发，找通向主区域的最薄墙
            for cy, cx in comp:
                for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                    # 沿方向扫描，找墙的厚度
                    wall_thickness = 0
                    wall_positions = []
                    ny, nx = cy + dy, cx + dx
                    while 0 <= ny < height and 0 <= nx < width:
                        if layer[ny, nx] == VoxelGrid.WALL:
                            wall_thickness += 1
                            wall_positions.append((ny, nx))
                            ny += dy
                            nx += dx
                        elif (ny, nx) in main_set:
                            # 到达主区域
                            if wall_thickness > 0 and wall_thickness < best_thickness:
                                best_thickness = wall_thickness
                                best_wall = wall_positions[0]  # 在墙的第一个位置开门
                            break
                        else:
                            break

            if best_wall is not None:
                wy, wx = best_wall
                if layer[wy, wx] == VoxelGrid.WALL:
                    layer[wy, wx] = VoxelGrid.DOOR
                    total_doors_added += 1

    if total_doors_added > 0:
        print(f"[连通性修复] 共添加 {total_doors_added} 个门，打通小房间")


def fill_slab_gaps(grid):
    """填充楼板层的空气缺口（楼梯井除外）。

    楼板体素化不完整会导致楼层之间出现漏洞，人员可能进入楼板夹层。
    本函数检测楼板层中的空气体素，保留楼梯井（与上下层楼梯相邻），
    其余空气填充为墙（楼板）。
    """
    data = grid.data
    depth, height, width = data.shape
    total_filled = 0

    # 检测楼板层（非空气占比 > 80%）
    slab_layers = []
    for z in range(depth):
        ratio = np.sum(data[z] != VoxelGrid.EMPTY) / (width * height)
        if ratio > 0.80:
            slab_layers.append(z)

    for z in slab_layers:
        layer = data[z]
        air_positions = np.argwhere(layer == VoxelGrid.EMPTY)
        if len(air_positions) == 0:
            continue

        # 判断是否为屋顶层：上方没有活动楼层（非空气占比 <80%）
        is_roof = True
        for nz in range(z + 1, depth):
            ratio = np.sum(data[nz] != VoxelGrid.EMPTY) / (width * height)
            if ratio < 0.80:
                is_roof = False
                break

        # 识别楼梯井：与上下层楼梯相邻的空气
        stair_well = np.zeros((height, width), dtype=bool)
        for y, x in air_positions:
            for dz in [-1, 0, 1]:
                for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                    nz, ny, nx = z+dz, y+dy, x+dx
                    if 0 <= nz < depth and 0 <= ny < height and 0 <= nx < width:
                        if data[nz, ny, nx] == VoxelGrid.STAIR:
                            stair_well[y, x] = True
                            break
                if stair_well[y, x]:
                    break

        # 识别出口保护区域：出口周围 2 体素内的空气不填充，确保出口连通
        exit_protected = np.zeros((height, width), dtype=bool)
        exits = np.argwhere(layer == VoxelGrid.EXIT)
        for ey, ex in exits:
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    ny, nx = ey + dy, ex + dx
                    if 0 <= ny < height and 0 <= nx < width and layer[ny, nx] == VoxelGrid.EMPTY:
                        exit_protected[ny, nx] = True
        # 也检查上下层的出口
        for dz in [-1, 1]:
            nz = z + dz
            if 0 <= nz < depth:
                upper_exits = np.argwhere(data[nz] == VoxelGrid.EXIT)
                for ey, ex in upper_exits:
                    for dy in range(-2, 3):
                        for dx in range(-2, 3):
                            ny, nx = ey + dy, ex + dx
                            if 0 <= ny < height and 0 <= nx < width and layer[ny, nx] == VoxelGrid.EMPTY:
                                exit_protected[ny, nx] = True

        # 填充非楼梯井、非出口保护区域的空气缺口为墙
        gap_mask = (layer == VoxelGrid.EMPTY) & (~stair_well) & (~exit_protected)
        gap_count = int(np.sum(gap_mask))
        total_cells = width * height
        if is_roof:
            # 屋顶层完全填充，不保留空气（防止人员到达屋顶）
            need_keep = 0
        else:
            # 非屋顶层保留 7% 空气，使非空气占比 < 95%（避免被楼板检测逻辑排除）
            protected_air = int(np.sum(stair_well)) + int(np.sum(exit_protected))
            target_air = int(total_cells * 0.07)
            need_keep = max(0, target_air - protected_air)

        if gap_count > need_keep:
            gap_indices = np.argwhere(gap_mask)
            keep_mask = np.zeros_like(gap_mask, dtype=bool)
            for i in range(min(need_keep, len(gap_indices))):
                keep_mask[gap_indices[i][0], gap_indices[i][1]] = True
            fill_mask = gap_mask & (~keep_mask)
        else:
            fill_mask = gap_mask

        fill_count = int(np.sum(fill_mask))
        if fill_count > 0:
            layer[fill_mask] = VoxelGrid.WALL
            total_filled += fill_count

    if total_filled > 0:
        print(f"[楼板缺口填充] 共填充 {total_filled} 个楼板空气缺口（楼梯井保留）")


def seal_isolated_air(grid, max_size=500):
    """封闭被墙包围的孤立空气区域（无门/出口/楼梯）。

    seal_exterior 只能封闭与边界连通的外部空气，无法处理被墙包围的
    外部空气孤岛（如建筑凹进处、墙外残留空气）。本函数检测每层的
    小空气连通域，如果不包含门/出口/楼梯则视为外部并封闭。
    """
    from collections import deque
    data = grid.data
    depth, height, width = data.shape
    total_sealed = 0

    passable_types = {VoxelGrid.EMPTY, VoxelGrid.DOOR, VoxelGrid.EXIT, VoxelGrid.STAIR}

    for z in range(depth):
        layer = data[z]
        # 跳过楼板层（非空气占比 > 80%），只处理活动楼层的孤立空气
        non_empty_ratio = np.sum(layer != VoxelGrid.EMPTY) / (width * height)
        if non_empty_ratio > 0.80:
            continue
        visited = np.zeros((height, width), dtype=bool)

        for y in range(height):
            for x in range(width):
                if layer[y, x] != VoxelGrid.EMPTY or visited[y, x]:
                    continue

                # BFS 找连通域
                component = []
                has_door_or_exit = False
                q = deque([(y, x)])
                visited[y, x] = True
                while q:
                    cy, cx = q.popleft()
                    component.append((cy, cx))
                    # 四方向扩展空气连通域
                    for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                        ny, nx = cy + dy, cx + dx
                        if (0 <= ny < height and 0 <= nx < width and
                                not visited[ny, nx]):
                            t = layer[ny, nx]
                            if t == VoxelGrid.EMPTY:
                                visited[ny, nx] = True
                                q.append((ny, nx))
                            elif t in (VoxelGrid.DOOR, VoxelGrid.EXIT, VoxelGrid.STAIR):
                                has_door_or_exit = True

                # 如果连通域较小且无门/出口/楼梯，封闭为墙
                if len(component) <= max_size and not has_door_or_exit:
                    for cy, cx in component:
                        layer[cy, cx] = VoxelGrid.WALL
                    total_sealed += len(component)

    if total_sealed > 0:
        print(f"[孤立空气封闭] 共封闭 {total_sealed} 个孤立空气体素")


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


def post_process_cleanup(grid):
    """后处理：清除楼板层孤立门，验证出口连通性。
    
    1. 清除楼板层中上下都是墙的孤立门（体素化误差残留）
    2. 取消不与外部空气相邻的出口标记（楼梯间出口误判）
    """
    data = grid.data
    depth, height, width = data.shape
    
    # 1. 清除楼板层孤立门（门不应延伸到楼板层）
    slab_cleaned = 0
    for z in range(1, depth - 1):
        layer = data[z]
        non_empty = int(np.sum(layer != VoxelGrid.EMPTY))
        ratio = non_empty / (width * height)
        if ratio > 0.70:  # 楼板层或接近楼板层
            doors = np.argwhere(layer == VoxelGrid.DOOR)
            for y, x in doors:
                above = data[z+1, y, x]
                # 门在楼板层且上层是墙/楼板，说明门穿透了楼板，清除
                if above == VoxelGrid.WALL:
                    data[z, y, x] = VoxelGrid.EMPTY
                    slab_cleaned += 1
    if slab_cleaned > 0:
        print(f"[后处理] 清除楼板层孤立门 {slab_cleaned} 个")
    
    # 2. 验证出口连通性：取消整个出口组都不与外部空气相邻的出口
    exit_removed = 0
    for z in range(depth):
        layer = data[z]
        # 从边界洪水填充外部空气
        outside_air = np.zeros((height, width), dtype=bool)
        q = deque()
        for x in range(width):
            if layer[0, x] == VoxelGrid.EMPTY:
                q.append((0, x)); outside_air[0, x] = True
            if layer[height-1, x] == VoxelGrid.EMPTY:
                q.append((height-1, x)); outside_air[height-1, x] = True
        for y in range(height):
            if layer[y, 0] == VoxelGrid.EMPTY:
                q.append((y, 0)); outside_air[y, 0] = True
            if layer[y, width-1] == VoxelGrid.EMPTY:
                q.append((y, width-1)); outside_air[y, width-1] = True
        while q:
            cy, cx = q.popleft()
            for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                ny, nx = cy+dy, cx+dx
                if 0<=ny<height and 0<=nx<width:
                    if layer[ny,nx] == VoxelGrid.EMPTY and not outside_air[ny,nx]:
                        outside_air[ny,nx] = True
                        q.append((ny,nx))
        
        # 找出所有出口组（4连通域）
        visited = np.zeros((height, width), dtype=bool)
        exit_groups = []
        for y in range(height):
            for x in range(width):
                if layer[y,x] == VoxelGrid.EXIT and not visited[y,x]:
                    group = []
                    gq = deque([(y,x)])
                    visited[y,x] = True
                    while gq:
                        cy, cx = gq.popleft()
                        group.append((cy,cx))
                        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                            ny, nx = cy+dy, cx+dx
                            if 0<=ny<height and 0<=nx<width and layer[ny,nx]==VoxelGrid.EXIT and not visited[ny,nx]:
                                visited[ny,nx] = True
                                gq.append((ny,nx))
                    exit_groups.append(group)
        
        # 检查每个出口组是否与外部空气相邻
        for group in exit_groups:
            group_connected = False
            for y, x in group:
                for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                    ny, nx = y+dy, x+dx
                    if 0<=ny<height and 0<=nx<width and outside_air[ny,nx]:
                        group_connected = True
                        break
                if group_connected:
                    break
            if not group_connected:
                # 整个出口组都不与外部空气相邻，取消所有出口标记
                for y, x in group:
                    layer[y, x] = VoxelGrid.DOOR  # 恢复为门
                    exit_removed += 1
    
    if exit_removed > 0:
        print(f"[后处理] 取消未连通外部的出口标记 {exit_removed} 个")
    
    return slab_cleaned + exit_removed
