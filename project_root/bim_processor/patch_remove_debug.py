with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        if (System.currentTimeMillis() % 1000 < 100) System.out.println("[Person step] pos=(" + x + "," + y + "," + z + ") exitDist=" + currentDist + " cooldown=" + moveCooldown + " alive=" + alive + " escaped=" + escaped);"""

new = """        // 调试输出已移除"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
