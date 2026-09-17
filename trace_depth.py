#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""逐行追踪 Person.java step() 方法内的深度变化"""

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\Person.java"
with open(p, 'r', encoding='utf-8') as f:
    lines = f.readlines()

depth = 0
in_str = False
in_lc = False
in_bc = False
step_start = None

for i, line in enumerate(lines):
    j = 0
    line_depth_start = depth
    while j < len(line):
        c = line[j]
        if in_lc: break
        if in_bc:
            if c == '*' and j+1 < len(line) and line[j+1] == '/':
                in_bc = False; j += 2; continue
            j += 1; continue
        if c == '/' and j+1 < len(line):
            if line[j+1] == '/': in_lc = True; break
            if line[j+1] == '*': in_bc = True; j += 2; continue
        if c == '"' and (j == 0 or line[j-1] != '\\'):
            in_str = not in_str
        if not in_str:
            if c == '{': depth += 1
            elif c == '}': depth -= 1
        j += 1
    in_lc = False

    if 'void step(' in line:
        step_start = i
        print(f"--- step() 开始: 行 {i+1}, 深度 {depth} ---")

    if step_start and i >= step_start and i <= step_start + 120:
        if depth != line_depth_start or '{' in line or '}' in line:
            print(f"  行 {i+1}: {line_depth_start} -> {depth} | {line.rstrip()[:85]}")

    if step_start and depth <= 1 and i > step_start + 5:
        print(f"--- step() 似乎闭合于行 {i+1}, 深度 {depth} ---")
        print(f"  内容: {line.rstrip()[:85]}")
        break
