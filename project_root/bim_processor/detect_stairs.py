"""
检测楼梯连通性和出口可达性，模拟 Java 端移动规则：
- 水平移动：可通行格子（空气/门/出口/楼梯）
- 垂直移动：只有当前位置是楼梯(TYPE_STAIR)才能上下楼
- 目标：到达出口(TYPE_EXIT)
"""
import numpy as np
import struct
from collections import deque

with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    magic = f.read(5)
    ver = struct.unpack('<H', f.read(2))[0]
    w, h, d = struct.unpack('<III', f.read(12))
    vs = struct.unpack('<f', f.read(4))[0]
    f.read(9)
    data = np.frombuffer(f.read(w * h * d), dtype=np.uint8).reshape(d, h, w)

EMPTY, WALL, FUEL, DOOR, EXIT, STAIR, COLUMN, WINDOW = range(8)

# Java 端 insideGrid: 非墙非楼梯 = inside=1
# 但楼梯 inside=0，所以人员不能在楼梯上生成，但可以站在楼梯上移动
# Java 端 isPassable: 空气/门/出口/楼梯
passable_types = {EMPTY, DOOR, EXIT, STAIR}

print("=" * 60)
print("检测1: 楼梯 z 方向连通性（当前位置必须是楼梯才能上下楼）")
print("=" * 60)

# 找所有楼梯位置
stair_positions = np.argwhere(data == STAIR)
print(f"楼梯体素总数: {len(stair_positions)}")

# 按 z 层分组
stairs_by_z = {}
for z, y, x in stair_positions:
    stairs_by_z.setdefault(z, set()).add((y, x))

z_layers = sorted(stairs_by_z.keys())
print(f"楼梯所在层: {z_layers}")
print()

# 检查相邻 z 层的楼梯是否有相同 (x,y) 位置（人员站在楼梯上可以上下楼）
print("相邻层楼梯 (x,y) 重叠检测:")
vertical_connected = True
for i in range(len(z_layers) - 1):
    z1 = z_layers[i]
    z2 = z_layers[i + 1]
    overlap = stairs_by_z[z1] & stairs_by_z[z2]
    gap = z2 - z1
    if gap > 1:
        print(f"  z={z1} -> z={z2}: 间隔 {gap} 层（中间无楼梯），不连通!")
        vertical_connected = False
    elif len(overlap) == 0:
        print(f"  z={z1} -> z={z2}: 无 (x,y) 重叠，不连通!")
        vertical_connected = False
    else:
        print(f"  z={z1} -> z={z2}: 重叠 {len(overlap)} 个位置，连通")

print(f"\n楼梯垂直连通性: {'✓ 连通' if vertical_connected else '✗ 不连通'}")

print()
print("=" * 60)
print("检测2: 从各层楼梯到出口的路径可达性（BFS，模拟Java移动规则）")
print("=" * 60)

# BFS 从出口开始，反向搜索所有可达位置
# 移动规则：
# - 水平方向：目标格可通行
# - 垂直方向：当前格是楼梯，目标格可通行
def bfs_from_exits():
    visited = np.zeros_like(data, dtype=bool)
    q = deque()

    # 从所有出口开始
    exits = np.argwhere(data == EXIT)
    for z, y, x in exits:
        visited[z, y, x] = True
        q.append((z, y, x))

    while q:
        z, y, x = q.popleft()
        current_is_stair = (data[z, y, x] == STAIR)

        # 水平移动（6方向中的4个水平方向）
        for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and not visited[z, ny, nx]:
                if data[z, ny, nx] in passable_types:
                    visited[z, ny, nx] = True
                    q.append((z, ny, nx))

        # 垂直移动（只有当前是楼梯才能上下楼）
        if current_is_stair:
            for dz in [-1, 1]:
                nz = z + dz
                if 0 <= nz < d and not visited[nz, y, x]:
                    if data[nz, y, x] in passable_types:
                        visited[nz, y, x] = True
                        q.append((nz, y, x))

    return visited

reachable = bfs_from_exits()

# 检查各层楼梯是否可达出口
print("各层楼梯到达出口的可达性:")
for z in z_layers:
    stairs = stairs_by_z[z]
    reachable_count = sum(1 for y, x in stairs if reachable[z, y, x])
    total = len(stairs)
    status = "✓" if reachable_count == total else ("✗" if reachable_count == 0 else "△")
    print(f"  z={z:2d}: {reachable_count}/{total} 楼梯可达出口 {status}")

# 检查各层空气是否可达出口
print()
print("各层空气到达出口的可达性:")
for z in range(d):
    air = np.argwhere(data[z] == EMPTY)
    if len(air) == 0:
        continue
    reachable_count = sum(1 for y, x in air if reachable[z, y, x])
    total = len(air)
    pct = reachable_count / total * 100 if total > 0 else 0
    status = "✓" if pct > 80 else ("✗" if pct < 20 else "△")
    print(f"  z={z:2d}: {reachable_count}/{total} ({pct:.0f}%) 空气可达出口 {status}")

print()
print("=" * 60)
print("检测3: 楼梯周围环境（是否被墙包围）")
print("=" * 60)

for z in z_layers:
    stairs = stairs_by_z[z]
    isolated = 0
    for y, x in stairs:
        # 检查4个水平邻居是否有可通行格子
        has_passable_neighbor = False
        for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and data[z, ny, nx] in passable_types:
                has_passable_neighbor = True
                break
        if not has_passable_neighbor:
            isolated += 1
    if isolated > 0:
        print(f"  z={z:2d}: {isolated}/{len(stairs)} 楼梯被墙包围（无水平可通行邻居）")

print()
print("=" * 60)
print("检测4: 从楼上到出口的完整路径模拟")
print("=" * 60)

# 从第四层（z=24~28）随机选一个空气位置，BFS到出口
# 看是否能到达
def bfs_to_exit(start_z, start_y, start_x):
    """从起点BFS到最近出口，返回路径长度或None"""
    if data[start_z, start_y, start_x] not in passable_types:
        return None, "起点不可通行"

    visited = np.zeros_like(data, dtype=bool)
    visited[start_z, start_y, start_x] = True
    q = deque([(start_z, start_y, start_x, 0)])

    while q:
        z, y, x, dist = q.popleft()

        if data[z, y, x] == EXIT:
            return dist, "到达出口"

        current_is_stair = (data[z, y, x] == STAIR)

        # 水平移动
        for dy, dx in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
            ny, nx = y + dy, x + dx
            if 0 <= ny < h and 0 <= nx < w and not visited[z, ny, nx]:
                if data[z, ny, nx] in passable_types:
                    visited[z, ny, nx] = True
                    q.append((z, ny, nx, dist + 1))

        # 垂直移动（只有当前是楼梯）
        if current_is_stair:
            for dz in [-1, 1]:
                nz = z + dz
                if 0 <= nz < d and not visited[nz, y, x]:
                    if data[nz, y, x] in passable_types:
                        visited[nz, y, x] = True
                        q.append((nz, y, x, dist + 1))

    return None, "无法到达出口"

# 从各层选几个起点测试
test_points = [
    ("第四层", 25, 10, 10),
    ("第三层", 18, 10, 10),
    ("第二层", 11, 10, 10),
    ("第一层", 3, 10, 10),
]

for name, sz, sy, sx in test_points:
    # 找最近的可通行起点
    found = False
    for dy in range(-5, 6):
        for dx in range(-5, 6):
            y, x = sy + dy, sx + dx
            if 0 <= y < h and 0 <= x < w and data[sz, y, x] == EMPTY:
                dist, msg = bfs_to_exit(sz, y, x)
                if dist is not None:
                    print(f"  {name} (z={sz},y={y},x={x}): {dist} 步到达出口 ✓")
                else:
                    print(f"  {name} (z={sz},y={y},x={x}): {msg} ✗")
                found = True
                break
        if found:
            break
    if not found:
        print(f"  {name}: 未找到可通行起点")
