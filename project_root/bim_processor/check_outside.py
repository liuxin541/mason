import struct
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5)
    ver = struct.unpack('<H', f.read(2))[0]
    w, h, d = struct.unpack('<III', f.read(12))
    vs = struct.unpack('<f', f.read(4))[0]
    f.read(9)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

print(f'网格: {w}x{h}x{d}')

# 3D 洪水填充：从六个面的空气体素(0)开始，只通过空气连通
# 障碍物：墙(1)、门(3)、楼梯(5)、柱(6)、窗(7) — 都不通过
passable_mask = (data == 0)  # 只有空气可传播
visited = np.zeros_like(passable_mask, dtype=bool)

q = deque()
# 六个面
for x in range(w):
    for y in range(h):
        if passable_mask[0, y, x]:
            q.append((0, y, x)); visited[0, y, x] = True
        if passable_mask[d-1, y, x]:
            q.append((d-1, y, x)); visited[d-1, y, x] = True
for z in range(d):
    for x in range(w):
        if passable_mask[z, 0, x]:
            q.append((z, 0, x)); visited[z, 0, x] = True
        if passable_mask[z, h-1, x]:
            q.append((z, h-1, x)); visited[z, h-1, x] = True
for z in range(d):
    for y in range(h):
        if passable_mask[z, y, 0]:
            q.append((z, y, 0)); visited[z, y, 0] = True
        if passable_mask[z, y, w-1]:
            q.append((z, y, w-1)); visited[z, y, w-1] = True

while q:
    cz, cy, cx = q.popleft()
    for dz, dy, dx in [(-1,0,0),(1,0,0),(0,-1,0),(0,1,0),(0,0,-1),(0,0,1)]:
        nz, ny, nx = cz+dz, cy+dy, cx+dx
        if 0 <= nz < d and 0 <= ny < h and 0 <= nx < w:
            if passable_mask[nz, ny, nx] and not visited[nz, ny, nx]:
                visited[nz, ny, nx] = True
                q.append((nz, ny, nx))

outside_air = np.sum(visited & passable_mask)
inside_air = np.sum(passable_mask & ~visited)
total_air = np.sum(data == 0)

print(f'\n空气体素总数: {total_air}')
print(f'  建筑外部空气(与边界连通): {outside_air} ({outside_air/total_air*100:.1f}%)')
print(f'  建筑内部空气(被墙围合): {inside_air} ({inside_air/total_air*100:.1f}%)')
print(f'\nJava端当前逻辑: 非墙非楼梯=内部, 因此 {outside_air} 个外部空气被误判为内部!')
print(f'这就是人员在楼外生成/移动的根本原因。')

# 检查每层的外部空气分布
print(f'\n各层外部空气分布:')
for z in range(d):
    out_cnt = int(np.sum(visited[z] & passable_mask[z]))
    in_cnt = int(np.sum(passable_mask[z] & ~visited[z]))
    if out_cnt > 0 or in_cnt > 0:
        print(f'  z={z:2d}: 外部空气={out_cnt:4d}  内部空气={in_cnt:4d}')
