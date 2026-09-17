# -*- coding: utf-8 -*-
"""buildThumbStrip 重建后立即重绘缩略条，避免窗口期空白"""
import io, sys

PATH = r'E:\xuexi\MASON\mason\fireescape.html'
with io.open(PATH, 'r', encoding='utf-8') as f:
    content = f.read()
NL = '\r\n' if content.count('\r\n') > content.count('\n') - content.count('\r\n') else '\n'

old = (
    "            wrap.appendChild(lbl); wrap.appendChild(cvs);" + NL +
    "            strip.appendChild(wrap);" + NL +
    "            thumbCanvases.push({ canvas: cvs, floorZ: fz, idx: i });" + NL +
    "        }" + NL +
    "    }" + NL
)
new = (
    "            wrap.appendChild(lbl); wrap.appendChild(cvs);" + NL +
    "            strip.appendChild(wrap);" + NL +
    "            thumbCanvases.push({ canvas: cvs, floorZ: fz, idx: i });" + NL +
    "        }" + NL +
    "        drawThumbStrip();  // ★ 重建后立即绘制，避免楼层检测后缩略条空白" + NL +
    "    }" + NL
)
cnt = content.count(old)
if cnt != 1:
    print(f'[FAIL] 匹配 {cnt} 处，应唯一'); sys.exit(1)
content = content.replace(old, new)
with io.open(PATH, 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('[OK] buildThumbStrip 重建后立即绘制')
