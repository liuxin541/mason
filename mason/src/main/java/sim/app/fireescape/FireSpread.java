package sim.app.fireescape;

import sim.engine.*;
import sim.field.grid.*;
import java.util.List;
import java.util.ArrayList;

public class FireSpread implements Steppable {
    private static final long serialVersionUID = 1L;

    private boolean hasLineOfSight(int x1, int y1, int x2, int y2, int[][][] obstacle) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1, sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            if (x1 < 0 || x1 >= obstacle.length || y1 < 0 || y1 >= obstacle[0].length) return false;
            if (obstacle[x1][y1][0] == 1) return false;
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
        double dx = fire.deltaX;
        double dz = fire.deltaZ;
        double dt = fire.deltaT;
        double rho = fire.rhoAir;
        double cp = fire.cpAir;

        int radR = (int)(radiusMeters / dx);
        int radZ = (int)(radiusMeters / dz);

        for (int dx2 = -radR; dx2 <= radR; dx2++) {
            for (int dy2 = -radR; dy2 <= radR; dy2++) {
                for (int dz2 = -radZ; dz2 <= radZ; dz2++) {
                    if (dx2 == 0 && dy2 == 0 && dz2 == 0) continue;
                    int nx = fire.fireGrid.stx(cx + dx2);
                    int ny = fire.fireGrid.sty(cy + dy2);
                    int nz = fire.fireGrid.stz(cz + dz2);
                    if (inside[nx][ny][nz] == 0 || obstacle[nx][ny][0] == 1) continue;
                    if (!hasLineOfSight(cx, cy, nx, ny, obstacle)) continue;

                    double dist = Math.sqrt(Math.pow(dx2 * dx, 2) + Math.pow(dy2 * dx, 2) + Math.pow(dz2 * dz, 2));
                    if (dist < 0.1) continue;

                    double q_rad = Q_rad * 1000.0 / (4.0 * Math.PI * dist * dist);
                    double cellVolume = dx * dx * dz;
                    double area = dx * dz;
                    double deltaT_rad = q_rad * area * dt / (rho * cp * cellVolume);

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

        final double T_AMB = FireEscape.T_AMBIENT;
        final double T_FLAME = FireEscape.T_FLAME;
        final double T_IGN = FireEscape.T_IGNITION;
        final double T_MAX = FireEscape.T_MAX;
        final double dx = fire.deltaX;
        final double dz = fire.deltaZ;
        final double dt = fire.deltaT;
        final double rho = fire.rhoAir;
        final double cp = fire.cpAir;
        final double cellVolume = dx * dx * dz;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    if (obstacle[x][y][0] == 1 || inside[x][y][z] == 0) {
                        nextT[x][y][z] = T_AMB;
                        nextC[x][y][z] = 0.0;
                    } else {
                        nextT[x][y][z] = T[x][y][z];
                        nextC[x][y][z] = C[x][y][z];
                    }
                }
            }
        }

        if (nSources == 0) {
            for (int x = 0; x < w; x++) for (int y = 0; y < h; y++) for (int z = 0; z < d; z++) {
                if (obstacle[x][y][0] == 1 || inside[x][y][z] == 0) continue;
                nextT[x][y][z] = T[x][y][z] - fire.coolingRate * dt * (T[x][y][z] - T_AMB);
                if (nextT[x][y][z] < T_AMB) nextT[x][y][z] = T_AMB;
                nextC[x][y][z] = C[x][y][z] * (1.0 - fire.smokeDecayRate * dt);
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

        for (int idx = 0; idx < nSources; idx++) {
            int[] fp = fireSources.get(idx);
            int cx = fp[0], cy = fp[1], cz = fp[2];
            for (int dx2 = -srcRadius; dx2 <= srcRadius; dx2++) {
                for (int dy2 = -srcRadius; dy2 <= srcRadius; dy2++) {
                    for (int dz2 = -srcRadius; dz2 <= srcRadius; dz2++) {
                        int nx = fire.fireGrid.stx(cx + dx2);
                        int ny = fire.fireGrid.sty(cy + dy2);
                        int nz = fire.fireGrid.stz(cz + dz2);
                        if (inside[nx][ny][nz] == 1 && obstacle[nx][ny][0] != 1) {
                            double dist = Math.sqrt(dx2*dx2 + dy2*dy2 + dz2*dz2);
                            if (dist > srcRadius + 0.5) continue;
                            double factor = Math.exp(-dist * dist / (srcRadius * srcRadius * 0.5));
                            double val = T_AMB + factor * (T_source - T_AMB);
                            if (val > nextT[nx][ny][nz]) nextT[nx][ny][nz] = val;
                        }
                    }
                }
            }
        }

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < d; z++) {
                    if (obstacle[x][y][0] == 2 && fuel[x][y][z] > 0 && T[x][y][z] >= T_IGN) {
                        double tempFactor = Math.min(1.5, (T[x][y][z] - T_IGN) / (T_FLAME - T_IGN) + 0.5);
                        if (tempFactor < 0.1) tempFactor = 0.1;
                        double burnRate = fire.burnRatePerArea * tempFactor;
                        double fuelBurned = Math.min(fuel[x][y][z], burnRate * dx * dx * dt);
                        if (fuelBurned > 0) {
                            double heatRelease = fuelBurned * fire.fuelHeatValue;
                            double deltaT_comb = heatRelease / (rho * cp * cellVolume);
                            if (!Double.isNaN(deltaT_comb)) nextT[x][y][z] += deltaT_comb;
                            nextC[x][y][z] += (fuelBurned * fire.smokeYield) / cellVolume;
                            fuel[x][y][z] -= fuelBurned;
                            if (fuel[x][y][z] <= 0) {
                                fuel[x][y][z] = 0;
                                obstacle[x][y][0] = 0;
                            }
                        }
                    }
                }
            }
        }

        double Q_per_source = Q_total / nSources;
        double Q_rad_source = fire.radiationFraction * Q_per_source;
        for (int idx = 0; idx < nSources; idx++) {
            int[] fp = fireSources.get(idx);
            addRadiation(fp[0], fp[1], fp[2], Q_rad_source, fire.radiationRadius, fire, nextT);
        }

        double convRadius = 1.0;
        int convR = (int)(convRadius / dx), convZ = (int)(convRadius / dz);
        for (int idx = 0; idx < nSources; idx++) {
            int[] fp = fireSources.get(idx);
            int cx = fp[0], cy = fp[1], cz = fp[2];
            for (int dx2 = -convR; dx2 <= convR; dx2++) for (int dy2 = -convR; dy2 <= convR; dy2++) for (int dz2 = -convZ; dz2 <= convZ; dz2++) {
                if (dx2 == 0 && dy2 == 0 && dz2 == 0) continue;
                int nx = fire.fireGrid.stx(cx + dx2), ny = fire.fireGrid.sty(cy + dy2), nz = fire.fireGrid.stz(cz + dz2);
                if (inside[nx][ny][nz] == 0 || obstacle[nx][ny][0] == 1) continue;
                double dist = Math.sqrt(Math.pow(dx2*dx,2) + Math.pow(dy2*dx,2) + Math.pow(dz2*dz,2));
                if (dist > convRadius) continue;
                double distFactor = 1.0 - dist / convRadius;
                double h_eff = fire.h_conv * distFactor;
                double q_conv = h_eff * (T_FLAME - nextT[nx][ny][nz]) * (dx * dz);
                double deltaT_conv = q_conv * dt / (rho * cp * cellVolume);
                if (!Double.isNaN(deltaT_conv)) nextT[nx][ny][nz] += deltaT_conv;
            }
        }

        double alpha_eff = fire.alpha_air * fire.conductionBoost;
        double Fo = alpha_eff * dt / (dx * dx);
        final double TOTAL_WEIGHT_3D = 18.0;

        for (int x = 1; x < w-1; x++) {
            for (int y = 1; y < h-1; y++) {
                for (int z = 1; z < d-1; z++) {
                    if (obstacle[x][y][0] == 1 || inside[x][y][z] == 0) continue;
                    double laplacian = 0.0;
                    for (int dx2 = -1; dx2 <= 1; dx2++) {
                        for (int dy2 = -1; dy2 <= 1; dy2++) {
                            for (int dz2 = -1; dz2 <= 1; dz2++) {
                                if (dx2 == 0 && dy2 == 0 && dz2 == 0) continue;
                                int nx = fire.fireGrid.stx(x + dx2);
                                int ny = fire.fireGrid.sty(y + dy2);
                                int nz = fire.fireGrid.stz(z + dz2);
                                double wgt = 1.0;
                                if (dx2 != 0 && dy2 != 0 && dz2 != 0) wgt = 0.25;
                                else if (dx2 != 0 && dy2 != 0 || dx2 != 0 && dz2 != 0 || dy2 != 0 && dz2 != 0) wgt = 0.5;
                                double neighborT = T_AMB;
                                if (obstacle[nx][ny][0] != 1 && inside[nx][ny][nz] == 1) {
                                    neighborT = nextT[nx][ny][nz];
                                }
                                laplacian += wgt * (neighborT - nextT[x][y][z]);
                            }
                        }
                    }
                    nextT[x][y][z] += Fo * (laplacian / TOTAL_WEIGHT_3D);
                    if (Double.isNaN(nextT[x][y][z])) nextT[x][y][z] = T_AMB;
                }
            }
        }

        double D_smoke = fire.baseSmokeSpread * 1.0;
        double[][][] C_afterDiff = new double[w][h][d];
        for (int x=0; x<w; x++) for (int y=0; y<h; y++) for (int z=0; z<d; z++) C_afterDiff[x][y][z] = nextC[x][y][z];

        for (int x = 1; x < w-1; x++) {
            for (int y = 1; y < h-1; y++) {
                for (int z = 1; z < d-1; z++) {
                    if (obstacle[x][y][0] == 1 || inside[x][y][z] == 0) continue;
                    double laplacianC = 0.0; double totalW = 0.0;
                    for (int dx2 = -1; dx2 <= 1; dx2++) {
                        for (int dy2 = -1; dy2 <= 1; dy2++) {
                            for (int dz2 = -1; dz2 <= 1; dz2++) {
                                if (dx2 == 0 && dy2 == 0 && dz2 == 0) continue;
                                int nx = fire.fireGrid.stx(x + dx2);
                                int ny = fire.fireGrid.sty(y + dy2);
                                int nz = fire.fireGrid.stz(z + dz2);
                                double wgt = 1.0;
                                if (dx2 != 0 && dy2 != 0 && dz2 != 0) wgt = 0.25;
                                else if (dx2 != 0 && dy2 != 0 || dx2 != 0 && dz2 != 0 || dy2 != 0 && dz2 != 0) wgt = 0.5;
                                if (obstacle[nx][ny][0] != 1 && inside[nx][ny][nz] == 1) {
                                    double tempFactor = 1.0 + 2.0 * (nextT[nx][ny][nz] - T_AMB) / T_AMB;
                                    if (tempFactor < 1.0) tempFactor = 1.0;
                                    laplacianC += wgt * nextC[nx][ny][nz] * tempFactor;
                                    totalW += wgt;
                                }
                            }
                        }
                    }
                    if (totalW > 0) {
                        double diff = D_smoke * (laplacianC / totalW - nextC[x][y][z]) * dt / (dx * dx);
                        C_afterDiff[x][y][z] = nextC[x][y][z] + diff;
                    }
                }
            }
        }

        double[][][] C_afterAdvection = new double[w][h][d];
        for (int x=0; x<w; x++) for (int y=0; y<h; y++) for (int z=0; z<d; z++) C_afterAdvection[x][y][z] = C_afterDiff[x][y][z];
        for (int x = 1; x < w-1; x++) {
            for (int y = 1; y < h-1; y++) {
                for (int z = 1; z < d-1; z++) {
                    if (obstacle[x][y][0] == 1 || inside[x][y][z] == 0 || C_afterDiff[x][y][z] < 0.001) continue;
                    double gradZ = (nextT[x][y][z+1] - nextT[x][y][z-1]) / (2 * dz);
                    double gradMag = Math.abs(gradZ);
                    if (gradMag < 1e-6) continue;
                    double driftSpeed = fire.smokeDriftCoeff * (nextT[x][y][z] - T_AMB) / T_AMB;
                    if (driftSpeed < 0.01) driftSpeed = 0.01;
                    if (driftSpeed > fire.smokeDriftMaxSpeed) driftSpeed = fire.smokeDriftMaxSpeed;
                    double vz = Math.min(0.0, -driftSpeed * (gradZ / gradMag));
                    if (vz < 0) {
                        double advZ = -vz * (C_afterDiff[x][y][z] - C_afterDiff[x][y][z-1]) / dz;
                        C_afterAdvection[x][y][z] += advZ * dt;
                        if (C_afterAdvection[x][y][z] < 0) C_afterAdvection[x][y][z] = 0;
                    }
                }
            }
        }

        for (int x=0; x<w; x++) for (int y=0; y<h; y++) for (int z=0; z<d; z++) {
            if (obstacle[x][y][0] == 1 || inside[x][y][z] == 0) continue;
            nextC[x][y][z] = C_afterAdvection[x][y][z];
        }

        for (int x=0; x<w; x++) for (int y=0; y<h; y++) for (int z=0; z<d; z++) {
            if (obstacle[x][y][0] == 1 || inside[x][y][z] == 0) continue;
            nextT[x][y][z] -= fire.coolingRate * dt * (nextT[x][y][z] - T_AMB);
            if (nextT[x][y][z] < T_AMB) nextT[x][y][z] = T_AMB;
            if (nextT[x][y][z] > T_MAX) nextT[x][y][z] = T_MAX;
            if (Double.isNaN(nextT[x][y][z])) nextT[x][y][z] = T_AMB;
            nextC[x][y][z] *= (1.0 - fire.smokeDecayRate * dt);
            if (nextC[x][y][z] < 0) nextC[x][y][z] = 0;
            if (Double.isNaN(nextC[x][y][z])) nextC[x][y][z] = 0.0;
        }

        for (int x=0; x<w; x++) for (int y=0; y<h; y++) {
            if (obstacle[x][y][0] != 4) continue;
            for (int dx2 = -2; dx2 <= 2; dx2++) for (int dy2 = -2; dy2 <= 2; dy2++) {
                int nx = x + dx2, ny = y + dy2;
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                for (int z=0; z<d; z++) {
                    if (inside[nx][ny][z] == 0 || obstacle[nx][ny][0] == 1) continue;
                    double dist = Math.hypot(dx2, dy2);
                    double exhaustRate = fire.exhaustRate * dt / (dist + 1.0);
                    nextC[nx][ny][z] -= exhaustRate * nextC[nx][ny][z];
                    if (nextC[nx][ny][z] < 0) nextC[nx][ny][z] = 0;
                    double tempFactor = (nextT[nx][ny][z] - T_AMB) / (T_FLAME - T_AMB);
                    nextT[nx][ny][z] -= exhaustRate * tempFactor * (nextT[nx][ny][z] - T_AMB);
                    if (nextT[nx][ny][z] < T_AMB) nextT[nx][ny][z] = T_AMB;
                }
            }
        }

        swapBuffers(fire, nextT, nextC);
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