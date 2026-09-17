# -*- coding: utf-8 -*-
"""m1/m3/m5 修复脚本"""
import shutil, docx
from docx.shared import Pt

SRC = r'E:\xuexi\MASON\论文初稿v3.docx'
TGT = r'E:\xuexi\MASON\mason\论文初稿v3_minor.docx'
shutil.copyfile(SRC, TGT)

doc = docx.Document(TGT)
paras = doc.paragraphs
changes = []

# ---------- m3：段7 run4 补锚点 ----------
p7 = paras[7]
old_phrase = '在标准化场景开展'
new_phrase = '在24 m×15 m×3 m标准化矩形单层大空间基准场景（后文第7章详述）开展'
done7 = False
for r in p7.runs:
    if old_phrase in r.text and not r.font.superscript:
        r.text = r.text.replace(old_phrase, new_phrase)
        done7 = True
        break
changes.append(('m3 引言锚点', done7))

# ---------- m5：段108 机理长句精简 ----------
p108 = paras[108]
old_m = ('其机理在于：本文各火源取相同热释放水平的中速t²源（α=0.0117、稳态截断Q∞=150），'
         '在单一封闭大空间内烟气充分混合后会较快达到危害浓度，增加火源主要缩短烟气层下降至人员高度的时间；'
         '当火源不少于3处时，烟气均能在人员完成撤离前下沉并充满空间，继续增加火源已难以进一步显著缩短该时间，'
         '与此同时单出口在格点排他下的通过能力趋于固定瓶颈，'
         '两方面共同使火源数超过一定规模后烟气危害趋于饱和，'
         '疏散结局转而主要由烟气层下降时间与出口通过能力决定。')
new_m = ('其机理在于：单一封闭大空间内烟气混合充分，3处以上火源均可在人员完成撤离前使烟气下沉充满空间，'
         '危害趋于饱和；同时单出口在格点排他下的通过能力构成固定瓶颈，'
         '故疏散结局主要由烟气层下降时间与出口通过能力决定。')
done108 = False
full = p108.text
if old_m in full:
    runs = p108.runs
    if len(runs) == 1 and not runs[0].font.superscript:
        runs[0].text = full.replace(old_m, new_m)
        done108 = True
    else:
        # 多 run：找到包含 old_m 的连续区段，做拆分重建（保守：整段重建并克隆 rPr）
        rpr = runs[0]._r.rPr
        for r in list(runs):
            r._r.getparent().remove(r._r)
        from docx.oxml.ns import qn
        new_text = full.replace(old_m, new_m)
        r = p108.add_run(new_text)
        if rpr is not None:
            r._r.insert(0, rpr)
        done108 = True
changes.append(('m5 机理精简', done108))

# ---------- m1a：段153 [1] 更新 5th ed ----------
def rebuild(paras, idx, new_text):
    p = paras[idx]
    runs = p.runs
    rpr = runs[0]._r.rPr if runs else None
    for r in list(runs):
        r._r.getparent().remove(r._r)
    r = p.add_run(new_text)
    if rpr is not None:
        r._r.insert(0, rpr)
    return True

new1 = ('[1] PURSER D A. Toxicity assessment of combustion products[M]//HURLEY M J, GOTTUK D T, '
        'HALL J R, et al. SFPE Handbook of Fire Protection Engineering. 5th ed. New York: Springer, 2016.')
changes.append(('m1a [1] 5th ed', rebuild(paras, 153, new1)))

# ---------- m1b：段177 [25] 替换为 ISO 13571:2012 ----------
new25 = ('[25] International Organization for Standardization. Life-threatening components of fire: '
        'Guidelines for the estimation of time to compromised tenability in fires: ISO 13571:2012[S]. '
        'Geneva: ISO, 2012.')
changes.append(('m1b [25] ISO 13571', rebuild(paras, 177, new25)))

doc.save(TGT)
print('修改清单:')
for name, ok in changes:
    print('  %s -> %s' % (name, 'OK' if ok else 'FAIL'))

# ---------- 回读验证 ----------
d2 = docx.Document(TGT)
print()
print('段7 锚点:', '24 m×15 m×3 m标准化矩形单层大空间基准场景（后文第7章详述）' in d2.paragraphs[7].text)
print('段7 上标保留:', [bool(r.font.superscript) for r in d2.paragraphs[7].runs if r.font.superscript])
t108 = d2.paragraphs[108].text
print('段108 精简:', '其机理在于：单一封闭大空间内烟气混合充分' in t108 and '两因素概括' not in t108)
print('段108 旧长句清除:', '本文各火源取相同热释放水平的中速t²源' not in t108)
print('段153:', d2.paragraphs[153].text[:90])
print('段177:', d2.paragraphs[177].text[:110])
