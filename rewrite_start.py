#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重写 FireEscape.java start() 方法的混乱部分"""

p = r"E:\xuexi\MASON\mason\mason\src\main\java\sim\app\fireescape\FireEscape.java"
with open(p, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 找到 start() 方法开始和 fireSourcePlumeRadius 行
start_idx = None
end_idx = None
for i, line in enumerate(lines):
    if 'public void start()' in line:
        start_idx = i
    if start_idx and 'fireSourcePlumeRadius = new double' in line:
        end_idx = i
        break

print(f"start() 开始: 行 {start_idx+1}")
print(f"fireSourcePlumeRadius: 行 {end_idx+1}")
print(f"需要替换: 行 {start_idx+1} 到 {end_idx}")

# 正确的 start() 方法前部
correct = '''    public void start() {
        super.start();

        // ★ 根据 mapMode 选择地图
        if ("simple".equals(mapMode)) {
            loadSimpleRectRoom();
        } else {
            // 1. 尝试加载真实体素
            // ★ 默认指向 ifc_output（一号办公楼）；若想临时切换，可用 -Dfireescape.voxel=新路径 覆盖
            String voxelPath = System.getProperty("fireescape.voxel", "./ifc_output/voxel_grid.voxel");
            String semanticPath = System.getProperty("fireescape.semantic", "./ifc_output/semantic_info.json");

            System.out.println("[Init] 默认加载路径: " + voxelPath);
            boolean loaded = loadFromVoxel(voxelPath, semanticPath);

            // 2. 如果加载失败，自动回退到内建测试地图
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
        }

        // 3. 重置状态与火源
        deadByFire = 0;
        deadBySmoke = 0;
        currentTime = 0.0;

        generateRandomFireSources();

        fireSourceHeatRelease = new double[fireSourcePositions.size()];
        fireSourceFlameHeight = new double[fireSourcePositions.size()];
'''

# 替换
new_lines = lines[:start_idx] + [correct] + lines[end_idx:]
with open(p, 'w', encoding='utf-8') as f:
    f.writelines(new_lines)

print("替换完成")

# 验证
with open(p, 'r', encoding='utf-8') as f:
    final = f.read()
if '<<<<<<<' in final or '>>>>>>>' in final:
    print("警告: 仍有冲突标记!")
    import re
    for m in re.finditer(r'^(<<<<<<<|=======|>>>>>>>)', final, re.MULTILINE):
        line_num = final[:m.start()].count('\n') + 1
        print(f"  行 {line_num}: {m.group()}")
else:
    print("所有冲突标记已清除")
