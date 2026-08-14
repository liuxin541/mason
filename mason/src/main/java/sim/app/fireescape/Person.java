package sim.app.fireescape;

import sim.engine.*;
import sim.util.*;

public class Person implements Steppable {
    private static final long serialVersionUID = 1L;

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