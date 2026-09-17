#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复水平漂移的条件包裹bug:基础漂移移到if条件外"""

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireSpread.java"
with open(p, 'r', encoding='utf-8') as f:
    content = f.read()

# 修复 vx: 把基础漂移移到 if (gradMagX > GRAD_EPS) 之前
old_vx = '''                    double gradX = (nextT[x+1][y][z] - nextT[x-1][y][z]) / twoDx;
                    double gradMagX = Math.abs(gradX);
                    if (gradMagX > GRAD_EPS) {
                        // 优化: 水平漂移解耦浓度梯度 - 基础速度(温度驱动,不依赖梯度) + 梯度驱动速度
            double baseDriftX = _smkCoeff * Math.max(excessT / T_AMB, 0.25) * (fire.random.nextDouble() - 0.5) * 2.0;
            double gradDriftX = (gradMagX > 1e-6) ? _smkCoeff * 0.5 * gradX / gradMagX * Math.max(excessT / T_AMB, 0.15) : 0.0;
            double vx = baseDriftX + gradDriftX;'''

new_vx = '''                    double gradX = (nextT[x+1][y][z] - nextT[x-1][y][z]) / twoDx;
                    double gradMagX = Math.abs(gradX);
                    // 优化: 基础水平漂移(温度驱动,不依赖浓度梯度,始终执行)
                    double baseDriftX = _smkCoeff * Math.max(excessT / T_AMB, 0.3) * (fire.random.nextDouble() - 0.5) * 2.0;
                    double vx = baseDriftX;
                    if (gradMagX > GRAD_EPS) {
                        // 梯度驱动的附加漂移(沿温度梯度方向)
                        double gradDriftX = _smkCoeff * 0.5 * gradX / gradMagX * Math.max(excessT / T_AMB, 0.15);
                        vx += gradDriftX;'''

if old_vx in content:
    content = content.replace(old_vx, new_vx)
    print('修复 vx: 基础漂移移到if条件外')
else:
    print('vx 旧代码未找到,尝试其他模式...')

# 修复 vy: 同样的问题
old_vy = '''                    double gradY = (nextT[x][y+1][z] - nextT[x][y-1][z]) / twoDx;
                    double gradMagY = Math.abs(gradY);
                    if (gradMagY > GRAD_EPS) {
                        double baseDriftY = _smkCoeff * Math.max(excessT / T_AMB, 0.25) * (fire.random.nextDouble() - 0.5) * 2.0;
            double gradDriftY = (gradMagY > 1e-6) ? _smkCoeff * 0.5 * gradY / gradMagY * Math.max(excessT / T_AMB, 0.15) : 0.0;
            double vy = baseDriftY + gradDriftY;'''

new_vy = '''                    double gradY = (nextT[x][y+1][z] - nextT[x][y-1][z]) / twoDx;
                    double gradMagY = Math.abs(gradY);
                    // 优化: 基础水平漂移(温度驱动,不依赖浓度梯度,始终执行)
                    double baseDriftY = _smkCoeff * Math.max(excessT / T_AMB, 0.3) * (fire.random.nextDouble() - 0.5) * 2.0;
                    double vy = baseDriftY;
                    if (gradMagY > GRAD_EPS) {
                        // 梯度驱动的附加漂移(沿温度梯度方向)
                        double gradDriftY = _smkCoeff * 0.5 * gradY / gradMagY * Math.max(excessT / T_AMB, 0.15);
                        vy += gradDriftY;'''

if old_vy in content:
    content = content.replace(old_vy, new_vy)
    print('修复 vy: 基础漂移移到if条件外')
else:
    print('vy 旧代码未找到')

with open(p, 'w', encoding='utf-8') as f:
    f.write(content)

print('\n修复完成!')
