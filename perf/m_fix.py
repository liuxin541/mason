# -*- coding: utf-8 -*-
"""M1-M13 综合修复脚本（工作副本编辑）"""
import shutil, copy, re
import docx
from docx.shared import Pt

SRC = r'E:\xuexi\MASON\论文初稿v3.docx'          # 只读基线
TGT = r'E:\xuexi\MASON\mason\论文初稿v3_M13修复.docx'  # 工作副本（新建）
BAK = r'E:\xuexi\MASON\mason\_m13_before.docx'

shutil.copy2(SRC, BAK)
shutil.copy2(SRC, TGT)
doc = docx.Document(TGT)
paras = doc.paragraphs

# ---------- 工具 ----------
def set_run(r, text):
    r.text = text

def para_text(p):
    return ''.join(r.text for r in p.runs)

def rebuild(p, segments, base_fmt_run=None):
    """segments: [(text, sup)] 重建段落 runs；格式继承段内首个 run 或给定 run"""
    src_run = base_fmt_run if base_fmt_run is not None else (p.runs[0] if p.runs else None)
    rpr = None
    if src_run is not None:
        r = src_run._r
        for child in r:
            if child.tag.endswith('}rPr'):
                rpr = copy.deepcopy(child)
                break
    # 清空段落
    for r in list(p.runs):
        r._r.getparent().remove(r._r)
    from docx.oxml.ns import qn
    for text, sup in segments:
        new_r = p.add_run(text)
        if sup:
            rPr = new_r._r.get_or_add_rPr()
            va = rPr.makeelement(qn('w:vertAlign'), {qn('w:val'): 'superscript'})
            rPr.append(va)
        if rpr is not None:
            # 覆盖 rPr（保留上标）
            rPr = new_r._r.get_or_add_rPr()
            for child in list(rPr):
                rPr.remove(child)
            rPr.append(copy.deepcopy(rpr))
            if sup:
                va = rPr.makeelement(qn('w:vertAlign'), {qn('w:val'): 'superscript'})
                rPr.append(va)

def replace_in_para(p, old, new):
    """段落内子串替换（run 级；跨 run 则整段重建为单 run）"""
    full = para_text(p)
    if old not in full:
        print('  !! 未找到子串: %s...' % old[:30]); return False
    newfull = full.replace(old, new)
    rebuild(p, [(newfull, False)])
    return True

CHANGES = []

# ---------- 1. 段16 2.4 改写（M6） ----------
p = paras[16]
seg16 = [
    ('文献', False), ('[6]', True),
    ('将建筑人群疏散建模归纳为七类方法并指出其各自适用场景。近年相关工作在三个方向推进了疏散仿真：', False),
    ('[28]', True), ('提出BIM+FDS+多智能体的疏散仿真框架并统计关键影响因素；', False),
    ('[29]', True), ('以改进元胞自动机结合IoT传感器实现建筑疏散的实时引导；', False),
    ('[30]', True), ('将FDS灾场数据实时加载到元胞自动机元胞中实现火灾与疏散的动态耦合。此外，', False),
    ('[31]', True), ('对火灾中人员疏散响应过程进行了推理与评估建模。上述工作分别在平台集成、实时性与灾—人耦合上推进了仿真能力，但或依赖CFD计算而难以在线交互（文献', False),
    ('[28]', True), ('），或将实时性建立在专用IoT传感硬件之上（文献', False),
    ('[29]', True), ('），或仍以离线FDS数据驱动元胞而非自洽的火—烟—人群在线演化（文献', False),
    ('[30]', True), ('）。为清晰说明本文定位，表1从火灾物理、烟气毒性、人群模型、三维/BIM能力、实时交互与可扩展性等维度，对主流工具与本文系统进行对比。', False),
]
rebuild(p, seg16)
CHANGES.append('段16 2.4改写(M6)')

# ---------- 2. 段38 M10（α 单位） ----------
ok = replace_in_para(paras[38],
    '式中：Q为火源强度，α为增长系数（基准取中速t²火α=0.0117，并以Q∞=150作稳态截断），tig为点燃时刻。',
    '式中：Q为火源热释放速率（kW），α为增长系数，取标准中速t²火α=0.0117 kW/s²，并以Q∞=150 kW作稳态截断，tig为点燃时刻。')
if ok: CHANGES.append('段38 M10 α单位')

# ---------- 3. 段40 M10（归一化映射） ----------
ok = replace_in_para(paras[40],
    '并计入火源注入qs,i；障碍体素恒置零，强度整体截断于区间[0,100]：',
    '并计入火源注入qs,i；火源强度Q(t)经归一化因子Q(t)/Q∞∈[0,1]按线性标度映射为体素场强度Fi（环境背景对应0、稳态饱和Q=Q∞对应100），障碍体素恒置零，强度整体截断于区间[0,100]：')
if ok: CHANGES.append('段40 M10 归一化')

# ---------- 4. 段103 M1（指标定义） ----------
ok = replace_in_para(paras[103],
    '指标包括疏散率、烟气死亡率、高温死亡率、幸存者平均疏散时间与95分位疏散时间T95，并报告样本均值、标准差与95%置信区间（t0.975,29=2.045）。',
    '指标定义如下：疏散率=成功逃离人数/总人数；烟气死亡率=烟气失能死亡人数/总人数；高温死亡率=高温死亡人数/总人数；幸存者平均疏散时间=成功逃离人员逃生耗时的平均值；T95=按逃生耗时升序排列的95分位完成时间（95%人员完成疏散所需时间）。每组工况独立重复30次，报告样本均值、标准差与95%置信区间（t0.975,29=2.045，区间半宽≈0.37×SD）。')
if ok: CHANGES.append('段103 M1 指标定义')

# ---------- 5. 段107 M2+M13 ----------
ok = replace_in_para(paras[107],
    '三者疏散率之差均落在约1.2个百分点的95%置信区间内、不具统计显著性。',
    '三者疏散率之差均落在约1.2个百分点（95%置信区间半宽，按t0.975,29·SD/√30计算）内、不具统计显著性。其中“1处”为单火源居中布置，与（5）中火源位置“中央”为同一工况，故表5中两组数值完全一致。')
if ok: CHANGES.append('段107 M2+M13')

# ---------- 6. 段108 M3（密度选择效应） ----------
ok = replace_in_para(paras[108],
    '且相邻水平差异超出95%置信区间，格点排他机制下密度效应得以正确涌现。',
    '且相邻水平差异超出95%置信区间，格点排他机制下密度效应得以正确涌现。需注意，180人组幸存者平均疏散时间（66.0 s）略低于60人组（66.9 s），并非“密度越高疏散越快”：高密度下更多人员在出口排队中累积烟气暴露并因FED失能、提前退出幸存者集合，幸存者多为较早通过出口者，其平均疏散时间反而更短，此为竞争风险下的幸存者选择效应（与7.2.1节（4）及7.2.6节一致），该指标须与疏散率、死亡率联合解读。')
if ok: CHANGES.append('段108 M3')

# ---------- 7. 段111 M13 + [29]→[32] ----------
p = paras[111]
seg111 = [
    ('（5）火源位置：火源远离出口、位于中央、靠近出口时疏散率分别为89.1%、79.8%、78.7%，烟气死亡率分别为10.9%、20.2%、21.3%。火源远离出口时撤离条件最好，火源位于中央或靠近出口会胁迫、封堵主要撤离路径，并迫使人员改选其他出口、在背离火源的出口前形成局部拥堵，结局显著恶化；这与文献', False),
    ('[32]', True),
    ('关于火源位置改变人员出口选择、并在局部形成拥堵的模拟结论一致（其中“中央”档与（1）中火源数量“1处”为同一工况，表5中两组数值一致）。', False),
]
rebuild(p, seg111)
CHANGES.append('段111 M13+[29]->[32]')

# ---------- 8. 段123 M12（破折号） ----------
ok = replace_in_para(paras[123],
    '但烟气毒性的绝对结局对分辨率仍较敏感——Δ=0.50 m时存在约两成烟气死亡',
    '但烟气毒性的绝对结局对分辨率仍较敏感：Δ=0.50 m时存在约两成烟气死亡')
if ok: CHANGES.append('段123 M12 破折号')

# ---------- 9. 段127 M4（死亡分层） ----------
ok = replace_in_para(paras[127],
    '汇总全部对照实验（共570次仿真），高温直接死亡累计0人次、烟气FED死亡累计10790人次，烟气毒性致死占比100%（图8）。',
    '汇总全部对照实验（共570次仿真），高温直接死亡累计0人次、烟气FED死亡累计10790人次，烟气毒性致死占比100%（图8）。按网格分辨率分层可见：全部10790人次死亡均来自Δ=0.50 m标定档（480次仿真），Δ=0.30、0.25、0.20 m三档细网格工况（90次仿真）死亡均为0人次，毒性死亡人次仅在相同网格分辨率口径下可比（见7.2.3节）。')
if ok: CHANGES.append('段127 M4')

# ---------- 10. 段131 M8（FDS 细节） ----------
ok = replace_in_para(paras[131],
    '为检验火—烟物理场的外部一致性，采用FDS 6.11.1建立与基准场景同尺寸（24 m×15 m×3 m、单出口、三处中速t²火源（α=0.0117），网格0.5 m）的算例，在近火源、中场与出口内侧的1.5 m和2.0 m高度布置温度与能见度测点，模拟300 s。',
    '为检验火—烟物理场的外部一致性，采用FDS 6.11.1建立与基准场景同尺寸（24 m×15 m×3 m、单出口）的算例：网格取0.5 m（48×30×6体素）与元胞模型体素一一对应；三处火源各为0.5 m×0.5 m单格燃烧器（HRRPUA=600 kW/m²、单源150 kW，丙烷燃料、烟产率0.05、CO产率0.04），HRR按α=0.0117 kW/s²的慢速t²曲线于113.2 s达稳态150 kW；三源合计约450 kW，特征火源直径D*≈0.7 m、D*/δx≈1.4，低于FDS指南推荐的网格分辨率下限，故本交叉标定仅在定性层面展开。模拟时长300 s，覆盖烟气层降至人员高度的关键窗口（约120 s，见7.2.1节）；在近火源、中场与出口内侧的1.5 m和2.0 m高度布置温度与能见度测点，能见度危害阈值10 m、极限可视距离6 m参照ISO 13571对逃生阶段能见度临界值的常用取值。')
if ok: CHANGES.append('段131 M8')

# ---------- 11. 段139 M7（SFPE 流量对比） ----------
p = paras[139]
seg139 = [
    ('为定位本文模型相对经典元胞疏散模型的位置，在同一基准场景与配对种子下设置三组对照（每种人数各重复30次）：其一为退化的静态地板场CA，即关闭火源与烟气、仅按到出口距离决策；其二保留火—烟场但关闭火、烟代价权重，人员沿最短距离硬穿火烟；其三为本文中性动态火烟代价场，结果如表7。退化静态CA在60、120、180人下均实现100%疏散、无死亡，出口流量峰值随密度由1.92人/s单调升至3.77人/s。需与物理经验值核对：按SFPE推荐的单位宽度出口流量约1.3人/(m·s)估算，0.5 m宽出口对应稳态约0.65人/s，本文静态CA峰值明显偏高，其结构原因在于离散元胞出口仅1格宽、每Δt=0.5 s最多放行1人（理论上限约2人/s，2 s采样窗口内瞬时峰最高可达4人/s），且无人体体积与姿态约束、出口格瞬离豁免，故绝对疏散时间存在系统性偏短风险；但本文全部结论均为同分辨率下的工况相对比较与方案排序，该偏差不影响相对结论，适用范围以7.2.5节FDS与本节经典CA的交叉验证为界。这与文献', False),
    ('[12-13,20-21]', True),
    ('等经典元胞疏散模型在无灾害场景下“人员可全部撤离、单出口通过能力随需求上升并形成瓶颈”的普遍规律定性一致，说明本文运动学内核在退化到经典CA时行为合理，可作为比较基准。', False),
]
rebuild(p, seg139)
CHANGES.append('段139 M7')

# ---------- 12. 表5 题注与表注（M2） ----------
replace_in_para(paras[112],
    '表5 单因子对照实验结果（每工况30次配对重复，报告样本均值）',
    '表5 单因子对照实验结果（每工况30次配对重复，报告样本均值±标准差）')
replace_in_para(paras[113],
    '注：网格Δ四行仅用于考察收敛趋势；烟气毒性死亡的绝对数量具有分辨率尺度依赖，不跨分辨率比较绝对值。',
    '注：表中数值为30次配对重复的样本均值±标准差（±后为SD），95%置信区间半宽按t0.975,29·SD/√30≈0.37·SD估算；网格Δ四行仅用于考察收敛趋势；烟气毒性死亡的绝对数量具有分辨率尺度依赖，不跨分辨率比较绝对值。')
CHANGES.append('表5题注+表注 M2')

# ---------- 13. 表5 数值改均值±SD（M2） ----------
t5 = doc.tables[4]
t5data = [
    ['79.8±3.3','20.2±3.3','66.4±3.4','122.2±2.0'],
    ['77.9±3.3','22.1±3.3','66.7±3.4','120.3±2.1'],
    ['78.8±3.4','21.3±3.4','66.9±3.1','121.0±1.8'],
    ['81.6±4.7','18.4±4.7','66.9±4.4','119.8±2.5'],
    ['77.9±3.3','22.1±3.3','66.7±3.4','120.3±2.1'],
    ['72.4±3.5','27.6±3.5','66.0±2.1','119.5±1.5'],
    ['81.8±3.2','18.3±3.2','65.1±3.2','117.8±2.3'],
    ['77.9±3.3','22.1±3.3','66.7±3.4','120.3±2.1'],
    ['73.3±3.7','26.7±3.7','72.6±2.5','123.4±1.7'],
    ['100.0±0.0','0.0±0.0','77.7±3.4','142.1±3.7'],
    ['100.0±0.0','0.0±0.0','83.8±3.9','153.5±4.8'],
    ['77.9±3.3','22.1±3.3','66.7±3.4','120.3±2.1'],
    ['89.1±3.0','10.9±3.0','72.2±4.0','133.3±2.4'],
    ['79.8±3.3','20.2±3.3','66.4±3.4','122.2±2.0'],
    ['78.7±3.8','21.3±3.8','68.3±3.6','120.7±1.9'],
    ['77.9±3.3','22.1±3.3','66.7±3.4','120.3±2.1'],
    ['100.0±0.0','0.0±0.0','79.1±4.2','143.9±4.0'],
    ['100.0±0.0','0.0±0.0','77.9±4.2','144.6±3.2'],
    ['100.0±0.0','0.0±0.0','78.9±4.3','143.6±3.5'],
]
for ri, vals in enumerate(t5data, start=1):
    row = t5.rows[ri]
    for ci, v in enumerate(vals, start=2):
        cell = row.cells[ci]
        for p in cell.paragraphs:
            for r in p.runs:
                r.text = ''
            p.add_run(v)
CHANGES.append('表5 数值±SD')

# ---------- 14. 表7 加列 幸存者平均累计FED（M5） ----------
t7 = doc.tables[6]
from docx.oxml.ns import qn
def add_col(table, header, values):
    for ri, row in enumerate(table.rows):
        new_tc = copy.deepcopy(row._tr.tc_lst[-1])
        row._tr.append(new_tc)
        # 清空文本
        tc_paras = new_tc.findall(qn('w:p'))
        for tp in tc_paras:
            for r in tp.findall(qn('w:r')):
                tp.remove(r)
        # 写入
        from docx.text.paragraph import Paragraph
        if tc_paras:
            first_p = Paragraph(tc_paras[0], table._parent)
            first_p.add_run(header if ri == 0 else values[ri - 1])
        else:
            pass
add_col(t7, '幸存者平均累计FED', [
    '0.00', '0.00', '0.00',
    '0.20', '0.20', '0.20',
    '0.28', '0.27', '0.27',
])
CHANGES.append('表7 加FED列 M5')

# ---------- 15. 表7 题注（M5） ----------
replace_in_para(paras[141],
    '表7 与经典元胞疏散模型的同工况横向对比（每种人数30次配对重复）',
    '表7 与经典元胞疏散模型的同工况横向对比（每种人数30次配对重复；幸存者平均累计FED为成功逃离人员暴露剂量的样本均值）')

# ---------- 16. 表1 表注（M11） ----------
# 在表1 表格后、段18 前插入注
p18 = paras[18]._p
from docx.oxml import OxmlElement
note_p = OxmlElement('w:p')
p18.addprevious(note_p)
# 用 docx 对象写
from docx.text.paragraph import Paragraph
np = Paragraph(note_p, paras[18]._parent)
run = np.add_run('注：本表依据文献[4,6,14-18]与本文分析整理。')
CHANGES.append('表1 表注 M11')

# ---------- 17. 引文全局重编号：sup 引文 [28]->[31]、[29]->[32] ----------
renum = {'28': '31', '29': '32'}
cnt = 0
for pi, p in enumerate(paras):
    if pi in (16, 111):  # 已重建
        continue
    for r in p.runs:
        if r.font.superscript and r.text.startswith('[') and r.text.endswith(']'):
            inner = r.text[1:-1]
            new_parts = []
            for part in re.split(r'([\-–,，])', inner):
                if part.isdigit() and part in renum:
                    new_parts.append(renum[part])
                else:
                    new_parts.append(part)
            new_inner = ''.join(new_parts)
            if new_inner != inner:
                r.text = '[' + new_inner + ']'
                cnt += 1
                print('  重编号段%d: %s -> %s' % (pi, r.text, '['+new_inner+']'))
CHANGES.append('引文重编号 %d 处' % cnt)

# ---------- 18. 参考文献：插入 3 条 + 旧28/29 改号 ----------
# 找 [27] 与 [28] 段
idx27 = idx28 = None
for i in range(150, len(paras)):
    t = paras[i].text.strip()
    if t.startswith('[27]'): idx27 = i
    if t.startswith('[28]'): idx28 = i
    if t.startswith('[29]'): idx29 = i
print('  参考文献 [27]=段%d [28]=段%d [29]=段%d' % (idx27, idx28, idx29))
new_refs = [
    '[28] SUN Q, TURKAN Y. A BIM-based simulation framework for fire safety management and investigation of the critical factors affecting human evacuation performance[J]. Advanced Engineering Informatics, 2020, 44: 101093.',
    '[29] JI Y P, WANG W S, ZHENG M Y, et al. Real time building evacuation modeling with an improved cellular automata method and corresponding IoT system implementation[J]. Buildings, 2022, 12(6): 718.',
    '[30] 李超, 李宇飞, 霍非舟, 等. 基于FDS和元胞自动机动态耦合的火灾疏散模型[J]. 计算机应用研究, 2022, 39(9): 33-38.',
]
anchor = paras[idx28]._p
for ref in new_refs:
    new_p = OxmlElement('w:p')
    anchor.addprevious(new_p)
    np2 = Paragraph(new_p, paras[idx28]._parent)
    np2.add_run(ref)
# 旧 [28] 改 [31]、旧 [29] 改 [32]
replace_in_para(paras[idx28], '[28]', '[31]')
replace_in_para(paras[idx29], '[29]', '[32]')
CHANGES.append('参考文献插入3条+改号')

doc.save(TGT)
print('SAVED:', TGT)
print('完成修改:', CHANGES)
