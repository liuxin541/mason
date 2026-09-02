with open('run.py', 'r', encoding='utf-8') as f:
    content = f.read()

old = '            "floors": floor_count,'
new = '            "floors": len(floor_slabs) + 1,  # 活动楼层数 = 楼板数 + 1'
content = content.replace(old, new)

with open('run.py', 'w', encoding='utf-8') as f:
    f.write(content)
print('修改完成')
