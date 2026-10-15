package sim.app.fireescape;

import sim.engine.*;
import sim.field.grid.*;
import sim.util.*;
import java.io.*;
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

    // ---- 物理参数（FDS 对齐 · 仅参数调优） ----
    public static final double T_AMBIENT = 293.15;
    public static final double T_FLAME = 1173.0;
    public static final double T_IGNITION = 573.0;
    public static final double T_DEATH = 473.0;
    public static final double T_MAX = 1273.0;

    public double alpha_air = 2.0e-5;
    public double conductionBoost = 50.0;
    public double coolingRate = 0.01;
    public double rhoAir = 1.2;
    public double cpAir = 1005;
    public double h_conv = 25.0;
    public double radiationFraction = 0.3;
    public double Cd = 0.65;
    public double g = 9.81;
    public double t2FireGrowth = 0.0117;
    public double Q_steady = 500.0;
    public int maxSourceRadius = 5;
    public double radiationRadius = 8.0;
    public double fuelRadiationRadius = 4.0;
    public double ceilingHeight = 3.0;
    public double ceilingJetRadius = 8.0;
    public double ceilingJetStrength = 0.5;
    public double fuelHeatValue = 18.0e6;
    public double burnRatePerArea = 0.02;
    public double smokeYield = 0.05;
    public int fuelCapacity = 80;
    public double baseSmokeSpread = 0.25;
    public double smokeDecayRate = 0.0003;
    public double smokeDriftCoeff = 0.25;
    public double smokeDriftMaxSpeed = 1.2;
    public double exhaustRate = 0.3;
    public double smokeAbsorptionCoeff = 0.5;
    public static final double MAX_SMOKE = 1.0;
    public double moveProbability = 0.4;
    public double safeRange = 15.0;
    public double deltaX = 0.5;
    public double deltaZ = 0.25;
    public double deltaT = 0.5;
    public double currentTime = 0.0;
    public volatile boolean showPersons = true;

    private int fireSourceCount = 1;
    public List<int[]> fireSourcePositions = new ArrayList<>();

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
                        if (type == TYPE_WALL || type == TYPE_STAIR) {
                            insideGrid.set(x, y, z, 0);
                            fuelGrid.set(x, y, z, 0);
                        } else {
                            insideGrid.set(x, y, z, 1);
                            if (type == TYPE_FUEL) fuelGrid.set(x, y, z, fuelCapacity);
                            else if (type == TYPE_EXIT) exits.add(new int[]{x, y, z});
                        }
                    }
                }
            }

            // 4. 处理语义信息（楼梯 2D 坐标 -> 3D 贯穿）
            SemanticInfo semantic = SemanticInfo.loadFromJson(semanticPath);
            for (int[] pos : semantic.stairPositions) {
                int sx = pos[0], sy = pos[1];
                for (int z = 0; z < depth; z++) {
                    obstacleGrid.set(sx, sy, z, TYPE_STAIR);
                    insideGrid.set(sx, sy, z, 0);
                }
            }
            System.out.println("[BIM] 地图加载成功！尺寸: " + width + "x" + height + "x" + depth + ", 出口数量: " + exits.size());
            return true;

        } catch (Exception e) {
            System.err.println("[BIM] 加载体素失败: " + e.getMessage());
            return false;
        }
    }

    // ★ 动态网格分配器
    public void createGrids(int width, int height, int depth) {
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
                    if (obstacleGrid.get(x, y, z) == TYPE_WALL || obstacleGrid.get(x, y, z) == TYPE_STAIR) {
                        insideGrid.set(x, y, z, 0);
                        fuelGrid.set(x, y, z, 0);
                    } else {
                        insideGrid.set(x, y, z, 1);
                    }
                }
            }
        }
        System.out.println("[Fallback] 已加载内建测试地图 (20x20x24)");
    }

    // ★ 重写核心启动流程：先加载体素，若失败则回退，再生成人员
    public void start() {
        super.start();

        // 1. 尝试动态加载真实体素
        // 1.1 优先加载真实 BIM 体素（ifc_output），找不到再退测试数据（test_output）
        boolean loaded = loadFromVoxel("./ifc_output/voxel_grid.voxel", "./ifc_output/semantic_info.json");
        if (!loaded) {
            loaded = loadFromVoxel("./test_output/voxel_grid.voxel", "./test_output/semantic_info.json");
        }
        if (!loaded) {
            fallbackInitRealisticObstacles();
        }

        // 2. 重置状态与火源
        deadByFire = 0;
        deadBySmoke = 0;
        currentTime = 0.0;

        generateRandomFireSources();
        initMultipleFireSources();
        initExits();

        // 3. 根据最终网格尺寸生成全 3D 随机人员
        persons = new Person[personCount];
        Steppable[] agents = new Steppable[personCount];
        for (int i = 0; i < personCount; i++) {
            int x, y, z;
            do {
                x = random.nextInt(gridWidth);
                y = random.nextInt(gridHeight);
                z = random.nextInt(gridDepth);
            } while (!(insideGrid.get(x, y, z) == 1 && obstacleGrid.get(x, y, z) == 0) || fireGrid.get(x, y, z) > T_AMBIENT + 50);

            persons[i] = new Person(this, x, y, z);
            personGrid.setObjectLocation(persons[i], x, y, z);
            agents[i] = persons[i];
        }
        schedule.scheduleRepeating(new RandomSequence(agents));

        fireSpread = new FireSpread();
        schedule.scheduleRepeating(Schedule.EPOCH, 1, fireSpread, 1);
        System.out.println("[Sim] 模拟已启动！当前网格尺寸： " + gridWidth + "x" + gridHeight + "x" + gridDepth + ", 总人数: " + personCount);
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
        if (gridWidth > 0 && gridHeight > 0) {
            fireSourcePositions.add(new int[]{gridWidth / 4, gridHeight / 4, 0});
        } else {
            fireSourcePositions.add(new int[]{5, 5, 0});
        }
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
    public void stop() { super.finish(); }
    public int getActivePersonCount() { int cnt = 0; for (Person p : persons) if (p != null && p.alive && !p.escaped) cnt++; return cnt; }
    public int getDeadCount() { return deadByFire + deadBySmoke; }
    public int getDeadByFire() { return deadByFire; }
    public int getDeadBySmoke() { return deadBySmoke; }
    public int getEscapedCount() { int cnt = 0; for (Person p : persons) if (p != null && p.escaped) cnt++; return cnt; }

    public static void main(String[] args) { doLoop(FireEscape.class, args); System.exit(0); }

    // ================================================================
    // ★ 静态内部类：SemanticInfo (JSON 语义解析器)
    // ================================================================
    public static class SemanticInfo {
        public int floorCount = 0;
        public List<int[]> stairPositions = new ArrayList<>();

        public static SemanticInfo loadFromJson(String filePath) throws IOException {
            if (!Files.exists(Paths.get(filePath))) return new SemanticInfo();
            String content = new String(Files.readAllBytes(Paths.get(filePath)));
            SemanticInfo info = new SemanticInfo();
            Pattern floorPattern = Pattern.compile("\"floors\"\\s*:\\s*(\\d+)");
            Matcher fm = floorPattern.matcher(content);
            if (fm.find()) info.floorCount = Integer.parseInt(fm.group(1));
            Pattern stairPattern = Pattern.compile("\\[(\\d+),(\\d+)\\]");
            Matcher sm = stairPattern.matcher(content);
            while (sm.find()) {
                int x = Integer.parseInt(sm.group(1));
                int y = Integer.parseInt(sm.group(2));
                info.stairPositions.add(new int[]{x, y});
            }
            return info;
        }
    }
}