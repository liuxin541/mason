#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""解决 Person.java 合并冲突"""
import re

PATH = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\Person.java"

with open(PATH, "r", encoding="utf-8") as f:
    src = f.read()

# 找到所有冲突块
conflicts = list(re.finditer(r"<<<<<<< Updated upstream\n(.*?)=======\n(.*?)>>>>>>> Stashed changes\n", src, re.DOTALL))
print(f"找到 {len(conflicts)} 个冲突块")

for i, m in enumerate(conflicts):
    upstream = m.group(1)
    stashed = m.group(2)

    if i == 0:
        # 冲突1: 字段声明 - 合并 upstream 的所有字段 + stashed 的 escapeTime
        merged = upstream.rstrip() + "\n" + stashed
        src = src[:m.start()] + merged + src[m.end():]
        print(f"冲突{i+1}解决: 合并字段(upstream A*字段 + escapeTime)")
    elif i == 1:
        # 冲突2: step() 方法 - 采用 upstream 的新版本(A*寻路+累积伤害)
        src = src[:m.start()] + upstream + src[m.end():]
        print(f"冲突{i+1}解决: 采用upstream版本(A*寻路+累积伤害系统)")

with open(PATH, "w", encoding="utf-8") as f:
    f.write(src)

# 验证
with open(PATH, "r", encoding="utf-8") as f:
    final = f.read()
if "<<<<<<<" in final or ">>>>>>>" in final:
    print("警告: 仍有冲突标记!")
else:
    print("所有冲突标记已清除")

# 检查关键内容
checks = ["escapeTime", "EvacuationPhase", "accumulateInjuryDoses", "ensureNavigation", "route", "A*", "dijkstra", "Dijkstra"]
for c in checks:
    if c in final:
        print(f"  ✓ 包含: {c}")
    else:
        print(f"  ✗ 缺失: {c}")
