# -*- coding: utf-8 -*-
"""优化：drawThumbStrip 从动画循环移除（无动态元素），改为数据/楼层变化时重绘"""
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

# 1. animate 节流块去掉 drawThumbStrip（保留 drawMinimap 与放大弹窗）
old1 = (
    "        const _planNow = performance.now();" + NL +
    "        if (_planNow - lastPlanDrawTime > 150) {" + NL +
    "            lastPlanDrawTime = _planNow;" + NL +
    "            drawMinimap();" + NL +
    "            drawThumbStrip();" + NL +
    "            // 若放大弹窗打开，同步重绘" + NL +
    "            if (window.__isZoomOpen && window.__isZoomOpen()) {" + NL +
    "                window.__drawZoomMap && window.__drawZoomMap();" + NL +
    "            }" + NL +
    "        }" + NL
)
new1 = (
    "        const _planNow = performance.now();" + NL +
    "        if (_planNow - lastPlanDrawTime > 150) {" + NL +
    "            lastPlanDrawTime = _planNow;" + NL +
    "            drawMinimap();" + NL +
    "            // 缩略条无动态元素，仅在数据/楼层变化时重绘（fetchState 与楼层切换处）" + NL +
    "            // 若放大弹窗打开，同步重绘" + NL +
    "            if (window.__isZoomOpen && window.__isZoomOpen()) {" + NL +
    "                window.__drawZoomMap && window.__drawZoomMap();" + NL +
    "            }" + NL +
    "        }" + NL
)
sub(old1, new1, 'animate 节流块移除 drawThumbStrip')

# 2. 楼层切换（up/down）后重绘缩略条
old2 = (
    "        if (idx < detectedFloors.length - 1) {" + NL +
    "            currentFloorZ = detectedFloors[idx + 1];" + NL +
    "            updateFloorLabel();" + NL +
    "            addLog(`🗺️ 切换到楼层 F${idx + 2} (z=${currentFloorZ})`, 'info');" + NL +
    "            // 同步放大弹窗" + NL +
    "            if (window.__syncZoomFloor) window.__syncZoomFloor(currentFloorZ);" + NL +
    "        }" + NL
)
new2 = (
    "        if (idx < detectedFloors.length - 1) {" + NL +
    "            currentFloorZ = detectedFloors[idx + 1];" + NL +
    "            updateFloorLabel();" + NL +
    "            addLog(`🗺️ 切换到楼层 F${idx + 2} (z=${currentFloorZ})`, 'info');" + NL +
    "            drawThumbStrip();" + NL +
    "            // 同步放大弹窗" + NL +
    "            if (window.__syncZoomFloor) window.__syncZoomFloor(currentFloorZ);" + NL +
    "        }" + NL
)
sub(old2, new2, '楼层上切换后重绘缩略条')

old3 = (
    "        if (idx > 0) {" + NL +
    "            currentFloorZ = detectedFloors[idx - 1];" + NL +
    "            updateFloorLabel();" + NL +
    "            addLog(`🗺️ 切换到楼层 F${idx} (z=${currentFloorZ})`, 'info');" + NL +
    "            // 同步放大弹窗" + NL +
    "            if (window.__syncZoomFloor) window.__syncZoomFloor(currentFloorZ);" + NL +
    "        }" + NL
)
new3 = (
    "        if (idx > 0) {" + NL +
    "            currentFloorZ = detectedFloors[idx - 1];" + NL +
    "            updateFloorLabel();" + NL +
    "            addLog(`🗺️ 切换到楼层 F${idx} (z=${currentFloorZ})`, 'info');" + NL +
    "            drawThumbStrip();" + NL +
    "            // 同步放大弹窗" + NL +
    "            if (window.__syncZoomFloor) window.__syncZoomFloor(currentFloorZ);" + NL +
    "        }" + NL
)
sub(old3, new3, '楼层下切换后重绘缩略条')

# 3. 点击缩略条跳转楼层后重绘（active 高亮）
old4 = (
    "            cvs.addEventListener('click', () => {" + NL +
    "                currentFloorZ = detectedFloors[i];" + NL +
    "                updateFloorLabel();" + NL +
    "                addLog(`🗺️ 跳转楼层 F${i + 1} (z=${currentFloorZ})`, 'info');" + NL +
    "            });" + NL
)
new4 = (
    "            cvs.addEventListener('click', () => {" + NL +
    "                currentFloorZ = detectedFloors[i];" + NL +
    "                updateFloorLabel();" + NL +
    "                drawThumbStrip();" + NL +
    "                addLog(`🗺️ 跳转楼层 F${i + 1} (z=${currentFloorZ})`, 'info');" + NL +
    "            });" + NL
)
sub(old4, new4, '缩略条点击后重绘缩略条')

with io.open(PATH, 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('[DONE] 缩略条优化完成')
