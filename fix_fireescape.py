#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""精确修复 FireEscape.java 混乱区域"""

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
with open(p, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 找到混乱区域
start_idx = None
end_idx = None
for i, line in enumerate(lines):
    if '<<<<<<< HEAD' in line and start_idx is None and i > 1190:
        start_idx = i
    if start_idx is not None and 'HeatRelease = new double' in line:
        end_idx = i
        break

print(f"混乱区域: 行 {start_idx+1} 到 {end_idx+1}")
print(f"起始行: {lines[start_idx].rstrip()}")
print(f"结束行: {lines[end_idx].rstrip()}")

correct = '''            // 2. 如果加载失败，自动回退到内建测试地图
            if (!loaded) {
                System.out.println("[Init] 回退至内建测试地图！");
                fallbackInitRealisticObstacles();
            } else {
                // ★ 体素加载成功后打通"翼区走廊 ↔ 楼梯间"门户。
                // 必须先修复封闭楼梯间的室内标记，再打通门户，顺序不能颠倒：
                // IFC 楼梯井常被 3D 洪水漏标为室外，行走掩码缺楼梯间内部空气时，
                // 穿墙 Dijkstra 会因代价超限漏凿所有楼层（顶层甚至识别不到楼梯间）。
                // start() 是 GUI 与 HTTP(initModel) 的公共入口，两处行为保持一致。
                Person.repairEnclosedStairwellIndoorStatus(this);
                // IFC 缺失楼梯间门导致各翼区被墙隔断，人物无法走到楼梯；
                // 此修复把最薄隔墙改写为 TYPE_DOOR（数据层修复，详见方法注释）。
                repairStairwellDoorways();
            }

        fireSourceHeatRelease = new double[fireSourcePositions.size()];
'''

new_lines = lines[:start_idx] + [correct] + lines[end_idx+1:]
with open(p, 'w', encoding='utf-8') as f:
    f.writelines(new_lines)
print("修复完成")

with open(p, 'r', encoding='utf-8') as f:
    content = f.read()
if '<<<<<<<' in content or '>>>>>>>' in content:
    print("警告: 仍有冲突标记!")
else:
    print("所有冲突标记已清除")
