package sim.app.fireescape;

import sim.engine.*;
import sim.field.grid.*;
import java.util.List;

public class FireSpread implements Steppable {
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
    private static final double CFL_SAFETY             = 0.5;
    private static final double DIFFUSION_SAFETY       = 0.4;
    private static final double GAUSSIAN_SIGMA_FACTOR  = 0.5;
    private static final double TEMP_FACTOR_MIN        = 0.1;
    private static final double TEMP_FACTOR_MAX        = 1.5;
    private static final double SMOKE_FLOOR            = 0.002;
    private static final double SMOKE_CEIL             = 1.0;
    // ===== 方案1:火源烟气注入速率(单位浓度/秒),控制产烟强度 =====
    private static final double SMOKE_SOURCE_RATE      = 2.0;
    private static final int    EXIT_RADIUS            = 2;
    private static final double KILOWATT_TO_WATT       = 1000.0;
    private static final double CONV_RADIUS_METERS     = 1.0;
    private static final double MIN_SMOKE_FOR_ADVECT   = 0.001;
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
        double Q_ratio = Q_total / fire.Q_steady;
        int srcRadius = 1 + (int)(Math.floor((fire.maxSourceRadius - 1) * Math.sqrt(Q_ratio)));
        if (srcRadius > fire.maxSourceRadius) srcRadius = fire.maxSourceRadius;
        double T_source = T_AMB + Q_ratio * (T_FLAME - T_AMB);
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
                            if (smokeInject > 0) nextC[nx][ny][nz] += smokeInject;
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
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    if (obstacle[x][y][z] != TYPE_FUEL || fuel[x][y][z] <= 0) continue;
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
            }
        }

        // ===================================================================
        // (4) 热辐射
        // ===================================================================
        double Q_per_source = Q_total / nSources;
        double Q_rad_source = _radFrac * Q_per_source;
        for (int idx = 0; idx < nSources; idx++) {
            int[] fp = fireSources.get(idx);
            addRadiation(fp[0], fp[1], fp[2], Q_rad_source, fire.radiationRadius, fire, nextT);
        }

        // ===================================================================
        // (5) 对流换热 - 优化项 2: 用 nextT 而非 T
        // ===================================================================
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
                        double deltaT_conv = q_conv * DT_FINAL / (rho * cp * cellVolume);
                        if (!Double.isNaN(deltaT_conv)) nextT[nx][ny][nz] += deltaT_conv;
                    }
        }

        // ===================================================================
        // (6) 热传导 - 优化项 4: Laplacian 动态权重(各向异性 + 墙体修正)
        // ===================================================================
        double alpha_eff = _alphaAir * _condBoost;
        double Fo = alpha_eff * DT_FINAL / dxSq;
        // ===== 优化项 4: 各向异性网格比 =====
        double anisotropyZ = dx / dz;  // z 方向细,权重调整
        double anisotropyFace = anisotropyZ;

        // ===== 优化项 4: 6 面邻居静态表(避免循环内反复 new 数组) =====
        final int[][] NB6_FACES = new int[][] { {-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1} };

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

        // ===================================================================
        // (7) 烟气扩散 - 优化项 13: 分层抑制(Richardson 数)
        // ===================================================================
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                for (int z = 0; z < d; z++) {
                    C_afterDiff[x][y][z] = nextC[x][y][z];
                }

        for (int x = 1; x < w-1; x++) {
            for (int y = 1; y < h-1; y++) {
                for (int z = 1; z < d-1; z++) {
                    if (obstacle[x][y][z] == TYPE_WALL || inside[x][y][z] == 0) continue;
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
                        double D_eff = _D_smoke_base * stratFactor;
                        // 修复: 顶棚层水平扩散增强5倍, 模拟顶棚射流的水平推进(烟雾能蔓延到整个建筑)
                        if (ceilingNearLayer[z]) D_eff *= 5.0;
                        double diff = D_eff * (laplacianC / totalW - nextC[x][y][z]) * DT_FINAL / dxSq;
                        C_afterDiff[x][y][z] = nextC[x][y][z] + diff;
                    }
                }
            }
        }

        // ===== 同时初始化 T_afterAdvection (用于温度对流) =====
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                for (int z = 0; z < d; z++) {
                    C_afterAdvection[x][y][z] = C_afterDiff[x][y][z];
                    T_afterAdvection[x][y][z] = nextT[x][y][z];
                }

        // ===================================================================
        // (8) 烟气平流 + 温度对流 - 优化项 1: 修正方向;优化项 3: 顶棚射流水平增强
        // ===================================================================
        final int ceilingZ = d - 1;
        double cjR = _cjRadius / dx;
        // ===== 性能修正: 限制顶棚射流搜索半径上限 6 格 =====
        final int CJ_MAX_RI = 6;
        int cjRi = (int)Math.ceil(cjR);
        if (cjRi > CJ_MAX_RI) cjRi = CJ_MAX_RI;
        if (cjRi < 2) cjRi = 2;
        double cjR_eff = (double)cjRi;
        double cjR2_05 = cjR_eff * cjR_eff * GAUSSIAN_SIGMA_FACTOR;
        double cjStrengthEff = _cjStrength * DT_FINAL;
        // ===== 性能优化: 顶棚射流在最顶3层处理,降低温度和浓度门限 =====
        final int CJ_Z_MIN = 0;  // 修复: 不再用网格顶部, 改用 ceilingNearLayer 动态判断
        final double CJ_EXCESS_T = 30.0;
        final double CJ_MIN_C = 0.002;

        for (int x = 1; x < w-1; x++) {
            for (int y = 1; y < h-1; y++) {
                for (int z = 1; z < d-1; z++) {
                    if (obstacle[x][y][z] == TYPE_WALL || inside[x][y][z] == 0) continue;
                    if (C_afterDiff[x][y][z] < MIN_SMOKE_FOR_ADVECT) continue;

                    double T_cell = nextT[x][y][z];
                    double excessT = Math.max(0.0, T_cell - T_AMB);

                    // ===== 优化项 1: 修正垂直烟气漂移方向 =====
                    // z=0 地板, z=d-1 顶棚,烟往 +z 方向漂移(向上)
                    double vz = _smkCoeff * excessT / T_AMB;
                    if (vz > _maxDrift) vz = _maxDrift;
                    if (vz > VEL_EPS) {
                        // 迎风格式: 从 z-1 向 z 输运,当 z-1 浓度更高时,输运到 z
                        double grad = (C_afterDiff[x][y][z] - C_afterDiff[x][y][z-1]) / dz;
                        double advZ = -vz * grad;
                        // ===== 修正:烟向上走,如果上层(当前格)浓度比下层低,则下层向上补充 =====
                        // 保证: 如果 C[z-1] > C[z],则从 z-1 搬到 z(正增量)
                        double C_lo = C_afterDiff[x][y][z-1];
                        double C_hi = C_afterDiff[x][y][z];
                        double deltaC = Math.max(0, C_lo - C_hi) * Math.min(1.0, vz * DT_FINAL / dz);
                        C_afterAdvection[x][y][z] += deltaC;
                        C_afterAdvection[x][y][z-1] -= deltaC;

                        // ===== 温度同样对流 =====
                        double T_lo = T_afterAdvection[x][y][z-1];
                        double T_hi = T_afterAdvection[x][y][z];
                        double deltaT_trans = Math.max(0, T_lo - T_hi) * Math.min(1.0, vz * DT_FINAL / dz);
                        T_afterAdvection[x][y][z] += deltaT_trans;
                        T_afterAdvection[x][y][z-1] -= deltaT_trans;
                    }

                    // ===== 水平漂移 =====
                    double gradX = (nextT[x+1][y][z] - nextT[x-1][y][z]) / twoDx;
                    double gradMagX = Math.abs(gradX);
                    if (gradMagX > GRAD_EPS) {
                        double vx = _smkCoeff * 0.5 * gradX / gradMagX * Math.max(excessT / T_AMB, 0.15);
                        if (vx > _maxDrift) vx = _maxDrift;
                        if (vx < -_maxDrift) vx = -_maxDrift;
                        double abs_vx = Math.abs(vx);
                        if (abs_vx > VEL_EPS) {
                            double frac = Math.min(1.0, abs_vx * DT_FINAL / dx);
                            if (vx > 0) {
                                // 往 +x 方向输运:从 x 搬到 x+1
                                double deltaC_x = C_afterDiff[x][y][z] * frac;
                                double deltaT_x = T_afterAdvection[x][y][z] * frac;
                                C_afterAdvection[x][y][z] -= deltaC_x;
                                C_afterAdvection[x+1][y][z] += deltaC_x;
                                T_afterAdvection[x][y][z] -= deltaT_x;
                                T_afterAdvection[x+1][y][z] += deltaT_x;
                            } else {
                                double deltaC_x = C_afterDiff[x][y][z] * frac;
                                double deltaT_x = T_afterAdvection[x][y][z] * frac;
                                C_afterAdvection[x][y][z] -= deltaC_x;
                                C_afterAdvection[x-1][y][z] += deltaC_x;
                                T_afterAdvection[x][y][z] -= deltaT_x;
                                T_afterAdvection[x-1][y][z] += deltaT_x;
                            }
                        }
                    }

                    double gradY = (nextT[x][y+1][z] - nextT[x][y-1][z]) / twoDx;
                    double gradMagY = Math.abs(gradY);
                    if (gradMagY > GRAD_EPS) {
                        double vy = _smkCoeff * 0.5 * gradY / gradMagY * Math.max(excessT / T_AMB, 0.15);
                        if (vy > _maxDrift) vy = _maxDrift;
                        if (vy < -_maxDrift) vy = -_maxDrift;
                        double abs_vy = Math.abs(vy);
                        if (abs_vy > VEL_EPS) {
                            double frac = Math.min(1.0, abs_vy * DT_FINAL / dx);
                            if (vy > 0) {
                                double deltaC_y = C_afterDiff[x][y][z] * frac;
                                double deltaT_y = T_afterAdvection[x][y][z] * frac;
                                C_afterAdvection[x][y][z] -= deltaC_y;
                                C_afterAdvection[x][y+1][z] += deltaC_y;
                                T_afterAdvection[x][y][z] -= deltaT_y;
                                T_afterAdvection[x][y+1][z] += deltaT_y;
                            } else {
                                double deltaC_y = C_afterDiff[x][y][z] * frac;
                                double deltaT_y = T_afterAdvection[x][y][z] * frac;
                                C_afterAdvection[x][y][z] -= deltaC_y;
                                C_afterAdvection[x][y-1][z] += deltaC_y;
                                T_afterAdvection[x][y][z] -= deltaT_y;
                                T_afterAdvection[x][y-1][z] += deltaT_y;
                            }
                        }
                    }

                    // ===== 优化项 10: 顶棚射流 (Ceiling Jet) =====
                    // 当烟接近/到达顶棚时,将垂直动量转为水平径向扩散
                    if (ceilingNearLayer[z] && excessT > CJ_EXCESS_T) {
                        double zNearCeil = 1.0;
                        double curC = C_afterDiff[x][y][z];
                        double curT = T_afterAdvection[x][y][z];
                        if (curC > CJ_MIN_C) {
                            for (int dx2 = -cjRi; dx2 <= cjRi; dx2++) {
                                for (int dy2 = -cjRi; dy2 <= cjRi; dy2++) {
                                    if (dx2 == 0 && dy2 == 0) continue;
                                    int nx = x + dx2, ny = y + dy2;
                                    if (nx < 1 || nx >= w-1 || ny < 1 || ny >= h-1) continue;
                                    if (inside[nx][ny][z] == 0 || obstacle[nx][ny][z] == TYPE_WALL) continue;
                                    double r2 = (double)dx2*dx2 + (double)dy2*dy2;
                                    if (r2 > cjRi * cjRi) continue;
                                    double decay = Math.exp(-r2 / cjR2_05);
                                    double amountC = curC * cjStrengthEff * decay * zNearCeil;
                                    double amountT = (curT - T_AMB) * cjStrengthEff * decay * zNearCeil;
                                    C_afterAdvection[x][y][z] -= amountC;
                                    C_afterAdvection[nx][ny][z] += amountC;
                                    T_afterAdvection[x][y][z] -= amountT;
                                    T_afterAdvection[nx][ny][z] += amountT;
                                }
                            }
                        }
                    }

                    if (C_afterAdvection[x][y][z] < 0) C_afterAdvection[x][y][z] = 0;
                    if (T_afterAdvection[x][y][z] < T_AMB) T_afterAdvection[x][y][z] = T_AMB;
                }
            }
        }

        // ===================================================================
        // 回写烟 + 温度
        // ===================================================================
        for (int[] cell : fire.insideCells) {
            int x = cell[0], y = cell[1], z = cell[2];
            nextC[x][y][z] = C_afterAdvection[x][y][z];
            nextT[x][y][z] = T_afterAdvection[x][y][z];
        }

        // ===================================================================
        // (9) 冷却衰减 + 优化项 14: 高温烟 Stefan-Boltzmann 辐射
        // ===================================================================
        for (int[] cell : fire.insideCells) {
            int x = cell[0], y = cell[1], z = cell[2];
                    nextT[x][y][z] -= _cooling * DT_FINAL * (nextT[x][y][z] - T_AMB);
                    if (nextT[x][y][z] < T_AMB) nextT[x][y][z] = T_AMB;

                    // ===== 优化项 14: 高温烟 Stefan-Boltzmann 辐射冷却 =====
                    if (nextT[x][y][z] > HOT_SMOKE_T_THRESHOLD) {
                        addRadiativeCooling(nextT, x, y, z, T_AMB, DT_FINAL, _smkAbsorb, rho, cp, dxSq, dz);
                    }

                    if (nextT[x][y][z] > T_MAX) nextT[x][y][z] = T_MAX;
                    if (Double.isNaN(nextT[x][y][z])) nextT[x][y][z] = T_AMB;

                    nextC[x][y][z] *= (1.0 - _smokeDecay * DT_FINAL);
                    if (nextC[x][y][z] < SMOKE_FLOOR) nextC[x][y][z] = SMOKE_FLOOR;
                    if (nextC[x][y][z] > SMOKE_CEIL) nextC[x][y][z] = SMOKE_CEIL;
                    if (Double.isNaN(nextC[x][y][z])) nextC[x][y][z] = 0.0;
                }

        // ===================================================================
        // 优化项 12: 开口流动 (Doorway Flow)
        // ===================================================================
        applyDoorwayFlow(nextT, nextC, obstacle, inside,
                w, h, d, T_AMB, T_FLAME, dx, dz, DT_FINAL,
                rho, _cp, _g, _cdCoef);

        // ===================================================================
        // (10) 排烟口处理
        // ===================================================================
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                boolean hasExit = false;
                for (int zz = 0; zz < d; zz++) { if (obstacle[x][y][zz] == TYPE_EXIT) { hasExit = true; break; } }
                if (!hasExit) continue;
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
        }

        // ===== 最终安全钳制 =====
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                for (int z = 0; z < d; z++) {
                    if (nextT[x][y][z] < T_AMB) nextT[x][y][z] = T_AMB;
                    if (nextT[x][y][z] > T_MAX) nextT[x][y][z] = T_MAX;
                    if (Double.isNaN(nextT[x][y][z])) nextT[x][y][z] = T_AMB;
                    if (nextC[x][y][z] < 0) nextC[x][y][z] = 0;
                    if (nextC[x][y][z] > SMOKE_CEIL) nextC[x][y][z] = SMOKE_CEIL;
                    if (Double.isNaN(nextC[x][y][z])) nextC[x][y][z] = 0;
                }

        // ★ 烟雾从窗户涌出: 先在建筑内扩散, 浓度足够高时才从窗户挤出, 然后柱状上升
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    if (obstacle[x][y][z] != 6) continue;
                    int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

                    // === 1. 窗户挤出: 只有当窗户内侧浓度足够高(>0.15)时才向外侧挤出 ===
                    // 真实火灾: 烟雾先充满房间, 内部压力/浓度足够高时才从窗户涌出
                    if (nextC[x][y][z] > 0.15) {
                        for (int[] dir : dirs) {
                            int nx = x+dir[0], ny = y+dir[1], nz = z+dir[2];
                            if (nx<0||nx>=w||ny<0||ny>=h||nz<0||nz>=d) continue;
                            if (obstacle[nx][ny][nz] == TYPE_WALL) continue;
                            if (inside[nx][ny][nz] == 0) continue;
                            double diff = nextC[x][y][z] - nextC[nx][ny][nz];
                            if (diff > 0.02) {
                                // 挤出系数: 水平方向0.45(比之前0.7降低), 垂直0.2
                                double coeff = (dir[2] == 0) ? 0.45 : 0.2;
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
            }
        }

        swapBuffers(fire, nextT, nextC);
    }

    // ======================================================================
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

    // ======================================================================
    // 辅助方法: 优化项 12 — 开口流动 (Doorway Flow)
    // ======================================================================
    private static void applyDoorwayFlow(double[][][] nextT, double[][][] nextC,
                                          int[][][] obstacle, int[][][] inside,
                                          int w, int h, int d,
                                          double T_AMB, double T_FLAME,
                                          double dx, double dz, double dt,
                                          double rho, double cp, double g, double Cd) {
        double h_door = (double)d * dz;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                boolean hasDoor = false;
                for (int zz = 0; zz < d; zz++) { if (obstacle[x][y][zz] == TYPE_DOOR) { hasDoor = true; break; } }
                if (!hasDoor) continue;
                if (inside[x][y][0] == 0) continue;

                double T_in_max = T_AMB;
                double T_in_avg = 0.0;
                int count = 0;
                for (int z = 0; z < d; z++) {
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
                double z_neutral = h_door / 2.0 * T_AMB / Math.sqrt(Math.max(T_eff * T_AMB, 1.0));

                for (int z = 0; z < d; z++) {
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
