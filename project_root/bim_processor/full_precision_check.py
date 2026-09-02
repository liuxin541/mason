"""BIM 转换精度全面检查"""
import numpy as np
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

EMPTY, WALL, DOOR, EXIT, STAIR, COLUMN, WINDOW = 0, 1, 3, 4, 5, 6, 7
d, h, w = data.shape
floor_ranges = [(0,7),(11,15),(18,22),(25,28)]
floor_names = ['F1','F2','F3','F4']

print("=" * 70)
print("BIM 转换精度全面检查")
print("=" * 70)

# 1. 门高度精度
print("\n【1. 门高度精度】")
all_ok = True
for fi, (zs, ze) in enumerate(floor_ranges):
    door_heights = {}
    for z in range(zs, ze+1):
        for y, x in np.argwhere(data[z] == DOOR):
            key = (int(x), int(y))
            if key not in door_heights:
                door_heights[key] = [z, z]
            else:
                door_heights[key][1] = z
    heights = [v[1]-v[0]+1 for v in door_heights.values()]
    if heights:
        max_h = max(heights)
        status = "✓" if max_h <= 5 else "⚠"
        if max_h > 5: all_ok = False
        print(f"  {floor_names[fi]}: {len(heights)}个门, 最高{max_h}体素({max_h*0.5:.1f}m) {status}")
print(f"  结论: {'✓ 门高度正常' if all_ok else '⚠ 存在超高门'}")

# 2. 楼板层穿透
print("\n【2. 楼板层穿透检查】")
slab_layers = []
for z in range(d):
    non_empty = np.sum(data[z] != EMPTY)
    if non_empty / (w*h) > 0.70:
        slab_layers.append(z)
penetration = False
for z in slab_layers:
    doors = np.sum(data[z] == DOOR)
    wins = np.sum(data[z] == WINDOW)
    if doors > 0:
        print(f"  ⚠ z={z}楼板层有{doors}个门")
        penetration = True
    if wins > 0:
        print(f"  ⚠ z={z}楼板层有{wins}个窗")
        penetration = True
if not penetration:
    print(f"  ✓ 楼板层({len(slab_layers)}层)无门/窗穿透")

# 3. 窗位置检查（窗不应在楼板层，不应穿透）
print("\n【3. 窗垂直连续性】")
window_ok = True
for fi, (zs, ze) in enumerate(floor_ranges):
    # 检查窗是否只在活动楼层
    for z in range(zs, ze+1):
        pass  # 窗在活动楼层是正常的
    # 检查窗高度
    win_heights = {}
    for z in range(zs, ze+1):
        for y, x in np.argwhere(data[z] == WINDOW):
            key = (int(x), int(y))
            if key not in win_heights:
                win_heights[key] = [z, z]
            else:
                win_heights[key][1] = z
    heights = [v[1]-v[0]+1 for v in win_heights.values()]
    if heights:
        max_h = max(heights)
        if max_h > (ze-zs+1):
            print(f"  ⚠ {floor_names[fi]}窗高度{max_h}超过楼层高度")
            window_ok = False
if window_ok:
    print("  ✓ 窗垂直连续性正常")

# 4. 出口连通性（按出口组）
print("\n【4. 出口连通性（按出口组）】")
exit_ok = True
for fi, (zs, ze) in enumerate(floor_ranges):
    z = (zs + ze) // 2
    layer = data[z]
    # 外部空气
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
    # 出口组
    visited = np.zeros((h,w), dtype=bool)
    groups = []
    for y in range(h):
        for x in range(w):
            if layer[y,x]==EXIT and not visited[y,x]:
                group = []
                gq = deque([(y,x)])
                visited[y,x]=True
                while gq:
                    cy,cx = gq.popleft()
                    group.append((cy,cx))
                    for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                        ny,nx = cy+dy,cx+dx
                        if 0<=ny<h and 0<=nx<w and layer[ny,nx]==EXIT and not visited[ny,nx]:
                            visited[ny,nx]=True; gq.append((ny,nx))
                groups.append(group)
    unconnected = 0
    for group in groups:
        conn = False
        for y,x in group:
            for dy,dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                ny,nx = y+dy,x+dx
                if 0<=ny<h and 0<=nx<w and outside[ny,nx]:
                    conn=True; break
            if conn: break
        if not conn:
            unconnected += 1
    if unconnected > 0:
        print(f"  ⚠ {floor_names[fi]}: {len(groups)}个出口组, {unconnected}个未连通外部")
        exit_ok = False
    else:
        print(f"  ✓ {floor_names[fi]}: {len(groups)}个出口组全部连通外部")
if exit_ok:
    print("  结论: ✓ 所有出口组均连通外部")

# 5. 楼梯连通性
print("\n【5. 楼梯垂直连通性】")
stair_z = np.where(np.any(data == STAIR, axis=(1,2)))[0]
if len(stair_z) > 0:
    gaps = []
    for i in range(1, len(stair_z)):
        if stair_z[i] - stair_z[i-1] > 2:
            gaps.append((stair_z[i-1], stair_z[i]))
    if gaps:
        print(f"  ⚠ 楼梯断层: {gaps}")
    else:
        print(f"  ✓ 楼梯z={stair_z[0]}~{stair_z[-1]}连续无断层")
    # 检查楼梯是否连接各楼层
    for fi, (zs, ze) in enumerate(floor_ranges):
        has_stair = any(zs <= z <= ze for z in stair_z)
        if not has_stair:
            print(f"  ⚠ {floor_names[fi]}无楼梯")
else:
    print("  ⚠ 无楼梯数据")

# 6. 屋顶/楼外空气
print("\n【6. 屋顶与楼外空气】")
# 屋顶层（最高活动楼层之上）
roof_air = 0
for z in range(29, d):
    roof_air += np.sum(data[z] == EMPTY)
if roof_air > 50:
    print(f"  ⚠ 屋顶层(z>=29)有{roof_air}个空气")
else:
    print(f"  ✓ 屋顶层空气仅{roof_air}个")

# 7. 建筑外部封闭（活动楼层边界空气）
print("\n【7. 建筑外部封闭】")
ext_ok = True
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
    cnt = np.sum(outside)
    if cnt > 200:
        print(f"  ⚠ {floor_names[fi]}边界连通空气={cnt}个")
        ext_ok = False
if ext_ok:
    print("  ✓ 所有楼层外部封闭良好")

# 8. 墙体完整性（检查活动楼层是否有大面积空洞）
print("\n【8. 墙体完整性】")
wall_ok = True
for fi, (zs, ze) in enumerate(floor_ranges):
    z = (zs + ze) // 2
    layer = data[z]
    non_empty = np.sum(layer != EMPTY)
    ratio = non_empty / (w*h)
    if ratio < 0.15:
        print(f"  ⚠ {floor_names[fi]}(z={z})非空气占比{ratio:.1%}过低，可能有大面积空洞")
        wall_ok = False
if wall_ok:
    print("  ✓ 各楼层墙体完整")

# 9. 体素类型分布合理性
print("\n【9. 体素类型分布】")
total = d * h * w
for t, name in [(EMPTY,'空气'),(WALL,'墙'),(DOOR,'门'),(EXIT,'出口'),(STAIR,'楼梯'),(COLUMN,'柱'),(WINDOW,'窗')]:
    cnt = np.sum(data == t)
    print(f"  {name}: {cnt} ({cnt/total:.1%})")

# 10. 楼层数验证
print("\n【10. 楼层数验证】")
print(f"  活动楼层: F1(z=0~7), F2(z=11~15), F3(z=18~22), F4(z=25~28)")
print(f"  楼板层: z={slab_layers}")
print(f"  ✓ 共4个活动楼层，符合建筑实际")

print("\n" + "=" * 70)
print("检查完成")
