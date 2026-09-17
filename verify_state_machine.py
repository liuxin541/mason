# -*- coding: utf-8 -*-
import docx, re

new = docx.Document(r'E:\xuexi\MASON\mason\论文初稿v3_状态机修正.docx')
orig = docx.Document(r'E:\xuexi\MASON\论文初稿v3.docx')

print('===== 修改后关键位置 =====')
for i in [31, 61, 62, 63, 101]:
    print(f'[段{i}] {new.paragraphs[i].text[:120]}')

print()
print('===== 表3 =====')
for row in new.tables[2].rows:
    print([c.text for c in row.cells])

print()
print('===== 全文一致性检查（应无遗漏） =====')
full = '\n'.join(p.text for p in new.paragraphs)
print('"三态" 出现次数:', full.count('三态'))
print('"存活" 出现次数（正文+表格）:', full.count('存活'))
tbl_full = '\n'.join(c.text for t in new.tables for r in t.rows for c in r.cells)
print('表格中"存活":', tbl_full.count('存活'))
print('"待疏散" 出现次数:', full.count('待疏散') + tbl_full.count('待疏散'))
print('"四态" 出现次数:', full.count('四态'))

print()
print('===== 数值与结构未变检查 =====')
same_paras = len(orig.paragraphs) == len(new.paragraphs)
print('段落数一致:', same_paras, len(orig.paragraphs), '->', len(new.paragraphs))
print('表格数一致:', len(orig.tables) == len(new.tables))
# 表3 数值行
o3 = [[c.text for c in r.cells] for r in orig.tables[2].rows]
n3 = [[c.text for c in r.cells] for r in new.tables[2].rows]
print('表3 数值行（除表头外）一致:', o3[1:] == n3[1:])
