with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'r', encoding='utf-8') as f:
    content = f.read()

# 修改函数签名
old_sig = 'def _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type, pad_voxels=0.0):'
new_sig = 'def _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type, pad_voxels=0.0, pad_z=True):'
content = content.replace(old_sig, new_sig)

# 修改文档字符串
old_doc = '''    """将世界坐标 AABB 填充为指定体素类型。

    pad_voxels: 各方向膨胀量（体素单位），用于门窗洞口确保完全覆盖。
    """'''
new_doc = '''    """将世界坐标 AABB 填充为指定体素类型。

    pad_voxels: 各方向膨胀量（体素单位），用于门窗洞口确保完全覆盖。
    pad_z: 是否在 z 方向应用膨胀。门的 z 方向不应膨胀，避免门高超过实际高度。
    """'''
content = content.replace(old_doc, new_doc)

# 修改 z 方向计算
old_z = '''    pad = pad_voxels * voxel_size
    vx_start = max(0, int(math.floor((xmin - pad - ox) / voxel_size)))
    vy_start = max(0, int(math.floor((ymin - pad - oy) / voxel_size)))
    vz_start = max(0, int(math.floor((zmin - pad - oz) / voxel_size)))
    vx_end = min(grid.width - 1, int(math.floor((xmax + pad - ox) / voxel_size)))
    vy_end = min(grid.height - 1, int(math.floor((ymax + pad - oy) / voxel_size)))
    vz_end = min(grid.depth - 1, int(math.floor((zmax + pad - oz) / voxel_size)))'''
new_z = '''    pad = pad_voxels * voxel_size
    z_pad = pad if pad_z else 0
    vx_start = max(0, int(math.floor((xmin - pad - ox) / voxel_size)))
    vy_start = max(0, int(math.floor((ymin - pad - oy) / voxel_size)))
    vz_start = max(0, int(math.floor((zmin - z_pad - oz) / voxel_size)))
    vx_end = min(grid.width - 1, int(math.floor((xmax + pad - ox) / voxel_size)))
    vy_end = min(grid.height - 1, int(math.floor((ymax + pad - oy) / voxel_size)))
    vz_end = min(grid.depth - 1, int(math.floor((zmax + z_pad - oz) / voxel_size)))'''
content = content.replace(old_z, new_z)

# 修改门的填充调用
old_call = '''                _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type,
                           pad_voxels=OPENING_PAD)'''
new_call = '''                _fill_bbox(grid, bbox, origin_shift, voxel_size, voxel_type,
                           pad_voxels=OPENING_PAD, pad_z=(voxel_type != VoxelGrid.DOOR))'''
content = content.replace(old_call, new_call)

with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('修改完成')

# 验证
with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'r', encoding='utf-8') as f:
    c = f.read()
print('pad_z in sig:', 'pad_z=True' in c)
print('z_pad in body:', 'z_pad' in c)
print('pad_z in call:', 'pad_z=(voxel_type' in c)
