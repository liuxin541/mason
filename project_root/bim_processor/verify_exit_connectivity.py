"""验证出口是否连通到建筑外部"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5); version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little'); h = int.from_bytes(f.read(4), 'little'); d = int.from_bytes(f.read(4), 'little')
    f.read(13)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, DOOR, EXIT, STAIR, WINDOW = 0, 1, 3, 4, 5, 7

print("=" * 60)
print("出口连通性验证")
print("=" * 60)

# 可通行类型：空气、门、出口、楼梯
passable_types = {EMPTY, DOOR, EXIT, STAIR}

# 对每个出口，检查是否能到达网格边界
exit_positions = np.argwhere(data == EXIT)
print(f"出口总数: {len(exit_positions)}")

# 按 z 层检查
for z in sorted(set(exit_positions[:,0])):
    layer = data[z]
    passable = np.isin(layer, list(passable_types))
    
    # 从所有出口出发，BFS 检查能到达的区域
    visited = np.zeros_like(passable, dtype=bool)
    q = deque()
    for y, x in np.argwhere(layer == EXIT):
        q.append((y, x))
        visited[y, x] = True
    
    reached_boundary = False
    max_reach = 0
    while q:
        cy, cx = q.popleft()
        # 检查是否到达网格边界
        if cy <= 0 or cy >= h-1 or cx <= 0 or cx >= w-1:
            reached_boundary = True
        max_reach = max(max_reach, cy, h-1-cy, cx, w-1-cx)
        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny, nx = cy+dy, cx+dx
            if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
                visited[ny, nx] = True
                q.append((ny, nx))
    
    reachable_count = int(np.sum(visited))
    print(f"\nz={z}: 出口数={int(np.sum(layer == EXIT))}, "
          f"从出口可达区域={reachable_count}体素, "
          f"到达网格边界={'是' if reached_boundary else '否'}, "
          f"最远距离边界={max_reach}体素")
    
    if not reached_boundary:
        print(f"  ⚠ 出口未连通到建筑外部！")
        # 检查出口北侧是什么
        exits = np.argwhere(layer == EXIT)
        if len(exits) > 0:
            y, x = exits[0]
            print(f"  出口 at (x={x}, y={y}):")
            for dy, dx, dir_name in [(-1,0,'北'),(1,0,'南'),(0,-1,'西'),(0,1,'东')]:
                for dist in range(1, 6):
                    ny, nx = y+dy*dist, x+dx*dist
                    if ny < 0 or ny >= h or nx < 0 or nx >= w:
                        print(f"    向{dir_name} dist={dist}: 越界(到达外部)")
                        break
                    t = layer[ny, nx]
                    name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
                    if t == WALL:
                        print(f"    向{dir_name} dist={dist}: {name} (被阻挡)")
                        break
                    if dist == 5:
                        print(f"    向{dir_name} dist=5: 仍未到边界")

# 检查 z=2 层出口北侧通道
print("\n" + "=" * 60)
print("z=2 层出口北侧通道详细检查")
print("=" * 60)
z = 2
exits = np.argwhere(data[z] == EXIT)
if len(exits) > 0:
    y, x = exits[0]
    print(f"出口 at (x={x}, y={y})")
    print("向北扫描:")
    for ny in range(y, -1, -1):
        t = data[z, ny, x]
        name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
        print(f"  y={ny}: {name}")
        if ny == 0:
            print(f"  → 到达网格边界（建筑外部）")
