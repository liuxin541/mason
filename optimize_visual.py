#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""前端视觉+性能综合优化:
1. 烟雾颜色/光学贴合真实火灾烟雾(灰棕色调, 前向散射增强)
2. 质量档位系统: 像素比+光线步数+火焰粒子联动自适应
3. 轮询频率自适应(低帧率降频)
4. 墙体材质暖化, 更贴近真实建筑
"""

p = r"E:\xuexi\MASON\mason\fireescape.html"
with open(p, 'r', encoding='utf-8') as f:
    content = f.read()

count = 0

# ============ 1. 烟雾颜色/光学真实化 ============
# 1a. 烟雾基色: 纯灰 -> 暖灰棕(火灾烟雾=不完全燃烧碳颗粒)
old = '''                // === 深灰色体积烟雾 (低密度浅灰, 高密度深黑, 参考FDS Smokeview) ===
                // 颜色渐变: 极淡(0.45) → 淡灰(0.25) → 深灰(0.10) → 近黑(0.03)
                vec3 smokeColor = mix(vec3(0.42, 0.41, 0.43), vec3(0.03, 0.03, 0.04), smoothstep(0.0, 0.6, density));'''
new = '''                // === 真实火灾烟雾颜色 (暖灰棕: 不完全燃烧碳颗粒, 参考FDS Smokeview) ===
                // 低密度: 暖灰白(被环境光照亮) → 高密度: 深棕黑(遮挡光线)
                vec3 smokeColor = mix(vec3(0.56, 0.53, 0.49), vec3(0.10, 0.085, 0.07), smoothstep(0.0, 0.55, density));
                // 中密度带一点棕褐色调, 模拟未燃尽碳烟
                smokeColor = mix(smokeColor, vec3(0.30, 0.24, 0.19), smoothstep(0.25, 0.75, density) * 0.35);'''
if old in content:
    content = content.replace(old, new)
    count += 1
    print('1a. 烟雾基色 -> 暖灰棕')
else:
    print('1a. 未找到烟雾基色代码')

# 1b. HG散射: 增强前向散射(阳光/火光穿透烟雾时更亮)
old = 'float phase = hgPhase(cosT, 0.55);'
new = 'float phase = hgPhase(cosT, 0.62);  // 前向散射增强: 光从光源方向穿过时烟雾更亮'
if old in content:
    content = content.replace(old, new)
    count += 1
    print('1b. HG散射 g: 0.55 -> 0.62')
else:
    print('1b. 未找到HG散射代码')

# 1c. 散射/吸收平衡: 吸收略降, 散射略增 -> 烟雾更通透有体积感
old = 'uAbsorption: { value: 22.0 },'
new = 'uAbsorption: { value: 16.0 },  // 吸收略降: 烟雾更通透'
if old in content:
    content = content.replace(old, new)
    count += 1
    print('1c. uAbsorption: 22 -> 16')
else:
    print('1c. 未找到uAbsorption')

old = 'uScattering: { value: 4.5 },'
new = 'uScattering: { value: 6.5 },  // 散射增强: 体积光感更明显'
if old in content:
    content = content.replace(old, new)
    count += 1
    print('1d. uScattering: 4.5 -> 6.5')
else:
    print('1d. 未找到uScattering')

# ============ 2. 质量档位系统 ============
# 2a. 体积烟雾质量档位: 增加低档24步, 细分各档
old = '''        applyQuality(fps) {
            if (!this.material || fps < 0) return;
            const u = this.material.uniforms;
            if (fps < 18)      { u.uMaxSteps.value = 32;  u.uStepSize.value = 0.5; }
            else if (fps < 30) { u.uMaxSteps.value = 48;  u.uStepSize.value = 0.4; }
            else               { u.uMaxSteps.value = 72;  u.uStepSize.value = 0.3; }
        }'''
new = '''        applyQuality(fps) {
            if (!this.material || fps < 0) return;
            const u = this.material.uniforms;
            if (fps < 12)      { u.uMaxSteps.value = 24;  u.uStepSize.value = 0.65; u.uDensityThreshold.value = 0.01; }
            else if (fps < 18) { u.uMaxSteps.value = 32;  u.uStepSize.value = 0.5;  u.uDensityThreshold.value = 0.008; }
            else if (fps < 30) { u.uMaxSteps.value = 48;  u.uStepSize.value = 0.4;  u.uDensityThreshold.value = 0.005; }
            else               { u.uMaxSteps.value = 72;  u.uStepSize.value = 0.3;  u.uDensityThreshold.value = 0.003; }
        }'''
if old in content:
    content = content.replace(old, new)
    count += 1
    print('2a. 烟雾质量档位: 增加24步低档, 密度阈值联动')
else:
    print('2a. 未找到applyQuality')

# 2b. 动态像素比: 在帧率检测处联动
old = '''            currentFps = fpsFrames / (_fpsTime - fpsWindowStart);
            fpsFrames = 0;
            fpsWindowStart = _fpsTime;
            if (volumeSmoke) volumeSmoke.applyQuality(currentFps);
        }'''
new = '''            currentFps = fpsFrames / (_fpsTime - fpsWindowStart);
            fpsFrames = 0;
            fpsWindowStart = _fpsTime;
            if (volumeSmoke) volumeSmoke.applyQuality(currentFps);
            // ★ 动态像素比: 低帧率降分辨率提帧率, 高帧率恢复清晰度
            const targetPR = currentFps < 12 ? 0.75 : currentFps < 20 ? 1.0 : 1.25;
            const _curPR = renderer.getPixelRatio();
            if (Math.abs(_curPR - targetPR) > 0.01) {
                renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, targetPR));
                renderer.setSize(canvas.clientWidth, canvas.clientHeight);
            }
            // ★ 火焰粒子自适应: 低帧率减少发射
            if (fireParticles) {
                fireParticles.emissionScale = currentFps < 12 ? 0.4 : currentFps < 20 ? 0.7 : 1.0;
            }
        }'''
if old in content:
    content = content.replace(old, new)
    count += 1
    print('2b. 动态像素比+火焰粒子自适应')
else:
    print('2b. 未找到帧率检测代码')

# ============ 3. 轮询频率自适应 ============
# 3a. 体积烟雾轮询: 低帧率降频(500ms -> 800ms), 减少纹理上传压力
old = '''    // ★ 递归 setTimeout, 上一个请求完成后再等500ms发起下一个
    function pollSmokeVolume() {
        fetchSmokeVolume().finally(() => {
            setTimeout(pollSmokeVolume, 500);
        });
    }'''
new = '''    // ★ 递归 setTimeout, 上一个请求完成后再等下一轮(帧率低时降频, 减少纹理上传压力)
    function pollSmokeVolume() {
        fetchSmokeVolume().finally(() => {
            const interval = (typeof currentFps === 'number' && currentFps > 0 && currentFps < 20) ? 1000 : 500;
            setTimeout(pollSmokeVolume, interval);
        });
    }'''
if old in content:
    content = content.replace(old, new)
    count += 1
    print('3a. 烟雾轮询频率自适应')
else:
    print('3a. 未找到pollSmokeVolume')

# ============ 4. 墙体材质暖化 ============
old = 'color = new THREE.Color(0.55, 0.55, 0.60);'
new = 'color = new THREE.Color(0.72, 0.70, 0.67);  // 暖米色墙体, 贴近真实建筑'
if old in content:
    content = content.replace(old, new)
    count += 1
    print('4a. 墙体颜色 -> 暖米色')
else:
    print('4a. 未找到墙体颜色')

with open(p, 'w', encoding='utf-8') as f:
    f.write(content)

print(f'\n完成! 共 {count} 处修改')
