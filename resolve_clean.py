#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""干净地解决 FireEscape.java 合并冲突"""
import re

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
with open(p, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 步骤1: 删除 origin/master 的 rebuildInsideGridFromExterior + 窗户溢出(164-190行, index 163-189)
# 找到 "// 5. 从网格边界洪水填充室外空间" 到窗户溢出 for 循环结束
start_del = None
end_del = None
for i, line in enumerate(lines):
    if '从网格边界洪水填充室外空间' in line:
        start_del = i
    if start_del and i > start_del and '<<<<<<< HEAD' in line:
        end_del = i
        break

if start_del and end_del:
    print(f"删除 origin/master 的 rebuildInsideGridFromExterior+窗户溢出: 行 {start_del+1} 到 {end_del}")
    del lines[start_del:end_del]
else:
    print(f"未找到删除范围: start={start_del}, end={end_del}")

# 重新写入,然后处理冲突块
with open(p, 'w', encoding='utf-8') as f:
    f.writelines(lines)

# 步骤2: 处理冲突块
with open(p, 'r', encoding='utf-8') as f:
    content = f.read()

conflicts = list(re.finditer(r"<<<<<<< HEAD\n(.*?)=======\n(.*?)>>>>>>> origin/master\n", content, re.DOTALL))
print(f"\n找到 {len(conflicts)} 个冲突块")

for i, m in enumerate(conflicts):
    head = m.group(1)
    master = m.group(2)

    if i == 0:
        # 冲突1: 保留 HEAD(完整洪水填充+开口修复+窗户溢出+底层z范围)
        content = content[:m.start()] + head + content[m.end():]
        print(f"冲突{i+1}: 保留 HEAD(完整洪水填充+开口修复)")
    elif i == 1:
        # 冲突2: 保留 HEAD(缩进正确)
        content = content[:m.start()] + head + content[m.end():]
        print(f"冲突{i+1}: 保留 HEAD(缩进)")
    elif i == 2:
        # 冲突3: 采用 master(楼梯间修复),修正缩进
        fixed_lines = []
        for line in master.split('\n'):
            if line.strip() == '':
                fixed_lines.append('')
            else:
                fixed_lines.append('    ' + line)
        fixed = '\n'.join(fixed_lines)
        content = content[:m.start()] + fixed + content[m.end():]
        print(f"冲突{i+1}: 采用 master(楼梯间修复)+修正缩进")

with open(p, 'w', encoding='utf-8') as f:
    f.write(content)

# 验证
with open(p, 'r', encoding='utf-8') as f:
    final = f.read()
if '<<<<<<<' in final or '>>>>>>>' in final:
    print("\n警告: 仍有冲突标记!")
else:
    print("\n所有冲突标记已清除")

checks = ['rebuildInsideGridFromExterior', 'windowOverflow', '洪水填充', '开口修复', 'repairEnclosedStairwellIndoorStatus', 'repairStairwellDoorways', 'groundFloorMinZ']
for c in checks:
    count = final.count(c)
    print(f"  {c}: 出现 {count} 次")
