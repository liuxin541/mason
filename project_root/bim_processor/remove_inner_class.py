with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 找到静态内部类 SemanticInfo 的起始行
start_idx = None
for i, line in enumerate(lines):
    if '静态内部类' in line and 'SemanticInfo' in line:
        start_idx = i - 1  # 包含上面的分隔线
        break

if start_idx is not None:
    # 删除从 start_idx 到倒数第二行（保留最后的 }）
    # 文件最后一行是 }，是类的结束括号
    new_lines = lines[:start_idx] + ['}\n']
    with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscape.java', 'w', encoding='utf-8') as f:
        f.writelines(new_lines)
    print(f'Removed inner class from line {start_idx}')
else:
    print('Inner class not found')
