with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 回退寻路部分到原来的贪心寻路
old = """        double moveProb = fm.moveProbability * (1.0 - smoke * 0.5);
        if (moveCooldown > 0 || fm.random.nextDouble() >= moveProb) {
            moveCooldown = Math.max(0, moveCooldown - 1);
            return;
        }

        int bestX = x, bestY = y, bestZ = z;
        double bestScore = Double.NEGATIVE_INFINITY;
        int currentDist = fm.getExitDistance(x, y, z);

        // ★ 优化寻路：使用预计算的出口距离场（BFS 最短路径，考虑楼梯约束）
        // 优先选择距离出口更近的格子，同时避开火和烟
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    int nx = fm.personGrid.stx(x + dx);
                    int ny = fm.personGrid.sty(y + dy);
                    int nz = z + dz;

                    if (nz < 0 || nz >= fm.gridDepth) continue;

                    // 楼梯约束：只有当前位置在楼梯上才允许上下移动
                    if (dz != 0 && fm.obstacleGrid.get(x, y, z) != FireEscape.TYPE_STAIR) continue;

                    if (!fm.isPassable(nx, ny, nz)) continue;

                    int nextDist = fm.getExitDistance(nx, ny, nz);
                    // 不可达格子跳过
                    if (nextDist == Integer.MAX_VALUE) continue;

                    double fireVal = fm.fireGrid.get(nx, ny, nz);
                    double smokeVal = fm.getSmokeAt(nx, ny, nz);

                    // 评分：距离场梯度为主（越接近出口越好），火/烟惩罚
                    // 加微小随机扰动避免人员扎堆
                    double score = -nextDist * 10.0 - fireVal * 1.5 - smokeVal * 3.0
                            + fm.random.nextDouble() * 0.5;

                    if (score > bestScore) {
                        bestScore = score; bestX = nx; bestY = ny; bestZ = nz;
                    }
                }
            }
        }

        // 执行移动
        if (bestX != x || bestY != y || bestZ != z) {
            fm.personGrid.setObjectLocation(this, bestX, bestY, bestZ);
            x = bestX; y = bestY; z = bestZ;
            moveCooldown = 2; // 优化：减少移动冷却，加快逃生速度
        } else {
            moveCooldown = 1;
        }"""

new = """        double moveProb = fm.moveProbability * (1.0 - smoke * 0.7);
        if (moveCooldown > 0 || fm.random.nextDouble() >= moveProb) {
            moveCooldown = Math.max(0, moveCooldown - 1);
            return;
        }

        int bestX = x, bestY = y, bestZ = z;
        double bestScore = Double.NEGATIVE_INFINITY;

        // ★ 真正的 3D 邻域搜索 (27 个方向)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    int nx = fm.personGrid.stx(x + dx);
                    int ny = fm.personGrid.sty(y + dy);
                    int nz = z + dz;

                    // 边界检查：防止 Z 轴越界
                    if (nz < 0 || nz >= fm.gridDepth) continue;

                    // ★ 楼梯约束：在没有楼梯的楼层，绝对不允许上下移动
                    if (dz != 0) {
                        // 只有当前位置处于楼梯上，才允许上下移动
                        if (fm.obstacleGrid.get(x, y, z) != FireEscape.TYPE_STAIR) continue;
                    }

                    // 检查目标格子是否可通行
                    if (!fm.isPassable(nx, ny, nz)) continue;

                    // ★ 3D 寻路评分：改为 3D 欧几里得距离
                    double minExitDist = Double.MAX_VALUE;
                    for (int[] exit : fm.exits) {
                        double d = Math.sqrt(Math.pow(nx - exit[0], 2) + Math.pow(ny - exit[1], 2) + Math.pow(nz - exit[2], 2));
                        if (d < minExitDist) minExitDist = d;
                    }
                    double fireVal = fm.fireGrid.get(nx, ny, nz);
                    double smokeVal = fm.getSmokeAt(nx, ny, nz);
                    double score = -minExitDist - fireVal * 1.2 - smokeVal * 2.0;

                    if (score > bestScore) {
                        bestScore = score; bestX = nx; bestY = ny; bestZ = nz;
                    }
                }
            }
        }

        // 执行移动
        if (bestX != x || bestY != y || bestZ != z) {
            fm.personGrid.setObjectLocation(this, bestX, bestY, bestZ);
            x = bestX; y = bestY; z = bestZ;
            moveCooldown = 4;
        } else {
            moveCooldown = 1;
        }"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('Person.java reverted')
