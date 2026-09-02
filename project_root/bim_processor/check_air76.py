"""检查 (76,14) 空气是否与外部连通"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

EMPTY, WALL, DOOR, EXIT, WINDOW = 0, 1, 3, 4, 7
d, h, w = data.shape

z = 3
layer = data[z]

# 显示 x=70~79, y=10~18
print(f"z={z} x=70~79, y=10~18:")
for y in range(10, 19):
    row = []
    for x in range(70, 80):
        t = layer[y, x]
        row.append({0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t,'?'))
    print(f"  y={y}: {''.join(row)}")

# 洪水填充外部空气
outside = np.zeros((h,w), dtype=bool)
q = deque()
for x in range(w):
    if layer[0,x]==EMPTY: q.append((0,x)); outside[0,x]=True
    if layer[h-1,x]==EMPTY: q.append((h-1,x)); outside[h-1,x]=True
for y in range(h):
    if layer[y,0]==EMPTY: q.append((y,0)); outside[y,0]=True
    if layer[y,w-1]==EMPTY: q.append((y,w-1)); outside[y,w-1]=True
while q:
    cy,cx = q.popleft()
    for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
        ny,nx = cy+dy,cx+dx
        if 0<=ny<h and 0<=nx<w and layer[ny,nx]==EMPTY and not outside[ny,nx]:
            outside[ny,nx]=True; q.append((ny,nx))

print(f"\n(76,14)空气: 类型={layer[14,76]}, 外部={outside[14,76]}")
print(f"(77,14)空气: 类型={layer[14,77]}, 外部={outside[14,77]}")
print(f"(78,14): 类型={layer[14,78]}, 外部={outside[14,78]}")
print(f"(79,14)边界: 类型={layer[14,79]}, 外部={outside[14,79]}")

# 检查 (76,14) 的连通域
if layer[14,76] == EMPTY:
    visited = np.zeros((h,w), dtype=bool)
    q2 = deque([(14,76)])
    visited[14,76] = True
    comp = []
    while q2:
        cy,cx = q2.popleft()
        comp.append((cy,cx))
        for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny,nx = cy+dy,cx+dx
            if 0<=ny<h and 0<=nx<w and layer[ny,nx]==EMPTY and not visited[ny,nx]:
                visited[ny,nx]=True; q2.append((ny,nx))
    print(f"\n(76,14)连通域大小={len(comp)}, 位置={comp}")
    reaches = any(cy==0 or cy==h-1 or cx==0 or cx==w-1 for cy,cx in comp)
    print(f"到达边界={reaches}")
