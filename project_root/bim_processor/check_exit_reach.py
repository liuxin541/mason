import numpy as np
import struct

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5)
    ver = struct.unpack('<H', f.read(2))[0]
    w, h, d = struct.unpack('<III', f.read(12))
    vs = struct.unpack('<f', f.read(4))[0]
    f.read(9)
    data = np.frombuffer(f.read(w * h * d), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, FUEL, DOOR, EXIT, STAIR, COLUMN, WINDOW = range(8)

exits = np.argwhere(data == EXIT)
print(f'出口总数: {len(exits)}')
print()

passable_types = {EMPTY, DOOR, STAIR, EXIT}
reachable = 0
unreachable = 0
for z, y, x in exits:
    has_passable = False
    for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
        ny, nx = y + dy, x + dx
        if 0 <= ny < h and 0 <= nx < w and data[z, ny, nx] in passable_types:
            has_passable = True
            break
    if has_passable:
        reachable += 1
    else:
        unreachable += 1
        if unreachable <= 3:
            neighbors = []
            for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                ny, nx = y + dy, x + dx
                if 0 <= ny < h and 0 <= nx < w:
                    neighbors.append(int(data[z, ny, nx]))
                else:
                    neighbors.append(-1)
            print(f'  不可达出口: z={z},y={y},x={x}, 上下左右={neighbors}')

print(f'可达出口: {reachable}, 不可达出口: {unreachable}')

print()
print('出口所在层(z=2)的第一个出口周围5x5区域:')
z = 2
exit_positions = [(y, x) for zz, y, x in exits if zz == z]
if exit_positions:
    y0, x0 = exit_positions[0]
    for dy in range(-2, 3):
        row = []
        for dx in range(-2, 3):
            ny, nx = y0 + dy, x0 + dx
            if 0 <= ny < h and 0 <= nx < w:
                row.append(str(int(data[z, ny, nx])))
            else:
                row.append('-')
        print('  ' + ' '.join(row))
