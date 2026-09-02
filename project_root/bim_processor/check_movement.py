import urllib.request, json, time

r1 = urllib.request.urlopen('http://localhost:8080/state')
d1 = json.loads(r1.read())
p1 = set((p[0],p[1],p[2]) for p in d1.get('persons', []))
time.sleep(3)
r2 = urllib.request.urlopen('http://localhost:8080/state')
d2 = json.loads(r2.read())
p2 = set((p[0],p[1],p[2]) for p in d2.get('persons', []))
moved = len(p1 - p2)
print('3秒内移动的人员数:', moved, '/', len(p1))
print('逃出:', d2.get('escapedCount', 0))
print('存活:', d2.get('activePersonCount', 0))
# 显示前5个人员的位置变化
persons1 = d1.get('persons', [])
persons2 = d2.get('persons', [])
for i in range(min(5, len(persons1))):
    print(f'  人员{i}: {persons1[i]} -> {persons2[i]}')
