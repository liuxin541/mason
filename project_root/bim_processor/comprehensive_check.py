"""BIM 建模转换综合问题检查（只分析不修改）"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

EMPTY, WALL, FUEL, DOOR, EXIT, STAIR, COLUMN, WINDOW = range(8)
passable = {EMPTY, DOOR, EXIT, STAIR}

print("=" * 70)
print("BIM 建模转换问题综合检查报告")
print("=" * 70)

# 1. 体素类型统计
print("\n【1. 体素类型统计】")
for t, name in [(EMPTY,'空气'),(WALL,'墙'),(FUEL,'燃料'),(DOOR,'门'),(EXIT,'出口'),(STAIR,'楼梯'),(COLUMN,'柱'),(WINDOW,'窗')]:
    count = int(np.sum(data == t))
    print(f"  {name}: {count} ({count/(80*38*36)*100:.1f}%)")

# 2. 出口连通性
print("\n【2. 出口连通性检查】")
exit_count = int(np.sum(data == EXIT))
unconnected = []
for z in range(36):
    exits = np.argwhere(data[z] == EXIT)
    if len(exits) == 0: continue
    layer = data[z]
    vis = np.zeros((38,80), dtype=bool)
    q = deque()
    for y,x in exits:
        q.append((y,x)); vis[y,x]=True
    reached = False
    while q:
        cy,cx = q.popleft()
        if cy<=0 or cy>=37 or cx<=0 or cx>=79: reached=True
        for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny,nx = cy+dy,cx+dx
            if 0<=ny<38 and 0<=nx<80 and layer[ny,nx] in passable and not vis[ny,nx]:
                vis[ny,nx]=True; q.append((ny,nx))
    if not reached:
        unconnected.append((z, len(exits)))
if unconnected:
    print(f"  ⚠ 未连通外部的出口层: {unconnected}")
else:
    print(f"  ✓ 所有出口({exit_count}个)均连通外部")

# 3. 楼板层穿透检查
print("\n【3. 楼板层穿透检查】")
slab_layers = [z for z in range(36) if np.sum(data[z]!=EMPTY)/(80*38) > 0.8]
penetration = []
for z in slab_layers:
    w = int(np.sum(data[z]==WINDOW))
    d = int(np.sum(data[z]==DOOR))
    if w > 0 or d > 5:
        penetration.append((z, d, w))
if penetration:
    print(f"  ⚠ 楼板层穿透: {penetration}")
else:
    print(f"  ✓ 所有楼板层无明显穿透")

# 4. 楼层连通性
print("\n【4. 楼层连通性检查】")
floors = [(0,7),(11,15),(18,22),(25,28)]
for idx,(zs,ze) in enumerate(floors):
    max_comp = 0
    for z in range(zs,ze+1):
        layer = data[z]
        vis = np.zeros((38,80), dtype=bool)
        comp = 0
        for y in range(38):
            for x in range(80):
                if layer[y,x] in passable and not vis[y,x]:
                    comp += 1
                    q = deque([(y,x)]); vis[y,x]=True
                    while q:
                        cy,cx = q.popleft()
                        for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                            ny,nx = cy+dy,cx+dx
                            if 0<=ny<38 and 0<=nx<80 and layer[ny,nx] in passable and not vis[ny,nx]:
                                vis[ny,nx]=True; q.append((ny,nx))
        max_comp = max(max_comp, comp)
    status = "✓" if max_comp <= 2 else "⚠"
    print(f"  F{idx+1}: 最多{max_comp}连通域 {status}")

# 5. 楼梯垂直连通性
print("\n【5. 楼梯垂直连通性检查】")
stair_positions = np.argwhere(data == STAIR)
if len(stair_positions) > 0:
    zs = sorted(set(s[0] for s in stair_positions))
    print(f"  楼梯 z 范围: {min(zs)}~{max(zs)}")
    # 检查每层楼梯是否与上下层楼梯连通
    disconnected_stairs = []
    for z in range(min(zs), max(zs)+1):
        cur = set((s[1],s[2]) for s in stair_positions if s[0]==z)
        if z+1 <= max(zs):
            nxt = set((s[1],s[2]) for s in stair_positions if s[0]==z+1)
            overlap = cur & nxt
            if not overlap and cur and nxt:
                disconnected_stairs.append(z)
    if disconnected_stairs:
        print(f"  ⚠ 楼梯垂直断开层: {disconnected_stairs}")
    else:
        print(f"  ✓ 楼梯垂直连通")
else:
    print("  ⚠ 无楼梯体素!")

# 6. 屋顶层空气检查
print("\n【6. 屋顶层空气检查】")
for z in [29, 30]:
    air = int(np.sum(data[z]==EMPTY))
    print(f"  z={z}: 空气={air} {'✓' if air < 20 else '⚠'}")

# 7. 建筑外部空气（seal_exterior 效果）
print("\n【7. 建筑外部封闭检查】")
# 从边界洪水填充，看有多少外部空气
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
outside_air = 0
while q:
    cz,cy,cx = q.popleft()
    outside_air += 1
    for dz,dy,dx in [(-1,0,0),(1,0,0),(0,-1,0),(0,1,0),(0,0,-1),(0,0,1)]:
        nz,ny,nx = cz+dz,cy+dy,cx+dx
        if 0<=nz<36 and 0<=ny<38 and 0<=nx<80 and data[nz,ny,nx]==EMPTY and not vis[nz,ny,nx]:
            vis[nz,ny,nx]=True; q.append((nz,ny,nx))
print(f"  与边界连通的外部空气: {outside_air} {'✓' if outside_air < 100 else '⚠'}")

# 8. 门高度异常检查
print("\n【8. 门高度异常检查】")
for floor_idx,(zs,ze) in enumerate(floors):
    door_heights = {}
    for z in range(zs, ze+1):
        doors = np.argwhere(data[z] == DOOR)
        for y,x in doors:
            key = (x,y)
            if key not in door_heights:
                door_heights[key] = [z, z]
            else:
                door_heights[key][1] = z
    tall_doors = sum(1 for v in door_heights.values() if v[1]-v[0]+1 > 5)
    if tall_doors > 0:
        print(f"  F{floor_idx+1}: {tall_doors}个门高度>2.5m ⚠")
    else:
        print(f"  F{floor_idx+1}: 门高度正常 ✓")

print("\n" + "=" * 70)
print("检查完成")
print("=" * 70)
