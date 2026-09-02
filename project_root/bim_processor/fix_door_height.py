with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'r', encoding='utf-8') as f:
    content = f.read()

# 修改 _fill_bbox 函数签名和实现
old = '''def _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type,
               pad_voxels=0.0):
    """将世界坐标 AABB 填充为指定体素类型。

    pad_voxels: 各方向膨胀量（体素单位），用于门窗洞口确保完全覆盖。
    """
    ox, oy, oz = origin_shift
    xmin, ymin, zmin, xmax, ymax, zmax = bbox

    pad = pad_voxels * voxel_size
    vx_start = max(0, int(math.floor((xmin - pad - ox) / voxel_size)))
    vy_start = max(0, int(math.floor((ymin - pad - oy) / voxel_size)))
    vz_start = max(0, int(math.floor((zmin - pad - oz) / voxel_size)))
    vx_end = min(grid.width - 1, int(math.floor((xmax + pad - ox) / voxel_size)))
    vy_end = min(grid.height - 1, int(math.floor((ymax + pad - oy) / voxel_size)))
    vz_end = min(grid.depth - 1, int(math.floor((zmax + pad - oz) / voxel_size)))'''

new = '''def _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type,
               pad_voxels=0.0, pad_z=True):
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
    vz_end = min(grid.depth - 1, int(math.floor((zmax + z_pad - oz) / voxel_size)))'''

content = content.replace(old, new)

# 修改门的填充调用，添加 pad_z=False
old_call = '''                # 门/窗：用 bbox 填充洞口，x-y 方向微量膨胀避免边缘残留墙体
                _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type,
                           pad_voxels=OPENING_PAD)'''

new_call = '''                # 门/窗：用 bbox 填充洞口，x-y 方向微量膨胀避免边缘残留墙体
                # 门的 z 方向不膨胀，避免门高超过实际高度（2.1m门+0.75m膨胀=3.6m，覆盖整层）
                _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type,
                           pad_voxels=OPENING_PAD, pad_z=(voxel_type != VoxelGrid.DOOR))'''

content = content.replace(old_call, new_call)

with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('修改完成')
