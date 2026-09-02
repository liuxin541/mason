with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscapeHttpServer.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = '        sb.append("\\"depth\\":").append(model.gridDepth).append(",");\n        sb.append("\\"floorSlabs\\":[0,17,34,51],");'

new = '''        sb.append("\\"depth\\":").append(model.gridDepth).append(",");
        // 从语义信息读取真实楼板层位置，不再硬编码
        sb.append("\\"floorSlabs\\":[");
        for (int i = 0; i < model.floorSlabs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(model.floorSlabs.get(i));
        }
        sb.append("],");'''

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscapeHttpServer.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
