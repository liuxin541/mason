package sim.app.fireescape;

import sim.engine.*;

public class Debug2 {
    public static void main(String[] a){
        int[][] pool={{4,5},{4,15},{14,10},{20,16},{25,6}};
        int[][] f3={pool[0],pool[2],pool[4]};
        FireEscape m=new FireEscape(1000L);
        m.mapMode="simple";m.personCount=100;m.smokeMode=2;m.wDist=0.6;m.wFire=0.2;m.wSmoke=0.2;
        m.loadSimpleRectRoom(30,20,10,0.5);
        m.fireSourcePositions.clear(); for(int[]q:f3)m.fireSourcePositions.add(new int[]{q[0],q[1],1});
        m.initMultipleFireSources();
        m.persons=new Person[100]; Steppable[] ag=new Steppable[100];
        for(int i=0;i<100;i++){int x,y,z=1,att=0;do{x=1+m.random.nextInt(28);y=1+m.random.nextInt(18);att++;if(att>2000)break;}
            while(!(m.insideGrid.get(x,y,z)==1&&m.obstacleGrid.get(x,y,z)==0)||m.fireGrid.get(x,y,z)>FireEscape.T_AMBIENT+50);
            m.persons[i]=new Person(m,x,y,z);m.personGrid.setObjectLocation(m.persons[i],x,y,z);ag[i]=m.persons[i];}
        m.schedule.scheduleRepeating(new RandomSequence(ag));
        m.schedule.scheduleRepeating(Schedule.EPOCH,1,new FireSpread(),1);
        for(int s=1;s<=800;s++){
            m.schedule.step(m);
            if(s==100||s==200||s==400||s==800){
                System.out.printf("step%4d t=%6.1f active=%d esc=%d dF=%d dS=%d | exit(29,10)fire=%.0f smoke=%.2f | inner(28,10)fire=%.0f sm=%.2f | (27,10)fire=%.0f%n",
                    s,m.currentTime,m.getActivePersonCount(),m.getEscapedCount(),m.getDeadByFire(),m.getDeadBySmoke(),
                    m.fireGrid.get(29,10,1),m.getSmokeAt(29,10,1),m.fireGrid.get(28,10,1),m.getSmokeAt(28,10,1),m.fireGrid.get(27,10,1));
                int[] hist=new int[30]; for(Person p:m.persons)if(p.alive&&!p.escaped)hist[p.getX()]++;
                StringBuilder sb=new StringBuilder(" x分布(alive):"); for(int xx=20;xx<30;xx++)sb.append(" x").append(xx).append("=").append(hist[xx]); System.out.println(sb);
            }
        }
    }
}
