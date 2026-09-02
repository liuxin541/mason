with open(r'D:\mason\fireescape.html', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        // 1) 收集楼板 slabZ
        if (currentData.floorSlabs && currentData.floorSlabs.length > 0) {
            for (const z of currentData.floorSlabs) slabs.push(Number(z) | 0);
        } else {"""

new = """        // 1) 收集楼板 slabZ（在最前面补 0 作为地面，确保 1F 不被忽略）
        if (currentData.floorSlabs && currentData.floorSlabs.length > 0) {
            for (const z of currentData.floorSlabs) slabs.push(Number(z) | 0);
            if (slabs.length === 0 || slabs[0] > 0) slabs.unshift(0);
        } else {"""

content = content.replace(old, new)

with open(r'D:\mason\fireescape.html', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
