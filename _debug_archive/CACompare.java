package sim.app.fireescape;

import sim.engine.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 7.2.6 与经典元胞疏散模型的横向对比（同工况、配对种子）。
 * staticCA : 无火源、无烟气、纯距离驱动 —— 退化为静态地板场CA（经典CA基线）
 * naiveDist: 3火源+完整烟气，但 wFire=wSmoke=0 —— 最短路径、不规避火烟
 * dynamic  : 3火源+完整烟气、中性权重(0.6,0.2,0.2) —— 本文动态火烟代价场
 * 指标：疏散率、烟亡、幸存者平均疏散时间/T95、幸存者平均FED暴露剂量、出口流量峰值(人/s)
 */
public class CACompare {
    static final String OUT = "E:/xuexi/MASON/_exp_work/results/ca_compare.csv";
    static final double[][] POOL = {{0.12,0.25},{0.12,0.75},{0.45,0.50},{0.68,0.78},{0.82,0.30}};
    static final int[] FC3={0,2,4};
    static final int W=48,H=30,D=6, EVERY=4;   // 每4步=2s采样
    static int[] toGrid(double rx,double ry){
        return new int[]{Math.max(1,Math.min(W-2,(int)Math.round(rx*W))),
                         Math.max(1,Math.min(H-2,(int)Math.round(ry*H))),1};
    }
    static class R{int escaped,deadS; double meanEvac,t95,meanFED,flowPeak;}

    static R run(long seed,int N,int nf,double wD,double wF,double wS,int sm){
        FireEscape m=new FireEscape(seed);
        m.mapMode="simple"; m.personCount=N;
        m.wDist=wD;m.wFire=wF;m.wSmoke=wS;m.smokeMode=sm;m.occupantExclusion=true;
        m.loadSimpleRectRoom(W,H,D,0.5);
        m.fireSourcePositions.clear();
        int[] idx = nf==3?FC3:new int[]{};
        for(int i:idx) m.fireSourcePositions.add(toGrid(POOL[i][0],POOL[i][1]));
        m.initMultipleFireSources(); m.initExits();
        m.deadByFire=0;m.deadBySmoke=0;m.currentTime=0;
        m.persons=new Person[N]; Steppable[] ag=new Steppable[N];
        for(int i=0;i<N;i++){
            int x,y,z=1,a=0;
            do{x=1+m.random.nextInt(W-2);y=1+m.random.nextInt(H-2);a++;if(a>3000)break;}
            while(!(m.insideGrid.get(x,y,z)==1&&m.obstacleGrid.get(x,y,z)==0)
                  ||m.fireGrid.get(x,y,z)>FireEscape.T_AMBIENT+50);
            m.persons[i]=new Person(m,x,y,z);
            m.personGrid.setObjectLocation(m.persons[i],x,y,z); ag[i]=m.persons[i];
        }
        m.schedule.clear();
        m.schedule.scheduleRepeating(new RandomSequence(ag));
        m.schedule.scheduleRepeating(Schedule.EPOCH,1,new FireSpread(),1);
        int max=1200,step=0,prev=0; double flowPeak=0;
        while(step<max){
            m.schedule.step(m); step++;
            if(step%EVERY==0){int e=m.getEscapedCount();double rate=(e-prev)/(EVERY*0.5);if(rate>flowPeak)flowPeak=rate;prev=e;}
            if(m.getActivePersonCount()==0)break;
        }
        R r=new R();
        r.escaped=m.getEscapedCount(); r.deadS=m.getDeadBySmoke();
        double[] et=new double[r.escaped]; int k=0; double fedS=0; int fedN=0;
        for(Person p:m.persons) if(p!=null&&p.escaped&&p.escapeTime>=0){
            et[k++]=p.escapeTime; fedS+=p.getSmokeExposure(); fedN++;
        }
        Arrays.sort(et); double s=0; for(double v:et)s+=v;
        r.meanEvac=et.length>0?s/et.length:Double.NaN;
        r.t95=et.length>0?et[Math.max(0,(int)Math.ceil(0.95*et.length)-1)]:Double.NaN;
        r.meanFED=fedN>0?fedS/fedN:0.0; r.flowPeak=flowPeak;
        m.finish(); return r;
    }

    static void emit(PrintWriter w,String g,int N,long seed,R r){
        w.printf(Locale.US,"%s,%d,%d,%d,%d,%d,%.4f,%.3f,%.3f,%.4f,%.3f%n",
            g,N,seed,N,r.escaped,r.deadS,r.escaped/(double)N,
            Double.isNaN(r.meanEvac)?-1:r.meanEvac,
            Double.isNaN(r.t95)?-1:r.t95,r.meanFED,r.flowPeak);
        w.flush();
    }

    public static void main(String[] a)throws IOException{
        PrintWriter w=new PrintWriter(new OutputStreamWriter(new FileOutputStream(OUT),StandardCharsets.UTF_8));
        w.println("group,density,seed,N,escaped,deadS,evacRate,meanEvac,t95,meanSurvFED,flowPeak");
        int REP=30; long t0=System.currentTimeMillis();
        for(int rep=0;rep<REP;rep++){
            long seed=1000L+rep*37L;
            for(int N:new int[]{60,120,180}){
                R s=run(seed,N,0,1,0,0,0);
                R v=run(seed,N,3,1,0,0,2);
                R d=run(seed,N,3,0.6,0.2,0.2,2);
                emit(w,"staticCA",N,seed,s);
                emit(w,"naiveDist",N,seed,v);
                emit(w,"dynamic",N,seed,d);
            }
            if(rep%5==4)System.out.printf("rep %d/%d %.1fs%n",rep+1,REP,(System.currentTimeMillis()-t0)/1000.0);
        }
        w.close();
        System.out.printf("CACompare DONE %.1fs%n",(System.currentTimeMillis()-t0)/1000.0);
    }
}
