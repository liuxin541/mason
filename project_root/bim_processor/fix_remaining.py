"""修复剩余两个问题：楼板层孤立门 + 楼梯间出口误判"""
import re

with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'r', encoding='utf-8') as f:
    content = f.read()

# 在文件末尾添加后处理函数
new_func = '''

def post_process_cleanup(grid):
    """后处理：清除楼板层孤立门，验证出口连通性。
    
    1. 清除楼板层中上下都是墙的孤立门（体素化误差残留）
    2. 取消不与外部空气相邻的出口标记（楼梯间出口误判）
    """
    data = grid.data
    depth, height, width = data.shape
    
    # 1. 清除楼板层孤立门
    slab_cleaned = 0
    for z in range(1, depth - 1):
        layer = data[z]
        non_empty = int(np.sum(layer != VoxelGrid.EMPTY))
        ratio = non_empty / (width * height)
        if ratio > 0.70:  # 楼板层或接近楼板层
            doors = np.argwhere(layer == VoxelGrid.DOOR)
            for y, x in doors:
                below = data[z-1, y, x]
                above = data[z+1, y, x]
                # 如果上下都是墙/楼板，说明是孤立门，清除
                if below in (VoxelGrid.WALL, VoxelGrid.EMPTY) and above in (VoxelGrid.WALL, VoxelGrid.EMPTY):
                    # 进一步检查：如果上下都是墙，则清除
                    if below == VoxelGrid.WALL and above == VoxelGrid.WALL:
                        data[z, y, x] = VoxelGrid.EMPTY
                        slab_cleaned += 1
    if slab_cleaned > 0:
        print(f"[后处理] 清除楼板层孤立门 {slab_cleaned} 个")
    
    # 2. 验证出口连通性：取消不与外部空气相邻的出口
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
        
        # 检查出口是否与外部空气相邻
        exits = np.argwhere(layer == VoxelGrid.EXIT)
        for y, x in exits:
            connected = False
            for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                ny, nx = y+dy, x+dx
                if 0<=ny<height and 0<=nx<width and outside_air[ny,nx]:
                    connected = True
                    break
            if not connected:
                # 检查是否在出口组的边缘（出口组可能有多个连续体素）
                # 只取消不与外部空气相邻且不与其他出口相邻的出口
                has_exit_neighbor = False
                for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                    ny, nx = y+dy, x+dx
                    if 0<=ny<height and 0<=nx<width and layer[ny,nx] == VoxelGrid.EXIT:
                        has_exit_neighbor = True
                        break
                if not has_exit_neighbor:
                    layer[y, x] = VoxelGrid.DOOR  # 恢复为门
                    exit_removed += 1
    
    if exit_removed > 0:
        print(f"[后处理] 取消未连通外部的出口标记 {exit_removed} 个")
    
    return slab_cleaned + exit_removed
'''

content += new_func

with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('builder.py 添加后处理函数完成')

# 修改 run.py，在调用链末尾添加 post_process_cleanup
with open(r'D:\mason\project_root\bim_processor\run.py', 'r', encoding='utf-8') as f:
    run_content = f.read()

# 找到 fix_room_connectivity 调用后添加
old = '''    # 7. 小连通域自动开门（必须在最后，确保所有房间连通）
    fix_room_connectivity(grid)'''

new = '''    # 7. 小连通域自动开门（必须在最后，确保所有房间连通）
    fix_room_connectivity(grid)
    
    # 8. 后处理：清除楼板层孤立门，验证出口连通性
    post_process_cleanup(grid)'''

if old in run_content:
    run_content = run_content.replace(old, new)
    print('run.py 添加 post_process_cleanup 调用完成')
else:
    print('run.py 未找到 fix_room_connectivity 调用，尝试其他匹配')
    # 尝试找到导入语句
    if 'from voxelizer.builder import' in run_content:
        # 在导入中添加 post_process_cleanup
        old_import = 'from voxelizer.builder import ('
        # 找到导入块
        import_start = run_content.find(old_import)
        if import_start >= 0:
            import_end = run_content.find(')', import_start)
            import_block = run_content[import_start:import_end+1]
            if 'post_process_cleanup' not in import_block:
                new_import = import_block[:-1] + '    post_process_cleanup,\n)'
                run_content = run_content[:import_start] + new_import + run_content[import_end+1:]
                print('run.py 导入添加 post_process_cleanup 完成')

with open(r'D:\mason\project_root\bim_processor\run.py', 'w', encoding='utf-8') as f:
    f.write(run_content)

print('修复完成')
