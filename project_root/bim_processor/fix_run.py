with open(r'D:\mason\project_root\bim_processor\run.py', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 添加导入
old_import = 'seal_isolated_air'
new_import = 'seal_isolated_air, post_process_cleanup'
content = content.replace(old_import, new_import, 1)  # 只替换第一个（导入行）

# 2. 添加调用
old_call = '        fix_room_connectivity(grid)  # 打通小房间，修复连通性'
new_call = '''        fix_room_connectivity(grid)  # 打通小房间，修复连通性
        post_process_cleanup(grid)  # 后处理：清除楼板层孤立门，验证出口连通性'''
content = content.replace(old_call, new_call)

with open(r'D:\mason\project_root\bim_processor\run.py', 'w', encoding='utf-8') as f:
    f.write(content)

# 验证
with open(r'D:\mason\project_root\bim_processor\run.py', 'r', encoding='utf-8') as f:
    c = f.read()
print('post_process_cleanup in import:', 'post_process_cleanup' in c.split('\n')[14])
print('post_process_cleanup in call:', 'post_process_cleanup(grid)' in c)
