import urllib.request, json
d = json.loads(urllib.request.urlopen('http://localhost:8080/state').read())
print('存活:', d.get('active'))
print('逃出:', d.get('escaped'))
persons = d.get('persons', [])
print('人员数:', len(persons))

outside = 0
roof = 0
normal = 0
for i, p in enumerate(persons):
    x, y, z = p[0], p[1], p[2]
    # 屋顶: z > 28 (F4活动层 z=25~28, 楼板 z=29,30)
    if z > 28:
        roof += 1
        print(f'  [屋顶] 人员{i}: x={x}, y={y}, z={z}')
    # 楼外: 建筑范围约 x=10~70, y=5~30
    elif x < 8 or x > 72 or y < 3 or y > 32:
        outside += 1
        print(f'  [楼外] 人员{i}: x={x}, y={y}, z={z}')
    else:
        normal += 1

print(f'\n楼外人员: {outside}')
print(f'屋顶人员: {roof}')
print(f'正常人员: {normal}')
