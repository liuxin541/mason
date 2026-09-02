with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscapeHttpServer.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        Steppable[] agents = new Steppable[model.personCount];
        for (int i=0; i<model.personCount; i++) {
            int x,y,z;
            do {
                x = model.random.nextInt(model.gridWidth);
                y = model.random.nextInt(model.gridHeight);
                z = model.random.nextInt(model.gridDepth);
            } while (!(model.insideGrid.get(x,y,z)==1 && model.obstacleGrid.get(x,y,z)==0) ||
                    model.fireGrid.get(x,y,z) > FireEscape.T_AMBIENT + 50);
            model.persons[i] = new Person(model, x, y, z);
            model.personGrid.setObjectLocation(model.persons[i], x, y, z);
            agents[i] = model.persons[i];
        }"""

new = """        Steppable[] agents = new Steppable[model.personCount];
        for (int i=0; i<model.personCount; i++) {
            int x=0,y=0,z=0;
            boolean found = false;
            for (int tries=0; tries<10000; tries++) {
                x = model.random.nextInt(model.gridWidth);
                y = model.random.nextInt(model.gridHeight);
                z = model.random.nextInt(model.gridDepth);
                if (model.insideGrid.get(x,y,z)==1 && model.obstacleGrid.get(x,y,z)==0 &&
                        model.fireGrid.get(x,y,z) <= FireEscape.T_AMBIENT + 50) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                // 兜底：找任意可通行位置
                for (int xx=0; xx<model.gridWidth && !found; xx++)
                    for (int yy=0; yy<model.gridHeight && !found; yy++)
                        for (int zz=0; zz<model.gridDepth && !found; zz++)
                            if (model.insideGrid.get(xx,yy,zz)==1 && model.obstacleGrid.get(xx,yy,zz)==0) {
                                x=xx; y=yy; z=zz; found=true; break;
                            }
            }
            model.persons[i] = new Person(model, x, y, z);
            model.personGrid.setObjectLocation(model.persons[i], x, y, z);
            agents[i] = model.persons[i];
        }"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscapeHttpServer.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
