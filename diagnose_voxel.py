#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""诊断 BIM 体素网格:类型分布、内部空间、连通性、门窗位置"""
import struct
from collections import deque, Counter

VOXEL_PATH = r"E:\xuexi\MASON\mason\ifc_output\voxel_grid.voxel"
TYPE_NAMES = {0:"EMPTY(空气)", 1:"WALL(墙)", 2:"FUEL(可燃物)", 3:"DOOR(门)", 4:"EXIT(出口)", 5:"STAIR(楼梯)", 6:"WINDOW(窗)"}

def load_voxel(path):
    with open(path, "rb") as f:
        magic = f.read(5)
        assert magic == b"VOXEL", f"bad magic: {magic}"
        f.read(2)  # version
        w, h, d = struct.unpack("<iii", f.read(12))
        f.read(13)  # voxel_size + reserved
        data = f.read(w * h * d)
    grid = [[[0]*d for _ in range(h)] for _ in range(w)]
    idx = 0
    for z in range(d):
        for y in range(h):
            for x in range(w):
                grid[x][y][z] = data[idx]
                idx += 1
    return w, h, d, grid

def compute_inside(w, h, d, grid):
    """复现 FireEscape.loadFromVoxel 的 inside 判定:射线扫描+洪水填充+窗户溢出"""
    inside = [[[0]*d for _ in range(h)] for _ in range(w)]
    # 5a. 找 zGround/zRoof(大面积楼板层:墙>40%)
    zGround, zRoof = -1, -1
    for z in range(d):
        wc = sum(1 for x in range(w) for y in range(h) if grid[x][y][z] == 1)
        if wc > w * h * 0.4:
            if zGround == -1: zGround = z
            zRoof = z
    if zGround == -1: zGround, zRoof = 0, d-1
    # 5c. 射线扫描
    for x in range(w):
        for y in range(h):
            ins = False
            for z in range(zGround, zRoof+1):
                t = grid[x][y][z]
                if t == 1:
                    ins = not ins
                elif ins:
                    inside[x][y][z] = 1
    # 开口修复
    for x in range(w):
        for y in range(h):
            for z in range(d):
                t = grid[x][y][z]
                if t in (3, 5, 6) and inside[x][y][z] == 0:
                    inside[x][y][z] = 1
    # 洪水填充
    q = deque()
    for x in range(w):
        for y in range(h):
            for z in range(d):
                if inside[x][y][z] == 1:
                    q.append((x,y,z))
    dirs = [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]
    while q:
        cx,cy,cz = q.popleft()
        for dx,dy,dz in dirs:
            nx,ny,nz = cx+dx, cy+dy, cz+dz
            if not (0<=nx<w and 0<=ny<h and 0<=nz<d): continue
            if inside[nx][ny][nz] == 1: continue
            if grid[nx][ny][nz] == 1: continue
            inside[nx][ny][nz] = 1
            q.append((nx,ny,nz))
    # 窗户外部溢出
    for x in range(w):
        for y in range(h):
            for z in range(d):
                if grid[x][y][z] != 6: continue
                for dx in range(-6,7):
                    for dy in range(-6,7):
                        for dz in range(-1,9):
                            nx,ny,nz = x+dx, y+dy, z+dz
                            if not (0<=nx<w and 0<=ny<h and 0<=nz<d): continue
                            if inside[nx][ny][nz] == 1: continue
                            if grid[nx][ny][nz] == 1: continue
                            inside[nx][ny][nz] = 1
    return inside, zGround, zRoof

def flood_from(w, h, d, grid, inside, sx, sy, sz):
    """从火源位置出发,6邻域 flood-fill,统计可达内部格数"""
    if grid[sx][sy][sz] == 1 or inside[sx][sy][sz] == 0:
        return 0, 0
    visited = [[[False]*d for _ in range(h)] for _ in range(w)]
    q = deque([(sx,sy,sz)])
    visited[sx][sy][sz] = True
    cnt = 0
    zrange = set()
    dirs = [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]
    while q:
        cx,cy,cz = q.popleft()
        cnt += 1
        zrange.add(cz)
        for dx,dy,dz in dirs:
            nx,ny,nz = cx+dx, cy+dy, cz+dz
            if not (0<=nx<w and 0<=ny<h and 0<=nz<d): continue
            if visited[nx][ny][nz]: continue
            if grid[nx][ny][nz] == 1: continue
            if inside[nx][ny][nz] == 0: continue
            visited[nx][ny][nz] = True
            q.append((nx,ny,nz))
    return cnt, len(zrange)

def main():
    print("="*70)
    print("BIM 体素网格诊断")
    print("="*70)
    w, h, d, grid = load_voxel(VOXEL_PATH)
    print(f"网格尺寸: {w} x {h} x {d} = {w*h*d:,} 格")
    print(f"体素大小: 0.25m → 物理尺寸: {w*0.25:.1f}m x {h*0.25:.1f}m x {d*0.25:.1f}m")

    # 类型分布
    print("\n--- 类型分布 ---")
    cnt = Counter()
    for x in range(w):
        for y in range(h):
            for z in range(d):
                cnt[grid[x][y][z]] += 1
    total = w*h*d
    for t in sorted(cnt):
        name = TYPE_NAMES.get(t, f"TYPE_{t}")
        print(f"  {name:20s}: {cnt[t]:>8,} ({cnt[t]/total*100:5.2f}%)")

    # inside 判定
    print("\n--- 内部空间判定 ---")
    inside, zGround, zRoof = compute_inside(w, h, d, grid)
    inside_cnt = sum(inside[x][y][z] for x in range(w) for y in range(h) for z in range(d))
    print(f"  zGround={zGround} (world_z={zGround*0.25-0.7:.2f}m), zRoof={zRoof} (world_z={zRoof*0.25-0.7:.2f}m)")
    print(f"  内部格数(inside=1): {inside_cnt:,} ({inside_cnt/total*100:.2f}%)")

    # 内部空间 z 分布
    print("\n--- 内部空间 z 层分布(每层 inside 格数) ---")
    for z in range(d):
        c = sum(inside[x][y][z] for x in range(w) for y in range(h))
        if c > 0:
            bar = "#" * min(50, c // max(1, inside_cnt//50))
            print(f"  z={z:3d} (world={z*0.25-0.7:5.2f}m): {c:>6,} {bar}")

    # 门/窗/出口位置
    print("\n--- 门/窗/出口/楼梯 统计 ---")
    doors = [(x,y,z) for x in range(w) for y in range(h) for z in range(d) if grid[x][y][z]==3]
    windows = [(x,y,z) for x in range(w) for y in range(h) for z in range(d) if grid[x][y][z]==6]
    exits = [(x,y,z) for x in range(w) for y in range(h) for z in range(d) if grid[x][y][z]==4]
    stairs = [(x,y,z) for x in range(w) for y in range(h) for z in range(d) if grid[x][y][z]==5]
    print(f"  门(DOOR=3):   {len(doors)} 个")
    if doors:
        dzs = Counter(z for _,_,z in doors)
        print(f"    z分布: {dict(sorted(dzs.items()))}")
    print(f"  窗(WINDOW=6): {len(windows)} 个")
    if windows:
        wzs = Counter(z for _,_,z in windows)
        print(f"    z分布: {dict(sorted(wzs.items()))}")
    print(f"  出口(EXIT=4): {len(exits)} 个")
    if exits:
        print(f"    位置: {exits[:10]}")
    print(f"  楼梯(STAIR=5): {len(stairs)} 个")

    # 连通性:从建筑中心(找一个内部空气格)出发
    print("\n--- 连通性诊断(从建筑中心内部格 6 邻域 flood-fill) ---")
    # 找中心附近的内部空气格
    cx0, cy0, cz0 = w//2, h//2, (zGround+zRoof)//2
    start = None
    for r in range(20):
        for dx in range(-r, r+1):
            for dy in range(-r, r+1):
                for dz in range(-r, r+1):
                    x,y,z = cx0+dx, cy0+dy, cz0+dz
                    if 0<=x<w and 0<=y<h and 0<=z<d:
                        if grid[x][y][z]==0 and inside[x][y][z]==1:
                            start = (x,y,z)
                            break
                if start: break
            if start: break
        if start: break
    if start:
        print(f"  起点: {start} (world_z={start[2]*0.25-0.7:.2f}m)")
        reach, zcnt = flood_from(w, h, d, grid, inside, *start)
        print(f"  可达内部格数: {reach:,} / {inside_cnt:,} = {reach/inside_cnt*100:.1f}%")
        print(f"  可达 z 层数: {zcnt}")
        if reach < inside_cnt * 0.9:
            print("  ⚠ 连通性不足!有大量内部格不可达,可能被墙封堵")
        else:
            print("  ✓ 连通性良好")
    else:
        print("  未找到中心内部空气格!")

    # 检测:是否有房间被完全墙围合(无门)
    print("\n--- 潜在封堵房间检测 ---")
    # 找所有内部空气格,做连通分量
    visited = [[[False]*d for _ in range(h)] for _ in range(w)]
    components = []
    dirs = [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]
    for x in range(w):
        for y in range(h):
            for z in range(d):
                if visited[x][y][z] or grid[x][y][z]==1 or inside[x][y][z]==0: continue
                q = deque([(x,y,z)])
                visited[x][y][z] = True
                comp = []
                while q:
                    cx,cy,cz = q.popleft()
                    comp.append((cx,cy,cz))
                    for dx,dy,dz in dirs:
                        nx,ny,nz = cx+dx, cy+dy, cz+dz
                        if not (0<=nx<w and 0<=ny<h and 0<=nz<d): continue
                        if visited[nx][ny][nz]: continue
                        if grid[nx][ny][nz]==1: continue
                        if inside[nx][ny][nz]==0: continue
                        visited[nx][ny][nz] = True
                        q.append((nx,ny,nz))
                components.append(comp)
    components.sort(key=len, reverse=True)
    print(f"  连通分量总数: {len(components)}")
    for i, comp in enumerate(components[:8]):
        zs = set(z for _,_,z in comp)
        print(f"  分量#{i}: {len(comp):>6,} 格, z范围={min(zs)}-{max(zs)} ({len(zs)}层)")
    if len(components) > 1:
        small = sum(len(c) for c in components[1:])
        print(f"  ⚠ 除最大分量外,其余共 {small:,} 格被隔离(可能是封堵房间或外部区域)")

if __name__ == "__main__":
    main()
