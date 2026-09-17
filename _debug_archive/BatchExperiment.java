package sim.app.fireescape;

import sim.engine.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 无界面批量对照实验（最终矩阵）。
 * 基准场景：24m x 15m x 3m 大厅(48x30x6, Δ=0.5m)，右侧墙中部单出口(宽1m)，开启格点排他。
 * 基线：N=120、3火源、中性权重、完整烟气。每组 30 个配对随机种子。
 * 输出 results/summary.csv 与 results/curves.csv。
 */
public class BatchExperiment {

    static final String OUT_DIR = "E:/xuexi/MASON/_exp_work/results";
    // 5 个固定火源候选点(物理比例 rx,ry; z=1 贴地)
    static final double[][] POOL = {
        {0.12,0.25},{0.12,0.75},{0.45,0.50},{0.68,0.78},{0.82,0.30}
    };
    static final int[] FC1={2}, FC3={0,2,4}, FC5={0,1,2,3,4};
    static final int W=48,H=30,D=6;          // 基准网格 Δ=0.5
    static PrintWriter sumW, curW;
    static final int CURVE_EVERY=4;          // 每4步=2s

    static class Res {
        int steps,escaped,deadF,deadS,active; double endTime,meanEvac,t95;
        List<int[]> curve=new ArrayList<>();
    }

    static int[] toGrid(double rx,double ry,int W,int H){
        return new int[]{Math.max(1,Math.min(W-2,(int)Math.round(rx*W))),
                         Math.max(1,Math.min(H-2,(int)Math.round(ry*H))),1};
    }

    static Res run(long seed,int N,int[] fireIdx,double wD,double wF,double wS,
                   int smokeMode,int W,int H,int D,double delta,boolean recordCurve){
        FireEscape m=new FireEscape(seed);
        m.mapMode="simple"; m.personCount=N;
        m.wDist=wD;m.wFire=wF;m.wSmoke=wS;m.smokeMode=smokeMode;m.occupantExclusion=true;
        m.loadSimpleRectRoom(W,H,D,delta);
        m.fireSourcePositions.clear();
        for(int i:fireIdx) m.fireSourcePositions.add(toGrid(POOL[i][0],POOL[i][1],W,H));
        m.initMultipleFireSources(); m.initExits();
        m.deadByFire=0;m.deadBySmoke=0;m.currentTime=0;
        m.persons=new Person[N]; Steppable[] ag=new Steppable[N];
        for(int i=0;i<N;i++){
            int x,y,z=1,att=0;
            do{x=1+m.random.nextInt(W-2);y=1+m.random.nextInt(H-2);att++;if(att>3000)break;}
            while(!(m.insideGrid.get(x,y,z)==1&&m.obstacleGrid.get(x,y,z)==0)
                  ||m.fireGrid.get(x,y,z)>FireEscape.T_AMBIENT+50);
            m.persons[i]=new Person(m,x,y,z);
            m.personGrid.setObjectLocation(m.persons[i],x,y,z); ag[i]=m.persons[i];
        }
        m.schedule.clear();
        m.schedule.scheduleRepeating(new RandomSequence(ag));
        m.schedule.scheduleRepeating(Schedule.EPOCH,1,new FireSpread(),1);
        int max=(int)Math.round(600.0/m.deltaT),step=0; Res r=new Res();
        while(step<max){
            m.schedule.step(m); step++;
            if(recordCurve&&step%CURVE_EVERY==0) r.curve.add(new int[]{step,m.getEscapedCount(),m.getDeadCount()});
            if(m.getActivePersonCount()==0)break;
        }
        r.steps=step;r.endTime=m.currentTime;r.escaped=m.getEscapedCount();
        r.deadF=m.getDeadByFire();r.deadS=m.getDeadBySmoke();r.active=m.getActivePersonCount();
        double[] et=new double[r.escaped];int k=0;
        for(Person p:m.persons)if(p!=null&&p.escaped&&p.escapeTime>=0)et[k++]=p.escapeTime;
        Arrays.sort(et);double s=0;for(double v:et)s+=v;
        r.meanEvac=et.length>0?s/et.length:Double.NaN;
        r.t95=et.length>0?et[Math.max(0,(int)Math.ceil(0.95*et.length)-1)]:Double.NaN;
        m.finish(); return r;
    }

    static void emit(String f,String lv,long seed,int N,int nf,int sm,double wD,double wF,double wS,
                     double delta,Res r,boolean curve)throws IOException{
        sumW.printf(Locale.US,"%s,%s,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.3f,%d,%.2f,%d,%d,%d,%d,%.4f,%.3f,%.3f%n",
            f,lv,seed,N,nf,sm,wD,wF,wS,delta,r.steps,r.endTime,r.escaped,r.deadF,r.deadS,r.active,
            r.escaped/(double)N,Double.isNaN(r.meanEvac)?-1:r.meanEvac,Double.isNaN(r.t95)?-1:r.t95);sumW.flush();
        if(curve)for(int[]c:r.curve)curW.printf(Locale.US,"%s,%s,%d,%.2f,%d,%d%n",f,lv,seed,c[0]*0.5,c[1],c[2]);
    }

    public static void main(String[] a)throws IOException{
        new File(OUT_DIR).mkdirs();
        sumW=new PrintWriter(new OutputStreamWriter(new FileOutputStream(OUT_DIR+"/summary.csv"),StandardCharsets.UTF_8));
        curW=new PrintWriter(new OutputStreamWriter(new FileOutputStream(OUT_DIR+"/curves.csv"),StandardCharsets.UTF_8));
        sumW.println("factor,level,seed,N,nFire,smokeMode,wD,wF,wS,delta,steps,endTime,escaped,deadF,deadS,active,evacRate,meanEvacTime,t95Evac");
        curW.println("factor,level,seed,t,cumEscaped,dead");
        int REP=30; long t0=System.currentTimeMillis();
        for(int rep=0;rep<REP;rep++){
            long seed=1000L+rep*37L; boolean curve=true;
            // 1 火源数量
            emit("firecount","1",seed,120,1,2,.6,.2,.2,.5, run(seed,120,FC1,.6,.2,.2,2,W,H,D,.5,false),false);
            emit("firecount","3",seed,120,3,2,.6,.2,.2,.5, run(seed,120,FC3,.6,.2,.2,2,W,H,D,.5,false),false);
            emit("firecount","5",seed,120,5,2,.6,.2,.2,.5, run(seed,120,FC5,.6,.2,.2,2,W,H,D,.5,false),false);
            // 2 人员密度
            emit("density","60",seed,60,3,2,.6,.2,.2,.5, run(seed,60,FC3,.6,.2,.2,2,W,H,D,.5,false),false);
            emit("density","120",seed,120,3,2,.6,.2,.2,.5, run(seed,120,FC3,.6,.2,.2,2,W,H,D,.5,false),false);
            emit("density","180",seed,180,3,2,.6,.2,.2,.5, run(seed,180,FC3,.6,.2,.2,2,W,H,D,.5,false),false);
            // 3 风险权重
            emit("risk","seeking",seed,120,3,2,.9,.05,.05,.5, run(seed,120,FC3,.9,.05,.05,2,W,H,D,.5,curve),curve);
            emit("risk","neutral",seed,120,3,2,.6,.2,.2,.5, run(seed,120,FC3,.6,.2,.2,2,W,H,D,.5,curve),curve);
            emit("risk","averse",seed,120,3,2,.3,.35,.35,.5, run(seed,120,FC3,.3,.35,.35,2,W,H,D,.5,curve),curve);
            // 4 烟气效应
            emit("smoke","off",seed,120,3,0,.6,.2,.2,.5, run(seed,120,FC3,.6,.2,.2,0,W,H,D,.5,curve),curve);
            emit("smoke","vis",seed,120,3,1,.6,.2,.2,.5, run(seed,120,FC3,.6,.2,.2,1,W,H,D,.5,curve),curve);
            emit("smoke","full",seed,120,3,2,.6,.2,.2,.5, run(seed,120,FC3,.6,.2,.2,2,W,H,D,.5,curve),curve);
            // 5 火源位置(单火源 远/中/近出口)
            int[] far={0},cen={2},near={4};
            emit("firepos","far",seed,120,1,2,.6,.2,.2,.5, run(seed,120,far,.6,.2,.2,2,W,H,D,.5,false),false);
            emit("firepos","center",seed,120,1,2,.6,.2,.2,.5, run(seed,120,cen,.6,.2,.2,2,W,H,D,.5,false),false);
            emit("firepos","near",seed,120,1,2,.6,.2,.2,.5, run(seed,120,near,.6,.2,.2,2,W,H,D,.5,false),false);
            // 6 网格收敛 Δ=0.5/0.4/0.25/0.2 (同一物理24x15x3m)
            emit("grid","0.50",seed,120,3,2,.6,.2,.2,.50, run(seed,120,FC3,.6,.2,.2,2,48,30,6,.50,false),false);
            emit("grid","0.30",seed,120,3,2,.6,.2,.2,.30, run(seed,120,FC3,.6,.2,.2,2,80,50,10,.30,false),false);
            emit("grid","0.25",seed,120,3,2,.6,.2,.2,.25, run(seed,120,FC3,.6,.2,.2,2,96,60,12,.25,false),false);
            emit("grid","0.20",seed,120,3,2,.6,.2,.2,.20, run(seed,120,FC3,.6,.2,.2,2,120,75,15,.20,false),false);
            if(rep%5==4)System.out.printf("rep %d/%d %.1fs%n",rep+1,REP,(System.currentTimeMillis()-t0)/1000.0);
        }
        sumW.close();curW.close();
        System.out.printf("ALL DONE %.1fs%n",(System.currentTimeMillis()-t0)/1000.0);
    }
}
