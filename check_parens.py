#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""精确检查JS代码括号平衡: 跳过注释/字符串/模板串"""
import re

with open('fireescape.html', 'r', encoding='utf-8') as f:
    content = f.read()

m = re.search(r'<script type="module">(.*?)</script>', content, re.DOTALL)
s = m.group(1)

# 移除注释和字符串后检查
def strip_comments_and_strings(code):
    out = []
    i = 0
    n = len(code)
    while i < n:
        c = code[i]
        # 行注释
        if c == '/' and i+1 < n and code[i+1] == '/':
            while i < n and code[i] != '\n':
                i += 1
            out.append('\n')
            continue
        # 块注释
        if c == '/' and i+1 < n and code[i+1] == '*':
            i += 2
            while i+1 < n and not (code[i] == '*' and code[i+1] == '/'):
                i += 1
            i += 2
            out.append('  ')
            continue
        # 字符串/模板串
        if c in ('"', "'", '`'):
            quote = c
            i += 1
            while i < n:
                if code[i] == '\\':
                    i += 2
                    continue
                if code[i] == quote:
                    i += 1
                    break
                i += 1
            out.append('""')
            continue
        out.append(c)
        i += 1
    return ''.join(out)

clean = strip_comments_and_strings(s)
for name, a, b in [('大括号', '{', '}'), ('圆括号', '(', ')'), ('方括号', '[', ']')]:
    o = clean.count(a)
    cl = clean.count(b)
    print('%s: %d/%d %s' % (name, o, cl, 'OK' if o == cl else 'MISMATCH!'))

# 精确定位
stack = []
lines = clean.split('\n')
for ln, line in enumerate(lines, 1):
    for ch in line:
        if ch in '([{':
            stack.append((ln, ch))
        elif ch in ')]}':
            pairs = {')': '(', ']': '[', '}': '{'}
            if stack and stack[-1][1] == pairs[ch]:
                stack.pop()
            else:
                print('多余闭合 %s at line %d' % (ch, ln))
print('未匹配开括号:', len(stack), stack[:5])
