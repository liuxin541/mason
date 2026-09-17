# -*- coding: utf-8 -*-
"""fireescape.html 性能修复补丁 2：update3DWorld 墙体缓存 + 体积烟雾参数降载"""
import io, sys

PATH = r'E:\xuexi\MASON\mason\fireescape.html'

with io.open(PATH, 'r', encoding='utf-8') as f:
    content = f.read()

NL = '\r\n' if content.count('\r\n') > content.count('\n') - content.count('\r\n') else '\n'

def sub(old, new, label, must=True):
    global content
    cnt = content.count(old)
    if cnt == 0:
        if must:
            print(f'[FAIL] 未找到: {label}')
            sys.exit(1)
        print(f'[SKIP] 未找到(非必需): {label}')
        return
    if cnt > 1:
        print(f'[FAIL] 匹配 {cnt} 处(应唯一): {label}')
        sys.exit(1)
    content = content.replace(old, new)
    print(f'[OK] {label}')

# ---------- 修复3: update3DWorld 静态墙体缓存 ----------
old3 = (
    "        personGroup = new THREE.Group();" + NL +
    "" + NL +
    "        const matrices = [];" + NL +
    "        const colors = [];" + NL +
    "        const dummy = new THREE.Object3D();" + NL
)
new3 = (
    "        personGroup = new THREE.Group();" + NL +
    "" + NL +
    "        // ★ 性能：墙体（obstacle）为静态数据，网格尺寸/剖面/建筑模式未变化时" + NL +
    "        //   直接复用上次构建结果，避免每次轮询全量扫描体素" + NL +
    "        //   （BIM 大楼 156×73×68 ≈ 77 万体素）重建 InstancedMesh" + NL +
    "        const gridKey = depth + 'x' + w + 'x' + h + '|' + (clipMode ? 1 : 0) + '|' + (buildingGroup ? 1 : 0);" + NL +
    "        if (gridKey === lastWallGridKey) {" + NL +
    "            buildPersonMeshes(data, personGroup);" + NL +
    "            return;" + NL +
    "        }" + NL +
    "        lastWallGridKey = gridKey;" + NL +
    "" + NL +
    "        const matrices = [];" + NL +
    "        const colors = [];" + NL +
    "        const dummy = new THREE.Object3D();" + NL
)
sub(old3, new3, 'update3DWorld 墙体缓存入口')

# ---------- 修复3b: 抽取人员生成函数 + 原位置替换为调用 ----------
old4 = (
    "        // 人员生成" + NL +
    "        if (data.persons && data.persons.length > 0) {" + NL +
    "            const sphereGeo = new THREE.SphereGeometry(dx * 0.35, 8, 8);" + NL +
    "            const sphereMat = new THREE.MeshLambertMaterial({ color: 0xffffff });" + NL +
    "            for (let p of data.persons) {" + NL +
    "                const sphere = new THREE.Mesh(sphereGeo, sphereMat);" + NL +
    "                sphere.position.set(p[0] * dx + dx/2, p[2] * dz + dz/2, p[1] * dx + dx/2);" + NL +
    "                personGroup.add(sphere);" + NL +
    "            }" + NL +
    "            scene.add(personGroup);" + NL +
    "        }" + NL +
    "    }" + NL
)
new4 = (
    "        // 人员生成（抽取为函数，供墙体缓存命中路径复用）" + NL +
    "        buildPersonMeshes(data, personGroup);" + NL +
    "    }" + NL +
    "" + NL +
    "    // 构建人员网格（每个人员一个白色小球）" + NL +
    "    function buildPersonMeshes(data, group) {" + NL +
    "        if (!data.persons || data.persons.length === 0) return;" + NL +
    "        const sphereGeo = new THREE.SphereGeometry(dx * 0.35, 8, 8);" + NL +
    "        const sphereMat = new THREE.MeshLambertMaterial({ color: 0xffffff });" + NL +
    "        for (let p of data.persons) {" + NL +
    "            const sphere = new THREE.Mesh(sphereGeo, sphereMat);" + NL +
    "            sphere.position.set(p[0] * dx + dx/2, p[2] * dz + dz/2, p[1] * dx + dx/2);" + NL +
    "            group.add(sphere);" + NL +
    "        }" + NL +
    "        scene.add(group);" + NL +
    "    }" + NL
)
sub(old4, new4, '人员生成抽取为 buildPersonMeshes')

# ---------- 修复4: 体积烟雾光线步进参数降载 ----------
old5 = (
    "                        uStepSize: { value: 0.15 }," + NL +
    "                        uMaxSteps: { value: 320 }," + NL
)
new5 = (
    "                        // ★ 性能：步长 0.15→0.19、步数 320→224（覆盖 42.6m，原 48m），" + NL +
    "                        //   GPU 光线步进计算量约 -30%，视觉差异极小" + NL +
    "                        uStepSize: { value: 0.19 }," + NL +
    "                        uMaxSteps: { value: 224 }," + NL
)
sub(old5, new5, '体积烟雾光线步进参数降载')

with io.open(PATH, 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('[DONE] 补丁2写入完成')
