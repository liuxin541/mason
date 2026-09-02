import numpy as np
with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

# 检查 z=25~31 层的楼梯分布
print('z=25~31 层楼梯数量:')
for z in range(25, 32):
    stairs = np.sum(data[z] == 5)
    air = np.sum(data[z] == 0)
    print(f'  z={z}: 楼梯={stairs}, 空气={air}')

# 检查人员位置 x=62,y=15 和 x=29,y=14 的垂直剖面
print('\nx=62, y=15 垂直剖面 (z=25~31):')
for z in range(25, 32):
    t = data[z, 15, 62]
    name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
    print(f'  z={z}: {name}')

print('\nx=29, y=14 垂直剖面 (z=25~31):')
for z in range(25, 32):
    t = data[z, 14, 29]
    name = {0:'空气',1:'墙',3:'门',4:'出口',5:'楼梯',7:'窗'}.get(t, f'类型{t}')
    print(f'  z={z}: {name}')

# 检查所有楼梯的 z 范围
all_stairs = np.argwhere(data == 5)
if len(all_stairs) > 0:
    zs = [s[0] for s in all_stairs]
    print(f'\n所有楼梯 z 范围: {min(zs)}~{max(zs)}')
    print(f'z=29 楼梯位置: {[(s[1], s[2]) for s in all_stairs if s[0]==29][:10]}')
