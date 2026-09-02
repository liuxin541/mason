with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        distanceFieldComputed = true;
        System.out.println("[寻路] 出口距离场计算完成，可达格子: " + reachable + "/" + (w*h*d));
    }"""

new = """        distanceFieldComputed = true;
        System.out.println("[寻路] 出口距离场计算完成，可达格子: " + reachable + "/" + (w*h*d));
        // 调试：各层可达性
        for (int z = 0; z < d; z++) {
            int layerReach = 0, layerTotal = 0;
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                if (isPassable(x, y, z)) {
                    layerTotal++;
                    if (exitDistanceField[z][y][x] < Integer.MAX_VALUE) layerReach++;
                }
            }
            if (layerTotal > 0)
                System.out.println("  z=" + z + ": " + layerReach + "/" + layerTotal + " (" + (layerReach*100/layerTotal) + "%)");
        }
    }"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
