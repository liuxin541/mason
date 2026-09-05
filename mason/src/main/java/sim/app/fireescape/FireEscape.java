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
    public static final int TYPE_COLUMN = 6;
    // 窗保留独立类型：参与建筑围护，但不属于人员可通行的门。
    public static final int TYPE_WINDOW = 7;

    // ---- 物理参数（与原版保持一致） ----
    public static final double T_AMBIENT = 293.15;
    public static final double T_FLAME = 1173.0;
    public static final double T_IGNITION = 573.0;
    public static final double T_DEATH = 473.0;
    public static final double T_MAX = 1273.0;

    public double alpha_air = 2.0e-5;
    public double conductionBoost = 50.0;
    public double coolingRate = 0.02;
    public double rhoAir = 1.2;
    public double cpAir = 1005;
    public double h_conv = 15.0;
    public double radiationFraction = 0.38;
    public double Cd = 0.65;
    public double g = 9.81;
    public double t2FireGrowth = 0.0117;
    public double Q_steady = 500.0;
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
    public double baseSmokeSpread = 0.02;
    public double smokeDecayRate = 0.005;
    public double smokeDriftCoeff = 0.08;
    public double smokeDriftMaxSpeed = 0.3;
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

            // 5. 从网格边界洪水填充室外空间。门、出口和楼梯在此阶段临时封闭，
            //    防止室外区域从正常开口或竖向通道灌入建筑内部。
            rebuildInsideGridFromExterior();

            // ★ 窗户外部溢出: 将窗户周围大范围外部空气标记为inside, 让烟雾从窗户冒出形成羽状
            int windowOverflow = 0;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < depth; z++) {
                        int wtype = obstacleGrid.get(x, y, z);
                        if (wtype != TYPE_WINDOW && wtype != 6) continue;
                        for (int dx2 = -6; dx2 <= 6; dx2++) {
                            for (int dy2 = -6; dy2 <= 6; dy2++) {
                                for (int dz2 = -1; dz2 <= 8; dz2++) {
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

    /**
     * 从六个网格边界标记所有可达空气为室外，剩余封闭空气为室内。
     * 门、出口和楼梯只在室内掩码生成期间作为封闭体，完成后恢复为可通行区域。
     */
    public void rebuildInsideGridFromExterior() {
        int width = gridWidth;
        int height = gridHeight;
        int depth = gridDepth;
        int totalCells = width * height * depth;
        boolean[] outside = new boolean[totalCells];
        int[] queue = new int[totalCells];
        int head = 0;
        int tail = 0;
        int[][] directions = {
                {1, 0, 0}, {-1, 0, 0},
                {0, 1, 0}, {0, -1, 0},
                {0, 0, 1}, {0, 0, -1}
        };

        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (x != 0 && x != width - 1 &&
                            y != 0 && y != height - 1 &&
                            z != 0 && z != depth - 1) {
                        continue;
                    }
                    int index = voxelIndex(x, y, z);
                    if (!isEnclosureBarrier(obstacleGrid.get(x, y, z)) && !outside[index]) {
                        outside[index] = true;
                        queue[tail++] = index;
                    }
                }
            }
        }

        while (head < tail) {
            int index = queue[head++];
            int x = index % width;
            int yz = index / width;
            int y = yz % height;
            int z = yz / height;

            for (int[] direction : directions) {
                int nx = x + direction[0];
                int ny = y + direction[1];
                int nz = z + direction[2];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height || nz < 0 || nz >= depth) continue;

                int nextIndex = voxelIndex(nx, ny, nz);
                if (!outside[nextIndex] && !isEnclosureBarrier(obstacleGrid.get(nx, ny, nz))) {
                    outside[nextIndex] = true;
                    queue[tail++] = nextIndex;
                }
            }
        }

        int outsideCount = 0;
        int insideCount = 0;
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int type = obstacleGrid.get(x, y, z);
                    boolean interior = !isEnclosureBarrier(type) && !outside[voxelIndex(x, y, z)];
                    insideGrid.set(x, y, z, interior ? 1 : 0);
                    fuelGrid.set(x, y, z, interior && type == TYPE_FUEL ? fuelCapacity : 0);
                    if (interior) insideCount++;
                    else if (outside[voxelIndex(x, y, z)]) outsideCount++;
                }
            }
        }

        // 将与室内相邻的门、出口和楼梯连通块恢复为室内可通行区域。
        head = 0;
        tail = 0;
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (isInteriorTransition(obstacleGrid.get(x, y, z)) && hasInteriorNeighbor(x, y, z)) {
                        int index = voxelIndex(x, y, z);
                        insideGrid.set(x, y, z, 1);
                        queue[tail++] = index;
                    }
                }
            }
        }

        while (head < tail) {
            int index = queue[head++];
            int x = index % width;
            int yz = index / width;
            int y = yz % height;
            int z = yz / height;

            for (int[] direction : directions) {
                int nx = x + direction[0];
                int ny = y + direction[1];
                int nz = z + direction[2];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height || nz < 0 || nz >= depth) continue;
                if (insideGrid.get(nx, ny, nz) == 0 &&
                        isInteriorTransition(obstacleGrid.get(nx, ny, nz))) {
                    insideGrid.set(nx, ny, nz, 1);
                    queue[tail++] = voxelIndex(nx, ny, nz);
                }
            }
        }

        exits.clear();
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (insideGrid.get(x, y, z) == 1 &&
                            obstacleGrid.get(x, y, z) == TYPE_EXIT) {
                        exits.add(new int[]{x, y, z});
                    }
                }
            }
        }

        updateGroundFloorRange();
        System.out.println("[BIM] 室内外洪水填充完成: outside=" + outsideCount +
                ", inside=" + insideCount + ", exits=" + exits.size());
    }

    private boolean isEnclosureBarrier(int type) {
        // 所有非空气体素均阻断室外洪水；WINDOW 因而封闭外墙洞口，
        // DOOR/EXIT/STAIR 会在洪水结束后由 isInteriorTransition 单独恢复。
        return type != TYPE_EMPTY && type != TYPE_FUEL;
    }

    private boolean isInteriorTransition(int type) {
        return type == TYPE_DOOR || type == TYPE_EXIT || type == TYPE_STAIR;
    }

    private boolean hasInteriorNeighbor(int x, int y, int z) {
        int[][] directions = {
                {1, 0, 0}, {-1, 0, 0},
                {0, 1, 0}, {0, -1, 0},
                {0, 0, 1}, {0, 0, -1}
        };
        for (int[] direction : directions) {
            int nx = x + direction[0];
            int ny = y + direction[1];
            int nz = z + direction[2];
            if (nx >= 0 && nx < gridWidth && ny >= 0 && ny < gridHeight &&
                    nz >= 0 && nz < gridDepth && insideGrid.get(nx, ny, nz) == 1) {
                return true;
            }
        }
        return false;
    }

    private int voxelIndex(int x, int y, int z) {
        return (z * gridHeight + y) * gridWidth + x;
    }

    private void updateGroundFloorRange() {
        groundFloorMinZ = -1;
        groundFloorMaxZ = -1;
        for (int z = 0; z < gridDepth; z++) {
            boolean hasInside = false;
            for (int x = 0; x < gridWidth && !hasInside; x++) {
                for (int y = 0; y < gridHeight && !hasInside; y++) {
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
        if (groundFloorMinZ == -1) {
            groundFloorMinZ = 0;
            groundFloorMaxZ = 0;
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

    // ★ 重写核心启动流程：默认加载一号办公楼所在的 ifc_output，失败则回退
    public void start() {
        super.start();

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

        // 3. 重置状态与火源
        deadByFire = 0;
        deadBySmoke = 0;
        currentTime = 0.0;

        generateRandomFireSources();
        initMultipleFireSources();
        initExits();

        // 4. 人员出生规则统一由 Person 维护。
        Person.spawnPopulation(this);
    }

    public void initMultipleFireSources() {
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
            // 在建筑内部 (inside=1) 寻找一个合适的火源点，优先取低层 z
            int[] pos = findInteriorPoint(gridWidth / 4, gridHeight / 4, 1);
            if (pos != null) {
                fireSourcePositions.add(pos);
            } else {
                // 兜底：从网格中心扩散搜索任意内部点
                pos = findInteriorPoint(gridWidth / 2, gridHeight / 2, Math.max(1, gridDepth / 4));
                if (pos != null) fireSourcePositions.add(pos);
            }
        }
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
        return Person.canOccupy(this, x, y, z);
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
