#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解决 FireEscape.java 合并冲突"""
import re

PATH = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"

with open(PATH, "r", encoding="utf-8") as f:
    src = f.read()

# 冲突1 (191-300): 保留 HEAD 完整逻辑(洪水填充+开口修复+窗户溢出+底层z范围)
# 找到第一个冲突块
pattern1 = re.compile(
    r"<<<<<<< HEAD\n(.*?)=======\n.*?>>>>>>> origin/master\n",
    re.DOTALL
)
m1 = pattern1.search(src)
if m1:
    head_content = m1.group(1)
    src = src[:m1.start()] + head_content + src[m1.end():]
    print("冲突1解决: 保留HEAD版本(完整洪水填充+开口修复)")
else:
    print("冲突1未找到")

# 冲突2 (缩进): 保留HEAD缩进
# 冲突3: 采用origin/master版本(包含楼梯间修复),但修正缩进
# 找到剩余的冲突块
remaining = list(re.finditer(r"<<<<<<< HEAD\n(.*?)=======\n(.*?)>>>>>>> origin/master\n", src, re.DOTALL))
print(f"剩余冲突块: {len(remaining)}")

for i, m in enumerate(remaining):
    head = m.group(1)
    master = m.group(2)
    if "repairEnclosedStairwellIndoorStatus" in master or "repairStairwellDoorways" in master:
        # 冲突3: 采用master版本,但需要正确缩进(在else块内)
        # master版本的代码缩进少了一级,需要给每行加4个空格(除了空行)
        lines = master.split("\n")
        fixed_lines = []
        for line in lines:
            if line.strip() == "":
                fixed_lines.append("")
            else:
                fixed_lines.append("    " + line)
        fixed = "\n".join(fixed_lines)
        src = src[:m.start()] + fixed + src[m.end():]
        print(f"冲突{i+2}解决: 采用master版本(楼梯间修复)+修正缩进")
    else:
        # 冲突2(纯缩进): 保留HEAD
        src = src[:m.start()] + head + src[m.end():]
        print(f"冲突{i+2}解决: 保留HEAD版本")

with open(PATH, "w", encoding="utf-8") as f:
    f.write(src)

# 验证: 检查是否还有冲突标记
with open(PATH, "r", encoding="utf-8") as f:
    final = f.read()
if "<<<<<<<" in final or "=======" in final or ">>>>>>>" in final:
    print("警告: 仍有冲突标记未解决!")
else:
    print("所有冲突标记已清除")

# 检查关键方法是否存在
checks = [
    "repairEnclosedStairwellIndoorStatus",
    "repairStairwellDoorways",
    "insideCells",
    "预计算内部格",
    "出口推断",
]
for c in checks:
    if c in final:
        print(f"  ✓ 包含: {c}")
    else:
        print(f"  ✗ 缺失: {c}")
