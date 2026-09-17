#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证顶棚检测逻辑 + 出口推断"""
import struct
from collections import Counter

VOXEL_PATH = r"E:\xuexi\MASON\mason\ifc_output\voxel_grid.voxel"

def load_voxel(path):
    with open(path, "rb") as f:
        magic = f.read(5)
        f.read(2)
        w, h, d = struct.unpack("<iii", f.read(12))
        f.read(13)
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
    inside = [[[0]*d for _ in range(h)] for _ in range(w)]
    zGround, zRoof = -1, -1
    for z in range(d):
        wc = sum(1 for x in range(w) for y in range(h) if grid[x][y][z] == 1)
        if wc > w * h * 0.4:
            if zGround == -1: zGround = z
            zRoof = z
    if zGround == -1: zGround, zRoof = 0, d-1
    for x in range(w):
        for y in range(h):
            ins = False
            for z in range(zGround, zRoof+1):
                t = grid[x][y][z]
                if t == 1: ins = not ins
                elif ins: inside[x][y][z] = 1
    for x in range(w):
        for y in range(h):
            for z in range(d):
                t = grid[x][y][z]
                if t in (3, 5, 6) and inside[x][y][z] == 0:
                    inside[x][y][z] = 1
    from collections import deque
    q = deque()
    for x in range(w):
        for y in range(h):
            for z in range(d):
                if inside[x][y][z] == 1: q.append((x,y,z))
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
    return inside

def detect_ceilings(w, h, d, grid, inside):
    """复现 Java detectCeilings 逻辑"""
    wallByZ = [0]*d
    insideByZ = [0]*d
    for z in range(d):
        wc = ic = 0
        for x in range(w):
            for y in range(h):
                if grid[x][y][z] == 1: wc += 1
                if inside[x][y][z] == 1: ic += 1
        wallByZ[z] = wc
        insideByZ[z] = ic
    ceilings = []
    for z in range(2, d-1):
        cur = insideByZ[z]
        above = insideByZ[z+1]
        below = insideByZ[z-1]
        if cur > 0 and cur < (above + below) * 0.4 and wallByZ[z] > cur:
            ceilZ = z - 1
            if ceilZ >= 0 and (not ceilings or ceilZ - ceilings[-1] > 3):
                ceilings.append(ceilZ)
    if not ceilings: ceilings.append(d-1)
    return ceilings, insideByZ, wallByZ

def detect_exits(w, h, d, grid, inside):
    """复现出口推断逻辑"""
    exits = []
    for x in range(w):
        for y in range(h):
            for z in range(d):
                if grid[x][y][z] != 3: continue  # DOOR
                isBoundary = (x <= 2 or x >= w-3 or y <= 2 or y >= h-3)
                if not isBoundary: continue
                hasOutside = False
                dirs4 = [(-1,0),(1,0),(0,-1),(0,1)]
                for dx,dy in dirs4:
                    nx,ny = x+dx, y+dy
                    if nx < 0 or nx >= w or ny < 0 or ny >= h:
                        hasOutside = True; break
                    if inside[nx][ny][z] == 0 and grid[nx][ny][z] != 1:
                        hasOutside = True; break
                if hasOutside:
                    exits.append((x,y,z))
    return exits

def main():
    print("="*60)
    print("验证: 顶棚检测 + 出口推断")
    print("="*60)
    w, h, d, grid = load_voxel(VOXEL_PATH)
    inside = compute_inside(w, h, d, grid)
    ceilings, insideByZ, wallByZ = detect_ceilings(w, h, d, grid, inside)

    print(f"\n--- 顶棚检测结果 ---")
    print(f"检测到顶棚层 z = {ceilings}")
    for c in ceilings:
        print(f"  z={c} (world_z={c*0.25-0.7:.2f}m), 该层 inside={insideByZ[c]}, wall={wallByZ[c]}")
        print(f"    上方 z={c+1}: inside={insideByZ[c+1]}, wall={wallByZ[c+1]} (楼板层)")

    print(f"\n--- 近顶棚层标记(顶棚下0~3格) ---")
    near = set()
    for c in ceilings:
        for k in range(4):
            if c-k >= 0: near.add(c-k)
    print(f"近顶棚层 z = {sorted(near)}")
    print(f"共 {len(near)} 层, 占总层数 {len(near)/d*100:.1f}%")

    print(f"\n--- 出口推断结果 ---")
    exits = detect_exits(w, h, d, grid, inside)
    print(f"检测到出口 {len(exits)} 个")
    if exits:
        byZ = Counter(z for _,_,z in exits)
        print(f"按层分布: {dict(sorted(byZ.items()))}")
        print(f"前5个: {exits[:5]}")

    print(f"\n--- 内部格统计 ---")
    insideCnt = sum(inside[x][y][z] for x in range(w) for y in range(h) for z in range(d))
    nonWallInside = sum(1 for x in range(w) for y in range(h) for z in range(d)
                        if inside[x][y][z]==1 and grid[x][y][z]!=1)
    print(f"inside=1 总格数: {insideCnt:,}")
    print(f"inside=1 且非墙: {nonWallInside:,} (预计算列表大小)")
    print(f"全网格: {w*h*d:,}, 优化比例: {nonWallInside/(w*h*d)*100:.1f}%")

if __name__ == "__main__":
    main()
