with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        generateRandomFireSources();
        initMultipleFireSources();
        initExits();

        // 3. 根据最终网格尺寸生成全 3D 随机人员"""

new = """        generateRandomFireSources();
        initMultipleFireSources();
        initExits();
        computeExitDistanceField(); // 预计算出口距离场，优化人员寻路

        // 3. 根据最终网格尺寸生成全 3D 随机人员"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
