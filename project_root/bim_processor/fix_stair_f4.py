"""修复楼梯垂直连通性，扩展范围从1层增加到3层"""
with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'r', encoding='utf-8') as f:
    content = f.read()

old = '''def fix_stair_vertical_connectivity(grid):
    """修复楼梯 z 方向连通性。

    根本原因：楼梯是斜面，体素化后各层的 x,y 位置会偏移（如 z=8 楼梯在 y=27~30，
    z=9 楼梯在 y=31~34），导致相邻层楼梯没有 (x,y) 重叠。Java 端要求"当前位置
    必须是楼梯才能上下移动"，因此人员在楼板层被卡住，无法下楼到出口。

    修复：对每个楼梯体素，在 z 方向上下各扩展 1 层（同一 x,y 位置），
    把墙/空气设为楼梯。这样人员可以站在扩展后的楼梯上水平移动到原楼梯位置，
    再继续上下楼。
    """
    data = grid.data
    stair_mask = (data == VoxelGrid.STAIR)

    # 向上扩展：z 层是楼梯 => z+1 层同一位置如果是墙/空气，设为楼梯
    expand_up = np.zeros_like(stair_mask)
    expand_up = stair_mask[:-1]

    # 向下扩展：z 层是楼梯 => z-1 层同一位置如果是墙/空气，设为楼梯
    expand_down = np.zeros_like(stair_mask)
    expand_down[:-1] = stair_mask

    expand = expand_up | expand_down
    # 只把墙和空气设为楼梯，保留门/出口/窗/柱等有意义的类型
    mask = expand & ((data == VoxelGrid.EMPTY) | (data == VoxelGrid.WALL))
    count = int(np.sum(mask))
    data[mask] = VoxelGrid.STAIR

    print(f"[楼梯修复] z 方向扩展 {count} 体素，确保各层楼梯连通")'''

new = '''def fix_stair_vertical_connectivity(grid):
    """修复楼梯 z 方向连通性。

    根本原因：楼梯是斜面，体素化后各层的 x,y 位置会偏移（如 z=8 楼梯在 y=27~30，
    z=9 楼梯在 y=31~34），导致相邻层楼梯没有 (x,y) 重叠。Java 端要求"当前位置
    必须是楼梯才能上下移动"，因此人员在楼板层被卡住，无法下楼到出口。

    修复：对每个楼梯体素，在 z 方向上下各扩展 3 层（同一 x,y 位置），
    把墙/空气设为楼梯。扩展3层确保楼梯连接到上方活动楼层（如F3→F4），
    因为 IFC 楼梯段可能只到楼层底部，需要延伸到楼层内部。
    """
    data = grid.data
    depth, height, width = data.shape
    stair_mask = (data == VoxelGrid.STAIR)

    # 向上扩展3层：z 层是楼梯 => z+1,z+2,z+3 层同一位置如果是墙/空气，设为楼梯
    expand_up = np.zeros_like(stair_mask)
    for offset in range(1, 4):
        if offset < depth:
            expand_up[offset:] |= stair_mask[:-offset]

    # 向下扩展3层：z 层是楼梯 => z-1,z-2,z-3 层同一位置如果是墙/空气，设为楼梯
    expand_down = np.zeros_like(stair_mask)
    for offset in range(1, 4):
        if offset < depth:
            expand_down[:-offset] |= stair_mask[offset:]

    expand = expand_up | expand_down
    # 只把墙和空气设为楼梯，保留门/出口/窗/柱等有意义的类型
    mask = expand & ((data == VoxelGrid.EMPTY) | (data == VoxelGrid.WALL))
    count = int(np.sum(mask))
    data[mask] = VoxelGrid.STAIR

    print(f"[楼梯修复] z 方向扩展 {count} 体素(上下各3层)，确保各层楼梯连通")'''

content = content.replace(old, new)

with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('楼梯垂直连通性修复已更新（扩展3层）')
