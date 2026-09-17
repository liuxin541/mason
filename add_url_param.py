#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import io

p = r"E:\xuexi\MASON\mason\fireescape.html"
with io.open(p, 'r', encoding='utf-8') as f:
    content = f.read()

old = """    const cm = document.getElementById('currentModel');
    if (cm) cm.textContent = '简单矩形房间';
    fetch('/command', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'cmd=map_simple' });
"""

new = """    const cm = document.getElementById('currentModel');
    if (cm) cm.textContent = '简单矩形房间';
    fetch('/command', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'cmd=map_simple' });
    // ★ URL参数支持: ?model=building 直接进入BIM模式 (供headless截图/快捷访问)
    try {
        const _urlModel = new URLSearchParams(window.location.search).get('model');
        if (_urlModel === 'building' && ms) {
            ms.value = 'building';
            ms.dispatchEvent(new Event('change'));
            addLog('🏢 URL参数: 已自动切换到BIM大楼模型', 'info');
        }
    } catch (e) { /* 静默 */ }
"""

if old in content:
    content = content.replace(old, new)
    with io.open(p, 'w', encoding='utf-8') as f:
        f.write(content)
    print('URL参数支持已添加')
else:
    print('未找到目标文本!')
    # 打印上下文帮助调试
    idx = content.find('cmd=map_simple')
    print('上下文:', repr(content[idx-100:idx+100]))
