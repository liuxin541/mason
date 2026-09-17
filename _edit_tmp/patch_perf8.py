# -*- coding: utf-8 -*-
"""再降一档：像素比 1.25、烟雾 72 步、更激进的自适应、粒子减半"""
import io, sys

PATH = r'E:\xuexi\MASON\mason\fireescape.html'
with io.open(PATH, 'r', encoding='utf-8') as f:
    content = f.read()
NL = '\r\n' if content.count('\r\n') > content.count('\n') - content.count('\r\n') else '\n'

def sub(old, new, label):
    global content
    cnt = content.count(old)
    if cnt != 1:
        print(f'[FAIL] {label}: 匹配 {cnt} 处，应唯一'); sys.exit(1)
    content = content.replace(old, new)
    print(f'[OK] {label}')

# 1. 像素比 1.5 → 1.25
sub(
    "        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));  // ★ 性能：像素比上限 1.5" + NL,
    "        renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.25));  // ★ 性能：像素比上限 1.25" + NL,
    '像素比 1.5 → 1.25'
)

# 2. 体积烟雾默认 96/0.26 → 72/0.3
sub(
    "                        // ★ 性能：默认 96 步/0.26m（覆盖 25m，建筑内部完整），" + NL +
    "                        //   低帧率时由 applyQuality 自动再降级（48 步/0.4m）" + NL +
    "                        uStepSize: { value: 0.26 }," + NL +
    "                        uMaxSteps: { value: 96 }," + NL,
    "                        // ★ 性能：默认 72 步/0.3m（覆盖 21.6m），低帧率时 applyQuality 自动再降级" + NL +
    "                        uStepSize: { value: 0.3 }," + NL +
    "                        uMaxSteps: { value: 72 }," + NL,
    '体积烟雾默认 72 步/0.3m'
)

# 3. applyQuality 三档更激进
sub(
    "            if (fps < 18)      { u.uMaxSteps.value = 48;  u.uStepSize.value = 0.4; }" + NL +
    "            else if (fps < 30) { u.uMaxSteps.value = 72;  u.uStepSize.value = 0.3; }" + NL +
    "            else               { u.uMaxSteps.value = 96;  u.uStepSize.value = 0.26; }" + NL,
    "            if (fps < 18)      { u.uMaxSteps.value = 32;  u.uStepSize.value = 0.5; }" + NL +
    "            else if (fps < 30) { u.uMaxSteps.value = 48;  u.uStepSize.value = 0.4; }" + NL +
    "            else               { u.uMaxSteps.value = 72;  u.uStepSize.value = 0.3; }" + NL,
    'applyQuality 三档更激进'
)

# 4. 火粒子 3000 → 2000
sub(
    "        fireParticles = new FireParticleSystem(scene, 3000);" + NL,
    "        fireParticles = new FireParticleSystem(scene, 2000);  // ★ 性能：粒子上限 3000→2000" + NL,
    '火粒子上限 2000'
)

with io.open(PATH, 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('[DONE] 再降一档完成')
