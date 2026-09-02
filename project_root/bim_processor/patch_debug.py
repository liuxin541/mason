with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        int bestX = x, bestY = y, bestZ = z;
        double bestScore = Double.NEGATIVE_INFINITY;
        int currentDist = fm.getExitDistance(x, y, z);"""

new = """        int bestX = x, bestY = y, bestZ = z;
        double bestScore = Double.NEGATIVE_INFINITY;
        int currentDist = fm.getExitDistance(x, y, z);
        if (id < 3) System.out.println("[Person " + id + "] pos=(" + x + "," + y + "," + z + ") exitDist=" + currentDist + " onStair=" + (fm.obstacleGrid.get(x,y,z) == FireEscape.TYPE_STAIR));"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
