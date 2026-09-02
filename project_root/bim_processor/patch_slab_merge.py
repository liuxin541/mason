with open(r'D:\mason\project_root\bim_processor\run.py', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        # 检测楼板层：非空气体素占比 > 80% 的层视为楼板
        floor_slabs = []
        for z in range(grid.depth):
            layer = grid.data[z]
            non_empty = int(np.sum(layer != VoxelGrid.EMPTY))
            ratio = non_empty / (grid.width * grid.height)
            if ratio > 0.80:
                floor_slabs.append(z)
        logger.log(f'[INFO] 检测到 {len(floor_slabs)} 个楼板层: z={floor_slabs}')"""

new = """        # 检测楼板层：非空气体素占比 > 80% 的层视为楼板
        # 合并连续的楼板层（如双层楼板、屋顶以上的全墙层），只保留每层的起始 z
        raw_slabs = []
        for z in range(grid.depth):
            layer = grid.data[z]
            non_empty = int(np.sum(layer != VoxelGrid.EMPTY))
            ratio = non_empty / (grid.width * grid.height)
            if ratio > 0.80:
                raw_slabs.append(z)
        # 合并连续的楼板层，只保留每组的第一个
        floor_slabs = []
        prev = -10
        for z in raw_slabs:
            if z - prev > 1:
                floor_slabs.append(z)
            prev = z
        logger.log(f'[INFO] 检测到 {len(floor_slabs)} 个楼板层: z={floor_slabs} (原始 {len(raw_slabs)} 层)')"""

content = content.replace(old, new)

with open(r'D:\mason\project_root\bim_processor\run.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
