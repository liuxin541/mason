import urllib.request, json
import numpy as np

# 读取体素数据
with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

d = json.loads(urllib.request.urlopen('http://localhost:8080/state').read())
print('存活:', d.get('active'))
print('逃出:', d.get('escaped'))
persons = d.get('persons', [])
print('人员数:', len(persons))

outside = 0
roof = 0
normal = 0
for i, p in enumerate(persons):
    x, y, z = int(p[0]), int(p[1]), int(p[2])
    # 检查该位置是否是空气（可通行）
    if 0 <= z < 36 and 0 <= y < 38 and 0 <= x < 80:
        voxel = data[z, y, x]
        is_passable = voxel in (0, 3, 4, 5)  # 空气/门/出口/楼梯
    else:
        is_passable = False

    # 屋顶: z > 28
    if z > 28:
        roof += 1
        print(f'  [屋顶] 人员{i}: x={x}, y={y}, z={z}, 体素={voxel}')
    elif not is_passable:
        outside += 1
        print(f'  [非通行区] 人员{i}: x={x}, y={y}, z={z}, 体素={voxel}')
    else:
        normal += 1

print(f'\n屋顶人员: {roof}')
print(f'非通行区人员: {outside}')
print(f'正常人员: {normal}')
