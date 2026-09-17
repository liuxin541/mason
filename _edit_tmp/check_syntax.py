# -*- coding: utf-8 -*-
"""提取 fireescape.html 中所有 <script> 内联 JS，逐段做语法校验"""
import io, re, subprocess, sys, os

PATH = r'E:\xuexi\MASON\mason\fireescape.html'
with io.open(PATH, 'r', encoding='utf-8') as f:
    content = f.read()

# 找出所有内联 <script>...</script>（非 src 引用）
scripts = re.findall(r'<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>', content, re.S | re.I)
print(f'内联 script 块数量: {len(scripts)}')

ok = True
for i, js in enumerate(scripts):
    tmp = os.path.join(r'E:\xuexi\MASON\mason\_edit_tmp', f'check_{i}.js')
    with io.open(tmp, 'w', encoding='utf-8', newline='\n') as f:
        f.write(js)
    r = subprocess.run(['node', '--check', tmp], capture_output=True, text=True)
    if r.returncode != 0:
        ok = False
        print(f'[SYNTAX ERROR] script #{i}:')
        print(r.stderr[:2000])
    else:
        print(f'[OK] script #{i} 语法通过')
    os.remove(tmp)

print('语法检查结果:', '全部通过' if ok else '存在错误')
sys.exit(0 if ok else 1)
