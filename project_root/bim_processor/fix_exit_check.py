"""改进出口验证逻辑：检查整个出口组是否与外部空气相邻"""
with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'r', encoding='utf-8') as f:
    content = f.read()

# 找到 post_process_cleanup 函数中的出口验证部分并替换
old_exit_check = '''    # 2. 验证出口连通性：取消不与外部空气相邻的出口
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
                    exit_removed += 1'''

new_exit_check = '''    # 2. 验证出口连通性：取消整个出口组都不与外部空气相邻的出口
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
                    exit_removed += 1'''

content = content.replace(old_exit_check, new_exit_check)

with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('出口验证逻辑已改进')
