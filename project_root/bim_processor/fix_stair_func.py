"""直接替换 fix_stair_vertical_connectivity 函数体"""
with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 找到函数开始和结束
start_idx = None
end_idx = None
for i, line in enumerate(lines):
    if 'def fix_stair_vertical_connectivity' in line:
        start_idx = i
    if start_idx is not None and i > start_idx and line.startswith('def ') and 'fix_stair' not in line:
        end_idx = i
        break

print(f"函数位置: 行{start_idx+1}~{end_idx}")

# 新函数内容
new_func = '''def fix_stair_vertical_connectivity(grid):
    """修复楼梯 z 方向连通性。

    根本原因：楼梯是斜面，体素化后各层的 x,y 位置会偏移，导致相邻层楼梯
    没有 (x,y) 重叠。Java 端要求"当前位置必须是楼梯才能上下移动"，因此
    人员在楼板层被卡住，无法上下楼。

    修复：对每个楼梯体素，在 z 方向上下各扩展 3 层（同一 x,y 位置），
    把墙/空气设为楼梯。扩展3层确保楼梯连接到上方活动楼层（如F3→F4），
    因为 IFC 楼梯段可能只到楼层底部，需要延伸到楼层内部。
    """
    data = grid.data
    depth, height, width = data.shape
    stair_mask = (data == VoxelGrid.STAIR)

    # 向上扩展3层
    expand_up = np.zeros_like(stair_mask)
    for offset in range(1, 4):
        if offset < depth:
            expand_up[offset:] |= stair_mask[:-offset]

    # 向下扩展3层
    expand_down = np.zeros_like(stair_mask)
    for offset in range(1, 4):
        if offset < depth:
            expand_down[:-offset] |= stair_mask[offset:]

    expand = expand_up | expand_down
    mask = expand & ((data == VoxelGrid.EMPTY) | (data == VoxelGrid.WALL))
    count = int(np.sum(mask))
    data[mask] = VoxelGrid.STAIR

    print(f"[楼梯修复] z 方向扩展 {count} 体素(上下各3层)，确保各层楼梯连通")


'''

# 替换
new_lines = lines[:start_idx] + [new_func] + lines[end_idx:]

with open(r'D:\mason\project_root\bim_processor\voxelizer\builder.py', 'w', encoding='utf-8') as f:
    f.writelines(new_lines)

print("函数已替换为3层扩展版本")
