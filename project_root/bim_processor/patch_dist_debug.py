with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        int currentDist = fm.getExitDistance(x, y, z);
        // 调试输出已移除"""

new = """        int currentDist = fm.getExitDistance(x, y, z);
        if (System.currentTimeMillis() % 3000 < 100)
            System.out.println("[Person] pos=(" + x + "," + y + "," + z + ") dist=" + currentDist + " onStair=" + (fm.obstacleGrid.get(x,y,z) == FireEscape.TYPE_STAIR));"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
