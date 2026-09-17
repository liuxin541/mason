#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""添加 insideCells 字段和预计算"""

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
with open(p, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 添加 insideCells 字段声明
old_field = 'public IntGrid3D insideGrid;'
new_field = 'public IntGrid3D insideGrid;\n    public java.util.List<int[]> insideCells = new java.util.ArrayList<>();'
if old_field in content and 'insideCells' not in content:
    content = content.replace(old_field, new_field)
    print('添加 insideCells 字段声明')
else:
    print('insideCells 字段已存在或 insideGrid 未找到')

# 2. 在 rebuildInsideGridFromExterior() 方法末尾添加预计算
lines = content.split('\n')
insert_idx = None
for i, line in enumerate(lines):
    if '室内外洪水填充完成' in line:
        # 找到后面的 } (方法闭合)
        for j in range(i+1, min(i+5, len(lines))):
            if lines[j].strip() == '}':
                insert_idx = j
                break
        break

if insert_idx:
    print(f"在行 {insert_idx+1} 之前插入 insideCells 预计算")
    insert_code = '''        // ★ 预计算内部格列表,供 FireSpread 性能优化使用
        insideCells.clear();
        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                for (int z = 0; z < depth; z++)
                    if (insideGrid.get(x, y, z) == 1 && obstacleGrid.get(x, y, z) != TYPE_WALL)
                        insideCells.add(new int[]{x, y, z});
        System.out.println("[BIM] insideCells 预计算: " + insideCells.size() + " 个内部格");'''
    lines.insert(insert_idx, insert_code)
    content = '\n'.join(lines)
else:
    print('未找到插入位置')

with open(p, 'w', encoding='utf-8') as f:
    f.write(content)
print('完成')
