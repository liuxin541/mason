"""BIM 建模转换综合问题检查"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

EMPTY, WALL, DOOR, EXIT, STAIR, COLUMN, WINDOW = 0, 1, 3, 4, 5, 6, 7
d, h, w = data.shape

print("=" * 60)
print("BIM 建模转换问题综合检查")
print("=" * 60)

# 1. 基本统计
print("\n【1. 基本统计】")
for t, name in [(EMPTY,'空气'),(WALL,'墙'),(DOOR,'门'),(EXIT,'出口'),(STAIR,'楼梯'),(COLUMN,'柱'),(WINDOW,'窗')]:
    cnt = np.sum(data == t)
    print(f"  {name}: {cnt}")

# 2. 门高度检查
print("\n【2. 门高度检查】")
floor_ranges = [(0,7),(11,15),(18,22),(25,28)]
floor_names = ['F1','F2','F3','F4']
for fi, (zs, ze) in enumerate(floor_ranges):
    door_heights = {}
    for z in range(zs, ze+1):
        doors = np.argwhere(data[z] == DOOR)
        for y, x in doors:
            key = (int(x), int(y))
            if key not in door_heights:
                door_heights[key] = [z, z]
            else:
                door_heights[key][1] = z
    heights = [v[1]-v[0]+1 for v in door_heights.values()]
    if heights:
        print(f"  {floor_names[fi]}: 门位置数={len(heights)}, 高度范围={min(heights)}~{max(heights)}体素({min(heights)*0.5:.1f}~{max(heights)*0.5:.1f}m)")
        tall = sum(1 for h in heights if h >= 7)
        if tall:
            print(f"    ⚠ 高度>=7体素(3.5m)的门: {tall}个")
        else:
            print(f"    ✓ 无超高门")

# 3. 楼板层穿透检查
print("\n【3. 楼板层穿透检查】")
slab_layers = [8,9,16,23,29,30]
for z in slab_layers:
    doors = np.sum(data[z] == DOOR)
    stairs = np.sum(data[z] == STAIR)
    if doors > 0:
        print(f"  ⚠ z={z}楼板层有{doors}个门")
    if stairs > 0:
        print(f"  z={z}楼板层有{stairs}个楼梯(正常)")
print("  ✓ 楼板层无门穿透" if all(np.sum(data[z]==DOOR)==0 for z in slab_layers) else "")

# 4. 楼层连通性检查
print("\n【4. 楼层连通性检查】")
def count_components(layer):
    visited = np.zeros_like(layer, dtype=bool)
    count = 0
    for y in range(h):
        for x in range(w):
            if layer[y,x] == EMPTY and not visited[y,x]:
                count += 1
                q = deque([(y,x)])
                visited[y,x] = True
                while q:
                    cy, cx = q.popleft()
                    for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                        ny, nx = cy+dy, cx+dx
                        if 0<=ny<h and 0<=nx<w and layer[ny,nx]==EMPTY and not visited[ny,nx]:
                            visited[ny,nx] = True
                            q.append((ny,nx))
    return count

for fi, (zs, ze) in enumerate(floor_ranges):
    # 取楼层中间层
    z = (zs + ze) // 2
    comps = count_components(data[z])
    print(f"  {floor_names[fi]}(z={z}): {comps}个空气连通域 {'✓' if comps<=2 else '⚠'}")

# 5. 楼梯垂直连通性
print("\n【5. 楼梯垂直连通性】")
stair_z = np.where(np.any(data == STAIR, axis=(1,2)))[0]
if len(stair_z) > 0:
    print(f"  楼梯z范围: {stair_z[0]}~{stair_z[-1]}, 共{len(stair_z)}层")
    gaps = []
    for i in range(1, len(stair_z)):
        if stair_z[i] - stair_z[i-1] > 2:
            gaps.append((stair_z[i-1], stair_z[i]))
    if gaps:
        print(f"  ⚠ 楼梯断层: {gaps}")
    else:
        print(f"  ✓ 楼梯垂直连续")

# 6. 屋顶层空气
print("\n【6. 屋顶层空气检查】")
for z in [29, 30, 31, 32, 33, 34, 35]:
    air = np.sum(data[z] == EMPTY)
    if air > 100:
        print(f"  ⚠ z={z}有{air}个空气")
    elif air > 0:
        print(f"  z={z}有{air}个空气(少量)")

# 7. 出口连通性
print("\n【7. 出口连通外部检查】")
for fi, (zs, ze) in enumerate(floor_ranges):
    z = (zs + ze) // 2
    layer = data[z]
    # 从边界洪水填充外部空气
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
    # 检查出口是否与外部空气相邻
    exits = np.argwhere(layer == EXIT)
    connected = 0
    for y,x in exits:
        for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny,nx = y+dy,x+dx
            if 0<=ny<h and 0<=nx<w and outside[ny,nx]:
                connected += 1
                break
    total = len(exits)
    print(f"  {floor_names[fi]}(z={z}): 出口{total}个, 连通外部{connected}个 {'✓' if connected==total else '⚠'}")

# 8. 建筑外部封闭
print("\n【8. 建筑外部空气封闭检查】")
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
    outside_count = np.sum(outside)
    print(f"  {floor_names[fi]}(z={z}): 与边界连通外部空气={outside_count}个 {'⚠' if outside_count>500 else '✓'}")

print("\n" + "=" * 60)
print("检查完成")
