import re

with open(r'D:\mason\project_root\bim_processor\run.py', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 确保导入 numpy
if 'import numpy' not in content:
    content = content.replace('import math', 'import math\nimport numpy as np')

# 2. 在 load_ifc 后添加楼层提取
old1 = '        ifc_file = load_ifc(args.input)'
new1 = """        ifc_file = load_ifc(args.input)
        # 提取楼层信息（IfcBuildingStorey）
        storeys = ifc_file.by_type('IfcBuildingStorey')
        floor_count = len(storeys)
        floor_elevations = []
        for s in storeys:
            elev = getattr(s, 'Elevation', None)
            if elev is not None:
                # IFC 标高单位可能是毫米，转为米
                floor_elevations.append(float(elev) / 1000.0 if abs(elev) > 100 else float(elev))
            else:
                floor_elevations.append(0.0)
        logger.log(f'[INFO] 识别到 {floor_count} 个楼层')"""
content = content.replace(old1, new1)

# 3. 修改 semantic_info
old2 = '        semantic_info = {\n            "floors": 1,\n            "stair_positions": [],'
new2 = """        # 从体素数据提取楼梯位置（去重 x,y 坐标）
        stair_coords = set()
        stair_indices = np.argwhere(grid.data == VoxelGrid.STAIR)
        for z, y, x in stair_indices:
            stair_coords.add((int(x), int(y)))
        stair_positions = [list(p) for p in sorted(stair_coords)]
        logger.log(f'[INFO] 提取到 {len(stair_positions)} 个楼梯水平位置')

        semantic_info = {
            "floors": floor_count,
            "floor_elevations": floor_elevations,
            "stair_positions": stair_positions,"""
content = content.replace(old2, new2)

with open(r'D:\mason\project_root\bim_processor\run.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
