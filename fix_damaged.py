#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复 Person.java 和 FireEscape.java 的损坏代码"""

# ===== Person.java =====
p2 = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\Person.java"
with open(p2, 'r', encoding='utf-8') as f:
    content = f.read()

# 修复 1090 行的损坏代码
old = '            return;\nEvacuationState();'
new = '            return;\n        }\n        updateEvacuationState();'
if old in content:
    content = content.replace(old, new)
    print("Person.java: 修复了损坏的 EvacuationState() 代码")
else:
    print("Person.java: 未找到损坏代码,尝试其他模式...")
    # 看看 1088-1092 行
    lines = content.split('\n')
    for i in range(1085, 1095):
        if i < len(lines):
            print(f"  行 {i+1}: {lines[i]}")

with open(p2, 'w', encoding='utf-8') as f:
    f.write(content)

# ===== FireEscape.java =====
p1 = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
with open(p1, 'r', encoding='utf-8') as f:
    content1 = f.read()

# 检查 FireEscape.java 的深度问题 - 可能是 start() 方法的 else 块缺少闭合
# 找到 start() 方法和 generateRandomFireSources 之间的区域
lines1 = content1.split('\n')
for i, line in enumerate(lines1):
    if 'generateRandomFireSources' in line:
        print(f"\nFireEscape.java: generateRandomFireSources 在行 {i+1}")
        for j in range(max(0,i-8), i+1):
            print(f"  行 {j+1}: {lines1[j]}")
        break

# 检查是否有缺少的 } - 在 generateRandomFireSources 之前应该有两个 }
# 一个闭合 else 块(mapMode),一个闭合 start() 方法
# 看看 generateRandomFireSources 前面的 } 数量
for i, line in enumerate(lines1):
    if 'generateRandomFireSources' in line:
        # 往前找连续的 } 行
        braces = 0
        for j in range(i-1, max(0, i-10), -1):
            stripped = lines1[j].strip()
            if stripped == '}':
                braces += 1
            elif stripped == '':
                continue
            else:
                break
        print(f"  generateRandomFireSources 前有 {braces} 个连续 }} (需要2个: else块 + start方法)")
        if braces < 2:
            # 插入一个 }
            insert_pos = i
            while insert_pos > 0 and lines1[insert_pos-1].strip() == '':
                insert_pos -= 1
            lines1.insert(insert_pos, '    }')
            print(f"  在行 {insert_pos+1} 插入了 }} 闭合 else 块")
        break

with open(p1, 'w', encoding='utf-8') as f:
    f.write('\n'.join(lines1))

print("\n修复完成")
