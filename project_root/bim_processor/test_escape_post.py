import urllib.request, json, time

def post_cmd(cmd):
    data = ('cmd=' + cmd).encode()
    req = urllib.request.Request('http://localhost:8080/command', data=data, method='POST')
    urllib.request.urlopen(req)

post_cmd('reset')
time.sleep(0.5)
post_cmd('noFire')
time.sleep(0.5)
post_cmd('start')
print('模拟启动（POST）')

for i in range(30):
    time.sleep(1)
    r = urllib.request.urlopen('http://localhost:8080/state')
    d = json.loads(r.read())
    escaped = d.get('escapedCount', 0)
    ct = d.get('currentTime', 0)
    if i % 5 == 0 or escaped > 0:
        print(f'  {i}s: time={ct:.1f}, 逃出={escaped}')
    if escaped > 0 and d.get('activePersonCount', 0) == 0:
        break

print(f'最终: 逃出={escaped}')
