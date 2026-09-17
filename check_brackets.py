#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检查 Java 文件的括号匹配,定位不匹配位置"""

def check_brackets(path, method_name=None):
    with open(path, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    depth = 0
    method_start = None
    in_string = False
    in_line_comment = False
    in_block_comment = False

    for i, line in enumerate(lines):
        j = 0
        while j < len(line):
            c = line[j]
            # 处理注释
            if in_line_comment:
                break
            if in_block_comment:
                if c == '*' and j+1 < len(line) and line[j+1] == '/':
                    in_block_comment = False
                    j += 2
                    continue
                j += 1
                continue
            if c == '/' and j+1 < len(line):
                if line[j+1] == '/':
                    in_line_comment = True
                    break
                if line[j+1] == '*':
                    in_block_comment = True
                    j += 2
                    continue
            # 处理字符串
            if c == '"' and (j == 0 or line[j-1] != '\\'):
                in_string = not in_string
            if not in_string:
                if c == '{':
                    depth += 1
                    if method_name and method_start is None and method_name in line:
                        method_start = (i, depth)
                        print(f"  方法 {method_name} 开始: 行 {i+1}, 深度 {depth}")
                elif c == '}':
                    depth -= 1
                    if method_start and depth == method_start[1] - 1:
                        print(f"  方法 {method_name} 闭合: 行 {i+1}, 深度 {depth}")
                        method_start = None
                    if depth < 0:
                        print(f"  警告: 行 {i+1} 深度为负 ({depth})! {line.rstrip()}")
            j += 1
        in_line_comment = False

    print(f"  最终深度: {depth} (应为0)")
    return depth

print("=== FireEscape.java ===")
check_brackets(r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java", "start()")

print("\n=== Person.java ===")
check_brackets(r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\Person.java", "void step(")
