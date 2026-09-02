import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

z = 12
layer = data[z]
non_empty = np.sum(layer != 0)
ratio = non_empty / (38 * 80)
print(f'z={z} 非空气占比: {ratio*100:.1f}% ({"跳过楼板层" if ratio > 0.8 else "处理"})')

# 找 x=5, y=4 所在的连通域
visited = np.zeros((38, 80), dtype=bool)
q = deque([(4, 5)])
visited[4, 5] = True
component = []
has_door = False
while q:
    cy, cx = q.popleft()
    component.append((cy, cx))
    for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
        ny, nx = cy+dy, cx+dx
        if 0 <= ny < 38 and 0 <= nx < 80 and not visited[ny, nx]:
            t = layer[ny, nx]
            if t == 0:
                visited[ny, nx] = True
                q.append((ny, nx))
            elif t in (3, 4, 5):
                has_door = True
                print(f'  发现门/出口/楼梯 at ({ny},{nx}), 类型={t}')

print(f'连通域大小: {len(component)}')
print(f'有门/出口/楼梯: {has_door}')
print(f'连通域范围: x={min(c[1] for c in component)}~{max(c[1] for c in component)}, y={min(c[0] for c in component)}~{max(c[0] for c in component)}')

# 检查连通域边缘的体素类型
print('\n连通域边缘相邻体素:')
for cy, cx in component:
    for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
        ny, nx = cy+dy, cx+dx
        if 0 <= ny < 38 and 0 <= nx < 80:
            t = layer[ny, nx]
            if t != 0:
                name = {1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
                if t in (3,4,5):
                    print(f'  ({cy},{cx}) -> ({ny},{nx}): {name}')
