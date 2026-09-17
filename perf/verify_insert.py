# -*- coding: utf-8 -*-
import docx

new = docx.Document(r'E:\xuexi\MASON\mason\论文初稿v3_性能修正.docx')
orig = docx.Document(r'E:\xuexi\MASON\论文初稿v3.docx')

paras = new.paragraphs
print('===== 新节结构（定位 7.3） =====')
for i, p in enumerate(paras):
    t = p.text.strip()
    if '7.3' in t or '表8' in t or '性能基准' in t or i == 141:
        print(f'[段{i}] [{p.style.name}] {t[:100]}')

print()
print('===== 表8 内容 =====')
t8 = new.tables[7]
for row in t8.rows:
    print([c.text for c in row.cells])
print('表8 style:', t8.style.name if t8.style else None)

print()
print('===== 结构完整性 =====')
print('段落数: 原', len(orig.paragraphs), '-> 新', len(paras))
print('表格数: 原', len(orig.tables), '-> 新', len(new.tables))
print('新增段:', len(paras) - len(orig.paragraphs), '新增表:', len(new.tables) - len(orig.tables))

# 7.2.6 结尾与 8 章衔接
print()
print('===== 插入点上下文 =====')
for i in range(141, 141 + 7):
    t = paras[i].text.strip()
    print(f'[段{i}] {t[:90]}')
