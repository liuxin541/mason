with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        } catch (Exception e) {
            System.err.println("[BIM] 加载体素失败: " + e.getMessage());
            return false;
        }
    }

    // ★ 动态网格分配器"""

new = """        } catch (Exception e) {
            System.err.println("[BIM] 加载体素失败: " + e.getMessage());
            return false;
        }
    }

    // ★ 预计算出口距离场：BFS 从所有出口开始，考虑楼梯约束（只有楼梯位置才能上下移动）
    public void computeExitDistanceField() {
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

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
