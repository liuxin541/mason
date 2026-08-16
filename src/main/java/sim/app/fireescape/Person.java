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

        if (fire >= FireEscape.T_DEATH) {
            alive = false; deathCause = "fire";
            fm.personGrid.remove(this); fm.recordDeath(deathCause); return;
        }

        double exposureIncrement = smoke * 0.1;
        if (smoke > 0.9) exposureIncrement += 0.3;
        smokeExposure += exposureIncrement;
        if (smokeExposure >= 1.0) {
            alive = false; deathCause = "smoke";
            fm.personGrid.remove(this); fm.recordDeath(deathCause); return;
        }

        // ★ 3D 出口判断：无论在哪个楼层，到达出口（TYPE_EXIT）所在的格子就算逃出
        if (fm.obstacleGrid.get(x, y, z) == FireEscape.TYPE_EXIT) {
            escaped = true;
            fm.personGrid.remove(this); return;
        }

        double moveProb = fm.moveProbability * (1.0 - smoke * 0.7);
        if (moveCooldown > 0 || fm.random.nextDouble() >= moveProb) {
            moveCooldown = Math.max(0, moveCooldown - 1);
            return;
        }

        int bestX = x, bestY = y, bestZ = z;
        double bestScore = Double.NEGATIVE_INFINITY;

        // ★ 真正的 3D 邻域搜索 (27 个方向)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    int nx = fm.personGrid.stx(x + dx);
                    int ny = fm.personGrid.sty(y + dy);
                    int nz = z + dz;

                    // 边界检查：防止 Z 轴越界
                    if (nz < 0 || nz >= fm.gridDepth) continue;

                    // ★ 楼梯约束：在没有楼梯的楼层，绝对不允许上下移动
                    if (dz != 0) {
                        // 只有当前位置处于楼梯上，才允许上下移动
                        if (fm.obstacleGrid.get(x, y, z) != FireEscape.TYPE_STAIR) continue;
                    }

                    // 检查目标格子是否可通行
                    if (!fm.isPassable(nx, ny, nz)) continue;

                    // ★ 3D 寻路评分：改为 3D 欧几里得距离
                    double minExitDist = Double.MAX_VALUE;
                    for (int[] exit : fm.exits) {
                        double d = Math.sqrt(Math.pow(nx - exit[0], 2) + Math.pow(ny - exit[1], 2) + Math.pow(nz - exit[2], 2));
                        if (d < minExitDist) minExitDist = d;
                    }
                    double fireVal = fm.fireGrid.get(nx, ny, nz);
                    double smokeVal = fm.getSmokeAt(nx, ny, nz);
                    double score = -minExitDist - fireVal * 1.2 - smokeVal * 2.0;

                    if (score > bestScore) {
                        bestScore = score; bestX = nx; bestY = ny; bestZ = nz;
                    }
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

        // 移动后再次检查是否跑进了高温死区
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