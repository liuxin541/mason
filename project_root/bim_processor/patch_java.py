with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """            // 4. 处理语义信息（楼梯 2D 坐标 -> 3D 贯穿）
            SemanticInfo semantic = SemanticInfo.loadFromJson(semanticPath);
            for (int[] pos : semantic.stairPositions) {
                int sx = pos[0], sy = pos[1];
                for (int z = 0; z < depth; z++) {
                    obstacleGrid.set(sx, sy, z, TYPE_STAIR);
                    insideGrid.set(sx, sy, z, 0);
                }
            }
            System.out.println("[BIM] 地图加载成功！尺寸: " + width + "x" + height + "x" + depth + ", 出口数量: " + exits.size());"""

new = """            // 4. 处理语义信息（楼层、楼板位置）
            SemanticInfo semantic = SemanticInfo.loadFromJson(semanticPath);
            floorSlabs.clear();
            floorSlabs.addAll(semantic.floorSlabs);
            // 注意：楼梯已在 Python 端体素化时做连通性修复，体素数据中已有正确的 3D 楼梯，
            // 不再用 2D stairPositions 贯穿全楼（避免楼梯穿透楼板和屋顶）
            System.out.println("[BIM] 地图加载成功！尺寸: " + width + "x" + height + "x" + depth
                    + ", 出口数量: " + exits.size()
                    + ", 楼板层: " + floorSlabs.size() + " 层");"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
