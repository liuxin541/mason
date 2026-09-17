# -*- coding: utf-8 -*-
"""修复新增/重写 run 的字体格式"""
import docx
from docx.shared import Pt

TGT = r'E:\xuexi\MASON\mason\论文初稿v3_M13修复.docx'
doc = docx.Document(TGT)

def fmt_runs(runs, name='Times New Roman', size=9.5):
    for r in runs:
        r.font.name = name
        r.font.size = Pt(size)

# 1. 表5 数据单元格（rows[1:] × cells[2:6]）
t5 = doc.tables[4]
for row in t5.rows[1:]:
    for ci in range(2, 6):
        cell = row.cells[ci]
        for p in cell.paragraphs:
            fmt_runs(p.runs, size=9.5)

# 2. 表7 FED 列（最后一列）
t7 = doc.tables[6]
for row in t7.rows:
    cell = row.cells[-1]
    for p in cell.paragraphs:
        fmt_runs(p.runs, size=9.0)

# 3. 参考文献新增段（[28][29][30] = 段180/181/182）
paras = doc.paragraphs
for i in range(150, len(paras)):
    t = paras[i].text.strip()
    if t.startswith('[28] SUN') or t.startswith('[29] JI') or t.startswith('[30] 李超'):
        fmt_runs(paras[i].runs, size=10.5)

# 4. 表1 表注段（含"本表依据文献"）
for p in paras:
    if '本表依据文献' in p.text:
        fmt_runs(p.runs, size=9.0)
        break

doc.save(TGT)
print('格式修复完成')

# 复核
doc2 = docx.Document(TGT)
t5b = doc2.tables[4]
for r in t5b.rows[1].cells[2].paragraphs[0].runs:
    print('表5:', repr(r.text[:10]), r.font.name, r.font.size.pt if r.font.size else None)
t7b = doc2.tables[6]
for r in t7b.rows[1].cells[-1].paragraphs[0].runs:
    print('表7FED:', repr(r.text), r.font.name, r.font.size.pt if r.font.size else None)
for p in doc2.paragraphs:
    if p.text.strip().startswith('[28] SUN'):
        for r in p.runs:
            print('参考28:', repr(r.text[:15]), r.font.name, r.font.size.pt if r.font.size else None)
        break
for p in doc2.paragraphs:
    if '本表依据文献' in p.text:
        for r in p.runs:
            print('表1注:', repr(r.text[:12]), r.font.name, r.font.size.pt if r.font.size else None)
        break
