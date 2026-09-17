# -*- coding: utf-8 -*-
"""GPU 深度降载：体积烟雾步数/噪声、像素比、阴影、帧率自适应、低帧率冻结闪烁"""
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

# 1. 关闭阴影（透明半透明建筑上阴影视觉意义小，shadow pass 每帧额外渲染整场景）
sub(
    "        renderer.shadowMap.enabled = true;" + NL,
    "        renderer.shadowMap.enabled = false;  // ★ 性能：关闭阴影，避免每帧额外 shadow pass" + NL,
    '关闭 shadowMap'
)
sub(
    "        dirLight.castShadow = true;" + NL,
    "        dirLight.castShadow = false;  // ★ 性能：关闭方向光阴影" + NL,
    '关闭方向光阴影'
)

# 2. 像素比上限 2 → 1.5（4K/2K 屏渲染像素 -44%）
sub(
    "        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));" + NL,
    "        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));  // ★ 性能：像素比上限 1.5" + NL,
    '像素比上限 1.5'
)

# 3. 体积烟雾默认参数：224/0.19 → 96/0.26（步数 -57%，覆盖 25m 保持建筑内完整）
sub(
    "                        // ★ 性能：步长 0.15→0.19、步数 320→224（覆盖 42.6m，原 48m），" + NL +
    "                        //   GPU 光线步进计算量约 -30%，视觉差异极小" + NL +
    "                        uStepSize: { value: 0.19 }," + NL +
    "                        uMaxSteps: { value: 224 }," + NL,
    "                        // ★ 性能：默认 96 步/0.26m（覆盖 25m，建筑内部完整），" + NL +
    "                        //   低帧率时由 applyQuality 自动再降级（48 步/0.4m）" + NL +
    "                        uStepSize: { value: 0.26 }," + NL +
    "                        uMaxSteps: { value: 96 }," + NL,
    '体积烟雾默认参数降载'
)

# 4. FBM 噪声 4 层 → 3 层（每步噪声成本 -25%，高频细节略降）
sub(
    "        float fbm(vec3 p) {" + NL +
    "            float v = 0.0;" + NL +
    "            float a = 0.5;" + NL +
    "            for (int i = 0; i < 4; i++) {" + NL,
    "        float fbm(vec3 p) {" + NL +
    "            float v = 0.0;" + NL +
    "            float a = 0.5;" + NL +
    "            // ★ 性能：3 层噪声（原 4 层），每步 ALU 成本 -25%" + NL +
    "            for (int i = 0; i < 3; i++) {" + NL,
    'FBM 噪声降为 3 层'
)

# 5. VolumeSmoke 增加帧率自适应方法（在 updateFrame 之后插入）
old5 = (
    "        updateFrame(camera, time) {" + NL +
    "            if (!this.material) return;" + NL +
    "            this.material.uniforms.uCameraPos.value.copy(camera.position);" + NL +
    "            this.material.uniforms.uTime.value = time;" + NL +
    "        }" + NL
)
new5 = (
    "        updateFrame(camera, time) {" + NL +
    "            if (!this.material) return;" + NL +
    "            this.material.uniforms.uCameraPos.value.copy(camera.position);" + NL +
    "            this.material.uniforms.uTime.value = time;" + NL +
    "        }" + NL +
    "" + NL +
    "        // ★ 帧率自适应降级：操作卡顿时自动减少光线步进（GPU 负载随步数线性下降）" + NL +
    "        applyQuality(fps) {" + NL +
    "            if (!this.material || fps < 0) return;" + NL +
    "            const u = this.material.uniforms;" + NL +
    "            if (fps < 18)      { u.uMaxSteps.value = 48;  u.uStepSize.value = 0.4; }" + NL +
    "            else if (fps < 30) { u.uMaxSteps.value = 72;  u.uStepSize.value = 0.3; }" + NL +
    "            else               { u.uMaxSteps.value = 96;  u.uStepSize.value = 0.26; }" + NL +
    "        }" + NL
)
sub(old5, new5, 'VolumeSmoke.applyQuality 帧率自适应')

# 6. animate 帧率测量（放在 requestAnimationFrame 之后）
old6 = (
    "    function animate() {" + NL +
    "        requestAnimationFrame(animate);" + NL +
    "" + NL +
    "        // ★ GPU 粒子系统更新（火焰/烟雾）" + NL +
    "        const dt = clock.getDelta();" + NL +
    "        const ptime = clock.getElapsedTime();" + NL
)
new6 = (
    "    function animate() {" + NL +
    "        requestAnimationFrame(animate);" + NL +
    "" + NL +
    "        // ★ 帧率测量与体积烟雾质量自适应" + NL +
    "        fpsFrames++;" + NL +
    "        const _fpsTime = clock.getElapsedTime();" + NL +
    "        if (_fpsTime - fpsWindowStart >= 1.0) {" + NL +
    "            currentFps = fpsFrames / (_fpsTime - fpsWindowStart);" + NL +
    "            fpsFrames = 0;" + NL +
    "            fpsWindowStart = _fpsTime;" + NL +
    "            if (volumeSmoke) volumeSmoke.applyQuality(currentFps);" + NL +
    "        }" + NL +
    "" + NL +
    "        // ★ GPU 粒子系统更新（火焰/烟雾）" + NL +
    "        const dt = clock.getDelta();" + NL +
    "        const ptime = clock.getElapsedTime();" + NL
)
sub(old6, new6, 'animate 帧率测量')

# 7. 帧率状态变量声明（放在 clock 声明之后）
old7 = (
    "    const clock = new THREE.Clock();" + NL
)
new7 = (
    "    const clock = new THREE.Clock();" + NL +
    "    // ★ 帧率检测状态（供体积烟雾质量自适应与闪烁冻结）" + NL +
    "    let fpsFrames = 0, fpsWindowStart = 0, currentFps = 60;" + NL
)
sub(old7, new7, '帧率状态变量')

# 8. 低帧率冻结火焰光/光晕逐帧闪烁（减少 uniform 更新与光照重算）
old8 = (
    "        // ★ 动态火光: 火焰光源逐帧闪烁脉动, 照亮周围墙壁/地面 (真实火焰光照)" + NL +
    "        for (let i = 0; i < fireLights.length; i++) {" + NL +
    "            const l = fireLights[i];" + NL +
    "            if (l.baseIntensity === undefined) continue;" + NL +
    "            const flick = 0.78 + 0.22 * Math.sin(ptime * 12.7 + i * 1.7)" + NL +
    "                                * Math.sin(ptime * 8.3 + i * 2.3)" + NL +
    "                                + 0.08 * Math.sin(ptime * 31.0 + i * 5.1);" + NL +
    "            l.intensity = Math.max(0.1, l.baseIntensity * flick);" + NL +
    "        }" + NL +
    "        // ★ 火焰光晕逐帧脉动: 让光晕随火焰一起呼吸闪烁" + NL +
    "        for (let i = 0; i < fireGlows.length; i++) {" + NL +
    "            const sp = fireGlows[i];" + NL +
    "            const gf = 0.72 + 0.28 * Math.sin(ptime * 9.5 + i * 2.1)" + NL +
    "                             * Math.sin(ptime * 6.1 + i * 4.3);" + NL +
    "            sp.material.opacity = Math.max(0.28, Math.min(1.0, 0.9 * gf));" + NL +
    "        }" + NL
)
new8 = (
    "        // ★ 动态火光: 火焰光源逐帧闪烁脉动, 照亮周围墙壁/地面 (真实火焰光照)" + NL +
    "        //   性能：低帧率(<18fps)时冻结闪烁，减少光照 uniform 更新" + NL +
    "        if (currentFps >= 18) {" + NL +
    "        for (let i = 0; i < fireLights.length; i++) {" + NL +
    "            const l = fireLights[i];" + NL +
    "            if (l.baseIntensity === undefined) continue;" + NL +
    "            const flick = 0.78 + 0.22 * Math.sin(ptime * 12.7 + i * 1.7)" + NL +
    "                                * Math.sin(ptime * 8.3 + i * 2.3)" + NL +
    "                                + 0.08 * Math.sin(ptime * 31.0 + i * 5.1);" + NL +
    "            l.intensity = Math.max(0.1, l.baseIntensity * flick);" + NL +
    "        }" + NL +
    "        // ★ 火焰光晕逐帧脉动: 让光晕随火焰一起呼吸闪烁" + NL +
    "        for (let i = 0; i < fireGlows.length; i++) {" + NL +
    "            const sp = fireGlows[i];" + NL +
    "            const gf = 0.72 + 0.28 * Math.sin(ptime * 9.5 + i * 2.1)" + NL +
    "                             * Math.sin(ptime * 6.1 + i * 4.3);" + NL +
    "            sp.material.opacity = Math.max(0.28, Math.min(1.0, 0.9 * gf));" + NL +
    "        }" + NL +
    "        }" + NL
)
sub(old8, new8, '低帧率冻结闪烁')

# 9. 平面图节流 150 → 300ms（BIM 模式单次重绘耗时高）
sub(
    "        if (_planNow - lastPlanDrawTime > 150) {" + NL,
    "        if (_planNow - lastPlanDrawTime > 300) {" + NL,
    '平面图节流 300ms'
)

with io.open(PATH, 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('[DONE] GPU 深度降载补丁完成')
