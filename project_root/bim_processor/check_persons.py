import urllib.request, json
d = json.loads(urllib.request.urlopen('http://localhost:8080/state').read())
print('存活:', d.get('active'))
print('逃出:', d.get('escaped'))
print('烟熏死:', d.get('smokeDeaths', d.get('smoke_deaths', 0)))
persons = d.get('persons', [])
print('人员数:', len(persons))

# 检查是否有人员在建筑外部或屋顶
# 建筑范围: x=0~80, y=0~38, 活动楼层 z=0~28
outside_count = 0
roof_count = 0
for i, p in enumerate(persons):
    x = p.get('x', 0)
    y = p.get('y', 0)
    z = p.get('z', 0)
    state = p.get('state', '')
    if state == 'escaped':
        continue
    # 检查是否在屋顶上方 (z > 28)
    if z > 28:
        roof_count += 1
        print(f'  [屋顶] 人员{i}: x={x:.1f}, y={y:.1f}, z={z:.1f}, floor={p.get("floor")}')
    # 检查是否在建筑外部 (x<5 or x>75 or y<5 or y>33)
    if x < 5 or x > 75 or y < 5 or y > 33:
        outside_count += 1
        print(f'  [楼外] 人员{i}: x={x:.1f}, y={y:.1f}, z={z:.1f}, floor={p.get("floor")}')

print(f'\n楼外人员: {outside_count}')
print(f'屋顶人员: {roof_count}')
print(f'正常人员: {len(persons) - outside_count - roof_count - sum(1 for p in persons if p.get("state")=="escaped")}')
