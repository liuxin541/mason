#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""性能优化: 预计算内部格列表 + FireSpread 使用列表优化"""
import re

FE_PATH = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
FS_PATH = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireSpread.java"

# ===== 修改 FireEscape.java =====
with open(FE_PATH, "r", encoding="utf-8") as f:
    fe = f.read()

# 1. 添加 insideCells 字段
old_fe1 = "    public SemanticInfo semanticInfo = new SemanticInfo();"
new_fe1 = """    public SemanticInfo semanticInfo = new SemanticInfo();

    // ★ 性能优化: 预计算内部格列表(inside=1 且 obstacle!=WALL), 避免每步重复遍历全网格
    public List<int[]> insideCells = new ArrayList<>();
    public int insideCellCount = 0;"""
if old_fe1 in fe:
    fe = fe.replace(old_fe1, new_fe1, 1)
    print("FE修改1成功: 添加 insideCells 字段")
else:
    print("FE修改1失败")

# 2. 在 loadFromVoxel 完成前填充 insideCells(在 return true 之前)
old_fe2 = '            System.out.println("[BIM] 地图加载成功！尺寸: " + width + "x" + height + "x" + depth + ", 出口数量: " + exits.size());'
new_fe2 = """            // ★ 性能优化: 预计算内部格列表
            insideCells.clear();
            for (int x = 0; x < width; x++)
                for (int y = 0; y < height; y++)
                    for (int z = 0; z < depth; z++)
                        if (insideGrid.get(x, y, z) == 1 && obstacleGrid.get(x, y, z) != TYPE_WALL)
                            insideCells.add(new int[]{x, y, z});
            insideCellCount = insideCells.size();
            System.out.println("[BIM] 性能优化: 预计算内部格 " + insideCellCount + " 个(全网格 " + (width*height*depth) + ")");
            System.out.println("[BIM] 地图加载成功！尺寸: " + width + "x" + height + "x" + depth + ", 出口数量: " + exits.size());"""
if old_fe2 in fe:
    fe = fe.replace(old_fe2, new_fe2, 1)
    print("FE修改2成功: 填充 insideCells")
else:
    print("FE修改2失败")

with open(FE_PATH, "w", encoding="utf-8") as f:
    f.write(fe)

# ===== 修改 FireSpread.java: 使用 insideCells 优化关键循环 =====
with open(FS_PATH, "r", encoding="utf-8") as f:
    fs = f.read()

# 优化1: 初始化 nextT/nextC 的全网格遍历 → 用 insideCells
# 当前代码(218-230行):
#   for x for y for z { if wall||outside {nextT=AMB; nextC=0} else {nextT=T; nextC=C} }
# 优化: 先全部设为默认值, 然后只遍历 insideCells
old_fs1 = """        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    if (obstacle[x][y][z] == TYPE_WALL || inside[x][y][z] == 0) {
                        nextT[x][y][z] = T_AMB;
                        nextC[x][y][z] = 0.0;
                    } else {
                        nextT[x][y][z] = T[x][y][z];
                        nextC[x][y][z] = C[x][y][z];
                    }
                }
            }
        }"""
new_fs1 = """        // 性能优化: 先全部设默认值(用 Arrays.fill 更快), 再只遍历内部格拷贝当前值
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++) {
                java.util.Arrays.fill(nextT[x][y], T_AMB);
                java.util.Arrays.fill(nextC[x][y], 0.0);
            }
        for (int[] cell : fire.insideCells) {
            int cx = cell[0], cy = cell[1], cz = cell[2];
            nextT[cx][cy][cz] = T[cx][cy][cz];
            nextC[cx][cy][cz] = C[cx][cy][cz];
        }"""
if old_fs1 in fs:
    fs = fs.replace(old_fs1, new_fs1, 1)
    print("FS修改1成功: 初始化循环优化")
else:
    print("FS修改1失败")

# 优化2: 冷却衰减循环 → 用 insideCells
old_fs2 = """        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                for (int z = 0; z < d; z++) {
                    if (obstacle[x][y][z] == TYPE_WALL || inside[x][y][z] == 0) continue;
                    nextT[x][y][z] -= _cooling * DT_FINAL * (nextT[x][y][z] - T_AMB);"""
new_fs2 = """        for (int[] cell : fire.insideCells) {
            int x = cell[0], y = cell[1], z = cell[2];
                    nextT[x][y][z] -= _cooling * DT_FINAL * (nextT[x][y][z] - T_AMB);"""
if old_fs2 in fs:
    fs = fs.replace(old_fs2, new_fs2, 1)
    print("FS修改2成功: 冷却循环优化")
else:
    print("FS修改2失败")

# 优化3: 回写烟+温度循环 → 用 insideCells
old_fs3 = """        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                for (int z = 0; z < d; z++) {
                    if (obstacle[x][y][z] == TYPE_WALL || inside[x][y][z] == 0) continue;
                    nextC[x][y][z] = C_afterAdvection[x][y][z];
                    nextT[x][y][z] = T_afterAdvection[x][y][z];
                }"""
new_fs3 = """        for (int[] cell : fire.insideCells) {
            int x = cell[0], y = cell[1], z = cell[2];
            nextC[x][y][z] = C_afterAdvection[x][y][z];
            nextT[x][y][z] = T_afterAdvection[x][y][z];
        }"""
if old_fs3 in fs:
    fs = fs.replace(old_fs3, new_fs3, 1)
    print("FS修改3成功: 回写循环优化")
else:
    print("FS修改3失败")

with open(FS_PATH, "w", encoding="utf-8") as f:
    f.write(fs)

print("\n所有性能优化修改完成")
