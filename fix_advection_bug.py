#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修复水平输运的条件包裹bug:把输运执行移到if条件外"""

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireSpread.java"
with open(p, 'r', encoding='utf-8') as f:
    content = f.read()

# 修复 vx: 把 if (gradMagX > GRAD_EPS) 的闭合 } 移到 gradDriftX 之后
old_vx = '''                    double vx = baseDriftX;
                    if (gradMagX > GRAD_EPS) {
                        // 梯度驱动的附加漂移(沿温度梯度方向)
                        double gradDriftX = _smkCoeff * 0.5 * gradX / gradMagX * Math.max(excessT / T_AMB, 0.15);
                        vx += gradDriftX;
                        if (vx > _maxDrift) vx = _maxDrift;
                        if (vx < -_maxDrift) vx = -_maxDrift;
                        double abs_vx = Math.abs(vx);
                        if (abs_vx > VEL_EPS) {
                            double frac = Math.min(1.0, abs_vx * DT_FINAL / dx);
                            if (vx > 0) {
                                // 往 +x 方向输运:从 x 搬到 x+1
                                double deltaC_x = C_afterDiff[x][y][z] * frac;
                                double deltaT_x = T_afterAdvection[x][y][z] * frac;
                                C_afterAdvection[x][y][z] -= deltaC_x;
                                C_afterAdvection[x+1][y][z] += deltaC_x;
                                T_afterAdvection[x][y][z] -= deltaT_x;
                                T_afterAdvection[x+1][y][z] += deltaT_x;
                            } else {
                                double deltaC_x = C_afterDiff[x][y][z] * frac;
                                double deltaT_x = T_afterAdvection[x][y][z] * frac;
                                C_afterAdvection[x][y][z] -= deltaC_x;
                                C_afterAdvection[x-1][y][z] += deltaC_x;
                                T_afterAdvection[x][y][z] -= deltaT_x;
                                T_afterAdvection[x-1][y][z] += deltaT_x;
                            }
                        }
                    }'''

new_vx = '''                    double vx = baseDriftX;
                    if (gradMagX > GRAD_EPS) {
                        // 梯度驱动的附加漂移(沿温度梯度方向)
                        double gradDriftX = _smkCoeff * 0.5 * gradX / gradMagX * Math.max(excessT / T_AMB, 0.15);
                        vx += gradDriftX;
                    }
                    // 优化: 水平输运执行移到条件外,基础漂移始终生效
                    if (vx > _maxDrift) vx = _maxDrift;
                    if (vx < -_maxDrift) vx = -_maxDrift;
                    double abs_vx = Math.abs(vx);
                    if (abs_vx > VEL_EPS) {
                        double frac = Math.min(1.0, abs_vx * DT_FINAL / dx);
                        if (vx > 0) {
                            double deltaC_x = C_afterDiff[x][y][z] * frac;
                            double deltaT_x = T_afterAdvection[x][y][z] * frac;
                            C_afterAdvection[x][y][z] -= deltaC_x;
                            C_afterAdvection[x+1][y][z] += deltaC_x;
                            T_afterAdvection[x][y][z] -= deltaT_x;
                            T_afterAdvection[x+1][y][z] += deltaT_x;
                        } else {
                            double deltaC_x = C_afterDiff[x][y][z] * frac;
                            double deltaT_x = T_afterAdvection[x][y][z] * frac;
                            C_afterAdvection[x][y][z] -= deltaC_x;
                            C_afterAdvection[x-1][y][z] += deltaC_x;
                            T_afterAdvection[x][y][z] -= deltaT_x;
                            T_afterAdvection[x-1][y][z] += deltaT_x;
                        }
                    }'''

if old_vx in content:
    content = content.replace(old_vx, new_vx)
    print('修复 vx: 输运执行移到if条件外')
else:
    print('vx 旧代码未找到')

# 修复 vy: 同样的问题
old_vy = '''                    double vy = baseDriftY;
                    if (gradMagY > GRAD_EPS) {
                        // 梯度驱动的附加漂移(沿温度梯度方向)
                        double gradDriftY = _smkCoeff * 0.5 * gradY / gradMagY * Math.max(excessT / T_AMB, 0.15);
                        vy += gradDriftY;
                        if (vy > _maxDrift) vy = _maxDrift;
                        if (vy < -_maxDrift) vy = -_maxDrift;
                        double abs_vy = Math.abs(vy);
                        if (abs_vy > VEL_EPS) {
                            double frac = Math.min(1.0, abs_vy * DT_FINAL / dx);
                            if (vy > 0) {
                                double deltaC_y = C_afterDiff[x][y][z] * frac;
                                double deltaT_y = T_afterAdvection[x][y][z] * frac;
                                C_afterAdvection[x][y][z] -= deltaC_y;
                                C_afterAdvection[x][y+1][z] += deltaC_y;
                                T_afterAdvection[x][y][z] -= deltaT_y;
                                T_afterAdvection[x][y+1][z] += deltaT_y;
                            } else {
                                double deltaC_y = C_afterDiff[x][y][z] * frac;
                                double deltaT_y = T_afterAdvection[x][y][z] * frac;
                                C_afterAdvection[x][y][z] -= deltaC_y;
                                C_afterAdvection[x][y-1][z] += deltaC_y;
                                T_afterAdvection[x][y][z] -= deltaT_y;
                                T_afterAdvection[x][y-1][z] += deltaT_y;
                            }
                        }
                    }'''

new_vy = '''                    double vy = baseDriftY;
                    if (gradMagY > GRAD_EPS) {
                        // 梯度驱动的附加漂移(沿温度梯度方向)
                        double gradDriftY = _smkCoeff * 0.5 * gradY / gradMagY * Math.max(excessT / T_AMB, 0.15);
                        vy += gradDriftY;
                    }
                    // 优化: 水平输运执行移到条件外,基础漂移始终生效
                    if (vy > _maxDrift) vy = _maxDrift;
                    if (vy < -_maxDrift) vy = -_maxDrift;
                    double abs_vy = Math.abs(vy);
                    if (abs_vy > VEL_EPS) {
                        double frac = Math.min(1.0, abs_vy * DT_FINAL / dx);
                        if (vy > 0) {
                            double deltaC_y = C_afterDiff[x][y][z] * frac;
                            double deltaT_y = T_afterAdvection[x][y][z] * frac;
                            C_afterAdvection[x][y][z] -= deltaC_y;
                            C_afterAdvection[x][y+1][z] += deltaC_y;
                            T_afterAdvection[x][y][z] -= deltaT_y;
                            T_afterAdvection[x][y+1][z] += deltaT_y;
                        } else {
                            double deltaC_y = C_afterDiff[x][y][z] * frac;
                            double deltaT_y = T_afterAdvection[x][y][z] * frac;
                            C_afterAdvection[x][y][z] -= deltaC_y;
                            C_afterAdvection[x][y-1][z] += deltaC_y;
                            T_afterAdvection[x][y][z] -= deltaT_y;
                            T_afterAdvection[x][y-1][z] += deltaT_y;
                        }
                    }'''

if old_vy in content:
    content = content.replace(old_vy, new_vy)
    print('修复 vy: 输运执行移到if条件外')
else:
    print('vy 旧代码未找到')

with open(p, 'w', encoding='utf-8') as f:
    f.write(content)

print('\n修复完成!')
