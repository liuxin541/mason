#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""平衡烟雾参数:降低生成量避免浓度过高,确保第一层蔓延"""

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireSpread.java"
with open(p, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 烟雾生成速率: 50.0 -> 20.0 (降低避免浓度过高)
old = 'private static final double SMOKE_SOURCE_RATE      = 50.0;'
new = 'private static final double SMOKE_SOURCE_RATE      = 20.0;  // 优化: 平衡产烟量,避免浓度过高'
if old in content:
    content = content.replace(old, new)
    print('1. SMOKE_SOURCE_RATE: 50.0 -> 20.0')

# 2. 水平漂移基础速度: 0.8 -> 0.5 (降低避免过快)
old = 'double baseDriftX = _smkCoeff * Math.max(excessT / T_AMB, 0.8) * (fire.random.nextDouble() - 0.5) * 2.0;'
new = 'double baseDriftX = _smkCoeff * Math.max(excessT / T_AMB, 0.5) * (fire.random.nextDouble() - 0.5) * 2.0;'
if old in content:
    content = content.replace(old, new)
    print('2. baseDriftX: 0.8 -> 0.5')

old = 'double baseDriftY = _smkCoeff * Math.max(excessT / T_AMB, 0.8) * (fire.random.nextDouble() - 0.5) * 2.0;'
new = 'double baseDriftY = _smkCoeff * Math.max(excessT / T_AMB, 0.5) * (fire.random.nextDouble() - 0.5) * 2.0;'
if old in content:
    content = content.replace(old, new)
    print('3. baseDriftY: 0.8 -> 0.5')

with open(p, 'w', encoding='utf-8') as f:
    f.write(content)

# FireEscape.java 参数调整
p2 = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
with open(p2, 'r', encoding='utf-8') as f:
    content2 = f.read()

# 4. 基础扩散系数: 0.15 -> 0.1 (降低)
old = 'public double baseSmokeSpread = 0.15;'
new = 'public double baseSmokeSpread = 0.1;  // 优化: 平衡扩散系数'
if old in content2:
    content2 = content2.replace(old, new)
    print('4. baseSmokeSpread: 0.15 -> 0.1')

# 5. 漂移系数: 0.15 -> 0.1 (降低)
old = 'public double smokeDriftCoeff = 0.15;'
new = 'public double smokeDriftCoeff = 0.1;  // 优化: 平衡漂移系数'
if old in content2:
    content2 = content2.replace(old, new)
    print('5. smokeDriftCoeff: 0.15 -> 0.1')

# 6. 最大漂移速度: 1.0 -> 0.7 (降低)
old = 'public double smokeDriftMaxSpeed = 1.0;'
new = 'public double smokeDriftMaxSpeed = 0.7;  // 优化: 平衡最大漂移速度'
if old in content2:
    content2 = content2.replace(old, new)
    print('6. smokeDriftMaxSpeed: 1.0 -> 0.7')

with open(p2, 'w', encoding='utf-8') as f:
    f.write(content2)

print('\n参数平衡完成!')
