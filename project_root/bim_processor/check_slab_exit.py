import numpy as np
with open(r'D:\mason\ifc_output\voxel_grid.voxel', 'rb') as f:
    f.read(32)  # 32字节文件头
    data = np.frombuffer(f.read(), dtype=np.uint8).reshape(36, 38, 80)

print('z=10 出口(x=74~75,y=14)周围:')
for z in [9, 10, 11]:
    print(f'  z={z}:')
    for y in range(12, 17):
        row = []
        for x in range(72, 78):
            t = data[z, y, x]
            name = {0:'.',1:'#',3:'D',4:'E',5:'S',7:'W'}.get(t, '?')
            row.append(name)
        print(f'    y={y}: {" ".join(row)}')
