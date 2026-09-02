import urllib.request, json, time

def post_cmd(cmd):
    data = ('cmd=' + cmd).encode()
    req = urllib.request.Request('http://localhost:8080/command', data=data, method='POST')
    urllib.request.urlopen(req, timeout=5)

print('发送 reset...')
post_cmd('reset')
time.sleep(1)
print('发送 noFire...')
post_cmd('noFire')
time.sleep(1)
print('发送 start...')
post_cmd('start')
time.sleep(3)

r = urllib.request.urlopen('http://localhost:8080/state', timeout=5)
d = json.loads(r.read())
print('currentTime:', d.get('currentTime', 0))
print('escaped:', d.get('escapedCount', 0))
print('persons:', len(d.get('persons', [])))
