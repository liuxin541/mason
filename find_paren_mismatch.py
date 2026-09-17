#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import re
with open('fireescape.html', 'r', encoding='utf-8') as f:
    content = f.read()
m = re.search(r'<script type="module">(.*?)</script>', content, re.DOTALL)
s = m.group(1)
stack = []
lines = s.split('\n')
in_str = None
for ln, line in enumerate(lines, 1):
    i = 0
    while i < len(line):
        c = line[i]
        if in_str:
            if c == in_str and (i == 0 or line[i-1] != '\\'):
                in_str = None
            i += 1
            continue
        if c in '"\'`':
            in_str = c
        elif c == '(':
            stack.append((ln, c))
        elif c == ')':
            if stack and stack[-1][1] == '(':
                stack.pop()
            else:
                print('多余右括号 at line', ln, ':', line[max(0,i-40):i+40])
        i += 1
print('剩余未匹配左括号:', len(stack))
for x in stack[:10]:
    print('  line', x[0])
