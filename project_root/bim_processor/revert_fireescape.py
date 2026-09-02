with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 移除 exitDistanceField 和 distanceFieldComputed 成员变量
old1 = """    public List<int[]> exits = new ArrayList<>();
    public List<Integer> floorSlabs = new ArrayList<>();
    public int[][][] exitDistanceField; // 预计算：每个格子到最近出口的最短路径距离（考虑楼梯约束）
    private boolean distanceFieldComputed = false; // 距离场是否已计算（体素数据不变时不重复计算）"""
new1 = """    public List<int[]> exits = new ArrayList<>();
    public List<Integer> floorSlabs = new ArrayList<>();"""
content = content.replace(old1, new1)

# 2. 移除 computeExitDistanceField 和 getExitDistance 方法
old2 = """    // ★ 预计算出口距离场：BFS 从所有出口开始，考虑楼梯约束（只有楼梯位置才能上下移动）
    public void computeExitDistanceField() {
        if (distanceFieldComputed && exitDistanceField != null) {
            System.out.println("[寻路] 距离场已计算，跳过（体素数据未变）");
            return;
        }
        if (exits.isEmpty()) {
            System.out.println("[寻路] 无出口，跳过距离场计算");
            return;
        }
        int w = gridWidth, h = gridHeight, d = gridDepth;
        exitDistanceField = new int[d][h][w];
        for (int z = 0; z < d; z++)
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    exitDistanceField[z][y][x] = Integer.MAX_VALUE;

        java.util.Queue<int[]> queue = new java.util.LinkedList<>();
        for (int[] exit : exits) {
            int ex = exit[0], ey = exit[1], ez = exit[2];
            if (ez >= 0 && ez < d && ey >= 0 && ey < h && ex >= 0 && ex < w) {
                exitDistanceField[ez][ey][ex] = 0;
                queue.add(new int[]{ex, ey, ez});
            }
        }

        // 6 邻域 BFS（上下左右前后），楼梯位置允许 z 方向移动
        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        int visited = 0;
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            int x = cur[0], y = cur[1], z = cur[2];
            int dist = exitDistanceField[z][y][x];
            boolean onStair = (obstacleGrid.get(x, y, z) == TYPE_STAIR);

            for (int[] dir : dirs) {
                int nx = x + dir[0], ny = y + dir[1], nz = z + dir[2];
                if (nx < 0 || nx >= w || ny < 0 || ny >= h || nz < 0 || nz >= d) continue;
                // z 方向移动要求当前位置和目标位置都是楼梯
                if (dir[2] != 0 && !onStair) continue;
                if (!isPassable(nx, ny, nz)) continue;
                if (exitDistanceField[nz][ny][nx] > dist + 1) {
                    exitDistanceField[nz][ny][nx] = dist + 1;
                    queue.add(new int[]{nx, ny, nz});
                    visited++;
                }
            }
        }
        int reachable = 0;
        for (int z = 0; z < d; z++)
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    if (exitDistanceField[z][y][x] < Integer.MAX_VALUE)
                        reachable++;
        distanceFieldComputed = true;
        System.out.println("[寻路] 出口距离场计算完成，可达格子: " + reachable + "/" + (w*h*d));
    }

    // ★ 获取某格子到出口的距离（不可达返回大值）
    public int getExitDistance(int x, int y, int z) {
        if (exitDistanceField == null) return Integer.MAX_VALUE;
        if (z < 0 || z >= gridDepth || y < 0 || y >= gridHeight || x < 0 || x >= gridWidth)
            return Integer.MAX_VALUE;
        return exitDistanceField[z][y][x];
    }

    // ★ 动态网格分配器"""
new2 = """    // ★ 动态网格分配器"""
content = content.replace(old2, new2)

# 3. 移除 start() 中的 computeExitDistanceField() 调用
old3 = """        generateRandomFireSources();
        initMultipleFireSources();
        initExits();
        computeExitDistanceField(); // 预计算出口距离场，优化人员寻路

        // 3. 根据最终网格尺寸生成全 3D 随机人员"""
new3 = """        generateRandomFireSources();
        initMultipleFireSources();
        initExits();

        // 3. 根据最终网格尺寸生成全 3D 随机人员"""
content = content.replace(old3, new3)

# 4. 恢复 isPassable 原来的逻辑
old4 = """    public boolean isPassable(int x, int y, int z) {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight || z < 0 || z >= gridDepth) return false;
        int v = obstacleGrid.get(x, y, z);
        // 楼梯的 insideGrid=0（VoxelLoader 把墙和楼梯都设为非内部），但楼梯必须可通行
        if (v == TYPE_STAIR) return true;
        if (!isInsideBuilding(x, y, z)) return false;
        return v == TYPE_EMPTY || v == TYPE_DOOR || v == TYPE_EXIT;
    }"""
new4 = """    public boolean isPassable(int x, int y, int z) {
        if (!isInsideBuilding(x, y, z)) return false;
        int v = obstacleGrid.get(x, y, z);
        return v == TYPE_EMPTY || v == TYPE_DOOR || v == TYPE_EXIT || v == TYPE_STAIR;
    }"""
content = content.replace(old4, new4)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('FireEscape.java reverted')
