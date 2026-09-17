#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import io

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
with io.open(p, 'r', encoding='utf-8') as f:
    content = f.read()

old = """                    int type = obstacleGrid.get(x, y, z);
                    boolean interior = !isEnclosureBarrier(type) && !outside[voxelIndex(x, y, z)];
                    insideGrid.set(x, y, z, interior ? 1 : 0);
                    fuelGrid.set(x, y, z, interior && type == TYPE_FUEL ? fuelCapacity : 0);"""

new = """                    int type = obstacleGrid.get(x, y, z);
                    boolean interior = !isEnclosureBarrier(type) && !outside[voxelIndex(x, y, z)];
                    insideGrid.set(x, y, z, interior ? 1 : 0);
                    // ★ 可燃物判定: 原有TYPE_FUEL标记 + 室内空气格按40%比例自动标记(模拟家具/装修分布)
                    //   使用确定性伪随机(坐标hash),保证每次加载结果一致,火焰可从火源点燃周围可燃物并蔓延
                    boolean isFuelCell = (type == TYPE_FUEL) ||
                            (interior && type == TYPE_EMPTY && pseudoRandomFuel(x, y, z) < 0.40);
                    fuelGrid.set(x, y, z, isFuelCell ? fuelCapacity : 0);"""

if old in content:
    content = content.replace(old, new)
    print('可燃物标记逻辑已修改')
else:
    print('未找到目标文本!')
    idx = content.find('fuelGrid.set(x, y, z, interior && type == TYPE_FUEL')
    if idx >= 0:
        print('找到fuelGrid行,上下文:')
        print(repr(content[idx-200:idx+100]))

# 添加pseudoRandomFuel方法(在类的末尾,最后一个}之前)
method = """
    // ★ 确定性伪随机: 基于坐标hash,用于可燃物分布,保证每次加载结果一致
    private static double pseudoRandomFuel(int x, int y, int z) {
        int h = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
        h ^= (h >>> 13);
        h *= 1274126177;
        h ^= (h >>> 16);
        return ((h & 0x7FFFFFFF) % 100000) / 100000.0;
    }
"""

# 在最后一个}之前插入方法
last_brace = content.rfind('}')
if last_brace > 0:
    content = content[:last_brace] + method + '\n' + content[last_brace:]
    print('pseudoRandomFuel方法已添加')
else:
    print('未找到类结束括号!')

with io.open(p, 'w', encoding='utf-8') as f:
    f.write(content)
print('文件已保存')
