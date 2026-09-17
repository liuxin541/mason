# -*- coding: utf-8 -*-
"""fireescape.html 前端性能修复补丁（保持 CRLF / UTF-8 无 BOM）"""
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
            print(f'[FAIL] 未找到: {label}')
            sys.exit(1)
        print(f'[SKIP] 未找到(非必需): {label}')
        return
    if cnt > 1:
        print(f'[FAIL] 匹配 {cnt} 处(应唯一): {label}')
        sys.exit(1)
    content = content.replace(old, new)
    print(f'[OK] {label}')

# ---------- 修复1: animate() 平面图节流重绘 ----------
old1 = (
    "        renderer.render(scene, camera);" + NL +
    "" + NL +
    "        // ★ 更新小地图（每帧绘制，与主画面同步）" + NL +
    "        drawMinimap();" + NL +
    "        drawThumbStrip();" + NL +
    "        // 若放大弹窗打开，同步重绘" + NL +
    "        if (window.__isZoomOpen && window.__isZoomOpen()) {" + NL +
    "            window.__drawZoomMap && window.__drawZoomMap();" + NL +
    "        }" + NL +
    "    }" + NL
)
new1 = (
    "        renderer.render(scene, camera);" + NL +
    "" + NL +
    "        // ★ 平面图节流重绘：数据更新后由 fetchState 立即重绘，动画循环内仅降频兜底" + NL +
    "        //   （约 6fps），避免每帧全量 Canvas 重绘（BIM 模式每帧数十万次 fillRect）拖垮主线程" + NL +
    "        const _planNow = performance.now();" + NL +
    "        if (_planNow - lastPlanDrawTime > 150) {" + NL +
    "            lastPlanDrawTime = _planNow;" + NL +
    "            drawMinimap();" + NL +
    "            drawThumbStrip();" + NL +
    "            // 若放大弹窗打开，同步重绘" + NL +
    "            if (window.__isZoomOpen && window.__isZoomOpen()) {" + NL +
    "                window.__drawZoomMap && window.__drawZoomMap();" + NL +
    "            }" + NL +
    "        }" + NL +
    "    }" + NL
)
sub(old1, new1, 'animate 平面图节流重绘')

# ---------- 修复2a: fetchState 粒子源增量更新 + 数据驱动平面图重绘 ----------
old2 = (
    "                update3DWorld(d);" + NL +
    "                // ★ GPU 粒子系统：同步火源物理参数与浓度场 (粒子烟雾已由体积渲染接管)" + NL +
    "                if (fireParticles) fireParticles.updateSources(d.fireSources, dx, dz, d.fire, d.stride || 1);" + NL +
    "                // if (smokeParticles) smokeParticles.updateFromField(d.smoke, d.fire, dx, dz, d.depth, d.stride || 1);" + NL +
    "                updateFireLights(d);" + NL
)
new2 = (
    "                update3DWorld(d);" + NL +
    "                // ★ GPU 粒子系统：同步火源物理参数与浓度场 (粒子烟雾已由体积渲染接管)" + NL +
    "                //   性能：仅当仿真步进或火源布局变化时才全网格扫描重建次级火源" + NL +
    "                if (fireParticles) {" + NL +
    "                    const _fsKey = fireSourcesKey(d.fireSources);" + NL +
    "                    if (d.step !== lastParticleStep || _fsKey !== lastFireSourceKey) {" + NL +
    "                        lastParticleStep = d.step;" + NL +
    "                        lastFireSourceKey = _fsKey;" + NL +
    "                        fireParticles.updateSources(d.fireSources, dx, dz, d.fire, d.stride || 1);" + NL +
    "                    } else {" + NL +
    "                        // 数据未变：仅刷新主火源位置/强度，跳过全网格高温体素扫描" + NL +
    "                        fireParticles.updateSources(d.fireSources, dx, dz, null, d.stride || 1);" + NL +
    "                    }" + NL +
    "                }" + NL +
    "                // if (smokeParticles) smokeParticles.updateFromField(d.smoke, d.fire, dx, dz, d.depth, d.stride || 1);" + NL +
    "                updateFireLights(d);" + NL +
    "                // ★ 平面图：数据到达后立即重绘一次（小地图/缩略条/放大弹窗），保证新数据及时显示" + NL +
    "                drawMinimap();" + NL +
    "                drawThumbStrip();" + NL +
    "                if (window.__isZoomOpen && window.__isZoomOpen()) {" + NL +
    "                    window.__drawZoomMap && window.__drawZoomMap();" + NL +
    "                }" + NL
)
sub(old2, new2, 'fetchState 增量粒子源 + 数据驱动平面图重绘')

with io.open(PATH, 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('[DONE] 补丁写入完成')
