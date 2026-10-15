from PIL import Image, ImageDraw
import numpy as np

def generate_debug_slice(grid, z_index, output_path):
    """
    导出 Z=z_index 层的二维切片预览图。
    黄色=墙, 蓝色=门/出口, 红色=楼梯
    """
    size = (grid.width, grid.height)
    img = Image.new('RGB', size, color='black')
    draw = ImageDraw.Draw(img)

    for x in range(grid.width):
        for y in range(grid.height):
            val = grid.get_voxel(x, y, z_index)
            if val == grid.WALL:
                draw.point((x, y), fill='#4a4a5a') # 灰色
            elif val == grid.STAIR:
                draw.point((x, y), fill='#ff0000') # 红色楼梯
            elif val == grid.EXIT:
                draw.point((x, y), fill='#00ff00') # 绿色出口

    # 放大图片方便查看
    img = img.resize((grid.width * 10, grid.height * 10), Image.NEAREST)
    img.save(output_path)