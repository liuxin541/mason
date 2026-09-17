# -*- coding: utf-8 -*-
import docx

path = r'E:\xuexi\MASON\mason\论文初稿v3_状态机修正.docx'
doc = docx.Document(path)
log = []

# 1) 段31: 三态 -> 四态
p = doc.paragraphs[31]
assert '在存活、逃离、死亡三态间转移' in p.text
p.runs[0].text = p.runs[0].text.replace('在存活、逃离、死亡三态间转移',
                                        '在待疏散、逃离、死亡、完成四种状态间转移')
log.append('段31 已改')

# 2) 段61: 4.4.1 标题
p = doc.paragraphs[61]
assert p.text == '4.4.1 三态状态机与移动冷却'
p.runs[0].text = '4.4.1 四态状态机与移动冷却'
log.append('段61 已改')

# 3) 段62: 状态声明
p = doc.paragraphs[62]
assert '每名智能体具有存活、逃离、死亡三种状态' in p.text
p.runs[0].text = '每名智能体具有待疏散、逃离、完成、死亡四种状态，其状态转移为'
log.append('段62 已改')

# 4) 段63 式(8): 只改 run1 文本（公式 run 不动）
p = doc.paragraphs[63]
r1 = p.runs[1]
assert '存活→逃离（开始移动）；存活/逃离→死亡（' in r1.text
r1.text = '待疏散→逃离（开始移动）；待疏散/逃离→死亡（'
log.append('段63 式(8) 已改')

# 5) 表3 表头: 存活/人 -> 未完成/人
t = doc.tables[2]
hdr = t.rows[0].cells
target_cell = None
for c in hdr:
    if '存活' in c.text:
        target_cell = c
        break
assert target_cell is not None
for para in target_cell.paragraphs:
    for r in para.runs:
        if '存活' in r.text:
            r.text = r.text.replace('存活/人', '未完成/人')
log.append('表3 表头已改')

# 6) 段101 题注: 追加口径说明
p = doc.paragraphs[101]
assert '表3 典型室内场景三阶段人员状态统计（100名智能体、5处火源）' in p.text
p.runs[0].text = ('表3 典型室内场景三阶段人员状态统计（100名智能体、5处火源；'
                  '\u201c未完成\u201d含待疏散与逃离中人员，'
                  '\u201c成功逃离\u201d即完成状态）')
log.append('段101 题注已改')

doc.save(path)
for l in log:
    print(l)
print('ALL DONE')
