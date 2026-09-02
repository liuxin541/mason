import numpy as np
with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

# 检查 z=12 层 x=5, y=4 周围
print('z=12 层 x=3~8, y=2~7:')
for y in range(2, 8):
    row = []
    for x in range(3, 9):
        t = data[12, y, x]
        name = {0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t, '?')
        row.append(name)
    print(f'  y={y}: {" ".join(row)}')

# 检查 z=11~13 层 x=5, y=4 垂直方向
print('\nz=10~14 层 x=5, y=4:')
for z in range(10, 15):
    t = data[z, 4, 5]
    name = {0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t, '?')
    print(f'  z={z}: {name}')

# 检查 F2 层西北角是否有缺口
print('\nz=12 层 x=0~15, y=0~10 空气分布:')
for y in range(0, 11):
    row = []
    for x in range(0, 16):
        t = data[12, y, x]
        name = {0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t, '?')
        row.append(name)
    print(f'  y={y}: {" ".join(row)}')
