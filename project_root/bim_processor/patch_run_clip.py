with open(r'D:\mason\project_root\bim_processor\run.py', 'r', encoding='utf-8') as f:
    content = f.read()

old = """        build_from_elements(grid, elements, origin_shift)
        fix_stair_vertical_connectivity(grid)
        seal_exterior(grid)  # 封闭外部空气，防止人员在楼外生成"""

new = """        build_from_elements(grid, elements, origin_shift)
        fix_stair_vertical_connectivity(grid)
        clip_penetrating_elements(grid)  # 清除穿透楼板的墙和窗，防止人员在屋顶生成
        seal_exterior(grid)  # 封闭外部空气，防止人员在楼外生成"""

content = content.replace(old, new)

with open(r'D:\mason\project_root\bim_processor\run.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
