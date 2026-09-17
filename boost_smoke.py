#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""大幅优化烟雾蔓延:增大生成量、扩散系数、漂移速度"""

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireSpread.java"
with open(p, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 烟雾生成速率: 8.0 -> 50.0
old = 'private static final double SMOKE_SOURCE_RATE      = 8.0;'
new = 'private static final double SMOKE_SOURCE_RATE      = 50.0;  // 优化: 大幅增大产烟量'
if old in content:
    content = content.replace(old, new)
    print('1. SMOKE_SOURCE_RATE: 8.0 -> 50.0')

# 2. 水平漂移基础速度系数: 0.3 -> 0.8
old = 'double baseDriftX = _smkCoeff * Math.max(excessT / T_AMB, 0.3) * (fire.random.nextDouble() - 0.5) * 2.0;'
new = 'double baseDriftX = _smkCoeff * Math.max(excessT / T_AMB, 0.8) * (fire.random.nextDouble() - 0.5) * 2.0;'
if old in content:
    content = content.replace(old, new)
    print('2. baseDriftX 最低速度: 0.3 -> 0.8')

old = 'double baseDriftY = _smkCoeff * Math.max(excessT / T_AMB, 0.3) * (fire.random.nextDouble() - 0.5) * 2.0;'
new = 'double baseDriftY = _smkCoeff * Math.max(excessT / T_AMB, 0.8) * (fire.random.nextDouble() - 0.5) * 2.0;'
if old in content:
    content = content.replace(old, new)
    print('3. baseDriftY 最低速度: 0.3 -> 0.8')

# 4. 垂直漂移最低速度: 0.2 -> 0.5
old = 'double vz = _smkCoeff * Math.max(excessT / T_AMB, 0.2);'
new = 'double vz = _smkCoeff * Math.max(excessT / T_AMB, 0.5);'
if old in content:
    content = content.replace(old, new)
    print('4. 垂直漂移最低速度: 0.2 -> 0.5')

with open(p, 'w', encoding='utf-8') as f:
    f.write(content)

# FireEscape.java 参数调整
p2 = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
with open(p2, 'r', encoding='utf-8') as f:
    content2 = f.read()

# 5. 基础扩散系数: 0.05 -> 0.15
old = 'public double baseSmokeSpread = 0.05;'
new = 'public double baseSmokeSpread = 0.15;  // 优化: 增大烟雾扩散系数'
if old in content2:
    content2 = content2.replace(old, new)
    print('5. baseSmokeSpread: 0.05 -> 0.15')

# 6. 漂移系数: 0.08 -> 0.15
old = 'public double smokeDriftCoeff = 0.08;'
new = 'public double smokeDriftCoeff = 0.15;  // 优化: 增大烟雾漂移系数'
if old in content2:
    content2 = content2.replace(old, new)
    print('6. smokeDriftCoeff: 0.08 -> 0.15')

# 7. 最大漂移速度: 0.5 -> 1.0
old = 'public double smokeDriftMaxSpeed = 0.5;'
new = 'public double smokeDriftMaxSpeed = 1.0;  // 优化: 增大最大漂移速度'
if old in content2:
    content2 = content2.replace(old, new)
    print('7. smokeDriftMaxSpeed: 0.5 -> 1.0')

with open(p2, 'w', encoding='utf-8') as f:
    f.write(content2)

print('\n优化完成!')
