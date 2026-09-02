import numpy as np
with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

print('z=29~35 各层类型统计:')
for z in range(29, 36):
    layer = data[z]
    air = int(np.sum(layer == 0))
    wall = int(np.sum(layer == 1))
    door = int(np.sum(layer == 3))
    exit_ = int(np.sum(layer == 4))
    stair = int(np.sum(layer == 5))
    ratio = np.sum(layer != 0) / (38*80)
    print(f'  z={z}: 空气={air}, 墙={wall}, 门={door}, 出口={exit_}, 楼梯={stair}, 非空气占比={ratio*100:.1f}%')

# z=30 门的位置
print('\nz=30 门的位置:')
doors = np.argwhere(data[30] == 3)
for y, x in doors[:10]:
    print(f'  x={x}, y={y}')
