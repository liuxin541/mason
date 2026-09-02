with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """    public boolean isPassable(int x, int y, int z) {
        if (!isInsideBuilding(x, y, z)) return false;
        int v = obstacleGrid.get(x, y, z);
        return v == TYPE_EMPTY || v == TYPE_DOOR || v == TYPE_EXIT || v == TYPE_STAIR;
    }"""

new = """    public boolean isPassable(int x, int y, int z) {
        if (x < 0 || x >= gridWidth || y < 0 || y >= gridHeight || z < 0 || z >= gridDepth) return false;
        int v = obstacleGrid.get(x, y, z);
        // 楼梯的 insideGrid=0（VoxelLoader 把墙和楼梯都设为非内部），但楼梯必须可通行
        if (v == TYPE_STAIR) return true;
        if (!isInsideBuilding(x, y, z)) return false;
        return v == TYPE_EMPTY || v == TYPE_DOOR || v == TYPE_EXIT;
    }"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
