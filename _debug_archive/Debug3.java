package sim.app.fireescape;

public class Debug3 {
    public static void main(String[] a){
        FireEscape m=new FireEscape(7L);
        m.mapMode="simple";m.smokeMode=2;m.wDist=0.6;m.wFire=0.2;m.wSmoke=0.2;m.moveProbability=1.0; // 移除概率干扰
        m.loadSimpleRectRoom(30,20,10,0.5);
        m.fireSourcePositions.clear(); m.initMultipleFireSources();
        System.out.println("EXIT obst(29,10,1)="+m.obstacleGrid.get(29,10,1)+" pass="+m.isPassable(29,10,1));
        System.out.println("inside(29,10,1)="+m.insideGrid.get(29,10,1)+"  (28,10,1) obst="+m.obstacleGrid.get(28,10,1)+" inside="+m.insideGrid.get(28,10,1));
        Person p=new Person(m,28,10,1); m.persons=new Person[]{p};m.personGrid.setObjectLocation(p,28,10,1);
        for(int i=0;i<12;i++){
            p.step(m);
            System.out.println("step"+(i+1)+" pos=("+p.getX()+","+p.getY()+","+p.getZ()+") alive="+p.alive+" esc="+p.escaped+" escT="+p.escapeTime);
            if(p.escaped)break;
        }
    }
}
