# -*- coding: utf-8 -*-
"""在 7.2.6 表7 题注后插入 7.3 性能基准与实时性验证节（含表8）"""
import copy, docx
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

SRC = r'E:\xuexi\MASON\论文初稿v3.docx'
TGT = r'E:\xuexi\MASON\mason\论文初稿v3_性能修正.docx'
doc = docx.Document(SRC)

# ---------- 1. 构造段落 ----------
def make_para(text, style_id):
    p = OxmlElement('w:p')
    pPr = OxmlElement('w:pPr')
    pStyle = OxmlElement('w:pStyle')
    pStyle.set(qn('w:val'), style_id)
    pPr.append(pStyle)
    p.append(pPr)
    r = OxmlElement('w:r')
    t = OxmlElement('w:t')
    t.set(qn('xml:space'), 'preserve')
    t.text = text
    r.append(t)
    p.append(r)
    return p

H_STYLE = '4'   # Heading 2
N_STYLE = '1'   # Normal

h_para = make_para('7.3 性能基准与实时性验证', H_STYLE)
b1 = make_para(
    '为直接检验设计目标②的实时性指标，在7.2节基准场景（24 m×15 m×3 m单层大空间，'
    'Δ=0.5 m，48×30×6体素，3处火源、中性权重、完整烟气并开启格点排他）下开展无界面内核性能基准：'
    '智能体规模取60、120、180、500人，每规模沿用{1000+37r}前5个配对种子，'
    '测量单次调度步（含火场、烟场更新与全体智能体决策调度）的平均墙钟耗时；'
    '实时加速比定义为物理时间步长与单步耗时之比，λ=Δt/τstep，其中Δt=0.5 s对应1×实时。'
    '测试环境为13th Gen Intel Core i5-13500HX（14核/20线程）、14 GB内存、JDK 25，'
    '内核以单线程串行方式调度，结果不含Web渲染与网络传输开销。', N_STYLE)
b2 = make_para('表8给出各规模下单步耗时的中位数、范围与相应实时加速比。', N_STYLE)
cap = make_para('表8 无界面内核推演性能基准（每规模5个配对种子，报告单步耗时中位数与范围）', N_STYLE)
b3 = make_para(
    '由表8可见，60、120、180、500人规模下每步耗时中位数分别为2.16、2.08、2.26、2.37 ms，'
    '实时加速比达211～240倍，单步推演开销不足500 ms实时预算的1%。'
    '人数由60增至500（约8.3倍）时，单步耗时仅增加约10%，与4.4.2节单次决策O(1)复杂度的分析一致；'
    '即使取最慢单次样本（60人下5.04 ms/步，受JIT预热与系统噪声影响），加速比仍达99倍。'
    '上述实测结果为“百名以上智能体实时演化”提供了直接数据支撑，'
    '并为1×～8×速度倍率交互与Web端实时渲染预留了充足余量。', N_STYLE)

# ---------- 2. 深拷贝表3构造表8（5行4列） ----------
t3 = doc.tables[2]._tbl
new_tbl = copy.deepcopy(t3)
trs = new_tbl.tr_lst
# 删第5列
for tr in trs:
    tcs = tr.findall(qn('w:tc'))
    tr.remove(tcs[-1])
# 追加1行（原4行+1=5行：表头+4数据行）
row_tmpl = trs[3]
for _ in range(1):
    new_tr = copy.deepcopy(row_tmpl)
    new_tbl.append(new_tr)

data = [
    ['智能体规模/人', '单步耗时中位数/ms', '单步耗时范围/ms', '实时加速比/倍'],
    ['60', '2.16', '2.04–5.04', '232'],
    ['120', '2.08', '2.02–2.30', '240'],
    ['180', '2.26', '2.16–2.57', '221'],
    ['500', '2.37', '2.19–2.50', '211'],
]
for ri, tr in enumerate(new_tbl.tr_lst):
    tcs = tr.findall(qn('w:tc'))
    for ci, tc in enumerate(tcs):
        ps = tc.findall(qn('w:p'))
        p0 = ps[0]
        for p in ps[1:]:
            tc.remove(p)
        for r in p0.findall(qn('w:r')):
            p0.remove(r)
        r = p0.makeelement(qn('w:r'), {})
        t = r.makeelement(qn('w:t'), {})
        t.set(qn('xml:space'), 'preserve')
        t.text = data[ri][ci]
        r.append(t)
        p0.append(r)

# ---------- 3. 插入：先挂载链首再串链（避免孤立元素 addnext 限制） ----------
# 目标顺序：…表7 | h → b1 → b2 → cap → 表8 → b3 | 8结论
anchor = doc.paragraphs[142]._p  # "8 结论与展望"
anchor.addprevious(h_para)      # 先挂载链首
h_para.addnext(b1)
b1.addnext(b2)
b2.addnext(cap)
cap.addnext(new_tbl)
new_tbl.addnext(b3)

doc.save(TGT)
print('INSERTED OK')
