package sim.app.fireescape;

import sim.engine.*;

public class DebugStep {
    public static void main(String[] a){
        FireEscape m=new FireEscape(1L);
        m.mapMode="simple"; m.personCount=1; m.smokeMode=2; m.wDist=0.6;m.wFire=0.2;m.wSmoke=0.2;
        m.loadSimpleRectRoom(30,20,10,0.5);
        m.fireSourcePositions.clear();
        m.fireSourcePositions.add(new int[]{13,10,1});
        m.initMultipleFireSources();
        System.out.println("grids: fire="+(m.fireGrid!=null)+" smoke="+(m.smokeGrid!=null)+" person="+(m.personGrid!=null));
        System.out.println("ground z ["+m.groundFloorMinZ+","+m.groundFloorMaxZ+"] exits="+m.exits.size()+" exit@"+m.exits.get(0)[0]+","+m.exits.get(0)[1]);
        int sx=5,sy=10,sz=1;
        System.out.println("start cell inside="+m.insideGrid.get(sx,sy,sz)+" obst="+m.obstacleGrid.get(sx,sy,sz)+" passable="+m.isPassable(sx,sy,sz));
        // 邻居passable
        for(int dx=-1;dx<=1;dx++){int nx=sx+dx; System.out.println("  neigh dx="+dx+" x="+nx+" inside="+m.insideGrid.get(nx,sy,sz)+" obst="+m.obstacleGrid.get(nx,sy,sz)+" pass="+m.isPassable(nx,sy,sz));}
        Person p=new Person(m,sx,sy,sz);
        m.persons=new Person[]{p}; m.personGrid.setObjectLocation(p,sx,sy,sz);
        // 直接手动step（绕过schedule）
        for(int i=0;i<30;i++){
            p.step(m);
            if(i%5==4) System.out.println("manual step "+(i+1)+" pos=("+p.getX()+","+p.getY()+","+p.getZ()+") alive="+p.alive+" esc="+p.escaped);
        }
        // 通过schedule
        FireEscape m2=new FireEscape(1L);
        m2.mapMode="simple";m2.smokeMode=2;m2.wDist=0.6;m2.wFire=0.2;m2.wSmoke=0.2;
        m2.loadSimpleRectRoom(30,20,10,0.5);m2.fireSourcePositions.clear();m2.fireSourcePositions.add(new int[]{13,10,1});m2.initMultipleFireSources();
        Person q=new Person(m2,5,10,1); m2.persons=new Person[]{q};m2.personGrid.setObjectLocation(q,5,10,1);
        Steppable[] ag={q};
        m2.schedule.scheduleRepeating(new RandomSequence(ag));
        m2.schedule.scheduleRepeating(Schedule.EPOCH,1,new FireSpread(),1);
        for(int i=0;i<60;i++){m2.schedule.step(m2); if(i%10==9)System.out.println("sched step "+(i+1)+" schedSteps="+m2.schedule.getSteps()+" pos=("+q.getX()+","+q.getY()+","+q.getZ()+") esc="+q.escaped+" t="+m2.currentTime);}
    }
}
