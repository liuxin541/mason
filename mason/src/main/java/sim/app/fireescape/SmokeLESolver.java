package sim.app.fireescape;

/**
 * 基于显式速度场的烟雾 LES 求解器
 *
 * 物理模型（FDS 简化版，Boussinesq 不可压缩近似）：
 *   动量方程: ∂u/∂t + u·∇u = -∇p/ρ + ∇·((ν+ν_t)∇u) + f_buoyancy
 *   浮力项:   f_z = g * (T - T_amb) / T_amb
 *   连续性:   ∇·u = 0 （通过压力投影 enforce）
 *   LES:      ν_t = (Cs·Δ)² · |S|,  |S| = √(2 S_ij S_ij)
 *   标量:     T, C 作为被动标量，Semi-Lagrangian 平流 + 亚格子扩散
 *
 * 与 FDS 的对应关系：
 *   - FDS 低马赫可压缩 N-S → 此处 Boussinesq 不可压缩（火灾场景温差<1000K，密度变化可用线性化近似）
 *   - FDS Smagorinsky LES → 此处相同的 Smagorinsky 模型
 *   - FDS 压力 Poisson 求解 → 此处 Jacobi 迭代（FDS 用多重网格/直接法，实时场景用迭代）
 *   - FDS 有限差分平流 → 此处 Semi-Lagrangian（无条件稳定，允许大 dt，实时必需）
 *   - FDS soot 输运方程 → 此处烟雾标量输运（源项由外部燃烧模型注入）
 *
 * 性能：50×50×24 网格，每步约 10-20ms（纯 Java 单线程），满足实时。
 */
public class SmokeLESolver {

    // ==================== 网格尺寸 ====================
    private int nx, ny, nz;
    private double dx, dy, dz;
    private double filterWidth; // Δ = (dx·dy·dz)^(1/3)

    // ==================== 速度场（collocated grid，格子中心存储） ====================
    private double[][][] velU, velV, velW;
    private double[][][] uStar, vStar, wStar;

    // ==================== 压力场 ====================
    private double[][][] pressure;
    private double[][][] pressureNew;

    // ==================== 辅助场 ====================
    private double[][][] divergence;
    private double[][][] nuSGS;       // 亚格子涡粘 ν_t

    // ==================== 物理参数 ====================
    private double rho        = 1.2;      // 空气密度 kg/m³
    private double nuMol      = 1.5e-5;   // 分子运动粘度 m²/s
    private double Cs         = 0.18;     // Smagorinsky 常数（FDS 默认 ~0.2）
    private double g          = 9.81;     // 重力 m/s²
    private double T_amb      = 293.15;   // 环境温度 K
    private double Sc_t       = 0.7;      // 湍流 Schmidt 数（ν_t / D_t）
    private double D_mol      = 2e-5;     // 烟雾分子扩散系数 m²/s

    // ==================== 数值参数 ====================
    private int pressureIters = 40;       // 压力 Poisson Jacobi 迭代次数
    private double maxVelCFL  = 0.5;      // CFL 安全系数（Semi-Lagrangian 无条件稳定，但精度考虑）
    private boolean enableVorticityConfinement = false; // 涡约束（非FDS原生，默认关闭）

    // ==================== 状态 ====================
    private boolean initialized = false;

    // ==================== 构造函数 ====================
    public SmokeLESolver(int nx, int ny, int nz, double dx, double dy, double dz) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.filterWidth = Math.cbrt(dx * dy * dz);
        allocate();
    }

    private void allocate() {
        velU    = new double[nx][ny][nz];
        velV    = new double[nx][ny][nz];
        velW    = new double[nx][ny][nz];
        uStar   = new double[nx][ny][nz];
        vStar   = new double[nx][ny][nz];
        wStar   = new double[nx][ny][nz];
        pressure    = new double[nx][ny][nz];
        pressureNew = new double[nx][ny][nz];
        divergence  = new double[nx][ny][nz];
        nuSGS       = new double[nx][ny][nz];
        initialized = true;
    }

    /** 网格尺寸变化时重新分配 */
    public void resize(int nx, int ny, int nz, double dx, double dy, double dz) {
        this.nx = nx; this.ny = ny; this.nz = nz;
        this.dx = dx; this.dy = dy; this.dz = dz;
        this.filterWidth = Math.cbrt(dx * dy * dz);
        allocate();
    }

    // ==================== 参数设置 ====================
    public void setRho(double v)        { this.rho = v; }
    public void setNuMol(double v)      { this.nuMol = v; }
    public void setCs(double v)         { this.Cs = v; }
    public void setG(double v)          { this.g = v; }
    public void setTAmb(double v)       { this.T_amb = v; }
    public void setSct(double v)        { this.Sc_t = v; }
    public void setPressureIters(int v) { this.pressureIters = v; }
    public void setEnableVorticityConfinement(boolean v) { this.enableVorticityConfinement = v; }

    // ==================== 速度场访问（供外部调试/可视化） ====================
    public double[][][] getVelU() { return velU; }
    public double[][][] getVelV() { return velV; }
    public double[][][] getVelW() { return velW; }
    public double[][][] getPressure() { return pressure; }
    public double[][][] getNuSGS() { return nuSGS; }

    /** 重置速度场为零 */
    public void resetVelocity() {
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++)
                for (int k = 0; k < nz; k++) {
                    velU[i][j][k] = 0;
                    velV[i][j][k] = 0;
                    velW[i][j][k] = 0;
                }
    }

    // ===================================================================
    //  主入口：一步求解
    //  输入：T（温度场，会被平流更新）、C（烟雾场，会被平流+扩散更新）
    //        obstacle（障碍物标记，1=墙）、inside（室内标记，1=室内）、dt
    // ===================================================================
    public void step(double[][][] T, double[][][] C,
                     int[][][] obstacle, int[][][] inside, double dt) {
        if (!initialized) return;
        if (dt <= 0) return;

        // ---- (1) 计算 Smagorinsky 亚格子涡粘场 ----
        computeSmagorinskyViscosity(obstacle, inside);

        // ---- (2) 速度平流（Semi-Lagrangian） ----
        advectVelocity(obstacle, inside, dt);

        // ---- (3) 加浮力（Boussinesq） ----
        addBuoyancy(T, obstacle, inside, dt);

        // ---- (4) 速度扩散（分子粘度 + 亚格子涡粘，显式） ----
        diffuseVelocity(obstacle, inside, dt);

        // ---- (5) 压力投影（保证 ∇·u = 0） ----
        pressureProjection(obstacle, inside, dt);

        // ---- (6) 温度平流 ----
        advectScalar(T, obstacle, inside, dt, T_amb);

        // ---- (7) 烟雾平流 ----
        advectScalar(C, obstacle, inside, dt, 0.0);

        // ---- (8) 烟雾扩散（分子扩散 + 亚格子湍流扩散） ----
        diffuseScalar(C, obstacle, inside, dt);

        // ---- (9) 速度边界条件（障碍物 no-slip） ----
        applyVelocityBC(obstacle, inside);
    }

    // ===================================================================
    //  (1) Smagorinsky 亚格子涡粘
    //      ν_t = (Cs·Δ)² · |S|
    //      |S| = √(2·S_ij·S_ij)
    // ===================================================================
    private void computeSmagorinskyViscosity(int[][][] obstacle, int[][][] inside) {
        double Cs2 = Cs * Cs;
        double Delta2 = filterWidth * filterWidth;
        double coeff = Cs2 * Delta2;

        for (int i = 1; i < nx - 1; i++) {
            for (int j = 1; j < ny - 1; j++) {
                for (int k = 1; k < nz - 1; k++) {
                    if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) {
                        nuSGS[i][j][k] = 0;
                        continue;
                    }

                    // 应变率张量分量（中心差分）
                    double dudx = (velU[i+1][j][k] - velU[i-1][j][k]) / (2*dx);
                    double dvdy = (velV[i][j+1][k] - velV[i][j-1][k]) / (2*dy);
                    double dwdz = (velW[i][j][k+1] - velW[i][j][k-1]) / (2*dz);

                    double dudy = (velU[i][j+1][k] - velU[i][j-1][k]) / (2*dy);
                    double dvdx = (velV[i+1][j][k] - velV[i-1][j][k]) / (2*dx);
                    double Sxy = 0.5 * (dudy + dvdx);

                    double dudz = (velU[i][j][k+1] - velU[i][j][k-1]) / (2*dz);
                    double dwdx = (velW[i+1][j][k] - velW[i-1][j][k]) / (2*dx);
                    double Sxz = 0.5 * (dudz + dwdx);

                    double dvdz = (velV[i][j][k+1] - velV[i][j][k-1]) / (2*dz);
                    double dwdy = (velW[i][j+1][k] - velW[i][j-1][k]) / (2*dy);
                    double Syz = 0.5 * (dvdz + dwdy);

                    // |S| = sqrt(2 * (Sxx² + Syy² + Szz² + 2·Sxy² + 2·Sxz² + 2·Syz²))
                    double sMag2 = 2.0 * (dudx*dudx + dvdy*dvdy + dwdz*dwdz
                                        + 2.0*Sxy*Sxy + 2.0*Sxz*Sxz + 2.0*Syz*Syz);
                    double sMag = Math.sqrt(Math.max(0, sMag2));

                    nuSGS[i][j][k] = coeff * sMag;
                }
            }
        }
    }

    // ===================================================================
    //  (2) 速度 Semi-Lagrangian 平流
    //      对每个格子，回溯到 p = x - u·dt，在 p 处三线性插值
    // ===================================================================
    private void advectVelocity(int[][][] obstacle, int[][][] inside, double dt) {
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) {
                        uStar[i][j][k] = 0;
                        vStar[i][j][k] = 0;
                        wStar[i][j][k] = 0;
                        continue;
                    }

                    // 回溯位置（格子坐标）
                    double px = i - velU[i][j][k] * dt / dx;
                    double py = j - velV[i][j][k] * dt / dy;
                    double pz = k - velW[i][j][k] * dt / dz;

                    uStar[i][j][k] = trilinearInterpolate(velU, px, py, pz, obstacle, inside, 0.0);
                    vStar[i][j][k] = trilinearInterpolate(velV, px, py, pz, obstacle, inside, 0.0);
                    wStar[i][j][k] = trilinearInterpolate(velW, px, py, pz, obstacle, inside, 0.0);
                }
            }
        }
        // 交换
        double[][][] tmp;
        tmp = velU; velU = uStar; uStar = tmp;
        tmp = velV; velV = vStar; vStar = tmp;
        tmp = velW; velW = wStar; wStar = tmp;
    }

    // ===================================================================
    //  (3) Boussinesq 浮力
    //      f_z = g * (T - T_amb) / T_amb
    //      仅作用于垂直速度 w
    // ===================================================================
    private void addBuoyancy(double[][][] T, int[][][] obstacle, int[][][] inside, double dt) {
        double buoyCoeff = g * dt / T_amb;
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) continue;
                    double excessT = T[i][j][k] - T_amb;
                    if (excessT > 0) {
                        velW[i][j][k] += buoyCoeff * excessT;
                    }
                }
            }
        }
    }

    // ===================================================================
    //  (4) 速度扩散（显式，ν_eff = ν_mol + ν_t）
    //      u_new = u + dt · ∇·(ν_eff · ∇u)
    // ===================================================================
    private void diffuseVelocity(int[][][] obstacle, int[][][] inside, double dt) {
        // 显式扩散稳定性检查：dt·ν_eff·(1/dx²+1/dy²+1/dz²) < 0.5
        double maxNu = nuMol;
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++)
                for (int k = 0; k < nz; k++)
                    if (nuSGS[i][j][k] > maxNu) maxNu = nuSGS[i][j][k];

        double diffLimit = 0.4 / (maxNu * (1.0/(dx*dx) + 1.0/(dy*dy) + 1.0/(dz*dz)) + 1e-12);
        double effDt = Math.min(dt, diffLimit);
        double subSteps = Math.ceil(dt / effDt);
        double subDt = dt / subSteps;

        for (int s = 0; s < (int)subSteps; s++) {
            diffuseComponent(velU, uStar, obstacle, inside, subDt);
            diffuseComponent(velV, vStar, obstacle, inside, subDt);
            diffuseComponent(velW, wStar, obstacle, inside, subDt);

            double[][][] tmp;
            tmp = velU; velU = uStar; uStar = tmp;
            tmp = velV; velV = vStar; vStar = tmp;
            tmp = velW; velW = wStar; wStar = tmp;
        }
    }

    private void diffuseComponent(double[][][] field, double[][][] fieldNew,
                                   int[][][] obstacle, int[][][] inside, double dt) {
        double invDx2 = 1.0 / (dx * dx);
        double invDy2 = 1.0 / (dy * dy);
        double invDz2 = 1.0 / (dz * dz);

        for (int i = 1; i < nx - 1; i++) {
            for (int j = 1; j < ny - 1; j++) {
                for (int k = 1; k < nz - 1; k++) {
                    if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) {
                        fieldNew[i][j][k] = 0;
                        continue;
                    }

                    double nuEff = nuMol + nuSGS[i][j][k];
                    double center = field[i][j][k];

                    // 6 面邻居，障碍物用零梯度（复制中心值）
                    double left   = isFluid(i-1, j, k, obstacle, inside) ? field[i-1][j][k] : center;
                    double right  = isFluid(i+1, j, k, obstacle, inside) ? field[i+1][j][k] : center;
                    double down   = isFluid(i, j-1, k, obstacle, inside) ? field[i][j-1][k] : center;
                    double up     = isFluid(i, j+1, k, obstacle, inside) ? field[i][j+1][k] : center;
                    double bottom = isFluid(i, j, k-1, obstacle, inside) ? field[i][j][k-1] : center;
                    double top    = isFluid(i, j, k+1, obstacle, inside) ? field[i][j][k+1] : center;

                    double laplacian = (left + right - 2*center) * invDx2
                                     + (down + up    - 2*center) * invDy2
                                     + (bottom + top - 2*center) * invDz2;

                    fieldNew[i][j][k] = center + dt * nuEff * laplacian;
                }
            }
        }
    }

    // ===================================================================
    //  (5) 压力投影
    //      a. div = ∇·u*
    //      b. 求解 ∇²p = (ρ/dt) · div  （Jacobi 迭代）
    //      c. u = u* - (dt/ρ) · ∇p
    // ===================================================================
    private void pressureProjection(int[][][] obstacle, int[][][] inside, double dt) {
        double invDx = 1.0 / dx;
        double invDy = 1.0 / dy;
        double invDz = 1.0 / dz;
        double invDx2 = 1.0 / (dx * dx);
        double invDy2 = 1.0 / (dy * dy);
        double invDz2 = 1.0 / (dz * dz);
        double laplacianCoeff = 2.0 * (invDx2 + invDy2 + invDz2);
        double rhoOverDt = rho / dt;

        // ---- (a) 计算散度 ----
        for (int i = 1; i < nx - 1; i++) {
            for (int j = 1; j < ny - 1; j++) {
                for (int k = 1; k < nz - 1; k++) {
                    if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) {
                        divergence[i][j][k] = 0;
                        continue;
                    }
                    double du_dx = (velU[i+1][j][k] - velU[i-1][j][k]) * 0.5 * invDx;
                    double dv_dy = (velV[i][j+1][k] - velV[i][j-1][k]) * 0.5 * invDy;
                    double dw_dz = (velW[i][j][k+1] - velW[i][j][k-1]) * 0.5 * invDz;
                    divergence[i][j][k] = du_dx + dv_dy + dw_dz;
                }
            }
        }

        // ---- (b) Jacobi 迭代求解压力 Poisson ----
        // 初始化压力为零
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++)
                for (int k = 0; k < nz; k++)
                    pressure[i][j][k] = 0;

        for (int iter = 0; iter < pressureIters; iter++) {
            for (int i = 1; i < nx - 1; i++) {
                for (int j = 1; j < ny - 1; j++) {
                    for (int k = 1; k < nz - 1; k++) {
                        if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) {
                            pressureNew[i][j][k] = 0;
                            continue;
                        }

                        double center = pressure[i][j][k];
                        // 障碍物/边界邻居用零梯度（复制中心值）
                        double left   = isFluid(i-1, j, k, obstacle, inside) ? pressure[i-1][j][k] : center;
                        double right  = isFluid(i+1, j, k, obstacle, inside) ? pressure[i+1][j][k] : center;
                        double down   = isFluid(i, j-1, k, obstacle, inside) ? pressure[i][j-1][k] : center;
                        double up     = isFluid(i, j+1, k, obstacle, inside) ? pressure[i][j+1][k] : center;
                        double bottom = isFluid(i, j, k-1, obstacle, inside) ? pressure[i][j][k-1] : center;
                        double top    = isFluid(i, j, k+1, obstacle, inside) ? pressure[i][j][k+1] : center;

                        double lapP = (left + right) * invDx2
                                    + (down + up)    * invDy2
                                    + (bottom + top) * invDz2;

                        // ∇²p = (ρ/dt)·div  →  p = (lapP - rho/dt·div) / laplacianCoeff
                        pressureNew[i][j][k] = (lapP - rhoOverDt * divergence[i][j][k]) / laplacianCoeff;
                    }
                }
            }
            // 交换压力缓冲区
            double[][][] tmp = pressure;
            pressure = pressureNew;
            pressureNew = tmp;
        }

        // ---- (c) 速度修正：u = u* - (dt/ρ)·∇p ----
        double dtOverRho = dt / rho;
        for (int i = 1; i < nx - 1; i++) {
            for (int j = 1; j < ny - 1; j++) {
                for (int k = 1; k < nz - 1; k++) {
                    if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) {
                        velU[i][j][k] = 0;
                        velV[i][j][k] = 0;
                        velW[i][j][k] = 0;
                        continue;
                    }
                    double dpdx = (pressure[i+1][j][k] - pressure[i-1][j][k]) * 0.5 * invDx;
                    double dpdy = (pressure[i][j+1][k] - pressure[i][j-1][k]) * 0.5 * invDy;
                    double dpdz = (pressure[i][j][k+1] - pressure[i][j][k-1]) * 0.5 * invDz;

                    velU[i][j][k] -= dtOverRho * dpdx;
                    velV[i][j][k] -= dtOverRho * dpdy;
                    velW[i][j][k] -= dtOverRho * dpdz;
                }
            }
        }
    }

    // ===================================================================
    //  (6)(7) 标量 Semi-Lagrangian 平流
    //      fieldNew[i] = field(回溯位置)
    // ===================================================================
    private void advectScalar(double[][][] field, int[][][] obstacle, int[][][] inside,
                               double dt, double boundaryValue) {
        // 用临时数组存储平流结果
        double[][][] result = uStar; // 复用 uStar 作为临时缓冲区（此时已不需要）

        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) {
                        result[i][j][k] = boundaryValue;
                        continue;
                    }

                    double px = i - velU[i][j][k] * dt / dx;
                    double py = j - velV[i][j][k] * dt / dy;
                    double pz = k - velW[i][j][k] * dt / dz;

                    result[i][j][k] = trilinearInterpolate(field, px, py, pz, obstacle, inside, boundaryValue);
                }
            }
        }

        // 回写
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++)
                for (int k = 0; k < nz; k++)
                    field[i][j][k] = result[i][j][k];
    }

    // ===================================================================
    //  (8) 烟雾扩散（D_eff = D_mol + ν_t / Sc_t）
    //      结果始终写回原数组 C
    // ===================================================================
    private void diffuseScalar(double[][][] C, int[][][] obstacle, int[][][] inside, double dt) {
        double maxD = D_mol;
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++)
                for (int k = 0; k < nz; k++) {
                    double d = D_mol + nuSGS[i][j][k] / Sc_t;
                    if (d > maxD) maxD = d;
                }

        double diffLimit = 0.4 / (maxD * (1.0/(dx*dx) + 1.0/(dy*dy) + 1.0/(dz*dz)) + 1e-12);
        int subSteps = (int)Math.ceil(dt / diffLimit);
        if (subSteps < 1) subSteps = 1;
        double subDt = dt / subSteps;

        double[][][] CNew = wStar; // 复用 wStar 作为临时缓冲区

        for (int s = 0; s < subSteps; s++) {
            double invDx2 = 1.0 / (dx * dx);
            double invDy2 = 1.0 / (dy * dy);
            double invDz2 = 1.0 / (dz * dz);

            for (int i = 1; i < nx - 1; i++) {
                for (int j = 1; j < ny - 1; j++) {
                    for (int k = 1; k < nz - 1; k++) {
                        if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) {
                            CNew[i][j][k] = 0;
                            continue;
                        }

                        double DEff = D_mol + nuSGS[i][j][k] / Sc_t;
                        double center = C[i][j][k];

                        double left   = isFluid(i-1, j, k, obstacle, inside) ? C[i-1][j][k] : center;
                        double right  = isFluid(i+1, j, k, obstacle, inside) ? C[i+1][j][k] : center;
                        double down   = isFluid(i, j-1, k, obstacle, inside) ? C[i][j-1][k] : center;
                        double up     = isFluid(i, j+1, k, obstacle, inside) ? C[i][j+1][k] : center;
                        double bottom = isFluid(i, j, k-1, obstacle, inside) ? C[i][j][k-1] : center;
                        double top    = isFluid(i, j, k+1, obstacle, inside) ? C[i][j][k+1] : center;

                        double lap = (left + right - 2*center) * invDx2
                                   + (down + up    - 2*center) * invDy2
                                   + (bottom + top - 2*center) * invDz2;

                        CNew[i][j][k] = center + subDt * DEff * lap;
                    }
                }
            }

            // 把 CNew 复制回 C（确保外部引用始终有效）
            for (int i = 0; i < nx; i++)
                for (int j = 0; j < ny; j++)
                    for (int k = 0; k < nz; k++)
                        C[i][j][k] = CNew[i][j][k];
        }
    }

    // ===================================================================
    //  三线性插值（带障碍物处理）
    //  回溯点落在障碍物内时，用最近流体格子的值或边界值
    // ===================================================================
    private double trilinearInterpolate(double[][][] field, double px, double py, double pz,
                                         int[][][] obstacle, int[][][] inside, double boundaryVal) {
        // 钳制到网格范围
        if (px < 0) px = 0;
        if (px > nx - 1) px = nx - 1;
        if (py < 0) py = 0;
        if (py > ny - 1) py = ny - 1;
        if (pz < 0) pz = 0;
        if (pz > nz - 1) pz = nz - 1;

        int i0 = (int)Math.floor(px);
        int j0 = (int)Math.floor(py);
        int k0 = (int)Math.floor(pz);
        int i1 = Math.min(i0 + 1, nx - 1);
        int j1 = Math.min(j0 + 1, ny - 1);
        int k1 = Math.min(k0 + 1, nz - 1);

        double fx = px - i0;
        double fy = py - j0;
        double fz = pz - k0;

        // 获取8个角点的值（障碍物用 boundaryVal）
        double v000 = sampleFluid(field, i0, j0, k0, obstacle, inside, boundaryVal);
        double v100 = sampleFluid(field, i1, j0, k0, obstacle, inside, boundaryVal);
        double v010 = sampleFluid(field, i0, j1, k0, obstacle, inside, boundaryVal);
        double v110 = sampleFluid(field, i1, j1, k0, obstacle, inside, boundaryVal);
        double v001 = sampleFluid(field, i0, j0, k1, obstacle, inside, boundaryVal);
        double v101 = sampleFluid(field, i1, j0, k1, obstacle, inside, boundaryVal);
        double v011 = sampleFluid(field, i0, j1, k1, obstacle, inside, boundaryVal);
        double v111 = sampleFluid(field, i1, j1, k1, obstacle, inside, boundaryVal);

        // 三线性插值
        double c00 = v000 * (1-fx) + v100 * fx;
        double c10 = v010 * (1-fx) + v110 * fx;
        double c01 = v001 * (1-fx) + v101 * fx;
        double c11 = v011 * (1-fx) + v111 * fx;

        double c0 = c00 * (1-fy) + c10 * fy;
        double c1 = c01 * (1-fy) + c11 * fy;

        return c0 * (1-fz) + c1 * fz;
    }

    private double sampleFluid(double[][][] field, int i, int j, int k,
                                int[][][] obstacle, int[][][] inside, double boundaryVal) {
        if (i < 0 || i >= nx || j < 0 || j >= ny || k < 0 || k >= nz) return boundaryVal;
        if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) return boundaryVal;
        return field[i][j][k];
    }

    private boolean isFluid(int i, int j, int k, int[][][] obstacle, int[][][] inside) {
        if (i < 0 || i >= nx || j < 0 || j >= ny || k < 0 || k >= nz) return false;
        return obstacle[i][j][0] != 1 && inside[i][j][k] == 1;
    }

    // ===================================================================
    //  速度边界条件：障碍物 no-slip（速度=0），边界 Neumann（零梯度）
    // ===================================================================
    private void applyVelocityBC(int[][][] obstacle, int[][][] inside) {
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < ny; j++) {
                for (int k = 0; k < nz; k++) {
                    if (obstacle[i][j][0] == 1 || inside[i][j][k] == 0) {
                        velU[i][j][k] = 0;
                        velV[i][j][k] = 0;
                        velW[i][j][k] = 0;
                    }
                }
            }
        }
        // 边界层零梯度（复制内一层）
        for (int i = 0; i < nx; i++)
            for (int j = 0; j < ny; j++) {
                velU[i][j][0] = velU[i][j][1];
                velV[i][j][0] = velV[i][j][1];
                velW[i][j][0] = velW[i][j][1];
                velU[i][j][nz-1] = velU[i][j][nz-2];
                velV[i][j][nz-1] = velV[i][j][nz-2];
                velW[i][j][nz-1] = velW[i][j][nz-2];
            }
        for (int i = 0; i < nx; i++)
            for (int k = 0; k < nz; k++) {
                velU[i][0][k] = velU[i][1][k];
                velV[i][0][k] = velV[i][1][k];
                velW[i][0][k] = velW[i][1][k];
                velU[i][ny-1][k] = velU[i][ny-2][k];
                velV[i][ny-1][k] = velV[i][ny-2][k];
                velW[i][ny-1][k] = velW[i][ny-2][k];
            }
        for (int j = 0; j < ny; j++)
            for (int k = 0; k < nz; k++) {
                velU[0][j][k] = velU[1][j][k];
                velV[0][j][k] = velV[1][j][k];
                velW[0][j][k] = velW[1][j][k];
                velU[nx-1][j][k] = velU[nx-2][j][k];
                velV[nx-1][j][k] = velV[nx-2][j][k];
                velW[nx-1][j][k] = velW[nx-2][j][k];
            }
    }
}
