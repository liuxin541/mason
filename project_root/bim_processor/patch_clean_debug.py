with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 移除 step 开头的调试输出
old1 = """        if (System.currentTimeMillis() % 2000 < 100)
            System.out.println("[Person step] showPersons=" + fm.showPersons + " alive=" + alive + " escaped=" + escaped + " pos=(" + x + "," + y + "," + z + ")");
        if (!fm.showPersons) return;"""
new1 = """        if (!fm.showPersons) return;"""
content = content.replace(old1, new1)

# 移除距离场调试输出
old2 = """        int currentDist = fm.getExitDistance(x, y, z);
        if (System.currentTimeMillis() % 3000 < 100)
            System.out.println("[Person] pos=(" + x + "," + y + "," + z + ") dist=" + currentDist + " onStair=" + (fm.obstacleGrid.get(x,y,z) == FireEscape.TYPE_STAIR));"""
new2 = """        int currentDist = fm.getExitDistance(x, y, z);"""
content = content.replace(old2, new2)

with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
