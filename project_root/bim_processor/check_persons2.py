import urllib.request, json
d = json.loads(urllib.request.urlopen('http://localhost:8080/state').read())
print('存活:', d.get('active'))
print('逃出:', d.get('escaped'))
persons = d.get('persons', [])
print('人员数:', len(persons))
if persons:
    print('第一个人员数据:', persons[0])
    print('类型:', type(persons[0]))
