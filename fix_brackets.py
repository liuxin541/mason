#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复 FireEscape.java 和 Person.java 的括号不匹配问题"""

# ===== FireEscape.java =====
p1 = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
with open(p1, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 找到 1243 行(闭合 for fp 的 })和 1244 行(闭合 start 的 })
# 在中间插入一个 } 闭合 else 块(1188行的 mapMode else)
for i in range(len(lines)-1):
    if 'fireSourceHeatRelease = new double' in lines[i]:
        # 找到这个语句所在的块,找到它后面的 start() 闭合
        for j in range(i, min(i+50, len(lines))):
            if lines[j].strip() == '}' and j+1 < len(lines) and 'generateRandomFireSources' in lines[j+1]:
                # 在 j 行之前插入一个 }
                lines.insert(j, '        }\n')
                print(f"FireEscape.java: 在行 {j+1} 前插入 else 块闭合 }}")
                break
        break

with open(p1, 'w', encoding='utf-8') as f:
    f.writelines(lines)

# ===== Person.java =====
p2 = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\Person.java"
with open(p2, 'r', encoding='utf-8') as f:
    lines2 = f.readlines()

# 找到 1175 行附近的 die 方法,看前面的方法是否括号不匹配
# 先看看 step() 方法的结构
for i, line in enumerate(lines2):
    if 'private void die(FireEscape fm' in line:
        print(f"Person.java: die() 方法在行 {i+1}")
        # 看前面 10 行
        for j in range(max(0,i-10), i+1):
            print(f"  {j+1}: {lines2[j].rstrip()}")
        break

with open(p2, 'w', encoding='utf-8') as f:
    f.writelines(lines2)

print("\n修复完成")
