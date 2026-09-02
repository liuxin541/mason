"""
BIM 建模转换精度分析脚本
只分析，不修改代码
"""
import numpy as np
from collections import deque
import json

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

print("=" * 60)
print("BIM 建模转换精度分析报告")
print("=" * 60)
print(f"网格尺寸: {w}x{h}x{d}")
print(f"体素大小: {voxel_size}m")
print(f"物理尺寸: {w*voxel_size:.1f}m x {h*voxel_size:.1f}m x {d*voxel_size:.1f}m")

# 体素类型统计
EMPTY, WALL, FUEL, DOOR, EXIT, STAIR, COLUMN, WINDOW = 0, 1, 2, 3, 4, 5, 6, 7
type_names = {0: '空气', 1: '墙', 2: '燃料', 3: '门', 4: '出口', 5: '楼梯', 6: '柱', 7: '窗'}
print("\n" + "=" * 60)
print("一、体素类型统计")
print("=" * 60)
for t in range(8):
    count = int(np.sum(data == t))
    pct = count / data.size * 100
    print(f"  {type_names[t]}({t}): {count:>8} 体素 ({pct:5.2f}%)")

print("\n" + "=" * 60)
print("二、楼层结构分析")
print("=" * 60)

# 分析每层的非空气体素数量
layer_nonempty = []
for z in range(d):
    nonempty = int(np.sum(data[z] != 0))
    layer_nonempty.append(nonempty)

# 识别楼板层（非空气占比高的层）
floor_slabs = []
for z in range(d):
    pct = layer_nonempty[z] / (w * h)
    if 0.5 < pct < 0.95:  # 楼板层特征
        floor_slabs.append((z, pct))

print(f"检测到楼板层: {[z for z, _ in floor_slabs]}")
for z, pct in floor_slabs:
    print(f"  z={z}: 非空气占比 {pct*100:.1f}%")

# 活动楼层（楼板层之间）
print(f"\n活动楼层划分:")
prev = 0
for i, (z, _) in enumerate(floor_slabs):
    print(f"  F{i+1}: z={prev}~{z-1} ({z-prev} 层, {(z-prev)*voxel_size:.1f}m)")
    prev = z + 1
if prev < d:
    print(f"  屋顶/设备层: z={prev}~{d-1} ({d-prev} 层)")

print("\n" + "=" * 60)
print("三、门的精度分析")
print("=" * 60)

# 门的 z 分布
door_z = {}
for z in range(d):
    count = int(np.sum(data[z] == DOOR))
    if count > 0:
        door_z[z] = count
print(f"门的 z 分布: {door_z}")

# 检查门是否穿透楼板
print("\n门穿透楼板检查:")
for z, _ in floor_slabs:
    door_count = int(np.sum(data[z] == DOOR))
    if door_count > 0:
        print(f"  ⚠ z={z} (楼板层) 有 {door_count} 个门体素，可能穿透楼板")

# 检查门的高度
door_positions = np.argwhere(data == DOOR)
if len(door_positions) > 0:
    # 按 (x,y) 分组，检查门的高度
    door_heights = {}
    for z, y, x in door_positions:
        key = (x, y)
        if key not in door_heights:
            door_heights[key] = []
        door_heights[key].append(z)
    
    heights = [max(zs) - min(zs) + 1 for zs in door_heights.values()]
    print(f"\n门的数量（按水平位置）: {len(door_heights)}")
    print(f"门的高度范围: {min(heights)}~{max(heights)} 体素 ({min(heights)*voxel_size:.1f}~{max(heights)*voxel_size:.1f}m)")
    print(f"门的平均高度: {np.mean(heights):.1f} 体素 ({np.mean(heights)*voxel_size:.1f}m)")
    
    # 检查异常高度的门
    abnormal = [(k, zs) for k, zs in door_heights.items() if max(zs) - min(zs) + 1 > 6]
    if abnormal:
        print(f"  ⚠ 有 {len(abnormal)} 个门高度超过 3m，可能是穿透楼板的门")

print("\n" + "=" * 60)
print("四、出口识别精度分析")
print("=" * 60)

exit_positions = np.argwhere(data == EXIT)
print(f"出口数量: {len(exit_positions)}")
if len(exit_positions) > 0:
    print(f"出口 z 范围: {exit_positions[:,0].min()}~{exit_positions[:,0].max()}")
    print(f"出口 x 范围: {exit_positions[:,2].min()}~{exit_positions[:,2].max()}")
    print(f"出口 y 范围: {exit_positions[:,1].min()}~{exit_positions[:,1].max()}")
    
    # 检查出口是否在建筑边缘
    edge_count = 0
    for z, y, x in exit_positions:
        # 检查出口外侧是否是墙（seal_exterior 后）
        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny, nx = y+dy, x+dx
            if 0 <= ny < h and 0 <= nx < w:
                if data[z, ny, nx] == WALL:
                    edge_count += 1
                    break
    print(f"出口外侧是墙的比例: {edge_count}/{len(exit_positions)} ({edge_count/len(exit_positions)*100:.1f}%)")
    if edge_count == len(exit_positions):
        print("  ⚠ 所有出口外侧都是墙，出口可能被封闭，人员到达出口后无法继续向外")
    
    # 检查出口是否在建筑最外层
    building_edge = 0
    for z, y, x in exit_positions:
        if x <= 2 or x >= w-3 or y <= 2 or y >= h-3:
            building_edge += 1
    print(f"出口在网格边缘(<=2格)的比例: {building_edge}/{len(exit_positions)} ({building_edge/len(exit_positions)*100:.1f}%)")

print("\n" + "=" * 60)
print("五、楼梯精度分析")
print("=" * 60)

stair_positions = np.argwhere(data == STAIR)
print(f"楼梯体素数量: {len(stair_positions)}")
if len(stair_positions) > 0:
    print(f"楼梯 z 范围: {stair_positions[:,0].min()}~{stair_positions[:,0].max()}")
    
    # 检查楼梯各层的水平位置是否连续
    stair_by_z = {}
    for z, y, x in stair_positions:
        if z not in stair_by_z:
            stair_by_z[z] = set()
        stair_by_z[z].add((x, y))
    
    print(f"楼梯覆盖的 z 层: {sorted(stair_by_z.keys())}")
    
    # 检查相邻层楼梯是否有重叠
    z_list = sorted(stair_by_z.keys())
    disconnected = []
    for i in range(len(z_list)-1):
        z1, z2 = z_list[i], z_list[i+1]
        if z2 - z1 == 1:
            overlap = stair_by_z[z1] & stair_by_z[z2]
            if len(overlap) == 0:
                disconnected.append((z1, z2))
    if disconnected:
        print(f"  ⚠ 相邻层楼梯无重叠: {disconnected}，可能导致上下层不连通")
    else:
        print("  ✓ 相邻层楼梯均有重叠")

print("\n" + "=" * 60)
print("六、房间连通性分析")
print("=" * 60)

# 可通行类型
passable_types = {EMPTY, DOOR, EXIT, STAIR}
passable = np.isin(data, list(passable_types))

# 检查各活动楼层的连通域
print("各活动楼层连通域数量:")
floor_ranges = [(0, 7), (10, 15), (17, 22), (24, 28)]  # 大致的活动楼层范围
for floor_name, (z_start, z_end) in [("1F", (0,7)), ("2F", (10,15)), ("3F", (17,22)), ("4F", (24,28))]:
    max_components = 0
    for z in range(z_start, z_end+1):
        if z >= d:
            continue
        layer = passable[z]
        visited = np.zeros_like(layer, dtype=bool)
        components = 0
        for y in range(h):
            for x in range(w):
                if layer[y, x] and not visited[y, x]:
                    components += 1
                    q = deque([(y, x)])
                    visited[y, x] = True
                    while q:
                        cy, cx = q.popleft()
                        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
                            ny, nx = cy+dy, cx+dx
                            if 0 <= ny < h and 0 <= nx < w and layer[ny, nx] and not visited[ny, nx]:
                                visited[ny, nx] = True
                                q.append((ny, nx))
        max_components = max(max_components, components)
    print(f"  {floor_name} (z={z_start}~{z_end}): 最多 {max_components} 个连通域")
    if max_components > 1:
        print(f"    ⚠ 存在多个连通域，房间可能不连通")

print("\n" + "=" * 60)
print("七、建筑封闭性分析")
print("=" * 60)

# 检查是否有从内部到网格边界的空气通路（seal_exterior 后应该没有）
leak_count = 0
for z in range(d):
    layer = data[z]
    # 从边界洪水填充空气
    outside = np.zeros((h, w), dtype=bool)
    q = deque()
    for x in range(w):
        if layer[0, x] == EMPTY:
            q.append((0, x)); outside[0, x] = True
        if layer[h-1, x] == EMPTY:
            q.append((h-1, x)); outside[h-1, x] = True
    for y in range(h):
        if layer[y, 0] == EMPTY:
            q.append((y, 0)); outside[y, 0] = True
        if layer[y, w-1] == EMPTY:
            q.append((y, w-1)); outside[y, w-1] = True
    while q:
        cy, cx = q.popleft()
        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny, nx = cy+dy, cx+dx
            if 0 <= ny < h and 0 <= nx < w and layer[ny, nx] == EMPTY and not outside[ny, nx]:
                outside[ny, nx] = True
                q.append((ny, nx))
    outside_count = int(np.sum(outside))
    if outside_count > 0:
        leak_count += 1
        print(f"  ⚠ z={z}: 有 {outside_count} 个外部空气体素与边界连通，建筑未完全封闭")

if leak_count == 0:
    print("  ✓ 所有楼层外部空气均已封闭")

print("\n" + "=" * 60)
print("八、楼板层空气缺口分析")
print("=" * 60)

for z, _ in floor_slabs:
    air_count = int(np.sum(data[z] == EMPTY))
    if air_count > 100:
        print(f"  ⚠ z={z} (楼板层) 有 {air_count} 个空气体素，可能是楼梯井或缺口")
        # 检查这些空气是否是楼梯井
        stair_air = 0
        for y, x in np.argwhere(data[z] == EMPTY):
            # 检查上下是否有楼梯
            if z > 0 and data[z-1, y, x] == STAIR:
                stair_air += 1
            elif z < d-1 and data[z+1, y, x] == STAIR:
                stair_air += 1
        print(f"    其中 {stair_air} 个与楼梯相邻（楼梯井），{air_count - stair_air} 个可能是缺口")

print("\n" + "=" * 60)
print("九、窗的精度分析")
print("=" * 60)

window_positions = np.argwhere(data == WINDOW)
print(f"窗体素数量: {len(window_positions)}")
if len(window_positions) > 0:
    print(f"窗 z 范围: {window_positions[:,0].min()}~{window_positions[:,0].max()}")
    
    # 检查窗是否穿透楼板
    for z, _ in floor_slabs:
        win_count = int(np.sum(data[z] == WINDOW))
        if win_count > 0:
            print(f"  ⚠ z={z} (楼板层) 有 {win_count} 个窗体素，可能穿透楼板")

print("\n" + "=" * 60)
print("十、柱的精度分析")
print("=" * 60)

column_positions = np.argwhere(data == COLUMN)
print(f"柱体素数量: {len(column_positions)}")
if len(column_positions) > 0:
    print(f"柱 z 范围: {column_positions[:,0].min()}~{column_positions[:,0].max()}")
    # 柱应该贯穿所有楼层
    column_xy = set((x, y) for z, y, x in column_positions)
    print(f"柱的水平位置数量: {len(column_xy)}")
    for x, y in list(column_xy)[:5]:
        zs = [z for z, yy, xx in column_positions if xx == x and yy == y]
        print(f"  柱 at (x={x}, y={y}): z={min(zs)}~{max(zs)} ({len(zs)} 层)")

print("\n" + "=" * 60)
print("分析完成")
print("=" * 60)
