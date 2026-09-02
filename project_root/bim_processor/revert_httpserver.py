with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscapeHttpServer.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 回退人员生成到原来的 do-while 循环
old = """        Steppable[] agents = new Steppable[model.personCount];
        // 预收集所有可达的可通行位置，避免人员生成在不可达区域
        java.util.List<int[]> validPositions = new java.util.ArrayList<>();
        for (int xx=0; xx<model.gridWidth; xx++)
            for (int yy=0; yy<model.gridHeight; yy++)
                for (int zz=0; zz<model.gridDepth; zz++)
                    if (model.isPassable(xx,yy,zz) && model.getExitDistance(xx,yy,zz) < Integer.MAX_VALUE
                            && model.fireGrid.get(xx,yy,zz) <= FireEscape.T_AMBIENT + 50)
                        validPositions.add(new int[]{xx,yy,zz});
        System.out.println("[initModel] 可达生成位置: " + validPositions.size());

        for (int i=0; i<model.personCount; i++) {
            int x=0,y=0,z=0;
            if (!validPositions.isEmpty()) {
                int[] pos = validPositions.get(model.random.nextInt(validPositions.size()));
                x = pos[0]; y = pos[1]; z = pos[2];
            } else {
                // 兜底：找任意可通行位置
                for (int xx=0; xx<model.gridWidth; xx++)
                    for (int yy=0; yy<model.gridHeight; yy++)
                        for (int zz=0; zz<model.gridDepth; zz++)
                            if (model.isPassable(xx,yy,zz)) { x=xx; y=yy; z=zz; break; }
            }
            model.persons[i] = new Person(model, x, y, z);
            model.personGrid.setObjectLocation(model.persons[i], x, y, z);
            agents[i] = model.persons[i];
        }"""

new = """        Steppable[] agents = new Steppable[model.personCount];
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

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscapeHttpServer.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('FireEscapeHttpServer.java reverted')
