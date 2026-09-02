with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """    public int[][][] exitDistanceField; // 预计算：每个格子到最近出口的最短路径距离（考虑楼梯约束）"""

new = """    public int[][][] exitDistanceField; // 预计算：每个格子到最近出口的最短路径距离（考虑楼梯约束）
    private boolean distanceFieldComputed = false; // 距离场是否已计算（体素数据不变时不重复计算）"""

content = content.replace(old, new)

old2 = """    public void computeExitDistanceField() {
        if (exits.isEmpty()) {
            System.out.println("[寻路] 无出口，跳过距离场计算");
            return;
        }"""

new2 = """    public void computeExitDistanceField() {
        if (distanceFieldComputed && exitDistanceField != null) {
            System.out.println("[寻路] 距离场已计算，跳过（体素数据未变）");
            return;
        }
        if (exits.isEmpty()) {
            System.out.println("[寻路] 无出口，跳过距离场计算");
            return;
        }"""

content = content.replace(old2, new2)

old3 = """        System.out.println("[寻路] 出口距离场计算完成，可达格子: " + reachable + "/" + (w*h*d));
    }"""

new3 = """        distanceFieldComputed = true;
        System.out.println("[寻路] 出口距离场计算完成，可达格子: " + reachable + "/" + (w*h*d));
    }"""

content = content.replace(old3, new3)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
