package sim.app.fireescape;

import sim.engine.*;
import sim.field.grid.*;
import java.util.List;

public class FireSpread implements Steppable {
    private long _stepCount = 0;  // 方案A: 性能计时
    private int[][] windowCells = null;
    private int[][] fuelCells = null;
    private int[][] exitColumns = null;
    private int cacheW = -1, cacheH = -1, cacheD = -1;
    private static final long serialVersionUID = 1L;

    private static final int TYPE_EMPTY  = 0;
    private static final int TYPE_WALL   = 1;
    private static final int TYPE_FUEL   = 2;
    private static final int TYPE_DOOR   = 3;
    private static final int TYPE_EXIT   = 4;
    private static final int TYPE_STAIR  = 5;

    // ===== 优化项 9: 魔法数字常量化 =====
    private static final double SIGMA_SB               = 5.670374419e-8;
    private static final double EMISSIVITY             = 0.8;
    private static final double MIN_DIST               = 0.1;
    private static final double CFL_SAFETY             = 1.0;  // 方案A微调: 搬移式平流 frac<=1 即守恒稳定, 放宽后 DT 翻倍(仿真提速)
    private static final double DIFFUSION_SAFETY       = 0.4;
    private static final double GAUSSIAN_SIGMA_FACTOR  = 0.5;
    private static final double TEMP_FACTOR_MIN        = 0.1;
    private static final double TEMP_FACTOR_MAX        = 1.5;
    private static final double SMOKE_FLOOR            = 0.002;
    private static final double SMOKE_CEIL             = 1.0;
    // ===== 方案1:火源烟气注入速率(单位浓度/秒),控制产烟强度 =====
    private static final double SMOKE_SOURCE_RATE      = 35.0;  // 方案A微调: 增强产烟, 烟形更饱满  // 优化: 平衡产烟量,避免浓度过高  // 优化: 大幅增大产烟量  // 优化: 增大产烟量,确保烟雾能蔓延到整个建筑
    private static final int    EXIT_RADIUS            = 2;
    private static final double KILOWATT_TO_WATT       = 1000.0;
    private static final double CONV_RADIUS_METERS     = 1.0;
    private static final double MIN_SMOKE_FOR_ADVECT   = 0.002;  // 方案A微调6: 与收集阈值一致;  // 方案A: 平流只处理真实烟区(SMOKE_FLOOR=0.002 的全建筑格跳过), 性能提升10x+
    private static final double SMALL_DT_EPS           = 1e-6;
    private static final double VEL_EPS                = 1e-6;
    private static final double GRAD_EPS               = 1e-6;
    private static final double WGT_FACE               = 1.0;
    private static final double WGT_EDGE               = 0.5;
    private static final double WGT_CORNER             = 0.25;
    private static final double WGT_WALL_FACTOR        = 0.1;
    // ===== 羽流卷吸参数 =====
    private static final double PLUME_ENTRAIN_COEFF    = 0.071;
    private static final double PLUME_ENTRAIN_MAX      = 0.3;
    private static final int    PLUME_MAX_HEIGHT_CELLS = 8;
    // ===== 分层抑制参数 =====
    private static final double STRAT_RI_COEFF         = 1.0;
    // ===== 高温烟辐射温度阈值 =====
    private static final double HOT_SMOKE_T_THRESHOLD  = 500.0;

    private double[][][] C_afterDiff_buf = null;
    private double[][][] C_afterAdvection_buf = null;
    private double[][][] T_afterAdvection_buf = null;

    // ===== 方案B: Stable Fluids 简化流场 (粗网格 stride=4, 1m格子) =====
    private double[] flowU = null, flowV = null, flowW = null;  // 粗网格速度场 (m/s) 一维拍平 idx=(cz*ch+cy)*cw+cx
    private int cachedFlowW = 0, cachedFlowH = 0, cachedFlowD = 0;
    private static final int FLOW_STRIDE = 4;
    private static final double FLOW_BUOY_COEFF = 0.05;   // 浮力系数 (T-K → m/s²) 方案B微调: 增强卷吸
    private static final double FLOW_DAMP = 0.985;        // 每步速度衰减
    private static final int FLOW_PROJ_ITERS = 8;   // 方案B优化: 粗网格CFL小, 8次投影足够        // 压力投影迭代
    private static final double FLOW_MAX_V = 1.0;          // 速度上限 m/s 方案B微调: 降低水平速度, 避免烟柱被水平打散
    private static final double FLOW_DT = 0.25;           // 流场时间步(粗网格CFL安全)
    private double[] flowP = null;  // 压力场(一维)
    private boolean[] flowOpenCache = null;  // 流通掩码缓存(网格不变复用)
    private int cachedW = 0, cachedH = 0, cachedD = 0;

    // ===== 优化项 6: 预分配辐射/羽流辅助数组 =====
    private int[] scratchNeighbors3x3_x = null;
    private int[] scratchNeighbors3x3_y = null;
    private int cachedNeighborSize = -1;

    // ===== 修复: 动态检测每层实际顶棚(替代硬编码 ceilingZ=d-1) =====
    private int[] ceilingZs = null;
    private boolean[] ceilingNearLayer = null;
    private int cachedCeilW = -1, cachedCeilH = -1, cachedCeilD = -1;

    private int[] detectCeilings(int w, int h, int d, int[][][] obstacle, int[][][] inside) {
        java.util.List<Integer> ceilings = new java.util.ArrayList<>();
        int[] wallByZ = new int[d];
        int[] insideByZ = new int[d];
        for (int z = 0; z < d; z++) {
            int wc = 0, ic = 0;
            for (int x = 0; x < w; x++)
                for (int y = 0; y < h; y++) {
                    if (obstacle[x][y][z] == TYPE_WALL) wc++;
                    if (inside[x][y][z] == 1) ic++;
                }
            wallByZ[z] = wc;
            insideByZ[z] = ic;
        }
        for (int z = 2; z < d - 1; z++) {
            int cur = insideByZ[z];
            int above = insideByZ[z + 1];
            int below = insideByZ[z - 1];
            if (cur > 0 && cur < (above + below) * 0.4 && wallByZ[z] > cur) {
                int ceilZ = z - 1;
                if (ceilZ >= 0 && (ceilings.isEmpty() || ceilZ - ceilings.get(ceilings.size() - 1) > 3)) {
                    ceilings.add(ceilZ);
                }
            }
        }
        if (ceilings.isEmpty()) ceilings.add(d - 1);
        int[] result = new int[ceilings.size()];
        for (int i = 0; i < ceilings.size(); i++) result[i] = ceilings.get(i);
        return result;
    }

    private void rebuildCeilingNearLayer(int d, int[] cz, int nearBelow) {
        ceilingNearLayer = new boolean[d];
        for (int c : cz) {
            for (int k = 0; k <= nearBelow && c - k >= 0; k++) {
                ceilingNearLayer[c - k] = true;
            }
        }
    }

    private boolean hasLineOfSight(int x1, int y1, int z1,
                                    int x2, int y2, int z2,
                                    int[][][] obstacle) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        int maxZ = obstacle[0][0].length;

        while (true) {
            if (x1 < 0 || x1 >= obstacle.length || y1 < 0 || y1 >= obstacle[0].length)
                return false;

            int zLo = Math.min(z1, z2);
            int zHi = Math.max(z1, z2);
            if (zLo < 0) zLo = 0;
            if (zHi >= maxZ) zHi = maxZ - 1;
            for (int zi = zLo; zi <= zHi; zi++) {
                if (obstacle[x1][y1][zi] == TYPE_WALL) return false;
            }

            if (x1 == x2 && y1 == y2) return true;

            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x1 += sx; }
            if (e2 < dx) { err += dx; y1 += sy; }
        }
    }

    private void addRadiation(int cx, int cy, int cz, double Q_rad, double radiusMeters,
                              FireEscape fire, double[][][] nextT) {
        int w = fire.gridWidth, h = fire.gridHeight, d = fire.gridDepth;
        int[][][] obstacle = fire.obstacleGrid.field;
        int[][][] inside = fire.insideGrid.field;
        final double dx = fire.deltaX;
        final double dz = fire.deltaZ;
        final double dt = fire.deltaT;
        final double rho = fire.rhoAir;
        final double cp = fire.cpAir;
        final double T_AMB = FireEscape.T_AMBIENT;
        // ===== 优化项 7: 常量提前到循环外 =====
        final double dxSq = dx * dx;
        final double dzSq = dz * dz;
        final double cellVol = dxSq * dz;

        double T_src = fire.fireGrid.get(cx, cy, cz);
        // 已用四次乘法,无需修改
        double T4_src = T_src * T_src * T_src * T_src;
        double T4_amb = T_AMB * T_AMB * T_AMB * T_AMB;

        double emissivePower = EMISSIVITY * SIGMA_SB * (T4_src - T4_amb);
        if (emissivePower < 1e-12) return;

        double baseFlux = Q_rad * KILOWATT_TO_WATT / (4.0 * Math.PI * radiusMeters * radiusMeters);
        double scaleFactor = baseFlux / emissivePower;
        final double fourPiInv = 1.0 / (4.0 * Math.PI);

        int radR = (int)(radiusMeters / dx);
        int radZ = (int)(radiusMeters / dz);
        double radR2 = radiusMeters * radiusMeters;

        for (int dx2 = -radR; dx2 <= radR; dx2++) {
            int nx_base = cx + dx2;
            double dx2sq_dx2 = (double)dx2 * dx2 * dxSq;
            for (int dy2 = -radR; dy2 <= radR; dy2++) {
                int ny_base = cy + dy2;
                double distSqXY = dx2sq_dx2 + (double)dy2 * dy2 * dxSq;
                for (int dz2 = -radZ; dz2 <= radZ; dz2++) {
                    if (dx2 == 0 && dy2 == 0 && dz2 == 0) continue;
                    int nx = nx_base, ny = ny_base, nz = cz + dz2;
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h || nz < 0 || nz >= d)
                        continue;
                    if (inside[nx][ny][nz] == 0)
                        continue;
                    if (!hasLineOfSight(cx, cy, cz, nx, ny, nz, obstacle))
                        continue;

                    double distSq = distSqXY + (double)dz2 * dz2 * dzSq;
                    // ===== 优化项 7: 用 distSq 直接比较,开方只在必要时 =====
                    if (distSq < MIN_DIST * MIN_DIST) continue;
                    double dist = Math.sqrt(distSq);
                    if (dist > radiusMeters) continue;

                    // ===== 优化项 5: 增加指数衰减(基于 smokeAbsorptionCoeff 的烟气吸收) =====
                    double distAbs = fire.smokeAbsorptionCoeff * dist;
                    double beerLambert = Math.exp(-distAbs);
                    double q_rad = scaleFactor * emissivePower * fourPiInv / distSq * beerLambert;
                    double deltaT_rad = q_rad * dt / (rho * cp * cellVol);

                    if (!Double.isNaN(deltaT_rad) && !Double.isInfinite(deltaT_rad)) {
                        nextT[nx][ny][nz] += deltaT_rad;
                    }
                }
            }
        }
    }

    @Override
    public void step(SimState state) {
        FireEscape fire = (FireEscape) state;
        fire.currentTime += fire.deltaT;
        long _t0 = System.nanoTime();

        final int w = fire.gridWidth;
        final int h = fire.gridHeight;
        final int d = fire.gridDepth;
        final int[][][] obstacle = fire.obstacleGrid.field;
        final int[][][] fuel = fire.fuelGrid.field;
        final int[][][] inside = fire.insideGrid.field;
        final double[][][] T = fire.fireGrid.field;
        final double[][][] nextT = fire.nextFireGrid.field;
        final double[][][] C = fire.smokeGrid.field;
        final double[][][] nextC = fire.nextSmokeGrid.field;
        final List<int[]> fireSources = fire.getFireSourcePositions();
        final int nSources = fireSources.size();

        // ===== 修复: 动态检测每层实际顶棚(只在尺寸变化时重算) =====
        if (cachedCeilW != w || cachedCeilH != h || cachedCeilD != d) {
            ceilingZs = detectCeilings(w, h, d, obstacle, inside);
            rebuildCeilingNearLayer(d, ceilingZs, 3);
            cachedCeilW = w; cachedCeilH = h; cachedCeilD = d;
            StringBuilder sb = new StringBuilder("[Smoke] 检测到顶棚层 z=");
            for (int c : ceilingZs) sb.append(c).append(" ");
            System.out.println(sb.toString());
        // 方案A性能: 缓存窗户格与出口列(网格变化时重建)
        if (windowCells == null || cacheW != w || cacheH != h || cacheD != d) {
            int wcN = 0, ecN = 0, fcN = 0;
            for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) {
                boolean hasExitCol = false;
                for (int z = 0; z < d; z++) {
                    if (obstacle[x][y][z] == 6) wcN++;
                    if (obstacle[x][y][z] == TYPE_FUEL) fcN++;
                    if (obstacle[x][y][z] == TYPE_EXIT) hasExitCol = true;
                }
                if (hasExitCol) ecN++;
            }
            windowCells = new int[wcN][3]; exitColumns = new int[ecN][2]; fuelCells = new int[fcN][3];
            int wi = 0, ei = 0, fi = 0;
            for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) {
                boolean hasExitCol = false;
                for (int z = 0; z < d; z++) {
                    if (obstacle[x][y][z] == 6) { windowCells[wi][0]=x; windowCells[wi][1]=y; windowCells[wi][2]=z; wi++; }
                    if (obstacle[x][y][z] == TYPE_FUEL) { fuelCells[fi][0]=x; fuelCells[fi][1]=y; fuelCells[fi][2]=z; fi++; }
                    if (obstacle[x][y][z] == TYPE_EXIT) hasExitCol = true;
                }
                if (hasExitCol) { exitColumns[ei][0]=x; exitColumns[ei][1]=y; ei++; }
            }
            cacheW = w; cacheH = h; cacheD = d;
            System.out.println("[Perf] 缓存: 窗户格=" + wcN + " 出口列=" + ecN + " 燃料格=" + fcN);
        }
        }

        final double T_AMB = FireEscape.T_AMBIENT;
        final double T_FLAME = FireEscape.T_FLAME;
        final double T_IGN = FireEscape.T_IGNITION;
        final double T_MAX = FireEscape.T_MAX;
        final double dx = fire.deltaX;
        final double dz = fire.deltaZ;
        // ===== 优化项 7: 常量提前 =====
        final double dxSq = dx * dx;
        final double dzSq = dz * dz;
        final double twoDx = 2.0 * dx;
        final double cellVolume = dxSq * dz;
        // ===== 优化项 6/8: final 标量缓存,便于 CFL 计算 =====
        final double _maxDrift = fire.smokeDriftMaxSpeed;
        final double _smkCoeff = fire.smokeDriftCoeff;
        final double _cooling = fire.coolingRate;
        final double _smokeDecay = fire.smokeDecayRate;
        final double _D_smoke_base = fire.baseSmokeSpread;
        final double _hConv = fire.h_conv;
        final double _radFrac = fire.radiationFraction;
        final double _alphaAir = fire.alpha_air;
        final double _condBoost = fire.conductionBoost;
        final double _exhaust = fire.exhaustRate;
        final double _cjRadius = fire.ceilingJetRadius;
        final double _cjStrength = fire.ceilingJetStrength;
        final double _cdCoef = fire.Cd;
        final double _rho = fire.rhoAir;
        final double _cp = fire.cpAir;
        final double _g = fire.g;
        final double _smkAbsorb = fire.smokeAbsorptionCoeff;
        final double _burnRate = fire.burnRatePerArea;
        final double _smkYield = fire.smokeYield;
        final double _fuelHeat = fire.fuelHeatValue;

        // ===== 优化项 8: 更严格的 dt (CFL + 扩散稳定性) =====
        double alpha_eff_global = _alphaAir * _condBoost;
        double dt_diff_x = DIFFUSION_SAFETY * dxSq / Math.max(alpha_eff_global, 1e-10);
        double dt_diff_z = DIFFUSION_SAFETY * dzSq / Math.max(alpha_eff_global, 1e-10);
        double dt_cfl = CFL_SAFETY * Math.min(dx, dz) / Math.max(_maxDrift, SMALL_DT_EPS);
        double dt = Math.min(fire.deltaT, Math.min(Math.min(dt_cfl, dt_diff_x), dt_diff_z));
        if (dt <= 0) dt = fire.deltaT;
        final double DT_FINAL = dt;
        final double rho = _rho;
        final double cp = _cp;

        // 性能优化: 先全部设默认值(用 Arrays.fill 更快), 再只遍历内部格拷贝当前值
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++) {
                java.util.Arrays.fill(nextT[x][y], T_AMB);
                java.util.Arrays.fill(nextC[x][y], 0.0);
            }
        for (int[] cell : fire.insideCells) {
            int cx = cell[0], cy = cell[1], cz = cell[2];
            nextT[cx][cy][cz] = T[cx][cy][cz];
            nextC[cx][cy][cz] = C[cx][cy][cz];
        }

        // ===== 优化项 6: 缓存数组扩展 =====
        if (cachedW != w || cachedH != h || cachedD != d) {
            C_afterDiff_buf = new double[w][h][d];
            C_afterAdvection_buf = new double[w][h][d];
            T_afterAdvection_buf = new double[w][h][d];
            cachedW = w; cachedH = h; cachedD = d;
        }
        double[][][] C_afterDiff = C_afterDiff_buf;
        double[][][] C_afterAdvection = C_afterAdvection_buf;
        double[][][] T_afterAdvection = T_afterAdvection_buf;

        if (nSources == 0) {
            for (int x = 0; x < w; x++)
                for (int y = 0; y < h; y++)
                    for (int z = 0; z < d; z++) {
                        if (obstacle[x][y][z] == TYPE_WALL || inside[x][y][z] == 0) continue;
                        nextT[x][y][z] = T[x][y][z] - _cooling * DT_FINAL * (T[x][y][z] - T_AMB);
                        if (nextT[x][y][z] < T_AMB) nextT[x][y][z] = T_AMB;
                        // ===== 优化项 14: Stefan-Boltzmann 辐射冷却 =====
                        addRadiativeCooling(nextT, x, y, z, T_AMB, DT_FINAL, _smkAbsorb, rho, cp, dxSq, dz);
                        nextC[x][y][z] = C[x][y][z] * (1.0 - _smokeDecay * DT_FINAL);
                    }
            swapBuffers(fire, nextT, nextC);
            return;
        }

        double Q_total = fire.t2FireGrowth * fire.currentTime * fire.currentTime;
        Q_total = Math.min(Q_total, fire.Q_steady);
        if (Q_total < 40.0) Q_total = 40.0;  // 方案A微调: 最小功率40kW, 火一启动即可见烟
        double Q_ratio = Q_total / fire.Q_steady;
        int srcRadius = 1 + (int)(Math.floor((fire.maxSourceRadius - 1) * Math.sqrt(Q_ratio)));
        if (srcRadius > fire.maxSourceRadius) srcRadius = fire.maxSourceRadius;
        double T_source = T_FLAME;  // 方案A微调: 火源核心恒为火焰温度(功率只影响烟量/辐射), 否则初期浮力不足烟无法上升
        double srcRadiusSq05 = (double)srcRadius * srcRadius * GAUSSIAN_SIGMA_FACTOR;

        // ===== 火焰物理参数输出（Heskestad 火焰高度公式）=====
        double Q_per_src = Q_total / Math.max(1, nSources);
        // 火源等效直径: 体素直径乘以0.6(实际燃烧区小于体素网格), 避免小火源火焰被压得过低
        double flameDiameter = 2.0 * srcRadius * dx * 0.6;
        for (int idx = 0; idx < nSources; idx++) {
            fire.fireSourceHeatRelease[idx] = Q_per_src;
            // Heskestad: L = 0.235 * Q^(2/5) - 1.02 * D  (Q in kW, L in m)
            double L_flame = 0.235 * Math.pow(Q_per_src, 0.4) - 1.02 * flameDiameter;
            if (L_flame < 0.8) L_flame = 0.8;  // 最小火焰高度(展示用, 避免看不见)
            fire.fireSourceFlameHeight[idx] = L_flame;
            // 羽流半径: 近似随高度线性扩展 (McCaffrey)
            fire.fireSourcePlumeRadius[idx] = 0.5 * flameDiameter + 0.18 * L_flame;
        }

        // ===================================================================
        // (2) 火灾源注入 + 优化项 11: 羽流卷吸 (Plume Entrainment)
        // 方案A性能: 辐射/对流/热传导统一每4步执行一次(等效4*DT), 均为慢传热过程
        boolean doConduction = ((_stepCount & 3) == 0);

        // ===================================================================
        for (int idx = 0; idx < nSources; idx++) {
            int[] fp = fireSources.get(idx);
            int cx = fp[0], cy = fp[1], cz = fp[2];
            for (int dx2 = -srcRadius; dx2 <= srcRadius; dx2++) {
                for (int dy2 = -srcRadius; dy2 <= srcRadius; dy2++) {
                    for (int dz2 = -srcRadius; dz2 <= srcRadius; dz2++) {
                        int nx = fire.fireGrid.stx(cx + dx2);
                        int ny = fire.fireGrid.sty(cy + dy2);
                        int nz = fire.fireGrid.stz(cz + dz2);
                        if (inside[nx][ny][nz] == 1 && obstacle[nx][ny][nz] != TYPE_WALL) {
                            double distSq = (double)dx2*dx2 + (double)dy2*dy2 + (double)dz2*dz2;
                            double distLim = (double)srcRadius + 0.5;
                            if (distSq > distLim * distLim) continue;
                            // ===== 优化项 7: exp 查表太复杂,保留 Math.exp 但公式不变 =====
                            double factor = Math.exp(-distSq / srcRadiusSq05);
                            double val = T_AMB + factor * (T_source - T_AMB);
                            if (val > nextT[nx][ny][nz]) nextT[nx][ny][nz] = val;
                            // ===== 方案1:火源持续注入烟气(不修改火,只加烟) =====
                            // 烟气产量与火势 Q_ratio 成正比,高斯衰减分布,确保火在烟就在
                            double smokeInject = Q_ratio * factor * SMOKE_SOURCE_RATE * DT_FINAL;
                            if (smokeInject > 0) {
                                // 方案C: 火焰核心(火源格附近)少产烟, 烟在火焰上方/外围成羽, 避免火焰被烟糊死
                                double coreW = (dx2 == 0 && dy2 == 0 && dz2 == 0) ? 0.25 : 1.0;
                                nextC[nx][ny][nz] += smokeInject * coreW;
                            }
                        }
                    }
                }
            }

            // ===== 优化项 11: 羽流卷吸 =====
            double Q_src = Q_total / nSources;
            double Q_13 = Math.pow(Q_src, 1.0 / 3.0);
            int plumeTop = Math.min(cz + PLUME_MAX_HEIGHT_CELLS, d);
            for (int z = cz + 1; z < plumeTop; z++) {
                if (inside[cx][cy][z] == 0) break;
                double hAbove = (z - cz) * dz;
                double entFactor = PLUME_ENTRAIN_COEFF * Q_13
                                 * Math.pow(hAbove, 5.0 / 3.0) * DT_FINAL;
                if (entFactor > PLUME_ENTRAIN_MAX) entFactor = PLUME_ENTRAIN_MAX;

                for (int dx2 = -1; dx2 <= 1; dx2++) {
                    int nx = cx + dx2;
                    if (nx < 0 || nx >= w) continue;
                    for (int dy2 = -1; dy2 <= 1; dy2++) {
                        if (dx2 == 0 && dy2 == 0) continue;
                        int ny = cy + dy2;
                        if (ny < 0 || ny >= h) continue;
                        if (inside[nx][ny][z] == 0) continue;

                        double dT = entFactor * nextT[nx][ny][z];
                        nextT[cx][cy][z] += dT;
                        nextT[nx][ny][z] -= dT;
                        double dC = entFactor * nextC[nx][ny][z];
                        nextC[cx][cy][z] += dC;
                        nextC[nx][ny][z] -= dC;
                    }
                }
            }
        }

        // ===================================================================
        // (3) 可燃物燃烧释热
        // ===================================================================
        for (int[] fc : fuelCells) {
            int x = fc[0], y = fc[1], z = fc[2];
                    if (fuel[x][y][z] <= 0) continue;
                    if (T[x][y][z] < T_IGN) continue;

                    double tempFactor = Math.min(TEMP_FACTOR_MAX,
                            (T[x][y][z] - T_IGN) / (T_FLAME - T_IGN) + 0.5);
                    if (tempFactor < TEMP_FACTOR_MIN) tempFactor = TEMP_FACTOR_MIN;
                    double burnRate = _burnRate * tempFactor;
                    double fuelBurned = Math.min(fuel[x][y][z], burnRate * dxSq * DT_FINAL);

                    if (fuelBurned > 0) {
                        double heatRelease = fuelBurned * _fuelHeat;
                        double deltaT_comb = heatRelease / (rho * cp * cellVolume);
                        if (!Double.isNaN(deltaT_comb)) nextT[x][y][z] += deltaT_comb;
                        nextC[x][y][z] += (fuelBurned * _smkYield) / cellVolume;
                        fuel[x][y][z] -= fuelBurned;
                        if (fuel[x][y][z] <= 0) {
                            fuel[x][y][z] = 0;
                            obstacle[x][y][z] = TYPE_EMPTY;
                        }
                    }
        }

        // ===================================================================
        // (4) 热辐射
        // ===================================================================
        if (doConduction) {
        double Q_per_source = Q_total / nSources;
        double Q_rad_source = _radFrac * Q_per_source;
        for (int idx = 0; idx < nSources; idx++) {
            int[] fp = fireSources.get(idx);
            addRadiation(fp[0], fp[1], fp[2], Q_rad_source, fire.radiationRadius, fire, nextT);
        }
        }

        // ===================================================================
        // (5) 对流换热 - 优化项 2: 用 nextT 而非 T
        // ===================================================================
        if (doConduction) {
        double convRadius = CONV_RADIUS_METERS;
        int convR = (int)(convRadius / dx), convZ = (int)(convRadius / dz);
        for (int idx = 0; idx < nSources; idx++) {
            int[] fp = fireSources.get(idx);
            int cx = fp[0], cy = fp[1], cz = fp[2];
            for (int dx2 = -convR; dx2 <= convR; dx2++)
                for (int dy2 = -convR; dy2 <= convR; dy2++)
                    for (int dz2 = -convZ; dz2 <= convZ; dz2++) {
                        if (dx2 == 0 && dy2 == 0 && dz2 == 0) continue;
                        int nx = fire.fireGrid.stx(cx + dx2);
                        int ny = fire.fireGrid.sty(cy + dy2);
                        int nz = fire.fireGrid.stz(cz + dz2);
                        if (inside[nx][ny][nz] == 0 || obstacle[nx][ny][nz] == TYPE_WALL)
                            continue;
                        double distSq = (double)dx2*dx2*dxSq + (double)dy2*dy2*dxSq + (double)dz2*dz2*dzSq;
                        if (distSq > convRadius * convRadius) continue;
                        double dist = Math.sqrt(distSq);
                        double distFactor = 1.0 - dist / convRadius;
                        double h_eff = _hConv * distFactor;
                        // ===== 优化项 2: nextT[nx] 替代 T[nx],使本步已加热的格子可立即参与换热 =====
                        double q_conv = h_eff * (T_FLAME - nextT[nx][ny][nz]) * (dx * dz);
                        double deltaT_conv = q_conv * (4.0 * DT_FINAL) / (rho * cp * cellVolume);
                        if (!Double.isNaN(deltaT_conv)) nextT[nx][ny][nz] += deltaT_conv;
                    }
        }
        }  // end doConduction 对流

        // ===================================================================
        // (6) 热传导 - 优化项 4: Laplacian 动态权重(各向异性 + 墙体修正)
        // ===================================================================
        double alpha_eff = _alphaAir * _condBoost;
        double Fo = alpha_eff * (doConduction ? 4.0 * DT_FINAL : 0.0) / dxSq;
        // ===== 优化项 4: 各向异性网格比 =====
        double anisotropyZ = dx / dz;  // z 方向细,权重调整
        double anisotropyFace = anisotropyZ;

        // ===== 优化项 4: 6 面邻居静态表(避免循环内反复 new 数组) =====
        final int[][] NB6_FACES = new int[][] { {-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1} };

        if (doConduction) {
        for (int x = 1; x < w-1; x++) {
            for (int y = 1; y < h-1; y++) {
                for (int z = 1; z < d-1; z++) {
                    if (obstacle[x][y][z] == TYPE_WALL || inside[x][y][z] == 0) continue;
                    double centerT = nextT[x][y][z];
                    double laplacian = 0.0;
                    double totalW = 0.0;
                    // ===== 优化项 4: 仅 6 面邻居(保持性能),保留各向异性+墙体权重修正 =====
                    for (int n = 0; n < 6; n++) {
                        int dx2 = NB6_FACES[n][0], dy2 = NB6_FACES[n][1], dz2 = NB6_FACES[n][2];
                        int nx = x + dx2, ny = y + dy2, nz = z + dz2;
                        boolean in_bounds = (nx >= 0 && nx < w && ny >= 0 && ny < h && nz >= 0 && nz < d);
                        boolean is_air = in_bounds && obstacle[nx][ny][nz] != TYPE_WALL && inside[nx][ny][nz] == 1;
                        double wgt = WGT_FACE;
                        // z 方向网格更细,热流交换更频繁,按比例略增权重
                        if (dz2 != 0) wgt *= anisotropyFace * 0.7;
                        if (!in_bounds || !is_air) {
                            wgt *= WGT_WALL_FACTOR;
                        }
                        double neighborT = T_AMB;
                        if (is_air) {
                            neighborT = nextT[nx][ny][nz];
                        }
                        laplacian += wgt * (neighborT - centerT);
                        totalW += wgt;
                    }
                    if (totalW > 0) {
                        nextT[x][y][z] += Fo * (laplacian / totalW);
                    }
                    if (Double.isNaN(nextT[x][y][z])) nextT[x][y][z] = T_AMB;
                    if (nextT[x][y][z] > T_MAX) nextT[x][y][z] = T_MAX;
                    if (nextT[x][y][z] < T_AMB) nextT[x][y][z] = T_AMB;
                }
            }
        }
        }  // end doConduction

        long _tI = System.nanoTime();  // 注入段结束(扩散前)
        long _tD0, _tD1, _tB;  // 扩散/平流计时(LES与经验模式分别赋值)
        // ===================================================================
        // (7)(8) 烟雾输运：LES 求解器 或 原有经验模型
        // ===================================================================
        if (fire.useLESSmoke && fire.smokeLESolver != null) {
            // ===== LES 模式：显式速度场 + 压力投影 + Smagorinsky + Semi-Lagrangian =====
            // 求解器直接更新 nextT（温度平流）和 nextC（烟雾平流+扩散）
            // 羽流、顶棚射流由浮力驱动的速度场自然产生，无需经验模型
            fire.smokeLESolver.step(nextT, nextC, obstacle, inside, DT_FINAL);
            // 同步到 afterAdvection 场（回写段用这些变量），一次 insideCells 遍历
            for (int[] sc : fire.insideCells) {
                int cx = sc[0], cy = sc[1], cz = sc[2];
                C_afterAdvection[cx][cy][cz] = nextC[cx][cy][cz];
                T_afterAdvection[cx][cy][cz] = nextT[cx][cy][cz];
                C_afterDiff[cx][cy][cz] = nextC[cx][cy][cz];
            }
            _tD0 = _tI;  // LES模式无扩散段, 计时归零
            _tD1 = _tI;
            _tB = System.nanoTime();
        } else {
        // ===================================================================
        // (7) 烟气扩散 - 优化项 13: 分层抑制(Richardson 数)
        // 方案A性能: 快照+收集合并为一次insideCells遍历, 避免77万全网格快照与26万收集两遍
        java.util.ArrayList<int[]> smokeCells = new java.util.ArrayList<int[]>();
        for (int[] sc : fire.insideCells) {
            int cx = sc[0], cy = sc[1], cz = sc[2];
            double cv = nextC[cx][cy][cz];
            C_afterDiff[cx][cy][cz] = cv;
            C_afterAdvection[cx][cy][cz] = cv;  // 初值=nextC(扩散前), 平流在其上增量更新
            T_afterAdvection[cx][cy][cz] = nextT[cx][cy][cz];  // 初值=nextT
            if (cv > 0.002) smokeCells.add(sc);  // 方案A微调6: 阈值0.002(性能), 烟铺开由溢出+射流维持高浓度
        }
        _tD0 = System.nanoTime();  // 烟格收集结束
        if ((_stepCount & 31) == 0) System.out.println("[DBG6] smokeCells.size=" + smokeCells.size());
        for (int[] sc : smokeCells) {
            int x = sc[0], y = sc[1], z = sc[2];
                    double laplacianC = 0.0;
                    double totalW = 0.0;
                    // ===== 优化项 4+13: 烟气扩散 6 面邻居 + Richardson 分层 =====
                    for (int n = 0; n < 6; n++) {
                        int dx2 = NB6_FACES[n][0], dy2 = NB6_FACES[n][1], dz2 = NB6_FACES[n][2];
                        double wgt = WGT_FACE;
                        if (dz2 != 0) wgt *= anisotropyZ * 0.7;
                        int nx = x + dx2, ny = y + dy2, nz = z + dz2;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && nz >= 0 && nz < d) {
                            if (obstacle[nx][ny][nz] != TYPE_WALL && inside[nx][ny][nz] == 1) {
                                double neighborC = nextC[nx][ny][nz];
                                laplacianC += wgt * neighborC;
                                totalW += wgt;
                            }
                        }
                    }
                    if (totalW > 0) {
                        // ===== 优化项 13: 简化 Richardson 数,稳定分层时抑制 z 方向扩散 =====
                        // 只在垂直方向温度有梯度时抑制,避免 Math.* 开销
                        double T_above = nextT[x][y][Math.min(z+1, d-1)];
                        double T_below = nextT[x][y][Math.max(z-1, 0)];
                        double stratFactor = 1.0;
                        if (T_above > T_below + 2.0) {
                            // 上热下冷:稳定分层,抑制扩散系数
                            double dT = T_above - T_below;
                            double denom = dT * dT / dxSq + 1.0;
                            double Ri_simp = (_g / T_AMB) * (dT / dz) / denom;
                            if (Ri_simp > 0) {
                                stratFactor = 1.0 / (1.0 + STRAT_RI_COEFF * Ri_simp);
                            }
                        }
                        double D_eff = _D_smoke_base * stratFactor * 2.0;  // 水平扩散x2: 慢一点更真实
                        // 方案A: 顶棚层扩散从×10降到×2.5, 避免"被子"效果; 水平推进交给顶棚射流
                        if (ceilingNearLayer[z]) D_eff *= 4.0;  // 方案C: 顶棚层水平扩散加强, 烟层铺满整层
                        double diff = D_eff * (laplacianC / totalW - nextC[x][y][z]) * DT_FINAL / dxSq;
                        C_afterDiff[x][y][z] = nextC[x][y][z] + diff;
                    }
        }

        _tD1 = System.nanoTime();  // 烟格扩散结束
    _tB = System.nanoTime();  // 扩散结束

        int dbgV = 0; double dbgVA = 0;
        // ===================================================================
        // (8) 烟气平流 + 温度对流 - 优化项 1: 修正方向;优化项 3: 顶棚射流水平增强
        // ===================================================================
        final int ceilingZ = d - 1;
        double cjR = _cjRadius / dx;
        // ===== 性能修正: 限制顶棚射流搜索半径上限 =====
        final int CJ_MAX_RI = 14;  // 方案C: 10→14格(7m), 烟层水平铺展+顶棚扩散加强; 半径20格性能爆炸(2471ms/步)
        int cjRi = (int)Math.ceil(cjR);
        if (cjRi > CJ_MAX_RI) cjRi = CJ_MAX_RI;
        if (cjRi < 2) cjRi = 2;
        double cjR_eff = (double)cjRi;
        double cjR2_05 = cjR_eff * cjR_eff * GAUSSIAN_SIGMA_FACTOR;
        double cjStrengthEff = _cjStrength * DT_FINAL;
        // ===== 性能优化: 顶棚射流在最顶3层处理,降低温度和浓度门限 =====
        final int CJ_Z_MIN = 0;  // 修复: 不再用网格顶部, 改用 ceilingNearLayer 动态判断
        final double CJ_EXCESS_T = 10.0;  // 方案A微调6: 放宽射流温度门限, 冷却烟层也参与水平铺展
        final double CJ_MIN_C = 0.02;  // 方案A: 只对真实顶棚烟触发射流, 避免全建筑两遍循环

        // ===== 方案B: Stable Fluids 简化流场 (每步更新粗网格速度场) =====
        long _tF0 = System.nanoTime();
        solveCoarseFlow(w, h, d, obstacle, inside, nextT, C_afterDiff, smokeCells);
        long _tF1 = System.nanoTime();
        double flowMs = (_tF1 - _tF0) / 1e6;

        // 方案A性能: 平流只遍历有烟格(烟区格数, 非77万全网格)
        for (int[] sc : smokeCells) {  // 复用扩散段收集的烟格, 省一次26万遍历
            int x = sc[0], y = sc[1], z = sc[2];
                    if (C_afterDiff[x][y][z] < MIN_SMOKE_FOR_ADVECT) continue;

                    double T_cell = nextT[x][y][z];
                    double excessT = Math.max(0.0, T_cell - T_AMB);

                    // === 方案A: Boussinesq 浮力驱动, 热烟整体上升, 冷却后停止 ===
                    // vz ∝ ΔT, 去掉 0.5 下限保底(冷却烟不应持续上升)
                    double vz = _smkCoeff * 12.0 * (excessT / T_AMB);  // 方案A微调: 浮力增强, 烟柱更快到达顶棚
                    // 方案B微调: 浓烟格即使温度冷却也持续上升到层顶(烟比空气轻, 密度差驱动, 烟柱直到撞楼板才停)
                    if (C_afterAdvection[x][y][z] > 0.02 && vz < 0.8) vz = 0.8;  // 上升速度0.8m/s, 慢一点更真实
                    if ((_stepCount & 7) == 0 && !fireSources.isEmpty()) { int[] dbgFp = fireSources.get(0); if (x==dbgFp[0] && y==dbgFp[1] && z>=dbgFp[2]-1 && z<=dbgFp[2]+12) System.out.println("[DBG5] step=" + _stepCount + " z=" + z + " T=" + T_cell + " excessT=" + excessT + " Cdiff=" + C_afterDiff[x][y][z] + " Cadv=" + C_afterAdvection[x][y][z] + " vz=" + vz + " frac=" + Math.min(1.0, Math.min(vz, _maxDrift) * DT_FINAL / dz)); }
                    if (vz > _maxDrift) vz = _maxDrift;
                    if (vz < 0) vz = 0;
                    if (vz > VEL_EPS && z < d - 1) {
                        // 真实火灾: 烟柱往上穿非wall格(楼梯井通道), 遇wall(楼板)就停
                        double frac = Math.min(1.0, vz * DT_FINAL / dz);
                        double deltaC2 = C_afterAdvection[x][y][z] * frac;
                        double deltaT2 = (T_afterAdvection[x][y][z] - T_AMB) * frac;
                        int nz = z + 1;
                        double decay = 1.0;
                        int maxRise = 24;  // 方案C: 24层(0.95^24≈0.29仍可达顶), 烟囱效应让烟窜到整栋楼; 40层循环开销大
                        int steps = 0;
                        while (nz < d && steps < maxRise) {
                            int obN = obstacle[x][y][nz];
                            boolean passUp = (obN != TYPE_WALL && obN != 7) && (inside[x][y][nz] == 1 || obN == TYPE_STAIR);
                            if (!passUp) break;
                            decay *= 0.95;  // 方案C: 0.75→0.95, 烟囱效应烟几乎不衰减上窜(真实楼梯井), 烟能蔓延整楼
                            C_afterAdvection[x][y][nz] += deltaC2 * decay;
                            T_afterAdvection[x][y][nz] += deltaT2 * decay;
                            nz++; steps++;
                        }
                        C_afterAdvection[x][y][z] -= deltaC2;
                        T_afterAdvection[x][y][z] -= deltaT2;
                        dbgV++; dbgVA += deltaC2;
                    }
                    // === 方案A微调: 烟层沉降 - 冷却烟缓慢下沉, 烟层增厚、界面清晰 ===
                    if (z > 1 && excessT < 45.0 && C_afterDiff[x][y][z] > 0.04) {
                        double sinkCoef = (C_afterDiff[x][y][z] > 0.15) ? 0.018 : 0.008;
                        double sinkFrac = Math.min(1.0, sinkCoef * DT_FINAL / dz);
                        if (inside[x][y][z-1] == 1 && obstacle[x][y][z-1] != TYPE_WALL) {
                            double deltaC = C_afterDiff[x][y][z] * sinkFrac;
                            C_afterAdvection[x][y][z] -= deltaC;
                            C_afterAdvection[x][y][z-1] += deltaC;
                            double deltaT = (T_afterAdvection[x][y][z] - T_AMB) * sinkFrac * 0.5;
                            T_afterAdvection[x][y][z] -= deltaT;
                            T_afterAdvection[x][y][z-1] += deltaT;
                        }
                    }

                    // === 方案B: 粗网格 Stable Fluids 流场驱动水平平流 (替代温度梯度+伪湍流) ===
                    double invS2 = 1.0 / (double)FLOW_STRIDE;
                    // 方案B微调: 垂直上升中的烟格(vz>0.5)先跳过水平搬移, 让烟柱冲到顶棚再水平铺展
                    boolean risingColumn = (vz > 0.5);  // ★ 修复: 上升中的烟柱(vz>0.5m/s)跳过水平搬移, 先垂直冲到顶棚再水平铺展(真实火灾羽流→顶棚射流)
                    double vx = risingColumn ? 0 : triLinearFlow(flowU, x * invS2, y * invS2, z * invS2);
                    double vy = risingColumn ? 0 : triLinearFlow(flowV, x * invS2, y * invS2, z * invS2);
                    if (vx > _maxDrift) vx = _maxDrift;
                    if (vx < -_maxDrift) vx = -_maxDrift;
                    double abs_vx = Math.abs(vx);
                    if (abs_vx > VEL_EPS) {
                        double frac = Math.min(1.0, abs_vx * DT_FINAL / dx);
                        if (vx > 0 && x < w-1 && inside[x+1][y][z] == 1 && obstacle[x+1][y][z] != TYPE_WALL) {
                            double deltaC_x = C_afterDiff[x][y][z] * frac;
                            double deltaT_x = (T_afterAdvection[x][y][z] - T_AMB) * frac;
                            C_afterAdvection[x][y][z] -= deltaC_x;
                            C_afterAdvection[x+1][y][z] += deltaC_x;
                            T_afterAdvection[x][y][z] -= deltaT_x;
                            T_afterAdvection[x+1][y][z] += deltaT_x;
                        } else if (vx < 0 && x > 0 && inside[x-1][y][z] == 1 && obstacle[x-1][y][z] != TYPE_WALL) {
                            double deltaC_x = C_afterDiff[x][y][z] * frac;
                            double deltaT_x = (T_afterAdvection[x][y][z] - T_AMB) * frac;
                            C_afterAdvection[x][y][z] -= deltaC_x;
                            C_afterAdvection[x-1][y][z] += deltaC_x;
                            T_afterAdvection[x][y][z] -= deltaT_x;
                            T_afterAdvection[x-1][y][z] += deltaT_x;
                        }
                    }
                    if (vy > _maxDrift) vy = _maxDrift;
                    if (vy < -_maxDrift) vy = -_maxDrift;
                    double abs_vy = Math.abs(vy);
                    if (abs_vy > VEL_EPS) {
                        double frac = Math.min(1.0, abs_vy * DT_FINAL / dx);
                        if (vy > 0 && y < h-1 && inside[x][y+1][z] == 1 && obstacle[x][y+1][z] != TYPE_WALL) {
                            double deltaC_y = C_afterDiff[x][y][z] * frac;
                            double deltaT_y = (T_afterAdvection[x][y][z] - T_AMB) * frac;
                            C_afterAdvection[x][y][z] -= deltaC_y;
                            C_afterAdvection[x][y+1][z] += deltaC_y;
                            T_afterAdvection[x][y][z] -= deltaT_y;
                            T_afterAdvection[x][y+1][z] += deltaT_y;
                        } else if (vy < 0 && y > 0 && inside[x][y-1][z] == 1 && obstacle[x][y-1][z] != TYPE_WALL) {
                            double deltaC_y = C_afterDiff[x][y][z] * frac;
                            double deltaT_y = (T_afterAdvection[x][y][z] - T_AMB) * frac;
                            C_afterAdvection[x][y][z] -= deltaC_y;
                            C_afterAdvection[x][y-1][z] += deltaC_y;
                            T_afterAdvection[x][y][z] -= deltaT_y;
                            T_afterAdvection[x][y-1][z] += deltaT_y;
                        }
                    }

                    // ===== 优化项 10: 顶棚射流 (Ceiling Jet) =====
                    // 方案C性能: 每2步执行一次(半径14格开销大), 铺展速度靠顶棚扩散×4补偿
                    boolean doCeilingJet = ((_stepCount & 1) == 0);
                    // 当烟接近/到达顶棚时,将垂直动量转为水平径向扩散
                    double curC = C_afterDiff[x][y][z];
                    double curT = T_afterAdvection[x][y][z];
                    boolean smokeTop = curC > 0.05;
                    if (smokeTop) {
                        for (int dz2 = 1; dz2 <= 3; dz2++) {
                            int nz2 = z + dz2;
                            if (nz2 >= d) break;
                            if (inside[x][y][nz2] == 1 && obstacle[x][y][nz2] != TYPE_WALL && C_afterAdvection[x][y][nz2] > 0.05) { smokeTop = false; break; }
                        }
                    }
                    // 方案A微调3: 顶棚射流触发 = 楼顶 OR 烟层顶(局部空间顶棚), 火源层烟到顶后水平铺展
                    // 方案A微调7: 浓烟(curC>0.3)即使温度低也触发水平铺展(烟层重力流, 与温度解耦)
                    if (doCeilingJet && ceilingNearLayer[z] && (excessT > CJ_EXCESS_T || curC > 0.3)) {
                        if (curC > CJ_MIN_C) {
                            // 方案A修复: 先统计有效邻居的总权重; 无处可去则不扣烟(守恒)
                            double totalDecay = 0.0;
                            int validN = 0;
                            for (int dx2 = -cjRi; dx2 <= cjRi; dx2++) {
                                for (int dy2 = -cjRi; dy2 <= cjRi; dy2++) {
                                    if (dx2 == 0 && dy2 == 0) continue;
                                    int nx = x + dx2, ny = y + dy2;
                                    if (nx < 1 || nx >= w-1 || ny < 1 || ny >= h-1) continue;
                                    if (inside[nx][ny][z] == 0 || obstacle[nx][ny][z] == TYPE_WALL) continue;
                                    double r2 = (double)dx2*dx2 + (double)dy2*dy2;
                                    if (r2 > cjRi * cjRi) continue;
                                    totalDecay += 1.0 / (1.0 + r2 / cjR2_05);  // 方案A微调5: 洛伦兹权重, 烟层均匀铺展
                                    validN++;
                                }
                            }
                            if (validN > 0 && totalDecay > 0) {
                                double outFrac = Math.min(cjStrengthEff, 0.9);
                                for (int dx2 = -cjRi; dx2 <= cjRi; dx2++) {
                                    for (int dy2 = -cjRi; dy2 <= cjRi; dy2++) {
                                        if (dx2 == 0 && dy2 == 0) continue;
                                        int nx = x + dx2, ny = y + dy2;
                                        if (nx < 1 || nx >= w-1 || ny < 1 || ny >= h-1) continue;
                                        if (inside[nx][ny][z] == 0 || obstacle[nx][ny][z] == TYPE_WALL) continue;
                                        double r2 = (double)dx2*dx2 + (double)dy2*dy2;
                                        if (r2 > cjRi * cjRi) continue;
                                        double share = (1.0 / (1.0 + r2 / cjR2_05)) / totalDecay;  // 方案A微调5
                                        double amountC = curC * outFrac * share;
                                        double amountT = (curT - T_AMB) * outFrac * share;
                                        C_afterAdvection[x][y][z] -= amountC;
                                        C_afterAdvection[nx][ny][z] += amountC;
                                        T_afterAdvection[x][y][z] -= amountT;
                                        T_afterAdvection[nx][ny][z] += amountT;
                                    }
                                }
                            }
                        }
                    }

                    if (C_afterAdvection[x][y][z] < 0) C_afterAdvection[x][y][z] = 0;
                    if (T_afterAdvection[x][y][z] < T_AMB) T_afterAdvection[x][y][z] = T_AMB;
        }

    if ((_stepCount & 31) == 0) System.out.println("[DBG] step=" + _stepCount + " vMoves=" + dbgV + " vAmt=" + String.format("%.2f", dbgVA));
        } // end of else (非LES模式)
    long _tC = System.nanoTime();  // 平流+顶棚射流结束(LES模式=LES求解结束)
        // ===================================================================
        // 回写烟 + 温度
        if ((_stepCount & 31) == 0) System.out.println("[DBG4] insideCells.size=" + fire.insideCells.size() + " fireSources=" + fireSources.size());
        // ===================================================================
        for (int[] cell : fire.insideCells) {
            int x = cell[0], y = cell[1], z = cell[2];
            double cv = C_afterAdvection[x][y][z];
            if ((_stepCount & 15) == 0 && !fireSources.isEmpty()) { int[] dbgF = fireSources.get(0); if (x==dbgF[0] && y==dbgF[1] && z==dbgF[2]) System.out.println("[DBG3] 回写火源格 cv=" + cv); }
            if (cv > 1.0) {
                // 方案A微调6: 烟不可压缩 - 超额浓度向6邻居推挤(波前传播), 烟层能以高浓度铺满空间
                double excess = cv - 1.0;
                cv = 1.0;
                double share = excess / 6.0;
                if (share > 1e-6) {
                    for (int n2 = 0; n2 < 6; n2++) {
                        int nx2 = x + NB6_FACES[n2][0], ny2 = y + NB6_FACES[n2][1], nz2 = z + NB6_FACES[n2][2];
                        if (nx2 >= 0 && nx2 < w && ny2 >= 0 && ny2 < h && nz2 >= 0 && nz2 < d
                            && obstacle[nx2][ny2][nz2] != TYPE_WALL && inside[nx2][ny2][nz2] == 1) {
                            double cap = 1.0 - nextC[nx2][ny2][nz2];  // 方案A微调6: 溢出按剩余容量截断, 浓度不超1.0
                            if (cap > 0) nextC[nx2][ny2][nz2] += Math.min(share, cap);
                        }
                    }
                }
            }
            if (cv < 0.0) cv = 0.0;
            nextC[x][y][z] = cv;
            nextT[x][y][z] = T_afterAdvection[x][y][z];
            // ===== 方案A性能: 回写+冷却+clamp合并为一次遍历 =====
            if (nextT[x][y][z] <= T_AMB + 0.01 && nextC[x][y][z] <= SMOKE_FLOOR + 1e-9) {
                nextC[x][y][z] = SMOKE_FLOOR;
            } else {
                nextT[x][y][z] -= _cooling * DT_FINAL * (nextT[x][y][z] - T_AMB);
                if (nextT[x][y][z] < T_AMB) nextT[x][y][z] = T_AMB;
                if (nextT[x][y][z] > HOT_SMOKE_T_THRESHOLD) addRadiativeCooling(nextT, x, y, z, T_AMB, DT_FINAL, _smkAbsorb, rho, cp, dxSq, dz);
                if (nextT[x][y][z] > T_MAX) nextT[x][y][z] = T_MAX;
                if (Double.isNaN(nextT[x][y][z])) nextT[x][y][z] = T_AMB;
                nextC[x][y][z] *= (1.0 - _smokeDecay * DT_FINAL);
                if (nextC[x][y][z] < SMOKE_FLOOR) nextC[x][y][z] = SMOKE_FLOOR;
                if (nextC[x][y][z] > SMOKE_CEIL) nextC[x][y][z] = SMOKE_CEIL;
                if (Double.isNaN(nextC[x][y][z])) nextC[x][y][z] = 0.0;
            }
        }
        // 方案A修复: 火源格可能不在 insideCells(重建时机与随机火源不同步), 补处理保证回写/clamp/冷却生效
        // 方案A修复: 火源格可能不在 insideCells(重建时机与随机火源不同步), 补处理保证回写/clamp/冷却生效
        for (int[] fp : fireSources) {
            int x = fp[0], y = fp[1], z = fp[2];
            double cv = C_afterAdvection[x][y][z];
            if (cv > 1.0) cv = 1.0;
            if (cv < 0.0) cv = 0.0;
            nextC[x][y][z] = cv;
            nextT[x][y][z] = T_afterAdvection[x][y][z];
            nextT[x][y][z] -= _cooling * DT_FINAL * (nextT[x][y][z] - T_AMB);
            if (nextT[x][y][z] < T_AMB) nextT[x][y][z] = T_AMB;
            nextC[x][y][z] *= (1.0 - _smokeDecay * DT_FINAL);
            if (nextC[x][y][z] > SMOKE_CEIL) nextC[x][y][z] = SMOKE_CEIL;
            if (nextC[x][y][z] < SMOKE_FLOOR) nextC[x][y][z] = SMOKE_FLOOR;
        }

        // ===================================================================
        // (9) 冷却衰减 + 优化项 14: 高温烟 Stefan-Boltzmann 辐射
        // ===================================================================

        // ===================================================================
        // 优化项 12: 开口流动 (Doorway Flow)
        // ===================================================================
        // 方案C修复: 门高不能用整栋楼高(d*dz=34m), 必须用实际层高, 否则中性面/热压差完全失真
        double floorH = computeFloorHeight(ceilingZs, d, dz);
        applyDoorwayFlow(nextT, nextC, obstacle, inside,
                w, h, d, T_AMB, T_FLAME, dx, dz, DT_FINAL,
                rho, _cp, _g, _cdCoef, floorH, cachedDoorColumns(fire));

        // ===================================================================
        // (10) 排烟口处理
        // ===================================================================
        for (int[] ec : exitColumns) {
            int x = ec[0], y = ec[1];
                for (int dx2 = -EXIT_RADIUS; dx2 <= EXIT_RADIUS; dx2++) {
                    for (int dy2 = -EXIT_RADIUS; dy2 <= EXIT_RADIUS; dy2++) {
                        int nx = x + dx2, ny = y + dy2;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        for (int z = 0; z < d; z++) {
                            if (inside[nx][ny][z] == 0 || obstacle[nx][ny][z] == TYPE_WALL) continue;
                            double dist = Math.hypot(dx2, dy2);
                            double exhaustRate = _exhaust * DT_FINAL / (dist + 1.0);
                            nextC[nx][ny][z] -= exhaustRate * nextC[nx][ny][z];
                            if (nextC[nx][ny][z] < SMOKE_FLOOR) nextC[nx][ny][z] = SMOKE_FLOOR;
                            double tempFactor = (nextT[nx][ny][z] - T_AMB) / Math.max(1.0, (T_FLAME - T_AMB));
                            nextT[nx][ny][z] -= exhaustRate * tempFactor * (nextT[nx][ny][z] - T_AMB);
                            if (nextT[nx][ny][z] < T_AMB) nextT[nx][ny][z] = T_AMB;
                        }
                    }
                }
        }


        // ★ 烟雾从窗户涌出 (方案A性能: 只遍历缓存的窗户格, 不再扫77万格)
        for (int[] wc : windowCells) {
            int x = wc[0], y = wc[1], z = wc[2];
                    int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

                    // === 1. 窗户挤出: 只有当窗户内侧浓度足够高(>0.15)时才向外侧挤出 ===
                    // 真实火灾: 烟雾先充满房间, 内部压力/浓度足够高时才从窗户涌出
                    // === 方案A微调: 蓄烟-溢流 - 房间先蓄烟, 浓度足够高才从窗户涌出 ===
                    if (nextC[x][y][z] > 0.25) {
                        for (int[] dir : dirs) {
                            int nx = x+dir[0], ny = y+dir[1], nz = z+dir[2];
                            if (nx<0||nx>=w||ny<0||ny>=h||nz<0||nz>=d) continue;
                            if (obstacle[nx][ny][nz] == TYPE_WALL) continue;
                            if (inside[nx][ny][nz] == 0) continue;
                            double diff = nextC[x][y][z] - nextC[nx][ny][nz];
                            if (diff > 0.02) {
                                // 挤出系数: 水平方向0.45(比之前0.7降低), 垂直0.2
                                double coeff = (dir[2] == 0) ? 0.30 : 0.15;
                                double transfer = diff * coeff * DT_FINAL;
                                nextC[x][y][z] -= transfer;
                                nextC[nx][ny][nz] += transfer;
                                double tDiff = nextT[x][y][z] - nextT[nx][ny][nz];
                                if (tDiff > 0) {
                                    double tTransfer = tDiff * 0.35 * DT_FINAL;
                                    nextT[x][y][z] -= tTransfer;
                                    nextT[nx][ny][nz] += tTransfer;
                                }
                            }
                        }
                    }

                    // === 2. 柱状上升: 窗户外侧±5体素区域, 增强向上浮力(只有当该区域有烟雾时) ===
                    // 方案A性能: 窗户格本身无烟则跳过柱状上升(大部分窗户无烟)
                    if (nextC[x][y][z] < 0.08) continue;
                    for (int hx = -5; hx <= 5; hx++) {
                        for (int hy = -5; hy <= 5; hy++) {
                            if (hx == 0 && hy == 0) continue;
                            int horizDist = Math.abs(hx) + Math.abs(hy);
                            if (horizDist > 5) continue;
                            int px = x + hx, py = y + hy;
                            if (px<0||px>=w||py<0||py>=h) continue;
                            // 向上7层, 浮力驱动柱状上升
                            for (int uz = z + 1; uz <= Math.min(z + 7, d - 1); uz++) {
                                if (obstacle[px][py][uz] == TYPE_WALL) break;
                                if (inside[px][py][uz] == 0) continue;
                                int belowZ = uz - 1;
                                if (belowZ < 0 || obstacle[px][py][belowZ] == TYPE_WALL) continue;
                                if (inside[px][py][belowZ] == 0) continue;
                                double cBelow = nextC[px][py][belowZ];
                                double cAbove = nextC[px][py][uz];
                                // 浮力系数: 离窗越近越强, 越高越弱
                                double buoyFactor = 0.4 * (1.0 - horizDist / 6.0) * (1.0 - (uz - z) / 8.0);
                                if (buoyFactor > 0.02 && cBelow > cAbove + 0.002) {
                                    double upTransfer = (cBelow - cAbove) * buoyFactor * DT_FINAL;
                                    nextC[px][py][belowZ] -= upTransfer;
                                    nextC[px][py][uz] += upTransfer;
                                    if (nextT[px][py][belowZ] > nextT[px][py][uz] + 3) {
                                        double tUp = (nextT[px][py][belowZ] - nextT[px][py][uz]) * buoyFactor * 0.7 * DT_FINAL;
                                        nextT[px][py][belowZ] -= tUp;
                                        nextT[px][py][uz] += tUp;
                                    }
                                }
                            }
                        }
                    }

                    // === 3. 火焰探出窗户: 温度>550K时向外侧注入高温火焰(火势足够大才探出) ===
                    if (nextT[x][y][z] > 550.0) {
                        for (int[] dir : dirs) {
                            if (dir[2] != 0) continue;
                            for (int dist = 1; dist <= 2; dist++) {
                                int nx = x+dir[0]*dist, ny = y+dir[1]*dist;
                                if (nx<0||nx>=w||ny<0||ny>=h) break;
                                if (obstacle[nx][ny][z] == TYPE_WALL) break;
                                if (inside[nx][ny][z] == 0) continue;
                                double flameInject = (nextT[x][y][z] - 500.0) * 0.1 / dist * DT_FINAL;
                                if (flameInject > 0) {
                                    nextT[nx][ny][z] = Math.min(T_MAX, nextT[nx][ny][z] + flameInject);
                                    nextC[nx][ny][z] = Math.min(SMOKE_CEIL, nextC[nx][ny][z] + 0.003 / dist * DT_FINAL);
                                }
                            }
                        }
                    }
        }

        swapBuffers(fire, nextT, nextC);
        long _t1 = System.nanoTime();
        _stepCount++;
        if (_stepCount % 50 == 0) {
            long _wall = System.currentTimeMillis();
            System.out.printf("[Perf] step %d: %.1f ms (注入%.1f 快照%.1f 烟格%.1f 平流%.1f 尾段%.1f) t=%.1fs wall=%d%n", _stepCount, (_t1-_t0)/1e6, (_tI-_t0)/1e6, (_tD0-_tI)/1e6, (_tD1-_tD0)/1e6, (_tC-_tB)/1e6, (_t1-_tC)/1e6, fire.currentTime, _wall);
        }
    }

    // 方案A性能: 缓存门柱列(obstacle==TYPE_DOOR 的 x,y,z), 避免 applyDoorwayFlow 每步扫77万格
    private int[][] cachedDoorCols = null;
    private int[][] cachedDoorColumns(FireEscape fire) {
        if (cachedDoorCols != null) return cachedDoorCols;
        java.util.ArrayList<int[]> dc = new java.util.ArrayList<int[]>();
        for (int x = 0; x < fire.gridWidth; x++)
            for (int y = 0; y < fire.gridHeight; y++)
                for (int z = 0; z < fire.gridDepth; z++)
                    if (fire.obstacleGrid.get(x, y, z) == TYPE_DOOR) { dc.add(new int[]{x, y, z}); break; }
        cachedDoorCols = dc.toArray(new int[0][]);
        System.out.println("[Perf] 缓存: 门柱列=" + cachedDoorCols.length);
        return cachedDoorCols;
    }

    // ======================================================================
    // ======================================================================
    // 方案B: Stable Fluids 简化流场 (粗网格 stride=4) — 不可压缩速度场
    // 迎风 advect + 浮力外力 + 压力投影, 驱动细网格烟水平运动(卷吸/回流/顶棚射流)
    // ======================================================================
    private void solveCoarseFlow(int w, int h, int d, int[][][] obstacle, int[][][] inside,
                                 double[][][] T, double[][][] C, java.util.List<int[]> smokeCells) {
        int S = FLOW_STRIDE;
        int cw = (w + S - 1) / S, ch = (h + S - 1) / S, cd = (d + S - 1) / S;
        int total = cw * ch * cd;
        if (flowU == null || cachedFlowW != cw || cachedFlowH != ch || cachedFlowD != cd) {
            flowU = new double[total];
            flowV = new double[total];
            flowW = new double[total];
            flowP = new double[total];
            cachedFlowW = cw; cachedFlowH = ch; cachedFlowD = cd;
        }
        double[] U = flowU, V = flowV, W = flowW, P = flowP;
        double dt = FLOW_DT;
        int chw = ch * cw;

        // 流通掩码: 块内是否有内部格(非纯墙) — 网格不变时缓存复用
        boolean[] open;
        if (flowOpenCache != null && flowOpenCache.length == total) {
            open = flowOpenCache;
        } else {
            open = new boolean[total];
            for (int cz = 0; cz < cd; cz++)
                for (int cy = 0; cy < ch; cy++)
                    for (int cx = 0; cx < cw; cx++) {
                        boolean any = false;
                        int x0 = cx * S, y0 = cy * S, z0 = cz * S;
                        outer:
                        for (int k = 0; k < S && z0 + k < d; k++)
                            for (int j = 0; j < S && y0 + j < h; j++)
                                for (int i = 0; i < S && x0 + i < w; i++)
                                    if (inside[x0+i][y0+j][z0+k] == 1) { any = true; break outer; }
                        open[cz * chw + cy * cw + cx] = any;
                    }
            flowOpenCache = open;
        }

        // === 1) 一阶迎风 advect 速度场 (Gauss-Seidel 原地更新, 隐式更稳) ===
        for (int cz = 0; cz < cd; cz++)
            for (int cy = 0; cy < ch; cy++)
                for (int cx = 0; cx < cw; cx++) {
                    int idx = cz * chw + cy * cw + cx;
                    if (!open[idx]) { U[idx] = 0; V[idx] = 0; W[idx] = 0; continue; }
                    int xm = Math.max(cx-1, 0), xp = Math.min(cx+1, cw-1);
                    int ym = Math.max(cy-1, 0), yp = Math.min(cy+1, ch-1);
                    int zm = Math.max(cz-1, 0), zp = Math.min(cz+1, cd-1);
                    int im = cz * chw + cy * cw + xm, ip = cz * chw + cy * cw + xp;
                    int jm = cz * chw + ym * cw + cx, jp = cz * chw + yp * cw + cx;
                    int km = zm * chw + cy * cw + cx, kp = zp * chw + cy * cw + cx;
                    double u = U[idx], v = V[idx], wz = W[idx];
                    // U 分量 advect (被 u,v,wz 共同搬运)
                    double advU = u - dt * (u * (u - ((u > 0) ? U[im] : U[ip]))
                                          + v * (u - ((v > 0) ? U[jm] : U[jp]))
                                          + wz * (u - ((wz > 0) ? U[km] : U[kp])));
                    // V 分量
                    double advV = v - dt * (u * (v - ((u > 0) ? V[im] : V[ip]))
                                          + v * (v - ((v > 0) ? V[jm] : V[jp]))
                                          + wz * (v - ((wz > 0) ? V[km] : V[kp])));
                    // W 分量
                    double advW = wz - dt * (u * (wz - ((u > 0) ? W[im] : W[ip]))
                                           + v * (wz - ((v > 0) ? W[jm] : W[jp]))
                                           + wz * (wz - ((wz > 0) ? W[km] : W[kp])));
                    U[idx] = advU; V[idx] = advV; W[idx] = advW;
                }

        // === 2) 浮力外力: 含烟块内最高温度 → 垂直升力 (高温烟柱上升, 周围空气补充形成卷吸) ===
        boolean[] hotBlock = new boolean[total];
        for (int[] sc2 : smokeCells) {
            int bx = sc2[0] / S, by = sc2[1] / S, bz = sc2[2] / S;
            if (bx < cw && by < ch && bz < cd) hotBlock[bz * chw + by * cw + bx] = true;
        }
        double amb = FireEscape.T_AMBIENT;
        for (int cz = 0; cz < cd; cz++)
            for (int cy = 0; cy < ch; cy++)
                for (int cx = 0; cx < cw; cx++) {
                    int idx = cz * chw + cy * cw + cx;
                    if (!open[idx]) continue;
                    if (hotBlock[idx]) {
                        int x0 = cx * S, y0 = cy * S, z0 = cz * S;
                        double tMax = amb;
                        for (int k = 0; k < S && z0 + k < d; k++)
                            for (int j = 0; j < S && y0 + j < h; j++)
                                for (int i = 0; i < S && x0 + i < w; i++) {
                                    int xx = x0+i, yy = y0+j, zz = z0+k;
                                    if (inside[xx][yy][zz] == 1 && obstacle[xx][yy][zz] != TYPE_WALL) {
                                        double tt = T[xx][yy][zz];
                                        if (tt > tMax) tMax = tt;
                                    }
                                }
                        double excess = tMax - amb;
                        if (excess > 3.0) W[idx] += FLOW_BUOY_COEFF * excess * dt;
                        else W[idx] -= 0.02 * dt;  // 冷却烟微下沉
                    } else {
                        W[idx] -= 0.02 * dt;  // 无烟块: 仅轻微衰减下沉
                    }
                }

        // === 3) 压力投影: Poisson 迭代消散度 (不可压缩) ===
        for (int i = 0; i < total; i++) P[i] = 0;
        for (int it = 0; it < FLOW_PROJ_ITERS; it++) {
            for (int cz = 0; cz < cd; cz++)
                for (int cy = 0; cy < ch; cy++)
                    for (int cx = 0; cx < cw; cx++) {
                        int idx = cz * chw + cy * cw + cx;
                        if (!open[idx]) continue;
                        int xm = Math.max(cx-1, 0), xp = Math.min(cx+1, cw-1);
                        int ym = Math.max(cy-1, 0), yp = Math.min(cy+1, ch-1);
                        int zm = Math.max(cz-1, 0), zp = Math.min(cz+1, cd-1);
                        int im = cz * chw + cy * cw + xm, ip = cz * chw + cy * cw + xp;
                        int jm = cz * chw + ym * cw + cx, jp = cz * chw + yp * cw + cx;
                        int km = zm * chw + cy * cw + cx, kp = zp * chw + cy * cw + cx;
                        double div = (U[ip] - U[im]) + (V[jp] - V[jm]) + (W[kp] - W[km]);
                        P[idx] = (P[im] + P[ip] + P[jm] + P[jp] + P[km] + P[kp] - div) / 6.0;
                    }
        }
        // 速度修正: u -= grad(p)
        for (int cz = 0; cz < cd; cz++)
            for (int cy = 0; cy < ch; cy++)
                for (int cx = 0; cx < cw; cx++) {
                    int idx = cz * chw + cy * cw + cx;
                    if (!open[idx]) { U[idx] = 0; V[idx] = 0; W[idx] = 0; continue; }
                    int xm = Math.max(cx-1, 0), xp = Math.min(cx+1, cw-1);
                    int ym = Math.max(cy-1, 0), yp = Math.min(cy+1, ch-1);
                    int zm = Math.max(cz-1, 0), zp = Math.min(cz+1, cd-1);
                    int im = cz * chw + cy * cw + xm, ip = cz * chw + cy * cw + xp;
                    int jm = cz * chw + ym * cw + cx, jp = cz * chw + yp * cw + cx;
                    int km = zm * chw + cy * cw + cx, kp = zp * chw + cy * cw + cx;
                    U[idx] -= (P[ip] - P[im]) * 0.5;
                    V[idx] -= (P[jp] - P[jm]) * 0.5;
                    W[idx] -= (P[kp] - P[km]) * 0.5;
                }

        // === 4) 衰减 + 速度上限 ===
        for (int i = 0; i < total; i++) {
            if (!open[i]) continue;
            double u = U[i] * FLOW_DAMP;
            double v = V[i] * FLOW_DAMP;
            double wz = W[i] * FLOW_DAMP;
            if (u > FLOW_MAX_V) u = FLOW_MAX_V; else if (u < -FLOW_MAX_V) u = -FLOW_MAX_V;
            if (v > FLOW_MAX_V) v = FLOW_MAX_V; else if (v < -FLOW_MAX_V) v = -FLOW_MAX_V;
            if (wz > FLOW_MAX_V) wz = FLOW_MAX_V; else if (wz < -FLOW_MAX_V) wz = -FLOW_MAX_V;
            U[i] = u; V[i] = v; W[i] = wz;
        }
    }

    // 三线性插值粗网格速度场 (细网格坐标 → 粗网格速度, 一维拍平)
    private double triLinearFlow(double[] f, double fx, double fy, double fz) {
        int cw = cachedFlowW, ch = cachedFlowH, cd = cachedFlowD;
        if (cw <= 0) return 0;
        int chw = ch * cw;
        int x0 = (int)fx; if (x0 < 0) x0 = 0; if (x0 > cw-1) x0 = cw-1;
        int y0 = (int)fy; if (y0 < 0) y0 = 0; if (y0 > ch-1) y0 = ch-1;
        int z0 = (int)fz; if (z0 < 0) z0 = 0; if (z0 > cd-1) z0 = cd-1;
        int x1 = Math.min(x0+1, cw-1), y1 = Math.min(y0+1, ch-1), z1 = Math.min(z0+1, cd-1);
        double tx = fx - x0, ty = fy - y0, tz = fz - z0;
        int i000 = z0 * chw + y0 * cw + x0, i100 = z0 * chw + y0 * cw + x1;
        int i010 = z0 * chw + y1 * cw + x0, i110 = z0 * chw + y1 * cw + x1;
        int i001 = z1 * chw + y0 * cw + x0, i101 = z1 * chw + y0 * cw + x1;
        int i011 = z1 * chw + y1 * cw + x0, i111 = z1 * chw + y1 * cw + x1;
        double c000 = f[i000], c100 = f[i100], c010 = f[i010], c110 = f[i110];
        double c001 = f[i001], c101 = f[i101], c011 = f[i011], c111 = f[i111];
        double c00 = c000 + (c100 - c000) * tx, c10 = c010 + (c110 - c010) * tx;
        double c01 = c001 + (c101 - c001) * tx, c11 = c011 + (c111 - c011) * tx;
        double c0 = c00 + (c10 - c00) * ty, c1 = c01 + (c11 - c01) * ty;
        return c0 + (c1 - c0) * tz;
    }
    // 辅助方法: 优化项 5/14 — Stefan-Boltzmann 辐射冷却
    // ======================================================================
    private static void addRadiativeCooling(double[][][] nextT, int x, int y, int z,
                                             double T_AMB, double dt,
                                             double smokeAbsorb, double rho, double cp,
                                             double dxSq, double dz) {
        double T_val = nextT[x][y][z];
        if (T_val <= T_AMB) return;
        double T4 = T_val * T_val * T_val * T_val;
        double T4_amb = T_AMB * T_AMB * T_AMB * T_AMB;
        double effEmiss = Math.max(0.1, Math.min(1.0, smokeAbsorb));
        double cool = effEmiss * SIGMA_SB * (T4 - T4_amb) * dt / (rho * cp * dxSq * dz);
        nextT[x][y][z] -= cool;
        if (nextT[x][y][z] < T_AMB) nextT[x][y][z] = T_AMB;
    }

    // 方案C: 由顶棚检测结果推算平均层高(门高应取层高而非整楼高)
    private static double computeFloorHeight(int[] ceilingZs, int d, double dz) {
        if (ceilingZs == null || ceilingZs.length < 2) return 3.0;  // 兜底3m门高
        double sum = 0;
        int cnt = 0;
        for (int i = 1; i < ceilingZs.length; i++) {
            int gap = ceilingZs[i] - ceilingZs[i - 1];
            if (gap >= 4 && gap <= 40) { sum += gap * dz; cnt++; }
        }
        if (cnt == 0) return 3.0;
        double h = sum / cnt;
        // 门高取层高(不超过3.5m, 真实门高约2-3m), 避免把整层当门
        return Math.max(2.0, Math.min(3.5, h * 0.5));
    }

    // ======================================================================
    // 辅助方法: 优化项 12 — 开口流动 (Doorway Flow)
    // ======================================================================
    private static void applyDoorwayFlow(double[][][] nextT, double[][][] nextC,
                                          int[][][] obstacle, int[][][] inside,
                                          int w, int h, int d,
                                          double T_AMB, double T_FLAME,
                                          double dx, double dz, double dt,
                                          double rho, double cp, double g, double Cd, double h_door, int[][] doorCols) {
        if (doorCols.length == 0) return;  // 方案A性能: 无门柱直接跳过
        // 方案C: 门洞流动只作用于门格所在楼层段(以门格z为中心, 高度=门高), 而非整栋楼
        int zSpan = (int)Math.ceil(h_door / dz);
        for (int[] dc : doorCols) {
            int x = dc[0], y = dc[1], dzDoor = dc[2];
                if (inside[x][y][dzDoor] == 0) continue;
                int zLo = Math.max(0, dzDoor - zSpan);
                int zHi = Math.min(d - 1, dzDoor + zSpan);

                double T_in_max = T_AMB;
                double T_in_avg = 0.0;
                int count = 0;
                for (int z = zLo; z <= zHi; z++) {
                    if (inside[x][y][z] == 0) continue;
                    T_in_max = Math.max(T_in_max, nextT[x][y][z]);
                    T_in_avg += nextT[x][y][z];
                    count++;
                }
                if (count == 0) continue;
                T_in_avg /= count;
                double T_eff = T_in_avg;
                if (T_eff <= T_AMB + 5.0) continue;

                double deltaT = Math.max(0.1, T_eff - T_AMB);
                double deltaP = rho * g * deltaT * h_door / (2.0 * T_AMB);
                double area = dx * dz;
                double Q_door = Cd * area * Math.sqrt(Math.max(0.0, 2.0 * deltaP / rho));
                double z_neutral = dzDoor * dz + h_door / 2.0 * T_AMB / Math.sqrt(Math.max(T_eff * T_AMB, 1.0));

                for (int z = zLo; z <= zHi; z++) {
                    if (inside[x][y][z] == 0) continue;
                    double z_height = z * dz;
                    double profile = 1.0 - Math.abs(z_height - z_neutral) / (h_door * 0.5 + 1e-6);
                    if (profile < 0) profile = 0;
                    double fluxFrac = Q_door * profile * dt / (rho * cp * dx * dx * dz + 1e-9);
                    fluxFrac = Math.min(fluxFrac, 0.3);

                    if (z_height > z_neutral) {
                        // 上部:烟向外流出
                        double outC = nextC[x][y][z] * fluxFrac;
                        nextC[x][y][z] -= outC;
                        if (nextC[x][y][z] < 0) nextC[x][y][z] = 0;
                        double outT = (nextT[x][y][z] - T_AMB) * fluxFrac;
                        nextT[x][y][z] -= outT;
                        if (nextT[x][y][z] < T_AMB) nextT[x][y][z] = T_AMB;
                    } else {
                        // 下部:空气流入
                        double inT = (T_AMB - nextT[x][y][z]) * Math.min(fluxFrac, 0.2);
                        nextT[x][y][z] += inT;
                        if (nextT[x][y][z] > T_FLAME) nextT[x][y][z] = T_FLAME;
                        nextC[x][y][z] *= (1.0 - fluxFrac * 0.5);
                        if (nextC[x][y][z] < 0) nextC[x][y][z] = 0;
                    }
                }
            }
    }

    private void swapBuffers(FireEscape fire, double[][][] nextT, double[][][] nextC) {
        double[][][] tempT = fire.fireGrid.field;
        fire.fireGrid.field = nextT;
        fire.nextFireGrid.field = tempT;
        double[][][] tempC = fire.smokeGrid.field;
        fire.smokeGrid.field = nextC;
        fire.nextSmokeGrid.field = tempC;
    }
}
