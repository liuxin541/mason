package sim.app.fireescape;

import sim.engine.*;
import sim.field.grid.*;
import sim.util.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class FireEscape extends SimState {
    private static final long serialVersionUID = 1L;

    // ---- 世界尺寸（由 loadFromVoxel 动态决定） ----
    public int gridWidth = 0;
    public int gridHeight = 0;
    public int gridDepth = 0;
    public int personCount = 50;
    public boolean simpleRoomMode = false;  // 简单矩形房间模式(观察算法用)

    // ---- 核心 3D 数据容器 ----
    public DoubleGrid3D fireGrid;
    public DoubleGrid3D nextFireGrid;
    public DoubleGrid3D smokeGrid;
    public DoubleGrid3D nextSmokeGrid;
    public SparseGrid3D personGrid;
    public IntGrid3D obstacleGrid;
    public IntGrid3D fuelGrid;
    public IntGrid3D insideGrid;
    public List<int[]> exits = new ArrayList<>();
    public int groundFloorMinZ = 0;
    public int groundFloorMaxZ = 0;
    public Person[] persons;
    private FireSpread fireSpread;

    public int deadByFire = 0;
    public int deadBySmoke = 0;

    // ---- 3D 建筑构件类型常量 ----
    public static final int TYPE_EMPTY = 0;
    public static final int TYPE_WALL = 1;
    public static final int TYPE_FUEL = 2;
    public static final int TYPE_DOOR = 3;
    public static final int TYPE_EXIT = 4;
    public static final int TYPE_STAIR = 5;

    // ---- 物理参数（与原版保持一致） ----
    public static final double T_AMBIENT = 293.15;
    public static final double T_FLAME = 873.0;
    public static final double T_IGNITION = 573.0;
    public static final double T_DEATH = 473.0;
    public static final double T_MAX = 823.0;

    public double alpha_air = 2.0e-5;
    public double conductionBoost = 50.0;
    public double coolingRate = 0.06;
    public double rhoAir = 1.2;
    public double cpAir = 1005;
    public double h_conv = 15.0;
    public double radiationFraction = 0.38;
    public double Cd = 0.65;
    public double g = 9.81;
    public double t2FireGrowth = 0.0117;
    public double Q_steady = 150.0;
    public int maxSourceRadius = 3;
    public double radiationRadius = 6.0;
    public double fuelRadiationRadius = 4.0;
    public double ceilingHeight = 3.0;
    public double ceilingJetRadius = 8.0;
    public double ceilingJetStrength = 0.5;
    public double fuelHeatValue = 18.0e6;
    public double burnRatePerArea = 0.010;
    public double smokeYield = 0.05;
    public int fuelCapacity = 80;
    public double baseSmokeSpread = 0.012;
    public double smokeDecayRate = 0.005;
    public double smokeDriftCoeff = 0.05;
    public double smokeDriftMaxSpeed = 0.2;
    public double exhaustRate = 0.15;
    public double smokeAbsorptionCoeff = 0.5;
    public static final double MAX_SMOKE = 1.0;
    public double moveProbability = 0.4;
    public double safeRange = 15.0;
    public double deltaX = 0.5;
    public double deltaZ = 0.5;  // 与 Python voxel_size 一致（z 方向体素大小），原值 0.25 与体素数据不匹配
    public double deltaT = 0.5;
    public double currentTime = 0.0;
    public volatile boolean showPersons = true;

    private int fireSourceCount = 1;
    public List<int[]> fireSourcePositions = new ArrayList<>();

    // 每个火源的实时物理参数（与 fireSourcePositions 下标一一对应）
    public double[] fireSourceHeatRelease = new double[0];  // kW
    public double[] fireSourceFlameHeight = new double[0];  // m
    public double[] fireSourcePlumeRadius = new double[0];  // m

    // ★ 语义信息（解析自 semantic_info.json，供 HTTP 端暴露给前端）
    public SemanticInfo semanticInfo = new SemanticInfo();

    // ---- 构造方法（不再硬编码尺寸） ----
    public FireEscape(long seed) {
        super(seed);
    }

    public void setFireSourceCount(int count) { if (count < 1) count = 1; this.fireSourceCount = count; }
    public int getFireSourceCount() { return fireSourceCount; }
    public List<int[]> getFireSourcePositions() { return fireSourcePositions; }
    public double getSmokeAt(int x, int y, int z) { return smokeGrid.get(x, y, z); }
    public void recordDeath(String cause) {
        if ("fire".equals(cause)) deadByFire++;
        else if ("smoke".equals(cause)) deadBySmoke++;
    }

    // ★ 核心：动态读取 .voxel 文件，确定尺寸并加载
    public boolean loadFromVoxel(String voxelPath, String semanticPath) {
        try (RandomAccessFile raf = new RandomAccessFile(voxelPath, "r")) {
            // 1. 校验文件头和读取动态尺寸（.voxel 小端序存储）
            byte[] magic = new byte[5];
            raf.readFully(magic);
            if (!new String(magic).equals("VOXEL")) throw new IOException("无效 .voxel 签名");

            raf.skipBytes(2); // version (short)
            int width = Integer.reverseBytes(raf.readInt()); // 小端转 Java 大端
            int height = Integer.reverseBytes(raf.readInt());
            int depth = Integer.reverseBytes(raf.readInt());
            raf.skipBytes(13); // voxel_size(4) + reserved(9)

            // 2. 根据真实尺寸动态分配网格
            createGrids(width, height, depth);

            // 3. 读取体素数据并填入网格
            int totalCells = width * height * depth;
            byte[] dataBuf = new byte[totalCells];
            raf.readFully(dataBuf);

            exits.clear();
            int idx = 0;
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int type = dataBuf[idx++] & 0xFF; // 无符号转 int
                        obstacleGrid.set(x, y, z, type);
                    }
                }
            }

            // 4. 处理语义信息（楼梯 2D 坐标 -> 3D 贯穿）
            SemanticInfo semantic = SemanticInfo.loadFromJson(semanticPath);
            this.semanticInfo = semantic;  // ★ 保存到字段，供 HTTP 端使用
            for (int[] pos : semantic.stairPositions) {
                int sx = pos[0], sy = pos[1];
                for (int z = 0; z < depth; z++) {
                    obstacleGrid.set(sx, sy, z, TYPE_STAIR);
                }
            }

            // 5. ★ 2D 射线扫描法 (Sweep Line / Even-Odd Rule) 精确判定建筑内部。
            //    原理：对每一列 (x,y) 从下往上扫描，每遇到墙体/楼板就翻转 inside 状态。
            //    改进：先找到"有效楼层范围" [zGround, zRoof]，在此范围外用射线扫描，
            //    在此范围外（架空底层、屋顶以上）直接标记为 outside。
            //    这样可以避免楼板开口（楼梯/电梯井）导致射线穿透到屋顶以上。
            //    要求：Python 端已将楼板/屋顶 slab 体素化为 WALL。

            // 5a. 找到有效楼层的 z 范围（检测"大面积楼板"层）
            int zGround = -1, zRoof = -1;
            for (int z = 0; z < depth; z++) {
                int wallCount = 0;
                for (int x = 0; x < width; x++) {
                    for (int y = 0; y < height; y++) {
                        if (obstacleGrid.get(x, y, z) == TYPE_WALL) wallCount++;
                    }
                }
                // "大面积楼板"：该层墙体素数 > 总列数的 40%
                if (wallCount > width * height * 0.4) {
                    if (zGround == -1) zGround = z;
                    zRoof = z; // 持续更新，取最高的楼板层
                }
            }
            // 如果找不到大面积楼板，回退到全范围扫描
            if (zGround == -1) { zGround = 0; zRoof = depth - 1; }
            System.out.println("[BIM] 有效楼层范围: zGround=" + zGround + " (world_z=" + fmt(zGround * deltaZ + semantic.originOffset[2])
                    + "m), zRoof=" + zRoof + " (world_z=" + fmt(zRoof * deltaZ + semantic.originOffset[2]) + "m)");

            // 5b. 先将所有体素标记为 outside
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < depth; z++) {
                        insideGrid.set(x, y, z, 0);
                        fuelGrid.set(x, y, z, 0);
                    }
                }
            }

            // 5c. 在 [zGround, zRoof] 范围内执行射线扫描
            int wallFlipCount = 0;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    boolean inside = false;
                    for (int z = zGround; z <= zRoof; z++) {
                        int type = obstacleGrid.get(x, y, z);
                        if (type == TYPE_WALL ) {
                            insideGrid.set(x, y, z, 0);
                            fuelGrid.set(x, y, z, 0);
                            inside = !inside;
                            wallFlipCount++;
                        } else {
                            if (inside) {
                                insideGrid.set(x, y, z, 1);
                                if (type == TYPE_FUEL) {
                                    fuelGrid.set(x, y, z, fuelCapacity);
                                } else {
                                    fuelGrid.set(x, y, z, 0);
                                }
                                if (type == TYPE_EXIT) {
                                    exits.add(new int[]{x, y, z});
                                }
                            }
                            // else: 保持 outside (已在 5b 中初始化)
                        }
                    }
                }
            }
            System.out.println("[BIM] 射线扫描完成: 墙体/楼梯翻转次数 = " + wallFlipCount);
            // ★ 强制将所有开口(门/楼梯/窗)标记为建筑内部, 确保烟雾能通过开口蔓延
            //   射线扫描法只在遇到墙体时翻转inside状态, 门/窗/楼梯不是墙体会被错误标记为outside
            int fixedOpenings = 0;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < depth; z++) {
                        int type = obstacleGrid.get(x, y, z);
                        if (type == TYPE_DOOR || type == TYPE_STAIR || type == 6 /* TYPE_WINDOW */) {
                            if (insideGrid.get(x, y, z) == 0) {
                                insideGrid.set(x, y, z, 1);
                                fuelGrid.set(x, y, z, 0);
                                fixedOpenings++;
                            }
                        }
                    }
                }
            }
            System.out.println("[BIM] 开口修复: 门/楼梯/窗标记为inside共 " + fixedOpenings + " 个体素");
            // 强制将所有楼梯格标记为建筑内部（垂直通道，可通行）
            for (int[] pos : semantic.stairPositions) {
                int sx = pos[0], sy = pos[1];
                for (int z = 0; z < depth; z++) {
                    if (obstacleGrid.get(sx, sy, z) == TYPE_STAIR) {
                        insideGrid.set(sx, sy, z, 1);
                        fuelGrid.set(sx, sy, z, 0);
                    }
                }
            }
            // ★ 洪水填充: 从所有已知inside点出发, 填充所有连通的非墙体区域为inside
            //   射线扫描法对复杂建筑结构不准确, 大量房间内部被错误标记为outside
            java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
            for (int x = 0; x < width; x++)
                for (int y = 0; y < height; y++)
                    for (int z = 0; z < depth; z++)
                        if (insideGrid.get(x, y, z) == 1) queue.add(new int[]{x, y, z});
            int floodFilled = 0;
            int[][] dirs6 = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
            while (!queue.isEmpty()) {
                int[] cur = queue.poll();
                for (int[] d : dirs6) {
                    int nx = cur[0]+d[0], ny = cur[1]+d[1], nz = cur[2]+d[2];
                    if (nx<0||nx>=width||ny<0||ny>=height||nz<0||nz>=depth) continue;
                    if (insideGrid.get(nx, ny, nz) == 1) continue;
                    int obs = obstacleGrid.get(nx, ny, nz);
                    if (obs == TYPE_WALL) continue;  // 只跳过墙体, 门/窗/楼梯/空气都可通行
                    insideGrid.set(nx, ny, nz, 1);
                    fuelGrid.set(nx, ny, nz, 0);
                    queue.add(new int[]{nx, ny, nz});
                    floodFilled++;
                }
            }
            System.out.println("[BIM] 洪水填充: 新增inside体素 " + floodFilled + " 个");
            // ★ 窗户外部溢出: 将窗户(type=6)周围大范围外部空气标记为inside, 让烟雾从窗户冒出形成羽状
            int windowOverflow = 0;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < depth; z++) {
                        if (obstacleGrid.get(x, y, z) != 6) continue;  // 只处理窗户
                        // 水平方向4体素, 垂直方向5体素(向上更多, 模拟羽状上升)
                        for (int dx2 = -4; dx2 <= 4; dx2++) {
                            for (int dy2 = -4; dy2 <= 4; dy2++) {
                                for (int dz2 = -1; dz2 <= 5; dz2++) {
                                    int nx = x+dx2, ny = y+dy2, nz = z+dz2;
                                    if (nx<0||nx>=width||ny<0||ny>=height||nz<0||nz>=depth) continue;
                                    if (insideGrid.get(nx, ny, nz) == 1) continue;
                                    if (obstacleGrid.get(nx, ny, nz) == TYPE_WALL) continue;
                                    insideGrid.set(nx, ny, nz, 1);
                                    fuelGrid.set(nx, ny, nz, 0);
                                    windowOverflow++;
                                }
                            }
                        }
                    }
                }
            }
            System.out.println("[BIM] 窗户外部溢出: 新增inside体素 " + windowOverflow + " 个");
            // 计算底层 z 范围（最低的连续室内区域）
            groundFloorMinZ = -1;
            groundFloorMaxZ = -1;
            for (int z = 0; z < depth; z++) {
                boolean hasInside = false;
                for (int x = 0; x < width && !hasInside; x++) {
                    for (int y = 0; y < height && !hasInside; y++) {
                        if (insideGrid.get(x, y, z) == 1) hasInside = true;
                    }
                }
                if (hasInside) {
                    if (groundFloorMinZ == -1) groundFloorMinZ = z;
                    groundFloorMaxZ = z;
                } else if (groundFloorMinZ != -1) {
                    break;
                }
            }
            if (groundFloorMinZ == -1) { groundFloorMinZ = 0; groundFloorMaxZ = 0; }


            // 5. ★ 坐标系统统一：从 semantic_info.json 读取 voxel_size / origin_offset，
            //    覆盖默认 deltaX/deltaZ，使后端物理计算与 Python 体素网格一致。
            if (semantic.hasVoxelSize) {
                deltaX = semantic.voxelSize;
                deltaZ = semantic.voxelSize;
                System.out.println("[BIM] 坐标对齐: deltaX=deltaZ=" + voxelSizeToString(semantic.voxelSize)
                        + " (来自 semantic_info.json)");
            }
            if (semantic.hasOriginOffset) {
                System.out.println("[BIM] origin_offset=[" + fmt(semantic.originOffset[0])
                        + ", " + fmt(semantic.originOffset[1]) + ", " + fmt(semantic.originOffset[2]) + "]");
            }
            if (semantic.hasGridDims) {
                System.out.println("[BIM] grid_dims=[" + semantic.gridDims[0] + ", "
                        + semantic.gridDims[1] + ", " + semantic.gridDims[2] + "]");
            }

            System.out.println("[BIM] 地图加载成功！尺寸: " + width + "x" + height + "x" + depth + ", 出口数量: " + exits.size());
            return true;

        } catch (Exception e) {
            System.err.println("[BIM] 加载体素失败: " + e.getMessage());
            return false;
        }
    }

    // ★ 动态网格分配器
    private void createGrids(int width, int height, int depth) {
        this.gridWidth = width;
        this.gridHeight = height;
        this.gridDepth = depth;

        fireGrid = new DoubleGrid3D(width, height, depth, T_AMBIENT);
        nextFireGrid = new DoubleGrid3D(width, height, depth, T_AMBIENT);
        smokeGrid = new DoubleGrid3D(width, height, depth, 0.0);
        nextSmokeGrid = new DoubleGrid3D(width, height, depth, 0.0);
        personGrid = new SparseGrid3D(width, height, depth);
        obstacleGrid = new IntGrid3D(width, height, depth, 0);
        fuelGrid = new IntGrid3D(width, height, depth, 0);
        insideGrid = new IntGrid3D(width, height, depth, 1);

        System.out.println("[Grid] 3D 网格已动态分配: " + width + "x" + height + "x" + depth);
    }

    // ★ 强健回退方案：即使没有体素文件，也能硬生成 3 层测试建筑
    private void fallbackInitRealisticObstacles() {
        int fallbackWidth = 20, fallbackHeight = 20, fallbackDepth = 24;
        createGrids(fallbackWidth, fallbackHeight, fallbackDepth);
        exits.clear();

        // 1. 填充外部墙体
        for (int z = 0; z < fallbackDepth; z++) {
            for (int x = 0; x < fallbackWidth; x++) {
                obstacleGrid.set(x, 0, z, TYPE_WALL);
                obstacleGrid.set(x, fallbackHeight - 1, z, TYPE_WALL);
            }
            for (int y = 0; y < fallbackHeight; y++) {
                obstacleGrid.set(0, y, z, TYPE_WALL);
                obstacleGrid.set(fallbackWidth - 1, y, z, TYPE_WALL);
            }
        }

        // 2. 设置楼梯 (x=5, y=10)
        for (int z = 0; z < fallbackDepth; z++) {
            obstacleGrid.set(5, 10, z, TYPE_STAIR);
        }

        // 3. 设置楼板 (z=8, 16)
        int[] floorSlabs = {8, 16};
        for (int z : floorSlabs) {
            for (int x = 1; x < fallbackWidth - 1; x++) {
                for (int y = 1; y < fallbackHeight - 1; y++) {
                    if (obstacleGrid.get(x, y, z) != TYPE_STAIR && obstacleGrid.get(x, y, z) != TYPE_WALL) {
                        obstacleGrid.set(x, y, z, TYPE_WALL);
                    }
                }
            }
        }

        // 4. 设置出口 (x=18, y=18, z=0)
        obstacleGrid.set(18, 18, 0, TYPE_EXIT);
        exits.add(new int[]{18, 18, 0});

        // 5. 同步 insideGrid 和 fuelGrid
        for (int x = 0; x < fallbackWidth; x++) {
            for (int y = 0; y < fallbackHeight; y++) {
                for (int z = 0; z < fallbackDepth; z++) {
                    if (obstacleGrid.get(x, y, z) == TYPE_WALL) {
                        insideGrid.set(x, y, z, 0);
                        fuelGrid.set(x, y, z, 0);
                    } else {
                        insideGrid.set(x, y, z, 1);
                    }
                }
            }
        }
        System.out.println("[Fallback] 已加载内建测试地图 (20x20x24)");
        this.groundFloorMinZ = 0;
        this.groundFloorMaxZ = 7;  // fallback 中一楼是 z=0~7
    }

    // ★ 地图模式: "building" (默认, 加载voxel) | "simple" (简单矩形房间)
    public String mapMode = "building";

    // ★ 简单矩形房间 (单层, 一个出口, 用于观察烟和火焰算法)
    public void loadSimpleRectRoom() {
        int w = 30, h = 20, d = 10;
        createGrids(w, h, d);
        exits.clear();

        // 1. 四面墙
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                obstacleGrid.set(x, 0, z, TYPE_WALL);
                obstacleGrid.set(x, h - 1, z, TYPE_WALL);
            }
            for (int y = 0; y < h; y++) {
                obstacleGrid.set(0, y, z, TYPE_WALL);
                obstacleGrid.set(w - 1, y, z, TYPE_WALL);
            }
        }
        // 2. 地板(z=0)和天花板(z=d-1)
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++) {
                obstacleGrid.set(x, y, 0, TYPE_WALL);
                obstacleGrid.set(x, y, d - 1, TYPE_WALL);
            }
        // 3. 出口: 在右侧墙(x=w-1)中间开一个门 (z=1~2)
        int exitY = h / 2;
        obstacleGrid.set(w - 1, exitY, 1, TYPE_EXIT);
        obstacleGrid.set(w - 1, exitY, 2, TYPE_EXIT);
        exits.add(new int[]{w - 1, exitY, 1});

        // 4. 同步 insideGrid / fuelGrid
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                for (int z = 0; z < d; z++) {
                    if (obstacleGrid.get(x, y, z) == TYPE_WALL) {
                        insideGrid.set(x, y, z, 0);
                        fuelGrid.set(x, y, z, 0);
                    } else {
                        insideGrid.set(x, y, z, 1);
                    }
                }

        // 5. 坐标参数
        deltaX = 0.5;
        deltaZ = 0.5;
        this.semanticInfo = new SemanticInfo();
        this.groundFloorMinZ = 1;
        this.groundFloorMaxZ = d - 2;
        System.out.println("[Simple] 已加载简单矩形房间 " + w + "x" + h + "x" + d
                + " (出口: x=" + (w-1) + ",y=" + exitY + ",z=1)");
    }

    // ★ 重写核心启动流程：默认加载一号办公楼所在的 ifc_output，失败则回退
    public void start() {
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
            }
        }

        // 3. 重置状态与火源
        deadByFire = 0;
        deadBySmoke = 0;
        currentTime = 0.0;

        generateRandomFireSources();
        initMultipleFireSources();
        initExits();

        // 4. 根据最终网格尺寸生成全 3D 随机人员（按楼层均匀分布，不再全部下落到底层）
        persons = new Person[personCount];
        Steppable[] agents = new Steppable[personCount];
        // ★ 收集所有楼层的楼板 z（obstacle 覆盖率 > 40% 的切片），用于"落到当前楼层楼板上方"
        java.util.List<Integer> floorSlabZs = new java.util.ArrayList<>();
        for (int z = 0; z < gridDepth; z++) {
            int wallCount = 0;
            for (int xi = 0; xi < gridWidth; xi++)
                for (int yi = 0; yi < gridHeight; yi++)
                    if (obstacleGrid.get(xi, yi, z) == TYPE_WALL) wallCount++;
            if (wallCount > gridWidth * gridHeight * 0.4) floorSlabZs.add(z);
        }
        // 兜底：如果没有检测到楼板，用 groundFloorMinZ
        if (floorSlabZs.isEmpty() && groundFloorMinZ >= 0) floorSlabZs.add(groundFloorMinZ);

        for (int i = 0; i < personCount; i++) {
            int x, y, z;
            int attempts = 0;
            do {
                x = random.nextInt(gridWidth);
                y = random.nextInt(gridHeight);
                z = random.nextInt(gridDepth);
                attempts++;
                if (attempts > 500) break;
            } while (!(insideGrid.get(x, y, z) == 1 && obstacleGrid.get(x, y, z) == 0) || fireGrid.get(x, y, z) > T_AMBIENT + 50);

            // ★ 楼层感知下落：只落到当前 z 所在楼层的楼板上方，不穿越楼板下落到更低的楼层。
            //   找到 <= 当前 z 的最大楼板 z（slabZ），然后从当前 z 向下落到 slabZ+1 即停。
            //   这样人员会分布在多个楼层，而不是全部堆在底层。
            int slabBelow = -1;
            for (int sz : floorSlabZs) {
                if (sz <= z && sz > slabBelow) slabBelow = sz;
            }
            if (slabBelow >= 0) {
                // 目标落脚点 = slabBelow + 1（楼板上方一格），但需确认该位置在建筑内部且非障碍
                int targetZ = Math.min(gridDepth - 1, slabBelow + 1);
                if (insideGrid.get(x, y, targetZ) == 1 && obstacleGrid.get(x, y, targetZ) == 0) {
                    z = targetZ;
                } else {
                    // 楼板上方不可站立，则在该楼层范围内找最近的可站立 z
                    int nextSlab = gridDepth;
                    for (int sz : floorSlabZs) {
                        if (sz > slabBelow) { nextSlab = sz; break; }
                    }
                    boolean placed = false;
                    for (int zz = slabBelow + 1; zz < nextSlab && zz < gridDepth; zz++) {
                        if (insideGrid.get(x, y, zz) == 1 && obstacleGrid.get(x, y, zz) == 0) {
                            z = zz; placed = true; break;
                        }
                    }
                    if (!placed) z = targetZ; // 实在找不到就放楼板上方
                }
            } else {
                // 没有楼板信息，退回原重力下落逻辑
                while (z > 0) {
                    int below = obstacleGrid.get(x, y, z - 1);
                    if (below == TYPE_WALL || below == TYPE_STAIR) break;
                    if (insideGrid.get(x, y, z - 1) != 1) break;
                    z--;
                }
            }

            persons[i] = new Person(this, x, y, z);
            personGrid.setObjectLocation(persons[i], x, y, z);
            agents[i] = persons[i];
        }
    }

    public void initMultipleFireSources() {
        fireSourceHeatRelease = new double[fireSourcePositions.size()];
        fireSourceFlameHeight = new double[fireSourcePositions.size()];
        fireSourcePlumeRadius = new double[fireSourcePositions.size()];
        for (int x = 0; x < gridWidth; x++) {
            for (int y = 0; y < gridHeight; y++) {
                for (int z = 0; z < gridDepth; z++) {
                    if (obstacleGrid.get(x, y, z) != TYPE_WALL) {
                        fireGrid.set(x, y, z, T_AMBIENT);
                        smokeGrid.set(x, y, z, 0.0);
                    }
                }
            }
        }
        for (int[] fp : fireSourcePositions) {
            int cx = fp[0], cy = fp[1], cz = fp[2];
            fireGrid.set(cx, cy, cz, T_FLAME);
            smokeGrid.set(cx, cy, cz, 0.01);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        int nx = fireGrid.stx(cx + dx);
                        int ny = fireGrid.sty(cy + dy);
                        int nz = fireGrid.stz(cz + dz);
                        if (insideGrid.get(nx, ny, nz) == 1 && (obstacleGrid.get(nx, ny, nz) == TYPE_EMPTY || obstacleGrid.get(nx, ny, nz) == TYPE_DOOR)) {
                            fireGrid.set(nx, ny, nz, Math.max(fireGrid.get(nx, ny, nz), T_FLAME * 0.7));
                            smokeGrid.set(nx, ny, nz, Math.max(smokeGrid.get(nx, ny, nz), 0.005));
                        }
                    }
                }
            }
        }
    }

    public void generateRandomFireSources() {
        fireSourcePositions.clear();
        if (gridWidth > 0 && gridHeight > 0 && gridDepth > 0) {
            // 收集所有可燃地面体素（inside=1，非墙/非出口）
            java.util.List<int[]> candidates = new java.util.ArrayList<>();
            int minZ = gridDepth;
            for (int z = 0; z < gridDepth; z++) {
                for (int x = 0; x < gridWidth; x++) {
                    for (int y = 0; y < gridHeight; y++) {
                        if (insideGrid.get(x, y, z) == 1
                                && obstacleGrid.get(x, y, z) != TYPE_WALL
                                && obstacleGrid.get(x, y, z) != TYPE_EXIT) {
                            candidates.add(new int[]{x, y, z});
                            if (z < minZ) minZ = z;
                        }
                    }
                }
            }
            // ★ 火源只在最低室内层（地板上方第一层）生成，避免火源飘在空中
            java.util.List<int[]> floorCandidates = new java.util.ArrayList<>();
            for (int[] c : candidates) {
                if (c[2] == minZ) floorCandidates.add(c);
            }
            if (!floorCandidates.isEmpty()) candidates = floorCandidates;
            if (!candidates.isEmpty()) {
                // Fisher-Yates 洗牌，取前 2~5 个
                for (int i = candidates.size() - 1; i > 0; i--) {
                    int j = random.nextInt(i + 1);
                    int[] tmp = candidates.get(i);
                    candidates.set(i, candidates.get(j));
                    candidates.set(j, tmp);
                }
                int count = Math.min(candidates.size(), 2 + random.nextInt(4)); // 2,3,4,5
                for (int i = 0; i < count; i++) {
                    fireSourcePositions.add(candidates.get(i));
                }
            }
        }
        fireSourceHeatRelease = new double[fireSourcePositions.size()];
        fireSourceFlameHeight = new double[fireSourcePositions.size()];
        fireSourcePlumeRadius = new double[fireSourcePositions.size()];
    }

    /** 在 (sx, sy, sz) 附近搜索最近的 inside=1 且非墙非出口体素，返回坐标或 null。 */
    private int[] findInteriorPoint(int sx, int sy, int sz) {
        if (gridWidth == 0 || gridHeight == 0 || gridDepth == 0) return null;
        int cx = Math.max(0, Math.min(gridWidth - 1, sx));
        int cy = Math.max(0, Math.min(gridHeight - 1, sy));
        int cz = Math.max(0, Math.min(gridDepth - 1, sz));
        // 螺旋扩散搜索 (曼哈顿距离递增)
        for (int r = 0; r < Math.max(gridWidth, Math.max(gridHeight, gridDepth)); r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = 0; dz <= r; dz++) {  // 只往低层及当前层找火源
                        int x = cx + dx, y = cy + dy, z = cz + dz;
                        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight || z < 0 || z >= gridDepth) continue;
                        if (insideGrid.get(x, y, z) == 1 && obstacleGrid.get(x, y, z) != FireEscape.TYPE_WALL
                                && obstacleGrid.get(x, y, z) != FireEscape.TYPE_EXIT) {
                            return new int[]{x, y, z};
                        }
                    }
                }
            }
        }
        return null;
    }

    public void initExits() {
        // 已经在加载过程中填充完毕
    }

    // 辅助判定方法
    public boolean isInsideBuilding(int x, int y, int z) {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight || z < 0 || z >= gridDepth) return false;
        return insideGrid.get(x, y, z) == 1;
    }
    public boolean isPassable(int x, int y, int z) {
        if (!isInsideBuilding(x, y, z)) return false;
        int v = obstacleGrid.get(x, y, z);
        return v == TYPE_EMPTY || v == TYPE_DOOR || v == TYPE_EXIT || v == TYPE_STAIR;
    }
    private static String fmt(double v) { return String.format("%.4f", v); }
    private static String voxelSizeToString(double v) { return String.format("%.4f", v); }
    public void stop() { super.finish(); }
    public int getActivePersonCount() { int cnt = 0; for (Person p : persons) if (p != null && p.alive && !p.escaped) cnt++; return cnt; }
    public int getDeadCount() { return deadByFire + deadBySmoke; }
    public int getDeadByFire() { return deadByFire; }
    public int getDeadBySmoke() { return deadBySmoke; }
    public int getEscapedCount() { int cnt = 0; for (Person p : persons) if (p != null && p.escaped) cnt++; return cnt; }

    public static void main(String[] args) { doLoop(FireEscape.class, args); System.exit(0); }

    // ================================================================
    // ★ 静态内部类：SemanticInfo (JSON 语义解析器)
    // 解析 Python run.py 输出的 semantic_info.json，作为坐标系统统一的权威来源。
    // 字段：
    //   floors / stair_positions        : 楼层与楼梯 2D 坐标
    //   voxel_size                      : 体素尺寸（米），与 deltaX/deltaZ 对齐
    //   origin_offset                   : 体素网格 (0,0,0) 对应的世界坐标
    //   grid_dims                       : 体素网格尺寸 [width, height, depth]
    //   building_bbox_world             : 建筑在世界坐标中的包围盒
    // 转换公式（与 Python builder.py / 前端 fireescape.html 一致）：
    //   voxel_index = floor((world_coord - origin_offset) / voxel_size)
    //   world_coord  = voxel_index * voxel_size + origin_offset
    // ================================================================
    public static class SemanticInfo {
        public int floorCount = 0;
        public List<int[]> stairPositions = new ArrayList<>();

        // 坐标系统统一字段（缺省值保证向后兼容）
        public double voxelSize = 0.5;            // 缺省与 deltaX 一致
        public double[] originOffset = new double[]{0.0, 0.0, 0.0};
        public int[] gridDims = new int[]{0, 0, 0};
        public double[] buildingBboxWorld = new double[]{0, 0, 0, 0, 0, 0};
        public boolean hasVoxelSize = false;
        public boolean hasOriginOffset = false;
        public boolean hasGridDims = false;
        public boolean hasBboxWorld = false;

        public static SemanticInfo loadFromJson(String filePath) throws IOException {
            if (!Files.exists(Paths.get(filePath))) return new SemanticInfo();
            String content = new String(Files.readAllBytes(Paths.get(filePath)));
            SemanticInfo info = new SemanticInfo();

            // 楼层
            Pattern floorPattern = Pattern.compile("\"floors\"\\s*:\\s*(\\d+)");
            Matcher fm = floorPattern.matcher(content);
            if (fm.find()) info.floorCount = Integer.parseInt(fm.group(1));

            // voxel_size
            Matcher vs = Pattern.compile("\"voxel_size\"\\s*:\\s*([0-9.eE+-]+)").matcher(content);
            if (vs.find()) {
                info.voxelSize = Double.parseDouble(vs.group(1));
                info.hasVoxelSize = true;
            }

            // origin_offset: [x, y, z]（匹配首个三元素数组，避免被 stair_positions 干扰）
            Matcher oo = Pattern.compile("\"origin_offset\"\\s*:\\s*\\[\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*\\]").matcher(content);
            if (oo.find()) {
                info.originOffset = new double[]{
                        Double.parseDouble(oo.group(1)),
                        Double.parseDouble(oo.group(2)),
                        Double.parseDouble(oo.group(3))};
                info.hasOriginOffset = true;
            }

            // grid_dims: [w, h, d]
            Matcher gd = Pattern.compile("\"grid_dims\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]").matcher(content);
            if (gd.find()) {
                info.gridDims = new int[]{
                        Integer.parseInt(gd.group(1)),
                        Integer.parseInt(gd.group(2)),
                        Integer.parseInt(gd.group(3))};
                info.hasGridDims = true;
            }

            // building_bbox_world: [xmin, ymin, zmin, xmax, ymax, zmax]
            Matcher bb = Pattern.compile("\"building_bbox_world\"\\s*:\\s*\\[\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*,\\s*([0-9.eE+-]+)\\s*\\]").matcher(content);
            if (bb.find()) {
                info.buildingBboxWorld = new double[]{
                        Double.parseDouble(bb.group(1)),
                        Double.parseDouble(bb.group(2)),
                        Double.parseDouble(bb.group(3)),
                        Double.parseDouble(bb.group(4)),
                        Double.parseDouble(bb.group(5)),
                        Double.parseDouble(bb.group(6))};
                info.hasBboxWorld = true;
            }

            // 楼梯坐标：在 stair_positions 字段之后匹配，避免误读 origin_offset/grid_dims 的数字
            Matcher sp = Pattern.compile("\"stair_positions\"\\s*:\\s*\\[(.*?)\\]").matcher(content);
            if (sp.find()) {
                String stairBlock = sp.group(1);
                Matcher sm = Pattern.compile("\\[(\\d+),(\\d+)\\]").matcher(stairBlock);
                while (sm.find()) {
                    info.stairPositions.add(new int[]{
                            Integer.parseInt(sm.group(1)),
                            Integer.parseInt(sm.group(2))});
                }
            }

            return info;
        }
    }
}