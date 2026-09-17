package sim.app.fireescape;

import junit.framework.TestCase;
import sim.field.grid.DoubleGrid3D;
import sim.field.grid.IntGrid3D;
import sim.field.grid.SparseGrid3D;

public class PersonSpawnTest extends TestCase {
    private static final int WIDTH = 17;
    private static final int HEIGHT = 17;
    private static final int DEPTH = 20;
    private static final int STAIR_X = 8;
    private static final int STAIR_Y = 8;
    private static final int STAIR_CLEARANCE_CELLS = 1;
    private static final int STAIRWELL_OUTER_RADIUS_CELLS = 8;
    private static final int HEADROOM_CELLS = 4;

    public void testSpawnPopulationCoversLowestFloorAndUsesStairwellPlatforms() {
        FireEscape model = createTwoFloorModel();
        model.personCount = 24;

        Person.spawnPopulation(model);

        int lowestFloorCount = 0;
        int upperFloorCount = 0;
        int stairwellPlatformCount = 0;
        for (Person person : model.persons) {
            assertNotNull(person);
            assertTrue(Person.isValidSpawnCell(
                    model, person.getX(), person.getY(), person.getZ()));

            if (person.getZ() == 2) lowestFloorCount++;
            else if (person.getZ() == 10) upperFloorCount++;
            else fail("人员出生在未识别的楼层 z=" + person.getZ());

            assertNoStairInBodySpace(model, person);
            int stairDistance = Math.abs(person.getX() - STAIR_X) +
                    Math.abs(person.getY() - STAIR_Y);
            if (stairDistance <= STAIRWELL_OUTER_RADIUS_CELLS) {
                stairwellPlatformCount++;
            }
        }

        // 楼层轮询应让最低层和上层都有人，人数差最多为 1。
        assertTrue(lowestFloorCount > 0);
        assertTrue(upperFloorCount > 0);
        assertTrue(Math.abs(lowestFloorCount - upperFloorCount) <= 1);
        // 定期优先取楼梯间候选池，保证平台空间确实会参与出生。
        assertTrue(stairwellPlatformCount > 0);
    }

    public void testFlatCellsInsideFormerTwoMeterExclusionCanSpawn() {
        FireEscape model = createTwoFloorModel();
        model.insideGrid.setTo(0);

        // 低层点距楼梯 2 个体素，旧 2 米规则会排除；现在只要不碰楼梯且净空充足，
        // 它和上层楼梯投影正下方的平整地面都应进入楼梯间平台候选池。
        allowStandingColumn(model, 10, STAIR_Y, 2);
        model.insideGrid.set(9, STAIR_Y, 2, 1);
        allowStandingColumn(model, STAIR_X, STAIR_Y, 10);
        model.personCount = 2;

        Person.spawnPopulation(model);

        boolean foundNearStairPlatform = false;
        boolean foundBelowOtherLevelStairProjection = false;
        for (Person person : model.persons) {
            assertNotNull(person);
            assertNoStairInBodySpace(model, person);
            if (person.getX() == 10 && person.getY() == STAIR_Y &&
                    person.getZ() == 2) {
                foundNearStairPlatform = true;
            }
            if (person.getX() == STAIR_X && person.getY() == STAIR_Y &&
                    person.getZ() == 10) {
                foundBelowOtherLevelStairProjection = true;
            }
        }

        assertTrue(foundNearStairPlatform);
        assertTrue(foundBelowOtherLevelStairProjection);
    }

    public void testInsufficientHeadroomBelowStairIsRejected() {
        FireEscape model = createEmptyModel(7, 7, 8);
        for (int x = 0; x < model.gridWidth; x++) {
            for (int y = 0; y < model.gridHeight; y++) {
                model.obstacleGrid.set(x, y, 1, FireEscape.TYPE_WALL);
            }
        }
        model.obstacleGrid.set(3, 3, 5, FireEscape.TYPE_STAIR);

        // 站立层 z=2 上方 1.8 米范围内存在楼梯，不能把人物生成在低矮梯底。
        assertFalse(Person.isValidSpawnCell(model, 3, 3, 2));
    }

    public void testEnclosedUpperStairwellCanRecoverFromOutsideMask() {
        FireEscape model = createTwoFloorModel();
        model.insideGrid.setTo(0);
        for (int z = 2; z < 17; z++) {
            for (int x = 0; x < WIDTH; x++) {
                model.obstacleGrid.set(x, 0, z, FireEscape.TYPE_WALL);
                model.obstacleGrid.set(x, HEIGHT - 1, z, FireEscape.TYPE_WALL);
            }
            for (int y = 0; y < HEIGHT; y++) {
                model.obstacleGrid.set(0, y, z, FireEscape.TYPE_WALL);
                model.obstacleGrid.set(WIDTH - 1, y, z, FireEscape.TYPE_WALL);
            }
        }
        model.personCount = 2;

        Person.spawnPopulation(model);

        boolean foundUpperStairwell = false;
        for (Person person : model.persons) {
            assertNotNull(person);
            assertNoStairInBodySpace(model, person);
            if (person.getZ() == 10) {
                int stairDistance = Math.abs(person.getX() - STAIR_X) +
                        Math.abs(person.getY() - STAIR_Y);
                foundUpperStairwell = stairDistance <= STAIRWELL_OUTER_RADIUS_CELLS;
            }
        }
        // 上层 initially outside 的楼梯间仅在二维封闭且有楼板、净空时恢复。
        assertTrue(foundUpperStairwell);
    }

    public void testWindowIsEnclosureButNotPassable() {
        FireEscape model = createEmptyModel(3, 3, 3);
        model.insideGrid.set(1, 1, 1, 1);
        model.obstacleGrid.set(1, 1, 1, FireEscape.TYPE_WINDOW);

        // WINDOW 保留围护语义，但人员通行列表中不包含它。
        assertFalse(Person.canOccupy(model, 1, 1, 1));
    }

    private FireEscape createTwoFloorModel() {
        FireEscape model = createEmptyModel(WIDTH, HEIGHT, DEPTH);

        // z=1、9 是两层楼板，z=17 是屋面；可站立层分别为 z=2、10。
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                model.obstacleGrid.set(x, y, 1, FireEscape.TYPE_WALL);
                model.obstacleGrid.set(x, y, 9, FireEscape.TYPE_WALL);
                model.obstacleGrid.set(x, y, 17, FireEscape.TYPE_WALL);
            }
        }

        // 楼梯体素用于验证逐层平台识别和人物身体三维碰撞检查。
        model.obstacleGrid.set(STAIR_X, STAIR_Y, 2, FireEscape.TYPE_STAIR);
        return model;
    }

    private void allowStandingColumn(FireEscape model, int x, int y, int z) {
        for (int dz = 0; dz < HEADROOM_CELLS; dz++) {
            model.insideGrid.set(x, y, z + dz, 1);
        }
    }

    private void assertNoStairInBodySpace(FireEscape model, Person person) {
        int minimumZ = Math.max(0, person.getZ() - 1);
        int maximumZ = Math.min(
                model.gridDepth - 1, person.getZ() + HEADROOM_CELLS - 1);
        for (int dx = -STAIR_CLEARANCE_CELLS; dx <= STAIR_CLEARANCE_CELLS; dx++) {
            for (int dy = -STAIR_CLEARANCE_CELLS; dy <= STAIR_CLEARANCE_CELLS; dy++) {
                int x = person.getX() + dx;
                int y = person.getY() + dy;
                if (x < 0 || x >= model.gridWidth || y < 0 || y >= model.gridHeight) {
                    continue;
                }
                for (int z = minimumZ; z <= maximumZ; z++) {
                    assertTrue("人物身体空间与楼梯相交",
                            model.obstacleGrid.get(x, y, z) != FireEscape.TYPE_STAIR);
                }
            }
        }
    }

    private FireEscape createEmptyModel(int width, int height, int depth) {
        FireEscape model = new FireEscape(1234L);
        model.gridWidth = width;
        model.gridHeight = height;
        model.gridDepth = depth;
        model.deltaX = 0.5;
        model.deltaZ = 0.5;
        model.obstacleGrid = new IntGrid3D(width, height, depth, FireEscape.TYPE_EMPTY);
        model.insideGrid = new IntGrid3D(width, height, depth, 1);
        model.fuelGrid = new IntGrid3D(width, height, depth, 0);
        model.fireGrid = new DoubleGrid3D(width, height, depth, FireEscape.T_AMBIENT);
        model.personGrid = new SparseGrid3D(width, height, depth);
        return model;
    }
}
