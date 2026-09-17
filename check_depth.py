#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查文件末尾和 step() 内部深度异常"""

def tail(path, n=5):
    with open(path, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    print(f"=== {path.split(chr(92))[-1]} 末尾{n}行 ===")
    for line in lines[-n:]:
        print(f"  {line.rstrip()}")
    print(f"  总行数: {len(lines)}")

def check_step_depth(path, start_line, end_line):
    with open(path, 'r', encoding='utf-8') as f:
        lines = f.readlines()
    depth = 0
    in_str = False
    in_lc = False
    in_bc = False
    print(f"\n=== {path.split(chr(92))[-1]} step() 内深度异常 ===")
    for i, line in enumerate(lines):
        j = 0
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
                elif c == '}':
                    depth -= 1
                    if start_line <= i+1 <= end_line and depth <= 1:
                        print(f"  行 {i+1}: 深度={depth} - {line.rstrip()[:90]}")
            j += 1
        in_lc = False
    print(f"  最终深度: {depth}")

tail(r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java")
tail(r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\Person.java")
check_step_depth(r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\Person.java", 1069, 2763)
