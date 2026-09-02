"""调试出口(74,14)为什么没被取消，并修复z=8门"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

EMPTY, WALL, DOOR, EXIT = 0, 1, 3, 4
d, h, w = data.shape

# 检查 z=3 层 (74,14) 出口组的连通性
z = 3
layer = data[z]
print(f"z={z} 层 (74,14) 周围:")
for y in range(10, 18):
    row = []
    for x in range(69, 78):
        t = layer[y, x]
        row.append({0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t,'?'))
    print(f"  y={y}: {''.join(row)} x=69~77")

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

# 检查出口组
print(f"\n出口组(73,14),(74,14)与外部空气相邻检查:")
for x, y in [(73,14),(74,14)]:
    print(f"  ({x},{y}):")
    for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
        ny,nx = y+dy,x+dx
        if 0<=ny<h and 0<=nx<w:
            t = layer[ny,nx]
            is_out = outside[ny,nx]
            name = {0:'空',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t,str(t))
            print(f"    方向({dy},{dx})=({nx},{ny}): {name}, 外部={is_out}")

# 检查 (75,14) 空气的连通域
print(f"\n(75,14)空气的连通域大小:")
if layer[14,75] == EMPTY:
    visited = np.zeros((h,w), dtype=bool)
    q2 = deque([(14,75)])
    visited[14,75] = True
    size = 0
    while q2:
        cy,cx = q2.popleft()
        size += 1
        for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny,nx = cy+dy,cx+dx
            if 0<=ny<h and 0<=nx<w and layer[ny,nx]==EMPTY and not visited[ny,nx]:
                visited[ny,nx]=True; q2.append((ny,nx))
    print(f"  连通域大小={size}")
    # 检查是否到达边界
    reaches_boundary = any(cy==0 or cy==h-1 or cx==0 or cx==w-1 for cy in range(h) for cx in range(w) if visited[cy,cx])
    print(f"  到达边界={reaches_boundary}")
