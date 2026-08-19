"""Mock HTTP server for fireescape.html frontend verification.

测试 slab 合并修复：模拟真实建筑的重复 slab 问题
- floorSlabs 有 7 个：[17, 18, 32, 46, 47, 59, 60]
- 前端 slab 合并后应得到 4 层：[17, 32, 46, 59]
- 每层 slab z (>50% wallCount) 下方有墙轮廓 z (5%-40% wallCount)
- 验证修复后 F1-F4 都显示墙轮廓而非灰块
"""
import http.server, socketserver, json, os, urllib.parse

ROOT = os.path.dirname(os.path.abspath(__file__))

# 模拟真实建筑尺寸
W, H, D = 156, 73, 68
VOXEL = 0.25
TOTAL = W * H

# 真实数据的 floorSlabs（含重复：同一楼板的顶面+天花板面）
FLOOR_SLABS_DUPLICATE = [17, 18, 32, 46, 47, 59, 60]

# 每层的墙轮廓 z（slab 上方 1-2 格）
FLOOR_WALL_Z = {
    17: 19,   # F1: slab=17, wall at z=19
    32: 33,   # F2: slab=32, wall at z=33
    46: 48,   # F3: slab=46, wall at z=48
    59: 60,   # F4: slab=59, wall at z=60 (最顶楼层，用 slab 本身作为兜底)
}

T_AMB = 293.15

def _empty_floor():
    return [[0] * H for _ in range(W)]

def _slab_floor():
    """楼板层：覆盖率 ~80%（模拟真实 slab）。"""
    f = _empty_floor()
    coverage = 0.80
    count = int(TOTAL * coverage)
    for i in range(count):
        x = i % W
        y = i // W
        if y < H:
            f[x][y] = 1
    return f

def _wall_floor(z):
    """墙轮廓层：覆盖率 ~12%（模拟真实墙体）。"""
    f = _empty_floor()
    coverage = 0.12
    count = int(TOTAL * coverage)
    for i in range(count):
        x = i % W
        y = i // W
        if 0 <= x < W and 1 <= y < H - 1:
            f[x][y] = 1
    return f

def build_obstacle():
    obs = [None] * D
    for z in range(D):
        if z in FLOOR_SLABS_DUPLICATE:
            obs[z] = _slab_floor()
        elif z in FLOOR_WALL_Z.values():
            obs[z] = _wall_floor(z)
        # 其他 z 为 None（无 obstacle 数据）
    return obs

def build_inside():
    ins = [None] * D
    for z in range(D):
        if z in FLOOR_WALL_Z.values():
            f = [[0] * H for _ in range(W)]
            for x in range(1, W - 1):
                for y in range(2, H - 2):
                    f[x][y] = 1
            ins[z] = f
    return ins

def build_smoke():
    sm = [None] * D
    for z in FLOOR_WALL_Z.values():
        f = [[0.0] * H for _ in range(W)]
        cx, cy = W // 2, H // 2
        for x in range(max(1, cx - 15), min(W - 1, cx + 15)):
            for y in range(max(1, cy - 10), min(H - 1, cy + 10)):
                dist = abs(x - cx) + abs(y - cy)
                v = max(0.0, 0.7 - dist * 0.03)
                f[x][y] = v
        sm[z] = f
    return sm

def build_fire():
    fr = [None] * D
    for z in FLOOR_WALL_Z.values():
        f = [[T_AMB] * H for _ in range(W)]
        cx, cy = W // 2, H // 2
        for x in range(max(1, cx - 6), min(W - 1, cx + 6)):
            for y in range(max(1, cy - 6), min(H - 1, cy + 6)):
                dist = abs(x - cx) + abs(y - cy)
                t = max(T_AMB, 900 - dist * 40)
                f[x][y] = t
        fr[z] = f
    return fr

def build_persons():
    ps = []
    for z in FLOOR_WALL_Z.values():
        for i in range(5):
            x = 10 + i * 25
            y = 10 + (i % 3) * 15
            if x < W and y < H:
                ps.append([x, y, z])
    return ps

def build_exits():
    return [{"x": 2, "y": H // 2, "z": z} for z in FLOOR_WALL_Z.values()]

def build_fire_sources():
    return [{"x": W // 2, "y": H // 2, "z": z} for z in FLOOR_WALL_Z.values()]

_PERSONS = build_persons()
_EXITS = build_exits()
_SOURCES = build_fire_sources()

STATE = {
    "width": W, "height": H, "depth": D,
    "voxelSize": VOXEL,
    "floorSlabs": FLOOR_SLABS_DUPLICATE,  # ★ 故意返回重复 slab
    "step": 0, "currentTime": 0.0,
    "active": len(_PERSONS), "deadFire": 0, "deadSmoke": 0, "escaped": 0,
    "total": len(_PERSONS),
    "obstacle": build_obstacle(),
    "inside": build_inside(),
    "smoke": build_smoke(),
    "fire": build_fire(),
    "persons": _PERSONS,
    "exits": _EXITS,
    "fireSources": _SOURCES,
}


class Handler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def end_headers(self):
        self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate')
        self.send_header('Pragma', 'no-cache')
        self.send_header('Expires', '0')
        super().end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == '/state':
            STATE["step"] += 1
            STATE["currentTime"] = STATE["step"] * 0.1
            body = json.dumps(STATE).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            super().do_GET()

    def do_POST(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain')
        self.end_headers()
        self.wfile.write(b'ok')

    def log_message(self, format, *args):
        pass


if __name__ == '__main__':
    PORT = 8080
    with socketserver.TCPServer(("127.0.0.1", PORT), Handler) as httpd:
        print(f"Mock server (slab merge test) on http://127.0.0.1:{PORT}/fireescape.html")
        print(f"floorSlabs={FLOOR_SLABS_DUPLICATE} (will be merged to 4 floors by frontend)")
        httpd.serve_forever()
