package sim.app.fireescape;

public class SmokeTest {
    public static void main(String[] x){
        int[] f3={0,2,4};
        long t=System.currentTimeMillis();
        // 基线: 100人 3火源 中性 完整烟气 Δ0.5
        BatchExperiment.Res r=BatchExperiment.run(42L,100,f3,0.6,0.2,0.2,2,30,20,10,0.5,true);
        int cons=r.escaped+r.deadF+r.deadS+r.active;
        System.out.printf("BASELINE steps=%d(%.1fs) esc=%d deadF=%d deadS=%d active=%d sum=%d meanEvac=%.2f t95=%.2f curve=%d cost=%dms%n",
            r.steps,r.endTime,r.escaped,r.deadF,r.deadS,r.active,cons,
            Double.isNaN(r.meanEvac)?-1:r.meanEvac, Double.isNaN(r.t95)?-1:r.t95, r.curve.size(), System.currentTimeMillis()-t);
        // 关闭烟气对照
        BatchExperiment.Res r2=BatchExperiment.run(42L,100,f3,0.6,0.2,0.2,0,30,20,10,0.5,false);
        System.out.printf("SMOKE-OFF steps=%d esc=%d deadF=%d deadS=%d active=%d sum=%d meanEvac=%.2f%n",
            r2.steps,r2.escaped,r2.deadF,r2.deadS,r2.active,r2.escaped+r2.deadF+r2.deadS+r2.active,
            Double.isNaN(r2.meanEvac)?-1:r2.meanEvac);
        // 高密度150
        BatchExperiment.Res r3=BatchExperiment.run(42L,150,f3,0.6,0.2,0.2,2,30,20,10,0.5,false);
        System.out.printf("N150 steps=%d esc=%d dead=%d active=%d sum=%d%n",r3.steps,r3.escaped,r3.deadF+r3.deadS,r3.active,r3.escaped+r3.deadF+r3.deadS+r3.active);
        // 细网格 Δ0.2 性能
        long t2=System.currentTimeMillis();
        BatchExperiment.Res r4=BatchExperiment.run(42L,100,f3,0.6,0.2,0.2,2,75,50,25,0.2,false);
        System.out.printf("GRID0.2 steps=%d esc=%d dead=%d active=%d cost=%dms%n",r4.steps,r4.escaped,r4.deadF+r4.deadS,r4.active,System.currentTimeMillis()-t2);
    }
}
