with open(r'D:\mason\project_root\bim_processor\run.py', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        # 合并连续的楼板层，只保留每组的第一个
        floor_slabs = []
        prev = -10
        for z in raw_slabs:
            if z - prev > 1:
                floor_slabs.append(z)
            prev = z
        logger.log(f'[INFO] 检测到 {len(floor_slabs)} 个楼板层: z={floor_slabs} (原始 {len(raw_slabs)} 层)')"""

new = """        # 合并连续的楼板层，只保留每组的第一个
        merged_slabs = []
        prev = -10
        for z in raw_slabs:
            if z - prev > 1:
                merged_slabs.append(z)
            prev = z
        # 过滤：只保留上方楼层内有门(DOOR=3)或出口(EXIT=4)的楼板
        # 屋顶设备层（如机房）没有门，不应作为独立楼层显示
        floor_slabs = []
        for i, slab_z in enumerate(merged_slabs):
            next_slab = merged_slabs[i + 1] if i + 1 < len(merged_slabs) else grid.depth
            has_door = False
            for z in range(slab_z + 1, next_slab):
                layer = grid.data[z]
                if int(np.sum((layer == 3) | (layer == 4))) > 0:
                    has_door = True
                    break
            if has_door:
                floor_slabs.append(slab_z)
        logger.log(f'[INFO] 检测到 {len(floor_slabs)} 个楼板层: z={floor_slabs} (合并后 {len(merged_slabs)} 层, 原始 {len(raw_slabs)} 层)')"""

content = content.replace(old, new)

with open(r'D:\mason\project_root\bim_processor\run.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
