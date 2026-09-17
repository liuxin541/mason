#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""精确修改 FireSpread.java: 添加顶棚检测调用 + 修改顶棚射流触发条件"""
import re

PATH = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireSpread.java"

with open(PATH, "r", encoding="utf-8") as f:
    src = f.read()

# === 修改1: 在 nSources 后添加顶棚检测调用 ===
old1 = "        final int nSources = fireSources.size();\n\n        final double T_AMB = FireEscape.T_AMBIENT;"
new1 = """        final int nSources = fireSources.size();

        // ===== 修复: 动态检测每层实际顶棚(只在尺寸变化时重算) =====
        if (cachedCeilW != w || cachedCeilH != h || cachedCeilD != d) {
            ceilingZs = detectCeilings(w, h, d, obstacle, inside);
            rebuildCeilingNearLayer(d, ceilingZs, 3);
            cachedCeilW = w; cachedCeilH = h; cachedCeilD = d;
            StringBuilder sb = new StringBuilder("[Smoke] 检测到顶棚层 z=");
            for (int c : ceilingZs) sb.append(c).append(" ");
            System.out.println(sb.toString());
        }

        final double T_AMB = FireEscape.T_AMBIENT;"""

if old1 in src:
    src = src.replace(old1, new1, 1)
    print("修改1成功: 添加顶棚检测调用")
else:
    print("修改1失败: 未找到匹配")
    # 调试: 找附近内容
    idx = src.find("nSources = fireSources.size()")
    if idx >= 0:
        print("附近内容:", repr(src[idx:idx+120]))

# === 修改2: 顶棚射流触发条件 - 用 ceilingNearLayer[z] 替代 z >= CJ_Z_MIN ===
# 先找 CJ_Z_MIN 定义和使用
old2 = "        final int CJ_Z_MIN = ceilingZ - 2;"
new2 = "        final int CJ_Z_MIN = 0;  // 修复: 不再用网格顶部, 改用 ceilingNearLayer 动态判断"
if old2 in src:
    src = src.replace(old2, new2, 1)
    print("修改2a成功: 修改 CJ_Z_MIN")
else:
    print("修改2a失败")

# 修改顶棚射流触发条件
old3 = "                    if (z >= CJ_Z_MIN && excessT > CJ_EXCESS_T) {"
new3 = "                    if (ceilingNearLayer[z] && excessT > CJ_EXCESS_T) {"
if old3 in src:
    src = src.replace(old3, new3, 1)
    print("修改2b成功: 修改顶棚射流触发条件")
else:
    print("修改2b失败")
    idx = src.find("CJ_Z_MIN && excessT")
    if idx >= 0:
        print("附近内容:", repr(src[idx-30:idx+60]))

# === 修改3: 烟气扩散 - 顶棚层增强水平扩散 ===
old4 = "                        double D_eff = _D_smoke_base * stratFactor;"
new4 = """                        double D_eff = _D_smoke_base * stratFactor;
                        // 修复: 顶棚层水平扩散增强5倍, 模拟顶棚射流的水平推进(烟雾能蔓延到整个建筑)
                        if (ceilingNearLayer[z]) D_eff *= 5.0;"""
if old4 in src:
    src = src.replace(old4, new4, 1)
    print("修改3成功: 顶棚层增强扩散")
else:
    print("修改3失败")

# === 修改4: 水平漂移 - 降低对温度的依赖, 增加浓度梯度驱动 ===
# 当前: vx = _smkCoeff * 0.5 * gradX/gradMagX * (excessT/T_AMB)
# 问题: excessT 冷却后趋近0, 烟雾停止水平移动
# 修复: 用 (excessT/T_AMB + 浓度梯度因子) 替代, 让高浓度烟雾即使冷却也能扩散
old5 = "                        double vx = _smkCoeff * 0.5 * gradX / gradMagX * (excessT / T_AMB);"
new5 = "                        double vx = _smkCoeff * 0.5 * gradX / gradMagX * Math.max(excessT / T_AMB, 0.15);"
if old5 in src:
    src = src.replace(old5, new5, 1)
    print("修改4a成功: vx 最低速度保底")
else:
    print("修改4a失败")

old6 = "                        double vy = _smkCoeff * 0.5 * gradY / gradMagY * (excessT / T_AMB);"
new6 = "                        double vy = _smkCoeff * 0.5 * gradY / gradMagY * Math.max(excessT / T_AMB, 0.15);"
if old6 in src:
    src = src.replace(old6, new6, 1)
    print("修改4b成功: vy 最低速度保底")
else:
    print("修改4b失败")

with open(PATH, "w", encoding="utf-8") as f:
    f.write(src)
print("\n文件已保存")
