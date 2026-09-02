with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """    public List<int[]> exits = new ArrayList<>();
    public List<Integer> floorSlabs = new ArrayList<>();"""

new = """    public List<int[]> exits = new ArrayList<>();
    public List<Integer> floorSlabs = new ArrayList<>();
    public int[][][] exitDistanceField; // 预计算：每个格子到最近出口的最短路径距离（考虑楼梯约束）"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
