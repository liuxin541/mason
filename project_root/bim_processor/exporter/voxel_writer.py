import struct
import os

def write_voxel(grid, filepath):
    """
    写入自定义二进制 .voxel 格式。
    头结构 (32 字节): 
        magic(5), version(H), width(I), height(I), depth(I), voxel_size(f), reserved(9)
    数据块: 连续写入 uint8 数据 (按 z,y,x 顺序)
    """
    with open(filepath, 'wb') as f:
        # 写入 Header
        f.write(b'VOXEL')
        f.write(struct.pack('<H', 1)) # version
        f.write(struct.pack('<I', grid.width))
        f.write(struct.pack('<I', grid.height))
        f.write(struct.pack('<I', grid.depth))
        f.write(struct.pack('<f', grid.voxel_size))
        f.write(b'\x00' * 9) # reserved
        
        # 写入数据块 (z-y-x 循环)
        f.write(grid.data.tobytes())