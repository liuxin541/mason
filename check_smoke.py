#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json, urllib.request
with urllib.request.urlopen('http://localhost:8090/state', timeout=10) as r:
    state = json.loads(r.read().decode('utf-8'))
smoke = state['smoke']
print('smoke类型:', type(smoke))
print('smoke长度:', len(smoke))
print('smoke[0]类型:', type(smoke[0]))
print('smoke[0]长度:', len(smoke[0]))
if isinstance(smoke[0], list) and len(smoke[0]) > 0:
    print('smoke[0][0]类型:', type(smoke[0][0]))
    if isinstance(smoke[0][0], list):
        print('smoke[0][0]长度:', len(smoke[0][0]))
        print('smoke[0][0][0]:', smoke[0][0][0])
    else:
        print('smoke[0][0]:', smoke[0][0])
w = state['width']
h = state['height']
d = state['depth']
print(f'width={w}, height={h}, depth={d}')
