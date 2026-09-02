"""深入分析发现的问题"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7

print("=" * 60)
print("问题深入分析")
print("=" * 60)

# 问题1: 外部空气分布
print("\n【问题1: 与边界连通的外部空气分布】")
vis = np.zeros_like(data, dtype=bool)
q = deque()
for y in range(38):
    for x in range(80):
        for z in [0, 35]:
            if data[z,y,x] == EMPTY and not vis[z,y,x]:
                vis[z,y,x]=True; q.append((z,y,x))
for z in range(36):
    for y in [0,37]:
        for x in range(80):
            if data[z,y,x] == EMPTY and not vis[z,y,x]:
                vis[z,y,x]=True; q.append((z,y,x))
for z in range(36):
    for y in range(38):
        for x in [0,79]:
            if data[z,y,x] == EMPTY and not vis[z,y,x]:
                vis[z,y,x]=True; q.append((z,y,x))
outside_by_z = {}
while q:
    cz,cy,cx = q.popleft()
    outside_by_z[cz] = outside_by_z.get(cz, 0) + 1
    for dz,dy,dx in [(-1,0,0),(1,0,0),(0,-1,0),(0,1,0),(0,0,-1),(0,0,1)]:
        nz,ny,nx = cz+dz,cy+dy,cx+dx
        if 0<=nz<36 and 0<=ny<38 and 0<=nx<80 and data[nz,ny,nx]==EMPTY and not vis[nz,ny,nx]:
            vis[nz,ny,nx]=True; q.append((nz,ny,nx))
for z in sorted(outside_by_z.keys()):
    if outside_by_z[z] > 100:
        print(f"  z={z}: {outside_by_z[z]}个外部空气 {'⚠ 活动楼层' if z in [2,5,12,20,27] else ''}")

# 问题2: F1门高度异常
print("\n【问题2: F1门高度异常分布】")
door_heights = {}
for z in range(0, 8):
    doors = np.argwhere(data[z] == DOOR)
    for y,x in doors:
        key = (x,y)
        if key not in door_heights:
            door_heights[key] = [z, z]
        else:
            door_heights[key][1] = z
tall = [(k, v[1]-v[0]+1) for k,v in door_heights.items() if v[1]-v[0]+1 > 5]
print(f"  高度>2.5m的门: {len(tall)}个")
if tall:
    print(f"  示例(前5个): {[(k, h) for k,h in tall[:5]]}")
    # 检查这些门是否延伸到了楼板层(z=8)
    into_slab = sum(1 for k,v in door_heights.items() if v[1] >= 8)
    print(f"  延伸到楼板层(z>=8)的门: {into_slab}个")

# 问题3: 楼板层出口详情
print("\n【问题3: 楼板层未连通出口详情】")
for z in [10, 17, 24, 29]:
    exits = np.argwhere(data[z] == EXIT)
    print(f"  z={z}: {len(exits)}个出口, 位置={[(e[1],e[0]) for e in exits]}")
    # 检查周围
    for y,x in exits[:1]:
        print(f"    周围(3x3):")
        for dy in range(-1,2):
            row = []
            for dx in range(-1,2):
                ny,nx = y+dy, x+dx
                if 0<=ny<38 and 0<=nx<80:
                    t = data[z,ny,nx]
                    name = {0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t,'?')
                    row.append(name)
            print(f"      {''.join(row)}")
