from PIL import Image, ImageDraw
import numpy as np

# 体素类型 -> 颜色映射（便于调试识别精度）
VOXEL_COLORS = {
    0: (0, 0, 0),          # EMPTY: 黑
    1: (120, 120, 140),    # WALL: 灰
    2: (255, 140, 0),      # FUEL: 橙
    3: (0, 120, 255),      # DOOR: 蓝（可通行开口）
    4: (0, 255, 0),        # EXIT: 绿
    5: (255, 0, 0),        # STAIR: 红
    6: (200, 150, 50),     # COLUMN: 棕黄
    7: (0, 200, 200),      # WINDOW: 青（围护、不可通行）
}


def generate_debug_slice(grid, z_index, output_path):
    """导出 Z=z_index 层的二维切片预览图。

    颜色编码：黑=空气, 灰=墙, 蓝=门, 绿=出口, 红=楼梯, 棕黄=柱, 青=窗, 橙=可燃物
    """
    size = (grid.width, grid.height)
    img = Image.new('RGB', size, color='black')
    draw = ImageDraw.Draw(img)

    for x in range(grid.width):
        for y in range(grid.height):
            val = grid.get_voxel(x, y, z_index)
            color = VOXEL_COLORS.get(val, (255, 255, 255))
            draw.point((x, y), fill=color)

    # 放大图片方便查看
    scale = max(1, min(20, 800 // max(grid.width, grid.height)))
    img = img.resize((grid.width * scale, grid.height * scale), Image.NEAREST)
    img.save(output_path)


def generate_debug_slices(grid, output_dir, z_indices=None):
    """批量导出多个 Z 层切片，便于多层建筑调试。

    z_indices: 要导出的 z 层列表；为 None 时自动选每层中间位置。
    """
    import os
    os.makedirs(output_dir, exist_ok=True)

    if z_indices is None:
        # 默认导出：底层、1/4、1/2、3/4、顶层
        z_indices = [0, grid.depth // 4, grid.depth // 2,
                     3 * grid.depth // 4, grid.depth - 1]

    for z in z_indices:
        if 0 <= z < grid.depth:
            path = os.path.join(output_dir, f'debug_slice_z{z:03d}.png')
            generate_debug_slice(grid, z, path)
