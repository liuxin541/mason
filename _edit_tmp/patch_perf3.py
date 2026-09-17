# -*- coding: utf-8 -*-
"""调整 fetchState 内重绘顺序：ensurePlanViewReady(fit) 之后再重绘平面图"""
import io, sys

PATH = r'E:\xuexi\MASON\mason\fireescape.html'
with io.open(PATH, 'r', encoding='utf-8') as f:
    content = f.read()
NL = '\r\n' if content.count('\r\n') > content.count('\n') - content.count('\r\n') else '\n'

old = (
    "                updateFireLights(d);" + NL +
    "                // ★ 平面图：数据到达后立即重绘一次（小地图/缩略条/放大弹窗），保证新数据及时显示" + NL +
    "                drawMinimap();" + NL +
    "                drawThumbStrip();" + NL +
    "                if (window.__isZoomOpen && window.__isZoomOpen()) {" + NL +
    "                    window.__drawZoomMap && window.__drawZoomMap();" + NL +
    "                }" + NL +
    "                // ★ 平面图：首次有数据时自适应画布 + 自动 fit 居中" + NL +
    "                ensurePlanViewReady(d);" + NL
)
new = (
    "                updateFireLights(d);" + NL +
    "                // ★ 平面图：首次有数据时自适应画布 + 自动 fit 居中（先 fit 再重绘，保证坐标正确）" + NL +
    "                ensurePlanViewReady(d);" + NL +
    "                // ★ 平面图：数据到达后立即重绘一次（小地图/缩略条/放大弹窗），保证新数据及时显示" + NL +
    "                drawMinimap();" + NL +
    "                drawThumbStrip();" + NL +
    "                if (window.__isZoomOpen && window.__isZoomOpen()) {" + NL +
    "                    window.__drawZoomMap && window.__drawZoomMap();" + NL +
    "                }" + NL
)
cnt = content.count(old)
if cnt != 1:
    print(f'[FAIL] 匹配 {cnt} 处，应唯一'); sys.exit(1)
content = content.replace(old, new)
with io.open(PATH, 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('[OK] 重绘顺序调整完成')
