import urllib.request, json, time

def post_cmd(cmd):
    data = ('cmd=' + cmd).encode()
    req = urllib.request.Request('http://localhost:8080/command', data=data, method='POST')
    try:
        urllib.request.urlopen(req, timeout=3)
    except:
        pass

post_cmd('reset')
time.sleep(2)
post_cmd('start')
print('模拟启动（无火模式）')

for i in range(120):
    time.sleep(1)
    r = urllib.request.urlopen('http://localhost:8080/state', timeout=5)
    d = json.loads(r.read())
    escaped = d.get('escaped', 0)
    active = d.get('active', 0)
    if i % 10 == 0:
        print(f'  {i}s: 逃出={escaped}, 存活={active}')
    if active == 0:
        break

print(f'最终: 逃出={escaped}, 存活={active}, 逃出率={escaped/50*100:.0f}%')
