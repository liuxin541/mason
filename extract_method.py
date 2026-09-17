#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""抽取 initMultipleFireSources 方法 + 修复 wallFlipCount"""

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
with open(p, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 步骤1: 修复 wallFlipCount - 删除没有对应逻辑的打印
for i, line in enumerate(lines):
    if '射线扫描完成' in line and 'wallFlipCount' in line:
        lines[i] = '            // 射线扫描已由洪水填充替代\n'
        print(f"修复 wallFlipCount: 行 {i+1}")
        break

# 步骤2: 找到 start() 方法中的火源初始化逻辑,抽取成方法
# 找到 "fireSourceHeatRelease = new double" 和后面的 for 循环
start_idx = None
end_idx = None
for i, line in enumerate(lines):
    if 'fireSourceHeatRelease = new double' in line:
        start_idx = i
    if start_idx and i > start_idx and line.strip() == '}' and i > start_idx + 30:
        # 找到 start() 方法的闭合 }
        end_idx = i
        break

print(f"\n火源初始化逻辑: 行 {start_idx+1} 到 {end_idx}")
print(f"起始: {lines[start_idx].rstrip()}")
print(f"结束: {lines[end_idx].rstrip()}")

# 抽取这部分内容(从 fireSourceHeatRelease 到 start() 闭合之前)
method_body = []
for i in range(start_idx, end_idx):
    method_body.append(lines[i])

# 在 start() 中替换为方法调用
lines[start_idx] = '        initMultipleFireSources();\n'
for i in range(start_idx + 1, end_idx):
    lines[i] = ''

# 在 generateRandomFireSources 方法之后添加 initMultipleFireSources 方法
insert_idx = None
for i, line in enumerate(lines):
    if 'public void generateRandomFireSources()' in line:
        # 找到这个方法的结束
        depth = 0
        for j in range(i, len(lines)):
            depth += lines[j].count('{') - lines[j].count('}')
            if depth == 0 and j > i:
                insert_idx = j + 1
                break
        break

print(f"\n在行 {insert_idx+1} 之后插入 initMultipleFireSources 方法")

method_def = '''
    public void initMultipleFireSources() {
''' + ''.join(method_body) + '''    }

'''

lines.insert(insert_idx, method_def)

with open(p, 'w', encoding='utf-8') as f:
    f.writelines(lines)

print("完成")

# 验证
with open(p, 'r', encoding='utf-8') as f:
    final = f.read()
print(f"initMultipleFireSources 出现次数: {final.count('initMultipleFireSources')}")
print(f"wallFlipCount 出现次数: {final.count('wallFlipCount')}")
