"""修复楼板层门清除逻辑：只要上层是墙就清除（门不应延伸到楼板层）"""
with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'r', encoding='utf-8') as f:
    content = f.read()

old = '''    # 1. 清除楼板层孤立门
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
                        slab_cleaned += 1'''

new = '''    # 1. 清除楼板层孤立门（门不应延伸到楼板层）
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
                    slab_cleaned += 1'''

content = content.replace(old, new)

with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('楼板层门清除逻辑已改进')
