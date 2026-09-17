package sim.app.fireescape;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 仅重跑网格收敛4档×30（物理一致性修正后），输出 grid_only.csv，列与 summary.csv 对齐。 */
public class GridOnly {
    public static void main(String[] a)throws IOException{
        Object[][] G={{"0.50",48,30,6,.50},{"0.30",80,50,10,.30},{"0.25",96,60,12,.25},{"0.20",120,75,15,.20}};
        PrintWriter w=new PrintWriter(new OutputStreamWriter(new FileOutputStream("E:/xuexi/MASON/_exp_work/results/grid_only.csv"),StandardCharsets.UTF_8));
        w.println("factor,level,seed,N,nFire,smokeMode,wD,wF,wS,delta,steps,endTime,escaped,deadF,deadS,active,evacRate,meanEvacTime,t95Evac");
        long t0=System.currentTimeMillis();
        for(int rep=0;rep<30;rep++){
            long seed=1000L+rep*37L;
            for(Object[]g:G){
                String lv=(String)g[0]; int W=(Integer)g[1],H=(Integer)g[2],D=(Integer)g[3];double dl=(Double)g[4];
                BatchExperiment.Res r=BatchExperiment.run(seed,120,BatchExperiment.FC3,.6,.2,.2,2,W,H,D,dl,false);
                w.printf(Locale.US,"grid,%s,%d,120,3,2,.6,.2,.2,%.3f,%d,%.2f,%d,%d,%d,%d,%.4f,%.3f,%.3f%n",
                    lv,seed,dl,r.steps,r.endTime,r.escaped,r.deadF,r.deadS,r.active,r.escaped/120.0,
                    Double.isNaN(r.meanEvac)?-1:r.meanEvac,Double.isNaN(r.t95)?-1:r.t95);
                w.flush();
            }
            if(rep%5==4)System.out.printf("grid rep %d/30 %.1fs%n",rep+1,(System.currentTimeMillis()-t0)/1000.0);
        }
        w.close();System.out.printf("GRID DONE %.1fs%n",(System.currentTimeMillis()-t0)/1000.0);
    }
}
