with open('run.py', 'r', encoding='utf-8') as f:
    content = f.read()

old = '''        # 过滤：只保留上方楼层内有门(DOOR=3)的楼板
        # 屋顶层只有出口没有门，不应作为独立楼层显示
        floor_slabs = []
        for i, slab_z in enumerate(merged_slabs):
            next_slab = merged_slabs[i + 1] if i + 1 < len(merged_slabs) else grid.depth
            has_door = False
            for z in range(slab_z + 1, next_slab):
                layer = grid.data[z]
                if int(np.sum(layer == 3)) > 0:
                    has_door = True
                    break
            if has_door:
                floor_slabs.append(slab_z)'''

new = '''        # 过滤：只保留上方有真正活动楼层（非楼板层）且有门的楼板
        # 屋顶楼板上方是全墙层，不应作为独立楼层显示
        floor_slabs = []
        for i, slab_z in enumerate(merged_slabs):
            # 找到当前楼板组的最后一层（连续楼板）
            slab_end = slab_z
            while slab_end + 1 < grid.depth:
                ratio = np.sum(grid.data[slab_end + 1] != VoxelGrid.EMPTY) / (grid.width * grid.height)
                if ratio > 0.80:
                    slab_end += 1
                else:
                    break
            # 检查楼板组上方是否有活动楼层（非空气占比 < 80%）且有门
            next_slab = merged_slabs[i + 1] if i + 1 < len(merged_slabs) else grid.depth
            has_active_floor_with_door = False
            for z in range(slab_end + 1, next_slab):
                layer = grid.data[z]
                ratio = np.sum(layer != VoxelGrid.EMPTY) / (grid.width * grid.height)
                if ratio < 0.80 and int(np.sum(layer == 3)) > 0:
                    has_active_floor_with_door = True
                    break
            if has_active_floor_with_door:
                floor_slabs.append(slab_z)'''

content = content.replace(old, new)
with open('run.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('修改完成')
