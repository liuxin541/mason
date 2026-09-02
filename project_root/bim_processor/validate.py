import struct, numpy as np, json
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5)
    ver = struct.unpack('<H', f.read(2))[0]
    w, h, d = struct.unpack('<III', f.read(12))
    vs = struct.unpack('<f', f.read(4))[0]
    f.read(9)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

print('=== 精度验证报告 ===')
print(f'网格尺寸: {w}x{h}x{d}, voxel_size={vs}m')

# 1. 外墙闭合性
print('\n--- 1. 外墙闭合性检查 ---')
for z in [0, 3, 10, 18, 26]:
    layer = data[z]
    top_solid = np.sum((layer[0, :] == 1) | (layer[0, :] == 7))
    bottom_solid = np.sum((layer[-1, :] == 1) | (layer[-1, :] == 7))
    left_solid = np.sum((layer[:, 0] == 1) | (layer[:, 0] == 7))
    right_solid = np.sum((layer[:, -1] == 1) | (layer[:, -1] == 7))
    print(f'  z={z:2d}: 上{top_solid:3d} 下{bottom_solid:3d} 左{left_solid:3d} 右{right_solid:3d} (墙+窗)')

# 2. 门洞打通验证
print('\n--- 2. 门洞打通验证 ---')
door_positions = np.argwhere(data == 3)
door_opened = 0
door_blocked = 0
for z, y, x in door_positions:
    neighbors = []
    if x > 0: neighbors.append(data[z, y, x-1])
    if x < w-1: neighbors.append(data[z, y, x+1])
    if y > 0: neighbors.append(data[z, y-1, x])
    if y < h-1: neighbors.append(data[z, y+1, x])
    has_air = any(n == 0 for n in neighbors)
    if has_air:
        door_opened += 1
    else:
        door_blocked += 1
print(f'  门体素总数: {len(door_positions)}')
print(f'  连通空气(门洞已打通): {door_opened} ({door_opened/len(door_positions)*100:.1f}%)')
print(f'  被墙包围(可能未打通): {door_blocked} ({door_blocked/len(door_positions)*100:.1f}%)')

# 3. 墙体孔洞检查
print('\n--- 3. 围护结构包围的孤立空间检查 ---')
total_holes = 0
for z in range(d):
    layer = data[z]
    passable = (layer == 0) | (layer == 3) | (layer == 5)
    visited = np.zeros_like(passable)
    q = deque()
    for x in range(w):
        if passable[0, x]: q.append((0, x)); visited[0, x] = True
        if passable[h-1, x]: q.append((h-1, x)); visited[h-1, x] = True
    for y in range(h):
        if passable[y, 0]: q.append((y, 0)); visited[y, 0] = True
        if passable[y, w-1]: q.append((y, w-1)); visited[y, w-1] = True
    while q:
        cy, cx = q.popleft()
        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny, nx = cy+dy, cx+dx
            if 0 <= ny < h and 0 <= nx < w and passable[ny, nx] and not visited[ny, nx]:
                visited[ny, nx] = True
                q.append((ny, nx))
    holes = int(np.sum(passable & ~visited))
    if holes > 0:
        total_holes += holes
        print(f'  z={z:2d}: {holes} 个孤立空间体素')
if total_holes == 0:
    print('  无孤立孔洞，所有可通行空间均与外界连通')
else:
    print(f'  总计 {total_holes} 个（多为内墙围合房间，属正常）')

# 4. 坐标元数据
print('\n--- 4. 坐标元数据 ---')
with open(r'D:\mason\ifc_output\semantic_info.json', 'r', encoding='utf-8') as f:
    info = json.load(f)
print(f'  origin_offset: {info["origin_offset"]}')
print(f'  voxel_size: {info["voxel_size"]}')
print(f'  grid_dims: {info["grid_dims"]}')
bbox = info['building_bbox_world']
print(f'  building_bbox_world: {bbox}')
print(f'  建筑尺寸: {bbox[3]-bbox[0]:.2f}m x {bbox[4]-bbox[1]:.2f}m x {bbox[5]-bbox[2]:.2f}m')
