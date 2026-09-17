#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""优化烟雾蔓延模型:解耦水平漂移与浓度梯度,增强浮力和顶棚射流"""

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireSpread.java"
with open(p, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 增大烟雾生成速率: 2.0 -> 8.0
old = 'private static final double SMOKE_SOURCE_RATE      = 2.0;'
new = 'private static final double SMOKE_SOURCE_RATE      = 8.0;  // 优化: 增大产烟量,确保烟雾能蔓延到整个建筑'
if old in content:
    content = content.replace(old, new)
    print('1. SMOKE_SOURCE_RATE: 2.0 -> 8.0')
else:
    print('1. SMOKE_SOURCE_RATE 未找到')

# 2. 垂直漂移添加最低速度保底: vz = _smkCoeff * excessT / T_AMB -> max(excessT/T_AMB, 0.2)
old_vz = 'double vz = _smkCoeff * excessT / T_AMB;'
new_vz = 'double vz = _smkCoeff * Math.max(excessT / T_AMB, 0.2);  // 优化: 烟雾最低上升速度保底,冷却后仍保持浮力'
if old_vz in content:
    content = content.replace(old_vz, new_vz)
    print('2. 垂直漂移 vz: 添加最低速度保底 0.2')
else:
    print('2. 垂直漂移 vz 未找到')

# 3. 水平漂移解耦浓度梯度: 当 gradMagX=0 时,用基础速度+随机方向
# 修改 vx 计算: 原公式 _smkCoeff * 0.5 * gradX / gradMagX * Math.max(excessT/T_AMB, 0.15)
# 改为: 基础水平速度(不依赖梯度) + 梯度驱动速度
old_vx = 'double vx = _smkCoeff * 0.5 * gradX / gradMagX * Math.max(excessT / T_AMB, 0.15);'
new_vx = '''// 优化: 水平漂移解耦浓度梯度 - 基础速度(温度驱动,不依赖梯度) + 梯度驱动速度
            double baseDriftX = _smkCoeff * Math.max(excessT / T_AMB, 0.25) * (fire.random.nextDouble() - 0.5) * 2.0;
            double gradDriftX = (gradMagX > 1e-6) ? _smkCoeff * 0.5 * gradX / gradMagX * Math.max(excessT / T_AMB, 0.15) : 0.0;
            double vx = baseDriftX + gradDriftX;'''
if old_vx in content:
    content = content.replace(old_vx, new_vx)
    print('3. 水平漂移 vx: 解耦浓度梯度,添加基础随机漂移')
else:
    print('3. 水平漂移 vx 未找到')

# 4. vy 同理
old_vy = 'double vy = _smkCoeff * 0.5 * gradY / gradMagY * Math.max(excessT / T_AMB, 0.15);'
new_vy = '''double baseDriftY = _smkCoeff * Math.max(excessT / T_AMB, 0.25) * (fire.random.nextDouble() - 0.5) * 2.0;
            double gradDriftY = (gradMagY > 1e-6) ? _smkCoeff * 0.5 * gradY / gradMagY * Math.max(excessT / T_AMB, 0.15) : 0.0;
            double vy = baseDriftY + gradDriftY;'''
if old_vy in content:
    content = content.replace(old_vy, new_vy)
    print('4. 水平漂移 vy: 解耦浓度梯度,添加基础随机漂移')
else:
    print('4. 水平漂移 vy 未找到')

# 5. 顶棚层扩散增强: 5倍 -> 10倍
old_ceil = 'if (ceilingNearLayer[z]) D_eff *= 5.0;'
new_ceil = 'if (ceilingNearLayer[z]) D_eff *= 10.0;  // 优化: 顶棚层扩散增强10倍,模拟强顶棚射流水平推进'
if old_ceil in content:
    content = content.replace(old_ceil, new_ceil)
    print('5. 顶棚层扩散增强: 5倍 -> 10倍')
else:
    print('5. 顶棚层扩散增强未找到')

# 6. 水平漂移上限: _maxDrift 可能限制了速度,检查 vx/vy 的上限
# 原代码 if (vx > _maxDrift) vx = _maxDrift; 上限0.5,可以保留
# 但基础随机漂移的幅度可能超过上限,需要确保上限合理

with open(p, 'w', encoding='utf-8') as f:
    f.write(content)

print('\n优化完成!')
