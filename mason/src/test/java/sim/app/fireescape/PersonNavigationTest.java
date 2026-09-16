package sim.app.fireescape;

import junit.framework.TestCase;
import sim.field.grid.DoubleGrid3D;
import sim.field.grid.IntGrid3D;
import sim.field.grid.SparseGrid3D;

public class PersonNavigationTest extends TestCase {
    public void testUpperFloorPersonLeavesRoomBeforeSeekingStair() {
        FireEscape model = createTwoFloorRoomEvacuationModel();
        Person person = addPerson(model, 2, 4, 7);
        boolean observedRoomDoorPhase = false;
        boolean leftRoom = false;
        boolean soughtStairAfterDoor = false;

        for (int step = 0; step < 800 && !person.escaped; step++) {
            person.step(model);
            String phase = person.getEvacuationPhase();
            observedRoomDoorPhase |= "SEEK_ROOM_DOOR".equals(phase);
            if (person.getX() > 4) leftRoom = true;
            if (leftRoom &&
                    ("SEEK_STAIR".equals(phase) ||
                            "QUEUE_FOR_STAIR".equals(phase) ||
                            "DESCEND_STAIR".equals(phase))) {
                soughtStairAfterDoor = true;
            }
        }

        assertTrue("高层房间人员没有先寻找有效房门", observedRoomDoorPhase);
        assertTrue("高层房间人员没有穿过房门进入走廊", leftRoom);
        assertTrue("穿门后没有切换到楼梯疏散阶段", soughtStairAfterDoor);
        assertTrue("高层房间人员没有最终完成疏散", person.escaped);
    }

    public void testGroundFloorPersonLeavesRoomBeforeSeekingExit() {
        FireEscape model = createGroundFloorRoomEvacuationModel();
        Person person = addPerson(model, 2, 3, 1);
        boolean observedRoomDoorPhase = false;
        boolean soughtExitAfterDoor = false;

        for (int step = 0; step < 500 && !person.escaped; step++) {
            person.step(model);
            String phase = person.getEvacuationPhase();
            observedRoomDoorPhase |= "SEEK_ROOM_DOOR".equals(phase);
            if (person.getX() > 4 &&
                    ("SEEK_EXIT".equals(phase) ||
                            "EXIT_QUEUE".equals(phase))) {
                soughtExitAfterDoor = true;
            }
        }

        assertTrue("一楼房间人员没有先寻找有效房门", observedRoomDoorPhase);
        assertTrue("一楼人员穿门后没有切换到出口疏散阶段", soughtExitAfterDoor);
        assertTrue("一楼房间人员没有最终完成疏散", person.escaped);
    }

    public void testOccupiedRoomDoorCreatesDoorQueue() {
        FireEscape model = createTwoFloorRoomEvacuationModel();
        Person front = new Person(model, 4, 4, 7);
        Person rear = new Person(model, 3, 4, 7);
        model.persons = new Person[]{front, rear};
        model.personGrid.setObjectLocation(front, 4, 4, 7);
        model.personGrid.setObjectLocation(rear, 3, 4, 7);

        boolean observedDoorQueue = false;
        for (int step = 0; step < 20 && !observedDoorQueue; step++) {
            rear.step(model);
            observedDoorQueue =
                    "DOOR_QUEUE".equals(rear.getEvacuationPhase());
            assertNoOverlap(front, rear);
        }
        assertTrue("房门被占用时后方人员没有进入门口排队状态", observedDoorQueue);

        for (int step = 0; step < 800 && (!front.escaped || !rear.escaped); step++) {
            if (!front.escaped) front.step(model);
            if (!rear.escaped) rear.step(model);
            assertNoOverlap(front, rear);
        }
        assertTrue(front.escaped);
        assertTrue(rear.escaped);
    }

    /**
     * 楼梯间门户自动修复测试：复现 IFC 缺失楼梯间门的问题——
     * 左右两翼被完整实体墙隔断（无任何门），楼梯在右翼。
     * 修复后墙体应被开凿成 TYPE_DOOR 门洞，左翼人员经门洞到楼梯并疏散。
     */
    public void testStairwellDoorwayRepairConnectsWingToStair() {
        FireEscape model = createSealedStairwellModel();
        int doorsBefore = countVoxelsOfType(model, FireEscape.TYPE_DOOR);
        model.repairStairwellDoorways();
        int doorsAfter = countVoxelsOfType(model, FireEscape.TYPE_DOOR);
        assertTrue("隔墙没有被开凿出 TYPE_DOOR 门洞", doorsAfter > doorsBefore);

        // 幂等性：再次调用不应继续开新洞
        model.repairStairwellDoorways();
        assertEquals("修复重复执行产生了多余门洞",
                doorsAfter, countVoxelsOfType(model, FireEscape.TYPE_DOOR));

        // 开洞后左翼人员应能穿过新门洞找到楼梯并完成疏散
        Person person = addPerson(model, 2, 4, 7);
        for (int step = 0; step < 600 && !person.escaped; step++) {
            person.step(model);
        }
        assertTrue("走廊与楼梯间打通后人员仍未能经楼梯疏散", person.escaped);
    }

    /** 构建被实体墙完全封死的双翼模型：楼梯与出口都在右翼，左翼无任何通路。 */
    private FireEscape createSealedStairwellModel() {
        FireEscape model = createModel(11, 9, 15);
        fillSlab(model, 0);
        fillSlab(model, 6);
        fillSlab(model, 13);
        // 竖向隔墙 x=6 完全封死上下两层（楼板层除外）
        for (int z = 1; z <= 5; z++) {
            for (int y = 0; y < model.gridHeight; y++) {
                model.obstacleGrid.set(6, y, z, FireEscape.TYPE_WALL);
            }
        }
        for (int z = 7; z <= 12; z++) {
            for (int y = 0; y < model.gridHeight; y++) {
                model.obstacleGrid.set(6, y, z, FireEscape.TYPE_WALL);
            }
        }
        // 楼梯与出口都在右翼
        for (int z = 1; z <= 7; z++) {
            model.obstacleGrid.set(9, 4, z, FireEscape.TYPE_STAIR);
        }
        model.obstacleGrid.set(10, 4, 1, FireEscape.TYPE_DOOR);
        return model;
    }

    private int countVoxelsOfType(FireEscape model, int type) {
        int count = 0;
        for (int z = 0; z < model.gridDepth; z++) {
            for (int y = 0; y < model.gridHeight; y++) {
                for (int x = 0; x < model.gridWidth; x++) {
                    if (model.obstacleGrid.get(x, y, z) == type) count++;
                }
            }
        }
        return count;
    }

    public void testUpperFloorPersonUsesVoxelStairAndThenExits() {
        FireEscape model = createTwoFloorEvacuationModel();
        Person person = addPerson(model, 2, 4, 7);
        int highestZ = person.getZ();
        boolean reachedStair = false;
        boolean reachedGround = false;

        for (int step = 0; step < 500 && !person.escaped; step++) {
            person.step(model);
            highestZ = Math.max(highestZ, person.getZ());
            if (model.obstacleGrid.get(
                    person.getX(), person.getY(), person.getZ()) ==
                    FireEscape.TYPE_STAIR) {
                reachedStair = true;
            }
            if (person.getZ() == 1) reachedGround = true;
        }

        assertTrue("高层人员没有进入体素楼梯", reachedStair);
        assertTrue("高层人员没有下降到一楼", reachedGround);
        assertTrue("一楼人员没有经外门完成逃生", person.escaped);
        assertEquals("疏散过程不应向更高楼层移动", 7, highestZ);
    }

    public void testOccupiedCorridorCellCreatesQueueWithoutOverlap() {
        FireEscape model = createGroundFloorCorridorModel();
        Person front = new Person(model, 6, 3, 1);
        Person rear = new Person(model, 5, 3, 1);
        model.persons = new Person[]{front, rear};
        model.personGrid.setObjectLocation(front, 6, 3, 1);
        model.personGrid.setObjectLocation(rear, 5, 3, 1);
        boolean observedQueue = false;

        for (int step = 0; step < 300 && (!front.escaped || !rear.escaped); step++) {
            // 后方人员先执行，确保它会遇到尚未移动的前方人员。
            if (!rear.escaped) rear.step(model);
            observedQueue |= "EXIT_QUEUE".equals(rear.getEvacuationPhase());
            assertNoOverlap(front, rear);

            if (!front.escaped) front.step(model);
            assertNoOverlap(front, rear);
        }

        assertTrue("拥堵时后方人员没有进入出口排队状态", observedQueue);
        assertTrue(front.escaped);
        assertTrue(rear.escaped);
    }

    public void testOccupiedStairEntranceCreatesQueueWithoutOverlap() {
        FireEscape model = createTwoFloorEvacuationModel();
        // 上层用柱体围出单格宽瓶颈，确保后方人员不能绕过前方人员；
        // 使用柱体而不是墙，避免测试障碍被楼层识别误当成新的楼板。
        for (int x = 0; x < model.gridWidth; x++) {
            for (int y = 0; y < model.gridHeight; y++) {
                boolean outsideRoom = x < 1 || x > 6 || y < 2 || y > 6;
                boolean blocksPassage = x == 4 && y != 4;
                if (outsideRoom || blocksPassage) {
                    model.obstacleGrid.set(x, y, 7, FireEscape.TYPE_COLUMN);
                }
            }
        }
        model.obstacleGrid.set(5, 4, 7, FireEscape.TYPE_STAIR);

        Person front = new Person(model, 4, 4, 7);
        Person rear = new Person(model, 3, 4, 7);
        model.persons = new Person[]{front, rear};
        model.personGrid.setObjectLocation(front, 4, 4, 7);
        model.personGrid.setObjectLocation(rear, 3, 4, 7);
        boolean observedStairQueue = false;

        for (int step = 0; step < 800 && (!front.escaped || !rear.escaped); step++) {
            if (!rear.escaped) rear.step(model);
            observedStairQueue |= "QUEUE_FOR_STAIR".equals(rear.getEvacuationPhase());
            assertNoOverlap(front, rear);

            if (!front.escaped) front.step(model);
            assertNoOverlap(front, rear);
        }

        assertTrue("楼梯入口拥堵时后方人员没有进入排队状态", observedStairQueue);
        assertTrue(front.escaped);
        assertTrue(rear.escaped);
    }

    public void testStairDescentResumesAfterTemporaryUnsafeLanding() {
        FireEscape model = createTwoFloorEvacuationModel();
        Person person = addPerson(model, 4, 4, 7);

        for (int step = 0; step < 100 &&
                model.obstacleGrid.get(person.getX(), person.getY(), person.getZ()) !=
                        FireEscape.TYPE_STAIR; step++) {
            person.step(model);
        }
        assertEquals(FireEscape.TYPE_STAIR,
                model.obstacleGrid.get(person.getX(), person.getY(), person.getZ()));

        // 暂时封锁下一层全部落脚点，验证 NO_SAFE_PATH 等待结束后仍恢复下楼阶段。
        for (int x = 0; x < model.gridWidth; x++) {
            for (int y = 0; y < model.gridHeight; y++) {
                model.fireGrid.set(x, y, 1, FireEscape.T_DEATH - 10.0);
            }
        }
        boolean observedNoSafePath = false;
        for (int step = 0; step < 100 && !observedNoSafePath; step++) {
            person.step(model);
            observedNoSafePath = "NO_SAFE_PATH".equals(person.getEvacuationPhase());
        }
        assertTrue("下一层暂时危险时没有进入等待状态", observedNoSafePath);

        model.fireGrid.setTo(FireEscape.T_AMBIENT);
        for (int step = 0; step < 500 && !person.escaped; step++) {
            person.step(model);
        }
        assertTrue("危险解除后没有恢复下楼并完成疏散", person.escaped);
    }

    public void testDisconnectedStairsAreNotMergedByPlanarProjection() {
        FireEscape model = createThreeFloorSplitStairModel();
        Person person = addPerson(model, 3, 4, 13);
        int upperStairId = -1;
        int lowerStairId = -1;

        for (int step = 0; step < 900 && !person.escaped; step++) {
            person.step(model);
            int type = model.obstacleGrid.get(
                    person.getX(), person.getY(), person.getZ());
            if (type == FireEscape.TYPE_STAIR && person.getZ() >= 8) {
                upperStairId = person.getSelectedStairId();
            } else if (type == FireEscape.TYPE_STAIR && person.getZ() <= 6) {
                lowerStairId = person.getSelectedStairId();
            }
        }

        assertTrue("人员没有进入上层楼梯", upperStairId >= 0);
        assertTrue("人员没有在中间层重新选择下层楼梯", lowerStairId >= 0);
        assertTrue("三维不连通的楼梯被二维投影错误合并",
                upperStairId != lowerStairId);
        assertTrue(person.escaped);
    }

    private FireEscape createTwoFloorEvacuationModel() {
        FireEscape model = createModel(11, 9, 15);
        fillSlab(model, 0);
        fillSlab(model, 6);
        fillSlab(model, 13);

        // 用一列连续 TYPE_STAIR 表示连接 z=7 与 z=1 的楼梯。
        // semanticInfo.stairPositions 保持为空，用于验证寻路直接识别体素楼梯。
        for (int z = 1; z <= 7; z++) {
            model.obstacleGrid.set(5, 4, z, FireEscape.TYPE_STAIR);
        }
        model.obstacleGrid.set(10, 4, 1, FireEscape.TYPE_DOOR);
        return model;
    }

    private FireEscape createTwoFloorRoomEvacuationModel() {
        FireEscape model = createModel(11, 9, 15);
        fillSlab(model, 0);
        fillSlab(model, 6);
        fillSlab(model, 13);

        // 上层房间和走廊由实体墙分隔，只保留一个 TYPE_DOOR 门洞。
        // 楼梯位于门外，验证人物必须先穿门，不能隔墙直接进入找楼梯阶段。
        for (int y = 0; y < model.gridHeight; y++) {
            model.obstacleGrid.set(4, y, 7, FireEscape.TYPE_COLUMN);
        }
        model.obstacleGrid.set(4, 4, 7, FireEscape.TYPE_DOOR);
        for (int z = 1; z <= 7; z++) {
            model.obstacleGrid.set(7, 4, z, FireEscape.TYPE_STAIR);
        }
        model.obstacleGrid.set(10, 4, 1, FireEscape.TYPE_DOOR);
        return model;
    }

    private FireEscape createGroundFloorRoomEvacuationModel() {
        FireEscape model = createModel(11, 7, 9);
        fillSlab(model, 0);
        fillSlab(model, 6);

        // 一楼房门不是最终出口：人物穿过房门进入走廊后，
        // 还必须继续前往右侧连接室外的出口门。
        for (int y = 0; y < model.gridHeight; y++) {
            model.obstacleGrid.set(4, y, 1, FireEscape.TYPE_COLUMN);
        }
        model.obstacleGrid.set(4, 3, 1, FireEscape.TYPE_DOOR);
        model.obstacleGrid.set(10, 3, 1, FireEscape.TYPE_DOOR);
        return model;
    }

    private FireEscape createGroundFloorCorridorModel() {
        FireEscape model = createModel(11, 7, 9);
        fillSlab(model, 0);
        fillSlab(model, 6);
        model.insideGrid.setTo(0);
        for (int x = 1; x < model.gridWidth; x++) {
            model.insideGrid.set(x, 3, 1, 1);
        }
        model.obstacleGrid.set(10, 3, 1, FireEscape.TYPE_DOOR);
        return model;
    }

    private FireEscape createThreeFloorSplitStairModel() {
        FireEscape model = createModel(11, 9, 21);
        fillSlab(model, 0);
        fillSlab(model, 6);
        fillSlab(model, 12);
        fillSlab(model, 19);

        // 两段楼梯二维投影相邻，但 z=7 平台将它们隔开，必须识别为两个组件。
        for (int z = 1; z <= 6; z++) {
            model.obstacleGrid.set(5, 4, z, FireEscape.TYPE_STAIR);
        }
        for (int z = 8; z <= 11; z++) {
            model.obstacleGrid.set(4, 4, z, FireEscape.TYPE_STAIR);
        }
        // 模拟 IFC 离散化产生的单体素楼梯顶面间隙。
        model.obstacleGrid.set(4, 4, 12, FireEscape.TYPE_EMPTY);
        model.obstacleGrid.set(10, 4, 1, FireEscape.TYPE_DOOR);
        return model;
    }

    private FireEscape createModel(int width, int height, int depth) {
        FireEscape model = new FireEscape(1234L);
        model.gridWidth = width;
        model.gridHeight = height;
        model.gridDepth = depth;
        model.deltaX = 0.5;
        model.deltaZ = 0.5;
        model.deltaT = 0.5;
        model.moveProbability = 1.0;
        model.obstacleGrid = new IntGrid3D(
                width, height, depth, FireEscape.TYPE_EMPTY);
        model.insideGrid = new IntGrid3D(width, height, depth, 1);
        model.fuelGrid = new IntGrid3D(width, height, depth, 0);
        model.fireGrid = new DoubleGrid3D(
                width, height, depth, FireEscape.T_AMBIENT);
        model.smokeGrid = new DoubleGrid3D(width, height, depth, 0.0);
        model.personGrid = new SparseGrid3D(width, height, depth);
        return model;
    }

    private void fillSlab(FireEscape model, int z) {
        for (int x = 0; x < model.gridWidth; x++) {
            for (int y = 0; y < model.gridHeight; y++) {
                model.obstacleGrid.set(x, y, z, FireEscape.TYPE_WALL);
            }
        }
    }

    private Person addPerson(FireEscape model, int x, int y, int z) {
        Person person = new Person(model, x, y, z);
        model.persons = new Person[]{person};
        model.personGrid.setObjectLocation(person, x, y, z);
        return person;
    }

    private void assertNoOverlap(Person first, Person second) {
        if (first.escaped || second.escaped) return;
        assertFalse("两个疏散人员占用了同一个体素",
                first.getX() == second.getX() &&
                        first.getY() == second.getY() &&
                        first.getZ() == second.getZ());
    }

    /**
     * 高温灼伤剂量测试：全场 150°C(423K) 干热环境，按 SFPE 耐受曲线
     * 约 1 分钟致死；人员无论如何移动都持续受热，用于标定累积速率与死因。
     */
    public void testModerateHeatAccumulatesToDeath() {
        FireEscape model = createModel(8, 8, 6);
        fillSlab(model, 0);
        for (int z = 0; z < model.gridDepth; z++) {
            for (int y = 0; y < model.gridHeight; y++) {
                for (int x = 0; x < model.gridWidth; x++) {
                    model.fireGrid.set(x, y, z, 423.0);   // 150°C
                }
            }
        }
        Person person = addPerson(model, 3, 3, 1);

        int deathStep = -1;
        for (int step = 0; step < 300; step++) {
            person.step(model);
            if (!person.alive) { deathStep = step; break; }
        }
        assertTrue("150°C 环境中人员没有在 300 步（150 秒）内死亡", !person.alive);
        assertEquals("150°C 致死应统计为烧死", "fire", person.deathCause);
        // 每步 0.5 秒，理论约 127 步（≈63 秒）致死，留宽松区间容差。
        assertTrue("150°C 致死时间偏离 SFPE 约 1 分钟标定，实际=" + deathStep,
                deathStep >= 80 && deathStep <= 200);
    }

    /**
     * 烟气毒性剂量测试：全场满烟（代理 CO≈8000ppm），按 Purser FED
     * 约 8 分钟致死；取代旧逻辑满烟 1 秒即死的不合理标定。
     */
    public void testHeavySmokeAccumulatesToDeath() {
        FireEscape model = createModel(8, 8, 6);
        fillSlab(model, 0);
        for (int z = 0; z < model.gridDepth; z++) {
            for (int y = 0; y < model.gridHeight; y++) {
                for (int x = 0; x < model.gridWidth; x++) {
                    model.smokeGrid.set(x, y, z, 1.0);
                }
            }
        }
        Person person = addPerson(model, 3, 3, 1);

        int deathStep = -1;
        for (int step = 0; step < 1300; step++) {
            person.step(model);
            if (!person.alive) { deathStep = step; break; }
        }
        assertTrue("满烟环境中人员没有在 1300 步（约 10.8 分钟）内死亡",
                !person.alive);
        assertEquals("满烟致死应统计为烟熏死", "smoke", person.deathCause);
        // 每步 0.5 秒，理论约 960 步（8 分钟）致死，留宽松区间容差。
        assertTrue("满烟致死时间偏离 8 分钟标定，实际=" + deathStep,
                deathStep >= 700 && deathStep <= 1150);
    }

    /** 达到致死温度（473K/200°C）应当步立即死亡，不走剂量累积过程。 */
    public void testLethalTemperatureKillsInstantly() {
        FireEscape model = createModel(8, 8, 6);
        fillSlab(model, 0);
        Person person = addPerson(model, 3, 3, 1);
        model.fireGrid.set(3, 3, 1, FireEscape.T_DEATH);

        person.step(model);

        assertFalse("身处 200°C 的人员没有立即死亡", person.alive);
        assertEquals("fire", person.deathCause);
        assertEquals("立即死亡应计入烧死统计", 1, model.getDeadByFire());
    }
}
