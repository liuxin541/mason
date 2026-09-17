#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import re
with open('fireescape.html', 'r', encoding='utf-8') as f:
    content = f.read()
# 主脚本: <script type="module"> 到 </script>
m = re.search(r'<script type="module">(.*?)</script>', content, re.DOTALL)
if m:
    s = m.group(1)
    opens = s.count('{'); closes = s.count('}')
    po = s.count('('); pc = s.count(')')
    print('主script长度:', len(s))
    print('大括号 %d/%d %s' % (opens, closes, 'OK' if opens==closes else 'MISMATCH!'))
    print('圆括号 %d/%d %s' % (po, pc, 'OK' if po==pc else 'MISMATCH!'))
else:
    print('未找到主script')
