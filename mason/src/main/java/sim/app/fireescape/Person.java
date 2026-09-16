package sim.app.fireescape;

import sim.engine.*;
import sim.util.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.WeakHashMap;

public class Person implements Steppable {
    private static final long serialVersionUID = 1L;
    private static final int MIN_FLOOR_SUPPORT_CELLS = 25;
    private static final double MIN_STORY_HEIGHT_METERS = 2.5;
    private static final double MAX_STORY_HEIGHT_METERS = 6.0;
    private static final double MIN_SPAWN_HEADROOM_METERS = 1.8;
    private static final int STAIR_CLEARANCE_CELLS = 1;
    private static final double STAIRWELL_SEARCH_RADIUS_METERS = 4.0;
    private static final int STAIRWELL_SPAWN_INTERVAL = 4;
    private static final double MAX_ROUTE_SMOKE = 0.8;
    private static final double MAX_ROUTE_TEMPERATURE = FireEscape.T_DEATH - 20.0;
    private static final double SMOKE_ROUTE_COST = 40.0;
    private static final double HEAT_ROUTE_COST = 20.0;
    private static final double OCCUPIED_CELL_COST = 12.0;
    private static final double QUEUED_PERSON_COST = 8.0;
    private static final double STAIR_SWITCH_RATIO = 0.8;
    private static final int BLOCKED_REPLAN_ATTEMPTS = 8;
    private static final int ROUTE_LOCK_TICKS = 20;
    private static final int NO_PATH_RETRY_TICKS = 10;

    // ===== 伤害剂量参数（按 SFPE 消防工程手册 / Purser FED 烟气毒性模型标定）=====
    // 每个时间步 deltaT=0.5s，即每分钟 120 tick；剂量 0→1，攒满即死亡。
    // 烟气毒性：满烟（代理 CO≈8000ppm）约 8 分钟（960 tick）致死；速率与烟浓度
    // 平方成正比（烟浓一倍、毒性涨速四倍）；烟浓度低于阈值时短时穿过无害。
    private static final double SMOKE_DOSE_TICKS_AT_FULL = 960.0;
    private static final double SMOKE_DOSE_IGNORE_BELOW = 0.15;
    // 高温灼伤：70°C(343K) 以下干热空气可长时间耐受；100°C 约 7.5 分钟、
    // 150°C 约 1 分钟致死（速率按超温幅度平方累积）；≥200°C(T_DEATH) 立即死亡。
    private static final double HEAT_DOSE_T_LOW = 343.0;    // 70°C，低于此温度不累积
    private static final double HEAT_DOSE_T_SPAN = 130.0;   // 343K → 473K(200°C)
    private static final double HEAT_DOSE_RATE_DIVISOR = 48.0;
    // 能见度减速（Jin 烟中行走实验）：能见度(米)≈1/烟浓度，速度从 10 米全速
    // 线性降到 1 米时的 30%（摸黑扶墙挪步，但不会完全停下）。
    private static final double VIS_SPEED_MIN = 0.3;
    private static final double VIS_CLEAR_METERS = 10.0;

    private static final Map<FireEscape, NavigationData> NAVIGATION_CACHE =
            Collections.synchronizedMap(new WeakHashMap<FireEscape, NavigationData>());

    public boolean alive;
    public boolean escaped;
    public String deathCause;
    // 两种累积伤害剂量（0=无, 1=致死），只增不减：CO 吸入后血液半衰期长达数小时，
    // 灼伤损伤同样不可逆，离开危险区只会停止累积、不会消退。
    private double toxicDose;   // 烟气毒性剂量（FED）
    private double heatDose;    // 高温灼伤剂量
    private int x, y, z;
    private FireEscape model;
    private int moveCooldown = 0;
    private EvacuationPhase evacuationPhase;
    private EvacuationPhase phaseBeforeQueue;
    private EvacuationPhase phaseBeforeNoSafePath;
    private int currentFloorIndex = -1;
    private int targetFloorIndex = -1;
    private int selectedStairId = -1;
    private int selectedDoorPortalId = -1;
    private int selectedDoorFloorZ = -1;
    private int blockedMoveAttempts = 0;
    private int routeLockTicks = 0;
    private int noPathRetryTicks = 0;
    private final ArrayDeque<int[]> route = new ArrayDeque<>();
    private transient NavigationData navigation;

    public Person(FireEscape model, int x, int y, int z) {
        this.model = model;
        this.x = x;
        this.y = y;
        this.z = z;
        this.alive = true;
        this.escaped = false;
        this.toxicDose = 0.0;
        this.heatDose = 0.0;
        this.deathCause = null;
        this.evacuationPhase = null;
        this.phaseBeforeQueue = null;
        this.phaseBeforeNoSafePath = null;
    }

    public static boolean canOccupy(FireEscape model, int x, int y, int z) {
        if (x < 0 || x >= model.gridWidth ||
                y < 0 || y >= model.gridHeight ||
                z < 0 || z >= model.gridDepth ||
                model.insideGrid.get(x, y, z) != 1) {
            return false;
        }
        int type = model.obstacleGrid.get(x, y, z);
        return type == FireEscape.TYPE_EMPTY ||
                type == FireEscape.TYPE_DOOR ||
                type == FireEscape.TYPE_EXIT ||
                type == FireEscape.TYPE_STAIR;
    }

    public static boolean isValidSpawnCell(FireEscape model, int x, int y, int z) {
        if (x < 0 || x >= model.gridWidth ||
                y < 0 || y >= model.gridHeight ||
                z <= 0 || z >= model.gridDepth ||
                !hasStandingHeadroom(model, x, y, z)) {
            return false;
        }
        if (model.fireGrid.get(x, y, z) > FireEscape.T_AMBIENT + 50) return false;
        return hasFloorSupport(model, x, y, z);
    }

    /**
     * 检查人物从脚底到 1.8 米高度的完整空间。
     * 不能只检查 z 和 z+1，否则人物可能出生在楼梯底部等净空不足的位置。
     */
    private static boolean hasStandingHeadroom(FireEscape model, int x, int y, int z) {
        int headroomCells = verticalMetersToCells(model, MIN_SPAWN_HEADROOM_METERS);
        if (z + headroomCells > model.gridDepth) return false;

        for (int dz = 0; dz < headroomCells; dz++) {
            int bodyZ = z + dz;
            if (model.insideGrid.get(x, y, bodyZ) != 1 ||
                    model.obstacleGrid.get(x, y, bodyZ) != FireEscape.TYPE_EMPTY) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasGeometricHeadroom(FireEscape model, int x, int y, int z) {
        int headroomCells = verticalMetersToCells(model, MIN_SPAWN_HEADROOM_METERS);
        if (z + headroomCells > model.gridDepth) return false;

        for (int dz = 0; dz < headroomCells; dz++) {
            if (model.obstacleGrid.get(x, y, z + dz) != FireEscape.TYPE_EMPTY) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasFloorSupport(FireEscape model, int x, int y, int z) {
        int below = model.obstacleGrid.get(x, y, z - 1);

        // 出生点脚下必须是楼板。楼梯虽然允许人员移动，但不能作为出生支撑，
        // 这样可同时排除楼梯踏步和楼梯实体顶部的出生点。
        if (below != FireEscape.TYPE_WALL) return false;

        // 楼板在水平方向应有连续面积，可排除内墙顶部等狭窄支撑面。
        int supportCount = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int sx = x + dx;
                int sy = y + dy;
                if (sx < 0 || sx >= model.gridWidth || sy < 0 || sy >= model.gridHeight) continue;
                int supportType = model.obstacleGrid.get(sx, sy, z - 1);
                if (supportType == FireEscape.TYPE_WALL) {
                    supportCount++;
                }
            }
        }
        return supportCount >= 5;
    }

    /**
     * 在所有合法站立格中按楼层轮询取点。
     *
     * 修改逻辑：
     * 1. 从楼板支撑面积和上方顶板自动识别真实楼层，避免把屋顶当成出生层。
     * 2. 最低层若因 IFC 缺少基底楼板而被 3D 室外洪水填充排除，只修复同时满足
     *    “二维封闭、上方有顶板”的区域，防止室外平台被重新加入。
     * 3. 每层只使用相邻楼层之间的 TYPE_STAIR 体素识别楼梯间，并沿室内空格做
     *    受墙体约束的距离扩展，图中的平整平台因此可以进入出生候选池。
     * 4. 楼梯安全检查只覆盖人物实际占用的 1.8 米三维空间和周围 1 个体素；
     *    不再用整栋楼梯的二维投影排除楼梯上方、下方具有足够净空的平地。
     * 5. 按楼层轮询分配人员，并定期优先使用楼梯间候选点，保证四层分布均衡。
     */
    public static Steppable[] spawnPopulation(FireEscape model) {
        model.personGrid.clear();
        model.persons = new Person[model.personCount];

        List<Integer> detectedFloors = detectValidFloors(model);
        if (detectedFloors.isEmpty()) {
            System.err.println("[Person] 未识别到有楼板支撑和顶板覆盖的出生楼层");
            return new Steppable[0];
        }

        // 当前 IFC 的首层基底可能没有被建模成 IfcSlab。这里只对最低真实楼层做
        // 封闭区修复，且要求上方存在顶板，不会把阳台、雨棚或露天平台当作室内。
        repairLowestFloorForSpawning(model, detectedFloors.get(0));

        Map<Integer, SpawnCandidates> candidatesByFloor = new TreeMap<>();
        Map<Integer, Integer> repairedStairwellByFloor = new TreeMap<>();
        int candidateCount = 0;
        int stairwellCandidateCount = 0;
        for (int floorIndex = 0; floorIndex < detectedFloors.size(); floorIndex++) {
            int z = detectedFloors.get(floorIndex);
            int lowerStairZ = floorIndex > 0 ? detectedFloors.get(floorIndex - 1) : z;
            int upperStairZ = floorIndex + 1 < detectedFloors.size() ?
                    detectedFloors.get(floorIndex + 1) : z;
            int[] stairwellDistances = buildFloorStairwellDistanceMap(
                    model, z, lowerStairZ, upperStairZ);
            int repairedStairwellCells = repairStairwellPlatformsForSpawning(
                    model, z, stairwellDistances);
            if (repairedStairwellCells > 0) {
                repairedStairwellByFloor.put(z, repairedStairwellCells);
            }

            SpawnCandidates floorCandidates = new SpawnCandidates();
            for (int x = 0; x < model.gridWidth; x++) {
                for (int y = 0; y < model.gridHeight; y++) {
                    if (!isValidSpawnCell(model, x, y, z)) continue;

                    // 只排除人物身体实际会碰到的楼梯，不再排除同一二维投影上
                    // 位于其他高度的楼梯，因此楼梯下方净空足够的平地仍可出生。
                    if (!hasStairBodyClearance(model, x, y, z)) continue;

                    int[] position = new int[]{x, y, z};
                    int stairwellDistance =
                            stairwellDistances[y * model.gridWidth + x];
                    if (stairwellDistance >= 0) {
                        floorCandidates.stairwell.add(position);
                        stairwellCandidateCount++;
                    } else {
                        floorCandidates.regular.add(position);
                    }
                    candidateCount++;
                }
            }
            if (floorCandidates.size() > 0) {
                candidatesByFloor.put(z, floorCandidates);
            }
        }

        if (candidateCount == 0) {
            System.err.println("[Person] 未找到同时满足室内、楼板支撑和净空条件的出生点");
            return new Steppable[0];
        }

        int spawnCount = Math.min(model.personCount, candidateCount);
        Steppable[] agents = new Steppable[spawnCount];
        List<Integer> floors = new ArrayList<>(candidatesByFloor.keySet());
        NavigationData navigation = buildNavigationData(model, floors);
        NAVIGATION_CACHE.put(model, navigation);
        int floorCursor = model.random.nextInt(floors.size());
        Map<Integer, Integer> spawnedByFloor = new TreeMap<>();
        Map<Integer, Integer> spawnedInStairwell = new TreeMap<>();

        for (int i = 0; i < spawnCount; i++) {
            int checkedFloors = 0;
            int floorZ = floors.get(floorCursor);
            SpawnCandidates floorCandidates = candidatesByFloor.get(floorZ);
            while (floorCandidates.isEmpty() && checkedFloors < floors.size()) {
                floorCursor = (floorCursor + 1) % floors.size();
                floorZ = floors.get(floorCursor);
                floorCandidates = candidatesByFloor.get(floorZ);
                checkedFloors++;
            }

            int floorSpawnedCount = spawnedByFloor.containsKey(floorZ) ?
                    spawnedByFloor.get(floorZ) : 0;
            boolean preferStairwell = floorSpawnedCount % STAIRWELL_SPAWN_INTERVAL == 0;
            List<int[]> selectedPool = preferStairwell && !floorCandidates.stairwell.isEmpty() ?
                    floorCandidates.stairwell : floorCandidates.regular;
            if (selectedPool.isEmpty()) {
                selectedPool = floorCandidates.stairwell;
            }

            int[] position = selectedPool.remove(model.random.nextInt(selectedPool.size()));
            Person person = new Person(model, position[0], position[1], position[2]);
            person.navigation = navigation;
            person.initializeEvacuationState();
            model.persons[i] = person;
            model.personGrid.setObjectLocation(person, position[0], position[1], position[2]);
            agents[i] = person;
            spawnedByFloor.put(floorZ, floorSpawnedCount + 1);
            if (selectedPool == floorCandidates.stairwell) {
                int currentCount = spawnedInStairwell.containsKey(floorZ) ?
                        spawnedInStairwell.get(floorZ) : 0;
                spawnedInStairwell.put(floorZ, currentCount + 1);
            }
            floorCursor = (floorCursor + 1) % floors.size();
        }

        if (spawnCount < model.personCount) {
            System.err.println("[Person] 合法出生点不足，仅生成 " + spawnCount +
                    "/" + model.personCount + " 人");
        }
        System.out.println("[Person] 出生楼层=" + floors +
                ", 合法出生点=" + candidateCount +
                ", 楼梯间候选点=" + stairwellCandidateCount +
                ", 楼梯间室内修复=" + repairedStairwellByFloor +
                ", 楼梯组件=" + navigation.stairwells.size() +
                ", 疏散出口=" + navigation.exitTargets.size() +
                ", 楼层分布=" + spawnedByFloor +
                ", 楼梯间分布=" + spawnedInStairwell +
                ", 已生成人员=" + spawnCount);
        return agents;
    }

    /**
     * 封闭楼梯间室内状态修复的独立入口（供门户修复在人员出生前调用）。
     * IFC 楼梯井常被 3D 室外洪水漏标为室外（楼梯间内部空气 insideGrid=0）。
     * 若不先修复，门户修复的行走掩码会缺失楼梯间内部空气：楼梯体素成为
     * 互不相连的穿行孤岛，走廊翼区到楼梯的穿墙代价超上限，导致所有楼层的
     * 门户开凿失败（顶层还会因楼梯足迹无重叠而整层跳过）。
     * 这里复用出生流程的楼梯间平台修复逻辑；spawnPopulation 内会再次执行，
     * insideGrid 已为 1 的格不会有任何改动，幂等安全。
     */
    public static void repairEnclosedStairwellIndoorStatus(FireEscape model) {
        List<Integer> floors = detectValidFloors(model);
        int totalRepaired = 0;
        for (int floorIndex = 0; floorIndex < floors.size(); floorIndex++) {
            int z = floors.get(floorIndex);
            int lowerStairZ = floorIndex > 0 ? floors.get(floorIndex - 1) : z;
            int upperStairZ = floorIndex + 1 < floors.size() ?
                    floors.get(floorIndex + 1) : z;
            int[] stairwellDistances = buildFloorStairwellDistanceMap(
                    model, z, lowerStairZ, upperStairZ);
            totalRepaired += repairStairwellPlatformsForSpawning(
                    model, z, stairwellDistances);
        }
        if (totalRepaired > 0) {
            System.out.println("[Person] 楼梯间室内预修复: 共将 " + totalRepaired +
                    " 个被误标为室外的楼梯间空气格改回室内（供门户修复使用）");
        }
    }

    // ===== 走廊-楼梯间交汇处门洞恢复 =====
    // IFC 体素化时，走廊和楼梯间交界处本应是门洞/开口的格子可能被误标为
    // TYPE_WALL。以下方法不"凿穿"墙体，而是识别这些物理交汇格（一侧邻
    // 走廊可行走区、另一侧邻楼梯间可行走区），将其类型恢复为 TYPE_DOOR，
    // 使人员能从走廊自然走到楼梯间，而不会在非交汇处造出不合理的门洞。

    /**
     * 交汇处射线扫描穿透墙体的最大厚度（格）：与 FireEscape 的
     * MAX_DOORWAY_WALL_THICKNESS 一致，设为独立常量以保持 Person 修复
     * 逻辑自包含。
     */
    private static final int JUNCTION_MAX_WALL_THICKNESS = 4;

    /**
     * 打通各层走廊与楼梯间的物理交汇处。幂等：门洞恢复后原墙格已变为
     * TYPE_DOOR（可通行格），再次调用时两侧已连通，不会重复开洞。
     */
    public static void repairStairwellJunctions(FireEscape model) {
        if (model.obstacleGrid == null || model.gridWidth <= 0 ||
                model.gridHeight <= 0) return;
        List<Integer> walkingLevels = detectValidFloors(model);
        if (walkingLevels.isEmpty()) {
            System.out.println("[交汇处修复] 未识别到楼层行走面，跳过");
            return;
        }
        System.out.println("[交汇处修复] 识别到楼层行走面 z=" + walkingLevels);
        // 顶层楼梯间足迹继承：顶层楼梯止于下层，站立带内无楼梯格时，
        // 靠下层楼梯间足迹重叠来识别楼梯间连通域。
        boolean[] inheritedStairFootprint = null;
        for (int z0 : walkingLevels) {
            inheritedStairFootprint = repairFloorJunctions(model, z0, inheritedStairFootprint);
        }
    }

    /**
     * 修复单层交汇处：迭代"识别连通域→找交界墙格→恢复为门"，
     * 直到所有交汇格都已打通或无新门洞产生。返回本层楼梯间足迹供上层继承。
     */
    private static boolean[] repairFloorJunctions(FireEscape model,
                                                   int z0,
                                                   boolean[] inheritedFootprint) {
        int width = model.gridWidth;
        int height = model.gridHeight;

        for (int iteration = 0; iteration < 8; iteration++) {
            // 构建行走掩码：标记每格是否可行走
            boolean[] walkMask = buildJunctionWalkMask(model, z0);
            // 4 邻接连通域标记
            int[] zoneByCell = labelJunctionZones(walkMask, width, height);
            int zoneCount = maxJunctionZoneId(zoneByCell) + 1;
            if (zoneCount == 0) return inheritedFootprint;

            // 识别楼梯间连通域：包含最多"楼梯可站立格"的区域
            int stairZone = findJunctionStairZone(model, zoneByCell, zoneCount, z0);
            if (stairZone < 0 && inheritedFootprint != null) {
                // 顶层无楼梯格时，靠下层楼梯间足迹重叠识别
                stairZone = findZoneOverlappingFootprint(
                        zoneByCell, zoneCount, width, height, inheritedFootprint);
            }
            if (stairZone < 0) {
                System.out.println("[交汇处修复] z=" + z0 + " 未识别到楼梯间连通域，跳过");
                return inheritedFootprint;
            }
            // 记录本层楼梯间足迹供上层继承
            inheritedFootprint = collectJunctionZoneMask(
                    zoneByCell, stairZone, width, height);

            // 找交界格：不在行走掩码中（非可行走），且沿 4 方向射线穿透墙体
            // 后两侧到达不同连通域。IFC 体素化后墙体常为 2+ 格厚，4 邻域只
            // 能看到 1 格距离，无法发现 2 格墙另一侧的连通域；改用射线扫描
            // （最多 MAX_DOORWAY_WALL_THICKNESS 格）穿透整面墙后判定。
            // 两侧分别为楼梯间连通域和非楼梯间连通域时，把整条墙段恢复为
            // 门洞（情况1）或恢复室内空气标记（情况2）。
            int opened = 0;
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    if (walkMask[index]) continue;  // 已可行走的格子不是交界格
                    int type = model.obstacleGrid.get(x, y, z0);
                    // 只处理墙/窗/室外空气格；柱体不凿穿
                    if (type != FireEscape.TYPE_WALL &&
                            type != FireEscape.TYPE_WINDOW &&
                            type != FireEscape.TYPE_EMPTY) continue;

                    // 沿 4 方向射线扫描，记录到达的连通域
                    boolean hasStairNeighbor = false;
                    boolean hasCorridorNeighbor = false;
                    int[] stairDir = null;  // 记录到达楼梯间连通域的方向
                    for (int[] dir : dirs) {
                        for (int step = 1;
                             step <= JUNCTION_MAX_WALL_THICKNESS; step++) {
                            int nx = x + dir[0] * step;
                            int ny = y + dir[1] * step;
                            if (nx < 0 || nx >= width ||
                                    ny < 0 || ny >= height) break;
                            int nIndex = ny * width + nx;
                            if (!walkMask[nIndex]) continue;  // 墙内继续穿透
                            // 到达可行走格，判定连通域归属
                            if (zoneByCell[nIndex] == stairZone) {
                                hasStairNeighbor = true;
                                if (stairDir == null) stairDir = dir;
                            } else if (zoneByCell[nIndex] >= 0) {
                                hasCorridorNeighbor = true;
                            }
                            break;  // 到达可行走格，停止此方向射线
                        }
                    }
                    if (!hasStairNeighbor || !hasCorridorNeighbor) continue;

                    if (type == FireEscape.TYPE_WALL ||
                            type == FireEscape.TYPE_WINDOW) {
                        // 情况1：墙体被误标，恢复为门洞。
                        // 沿到达楼梯间的方向把整条墙段都改为门洞，
                        // 保证 2+ 格厚墙也能贯通。
                        openJunctionDoorwayRay(model, x, y, z0,
                                stairDir, JUNCTION_MAX_WALL_THICKNESS);
                        opened++;
                    } else if (type == FireEscape.TYPE_EMPTY &&
                            model.insideGrid.get(x, y, z0) != 1) {
                        // 情况2：空气被误标为室外，恢复室内标记。
                        // 竖向恢复：从 z0 向上，将连续的室外空气格改回室内，
                        // 提供站立净空，遇到墙/楼板停止。
                        restoreJunctionIndoor(model, x, y, z0);
                        opened++;
                    }
                }
            }
            if (opened == 0) {
                System.out.println("[交汇处修复] z=" + z0 + " 交汇处已全部打通"
                        + (iteration == 0 ? "（无需修复）" :
                        "，共恢复 " + iteration + " 批门洞"));
                return inheritedFootprint;
            }
            System.out.println("[交汇处修复] z=" + z0 + " 恢复交汇通道 " + opened + " 处"
                    + "（恢复后自动重扫剩余区域）");
        }
        return inheritedFootprint;
    }

    /**
     * 行走掩码：楼梯/门/出口格可行走；空气格在室内、二维边界洪水确认封闭、
     * 或楼梯顶面上方时可行走。与 NavigationData 的 floorWalkable 判定一致：
     * 同时接受 3D insideGrid==1 和二维 planarInterior，否则被 3D 洪水误标为
     * 室外的房间格会被排除在掩码外，房间不形成独立连通域，房间-走廊交界墙
     * 的 4 邻域只有走廊一侧是可行走格，不满足"两侧不同连通域"的交界条件，
     * 导致交汇处修复漏掉所有房间-走廊门洞。
     */
    private static boolean[] buildJunctionWalkMask(FireEscape model, int z0) {
        int width = model.gridWidth;
        int height = model.gridHeight;
        boolean[] planarInterior = buildPlanarInteriorMask(model, z0);
        boolean[] mask = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int type = model.obstacleGrid.get(x, y, z0);
                if (type == FireEscape.TYPE_STAIR ||
                        type == FireEscape.TYPE_DOOR ||
                        type == FireEscape.TYPE_EXIT) {
                    mask[y * width + x] = true;
                } else if (type == FireEscape.TYPE_EMPTY) {
                    boolean aboveStair = z0 > 0 &&
                            model.obstacleGrid.get(x, y, z0 - 1) == FireEscape.TYPE_STAIR;
                    // 与 NavigationData.floorWalkable 一致：3D 室内标记或
                    // 二维边界洪水确认封闭的空格都算可行走，避免房间被 3D
                    // 洪水误标为室外时漏修复房间-走廊交界墙。
                    mask[y * width + x] =
                            model.insideGrid.get(x, y, z0) == 1 ||
                                    planarInterior[y * width + x] || aboveStair;
                }
            }
        }
        return mask;
    }

    /** 4 邻接连通域标记，返回每格所属组件号（未通行格为 -1）。 */
    private static int[] labelJunctionZones(boolean[] walkMask,
                                            int width, int height) {
        int[] zoneByCell = new int[width * height];
        Arrays.fill(zoneByCell, -1);
        int[] queue = new int[width * height];
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int zoneId = 0;
        for (int start = 0; start < walkMask.length; start++) {
            if (!walkMask[start] || zoneByCell[start] >= 0) continue;
            int head = 0, tail = 0;
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

    private static int maxJunctionZoneId(int[] zoneByCell) {
        int max = -1;
        for (int z : zoneByCell) max = Math.max(max, z);
        return max;
    }

    /** 识别楼梯间连通域：包含最多"脚层是楼梯或脚层空气正下方是楼梯"格的区域。 */
    private static int findJunctionStairZone(FireEscape model,
                                             int[] zoneByCell,
                                             int zoneCount, int z0) {
        int width = model.gridWidth;
        int height = model.gridHeight;
        int[] zoneStairCells = new int[zoneCount];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int zoneId = zoneByCell[y * width + x];
                if (zoneId < 0) continue;
                boolean onStair =
                        model.obstacleGrid.get(x, y, z0) == FireEscape.TYPE_STAIR ||
                                (z0 > 0 && model.obstacleGrid.get(x, y, z0 - 1) == FireEscape.TYPE_STAIR);
                if (onStair) zoneStairCells[zoneId]++;
            }
        }
        int stairZone = -1, best = 0;
        for (int z = 0; z < zoneCount; z++) {
            if (zoneStairCells[z] > best) {
                best = zoneStairCells[z];
                stairZone = z;
            }
        }
        return stairZone;
    }

    /** 在下层楼梯间足迹中找重叠最多的区域，用于顶层无楼梯格时识别楼梯间。 */
    private static int findZoneOverlappingFootprint(int[] zoneByCell,
                                                    int zoneCount,
                                                    int width, int height,
                                                    boolean[] footprint) {
        int[] overlap = new int[zoneCount];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (footprint.length > index && footprint[index]) {
                    int zoneId = zoneByCell[index];
                    if (zoneId >= 0) overlap[zoneId]++;
                }
            }
        }
        int bestZone = -1, bestOverlap = 0;
        for (int z = 0; z < zoneCount; z++) {
            if (overlap[z] > bestOverlap) {
                bestOverlap = overlap[z];
                bestZone = z;
            }
        }
        return bestZone;
    }

    private static boolean[] collectJunctionZoneMask(int[] zoneByCell,
                                                     int targetZone,
                                                     int width, int height) {
        boolean[] mask = new boolean[width * height];
        for (int i = 0; i < zoneByCell.length; i++) {
            if (zoneByCell[i] == targetZone) mask[i] = true;
        }
        return mask;
    }

    /**
     * 恢复交汇处门洞：从脚层向上改写 WALL/WINDOW 为 TYPE_DOOR，提供站立
     * 净空；遇到"上方是空气的墙"（楼板天花）立即停止，保证不凿穿楼层间隔。
     */
    private static void openJunctionDoorway(FireEscape model,
                                            int x, int y, int z0) {
        int maxZ = Math.min(z0 + 7, model.gridDepth - 1);
        for (int z = z0; z <= maxZ; z++) {
            int type = model.obstacleGrid.get(x, y, z);
            if (type != FireEscape.TYPE_WALL &&
                    type != FireEscape.TYPE_WINDOW) break;
            // 天花判定：当前墙格上方是空气 → 这是楼板下表面，改写后停止
            if (z > z0 && z + 1 < model.gridDepth) {
                int aboveType = model.obstacleGrid.get(x, y, z + 1);
                if (aboveType == FireEscape.TYPE_EMPTY) {
                    model.obstacleGrid.set(x, y, z, FireEscape.TYPE_DOOR);
                    break;
                }
            }
            model.obstacleGrid.set(x, y, z, FireEscape.TYPE_DOOR);
        }
    }

    /**
     * 沿指定方向射线开凿门洞：从 (x,y) 沿 dir 方向逐格改写 WALL/WINDOW 为
     * TYPE_DOOR，直到到达非墙格或超出 maxStep。处理 IFC 体素化中 2+ 格厚墙：
     * 整条墙段都改为门洞，保证走廊与房间之间贯通。竖向仍复用 openJunctionDoorway
     * 的天花停止逻辑，不凿穿楼板。
     */
    private static void openJunctionDoorwayRay(FireEscape model,
                                               int x, int y, int z0,
                                               int[] dir, int maxStep) {
        for (int step = 0; step <= maxStep; step++) {
            int cx = x + dir[0] * step;
            int cy = y + dir[1] * step;
            if (cx < 0 || cx >= model.gridWidth ||
                    cy < 0 || cy >= model.gridHeight) break;
            int type = model.obstacleGrid.get(cx, cy, z0);
            if (type != FireEscape.TYPE_WALL &&
                    type != FireEscape.TYPE_WINDOW) break;  // 到达非墙格停止
            // 竖向开凿此格门洞（含站立净空 + 天花停止）
            openJunctionDoorway(model, cx, cy, z0);
        }
    }

    /**
     * 恢复交汇处室内空气标记：从脚层向上，将连续的"室外空气格"
     * （TYPE_EMPTY 且 insideGrid != 1）改回室内（insideGrid = 1），
     * 提供站立净空，使走廊-楼梯间交汇处的开口被正确识别为可通行。
     * 遇到墙/楼板（非 EMPTY）立即停止；上方是墙时作为天花停止，
     * 避免竖向穿透到上层楼板。
     */
    private static void restoreJunctionIndoor(FireEscape model,
                                              int x, int y, int z0) {
        int maxZ = Math.min(z0 + 7, model.gridDepth - 1);
        for (int z = z0; z <= maxZ; z++) {
            int type = model.obstacleGrid.get(x, y, z);
            if (type != FireEscape.TYPE_EMPTY) break;  // 遇到墙/楼板停止
            model.insideGrid.set(x, y, z, 1);          // 恢复为室内
            // 天花判定：当前空气格上方是墙 → 这是楼板下表面，改写后停止
            if (z + 1 < model.gridDepth &&
                    model.obstacleGrid.get(x, y, z + 1) == FireEscape.TYPE_WALL) {
                break;
            }
        }
    }

    /**
     * 按连续楼板支撑面积识别站立层，再用上方大面积结构层排除屋顶。
     * 相邻高度的小支撑面会被归入同一组，只保留支撑面积最大的 z 层。
     * 设为 public 供 FireEscape 的楼梯间门户修复复用：门户开凿与人员出生
     * 必须基于同一套楼层基准，避免两套启发式对楼层的判定出现分歧。
     */
    public static List<Integer> detectValidFloors(FireEscape model) {
        int[] supportCounts = new int[model.gridDepth];
        for (int z = 1; z + 1 < model.gridDepth; z++) {
            for (int x = 0; x < model.gridWidth; x++) {
                for (int y = 0; y < model.gridHeight; y++) {
                    if (hasSpawnGeometry(model, x, y, z)) {
                        supportCounts[z]++;
                    }
                }
            }
        }

        int minimumStoryGap = metersToCells(model, MIN_STORY_HEIGHT_METERS);
        List<Integer> floorPeaks = new ArrayList<>();
        for (int z = 1; z + 1 < model.gridDepth; z++) {
            if (supportCounts[z] < MIN_FLOOR_SUPPORT_CELLS) continue;

            if (floorPeaks.isEmpty() ||
                    z - floorPeaks.get(floorPeaks.size() - 1) >= minimumStoryGap) {
                floorPeaks.add(z);
            } else {
                int previousZ = floorPeaks.get(floorPeaks.size() - 1);
                if (supportCounts[z] > supportCounts[previousZ]) {
                    floorPeaks.set(floorPeaks.size() - 1, z);
                }
            }
        }

        List<Integer> floors = new ArrayList<>();
        for (int z : floorPeaks) {
            if (findCeilingLayerAbove(model, z) >= 0) {
                floors.add(z);
            }
        }
        return floors;
    }

    /**
     * 只检查出生所需的实体条件，不读取 insideGrid。
     * 该检查用于楼层识别，因为最低层的 insideGrid 可能尚待封闭区修复。
     */
    private static boolean hasSpawnGeometry(FireEscape model, int x, int y, int z) {
        if (z <= 0 || z >= model.gridDepth ||
                !hasGeometricHeadroom(model, x, y, z)) {
            return false;
        }
        return hasFloorSupport(model, x, y, z);
    }

    private static int findCeilingLayerAbove(FireEscape model, int floorZ) {
        int minimumOffset = Math.max(2, metersToCells(model, 2.0));
        int maximumOffset = metersToCells(model, MAX_STORY_HEIGHT_METERS);
        int minimumCeilingArea = Math.max(
                MIN_FLOOR_SUPPORT_CELLS,
                (model.gridWidth * model.gridHeight) / 5);

        for (int z = floorZ + minimumOffset;
             z <= Math.min(model.gridDepth - 1, floorZ + maximumOffset);
             z++) {
            int structuralCount = 0;
            for (int x = 0; x < model.gridWidth; x++) {
                for (int y = 0; y < model.gridHeight; y++) {
                    int type = model.obstacleGrid.get(x, y, z);
                    if (type == FireEscape.TYPE_WALL || type == FireEscape.TYPE_COLUMN) {
                        structuralCount++;
                    }
                }
            }
            if (structuralCount >= minimumCeilingArea) return z;
        }
        return -1;
    }

    private static void repairLowestFloorForSpawning(FireEscape model, int floorZ) {
        int ceilingZ = findCeilingLayerAbove(model, floorZ);
        if (ceilingZ < 0) return;

        boolean[] planarInterior = buildPlanarInteriorMask(model, floorZ);
        int inferredFloorCells = 0;
        int repairedInsideCells = 0;

        for (int x = 0; x < model.gridWidth; x++) {
            for (int y = 0; y < model.gridHeight; y++) {
                int index = y * model.gridWidth + x;
                if (!planarInterior[index] ||
                        !hasGeometricHeadroom(model, x, y, floorZ) ||
                        !hasOverheadCover(model, x, y, floorZ, ceilingZ)) {
                    continue;
                }

                int below = model.obstacleGrid.get(x, y, floorZ - 1);
                if (below == FireEscape.TYPE_EMPTY) {
                    // IFC 常省略首层与地面重合的基底板。仅在二维封闭且有顶板覆盖的
                    // 最低层补一格虚拟楼板，使出生和后续重力校正使用同一支撑规则。
                    model.obstacleGrid.set(x, y, floorZ - 1, FireEscape.TYPE_WALL);
                    model.insideGrid.set(x, y, floorZ - 1, 0);
                    inferredFloorCells++;
                } else if (below != FireEscape.TYPE_WALL) {
                    continue;
                }

                if (model.insideGrid.get(x, y, floorZ) == 0) repairedInsideCells++;
                // 首层修复必须覆盖完整人物高度，否则新增的 1.8 米净空检查会因
                // 上部格仍被 3D 洪水标记为室外而错误排除合法首层出生点。
                int headroomCells = verticalMetersToCells(model, MIN_SPAWN_HEADROOM_METERS);
                for (int dz = 0; dz < headroomCells; dz++) {
                    model.insideGrid.set(x, y, floorZ + dz, 1);
                }
            }
        }

        int headroomCells = verticalMetersToCells(model, MIN_SPAWN_HEADROOM_METERS);
        for (int dz = 0; dz < headroomCells; dz++) {
            restoreFloorTransitions(model, floorZ + dz);
        }
        System.out.println("[Person] 首层封闭区修复: z=" + floorZ +
                ", 补充楼板=" + inferredFloorCells +
                ", 修复室内格=" + repairedInsideCells);
    }

    /**
     * 对单个站立层做二维边界洪水填充。门、出口和楼梯暂时作为边界，
     * 因此正常开口不会把整个首层误判为室外；边界可达空气仍保持室外。
     */
    private static boolean[] buildPlanarInteriorMask(FireEscape model, int z) {
        int width = model.gridWidth;
        int height = model.gridHeight;
        boolean[] outside = new boolean[width * height];
        boolean[] interior = new boolean[width * height];
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x != 0 && x != width - 1 && y != 0 && y != height - 1) continue;
                int index = y * width + x;
                if (isPlanarFloodCell(model.obstacleGrid.get(x, y, z)) && !outside[index]) {
                    outside[index] = true;
                    queue.addLast(index);
                }
            }
        }

        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            int x = index % width;
            int y = index / width;
            for (int[] direction : directions) {
                int nx = x + direction[0];
                int ny = y + direction[1];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;

                int nextIndex = ny * width + nx;
                if (!outside[nextIndex] &&
                        isPlanarFloodCell(model.obstacleGrid.get(nx, ny, z))) {
                    outside[nextIndex] = true;
                    queue.addLast(nextIndex);
                }
            }
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                interior[index] = !outside[index] &&
                        isPlanarFloodCell(model.obstacleGrid.get(x, y, z));
            }
        }
        return interior;
    }

    private static boolean isPlanarFloodCell(int type) {
        return type == FireEscape.TYPE_EMPTY || type == FireEscape.TYPE_FUEL;
    }

    private static boolean hasOverheadCover(FireEscape model, int x, int y,
                                            int floorZ, int ceilingZ) {
        for (int z = floorZ + 2; z <= ceilingZ; z++) {
            int type = model.obstacleGrid.get(x, y, z);
            if (type == FireEscape.TYPE_WALL || type == FireEscape.TYPE_COLUMN) {
                return true;
            }
        }
        return false;
    }

    private static void restoreFloorTransitions(FireEscape model, int z) {
        for (int x = 0; x < model.gridWidth; x++) {
            for (int y = 0; y < model.gridHeight; y++) {
                int type = model.obstacleGrid.get(x, y, z);
                if (type != FireEscape.TYPE_DOOR &&
                        type != FireEscape.TYPE_EXIT &&
                        type != FireEscape.TYPE_STAIR) {
                    continue;
                }
                if (hasHorizontalInteriorNeighbor(model, x, y, z)) {
                    model.insideGrid.set(x, y, z, 1);
                }
            }
        }
    }

    private static boolean hasHorizontalInteriorNeighbor(FireEscape model, int x, int y, int z) {
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            int nx = x + direction[0];
            int ny = y + direction[1];
            if (nx >= 0 && nx < model.gridWidth &&
                    ny >= 0 && ny < model.gridHeight &&
                    model.insideGrid.get(nx, ny, z) == 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * 构建当前楼层的楼梯间平台距离场。
     *
     * 只投影当前层相邻楼层之间的楼梯，避免其他楼层的楼梯影响本层；距离扩展只能
     * 穿过本层空格，墙、柱、窗、门等实体会自然截断，因此不会穿墙进入房间。
     * 楼梯井空腔可能被 3D 室外洪水标为 outside，所以距离场不读取 insideGrid；
     * 只有最终出生候选仍必须满足严格的室内判定。
     * 距离场只负责判断“是否属于楼梯间”，是否会碰到楼梯由三维安全检查单独决定。
     */
    private static int[] buildFloorStairwellDistanceMap(FireEscape model,
                                                         int floorZ,
                                                         int lowerStairZ,
                                                         int upperStairZ) {
        int width = model.gridWidth;
        int height = model.gridHeight;
        int[] distances = new int[width * height];
        Arrays.fill(distances, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        int minimumZ = Math.max(0, Math.min(lowerStairZ, upperStairZ));
        int maximumZ = Math.min(model.gridDepth - 1,
                Math.max(lowerStairZ, upperStairZ));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean hasStairInStory = false;
                for (int z = minimumZ; z <= maximumZ; z++) {
                    if (model.obstacleGrid.get(x, y, z) == FireEscape.TYPE_STAIR) {
                        hasStairInStory = true;
                        break;
                    }
                }
                if (!hasStairInStory) continue;

                int floorType = model.obstacleGrid.get(x, y, floorZ);
                // 投影位置在本层是空地时可直接作为扩展种子；若本层正好是楼梯，
                // 也保留为种子以便找到与踏步相邻的平整平台。
                if (floorType == FireEscape.TYPE_EMPTY ||
                        floorType == FireEscape.TYPE_STAIR) {
                    int index = y * width + x;
                    distances[index] = 0;
                    queue.addLast(index);
                }
            }
        }

        int maximumDistance =
                metersToCells(model, STAIRWELL_SEARCH_RADIUS_METERS);
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            if (distances[index] >= maximumDistance) continue;

            int x = index % width;
            int y = index / width;
            for (int[] direction : directions) {
                int nx = x + direction[0];
                int ny = y + direction[1];
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;

                int nextIndex = ny * width + nx;
                if (distances[nextIndex] < 0) {
                    if (!isStairwellFloodCell(model, nx, ny, floorZ)) continue;
                    distances[nextIndex] = distances[index] + 1;
                    queue.addLast(nextIndex);
                }
            }
        }
        return distances;
    }

    private static boolean isStairwellFloodCell(FireEscape model, int x, int y, int z) {
        // 只允许空气参与扩展。门保持为边界，可避免楼梯间候选区泄漏到走廊；
        // insideGrid 留给最终出生校验，避免楼梯井空腔中断平台搜索。
        return model.obstacleGrid.get(x, y, z) == FireEscape.TYPE_EMPTY;
    }

    private static int repairStairwellPlatformsForSpawning(FireEscape model,
                                                            int floorZ,
                                                            int[] stairwellDistances) {
        boolean[] planarInterior = buildPlanarInteriorMask(model, floorZ);
        int headroomCells = verticalMetersToCells(model, MIN_SPAWN_HEADROOM_METERS);
        int repairedCells = 0;

        for (int x = 0; x < model.gridWidth; x++) {
            for (int y = 0; y < model.gridHeight; y++) {
                int index = y * model.gridWidth + x;
                if (stairwellDistances[index] < 0 ||
                        !planarInterior[index] ||
                        !hasGeometricHeadroom(model, x, y, floorZ) ||
                        !hasFloorSupport(model, x, y, floorZ)) {
                    continue;
                }

                boolean repaired = false;
                for (int dz = 0; dz < headroomCells; dz++) {
                    int bodyZ = floorZ + dz;
                    if (model.insideGrid.get(x, y, bodyZ) == 0) repaired = true;
                    model.insideGrid.set(x, y, bodyZ, 1);
                }
                if (repaired) repairedCells++;
            }
        }
        // 该修复只覆盖“二维封闭 + 楼梯局部范围 + 平整楼板 + 足够净空”的交集，
        // 因此能恢复图中的楼梯间平台，同时不会把室外平台或楼梯实体加入出生池。
        return repairedCells;
    }

    private static boolean hasStairBodyClearance(FireEscape model, int x, int y, int z) {
        int headroomCells = verticalMetersToCells(model, MIN_SPAWN_HEADROOM_METERS);
        int minimumZ = Math.max(0, z - 1);
        int maximumZ = Math.min(model.gridDepth - 1, z + headroomCells - 1);

        // 仅检查人物身体周围 1 个水平体素。其他高度的楼梯不会再通过二维投影
        // 形成 2 米禁生区，但紧贴踏步的位置仍被排除，避免模型发生视觉穿插。
        for (int dx = -STAIR_CLEARANCE_CELLS; dx <= STAIR_CLEARANCE_CELLS; dx++) {
            for (int dy = -STAIR_CLEARANCE_CELLS; dy <= STAIR_CLEARANCE_CELLS; dy++) {
                int stairX = x + dx;
                int stairY = y + dy;
                if (stairX < 0 || stairX >= model.gridWidth ||
                        stairY < 0 || stairY >= model.gridHeight) {
                    continue;
                }

                for (int stairZ = minimumZ; stairZ <= maximumZ; stairZ++) {
                    if (model.obstacleGrid.get(stairX, stairY, stairZ) ==
                            FireEscape.TYPE_STAIR) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static int metersToCells(FireEscape model, double meters) {
        double voxelSize = model.deltaX > 0.0 ? model.deltaX : 0.5;
        return Math.max(1, (int) Math.ceil(meters / voxelSize));
    }

    private static int verticalMetersToCells(FireEscape model, double meters) {
        double voxelSize = model.deltaZ > 0.0 ? model.deltaZ : 0.5;
        return Math.max(1, (int) Math.ceil(meters / voxelSize));
    }

    private static final class SpawnCandidates {
        final List<int[]> regular = new ArrayList<>();
        final List<int[]> stairwell = new ArrayList<>();

        int size() {
            return regular.size() + stairwell.size();
        }

        boolean isEmpty() {
            return size() == 0;
        }
    }

    private enum EvacuationPhase {
        SEEK_ROOM_DOOR,
        DOOR_QUEUE,
        SEEK_STAIR,
        QUEUE_FOR_STAIR,
        DESCEND_STAIR,
        SEEK_EXIT,
        EXIT_QUEUE,
        NO_SAFE_PATH
    }

    @Override
    public void step(SimState state) {
        FireEscape fm = (FireEscape) state;
        if (!fm.showPersons) return;
        if (!alive || escaped) return;

        double fire = fm.fireGrid.get(x, y, z);
        double smoke = fm.getSmokeAt(x, y, z);

        // 1. 高温即时死亡：温度达到致死阈值（473K/200°C）相当于被火焰/灼热烟气
        //    直接裹住，现实中数十秒内致命，不再走累积过程。
        if (fire >= FireEscape.T_DEATH) {
            die(fm, "fire");
            return;
        }

        // 2. 累积伤害剂量（烟气毒性 + 高温灼伤）。剂量只增不减，任一攒满即死亡。
        accumulateInjuryDoses(fire, smoke);
        if (toxicDose >= 1.0 || heatDose >= 1.0) {
            // 先攒满的剂量决定死因统计口径；两者接近时取剂量更高者。
            die(fm, toxicDose >= heatDose ? "smoke" : "fire");
            return;
        }

        ensureNavigation();
        initializeEvacuationState();

        // 3. 到达显式出口或从一楼外门推断出的出口后完成疏散。
        if (isAtEvacuationExit()) {
            markEscaped();
            return;
        }

        // 4. 移动概率 = 基础概率 × 呛咳刺激 × 能见度 × 受伤程度；路径和排队状态
        //    仍保留到下一次移动机会。三个因子各管一种现实减速原因，互不重复：
        //    - 呛咳刺激（轻罚，满烟保留 70%）：浓烟呛得睁不开眼、剧烈咳嗽；
        //    - 能见度（Jin 实验，满烟低至 30%）：看不清路只能扶墙慢挪；
        //    - 受伤程度（剂量满时减半）：中毒头晕、灼伤疼痛导致腿软。
        double injuryFactor = 1.0 - 0.5 * Math.max(toxicDose, heatDose);
        double moveProb = fm.moveProbability
                * (1.0 - smoke * 0.3)
                * visibilitySpeedFactor(smoke)
                * injuryFactor;
        if (moveCooldown > 0 || fm.random.nextDouble() >= moveProb) {
            moveCooldown = Math.max(0, moveCooldown - 1);
            if (routeLockTicks > 0) routeLockTicks--;
            if (noPathRetryTicks > 0) noPathRetryTicks--;
            return;
        }

        if (routeLockTicks > 0) routeLockTicks--;
        if (noPathRetryTicks > 0) {
            noPathRetryTicks--;
            return;
        }

        if (!ensureRoute()) {
            moveCooldown = 1;
            return;
        }

        int[] next = route.peekFirst();
        if (next == null) {
            moveCooldown = 1;
            return;
        }

        // 路线上出现新的烟火危险时立即废弃路径，下次重新评估楼梯或出口。
        if (!isRouteCellSafe(fm, next[0], next[1], next[2]) ||
                !isNavigationCell(fm, next[0], next[1], next[2])) {
            invalidateRouteForHazard();
            moveCooldown = 1;
            return;
        }

        // 一个体素同一时刻只允许一个人占用。楼梯口被占用时原地排队，
        // 不再随机绕行或与前方人员重叠，从而让拥堵自然向走廊回溢。
        if (fm.personGrid.numObjectsAtLocation(next[0], next[1], next[2]) > 0) {
            enterQueueState(next);
            blockedMoveAttempts++;
            if (blockedMoveAttempts >= BLOCKED_REPLAN_ATTEMPTS) {
                reconsiderBlockedRoute();
            }
            moveCooldown = 1;
            return;
        }

        leaveQueueState();
        route.removeFirst();
        fm.personGrid.setObjectLocation(this, next[0], next[1], next[2]);
        x = next[0];
        y = next[1];
        z = next[2];
        blockedMoveAttempts = 0;
        moveCooldown = fm.obstacleGrid.get(x, y, z) == FireEscape.TYPE_STAIR ? 4 : 2;

        if (route.isEmpty()) completeRouteLeg();
        if (isAtEvacuationExit()) {
            markEscaped();
            return;
        }

        // 移动后再次检查高温，避免人物进入刚刚恶化的火场格后多存活一个时间步。
        fire = fm.fireGrid.get(x, y, z);
        if (fire >= FireEscape.T_DEATH) {
            die(fm, "fire");
        }
    }

    /** 死亡统一收口：设置死因、移出网格并计入统计（火灾死亡只有 fire/smoke 两类）。 */
    private void die(FireEscape fm, String cause) {
        alive = false;
        deathCause = cause;
        fm.personGrid.remove(this);
        fm.recordDeath(cause);
    }

    /**
     * 累积本时间步的伤害剂量。两种剂量都只增不减：CO 吸入后在血液中的半衰期
     * 长达数小时，灼伤损伤同样不可逆，因此离开危险区后剂量保持，只会停止
     * 上涨、不会消退。
     */
    private void accumulateInjuryDoses(double temperature, double smoke) {
        // 烟气毒性：超过轻烟阈值后按烟浓度平方累积（Purser FED 时间尺度），
        // 烟浓一倍、单位时间吸入的毒物约四倍。
        if (smoke > SMOKE_DOSE_IGNORE_BELOW) {
            toxicDose += smoke * smoke / SMOKE_DOSE_TICKS_AT_FULL;
        }
        // 高温灼伤：70°C 起按超温幅度平方累积（SFPE 干热空气耐受曲线：
        // 100°C 约 7.5 分钟、150°C 约 1 分钟致死）；≥T_DEATH 已在上方即时判定。
        if (temperature >= HEAT_DOSE_T_LOW && temperature < FireEscape.T_DEATH) {
            double scaled = (temperature - HEAT_DOSE_T_LOW) / HEAT_DOSE_T_SPAN;
            heatDose += scaled * scaled / HEAT_DOSE_RATE_DIVISOR;
        }
    }

    /**
     * 能见度对移动速度的影响（Jin 烟中行走实验 / SFPE 手册）：
     * 烟粒子挡光，能见度（米）≈ 1/烟浓度（满烟约 1 米、五成烟约 2 米、
     * 一成烟约 10 米）。行走速度随能见度近似线性下降，但浓烟中人会扶墙
     * 摸黑挪步而不是完全停下，故设 30% 下限。
     */
    private static double visibilitySpeedFactor(double smoke) {
        if (smoke < 0.05) return 1.0;   // 基本无烟，视线不受影响
        double visibility = Math.min(1.0 / smoke, VIS_CLEAR_METERS);
        double factor = VIS_SPEED_MIN +
                (1.0 - VIS_SPEED_MIN) * (visibility - 1.0) / (VIS_CLEAR_METERS - 1.0);
        return Math.max(VIS_SPEED_MIN, Math.min(1.0, factor));
    }

    private void ensureNavigation() {
        if (navigation != null) return;
        navigation = NAVIGATION_CACHE.get(model);
        if (navigation == null) {
            List<Integer> floors = detectValidFloors(model);
            navigation = buildNavigationData(model, floors);
            NAVIGATION_CACHE.put(model, navigation);
        }
    }

    private void initializeEvacuationState() {
        if (evacuationPhase != null) return;
        currentFloorIndex = nearestFloorIndex(navigation.floorLevels, z);
        currentFloorIndex = Math.max(0, currentFloorIndex);
        evacuationPhase = selectPhaseForCurrentSpace();
    }

    /**
     * 每到一个新楼层或穿过一道门后，重新判断人物是否仍在门分隔区域内。
     * 只有已经进入与楼梯/出口直接连通的区域，才开始寻找楼梯或出口；
     * 这样房间人员不会隔着墙直接搜索楼梯，也不会误选通往其他房间的近门。
     */
    private EvacuationPhase selectPhaseForCurrentSpace() {
        FloorDoorGraph floorGraph = navigation.getFloorDoorGraph(currentFloorIndex);
        if (floorGraph != null && floorGraph.requiresDoorTraversal(x, y)) {
            return EvacuationPhase.SEEK_ROOM_DOOR;
        }
        return primaryPhaseForFloor();
    }

    private EvacuationPhase primaryPhaseForFloor() {
        return currentFloorIndex <= 0 ?
                EvacuationPhase.SEEK_EXIT : EvacuationPhase.SEEK_STAIR;
    }

    /**
     * 按疏散阶段生成一段路径，而不是每一步重新贪心选择方向：
     * 高层先到选定楼梯，进入后保持同一楼梯连续下降，到达一楼后才转向出口。
     */
    private boolean ensureRoute() {
        for (int transitions = 0; transitions < 4; transitions++) {
            if (!route.isEmpty()) return true;

            if (evacuationPhase == EvacuationPhase.QUEUE_FOR_STAIR ||
                    evacuationPhase == EvacuationPhase.DOOR_QUEUE ||
                    evacuationPhase == EvacuationPhase.EXIT_QUEUE) {
                leaveQueueState();
            }

            if (evacuationPhase == EvacuationPhase.NO_SAFE_PATH) {
                // 暂时无路不等于疏散阶段结束。特别是人在楼梯中等待烟火消退时，
                // 必须恢复 DESCEND_STAIR，不能误回到“寻找本层楼梯”而原地卡死。
                evacuationPhase = phaseBeforeNoSafePath == null ?
                        selectPhaseForCurrentSpace() :
                        phaseBeforeNoSafePath;
                phaseBeforeNoSafePath = null;
            }

            if (evacuationPhase == EvacuationPhase.SEEK_ROOM_DOOR) {
                if (planRouteThroughNextDoor()) return true;
                // 到达下一门分区时可能已经进入走廊，也可能还需继续穿过防火门。
                // 让状态重新判断后在本次时间步继续生成下一段路线。
                if (evacuationPhase != EvacuationPhase.SEEK_ROOM_DOOR) continue;
                setNoSafePath();
                return false;
            }

            if (evacuationPhase == EvacuationPhase.SEEK_EXIT) {
                PathResult exitPath = findPath(new ExitGoal(navigation), false, null);
                if (installPathOrWait(exitPath)) return true;
                if (isAtEvacuationExit()) {
                    markEscaped();
                    return false;
                }
                continue;
            }

            if (evacuationPhase == EvacuationPhase.SEEK_STAIR) {
                if (planRouteToBestStair()) return true;
                // 人物可能已经位于选定楼梯入口，此时 A* 返回零长度路径；
                // 直接进入下楼阶段，不把“已经到达”误判成无路可走。
                if (evacuationPhase == EvacuationPhase.DESCEND_STAIR) continue;
                setNoSafePath();
                return false;
            }

            if (evacuationPhase == EvacuationPhase.DESCEND_STAIR) {
                if (currentFloorIndex <= 0) {
                    evacuationPhase = EvacuationPhase.SEEK_EXIT;
                    selectedStairId = -1;
                    continue;
                }
                targetFloorIndex = currentFloorIndex - 1;
                Stairwell stairwell = navigation.getStairwell(selectedStairId);
                if (stairwell == null) {
                    evacuationPhase = EvacuationPhase.SEEK_STAIR;
                    continue;
                }
                int landingZ = navigation.floorLevels.get(targetFloorIndex);
                PathResult descentPath = findPath(
                        new LandingGoal(stairwell, landingZ, model),
                        true,
                        stairwell);
                if (installPathOrWait(descentPath)) return true;
                if (isAtLanding(stairwell, landingZ)) {
                    completeRouteLeg();
                    continue;
                }

                // 当前楼梯无法到达下一层时，不允许随机下坠；只有已回到平台后
                // 才重新选择其他楼梯，否则保持原位等待路径恢复。
                if (model.obstacleGrid.get(x, y, z) != FireEscape.TYPE_STAIR) {
                    selectedStairId = -1;
                    evacuationPhase = EvacuationPhase.SEEK_STAIR;
                    continue;
                }
                setNoSafePath();
                return false;
            }
        }
        return !route.isEmpty();
    }

    private boolean planRouteThroughNextDoor() {
        FloorDoorGraph floorGraph = navigation.getFloorDoorGraph(currentFloorIndex);
        if (floorGraph == null) return false;

        int currentZone = floorGraph.resolveZoneAt(x, y);
        if (currentZone < 0) return false;
        if (floorGraph.egressDistance(currentZone) == 0) {
            selectedDoorPortalId = -1;
            selectedDoorFloorZ = -1;
            evacuationPhase = primaryPhaseForFloor();
            return false;
        }

        DoorExitChoice bestChoice = null;
        for (DoorTransition transition : floorGraph.transitionsFrom(currentZone)) {
            // 只选择能让人物更接近楼梯厅或一楼出口的门。
            // 与安全通道不连通的门、通往更深房间的门不会成为候选。
            if (!floorGraph.movesTowardEgress(currentZone, transition.destinationZone)) {
                continue;
            }

            PathResult path = findPath(
                    new ZoneGoal(floorGraph, transition.destinationZone),
                    false,
                    null);
            if (path == null) continue;

            double totalCost = path.cost +
                    countPeopleCommittedToDoor(
                            floorGraph.floorZ, transition.portalId) *
                            QUEUED_PERSON_COST;
            DoorExitChoice choice = new DoorExitChoice(
                    transition.portalId,
                    transition.destinationZone,
                    path,
                    totalCost);
            if (bestChoice == null || choice.totalCost < bestChoice.totalCost) {
                bestChoice = choice;
            }
        }

        if (bestChoice == null) return false;

        selectedDoorPortalId = bestChoice.portalId;
        selectedDoorFloorZ = floorGraph.floorZ;
        route.clear();
        route.addAll(bestChoice.path.steps);
        routeLockTicks = ROUTE_LOCK_TICKS;
        if (route.isEmpty()) {
            completeRouteLeg();
        }
        return !route.isEmpty();
    }

    private boolean installPathOrWait(PathResult path) {
        if (path == null) {
            setNoSafePath();
            return false;
        }
        route.clear();
        route.addAll(path.steps);
        return !route.isEmpty();
    }

    private boolean planRouteToBestStair() {
        StairChoice currentChoice = null;
        StairChoice bestChoice = null;

        for (Stairwell stairwell : navigation.stairwells) {
            PathResult path = findPath(
                    new StairEntryGoal(stairwell, currentFloorIndex, navigation, model),
                    true,
                    stairwell);
            if (path == null) continue;

            double totalCost = path.cost +
                    countPeopleCommittedToStair(stairwell.id) * QUEUED_PERSON_COST;
            StairChoice choice = new StairChoice(stairwell, path, totalCost);
            if (stairwell.id == selectedStairId) currentChoice = choice;
            if (bestChoice == null || choice.totalCost < bestChoice.totalCost) {
                bestChoice = choice;
            }
        }

        if (bestChoice == null) return false;

        // 已选路线只有在替代楼梯预计总代价至少降低 20% 时才切换，
        // 防止拥堵变化使所有人员在楼梯之间来回振荡。
        StairChoice selected = bestChoice;
        if (currentChoice != null && bestChoice.stairwell.id != currentChoice.stairwell.id &&
                (routeLockTicks > 0 ||
                        bestChoice.totalCost >= currentChoice.totalCost * STAIR_SWITCH_RATIO)) {
            selected = currentChoice;
        }

        selectedStairId = selected.stairwell.id;
        route.clear();
        route.addAll(selected.path.steps);
        routeLockTicks = ROUTE_LOCK_TICKS;
        if (route.isEmpty() &&
                selected.stairwell.containsCell(x, y, z)) {
            completeRouteLeg();
        }
        return !route.isEmpty();
    }

    private int countPeopleCommittedToStair(int stairId) {
        int count = 0;
        if (model.persons == null) return count;
        for (Person person : model.persons) {
            if (person == null || person == this || !person.alive || person.escaped) continue;
            if (person.selectedStairId == stairId &&
                    person.currentFloorIndex == currentFloorIndex) {
                count++;
            }
        }
        return count;
    }

    private int countPeopleCommittedToDoor(int floorZ, int portalId) {
        int count = 0;
        if (model.persons == null) return count;
        for (Person person : model.persons) {
            if (person == null || person == this || !person.alive || person.escaped) continue;
            if (person.selectedDoorFloorZ == floorZ &&
                    person.selectedDoorPortalId == portalId) {
                count++;
            }
        }
        return count;
    }

    private void enterQueueState(int[] next) {
        if (evacuationPhase == EvacuationPhase.QUEUE_FOR_STAIR ||
                evacuationPhase == EvacuationPhase.DOOR_QUEUE ||
                evacuationPhase == EvacuationPhase.EXIT_QUEUE) {
            return;
        }
        phaseBeforeQueue = evacuationPhase;
        if (evacuationPhase == EvacuationPhase.SEEK_ROOM_DOOR) {
            evacuationPhase = EvacuationPhase.DOOR_QUEUE;
        } else if (evacuationPhase == EvacuationPhase.SEEK_EXIT ||
                navigation.isExitTarget(next[0], next[1], next[2])) {
            evacuationPhase = EvacuationPhase.EXIT_QUEUE;
        } else {
            evacuationPhase = EvacuationPhase.QUEUE_FOR_STAIR;
        }
    }

    private void leaveQueueState() {
        if (evacuationPhase == EvacuationPhase.QUEUE_FOR_STAIR ||
                evacuationPhase == EvacuationPhase.DOOR_QUEUE ||
                evacuationPhase == EvacuationPhase.EXIT_QUEUE) {
            evacuationPhase = phaseBeforeQueue == null ?
                    selectPhaseForCurrentSpace() :
                    phaseBeforeQueue;
            phaseBeforeQueue = null;
        }
    }

    private void reconsiderBlockedRoute() {
        if (evacuationPhase == EvacuationPhase.DOOR_QUEUE &&
                phaseBeforeQueue == EvacuationPhase.SEEK_ROOM_DOOR &&
                routeLockTicks <= 0) {
            evacuationPhase = EvacuationPhase.SEEK_ROOM_DOOR;
            phaseBeforeQueue = null;
            route.clear();
            selectedDoorPortalId = -1;
            selectedDoorFloorZ = -1;
        } else if (evacuationPhase == EvacuationPhase.QUEUE_FOR_STAIR &&
                phaseBeforeQueue == EvacuationPhase.SEEK_STAIR &&
                routeLockTicks <= 0) {
            evacuationPhase = EvacuationPhase.SEEK_STAIR;
            phaseBeforeQueue = null;
            route.clear();
        }
        // 已经进入楼梯后保持队列顺序，不因前方短时拥堵逆行或超越。
        blockedMoveAttempts = 0;
    }

    private void invalidateRouteForHazard() {
        route.clear();
        blockedMoveAttempts = 0;
        leaveQueueState();
        if (evacuationPhase == EvacuationPhase.SEEK_ROOM_DOOR) {
            selectedDoorPortalId = -1;
            selectedDoorFloorZ = -1;
            routeLockTicks = 0;
        } else if (evacuationPhase == EvacuationPhase.SEEK_STAIR) {
            routeLockTicks = 0;
        } else if (evacuationPhase == EvacuationPhase.DESCEND_STAIR &&
                model.obstacleGrid.get(x, y, z) != FireEscape.TYPE_STAIR) {
            selectedStairId = -1;
            evacuationPhase = EvacuationPhase.SEEK_STAIR;
        }
    }

    private void completeRouteLeg() {
        if (evacuationPhase == EvacuationPhase.SEEK_ROOM_DOOR) {
            selectedDoorPortalId = -1;
            selectedDoorFloorZ = -1;
            evacuationPhase = selectPhaseForCurrentSpace();
        } else if (evacuationPhase == EvacuationPhase.SEEK_STAIR) {
            evacuationPhase = EvacuationPhase.DESCEND_STAIR;
            targetFloorIndex = currentFloorIndex - 1;
        } else if (evacuationPhase == EvacuationPhase.DESCEND_STAIR) {
            currentFloorIndex = Math.max(0, targetFloorIndex);
            if (currentFloorIndex == 0) {
                selectedStairId = -1;
                evacuationPhase = EvacuationPhase.SEEK_EXIT;
            } else {
                // 同一个受保护楼梯间继续向下一层，不在每层重新选择楼梯。
                evacuationPhase = EvacuationPhase.DESCEND_STAIR;
                targetFloorIndex = currentFloorIndex - 1;
            }
        }
    }

    private void setNoSafePath() {
        route.clear();
        if (evacuationPhase != EvacuationPhase.NO_SAFE_PATH) {
            phaseBeforeNoSafePath = evacuationPhase;
        }
        evacuationPhase = EvacuationPhase.NO_SAFE_PATH;
        noPathRetryTicks = NO_PATH_RETRY_TICKS;
    }

    private boolean isAtEvacuationExit() {
        return model.obstacleGrid.get(x, y, z) == FireEscape.TYPE_EXIT ||
                navigation.isExitTarget(x, y, z);
    }

    private void markEscaped() {
        escaped = true;
        route.clear();
        model.personGrid.remove(this);
    }

    private boolean isAtLanding(Stairwell stairwell, int landingZ) {
        return z == landingZ &&
                isNavigationCell(model, x, y, z) &&
                stairwell.distanceAt(x, y) <= metersToCells(
                        model, STAIRWELL_SEARCH_RADIUS_METERS);
    }

    private PathResult findPath(RouteGoal goal,
                                boolean allowVerticalStairMovement,
                                Stairwell stairRestriction) {
        return findPath(model, navigation, x, y, z, goal,
                allowVerticalStairMovement, stairRestriction, this);
    }

    private static PathResult findPath(FireEscape model,
                                       NavigationData navigation,
                                       int startX,
                                       int startY,
                                       int startZ,
                                       RouteGoal goal,
                                       boolean allowVerticalStairMovement,
                                       Stairwell stairRestriction,
                                       Person movingPerson) {
        if (!isWithinGrid(model, startX, startY, startZ)) return null;

        synchronized (navigation) {
            int searchStamp = navigation.nextSearchStamp();
            int startIndex = voxelIndex(model, startX, startY, startZ);
            PriorityQueue<PathNode> open = new PriorityQueue<>();
            navigation.visitedStamp[startIndex] = searchStamp;
            navigation.gScore[startIndex] = 0.0;
            navigation.parent[startIndex] = -1;
            open.add(new PathNode(startIndex,
                    goal.estimate(startX, startY, startZ)));

            while (!open.isEmpty()) {
                PathNode node = open.poll();
                if (navigation.closedStamp[node.index] == searchStamp) continue;
                navigation.closedStamp[node.index] = searchStamp;

                int cx = node.index % model.gridWidth;
                int yz = node.index / model.gridWidth;
                int cy = yz % model.gridHeight;
                int cz = yz / model.gridHeight;
                if (goal.matches(cx, cy, cz)) {
                    return reconstructPath(model, navigation, startIndex,
                            node.index, searchStamp);
                }

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        relaxPathNeighbor(model, navigation, goal, searchStamp,
                                node.index, cx, cy, cz,
                                cx + dx, cy + dy, cz,
                                allowVerticalStairMovement, stairRestriction,
                                movingPerson, open);
                    }
                }

                if (allowVerticalStairMovement) {
                    for (int dz = -1; dz <= 1; dz += 2) {
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                relaxPathNeighbor(model, navigation, goal, searchStamp,
                                        node.index, cx, cy, cz,
                                        cx + dx, cy + dy, cz + dz,
                                        true, stairRestriction, movingPerson, open);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static void relaxPathNeighbor(FireEscape model,
                                          NavigationData navigation,
                                          RouteGoal goal,
                                          int searchStamp,
                                          int currentIndex,
                                          int cx,
                                          int cy,
                                          int cz,
                                          int nx,
                                          int ny,
                                          int nz,
                                          boolean allowVerticalStairMovement,
                                          Stairwell stairRestriction,
                                          Person movingPerson,
                                          PriorityQueue<PathNode> open) {
        if (!isWithinGrid(model, nx, ny, nz) ||
                !isNavigationCell(model, nx, ny, nz) ||
                !isRouteCellSafe(model, nx, ny, nz)) {
            return;
        }

        int dx = nx - cx;
        int dy = ny - cy;
        int dz = nz - cz;

        if (dz != 0) {
            boolean currentOnStairRoute = isStairTransitionCell(
                    model, stairRestriction, cx, cy, cz);
            boolean nextOnStairRoute = isStairTransitionCell(
                    model, stairRestriction, nx, ny, nz);
            if (!allowVerticalStairMovement ||
                    (!currentOnStairRoute && !nextOnStairRoute)) {
                return;
            }
        } else if (dx != 0 && dy != 0) {
            // 对角移动要求两个正交方向都可通行，防止从墙角穿过去。
            if (!isNavigationCell(model, cx + dx, cy, cz) ||
                    !isNavigationCell(model, cx, cy + dy, cz)) {
                return;
            }
        }

        int nextIndex = voxelIndex(model, nx, ny, nz);
        if (navigation.closedStamp[nextIndex] == searchStamp) return;

        double movementCost = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dz > 0) movementCost += 3.0;
        double smoke = model.getSmokeAt(nx, ny, nz);
        double heatRatio = Math.max(0.0,
                (model.fireGrid.get(nx, ny, nz) - FireEscape.T_AMBIENT) /
                        Math.max(1.0, MAX_ROUTE_TEMPERATURE - FireEscape.T_AMBIENT));
        int occupants = model.personGrid.numObjectsAtLocation(nx, ny, nz);
        if (movingPerson != null && nx == movingPerson.x &&
                ny == movingPerson.y && nz == movingPerson.z) {
            occupants = Math.max(0, occupants - 1);
        }

        double tentativeScore = navigation.gScore[currentIndex] +
                movementCost +
                smoke * SMOKE_ROUTE_COST +
                heatRatio * HEAT_ROUTE_COST +
                occupants * OCCUPIED_CELL_COST;

        if (navigation.visitedStamp[nextIndex] != searchStamp ||
                tentativeScore < navigation.gScore[nextIndex]) {
            navigation.visitedStamp[nextIndex] = searchStamp;
            navigation.gScore[nextIndex] = tentativeScore;
            navigation.parent[nextIndex] = currentIndex;
            double estimatedTotal = tentativeScore + goal.estimate(nx, ny, nz);
            open.add(new PathNode(nextIndex, estimatedTotal));
        }
    }

    private static boolean isStairTransitionCell(FireEscape model,
                                                 Stairwell stairRestriction,
                                                 int x,
                                                 int y,
                                                 int z) {
        if (!isWithinGrid(model, x, y, z)) return false;
        if (model.obstacleGrid.get(x, y, z) == FireEscape.TYPE_STAIR) {
            return stairRestriction == null ||
                    stairRestriction.containsCell(x, y, z);
        }

        // IFC 体素化后，楼梯顶面与站立层之间偶尔会保留一个空气体素。
        // 仅将“指定楼梯实体正上方”的这一格视为楼梯过渡，避免开放普通区域竖移。
        int stairZ = z - 1;
        return stairZ >= 0 &&
                model.obstacleGrid.get(x, y, stairZ) == FireEscape.TYPE_STAIR &&
                (stairRestriction == null ||
                        stairRestriction.containsCell(x, y, stairZ));
    }

    private static PathResult reconstructPath(FireEscape model,
                                              NavigationData navigation,
                                              int startIndex,
                                              int goalIndex,
                                              int searchStamp) {
        ArrayDeque<int[]> steps = new ArrayDeque<>();
        int index = goalIndex;
        while (index != startIndex) {
            if (index < 0 || navigation.visitedStamp[index] != searchStamp) return null;
            int x = index % model.gridWidth;
            int yz = index / model.gridWidth;
            int y = yz % model.gridHeight;
            int z = yz / model.gridHeight;
            steps.addFirst(new int[]{x, y, z});
            index = navigation.parent[index];
        }
        return new PathResult(new ArrayList<>(steps), navigation.gScore[goalIndex]);
    }

    private static boolean isNavigationCell(FireEscape model, int x, int y, int z) {
        if (!isWithinGrid(model, x, y, z)) return false;
        int type = model.obstacleGrid.get(x, y, z);
        // 门和楼梯可能因开放连通关系被全局室内洪水标成 outside，但它们的业务
        // 语义本身就是可通行连接，因此导航时优先按类型恢复，不让路径在门口中断。
        if (type == FireEscape.TYPE_STAIR ||
                type == FireEscape.TYPE_DOOR ||
                type == FireEscape.TYPE_EXIT) {
            return true;
        }
        if (type == FireEscape.TYPE_EMPTY && z > 0 &&
                model.obstacleGrid.get(x, y, z - 1) == FireEscape.TYPE_STAIR) {
            // 楼梯顶面上方的单个空气体素属于踏步可站立空间；即使其因楼梯井
            // 与室外连通而被标成 outside，也必须保留，否则顶层无法踏入楼梯。
            return true;
        }
        NavigationData cachedNavigation = NAVIGATION_CACHE.get(model);
        if (type == FireEscape.TYPE_EMPTY &&
                cachedNavigation != null &&
                cachedNavigation.isFloorWalkable(x, y, z)) {
            // 3D indoor 仍是主判定；只有已识别站立层上经二维边界洪水确认封闭的
            // 空格才作为补偿通道，用于跨越 IFC 门槛和楼板交界处的体素缺口。
            return true;
        }
        if (!canOccupy(model, x, y, z)) return false;
        if (z <= 0) return false;
        int below = model.obstacleGrid.get(x, y, z - 1);
        return below == FireEscape.TYPE_WALL || below == FireEscape.TYPE_STAIR;
    }

    private static boolean isRouteCellSafe(FireEscape model, int x, int y, int z) {
        return model.fireGrid.get(x, y, z) < MAX_ROUTE_TEMPERATURE &&
                model.getSmokeAt(x, y, z) < MAX_ROUTE_SMOKE;
    }

    private static boolean isWithinGrid(FireEscape model, int x, int y, int z) {
        return x >= 0 && x < model.gridWidth &&
                y >= 0 && y < model.gridHeight &&
                z >= 0 && z < model.gridDepth;
    }

    private static int voxelIndex(FireEscape model, int x, int y, int z) {
        return (z * model.gridHeight + y) * model.gridWidth + x;
    }

    private static int nearestFloorIndex(List<Integer> floors, int z) {
        if (floors.isEmpty()) return 0;
        int bestIndex = 0;
        int bestDistance = Math.abs(floors.get(0) - z);
        for (int i = 1; i < floors.size(); i++) {
            int distance = Math.abs(floors.get(i) - z);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static NavigationData buildNavigationData(FireEscape model,
                                                       List<Integer> floorLevels) {
        List<Integer> floors = new ArrayList<>(floorLevels);
        Collections.sort(floors);
        List<Stairwell> stairwells = detectStairwells(model);
        List<int[]> exits = collectEvacuationExits(model, floors);
        NavigationData data = new NavigationData(
                model, model.gridWidth, model.gridHeight, model.gridDepth,
                floors, stairwells, exits);
        System.out.println("[Person] 疏散导航初始化: 楼层=" + floors +
                ", 楼梯组件=" + stairwells.size() +
                ", 出口目标=" + exits.size() +
                ", 需穿门区域=" + data.doorSeparatedZoneCount() +
                ", 不连通区域=" + data.disconnectedZoneCount());
        return data;
    }

    /**
     * 将一个楼层按门分割成若干空间区域，再把门作为区域之间的连接边。
     *
     * 反向从高层楼梯入口或一楼出口计算每个区域需要经过的门数。人物只沿门数
     * 递减的方向移动，因此不会因为几何距离较近而误入另一个房间或死胡同。
     */
    private static FloorDoorGraph buildFloorDoorGraph(
            FireEscape model,
            int floorIndex,
            List<Integer> floorLevels,
            List<Stairwell> stairwells,
            List<int[]> exitTargets,
            boolean[] floorWalkable) {
        int width = model.gridWidth;
        int height = model.gridHeight;
        int floorZ = floorLevels.get(floorIndex);
        int cellCount = width * height;
        boolean[] zoneCells = new boolean[cellCount];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                zoneCells[index] = isFloorGraphNavigationCell(
                        model, x, y, floorZ, floorWalkable) &&
                        model.obstacleGrid.get(x, y, floorZ) !=
                                FireEscape.TYPE_DOOR;
            }
        }

        int[] zoneByCell = new int[cellCount];
        Arrays.fill(zoneByCell, -1);
        int[] queue = new int[cellCount];
        int zoneCount = 0;
        for (int start = 0; start < cellCount; start++) {
            if (!zoneCells[start] || zoneByCell[start] >= 0) continue;

            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            zoneByCell[start] = zoneCount;
            while (head < tail) {
                int index = queue[head++];
                int x = index % width;
                int y = index / width;

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                            continue;
                        }

                        int nextIndex = ny * width + nx;
                        if (!zoneCells[nextIndex] || zoneByCell[nextIndex] >= 0) {
                            continue;
                        }
                        if (dx != 0 && dy != 0 &&
                                (!zoneCells[y * width + nx] ||
                                        !zoneCells[ny * width + x])) {
                            continue;
                        }
                        zoneByCell[nextIndex] = zoneCount;
                        queue[tail++] = nextIndex;
                    }
                }
            }
            zoneCount++;
        }

        int[] portalByCell = new int[cellCount];
        Arrays.fill(portalByCell, -1);
        boolean[] visitedDoor = new boolean[cellCount];
        List<DoorPortal> portals = new ArrayList<>();
        int[][] orthogonalDirections = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int start = 0; start < cellCount; start++) {
            int startX = start % width;
            int startY = start / width;
            if (visitedDoor[start] ||
                    model.obstacleGrid.get(startX, startY, floorZ) !=
                            FireEscape.TYPE_DOOR) {
                continue;
            }

            List<Integer> doorCells = new ArrayList<>();
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            visitedDoor[start] = true;
            while (head < tail) {
                int index = queue[head++];
                doorCells.add(index);
                int x = index % width;
                int y = index / width;
                for (int[] direction : orthogonalDirections) {
                    int nx = x + direction[0];
                    int ny = y + direction[1];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    int nextIndex = ny * width + nx;
                    if (!visitedDoor[nextIndex] &&
                            model.obstacleGrid.get(nx, ny, floorZ) ==
                                    FireEscape.TYPE_DOOR) {
                        visitedDoor[nextIndex] = true;
                        queue[tail++] = nextIndex;
                    }
                }
            }

            boolean[] touchedZone = new boolean[zoneCount];
            for (int index : doorCells) {
                int x = index % width;
                int y = index / width;
                for (int[] direction : orthogonalDirections) {
                    int nx = x + direction[0];
                    int ny = y + direction[1];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    int zoneId = zoneByCell[ny * width + nx];
                    if (zoneId >= 0) touchedZone[zoneId] = true;
                }
            }

            int touchedCount = 0;
            for (boolean touched : touchedZone) {
                if (touched) touchedCount++;
            }
            int[] touchingZones = new int[touchedCount];
            int cursor = 0;
            for (int zoneId = 0; zoneId < zoneCount; zoneId++) {
                if (touchedZone[zoneId]) touchingZones[cursor++] = zoneId;
            }

            int portalId = portals.size();
            for (int index : doorCells) {
                portalByCell[index] = portalId;
            }
            portals.add(new DoorPortal(portalId, touchingZones));
        }

        List<List<DoorTransition>> transitionsByZone = new ArrayList<>();
        for (int zoneId = 0; zoneId < zoneCount; zoneId++) {
            transitionsByZone.add(new ArrayList<DoorTransition>());
        }
        for (DoorPortal portal : portals) {
            for (int fromZone : portal.touchingZones) {
                for (int toZone : portal.touchingZones) {
                    if (fromZone != toZone) {
                        transitionsByZone.get(fromZone).add(
                                new DoorTransition(portal.id, toZone));
                    }
                }
            }
        }

        boolean[] egressZone = new boolean[zoneCount];
        if (floorIndex == 0) {
            for (int[] exit : exitTargets) {
                if (exit[2] != floorZ) continue;
                int index = exit[1] * width + exit[0];
                int zoneId = zoneByCell[index];
                if (zoneId >= 0) {
                    egressZone[zoneId] = true;
                } else {
                    int portalId = portalByCell[index];
                    if (portalId >= 0) {
                        for (int touchingZone : portals.get(portalId).touchingZones) {
                            egressZone[touchingZone] = true;
                        }
                    }
                }
            }
        } else {
            for (int index = 0; index < cellCount; index++) {
                int zoneId = zoneByCell[index];
                if (zoneId < 0) continue;
                int x = index % width;
                int y = index / width;
                if (hasStairAccessFromFloor(
                        model, stairwells, x, y, floorZ)) {
                    egressZone[zoneId] = true;
                }
            }
        }

        int[] egressDistance = new int[zoneCount];
        Arrays.fill(egressDistance, -1);
        ArrayDeque<Integer> zoneQueue = new ArrayDeque<>();
        for (int zoneId = 0; zoneId < zoneCount; zoneId++) {
            if (egressZone[zoneId]) {
                egressDistance[zoneId] = 0;
                zoneQueue.addLast(zoneId);
            }
        }
        while (!zoneQueue.isEmpty()) {
            int zoneId = zoneQueue.removeFirst();
            for (DoorTransition transition : transitionsByZone.get(zoneId)) {
                int nextZone = transition.destinationZone;
                if (egressDistance[nextZone] < 0) {
                    egressDistance[nextZone] = egressDistance[zoneId] + 1;
                    zoneQueue.addLast(nextZone);
                }
            }
        }

        return new FloorDoorGraph(
                floorZ, width, height,
                zoneByCell, portalByCell,
                portals, transitionsByZone, egressDistance);
    }

    private static boolean isFloorGraphNavigationCell(
            FireEscape model, int x, int y, int z, boolean[] floorWalkable) {
        int type = model.obstacleGrid.get(x, y, z);
        if (type == FireEscape.TYPE_STAIR ||
                type == FireEscape.TYPE_DOOR ||
                type == FireEscape.TYPE_EXIT) {
            return true;
        }
        if (type != FireEscape.TYPE_EMPTY) return false;
        if (z > 0 &&
                model.obstacleGrid.get(x, y, z - 1) ==
                        FireEscape.TYPE_STAIR) {
            return true;
        }
        return floorWalkable[y * model.gridWidth + x];
    }

    private static boolean hasStairAccessFromFloor(
            FireEscape model,
            List<Stairwell> stairwells,
            int x,
            int y,
            int floorZ) {
        // 只检查格子本身是否是楼梯过渡格，不扫描 3×3 邻域。
        // 3×3 邻域会让贴墙的房间格子扫到墙另一侧的楼梯实体，
        // 导致整个房间区域被误判为"已到疏散点"(egressDistance=0)，
        // 人物跳过 SEEK_ROOM_DOOR 阶段直接 A* 寻路到楼梯，
        // 若墙体有 IFC 体素化缝隙则表现为穿墙。
        for (Stairwell stairwell : stairwells) {
            if (isStairTransitionCell(model, stairwell, x, y, floorZ)) {
                return true;
            }
            // 顶层站立面可能比最后一级楼梯高一格；只允许真实楼梯过渡格
            // 建立连接，不能把整片楼梯二维投影都当作入口。
            if (isStairTransitionCell(model, stairwell, x, y, floorZ - 1)) {
                return true;
            }
        }
        return false;
    }

    private static List<Stairwell> detectStairwells(FireEscape model) {
        int width = model.gridWidth;
        int height = model.gridHeight;
        int depth = model.gridDepth;
        int cellCount = width * height * depth;
        boolean[] stairCells = new boolean[cellCount];
        for (int z = 0; z < model.gridDepth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (model.obstacleGrid.get(x, y, z) == FireEscape.TYPE_STAIR) {
                        stairCells[voxelIndex(model, x, y, z)] = true;
                    }
                }
            }
        }

        // 楼梯按真实三维邻接关系分组，不能把所有楼层先投影到二维平面。
        // 否则上下楼层中仅投影相邻、实际并不连通的楼梯会被错误合并。
        boolean[] visited = new boolean[cellCount];
        List<Stairwell> stairwells = new ArrayList<>();
        for (int start = 0; start < cellCount; start++) {
            if (!stairCells[start] || visited[start]) continue;
            boolean[] componentCells = new boolean[cellCount];
            boolean[] footprint = new boolean[width * height];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            visited[start] = true;

            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                int x = index % width;
                int yz = index / width;
                int y = yz % height;
                int z = yz / height;
                componentCells[index] = true;
                footprint[y * width + x] = true;

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            int nx = x + dx;
                            int ny = y + dy;
                            int nz = z + dz;
                            if (nx < 0 || nx >= width ||
                                    ny < 0 || ny >= height ||
                                    nz < 0 || nz >= depth) {
                                continue;
                            }
                            int nextIndex = voxelIndex(model, nx, ny, nz);
                            if (stairCells[nextIndex] && !visited[nextIndex]) {
                                visited[nextIndex] = true;
                                queue.addLast(nextIndex);
                            }
                        }
                    }
                }
            }
            stairwells.add(new Stairwell(
                    stairwells.size(), width, height, depth,
                    footprint, componentCells));
        }
        return stairwells;
    }

    private static List<int[]> collectEvacuationExits(FireEscape model,
                                                       List<Integer> floors) {
        List<int[]> targets = new ArrayList<>();
        boolean[] seen = new boolean[
                model.gridWidth * model.gridHeight * model.gridDepth];
        for (int[] exit : model.exits) {
            if (!isWithinGrid(model, exit[0], exit[1], exit[2])) continue;
            int index = voxelIndex(model, exit[0], exit[1], exit[2]);
            if (!seen[index] && canOccupy(model, exit[0], exit[1], exit[2])) {
                seen[index] = true;
                targets.add(new int[]{exit[0], exit[1], exit[2]});
            }
        }
        if (!targets.isEmpty() || floors.isEmpty()) return targets;

        int groundZ = floors.get(0);
        boolean[] visitedDoors = new boolean[model.gridWidth * model.gridHeight];
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        for (int y = 0; y < model.gridHeight; y++) {
            for (int x = 0; x < model.gridWidth; x++) {
                int startIndex = y * model.gridWidth + x;
                if (visitedDoors[startIndex] ||
                        model.obstacleGrid.get(x, y, groundZ) != FireEscape.TYPE_DOOR) {
                    continue;
                }

                List<Integer> component = new ArrayList<>();
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(startIndex);
                visitedDoors[startIndex] = true;
                boolean touchesIndoor = false;
                boolean touchesOutdoor = false;

                while (!queue.isEmpty()) {
                    int index = queue.removeFirst();
                    component.add(index);
                    int doorX = index % model.gridWidth;
                    int doorY = index / model.gridWidth;

                    for (int[] direction : directions) {
                        int nx = doorX + direction[0];
                        int ny = doorY + direction[1];
                        if (nx < 0 || nx >= model.gridWidth ||
                                ny < 0 || ny >= model.gridHeight) {
                            touchesOutdoor = true;
                            continue;
                        }

                        int nextIndex = ny * model.gridWidth + nx;
                        int nextType = model.obstacleGrid.get(nx, ny, groundZ);
                        if (nextType == FireEscape.TYPE_DOOR) {
                            if (!visitedDoors[nextIndex]) {
                                visitedDoors[nextIndex] = true;
                                queue.addLast(nextIndex);
                            }
                        } else if (model.insideGrid.get(nx, ny, groundZ) == 1) {
                            touchesIndoor = true;
                        } else if (nextType == FireEscape.TYPE_EMPTY ||
                                nextType == FireEscape.TYPE_FUEL) {
                            touchesOutdoor = true;
                        }
                    }
                }

                // 仅把同时连接室内与室外的首层门组件作为出口，普通房门不会误入。
                if (touchesIndoor && touchesOutdoor) {
                    for (int index : component) {
                        int doorX = index % model.gridWidth;
                        int doorY = index / model.gridWidth;
                        if (canOccupy(model, doorX, doorY, groundZ)) {
                            int voxelIndex = voxelIndex(model, doorX, doorY, groundZ);
                            if (!seen[voxelIndex]) {
                                seen[voxelIndex] = true;
                                targets.add(new int[]{doorX, doorY, groundZ});
                            }
                        }
                    }
                }
            }
        }
        return targets;
    }

    private interface RouteGoal {
        boolean matches(int x, int y, int z);
        double estimate(int x, int y, int z);
    }

    private static final class ZoneGoal implements RouteGoal {
        private final FloorDoorGraph floorGraph;
        private final int destinationZone;

        ZoneGoal(FloorDoorGraph floorGraph, int destinationZone) {
            this.floorGraph = floorGraph;
            this.destinationZone = destinationZone;
        }

        public boolean matches(int x, int y, int z) {
            return z == floorGraph.floorZ &&
                    floorGraph.zoneAt(x, y) == destinationZone;
        }

        public double estimate(int x, int y, int z) {
            // 区域可能是不规则形状。这里使用零启发保持可采纳性，
            // 由实际烟气、温度和拥堵代价决定穿门路线。
            return 0.0;
        }
    }

    private static final class ExitGoal implements RouteGoal {
        private final NavigationData navigation;

        ExitGoal(NavigationData navigation) {
            this.navigation = navigation;
        }

        public boolean matches(int x, int y, int z) {
            return navigation.isExitTarget(x, y, z);
        }

        public double estimate(int x, int y, int z) {
            double minimum = Double.MAX_VALUE;
            for (int[] exit : navigation.exitTargets) {
                double distance = Math.abs(x - exit[0]) +
                        Math.abs(y - exit[1]) +
                        Math.abs(z - exit[2]);
                if (distance < minimum) minimum = distance;
            }
            return minimum;
        }
    }

    private static final class StairEntryGoal implements RouteGoal {
        private final Stairwell stairwell;
        private final int lowerZ;
        private final int upperZ;
        private final FireEscape model;

        StairEntryGoal(Stairwell stairwell,
                       int floorIndex,
                       NavigationData navigation,
                       FireEscape model) {
            this.stairwell = stairwell;
            this.model = model;
            int currentZ = navigation.floorLevels.get(floorIndex);
            this.lowerZ = floorIndex > 0 ?
                    navigation.floorLevels.get(floorIndex - 1) : 0;
            this.upperZ = Math.min(model.gridDepth - 1, currentZ + 1);
        }

        public boolean matches(int x, int y, int z) {
            return z >= lowerZ && z <= upperZ &&
                    stairwell.containsCell(x, y, z) &&
                    model.obstacleGrid.get(x, y, z) == FireEscape.TYPE_STAIR;
        }

        public double estimate(int x, int y, int z) {
            int verticalDistance = z > upperZ ? z - upperZ :
                    (z < lowerZ ? lowerZ - z : 0);
            return stairwell.distanceAt(x, y) + verticalDistance;
        }
    }

    private static final class LandingGoal implements RouteGoal {
        private final Stairwell stairwell;
        private final int landingZ;
        private final int landingRadius;
        private final FireEscape model;

        LandingGoal(Stairwell stairwell, int landingZ, FireEscape model) {
            this.stairwell = stairwell;
            this.landingZ = landingZ;
            this.model = model;
            this.landingRadius = metersToCells(model, STAIRWELL_SEARCH_RADIUS_METERS);
        }

        public boolean matches(int x, int y, int z) {
            return z == landingZ &&
                    model.obstacleGrid.get(x, y, z) != FireEscape.TYPE_STAIR &&
                    stairwell.distanceAt(x, y) <= landingRadius &&
                    isNavigationCell(model, x, y, z);
        }

        public double estimate(int x, int y, int z) {
            return Math.abs(z - landingZ) +
                    Math.max(0, stairwell.distanceAt(x, y) - landingRadius);
        }
    }

    private static final class NavigationData {
        final int width;
        final int height;
        final int depth;
        final List<Integer> floorLevels;
        final List<Stairwell> stairwells;
        final List<int[]> exitTargets;
        final boolean[] exitMask;
        final Map<Integer, boolean[]> floorWalkableMasks;
        final Map<Integer, FloorDoorGraph> floorDoorGraphs;
        final double[] gScore;
        final int[] parent;
        final int[] visitedStamp;
        final int[] closedStamp;
        int searchStamp;

        NavigationData(FireEscape model,
                       int width,
                       int height,
                       int depth,
                       List<Integer> floorLevels,
                       List<Stairwell> stairwells,
                       List<int[]> exitTargets) {
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.floorLevels = Collections.unmodifiableList(
                    new ArrayList<>(floorLevels));
            this.stairwells = Collections.unmodifiableList(
                    new ArrayList<>(stairwells));
            this.exitTargets = Collections.unmodifiableList(
                    new ArrayList<>(exitTargets));
            this.floorWalkableMasks = new TreeMap<>();
            for (int floorZ : floorLevels) {
                boolean[] planarInterior = buildPlanarInteriorMask(model, floorZ);
                boolean[] walkable = new boolean[width * height];
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int index = y * width + x;
                        int type = model.obstacleGrid.get(x, y, floorZ);
                        walkable[index] = type == FireEscape.TYPE_EMPTY &&
                                (model.insideGrid.get(x, y, floorZ) == 1 ||
                                        planarInterior[index]);
                    }
                }
                // 封堵 IFC 体素化中的墙体缝隙：若某空气格 4 邻域中 ≥3 格是
                // 墙体或柱体，说明它是一条不该被通行的墙缝（体素化精度导致
                // 墙体中残留 TYPE_EMPTY 格子），从可行走掩码中排除，防止
                // A* 寻路利用这些缝隙穿墙。
                int[][] wallCheckDirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int index = y * width + x;
                        if (!walkable[index]) continue;
                        int wallNeighbors = 0;
                        for (int[] dir : wallCheckDirs) {
                            int nx = x + dir[0];
                            int ny = y + dir[1];
                            if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                                wallNeighbors++;
                                continue;
                            }
                            int ntype = model.obstacleGrid.get(nx, ny, floorZ);
                            if (ntype == FireEscape.TYPE_WALL ||
                                    ntype == FireEscape.TYPE_COLUMN) {
                                wallNeighbors++;
                            }
                        }
                        if (wallNeighbors >= 3) {
                            walkable[index] = false;
                        }
                    }
                }
                floorWalkableMasks.put(floorZ, walkable);
            }
            this.floorDoorGraphs = new TreeMap<>();
            for (int floorIndex = 0;
                 floorIndex < floorLevels.size();
                 floorIndex++) {
                int floorZ = floorLevels.get(floorIndex);
                floorDoorGraphs.put(
                        floorZ,
                        buildFloorDoorGraph(
                                model,
                                floorIndex,
                                floorLevels,
                                stairwells,
                                exitTargets,
                                floorWalkableMasks.get(floorZ)));
            }
            int cellCount = width * height * depth;
            this.exitMask = new boolean[cellCount];
            for (int[] exit : exitTargets) {
                exitMask[(exit[2] * height + exit[1]) * width + exit[0]] = true;
            }
            this.gScore = new double[cellCount];
            this.parent = new int[cellCount];
            this.visitedStamp = new int[cellCount];
            this.closedStamp = new int[cellCount];
            this.searchStamp = 0;
        }

        int nextSearchStamp() {
            searchStamp++;
            if (searchStamp == Integer.MAX_VALUE) {
                Arrays.fill(visitedStamp, 0);
                Arrays.fill(closedStamp, 0);
                searchStamp = 1;
            }
            return searchStamp;
        }

        Stairwell getStairwell(int id) {
            return id >= 0 && id < stairwells.size() ? stairwells.get(id) : null;
        }

        boolean isFloorLevel(int z) {
            return Collections.binarySearch(floorLevels, z) >= 0;
        }

        boolean isFloorWalkable(int x, int y, int z) {
            boolean[] mask = floorWalkableMasks.get(z);
            return mask != null && mask[y * width + x];
        }

        boolean isExitTarget(int x, int y, int z) {
            if (x < 0 || x >= width || y < 0 || y >= height ||
                    z < 0 || z >= depth) {
                return false;
            }
            return exitMask[(z * height + y) * width + x];
        }

        FloorDoorGraph getFloorDoorGraph(int floorIndex) {
            if (floorIndex < 0 || floorIndex >= floorLevels.size()) return null;
            return floorDoorGraphs.get(floorLevels.get(floorIndex));
        }

        int doorSeparatedZoneCount() {
            int count = 0;
            for (FloorDoorGraph graph : floorDoorGraphs.values()) {
                count += graph.doorSeparatedZoneCount();
            }
            return count;
        }

        int disconnectedZoneCount() {
            int count = 0;
            for (FloorDoorGraph graph : floorDoorGraphs.values()) {
                count += graph.disconnectedZoneCount();
            }
            return count;
        }
    }

    private static final class FloorDoorGraph {
        final int floorZ;
        final int width;
        final int height;
        final int[] zoneByCell;
        final int[] portalByCell;
        final List<DoorPortal> portals;
        final List<List<DoorTransition>> transitionsByZone;
        final int[] egressDistance;

        FloorDoorGraph(int floorZ,
                       int width,
                       int height,
                       int[] zoneByCell,
                       int[] portalByCell,
                       List<DoorPortal> portals,
                       List<List<DoorTransition>> transitionsByZone,
                       int[] egressDistance) {
            this.floorZ = floorZ;
            this.width = width;
            this.height = height;
            this.zoneByCell = zoneByCell;
            this.portalByCell = portalByCell;
            this.portals = portals;
            this.transitionsByZone = transitionsByZone;
            this.egressDistance = egressDistance;
        }

        int zoneAt(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) return -1;
            return zoneByCell[y * width + x];
        }

        int resolveZoneAt(int x, int y) {
            int index = y * width + x;
            int zoneId = zoneByCell[index];
            if (zoneId >= 0) return zoneId;

            int portalId = portalByCell[index];
            if (portalId < 0 || portalId >= portals.size()) return -1;
            DoorPortal portal = portals.get(portalId);
            int bestZone = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int touchingZone : portal.touchingZones) {
                int distance = egressDistance[touchingZone];
                if (distance >= 0 && distance < bestDistance) {
                    bestDistance = distance;
                    bestZone = touchingZone;
                }
            }
            return bestZone >= 0 ? bestZone :
                    (portal.touchingZones.length == 0 ?
                            -1 : portal.touchingZones[0]);
        }

        boolean requiresDoorTraversal(int x, int y) {
            int zoneId = resolveZoneAt(x, y);
            // 只对"有门路到疏散点但需穿门"的区域（egressDistance > 0）返回 true。
            // 不连通区域 egressDistance=-1，返回 false，避免误进入 SEEK_ROOM_DOOR
            // 后因 movesTowardEgress 永远 false 而原地卡死。
            return zoneId >= 0 && egressDistance[zoneId] > 0;
        }

        int egressDistance(int zoneId) {
            return zoneId >= 0 && zoneId < egressDistance.length ?
                    egressDistance[zoneId] : -1;
        }

        List<DoorTransition> transitionsFrom(int zoneId) {
            if (zoneId < 0 || zoneId >= transitionsByZone.size()) {
                return Collections.emptyList();
            }
            return transitionsByZone.get(zoneId);
        }

        boolean movesTowardEgress(int fromZone, int toZone) {
            int fromDistance = egressDistance(fromZone);
            int toDistance = egressDistance(toZone);
            return fromDistance > 0 &&
                    toDistance >= 0 &&
                    toDistance < fromDistance;
        }

        int doorSeparatedZoneCount() {
            int count = 0;
            for (int distance : egressDistance) {
                if (distance > 0) count++;
            }
            return count;
        }

        int disconnectedZoneCount() {
            int count = 0;
            for (int distance : egressDistance) {
                if (distance < 0) count++;
            }
            return count;
        }
    }

    private static final class DoorPortal {
        final int id;
        final int[] touchingZones;

        DoorPortal(int id, int[] touchingZones) {
            this.id = id;
            this.touchingZones = touchingZones;
        }
    }

    private static final class DoorTransition {
        final int portalId;
        final int destinationZone;

        DoorTransition(int portalId, int destinationZone) {
            this.portalId = portalId;
            this.destinationZone = destinationZone;
        }
    }

    private static final class Stairwell {
        final int id;
        final int width;
        final int height;
        final int depth;
        final boolean[] footprint;
        final boolean[] cells;
        final int[] horizontalDistance;

        Stairwell(int id,
                  int width,
                  int height,
                  int depth,
                  boolean[] footprint,
                  boolean[] cells) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.footprint = footprint;
            this.cells = cells;
            this.horizontalDistance = buildHorizontalDistance(
                    width, height, footprint);
        }

        boolean containsXY(int x, int y) {
            return x >= 0 && x < width && y >= 0 && y < height &&
                    footprint[y * width + x];
        }

        boolean containsCell(int x, int y, int z) {
            return x >= 0 && x < width &&
                    y >= 0 && y < height &&
                    z >= 0 && z < depth &&
                    cells[(z * height + y) * width + x];
        }

        int distanceAt(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) {
                return Integer.MAX_VALUE / 4;
            }
            return horizontalDistance[y * width + x];
        }

        private static int[] buildHorizontalDistance(
                int width, int height, boolean[] footprint) {
            int[] distances = new int[width * height];
            Arrays.fill(distances, -1);
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int index = 0; index < footprint.length; index++) {
                if (footprint[index]) {
                    distances[index] = 0;
                    queue.addLast(index);
                }
            }
            int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                int x = index % width;
                int y = index / width;
                for (int[] direction : directions) {
                    int nx = x + direction[0];
                    int ny = y + direction[1];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    int nextIndex = ny * width + nx;
                    if (distances[nextIndex] < 0) {
                        distances[nextIndex] = distances[index] + 1;
                        queue.addLast(nextIndex);
                    }
                }
            }
            return distances;
        }
    }

    private static final class PathNode implements Comparable<PathNode> {
        final int index;
        final double estimatedTotal;

        PathNode(int index, double estimatedTotal) {
            this.index = index;
            this.estimatedTotal = estimatedTotal;
        }

        public int compareTo(PathNode other) {
            return Double.compare(estimatedTotal, other.estimatedTotal);
        }
    }

    private static final class PathResult {
        final List<int[]> steps;
        final double cost;

        PathResult(List<int[]> steps, double cost) {
            this.steps = steps;
            this.cost = cost;
        }
    }

    private static final class StairChoice {
        final Stairwell stairwell;
        final PathResult path;
        final double totalCost;

        StairChoice(Stairwell stairwell, PathResult path, double totalCost) {
            this.stairwell = stairwell;
            this.path = path;
            this.totalCost = totalCost;
        }
    }

    private static final class DoorExitChoice {
        final int portalId;
        final int destinationZone;
        final PathResult path;
        final double totalCost;

        DoorExitChoice(int portalId,
                       int destinationZone,
                       PathResult path,
                       double totalCost) {
            this.portalId = portalId;
            this.destinationZone = destinationZone;
            this.path = path;
            this.totalCost = totalCost;
        }
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    /** 烟气毒性累积剂量（0=无, 1=致死），供外部观察中毒进度。 */
    public double getSmokeExposure() { return toxicDose; }
    /** 高温灼伤累积剂量（0=无, 1=致死）。 */
    public double getHeatDose() { return heatDose; }
    public String getEvacuationPhase() {
        return evacuationPhase == null ? "UNINITIALIZED" : evacuationPhase.name();
    }
    public int getSelectedStairId() { return selectedStairId; }
    public int getBlockedMoveAttempts() { return blockedMoveAttempts; }
}
