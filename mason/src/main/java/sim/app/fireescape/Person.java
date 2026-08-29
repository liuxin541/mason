package sim.app.fireescape;

import sim.engine.*;
import sim.util.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Person implements Steppable {
    private static final long serialVersionUID = 1L;
    private static final int MIN_FLOOR_SUPPORT_CELLS = 25;
    private static final double MIN_STORY_HEIGHT_METERS = 2.5;
    private static final double MAX_STORY_HEIGHT_METERS = 6.0;
    private static final double MIN_SPAWN_HEADROOM_METERS = 1.8;
    private static final int STAIR_CLEARANCE_CELLS = 1;
    private static final double STAIRWELL_SEARCH_RADIUS_METERS = 4.0;
    private static final int STAIRWELL_SPAWN_INTERVAL = 4;

    public boolean alive;
    public boolean escaped;
    public String deathCause;
    private double smokeExposure;
    private int x, y, z;
    private FireEscape model;
    private int moveCooldown = 0;

    public Person(FireEscape model, int x, int y, int z) {
        this.model = model;
        this.x = x;
        this.y = y;
        this.z = z;
        this.alive = true;
        this.escaped = false;
        this.smokeExposure = 0.0;
        this.deathCause = null;
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
                ", 楼层分布=" + spawnedByFloor +
                ", 楼梯间分布=" + spawnedInStairwell +
                ", 已生成人员=" + spawnCount);
        return agents;
    }

    /**
     * 按连续楼板支撑面积识别站立层，再用上方大面积结构层排除屋顶。
     * 相邻高度的小支撑面会被归入同一组，只保留支撑面积最大的 z 层。
     */
    private static List<Integer> detectValidFloors(FireEscape model) {
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

    @Override
    public void step(SimState state) {
        FireEscape fm = (FireEscape) state;
        if (!fm.showPersons) return;
        if (!alive || escaped) return;

        double fire = fm.fireGrid.get(x, y, z);
        double smoke = fm.getSmokeAt(x, y, z);

        // 1. 高温死亡判定
        if (fire >= FireEscape.T_DEATH) {
            alive = false; deathCause = "fire";
            fm.personGrid.remove(this); fm.recordDeath(deathCause); return;
        }

        // 2. 烟雾死亡判定
        double exposureIncrement = smoke * 0.1;
        if (smoke > 0.9) exposureIncrement += 0.3;
        smokeExposure += exposureIncrement;
        if (smokeExposure >= 1.0) {
            alive = false; deathCause = "smoke";
            fm.personGrid.remove(this); fm.recordDeath(deathCause); return;
        }

        // 3. 逃生成功判定
        if (fm.obstacleGrid.get(x, y, z) == FireEscape.TYPE_EXIT) {
            escaped = true;
            fm.personGrid.remove(this); return;
        }

        // 4. 移动概率判定
        double moveProb = fm.moveProbability * (1.0 - smoke * 0.7);
        if (moveCooldown > 0 || fm.random.nextDouble() >= moveProb) {
            moveCooldown = Math.max(0, moveCooldown - 1);
            return;
        }

        // ============================================================
        // ★ 核心逻辑：自动过门 + 找楼梯 + 避墙 + 底层冲出口
        // ============================================================
        // ============================================================
        boolean onStair = (fm.obstacleGrid.get(x, y, z) == FireEscape.TYPE_STAIR);
        boolean isGroundFloor = (z >= fm.groundFloorMinZ && z <= fm.groundFloorMaxZ);

        int bestX = x, bestY = y, bestZ = z;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    int nx = fm.personGrid.stx(x + dx);
                    int ny = fm.personGrid.sty(y + dy);
                    int nz = z + dz;

                    if (nz < 0 || nz >= fm.gridDepth) continue;

                    if (onStair && dz == 1) continue;

                    if (!isGroundFloor && dz != 0) {
                        if (fm.obstacleGrid.get(nx, ny, nz) != FireEscape.TYPE_STAIR &&
                                fm.obstacleGrid.get(x, y, z) != FireEscape.TYPE_STAIR) {
                            continue;
                        }
                    }

                    if (!fm.isPassable(nx, ny, nz)) continue;

                    double fireVal = fm.fireGrid.get(nx, ny, nz);
                    double smokeVal = fm.getSmokeAt(nx, ny, nz);
                    if (fireVal >= FireEscape.T_DEATH - 20.0) continue;
                    if (smokeVal >= 0.8) continue;

                    double currentScore = 0.0;
                    double minTargetDist = Double.MAX_VALUE;

                    if (isGroundFloor) {
                        for (int[] exit : fm.exits) {
                            double d = Math.sqrt(Math.pow(nx - exit[0], 2) + Math.pow(ny - exit[1], 2) + Math.pow(nz - exit[2], 2));
                            if (d < minTargetDist) minTargetDist = d;
                        }
                    } else {
                        if (fm.obstacleGrid.get(nx, ny, nz) == FireEscape.TYPE_STAIR) {
                            minTargetDist = -100.0;
                        } else {
                            double minStairDist = Double.MAX_VALUE;
                            for (int[] stair : fm.semanticInfo.stairPositions) {
                                double d = Math.sqrt(Math.pow(nx - stair[0], 2) + Math.pow(ny - stair[1], 2));
                                if (d < minStairDist) minStairDist = d;
                            }
                            minTargetDist = minStairDist * 2.0;
                        }
                    }

                    double wallPenalty = 0.0;
                    int wallCheckX = fm.personGrid.stx(x + dx * 2);
                    int wallCheckY = fm.personGrid.sty(y + dy * 2);
                    if (fm.obstacleGrid.get(wallCheckX, wallCheckY, nz) == FireEscape.TYPE_WALL) {
                        wallPenalty = 100.0;
                    }

                    if (fm.obstacleGrid.get(nx, ny, nz) == FireEscape.TYPE_DOOR) {
                        currentScore -= 50.0;
                    }

                    currentScore = minTargetDist + wallPenalty;

                    if (currentScore < bestScore) {
                        bestScore = currentScore;
                        bestX = nx; bestY = ny; bestZ = nz;
                    }
                }
            }
        }
        // ★ 暴力破除死锁：如果算了半天原地没动，强行推一把
        if (bestX == x && bestY == y && bestZ == z) {
            int[][] dirs = {{0,1},{1,0},{0,-1},{-1,0}};
            for (int[] d : dirs) {
                int nx = fm.personGrid.stx(x + d[0]);
                int ny = fm.personGrid.sty(y + d[1]);
                if (fm.isPassable(nx, ny, z)) {
                    bestX = nx; bestY = ny; bestZ = z;
                    break;
                }
            }
            if (bestX == x && bestY == y && bestZ == z) {
                int nz = z - 1;
                if (nz >= 0 && fm.insideGrid.get(x, y, nz) == 1) {
                    bestX = x; bestY = y; bestZ = nz;
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
        }
        // ★ 移动后重力校正：下落到楼板/楼梯上，且不离开建筑内部
        while (z > 0) {
            int below = fm.obstacleGrid.get(x, y, z - 1);
            if (below == FireEscape.TYPE_WALL || below == FireEscape.TYPE_STAIR) break;
            if (fm.insideGrid.get(x, y, z - 1) != 1) break;
            z--;
        }
        fm.personGrid.setObjectLocation(this, x, y, z);

        // 移动后检查高温
        fire = fm.fireGrid.get(x, y, z);
        if (fire >= FireEscape.T_DEATH) {
            alive = false; deathCause = "fire";
            fm.personGrid.remove(this); fm.recordDeath(deathCause);
        }

    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public double getSmokeExposure() { return smokeExposure; }
}
