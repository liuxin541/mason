package sim.app.fireescape;

import sim.engine.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 性能基准：无界面内核推演的单步耗时与实时加速比。
 * 场景与 BatchExperiment 基准一致：24x15x3m 大厅(48x30x6, Δ=0.5m)，
 * 3 火源(FC3)、中性权重(0.6/0.2/0.2)、完整烟气(smokeMode=2)、格点排他。
 * 人数规模 60/120/180/500，每规模 3 个配对种子取均值。
 * 实时加速比 = Δt / 每步耗时 = 0.5s / 每步耗时（1× 实时 = 每步 ≤ 500ms）。
 */
public class PerfBenchmark {

    static final double[][] POOL = {
        {0.12,0.25},{0.12,0.75},{0.45,0.50},{0.68,0.78},{0.82,0.30}
    };
    static final int[] FC3 = {0,2,4};
    static final int W=48,H=30,D=6;
    static final int MAX_PHYS_SEC = 600;   // 最长物理时间 600s（与 BatchExperiment 一致）
    static final double DELTA_T = 0.5;     // s/步

    static int[] toGrid(double rx,double ry,int W,int H){
        return new int[]{Math.max(1,Math.min(W-2,(int)Math.round(rx*W))),
                         Math.max(1,Math.min(H-2,(int)Math.round(ry*H))),1};
    }

    /** 返回 {wall_ns, steps} */
    static long[] runOnce(long seed,int N,int maxSteps){
        FireEscape m=new FireEscape(seed);
        m.mapMode="simple"; m.personCount=N;
        m.wDist=0.6;m.wFire=0.2;m.wSmoke=0.2;m.smokeMode=2;m.occupantExclusion=true;
        m.loadSimpleRectRoom(W,H,D,0.5);
        m.fireSourcePositions.clear();
        for(int i:FC3) m.fireSourcePositions.add(toGrid(POOL[i][0],POOL[i][1],W,H));
        m.initMultipleFireSources(); m.initExits();
        m.deadByFire=0;m.deadBySmoke=0;m.currentTime=0;
        m.persons=new Person[N]; Steppable[] ag=new Steppable[N];
        for(int i=0;i<N;i++){
            int x=1,y=1,z=1,att=0;
            do{x=1+m.random.nextInt(W-2);y=1+m.random.nextInt(H-2);att++;if(att>50000)break;}
            while(!(m.insideGrid.get(x,y,z)==1&&m.obstacleGrid.get(x,y,z)==0)
                  ||m.fireGrid.get(x,y,z)>FireEscape.T_AMBIENT+50);
            m.persons[i]=new Person(m,x,y,z);
            m.personGrid.setObjectLocation(m.persons[i],x,y,z); ag[i]=m.persons[i];
        }
        m.schedule.clear();
        m.schedule.scheduleRepeating(new RandomSequence(ag));
        m.schedule.scheduleRepeating(Schedule.EPOCH,1,new FireSpread(),1);
        int step=0;
        long t0=System.nanoTime();
        while(step<maxSteps){
            m.schedule.step(m); step++;
            if(m.getActivePersonCount()==0)break;
        }
        long wall=System.nanoTime()-t0;
        m.finish();
        return new long[]{wall,step};
    }

    public static void main(String[] a)throws IOException{
        int[] sizes={60,120,180,500};
        long[] seeds={1000L,1037L,1074L,1111L,1148L};
        int maxSteps=(int)Math.round(MAX_PHYS_SEC/DELTA_T);
        PrintWriter out=new PrintWriter(new OutputStreamWriter(
            new FileOutputStream("E:/xuexi/MASON/_exp_work/perf_results.csv"),StandardCharsets.UTF_8));
        out.println("N,seed,steps,wall_ms,perStep_ms,speedupX,physSec");
        System.out.println("N\tseed\tsteps\twall_ms\tperStep_ms\tspeedupX");
        for(int N:sizes){
            runOnce(1000L,N,maxSteps); // 同规模 JIT 预热，丢弃
            for(long seed:seeds){
                long[] r=runOnce(seed,N,maxSteps);
                double wallMs=r[0]/1e6;
                double perStepMs=wallMs/Math.max(1,r[1]);
                double speedup=DELTA_T*1000.0/perStepMs;   // 相对物理时间实时
                double physSec=r[1]*DELTA_T;
                out.printf(Locale.US,"%d,%d,%d,%.1f,%.3f,%.2f,%.1f%n",
                    N,seed,r[1],wallMs,perStepMs,speedup,physSec);
                System.out.printf(Locale.US,"%d\t%d\t%d\t%.1f\t%.3f\t%.2f%n",
                    N,seed,r[1],wallMs,perStepMs,speedup);
                out.flush();
            }
        }
        out.close();
        System.out.println("PERF DONE");
    }
}
