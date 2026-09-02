"""
BIM 建模转换精度分析 - 修正版
重点验证：出口识别精度、上层房间连通性
"""
import numpy as np
from collections import deque

# 读取体素文件
with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5)
    version = int.from_bytes(f.read(2), 'little')
    w = int.from_bytes(f.read(4), 'little')
    h = int.from_bytes(f.read(4), 'little')
    d = int.from_bytes(f.read(4), 'little')
    voxel_size = float(np.frombuffer(f.read(4), dtype=np.float32)[0])
    f.read(9)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, FUEL, DOOR, EXIT, STAIR, COLUMN, WINDOW = 0, 1, 2, 3, 4, 5, 6, 7

print("=" * 70)
print("BIM 建模转换精度分析（修正版）")
print("=" * 70)
print(f"网格: {w}x{h}x{d}, 体素: {voxel_size}m")

# ============================================================
# 一、准确识别楼板层（阈值 >0.8）
# ============================================================
print("\n" + "=" * 70)
print("一、楼层结构（修正阈值 >0.8）")
print("=" * 70)

layer_nonempty_pct = []
for z in range(d):
    pct = np.sum(data[z] != 0) / (w * h)
    layer_nonempty_pct.append(pct)

# 楼板层：非空气占比 > 80%
floor_slabs = [z for z in range(d) if layer_nonempty_pct[z] > 0.8]
print(f"真正的楼板层（非空气>80%）: {floor_slabs}")
for z in floor_slabs:
    print(f"  z={z}: 非空气占比 {layer_nonempty_pct[z]*100:.1f}%")

# 活动楼层划分
print(f"\n活动楼层划分:")
prev = 0
floor_idx = 1
for z in floor_slabs:
    if z - prev >= 2:  # 至少2层才算活动楼层
        print(f"  F{floor_idx}: z={prev}~{z-1} ({z-prev}层, {(z-prev)*voxel_size:.1f}m)")
        floor_idx += 1
    prev = z + 1
if prev < d and d - prev >= 2:
    print(f"  F{floor_idx}(屋顶): z={prev}~{d-1} ({d-prev}层)")

# ============================================================
# 二、出口识别精度（重点验证）
# ============================================================
print("\n" + "=" * 70)
print("二、出口识别精度（重点验证）")
print("=" * 70)

exit_positions = np.argwhere(data == EXIT)
print(f"出口总数: {len(exit_positions)}")

if len(exit_positions) > 0:
    print(f"出口 z 范围: {exit_positions[:,0].min()}~{exit_positions[:,0].max()}")
    print(f"出口 x 范围: {exit_positions[:,2].min()}~{exit_positions[:,2].max()}")
    print(f"出口 y 范围: {exit_positions[:,1].min()}~{exit_positions[:,1].max()}")
    
    # 按 z 层统计出口
    print("\n出口按楼层分布:")
    exit_by_z = {}
    for z, y, x in exit_positions:
        exit_by_z.setdefault(z, []).append((x, y))
    for z in sorted(exit_by_z.keys()):
        positions = exit_by_z[z]
        xs = [p[0] for p in positions]
        ys = [p[1] for p in positions]
        print(f"  z={z}: {len(positions)}个出口, x={min(xs)}~{max(xs)}, y={min(ys)}~{max(ys)}")
    
    # 检查出口外侧是什么（seal_exterior 后）
    print("\n出口外侧体素类型分析:")
    outside_types = {}
    for z, y, x in exit_positions:
        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny, nx = y+dy, x+dx
            if 0 <= ny < h and 0 <= nx < w:
                t = data[z, ny, nx]
                outside_types[t] = outside_types.get(t, 0) + 1
    type_names = {0:'空气', 1:'墙', 3:'门', 4:'出口', 5:'楼梯', 7:'窗'}
    for t, count in sorted(outside_types.items()):
        print(f"  {type_names.get(t, f'类型{t}')}: {count}次")
    
    # 检查出口是否在建筑最外层轮廓上
    print("\n出口是否在建筑最外层轮廓:")
    # 对每层，计算非空气体素的最外层轮廓
    for z in sorted(exit_by_z.keys())[:3]:  # 只看前3层
        layer = data[z]
        non_empty = layer != 0
        # 从边界洪水填充空气
        outside_air = np.zeros((h, w), dtype=bool)
        q = deque()
        for x in range(w):
            if layer[0, x] == EMPTY: q.append((0, x)); outside_air[0, x] = True
            if layer[h-1, x] == EMPTY: q.append((h-1, x)); outside_air[h-1, x] = True
        for y in range(h):
            if layer[y, 0] == EMPTY: q.append((y, 0)); outside_air[y, 0] = True
            if layer[y, w-1] == EMPTY: q.append((y, w-1)); outside_air[y, w-1] = True
        while q:
            cy, cx = q.popleft()
            for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                ny, nx = cy+dy, cx+dx
                if 0 <= ny < h and 0 <= nx < w and layer[ny, nx] == EMPTY and not outside_air[ny, nx]:
                    outside_air[ny, nx] = True
                    q.append((ny, nx))
        
        # 最外层轮廓：非空气体素且紧邻外部空气
        outline = np.zeros((h, w), dtype=bool)
        for y in range(h):
            for x in range(w):
                if non_empty[y, x]:
                    for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                        ny, nx = y+dy, x+dx
                        if 0 <= ny < h and 0 <= nx < w and outside_air[ny, nx]:
                            outline[y, x] = True
                            break
        
        exits_on_outline = 0
        for x, y in exit_by_z[z]:
            if outline[y, x]:
                exits_on_outline += 1
        total = len(exit_by_z[z])
        print(f"  z={z}: 出口在最外层轮廓上 {exits_on_outline}/{total} ({exits_on_outline/total*100:.0f}%)")
        if exits_on_outline == 0:
            print(f"    ⚠ 该层所有出口都不在建筑最外层轮廓上，是内门被误判为出口")
    
    # 检查出口北侧（y减小方向）是否有连续墙
    print("\n出口北侧墙体连续性检查（以北侧出口为例）:")
    z_sample = sorted(exit_by_z.keys())[0]
    for x, y in exit_by_z[z_sample][:3]:
        print(f"  出口 at (x={x}, y={y}, z={z_sample}):")
        # 向北扫描
        wall_count = 0
        for ny in range(y-1, -1, -1):
            if data[z_sample, ny, x] == WALL:
                wall_count += 1
            else:
                break
        print(f"    向北连续墙: {wall_count}层 ({wall_count*voxel_size:.1f}m)")
        if wall_count > 0:
            print(f"    ⚠ 出口北侧有墙，不是真正的外门")

# ============================================================
# 三、上层房间连通性（重点验证）
# ============================================================
print("\n" + "=" * 70)
print("三、上层房间连通性（重点验证）")
print("=" * 70)

passable_types = {EMPTY, DOOR, EXIT, STAIR}
passable = np.isin(data, list(passable_types))

# 准确的活动楼层范围（基于楼板层）
floor_ranges = []
prev = 0
for z in floor_slabs:
    if z - prev >= 2:
        floor_ranges.append((prev, z-1))
    prev = z + 1
if prev < d and d - prev >= 2:
    floor_ranges.append((prev, d-1))

for idx, (z_start, z_end) in enumerate(floor_ranges):
    floor_name = f"F{idx+1}"
    print(f"\n{floor_name} (z={z_start}~{z_end}):")
    
    max_components = 0
    max_z = z_start
    component_sizes = []
    
    for z in range(z_start, z_end+1):
        if z >= d:
            continue
        layer = passable[z]
        visited = np.zeros_like(layer, dtype=bool)
        components = 0
        sizes = []
        for y in range(h):
            for x in range(w):
                if layer[y, x] and not visited[y, x]:
                    components += 1
                    size = 0
                    q = deque([(y, x)])
                    visited[y, x] = True
                    while q:
                        cy, cx = q.popleft()
                        size += 1
                        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                            ny, nx = cy+dy, cx+dx
                            if 0 <= ny < h and 0 <= nx < w and layer[ny, nx] and not visited[ny, nx]:
                                visited[ny, nx] = True
                                q.append((ny, nx))
                    sizes.append(size)
        if components > max_components:
            max_components = components
            max_z = z
            component_sizes = sizes
    
    print(f"  最多连通域: {max_components} 个 (在 z={max_z})")
    if max_components > 1:
        print(f"  ⚠ 房间不连通！各连通域大小: {sorted(component_sizes, reverse=True)[:5]}")
        # 分析最大连通域覆盖范围
        layer = passable[max_z]
        visited = np.zeros_like(layer, dtype=bool)
        # 找最大连通域
        largest = []
        for y in range(h):
            for x in range(w):
                if layer[y, x] and not visited[y, x]:
                    comp = []
                    q = deque([(y, x)])
                    visited[y, x] = True
                    while q:
                        cy, cx = q.popleft()
                        comp.append((cy, cx))
                        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                            ny, nx = cy+dy, cx+dx
                            if 0 <= ny < h and 0 <= nx < w and layer[ny, nx] and not visited[ny, nx]:
                                visited[ny, nx] = True
                                q.append((ny, nx))
                    if len(comp) > len(largest):
                        largest = comp
        if largest:
            ys = [p[0] for p in largest]
            xs = [p[1] for p in largest]
            print(f"  最大连通域: {len(largest)}体素, 覆盖 x={min(xs)}~{max(xs)}, y={min(ys)}~{max(ys)}")
            # 检查被分隔的区域在哪里
            print(f"  被分隔的小连通域可能在: 建筑边缘或特定房间")

# ============================================================
# 四、门穿透楼板（修正版：只检查真正的楼板层）
# ============================================================
print("\n" + "=" * 70)
print("四、门/窗穿透楼板（修正版：只检查真正的楼板层）")
print("=" * 70)

for z in floor_slabs:
    door_count = int(np.sum(data[z] == DOOR))
    win_count = int(np.sum(data[z] == WINDOW))
    if door_count > 0:
        print(f"  ⚠ z={z} (楼板层): {door_count} 个门体素穿透楼板")
    if win_count > 0:
        print(f"  ⚠ z={z} (楼板层): {win_count} 个窗体素穿透楼板")
    if door_count == 0 and win_count == 0:
        print(f"  ✓ z={z} (楼板层): 无门/窗穿透")

# 门的高度（按楼层分别计算）
print("\n门的高度（按楼层分别计算）:")
for idx, (z_start, z_end) in enumerate(floor_ranges):
    floor_doors = {}
    for z in range(z_start, z_end+1):
        if z >= d:
            continue
        for y, x in np.argwhere(data[z] == DOOR):
            key = (x, y)
            if key not in floor_doors:
                floor_doors[key] = []
            floor_doors[key].append(z)
    if floor_doors:
        heights = [max(zs)-min(zs)+1 for zs in floor_doors.values()]
        print(f"  F{idx+1} (z={z_start}~{z_end}): {len(floor_doors)}个门, 高度 {min(heights)}~{max(heights)}体素 ({min(heights)*voxel_size:.1f}~{max(heights)*voxel_size:.1f}m), 平均{np.mean(heights):.1f}体素")
        abnormal = [h for h in heights if h > 6]  # >3m
        if abnormal:
            print(f"    ⚠ {len(abnormal)}个门高度超过3m")

# ============================================================
# 五、楼板层空气缺口
# ============================================================
print("\n" + "=" * 70)
print("五、楼板层空气缺口")
print("=" * 70)

for z in floor_slabs:
    air_count = int(np.sum(data[z] == EMPTY))
    if air_count > 0:
        # 检查是否与楼梯相邻
        stair_adjacent = 0
        for y, x in np.argwhere(data[z] == EMPTY):
            for dz in [-1, 0, 1]:
                for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                    nz, ny, nx = z+dz, y+dy, x+dx
                    if 0 <= nz < d and 0 <= ny < h and 0 <= nx < w and data[nz, ny, nx] == STAIR:
                        stair_adjacent += 1
                        break
                else:
                    continue
                break
        gap_count = air_count - stair_adjacent
        print(f"  z={z}: {air_count}个空气体素, {stair_adjacent}个与楼梯相邻(楼梯井), {gap_count}个可能是缺口")
        if gap_count > 50:
            print(f"    ⚠ 缺口较多({gap_count}个)，可能导致人员进入楼板夹层")

print("\n" + "=" * 70)
print("分析完成")
print("=" * 70)
