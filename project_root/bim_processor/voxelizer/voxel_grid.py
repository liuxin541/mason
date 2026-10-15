import numpy as np

class VoxelGrid:
    # 定义体素类型常量
    # WINDOW 单独保留语义：它与墙一样封闭建筑边界，但不能被当作门通行。
    EMPTY, WALL, FUEL, DOOR, EXIT, STAIR, COLUMN, WINDOW = range(8)
    
    def __init__(self, width, height, depth, voxel_size):
        self.width = int(width)
        self.height = int(height)
        self.depth = int(depth)
        self.voxel_size = float(voxel_size)
        # 使用 uint8 存储，极大节省内存
        self.data = np.zeros((self.depth, self.height, self.width), dtype=np.uint8)
        
    def set_voxel(self, x, y, z, value):
        if 0 <= x < self.width and 0 <= y < self.height and 0 <= z < self.depth:
            self.data[z, y, x] = value

    def get_voxel(self, x, y, z):
        if 0 <= x < self.width and 0 <= y < self.height and 0 <= z < self.depth:
            return self.data[z, y, x]
        return self.WALL # 边界视为墙
