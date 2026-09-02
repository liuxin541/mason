with open(r'D:\mason\project_root\bim_processor\run.py', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        # 从体素数据提取楼梯位置（去重 x,y 坐标）
        stair_coords = set()
        stair_indices = np.argwhere(grid.data == VoxelGrid.STAIR)
        for z, y, x in stair_indices:
            stair_coords.add((int(x), int(y)))
        stair_positions = [list(p) for p in sorted(stair_coords)]
        logger.log(f'[INFO] 提取到 {len(stair_positions)} 个楼梯水平位置')

        semantic_info = {
            "floors": floor_count,
            "floor_elevations": floor_elevations,
            "stair_positions": stair_positions,"""

new = """        # 从体素数据提取楼梯位置（去重 x,y 坐标）
        stair_coords = set()
        stair_indices = np.argwhere(grid.data == VoxelGrid.STAIR)
        for z, y, x in stair_indices:
            stair_coords.add((int(x), int(y)))
        stair_positions = [list(p) for p in sorted(stair_coords)]
        logger.log(f'[INFO] 提取到 {len(stair_positions)} 个楼梯水平位置')

        # 检测楼板层：非空气体素占比 > 80% 的层视为楼板
        floor_slabs = []
        for z in range(grid.depth):
            layer = grid.data[z]
            non_empty = int(np.sum(layer != VoxelGrid.EMPTY))
            ratio = non_empty / (grid.width * grid.height)
            if ratio > 0.80:
                floor_slabs.append(z)
        logger.log(f'[INFO] 检测到 {len(floor_slabs)} 个楼板层: z={floor_slabs}')

        semantic_info = {
            "floors": floor_count,
            "floor_elevations": floor_elevations,
            "floor_slabs": floor_slabs,
            "stair_positions": stair_positions,"""

content = content.replace(old, new)

with open(r'D:\mason\project_root\bim_processor\run.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
