"""检查具体的未连通出口和楼板层门位置"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

EMPTY, WALL, DOOR, EXIT, STAIR = 0, 1, 3, 4, 5
d, h, w = data.shape

# 1. 检查所有层的出口
print("【所有层的出口统计】")
for z in range(d):
    exits = np.sum(data[z] == EXIT)
    if exits > 0:
        # 检查连通性
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
        exit_pos = np.argwhere(layer == EXIT)
        connected = 0
        unconnected_pos = []
        for y, x in exit_pos:
            conn = False
            for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                ny,nx = y+dy,x+dx
                if 0<=ny<h and 0<=nx<w and outside[ny,nx]:
                    conn = True; break
            if conn:
                connected += 1
            else:
                unconnected_pos.append((int(x), int(y)))
        if unconnected_pos:
            print(f"  z={z}: 出口{exits}个, 连通{connected}个, 未连通{len(unconnected_pos)}个, 位置={unconnected_pos}")
            # 显示未连通出口周围
            for x, y in unconnected_pos[:1]:
                print(f"    位置({x},{y})周围5x5:")
                for dy in range(-2, 3):
                    row = []
                    for dx in range(-2, 3):
                        ny, nx = y+dy, x+dx
                        if 0<=ny<h and 0<=nx<w:
                            t = layer[ny,nx]
                            row.append({0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t,'?'))
                        else:
                            row.append('X')
                    print(f"      {''.join(row)}")

# 2. 检查楼板层门
print("\n【楼板层门位置】")
for z in [8, 9, 16, 23]:
    doors = np.argwhere(data[z] == DOOR)
    if len(doors) > 0:
        for y, x in doors:
            below = data[z-1, y, x] if z > 0 else -1
            above = data[z+1, y, x] if z < d-1 else -1
            name = {0:'空',1:'墙',3:'门',4:'出口',5:'楼梯'}
            print(f"  z={z} ({x},{y}): 下层={name.get(below,below)}, 上层={name.get(above,above)}")
