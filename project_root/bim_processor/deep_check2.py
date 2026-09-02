"""深入检查楼板层门穿透和出口连通问题"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

EMPTY, WALL, DOOR, EXIT, STAIR = 0, 1, 3, 4, 5
d, h, w = data.shape

# 1. 楼板层门穿透位置
print("【楼板层门穿透位置】")
for z in [8, 9, 16, 23]:
    doors = np.argwhere(data[z] == DOOR)
    if len(doors) > 0:
        print(f"  z={z}: {[(int(x),int(y)) for y,x in doors]}")
        # 检查上下层
        for y, x in doors:
            below = data[z-1, y, x] if z > 0 else -1
            above = data[z+1, y, x] if z < d-1 else -1
            name = {0:'空',1:'墙',3:'门',4:'出口',5:'楼梯'}
            print(f"    ({x},{y}): 下层z={z-1}={name.get(below,below)}, 上层z={z+1}={name.get(above,above)}")

# 2. 未连通出口位置
print("\n【未连通外部的出口位置】")
floor_ranges = [(0,7),(11,15),(18,22),(25,28)]
floor_names = ['F1','F2','F3','F4']
for fi, (zs, ze) in enumerate(floor_ranges):
    z = (zs + ze) // 2
    layer = data[z]
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
    exits = np.argwhere(layer == EXIT)
    for y, x in exits:
        connected = False
        for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny,nx = y+dy,x+dx
            if 0<=ny<h and 0<=nx<w and outside[ny,nx]:
                connected = True
                break
        if not connected:
            # 检查周围3x3
            print(f"  {floor_names[fi]}(z={z}) 出口({x},{y})未连通外部")
            print(f"    周围3x3:")
            for dy in range(-1, 2):
                row = []
                for dx in range(-1, 2):
                    ny, nx = y+dy, x+dx
                    if 0<=ny<h and 0<=nx<w:
                        t = layer[ny,nx]
                        row.append({0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t,'?'))
                    else:
                        row.append('X')
                print(f"      {''.join(row)}")

# 3. 楼层连通域分析（判断是否正常）
print("\n【楼层连通域分析】")
for fi, (zs, ze) in enumerate(floor_ranges):
    z = (zs + ze) // 2
    layer = data[z]
    visited = np.zeros_like(layer, dtype=bool)
    comps = []
    for y in range(h):
        for x in range(w):
            if layer[y,x] == EMPTY and not visited[y,x]:
                size = 0
                q = deque([(y,x)])
                visited[y,x] = True
                while q:
                    cy,cx = q.popleft()
                    size += 1
                    for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                        ny,nx = cy+dy,cx+dx
                        if 0<=ny<h and 0<=nx<w and layer[ny,nx]==EMPTY and not visited[ny,nx]:
                            visited[ny,nx]=True; q.append((ny,nx))
                comps.append(size)
    comps.sort(reverse=True)
    print(f"  {floor_names[fi]}(z={z}): {len(comps)}个连通域, 最大5个={comps[:5]}")
    small = sum(1 for c in comps if c < 50)
    print(f"    小于50体素的小连通域: {small}个")
