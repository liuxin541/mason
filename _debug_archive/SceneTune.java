package sim.app.fireescape;

public class SceneTune {
    static int[] F3=BatchExperiment.FC3, F5=BatchExperiment.FC5;
    static void cfg(String name,int W,int H,int D,int N,int[] fidx,double wD,double wF,double wS,int sm,int seeds){
        int e=0,dF=0,dS=0,act=0; double mt=0,tlast=0; int n=0;
        for(int r=0;r<seeds;r++){ long seed=1000L+r*37L;
            BatchExperiment.Res x=BatchExperiment.run(seed,N,fidx,wD,wF,wS,sm,W,H,D,0.5,false);
            e+=x.escaped;dF+=x.deadF;dS+=x.deadS;act+=x.active; if(!Double.isNaN(x.meanEvac)){mt+=x.meanEvac;tlast+=x.t95;n++;}
        }
        int tot=N*seeds;
        System.out.printf("%-22s 逃=%.1f%% 烟亡=%.1f%% 滞留=%d 均疏散=%.1fs T95=%.1fs%n",
            name,100.0*e/tot,100.0*dS/tot,act,mt/Math.max(1,n),tlast/Math.max(1,n));
    }
    public static void main(String[] a){
        int S=10,W=48,H=30,D=6;
        cfg("密度低60",W,H,D,60,F3,0.6,0.2,0.2,2,S);
        cfg("密度中120",W,H,D,120,F3,0.6,0.2,0.2,2,S);
        cfg("密度高180",W,H,D,180,F3,0.6,0.2,0.2,2,S);
        cfg("冒险 5火",W,H,D,120,F5,0.9,0.05,0.05,2,S);
        cfg("中性 5火",W,H,D,120,F5,0.6,0.2,0.2,2,S);
        cfg("保守 5火",W,H,D,120,F5,0.3,0.35,0.35,2,S);
        cfg("烟关 3火",W,H,D,120,F3,0.6,0.2,0.2,0,S);
        cfg("仅能见 3火",W,H,D,120,F3,0.6,0.2,0.2,1,S);
        cfg("烟全 3火",W,H,D,120,F3,0.6,0.2,0.2,2,S);
        cfg("火源1",W,H,D,120,BatchExperiment.FC1,0.6,0.2,0.2,2,S);
        cfg("火源3",W,H,D,120,F3,0.6,0.2,0.2,2,S);
        cfg("火源5",W,H,D,120,F5,0.6,0.2,0.2,2,S);
    }
}
