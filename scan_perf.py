# -*- coding: utf-8 -*-
import docx

doc = docx.Document(r'E:\xuexi\MASON\论文初稿v3.docx')
paras = [p.text for p in doc.paragraphs]

print('===== 实时/在线宣称位置 =====')
seen = set()
for i, p in enumerate(paras):
    t = p.strip()
    if not t or i >= 146:
        continue
    for kw in ['实时', '在线', '速度倍率']:
        idx = t.find(kw)
        if idx >= 0 and (i, kw) not in seen:
            seen.add((i, kw))
            print(f'[段{i}] ...{t[max(0,idx-35):idx+45]}...')
            break

print()
print('===== 性能数据关键词扫描（正文） =====')
for kw in ['耗时', '帧', 'fps', 'FPS', 'ms', '毫秒', '加速比', '内存', '计算时间', '性能', '吞吐', '步/秒', '每步']:
    hits = [i for i, p in enumerate(paras) if kw in p]
    print(f'  "{kw}": {hits if hits else "无"}')

print()
print('===== 7.2 开头原文 =====')
print(paras[103][:220])
