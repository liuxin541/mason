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
    public java.util.List<int[]> insideCells = new java.util.ArrayList<>();
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
    public double t2FireGrowth = 0.04;  // 方案A微调: 加快火势发展(t=100s 达400kW), 实时仿真可见烟
    public double Q_steady = 500.0;
    public int maxSourceRadius = 3;
    public double radiationRadius = 6.0;
    public double fuelRadiationRadius = 4.0;
    public double ceilingHeight = 3.0;
    public double ceilingJetRadius = 12.0;  // 方案A微调4: 射流半径加大
    public double ceilingJetStrength = 2.5;  // 方案A微调6: 射流再增强, 烟层铺满楼层
    public double fuelHeatValue = 18.0e6;
    public double burnRatePerArea = 0.010;
    public double smokeYield = 0.05;
    public int fuelCapacity = 80;
    public double baseSmokeSpread = 0.25;  // 方案A微调4: 烟层水平扩散再增强(0.15→0.25)
    public double smokeDecayRate = 0.005;
    public double smokeDriftCoeff = 0.15;  // 方案A: 增强浮力驱动
    public double smokeDriftMaxSpeed = 2.5;  // 方案A微调2: 羽流速度上限略升(真实羽流1-3m/s)
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

            // 5. 从网格边界洪水填充室外空间。门、出口和楼梯在此阶段临时封闭，
            //    防止室外区域从正常开口或竖向通道灌入建筑内部。
            rebuildInsideGridFromExterior();

            // ★ 窗户外部溢出: 将窗户周围大范围外部空气标记为inside, 让烟雾从窗户冒出形成羽状
            int windowOverflow = 0;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < depth; z++) {
                        int wtype = obstacleGrid.get(x, y, z);
                        if (wtype != TYPE_WINDOW) continue;
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

    // =====================================================================
    // ★ 楼梯间门户自动修复（数据层修复，遵循"门才可通行"的物理语义）
    // =====================================================================
    // 背景：IFC 模型中楼梯间入口缺失 IfcDoor，体素化后各翼区走廊与楼梯间
    // 之间被连续 WALL/COLUMN 完全隔断。人物按"出房间→走廊→找楼梯"疏散时，
    // 最后一跳无路可走（A* 搜不到楼梯而进入 NO_SAFE_PATH）。
    // 修复方式：逐层定位楼梯间连通域与各翼区连通域，在二者之间最薄的实体
    // 隔墙上自动开凿门洞（墙体格改写为 TYPE_DOOR），而不是在寻路中做穿墙
    // 特例，保证"墙不可穿越、门才可通行"的语义全局一致。
    // 门洞开在脚层 z0 并向上延伸站立净空带。Person 的门户识别(FloorDoorGraph)
    // 恰好按"脚层 z0 处的 TYPE_DOOR"划分门户，因此新门洞会被既有 DoorPortal
    // 分区、门口排队拥堵逻辑零改动地识别和使用。

    /** 允许开洞的最大隔墙厚度（格）；超过视为承重厚墙，禁止凿穿。 */
    private static final int MAX_DOORWAY_WALL_THICKNESS = 4;
    /** 参与楼梯间连通修复的最小区域格数，用于过滤卫生间等小隔间。 */
    private static final int MIN_STAIRWELL_LINK_ZONE_CELLS = 200;
    /** 门洞竖向最大高度（格）：脚层 + 6 格站立净空带 ≈ 1.75m。 */
    private static final int DOORWAY_HEIGHT_CELLS = 7;
    /** 每层开洞迭代上限：每次开洞至少合并一个区域，防止意外死循环。 */
    private static final int MAX_DOORWAY_ITERATIONS_PER_FLOOR = 8;
    /** 结构柱在穿墙选路中的代价：远高于墙，保证选路自动避开柱体。 */
    private static final double DOORWAY_UNCARVABLE_COST = 50.0;
    /**
     * 楼梯实体格在穿墙 Dijkstra 中的额外代价：让"房间→走廊"路径
     * （代价=墙厚）优先于"房间→楼梯间直达"路径（代价=墙厚+本罚值）。
     * 设为 3：一面墙代价 1，罚值 3 意味着直达楼梯比到走廊至少贵 3 格墙厚，
     * 但 1 格墙 + 3 = 4 仍在 MAX_DOORWAY_WALL_THICKNESS 上限内可接受。
     */
    private static final double STAIRWELL_CROSSING_PENALTY = 3.0;

    /**
     * 打通各层"走廊 ↔ 楼梯间"的物理交汇处。
     * 委托给 Person 类：只恢复走廊-楼梯间交界处被 IFC 体素化误标为墙的
     * 门洞格，不再使用 Dijkstra 穿墙开凿，避免在非交汇处造出不合理门洞。
     * 幂等：门洞恢复后原墙格已变为 TYPE_DOOR，再次调用时不会重复开洞。
     */
    public void repairStairwellDoorways() {
        Person.repairStairwellJunctions(this);
    }

    /**
     * 修复单层：迭代"识别连通域 → 找最薄实体墙 → 开凿门洞"，直到所有大型
     * 区域都与楼梯间连通，或不存在可开凿的墙。返回本层楼梯间足迹供上层继承。
     */
    private boolean[] repairFloorStairwellDoorways(int z0, boolean[] inheritedFootprint) {
        int width = gridWidth;
        int height = gridHeight;
        boolean[] stairFootprint = inheritedFootprint;
        for (int iteration = 0; iteration < MAX_DOORWAY_ITERATIONS_PER_FLOOR; iteration++) {
            boolean[] walkMask = buildStairwellWalkMask(z0);
            int[] zoneByCell = labelFloorZones(walkMask);
            int zoneCount = maxZoneId(zoneByCell) + 1;
            if (zoneCount == 0) return stairFootprint;

            // ---- 识别楼梯间连通域 ----
            // 首选：包含"楼梯可站立格"（脚层是楼梯，或脚层空气正下方是楼梯）
            // 最多的组件；顶层带内无楼梯格时退化为与下层足迹重叠最多的组件。
            int[] zoneStairCells = new int[zoneCount];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int zoneId = zoneByCell[y * width + x];
                    if (zoneId < 0) continue;
                    boolean onStair =
                            obstacleGrid.get(x, y, z0) == TYPE_STAIR ||
                                    (z0 > 0 && obstacleGrid.get(x, y, z0 - 1) == TYPE_STAIR);
                    if (onStair) zoneStairCells[zoneId]++;
                }
            }
            int stairZone = -1;
            int bestStairCells = 0;
            for (int zoneId = 0; zoneId < zoneCount; zoneId++) {
                if (zoneStairCells[zoneId] > bestStairCells) {
                    bestStairCells = zoneStairCells[zoneId];
                    stairZone = zoneId;
                }
            }
            if (stairZone < 0 && stairFootprint != null) {
                stairZone = findZoneOverlappingFootprint(
                        zoneByCell, zoneCount, stairFootprint);
            }
            if (stairZone < 0) {
                System.out.println("[门户修复] z=" + z0 + " 未识别到楼梯间连通域，跳过");
                return stairFootprint;
            }
            stairFootprint = collectZoneMask(zoneByCell, stairZone);

            // ---- 收集未与楼梯间连通的大型区域（按规模从大到小） ----
            int[] zoneSizes = new int[zoneCount];
            for (int zoneId : zoneByCell) {
                if (zoneId >= 0) zoneSizes[zoneId]++;
            }
            int minZoneCells = minLinkZoneCells();
            List<Integer> disconnected = new ArrayList<>();
            for (int zoneId = 0; zoneId < zoneCount; zoneId++) {
                if (zoneId != stairZone && zoneSizes[zoneId] >= minZoneCells) {
                    disconnected.add(zoneId);
                }
            }
            if (disconnected.isEmpty()) {
                System.out.println("[门户修复] z=" + z0 + " 所有大型区域均已与楼梯间连通"
                        + (iteration == 0 ? "（无需修复）" : "，共开洞 " + iteration + " 处"));
                return stairFootprint;
            }
            disconnected.sort((a, b) -> zoneSizes[b] - zoneSizes[a]);

            // ---- 在所有断开区域中寻找全局最便宜的穿墙路径并开洞 ----
            int bestRunStart = -1;
            int bestRunEnd = -1;
            double bestCost = Double.MAX_VALUE;
            int[] bestPath = null;
            for (int zoneId : disconnected) {
                int[][] crossing = findCheapestWallCrossing(
                        zoneByCell, zoneId, stairZone, walkMask, z0);
                if (crossing == null) continue;
                int[] run = firstCarvableWallRun(crossing[0], z0);
                if (run == null) continue;
                double cost = crossing[1][0];
                if (cost < bestCost) {
                    bestCost = cost;
                    bestRunStart = run[0];
                    bestRunEnd = run[1];
                    bestPath = crossing[0];
                }
            }
            if (bestPath == null) {
                System.out.println("[门户修复] z=" + z0 + " 剩余 " + disconnected.size()
                        + " 个区域无法打通（墙厚超上限 " + MAX_DOORWAY_WALL_THICKNESS
                        + " 格或无实体墙），保留现状");
                return stairFootprint;
            }
            int carved = carveDoorway(bestPath, bestRunStart, bestRunEnd, z0);
            if (carved <= 0) {
                System.out.println("[门户修复] z=" + z0 + " 门洞开凿失败，终止本层迭代");
                return stairFootprint;
            }
            System.out.println("[门户修复] z=" + z0 + " 开凿门洞: 墙厚 "
                    + (bestRunEnd - bestRunStart + 1) + " 格, 共改写 "
                    + carved + " 个体素为 TYPE_DOOR（开洞后自动重扫剩余区域）");
        }
        return stairFootprint;
    }

    /**
     * 穿墙 Dijkstra：从源区域所有格出发（代价 0），行走格代价 0，
     * 实体墙/未标记空气代价 1（计入墙厚），柱体等不可凿格代价极高。
     * 总代价超过厚度上限即剪枝。返回 {路径格索引序列, 总代价}，无路返回 null。
     */
    private int[][] findCheapestWallCrossing(int[] zoneByCell, int sourceZone,
                                             int targetZone, boolean[] walkMask,
                                             int z0) {
        int width = gridWidth;
        int height = gridHeight;
        int cellCount = width * height;
        double[] dist = new double[cellCount];
        Arrays.fill(dist, Double.MAX_VALUE);
        int[] prev = new int[cellCount];
        Arrays.fill(prev, -1);
        PriorityQueue<double[]> open = new PriorityQueue<>(
                (a, b) -> Double.compare(a[0], b[0]));
        for (int index = 0; index < cellCount; index++) {
            if (zoneByCell[index] == sourceZone) {
                dist[index] = 0;
                open.add(new double[]{0, index});
            }
        }
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            double[] node = open.remove();
            double d = node[0];
            int index = (int) node[1];
            if (d > dist[index]) continue;
            if (zoneByCell[index] == targetZone) {
                // 回溯完整路径（含源与目标），供后续提取墙段
                List<Integer> path = new ArrayList<>();
                for (int cur = index; cur >= 0; cur = prev[cur]) {
                    path.add(cur);
                }
                Collections.reverse(path);
                return new int[][]{path.stream().mapToInt(Integer::intValue).toArray(),
                        {(int) Math.round(d)}};
            }
            int x = index % width;
            int y = index / width;
            for (int[] dir : directions) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                int nextIndex = ny * width + nx;
                double step = crossingCellCost(nx, ny, z0, walkMask);
                double nd = d + step;
                if (nd > MAX_DOORWAY_WALL_THICKNESS + 1e-9) continue;
                if (nd < dist[nextIndex]) {
                    dist[nextIndex] = nd;
                    prev[nextIndex] = index;
                    open.add(new double[]{nd, nextIndex});
                }
            }
        }
        return null;
    }

    /**
     * 穿墙代价：可行走格中走廊格 0、楼梯实体格 STAIR_PENALTY（高代价）；
     * 墙/窗/未标记空气 1（计墙厚）；柱等不可凿格极高。
     *
     * 楼梯实体格额外代价确保 Dijkstra 优先选择"房间→走廊"的穿墙路径，
     * 而非"房间→楼梯间"直达路径。只有当房间不与走廊共享墙、只能向
     * 楼梯间开凿时，才接受直达路径（代价仍在上限内）。
     */
    private double crossingCellCost(int x, int y, int z0, boolean[] walkMask) {
        if (walkMask[y * gridWidth + x]) {
            // 可行走格：楼梯实体格给额外代价，避免直接从房间凿门到楼梯间，
            // 优先经过走廊再到楼梯间（符合现实疏散动线）。
            int type = obstacleGrid.get(x, y, z0);
            if (type == TYPE_STAIR) return STAIRWELL_CROSSING_PENALTY;
            return 0;
        }
        int type = obstacleGrid.get(x, y, z0);
        if (type == TYPE_WALL || type == TYPE_WINDOW || type == TYPE_EMPTY) return 1;
        return DOORWAY_UNCARVABLE_COST;
    }

    /**
     * 从穿墙路径中取第一段"可开凿实体墙"：段内脚层必须全部是 WALL/WINDOW
     * （纯空气间隙无墙可凿，例如一层楼板缺失区，直接放弃防止造出悬空门）。
     * 返回长度 2 数组 {路径起点索引, 路径终点索引}，不可凿返回 null。
     */
    private int[] firstCarvableWallRun(int[] path, int z0) {
        int runStart = -1;
        for (int i = 0; i <= path.length; i++) {
            boolean carvable = false;
            if (i < path.length) {
                int x = path[i] % gridWidth;
                int y = path[i] / gridWidth;
                int type = obstacleGrid.get(x, y, z0);
                carvable = type == TYPE_WALL || type == TYPE_WINDOW;
            }
            if (carvable && runStart < 0) {
                runStart = i;
            } else if (!carvable && runStart >= 0) {
                return new int[]{runStart, i - 1};
            }
        }
        return null;
    }

    /**
     * 沿墙段开凿门洞：墙段为穿越方向（厚度），切向加宽到 2 格形成可通行
     * 门宽；竖向从脚层向上改写 WALL/WINDOW 为 TYPE_DOOR，遇到"上方是空气
     * 的墙"（即楼板天花）立即停止，保证不凿穿上下楼层间隔。
     */
    private int carveDoorway(int[] path, int runStart, int runEnd, int z0) {
        int width = gridWidth;
        // 穿墙轴向：由墙段首尾格的位移决定；切向即门洞宽度方向
        int sx = path[runStart] % width;
        int sy = path[runStart] / width;
        int ex = path[runEnd] % width;
        int ey = path[runEnd] / width;
        boolean alongX = ex != sx;
        // 切向加宽：优先 +1 方向，要求候选格在脚层与带中层都是墙体（对齐墙），
        // 避免两侧同时开洞造成 3 格以上大洞
        int midZ = Math.min(z0 + 3, gridDepth - 1);
        int tx = alongX ? sx : sx + 1;
        int ty = alongX ? sy + 1 : sy;
        if (!isSolidWallColumn(tx, ty, z0, midZ)) {
            tx = alongX ? sx : sx - 1;
            ty = alongX ? sy - 1 : sy;
            if (!isSolidWallColumn(tx, ty, z0, midZ)) {
                tx = -1;   // 两侧都无法加宽时保留单格门宽（0.25m，仍可通行）
                ty = -1;
            }
        }
        int carved = 0;
        int[] cellsX = new int[(runEnd - runStart + 1) * 2];
        int[] cellsY = new int[cellsX.length];
        int cells = 0;
        for (int i = runStart; i <= runEnd; i++) {
            cellsX[cells] = path[i] % width;
            cellsY[cells] = path[i] / width;
            cells++;
        }
        if (tx >= 0) {
            cellsX[cells] = tx;
            cellsY[cells] = ty;
            cells++;
        }
        // 逐格竖向开洞：WALL/WINDOW→DOOR；遇"上空是空气的墙"（楼板）即停
        for (int i = 0; i < cells; i++) {
            int x = cellsX[i];
            int y = cellsY[i];
            for (int z = z0; z < Math.min(z0 + DOORWAY_HEIGHT_CELLS, gridDepth); z++) {
                int type = obstacleGrid.get(x, y, z);
                if (type != TYPE_WALL && type != TYPE_WINDOW) continue;
                boolean ceilingSlab = z + 1 < gridDepth &&
                        obstacleGrid.get(x, y, z + 1) == TYPE_EMPTY;
                if (ceilingSlab && z > z0) break;   // 到达天花楼板，停止向上
                obstacleGrid.set(x, y, z, TYPE_DOOR);
                carved++;
            }
        }
        return carved;
    }

    /** 判断 (x,y) 在脚层与带中层是否都是墙体（用于门洞切向加宽校验）。 */
    private boolean isSolidWallColumn(int x, int y, int z0, int midZ) {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight) return false;
        int base = obstacleGrid.get(x, y, z0);
        int mid = obstacleGrid.get(x, y, midZ);
        return (base == TYPE_WALL || base == TYPE_WINDOW) &&
                (mid == TYPE_WALL || mid == TYPE_WINDOW);
    }

    /**
     * 构建单层脚层可行走掩码（与 Person.isFloorGraphNavigationCell 对齐）：
     * 楼梯/门/出口直接通行；空气需室内(insideGrid==1)或楼梯正上方过渡格。
     */
    private boolean[] buildStairwellWalkMask(int z0) {
        int width = gridWidth;
        int height = gridHeight;
        boolean[] mask = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int type = obstacleGrid.get(x, y, z0);
                if (type == TYPE_STAIR || type == TYPE_DOOR || type == TYPE_EXIT) {
                    mask[y * width + x] = true;
                } else if (type == TYPE_EMPTY) {
                    boolean aboveStair = z0 > 0 &&
                            obstacleGrid.get(x, y, z0 - 1) == TYPE_STAIR;
                    mask[y * width + x] =
                            insideGrid.get(x, y, z0) == 1 || aboveStair;
                }
            }
        }
        return mask;
    }

    /** 4 邻接连通域标记，返回每格所属组件号（未通行格为 -1）。 */
    private int[] labelFloorZones(boolean[] walkMask) {
        int width = gridWidth;
        int height = gridHeight;
        int[] zoneByCell = new int[width * height];
        Arrays.fill(zoneByCell, -1);
        int[] queue = new int[width * height];
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int zoneId = 0;
        for (int start = 0; start < walkMask.length; start++) {
            if (!walkMask[start] || zoneByCell[start] >= 0) continue;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            zoneByCell[start] = zoneId;
            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int y = index / width;
                for (int[] dir : directions) {
                    int nx = x + dir[0];
                    int ny = y + dir[1];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    int nextIndex = ny * width + nx;
                    if (!walkMask[nextIndex] || zoneByCell[nextIndex] >= 0) continue;
                    zoneByCell[nextIndex] = zoneId;
                    queue[tail++] = nextIndex;
                }
            }
            zoneId++;
        }
        return zoneByCell;
    }

    /**
     * 楼层行走面识别：直接复用 Person 的出生楼层识别（连续楼板支撑面积 +
     * 净空 + 顶板校验），保证门户开凿与人员出生基于同一套楼层基准。
     * 不再自建"楼板夹层"启发式——IFC 楼板常为多体素厚（下表面贴墙），
     * "上下皆空气"的夹层特征会漏判 z=2/18/47 等楼层，只有 z=32 被识别。
     */
    private List<Integer> detectSlabWalkingLevels() {
        return Person.detectValidFloors(this);
    }

    /** 最小参与区域格数：真实大楼 200 格；小模型（单元测试）自动放宽。 */
    private int minLinkZoneCells() {
        return Math.max(20, Math.min(MIN_STAIRWELL_LINK_ZONE_CELLS,
                gridWidth * gridHeight / 50));
    }

    private int maxZoneId(int[] zoneByCell) {
        int max = -1;
        for (int zoneId : zoneByCell) max = Math.max(max, zoneId);
        return max;
    }

    /** 提取指定组件的二维格掩码（作为楼梯间足迹供上层继承）。 */
    private boolean[] collectZoneMask(int[] zoneByCell, int zoneId) {
        boolean[] mask = new boolean[gridWidth * gridHeight];
        for (int index = 0; index < mask.length; index++) {
            mask[index] = zoneByCell[index] == zoneId;
        }
        return mask;
    }

    /** 找与足迹（切比雪夫距离 ≤2 膨胀后）重叠格数最多的大型组件。 */
    private int findZoneOverlappingFootprint(int[] zoneByCell, int zoneCount,
                                             boolean[] footprint) {
        int width = gridWidth;
        int height = gridHeight;
        int[] overlap = new int[zoneCount];
        int[] size = new int[zoneCount];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                int zoneId = zoneByCell[index];
                if (zoneId < 0) continue;
                size[zoneId]++;
                if (footprintNear(footprint, x, y, 2)) overlap[zoneId]++;
            }
        }
        int minZoneCells = minLinkZoneCells();
        int best = -1;
        int bestOverlap = 0;
        for (int zoneId = 0; zoneId < zoneCount; zoneId++) {
            if (size[zoneId] < minZoneCells) continue;
            if (overlap[zoneId] > bestOverlap) {
                bestOverlap = overlap[zoneId];
                best = zoneId;
            }
        }
        return best;
    }

    private boolean footprintNear(boolean[] footprint, int x, int y, int radius) {
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int nx = x + dx;
                int ny = y + dy;
                if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue;
                if (footprint[ny * gridWidth + nx]) return true;
            }
        }
        return false;
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
                    // ★ 可燃物判定: 原有TYPE_FUEL标记 + 室内空气格按40%比例自动标记(模拟家具/装修分布)
                    //   使用确定性伪随机(坐标hash),保证每次加载结果一致,火焰可从火源点燃周围可燃物并蔓延
                    boolean isFuelCell = (type == TYPE_FUEL) ||
                            (interior && type == TYPE_EMPTY && pseudoRandomFuel(x, y, z) < 0.40);
                    fuelGrid.set(x, y, z, isFuelCell ? fuelCapacity : 0);
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
        // ★ 预计算内部格列表,供 FireSpread 性能优化使用
        insideCells.clear();
        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++)
                for (int z = 0; z < depth; z++)
                    if (insideGrid.get(x, y, z) == 1 && obstacleGrid.get(x, y, z) != TYPE_WALL)
                        insideCells.add(new int[]{x, y, z});
        System.out.println("[BIM] insideCells 预计算: " + insideCells.size() + " 个内部格");
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
        initMultipleFireSources();
        initExits();

        // 方案A修复: 楼梯间修复改变了部分格的 inside/obstacle, 火源格可能是修复后才可用的内部格,
        // 需在火源确定后重建 insideCells, 否则回写/冷却/clamp 会跳过火源格导致烟浓度无限累积
        insideCells.clear();
        for (int x = 0; x < gridWidth; x++)
            for (int y = 0; y < gridHeight; y++)
                for (int z = 0; z < gridDepth; z++)
                    if (insideGrid.get(x, y, z) == 1 && obstacleGrid.get(x, y, z) != TYPE_WALL)
                        insideCells.add(new int[]{x, y, z});
        System.out.println("[BIM] insideCells 重建(火源后): " + insideCells.size() + " 个内部格");
        if (!fireSourcePositions.isEmpty()) { int[] fp0 = fireSourcePositions.get(0); boolean hasFp = false; for (int[] c : insideCells) { if (c[0]==fp0[0] && c[1]==fp0[1] && c[2]==fp0[2]) { hasFp = true; break; } } System.out.println("[BIM] 火源格 (" + fp0[0] + "," + fp0[1] + "," + fp0[2] + ") inInsideCells=" + hasFp + " inside=" + insideGrid.get(fp0[0],fp0[1],fp0[2]) + " obstacle=" + obstacleGrid.get(fp0[0],fp0[1],fp0[2])); }

        // 4. 人员出生规则统一由 Person 维护。
        Person.spawnPopulation(this);
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
                        if (insideGrid.get(x, y, z) == 1 && obstacleGrid.get(x, y, z) == FireEscape.TYPE_EMPTY
                                && isOpenInteriorPoint(x, y, z)) {
                            return new int[]{x, y, z};
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 方案C修复: 前端画布设火源时, 找该 (x,y) 列最低的室内格(z>=0), 避免火源落在楼板/地下。返回 z 或 -1。 */
    public int findLowestInteriorZ(int x, int y) {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight) return -1;
        for (int z = 0; z < gridDepth; z++) {
            if (insideGrid.get(x, y, z) == 1 && obstacleGrid.get(x, y, z) == FireEscape.TYPE_EMPTY) return z;
        }
        return -1;
    }

    /** 方案A修复: 火源点必须水平开阔(至少3个水平邻居为室内可通行格), 避免火源落在竖井/门柱里 */
    private boolean isOpenInteriorPoint(int x, int y, int z) {
        int open = 0;
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : dirs) {
            int nx = x + d[0], ny = y + d[1];
            if (nx < 0 || nx >= gridWidth || ny < 0 || ny >= gridHeight) continue;
            if (insideGrid.get(nx, ny, z) == 1 && obstacleGrid.get(nx, ny, z) == FireEscape.TYPE_EMPTY) open++;
        }
        // 要求至少3个水平空房间邻居, 且上方一格也是空房间(有垂直蓄烟空间)
        if (open < 3) return false;
        if (z + 1 < gridDepth && insideGrid.get(x, y, z+1) == 1 && obstacleGrid.get(x, y, z+1) == FireEscape.TYPE_EMPTY) return true;
        return false;
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

    // ★ 确定性伪随机: 基于坐标hash,用于可燃物分布,保证每次加载结果一致
    private static double pseudoRandomFuel(int x, int y, int z) {
        int h = (x * 73856093) ^ (y * 19349663) ^ (z * 83492791);
        h ^= (h >>> 13);
        h *= 1274126177;
        h ^= (h >>> 16);
        return ((h & 0x7FFFFFFF) % 100000) / 100000.0;
    }

}
