# -*- coding: utf-8 -*-
"""M1-M13 修复回读验证"""
import docx, re
doc = docx.Document(r'E:\xuexi\MASON\mason\论文初稿v3_M13修复.docx')
paras = doc.paragraphs
print('总段数:', len(paras))
print()
print('===== 关键段落 =====')
for i in [16, 38, 40, 103, 107, 108, 111, 123, 127, 131, 139, 112, 113, 141]:
    print('[段%d] %s' % (i, paras[i].text[:300]))
    print()

print('===== 表5 前6行 =====')
t5 = doc.tables[4]
for row in t5.rows[:6]:
    print([c.text for c in row.cells])
print('... 网格行:')
for row in t5.rows[16:20]:
    print([c.text for c in row.cells])

print()
print('===== 表7 =====')
t7 = doc.tables[6]
for row in t7.rows:
    print([c.text for c in row.cells])

print()
print('===== 表1 表注（表1后段） =====')
t1 = doc.tables[0]
# 找表1后的段落
found = None
for i, p in enumerate(paras):
    if '本表依据文献' in p.text:
        found = (i, p.text)
        break
print('  表注:', found)

print()
print('===== 参考文献 28-32 =====')
for i in range(150, len(paras)):
    t = paras[i].text.strip()
    if t.startswith('[27]') or t.startswith('[28]') or t.startswith('[29]') or t.startswith('[30]') or t.startswith('[31]') or t.startswith('[32]'):
        print('[段%d] %s' % (i, t[:100]))

print()
print('===== 引文编号顺序重扫描 =====')
pat = re.compile(r'\[(\d+(?:[-–,，]\d+)*)\]')
first_seen = {}
order = []
for i, p in enumerate(paras):
    if p.text.startswith('[') and p.style.name in ('Normal',):
        # 跳过参考文献列表段
        if re.match(r'^\[\d+\]', p.text.strip()):
            continue
    for m in pat.finditer(p.text):
        inner = m.group(1)
        for part in re.split(r'[-–,，]', inner):
            if part.isdigit():
                n = int(part)
                if 0 < n <= 50 and n not in first_seen:
                    first_seen[n] = i
                    order.append(n)
print('顺序:', order)
print('编号范围: %d ~ %d' % (min(order), max(order)))
missing = [n for n in range(1, max(order)+1) if n not in first_seen]
print('缺失编号:', missing)
