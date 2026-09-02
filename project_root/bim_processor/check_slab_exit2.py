import numpy as np
with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

print('z=10 出口(x=74~75,y=14)周围(5x5):')
for z in [9, 10, 11]:
    print(f'  z={z}:')
    for y in range(12, 17):
        row = []
        for x in range(72, 77):
            t = data[z, y, x]
            name = {0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t, '?')
            row.append(name)
        print(f'    y={y}: {" ".join(row)}')

# 检查 z=10 出口能否到达边界
print('\nz=10 出口BFS可达范围:')
from collections import deque
layer = data[10]
passable = np.isin(layer, [0, 3, 4, 5])
visited = np.zeros_like(passable, dtype=bool)
q = deque()
for y, x in np.argwhere(layer == 4):
    q.append((y, x)); visited[y, x] = True
reached = 0
while q:
    cy, cx = q.popleft()
    reached += 1
    for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
        ny, nx = cy+dy, cx+dx
        if 0 <= ny < 38 and 0 <= nx < 80 and passable[ny, nx] and not visited[ny, nx]:
            visited[ny, nx] = True
            q.append((ny, nx))
print(f'  可达体素数: {reached}')
print(f'  可达范围: x={np.min(np.where(visited)[1])}~{np.max(np.where(visited)[1])}, y={np.min(np.where(visited)[0])}~{np.max(np.where(visited)[0])}')
