with open('run.py', 'r', encoding='utf-8') as f:
    content = f.read()

# 更新导入
old_import = 'from voxelizer.builder import build_from_elements, build_test_map, seal_exterior, fix_stair_vertical_connectivity, clip_penetrating_elements, fix_room_connectivity, fill_slab_gaps'
new_import = 'from voxelizer.builder import build_from_elements, build_test_map, seal_exterior, fix_stair_vertical_connectivity, clip_penetrating_elements, fix_room_connectivity, fill_slab_gaps, seal_isolated_air'
content = content.replace(old_import, new_import)

# 在 seal_exterior 后添加 seal_isolated_air 调用
old = '        seal_exterior(grid)  # 封闭外部空气，防止人员在楼外生成'
new = old + '\n        seal_isolated_air(grid)  # 封闭被墙包围的孤立空气区域（无门/出口/楼梯）'
content = content.replace(old, new)

with open('run.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('修改完成')
