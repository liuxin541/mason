# -*- coding: utf-8 -*-
"""放大弹窗 wheel/拖动 重绘改为 rAF 合并，避免每事件全量重绘"""
import io, sys

PATH = r'E:\xuexi\MASON\mason\fireescape.html'
with io.open(PATH, 'r', encoding='utf-8') as f:
    content = f.read()
NL = '\r\n' if content.count('\r\n') > content.count('\n') - content.count('\r\n') else '\n'

def sub(old, new, label, must=True):
    global content
    cnt = content.count(old)
    if cnt == 0:
        if must:
            print(f'[FAIL] 未找到: {label}'); sys.exit(1)
        print(f'[SKIP] {label}'); return
    if cnt > 1:
        print(f'[FAIL] 匹配 {cnt} 处(应唯一): {label}'); sys.exit(1)
    content = content.replace(old, new)
    print(f'[OK] {label}')

# 1. 在 zoomView 状态后定义 rAF 合并重绘函数
old1 = (
    "        let zoomView = { scale: 1, offsetX: 0, offsetY: 0 };" + NL +
    "        let zoomHover = null;" + NL +
    "        let zoomFloorZ = -1;" + NL +
    "        let zoomPanning = false, zoomLastX = 0, zoomLastY = 0;" + NL +
    "        let zoomMouseDownX = 0, zoomMouseDownY = 0, zoomDragged = false;" + NL
)
new1 = (
    "        let zoomView = { scale: 1, offsetX: 0, offsetY: 0 };" + NL +
    "        let zoomHover = null;" + NL +
    "        let zoomFloorZ = -1;" + NL +
    "        let zoomPanning = false, zoomLastX = 0, zoomLastY = 0;" + NL +
    "        let zoomMouseDownX = 0, zoomMouseDownY = 0, zoomDragged = false;" + NL +
    "        // ★ 性能：wheel/拖动等高频交互的重绘合并到 rAF，避免每事件全量 Canvas 重绘" + NL +
    "        let zoomRedrawPending = false;" + NL +
    "        function requestZoomRedraw() {" + NL +
    "            if (zoomRedrawPending) return;" + NL +
    "            zoomRedrawPending = true;" + NL +
    "            requestAnimationFrame(() => { zoomRedrawPending = false; drawZoomMap(); });" + NL +
    "        }" + NL
)
sub(old1, new1, '定义 rAF 合并重绘函数')

# 2. wheel 缩放重绘 → rAF 合并
old2 = (
    "            zoomView.offsetY = my - (my - zoomView.offsetY) * (ns / oldScale);" + NL +
    "            zoomView.scale = ns;" + NL +
    "            drawZoomMap();" + NL +
    "        }, { passive: false });" + NL
)
new2 = (
    "            zoomView.offsetY = my - (my - zoomView.offsetY) * (ns / oldScale);" + NL +
    "            zoomView.scale = ns;" + NL +
    "            requestZoomRedraw();" + NL +
    "        }, { passive: false });" + NL
)
sub(old2, new2, 'wheel 缩放重绘合并 rAF')

# 3. 拖动平移重绘 → rAF 合并
old3 = (
    "                zoomLastX = e.clientX;" + NL +
    "                zoomLastY = e.clientY;" + NL +
    "                drawZoomMap();" + NL +
    "            }" + NL +
    "        });" + NL
)
new3 = (
    "                zoomLastX = e.clientX;" + NL +
    "                zoomLastY = e.clientY;" + NL +
    "                requestZoomRedraw();" + NL +
    "            }" + NL +
    "        });" + NL
)
sub(old3, new3, '拖动平移重绘合并 rAF')

with io.open(PATH, 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('[DONE] 放大弹窗重绘节流完成')
