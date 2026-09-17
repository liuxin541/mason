package sim.app.fireescape;

import sim.engine.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** 无人员、纯火烟场，在与 FDS 对应的固定测点记录温度(°C)与烟气标量，用于交叉标定对比。 */
public class FdsCompare {
    public static void main(String[] a) throws Exception{
        int W=48,H=30,D=6; long seed=1000L;
        FireEscape m=new FireEscape(seed);
        m.mapMode="simple"; m.personCount=0; m.smokeMode=2; m.occupantExclusion=false;
        m.loadSimpleRectRoom(W,H,D,0.5);
        m.fireSourcePositions.clear();
        for(int i:BatchExperiment.FC3){int[]g=toGrid(BatchExperiment.POOL[i][0],BatchExperiment.POOL[i][1],W,H);m.fireSourcePositions.add(g);}
        m.initMultipleFireSources(); m.initExits(); m.currentTime=0; m.persons=new Person[0];
        m.schedule.clear();
        m.schedule.scheduleRepeating(Schedule.EPOCH,1,new FireSpread(),1);
        // 测点 (x,y,z) 对应 FDS：mid(6,7.5)->网格; near(10.75); exit(23)
        int[][] pts={{12,15,4},{22,15,4},{46,15,4},{12,15,3},{22,15,3}};
        String[] nm={"mid_2m","near_2m","exit_2m","mid_1p5","near_1p5"};
        PrintWriter w=new PrintWriter(new OutputStreamWriter(new FileOutputStream("E:/xuexi/MASON/_exp_work/fds/java_field.csv"),StandardCharsets.UTF_8));
        w.print("t"); for(String s:nm)w.print(",T_"+s); for(String s:nm)w.print(",S_"+s); w.println();
        int max=600;
        for(int step=0;step<=max;step++){
            if(step%2==0){
                w.printf(LocaleUS(),"%.1f",m.currentTime);
                for(int[]p:pts)w.printf(LocaleUS(),",%.2f",m.fireGrid.get(p[0],p[1],p[2])-273.15);
                for(int[]p:pts)w.printf(LocaleUS(),",%.3f",m.smokeGrid.get(p[0],p[1],p[2]));
                w.println();
            }
            if(step<max)m.schedule.step(m);
        }
        w.close(); m.finish(); System.out.println("java field exported");
    }
    static int[] toGrid(double rx,double ry,int W,int H){
        return new int[]{Math.max(1,Math.min(W-2,(int)Math.round(rx*W))),
                         Math.max(1,Math.min(H-2,(int)Math.round(ry*H))),1};
    }
    static java.util.Locale LocaleUS(){return java.util.Locale.US;}
}
