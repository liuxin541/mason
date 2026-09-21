package sim.app.fireescape;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import sim.engine.RandomSequence;
import sim.engine.Schedule;
import sim.engine.Steppable;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;

public class FireEscapeHttpServer {
    private static FireEscape model;
    private static HttpServer server;
    private static String htmlContent;
    private static ScheduledExecutorService stepScheduler;
    private static volatile boolean autoStepRunning = false;
    private static int stepIntervalMs = 500;  // 1x速度=1倍实时(每步0.5s, 每秒2步)
    private static volatile int speedMultiplier = 1;

    private static FireSpread fireSpread;
    private static int[][] customObstacles = null;
    private static int[][] customInside = null;
    private static List<int[]> customFireSources = null;
    private static boolean noFireMode = false;  // ★ 无火模式：只观察人员行为，不点火

    // ---- 一键启动配置参数 ----
    private static int serverPort = 8080;
    private static boolean autoOpenBrowser = true;

    public FireEscapeHttpServer(FireEscape sim) { model = sim; }

    public void start() throws IOException {
        loadHtmlContent();
        initModel();
        server = HttpServer.create(new InetSocketAddress(serverPort), 0);
        server.createContext("/", new IndexHandler());
        server.createContext("/ifc_output", new StaticFileHandler("ifc_output"));
        server.createContext("/state", new StateHandler());
        server.createContext("/smokeVolume", new SmokeVolumeHandler());
        server.createContext("/command", new CommandHandler());
        server.createContext("/listMaps", new ListMapsHandler());
        server.createContext("/loadMap", new LoadMapHandler());
        server.createContext("/saveMap", new SaveMapHandler());
        server.createContext("/uploadMapImage", new UploadMapImageHandler());
        server.createContext("/setBoundary", new SetBoundaryHandler());
        server.createContext("/setFireSources", new SetFireSourcesHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Web 界面已启动: http://localhost:" + serverPort);
    }

    // ---- 原版速度控制 ----
    // 基础间隔500ms: 每步0.5s物理时间, 1x速度=1倍实时(每秒2步=1s物理时间)
    private static void setSpeed(int multiplier) {
        if (multiplier < 1) multiplier = 1;
        speedMultiplier = multiplier;
        int newInterval = 500 / multiplier;
        if (newInterval < 10) newInterval = 10;
        boolean wasRunning = autoStepRunning;
        if (wasRunning) stopAutoStep();
        stepIntervalMs = newInterval;
        if (wasRunning) startAutoStep();
        System.out.println("速度设置为 " + multiplier + " 倍，步进间隔 " + stepIntervalMs + "ms");
    }

    private static void startAutoStep() {
        if (autoStepRunning) return;
        if (stepScheduler == null || stepScheduler.isShutdown()) {
            if (stepScheduler != null && !stepScheduler.isTerminated()) {
                stepScheduler.shutdownNow();
                try { if (!stepScheduler.awaitTermination(1, TimeUnit.SECONDS)) {} } catch (InterruptedException ignored) {}
            }
            stepScheduler = Executors.newSingleThreadScheduledExecutor();
        }
        autoStepRunning = true;
        stepScheduler.scheduleAtFixedRate(() -> {
            try {
                if (autoStepRunning) model.schedule.step(model);
            } catch (Throwable t) { System.out.println("[STEP-EX] " + t + " @ " + java.util.Arrays.toString(t.getStackTrace())); }
        }, 0, stepIntervalMs, TimeUnit.MILLISECONDS);
    }

    private static void stopAutoStep() {
        autoStepRunning = false;
        if (stepScheduler != null && !stepScheduler.isShutdown()) {
            stepScheduler.shutdownNow();
            stepScheduler = null;
        }
    }

    private static int[][] generateInsideFromObstacles(int[][] obstacles) {
        int w = obstacles.length, h = obstacles[0].length;
        int[][] inside = new int[w][h];
        for (int i = 0; i < w; i++) Arrays.fill(inside[i], 0);
        int cx = w/2, cy = h/2;
        int[][] dirs = {{0,0},{1,0},{-1,0},{0,1},{0,-1}};
        int seedX=-1, seedY=-1;
        for (int[] d : dirs) { int nx = cx+d[0], ny = cy+d[1]; if (nx>=0 && nx<w && ny>=0 && ny<h && obstacles[nx][ny]!=1) { seedX=nx; seedY=ny; break; } }
        if (seedX==-1) return inside;
        Queue<int[]> q = new LinkedList<>();
        q.add(new int[]{seedX, seedY}); inside[seedX][seedY]=1;
        while (!q.isEmpty()) {
            int[] p = q.poll();
            for (int dx=-1; dx<=1; dx++) for (int dy=-1; dy<=1; dy++) {
                if (dx==0 && dy==0) continue;
                int nx = p[0]+dx, ny = p[1]+dy;
                if (nx>=0 && nx<w && ny>=0 && ny<h && inside[nx][ny]==0 && obstacles[nx][ny]!=1) {
                    inside[nx][ny]=1; q.add(new int[]{nx,ny});
                }
            }
        }
        return inside;
    }

    private static void syncInsideGridWithObstacles() {
        for (int i=0; i<model.gridWidth; i++)
            for (int j=0; j<model.gridHeight; j++)
                for (int z=0; z<model.gridDepth; z++) {
                    int type = model.obstacleGrid.get(i,j,z);
                    // 墙、柱、窗都是实体障碍；窗只参与围护，不能像门一样恢复为室内通道。
                    if (type == FireEscape.TYPE_WALL ||
                            type == FireEscape.TYPE_COLUMN ||
                            type == FireEscape.TYPE_WINDOW)
                        model.insideGrid.set(i,j,z,0);
                }
    }

    private static void initModel() {
        model.start();
        if (customObstacles != null) {
            for (int z=0; z<model.gridDepth; z++) {
                for (int i=0; i<model.gridWidth && i<customObstacles.length; i++)
                    for (int j=0; j<model.gridHeight && j<customObstacles[i].length; j++) {
                        model.obstacleGrid.set(i,j,z,customObstacles[i][j]);
                        if (customObstacles[i][j]==2) model.fuelGrid.set(i,j,z,model.fuelCapacity);
                        else model.fuelGrid.set(i,j,z,0);
                    }
            }
        }
        if (customInside != null) {
            for (int i=0; i<model.gridWidth; i++) for (int j=0; j<model.gridHeight; j++) {
                for (int z=0; z<model.gridDepth; z++) model.insideGrid.set(i, j, z, customInside[i][j]);
            }
        } else {
            if (customObstacles != null) {
                int[][] auto = generateInsideFromObstacles(customObstacles);
                for (int i=0; i<model.gridWidth; i++) for (int j=0; j<model.gridHeight; j++) {
                    for (int z=0; z<model.gridDepth; z++) model.insideGrid.set(i,j,z,auto[i][j]);
                }
                customInside = auto;
            }
        }
        // ★ 仅2D自定义地图模式需要同步（voxel模型和简单房间已有正确的3D insideGrid）
        if (customObstacles != null) syncInsideGridWithObstacles();

        if (noFireMode) {
            // ★ 无火模式：清空火源，火场保持环境温度
            model.fireSourcePositions.clear();
            for (int i=0; i<model.gridWidth; i++)
                for (int j=0; j<model.gridHeight; j++)
                    for (int z=0; z<model.gridDepth; z++)
                        model.fireGrid.set(i, j, z, FireEscape.T_AMBIENT);
        } else if (customFireSources != null && !customFireSources.isEmpty()) {
            model.fireSourcePositions.clear();
            for (int[] p : customFireSources) {
                // 方案C修复: 火源z不再写死0(可能在地下/楼板下), 改为找该列最低室内格
                int zf = model.findLowestInteriorZ(p[0], p[1]);
                if (zf >= 0)
                    model.fireSourcePositions.add(new int[]{p[0], p[1], zf});
            }
            if (model.fireSourcePositions.isEmpty()) model.generateRandomFireSources();
            model.initMultipleFireSources();
        } else {
            model.fireSourcePositions.clear();
            model.generateRandomFireSources();
            model.initMultipleFireSources();
        }
        model.initExits();
        // 方案A微调2: 楼板开竖井(模拟楼梯井/竖井, 烟可向上层蔓延, 多层冒烟更真实)
        // 找楼板层(墙占比>55%的水平层), 在若干位置打通竖井列
        List<Integer> slabZs = new ArrayList<Integer>();
        for (int z = 1; z < model.gridDepth - 1; z++) {
            int wallCount = 0, total = 0;
            for (int x = 0; x < model.gridWidth; x++) for (int y = 0; y < model.gridHeight; y++) {
                if (model.obstacleGrid.get(x, y, z) == FireEscape.TYPE_WALL) wallCount++;
                total++;
            }
            if (wallCount * 100 / total > 55) slabZs.add(z);
        }
                // 方案B微调: 竖井选点重写 — 只选"上下层都是楼内(inside=1)、中间是楼板墙"的列, 确保竖井真在楼内贯通
        List<int[]> candidates = new ArrayList<int[]>();
        for (int sx = 4; sx < model.gridWidth - 4; sx++) {
            for (int sy = 4; sy < model.gridHeight - 4; sy++) {
                for (int z : slabZs) {
                    if (z - 1 < 0 || z + 1 >= model.gridDepth) continue;
                    if (model.obstacleGrid.get(sx, sy, z) != FireEscape.TYPE_WALL) continue;
                    if (model.insideGrid.get(sx, sy, z-1) != 1) continue;
                    if (model.insideGrid.get(sx, sy, z+1) != 1) continue;
                    candidates.add(new int[]{sx, sy, z});
                    break;
                }
            }
        }
        // ★ 修复: 楼梯间整截面打通楼板 — 找到所有TYPE_STAIR的(x,y)投影, 在所有楼板层打通
        // 真实火灾中楼梯间是完整垂直井道, 烟可在整个楼梯间截面上升(原实现只开3个1x1小洞, 烟根本上不去)
        List<int[]> stairXYList = new ArrayList<int[]>();
        boolean[][] stairXYFlag = new boolean[model.gridWidth][model.gridHeight];
        for (int x = 0; x < model.gridWidth; x++) {
            for (int y = 0; y < model.gridHeight; y++) {
                for (int z = 0; z < model.gridDepth; z++) {
                    if (model.obstacleGrid.get(x, y, z) == FireEscape.TYPE_STAIR) {
                        if (!stairXYFlag[x][y]) { stairXYFlag[x][y] = true; stairXYList.add(new int[]{x, y}); }
                        break;
                    }
                }
            }
        }
        int stairShaftCells = 0;
        for (int[] sp : stairXYList) {
            int sx = sp[0], sy = sp[1];
            for (int z : slabZs) {
                if (model.obstacleGrid.get(sx, sy, z) == FireEscape.TYPE_WALL) {
                    model.obstacleGrid.set(sx, sy, z, 0);
                    model.insideGrid.set(sx, sy, z, 1);
                    stairShaftCells++;
                }
            }
        }
        System.out.println("[BIM] 楼梯间竖井打通: " + stairXYList.size() + " 个楼梯间位置, 打通 " + stairShaftCells + " 个楼板格");

        int shaftMade = 0;
        int nCand = candidates.size();
        if (nCand > 0) {
            // ★ 修复: 竖井贯穿整栋楼(原实现只在z±2开5格, 其他楼层楼板仍挡住)
            List<int[]> shaftPicks = new ArrayList<int[]>();
            for (int seg = 0; seg < 3 && shaftMade < 3; seg++) {
                int lo = seg * nCand / 3, hi = (seg+1) * nCand / 3;
                if (hi <= lo) hi = lo + 1;
                shaftPicks.add(candidates.get((lo + hi) / 2));
                shaftMade++;
            }
            for (int[] pick : shaftPicks) {
                int sx = pick[0], sy = pick[1];
                for (int z : slabZs) {
                    if (model.obstacleGrid.get(sx, sy, z) == FireEscape.TYPE_WALL) {
                        model.obstacleGrid.set(sx, sy, z, 0);
                        model.insideGrid.set(sx, sy, z, 1);
                    }
                }
            }
        }
        System.out.println("[BIM] 竖井开洞: " + shaftMade + " 个 (楼板层=" + slabZs.size() + " 火源=(" + (model.fireSourcePositions.isEmpty() ? "无" : (model.fireSourcePositions.get(0)[0] + "," + model.fireSourcePositions.get(0)[1])) + "))");
        // 方案A修复: custom网格覆盖与火源重选都在 model.start() 之后, 需在此按最终网格重建 insideCells,
        // 否则火源柱/新内部格不在回写-clamp-冷却覆盖范围, 烟浓度无限累积且不衰减
        model.insideCells.clear();
        for (int x = 0; x < model.gridWidth; x++)
            for (int y = 0; y < model.gridHeight; y++)
                for (int z = 0; z < model.gridDepth; z++)
                    if (model.insideGrid.get(x, y, z) == 1 && model.obstacleGrid.get(x, y, z) != FireEscape.TYPE_WALL)
                        model.insideCells.add(new int[]{x, y, z});
        System.out.println("[BIM] insideCells 重建(initModel后): " + model.insideCells.size() + " 个内部格, 火源=" + (model.fireSourcePositions.isEmpty() ? "无" : ("(" + model.fireSourcePositions.get(0)[0] + "," + model.fireSourcePositions.get(0)[1] + "," + model.fireSourcePositions.get(0)[2] + ")")));
        model.deadByFire = 0;
        model.deadBySmoke = 0;
        model.schedule.clear();
        Steppable[] agents = Person.spawnPopulation(model);
        if (agents.length > 0) model.schedule.scheduleRepeating(new RandomSequence(agents));
        fireSpread = new FireSpread();
        model.schedule.scheduleRepeating(Schedule.EPOCH, 1, fireSpread, 1);
    }

    private static void resetModel() {
        boolean wasRunning = autoStepRunning;
        if (wasRunning) stopAutoStep();
        initModel();
        if (wasRunning) startAutoStep();
    }

    private static void fillInsideFromPolygon(List<int[]> points) {
        if (points.size() < 3) return;
        int[][] mask = new int[model.gridWidth][model.gridHeight];
        for (int i=0; i<model.gridWidth; i++) Arrays.fill(mask[i],0);
        Polygon polygon = new Polygon();
        for (int[] p : points) polygon.addPoint(p[0], p[1]);
        for (int x=0; x<model.gridWidth; x++) for (int y=0; y<model.gridHeight; y++)
            if (polygon.contains(x,y)) mask[x][y]=1;
        customInside = mask;
        resetModel();
    }

    private static void loadMapFile(String mapName) throws IOException {
        File mapFile = new File(mapName);
        if (!mapFile.exists()) throw new FileNotFoundException("地图不存在: "+mapName);
        List<int[]> rows = new ArrayList<>(), insideRows = new ArrayList<>(), fireRows = new ArrayList<>();
        boolean readingInside=false, readingFire=false;
        try (BufferedReader br = new BufferedReader(new FileReader(mapFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#inside")) { readingInside=true; readingFire=false; continue; }
                if (line.startsWith("#firesources")) { readingFire=true; readingInside=false; continue; }
                String[] parts = line.split(",");
                int[] row = new int[parts.length];
                for (int i=0; i<parts.length; i++) row[i] = Integer.parseInt(parts[i].trim());
                if (readingInside) insideRows.add(row);
                else if (readingFire) fireRows.add(row);
                else rows.add(row);
            }
        }
        if (rows.isEmpty()) throw new IOException("地图文件缺少障碍物数据");
        int width = rows.get(0).length, height = rows.size();
        if (width != model.gridWidth || height != model.gridHeight)
            throw new IllegalArgumentException("地图尺寸不匹配");
        customObstacles = new int[width][height];
        for (int x=0; x<width; x++) for (int y=0; y<height; y++) customObstacles[x][y] = rows.get(y)[x];
        if (!insideRows.isEmpty()) {
            customInside = new int[width][height];
            for (int x=0; x<width; x++) for (int y=0; y<height; y++) customInside[x][y] = insideRows.get(y)[x];
        } else { customInside = null; }
        customFireSources = new ArrayList<>();
        if (!fireRows.isEmpty()) { for (int[] row : fireRows) { if (row.length >= 2) customFireSources.add(new int[]{row[0], row[1]}); } } else { customFireSources = null; }
        resetModel();
    }

    private static void saveMapFile(String mapName, int[][] obstacles) throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(mapName))) {
            for (int y=0; y<model.gridHeight; y++) {
                StringBuilder sb = new StringBuilder();
                for (int x=0; x<model.gridWidth; x++) { if (x>0) sb.append(","); sb.append(obstacles[x][y]); }
                bw.write(sb.toString()); bw.newLine();
            }
            bw.write("#inside"); bw.newLine();
            int[][] inside = customInside != null ? customInside : new int[model.gridWidth][model.gridHeight];
            for (int y=0; y<model.gridHeight; y++) {
                StringBuilder sb = new StringBuilder();
                for (int x=0; x<model.gridWidth; x++) { if (x>0) sb.append(","); sb.append(inside[x][y]); }
                bw.write(sb.toString()); bw.newLine();
            }
            bw.write("#firesources"); bw.newLine();
            for (int[] p : model.fireSourcePositions) { bw.write(p[0]+","+p[1]); bw.newLine(); }
        }
    }

    /**
     * 静态文件处理器：将 URL 路径映射到本地文件目录，防止路径穿越攻击。
     */
    static class StaticFileHandler implements HttpHandler {
        private final File baseDir;

        public StaticFileHandler(String dir) {
            this.baseDir = new File(dir);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            // 去掉上下文前缀 "/ifc_output"
            if (path.startsWith("/ifc_output")) path = path.substring("/ifc_output".length());
            if (path.startsWith("/")) path = path.substring(1);
            if (path.isEmpty()) path = "index.html";

            // 安全：防止路径穿越
            File requested = new File(baseDir, path);
            File canonical = requested.getCanonicalFile();
            if (!canonical.getPath().startsWith(baseDir.getCanonicalPath())) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            if (!canonical.exists() || canonical.isDirectory()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            String contentType = guessContentType(canonical.getName());
            byte[] data = Files.readAllBytes(canonical.toPath());
            exchange.getResponseHeaders().set("Content-Type", contentType + ";charset=UTF-8");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
        }

        private static String guessContentType(String name) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".glb") || lower.endsWith(".gltf")) return "model/gltf-binary";
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".voxel")) return "application/octet-stream";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".html")) return "text/html";
            if (lower.endsWith(".js")) return "application/javascript";
            if (lower.endsWith(".css")) return "text/css";
            return "application/octet-stream";
        }
    }

    static class IndexHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = htmlContent.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html;charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    static class StateHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            for (int i=0; i<model.gridWidth; i++) for (int j=0; j<model.gridHeight; j++) for (int z=0; z<model.gridDepth; z++)
                if (model.obstacleGrid.get(i,j,z)==1 || model.insideGrid.get(i,j,z)==0) {
                    model.fireGrid.field[i][j][z] = FireEscape.T_AMBIENT;
                    model.smokeGrid.field[i][j][z] = 0.0;
                }
            String json = buildStateJson();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            String ae = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            if (ae != null && ae.toLowerCase().contains("gzip")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(bytes.length / 2);
                try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(bytes); }
                byte[] gzBytes = bos.toByteArray();
                if (gzBytes.length < bytes.length) {
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                    exchange.sendResponseHeaders(200, gzBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(gzBytes); }
                    return;
                }
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
    }

    // ★ 零长度响应必须关闭响应流, 否则 JDK HttpServer 客户端永远收不到响应
    private static void respondOk(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
    }

    // ===== /smokeVolume: 输出扁平化3D浓度场+温度场 (供前端体积渲染) =====
    // 降采样取 max + 按模拟时间缓存 + gzip, 避免大载荷拖垮轮询
    // 支持 ?stride=N 参数: 简单房间用1(全分辨率), 大楼用2/4(控制载荷)
    static class SmokeVolumeHandler implements HttpHandler {
        private static double cachedTime = -1;
        private static int cachedStride = -1;
        private static byte[] cachedBody = null;

        @Override public void handle(HttpExchange exchange) throws IOException {
            // 解析 stride 参数
            String query = exchange.getRequestURI().getQuery();
            int stride = 2;
            if (query != null && query.contains("stride=")) {
                try {
                    stride = Integer.parseInt(query.substring(query.indexOf("stride=") + 7).split("&")[0]);
                    if (stride < 1) stride = 1;
                    if (stride > 8) stride = 8;
                } catch (Exception e) { /* 默认2 */ }
            }
            double t = model.currentTime;
            if (cachedBody == null || cachedTime != t || cachedStride != stride) {
                cachedBody = buildSmokeVolumeJson(stride).getBytes(StandardCharsets.UTF_8);
                cachedTime = t;
                cachedStride = stride;
            }
            byte[] bytes = cachedBody;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            String ae = exchange.getRequestHeaders().getFirst("Accept-Encoding");
            if (ae != null && ae.toLowerCase().contains("gzip")) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(bytes.length / 3);
                try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(bytes); }
                byte[] gzBytes = bos.toByteArray();
                if (gzBytes.length < bytes.length) {
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                    exchange.sendResponseHeaders(200, gzBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(gzBytes); }
                    return;
                }
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }

        private static String buildSmokeVolumeJson(int stride) {
            int w = model.gridWidth, h = model.gridHeight, d = model.gridDepth;
            int dw = (w + stride - 1) / stride;
            int dh = (h + stride - 1) / stride;
            int dd = (d + stride - 1) / stride;
            double[][][] C = model.smokeGrid.field;
            double[][][] T = model.fireGrid.field;
            int[][][] inside = model.insideGrid.field;
            float[] smoke = new float[dw * dh * dd];
            float[] temp = new float[dw * dh * dd];
            int idx = 0;
            for (int z = 0; z < d; z += stride) {
                for (int y = 0; y < h; y += stride) {
                    for (int x = 0; x < w; x += stride) {
                        float sMax = 0f, tMax = (float) FireEscape.T_AMBIENT;
                        boolean anyInside = false;
                        for (int k = 0; k < stride && z + k < d; k++) {
                            int zz = z + k;
                            for (int j = 0; j < stride && y + j < h; j++) {
                                int yy = y + j;
                                double[] cx = C[x][yy];
                                double[] tx = T[x][yy];
                                int[] ix = inside[x][yy];
                                for (int i = 0; i < stride && x + i < w; i++) {
                                    if (ix[zz] == 1) anyInside = true;
                                    double s = cx[zz];
                                    double t2 = tx[zz];
                                    if (s > sMax) sMax = (float) s;
                                    if (t2 > tMax) tMax = (float) t2;
                                }
                            }
                        }
                        if (!anyInside) { sMax = 0f; tMax = (float) FireEscape.T_AMBIENT; }
                        smoke[idx] = sMax;
                        temp[idx] = tMax;
                        idx++;
                    }
                }
            }
            StringBuilder sb = new StringBuilder(200 + idx * 24);
            sb.append("{\"width\":").append(w).append(",\"height\":").append(h).append(",\"depth\":").append(d)
              .append(",\"stride\":").append(stride)
              .append(",\"smoke\":[");
            for (int i = 0; i < idx; i++) {
                if (i > 0) sb.append(',');
                sb.append(Math.round(smoke[i] * 100.0) / 100.0);
            }
            sb.append("],\"temp\":[");
            for (int i = 0; i < idx; i++) {
                if (i > 0) sb.append(',');
                sb.append(Math.round(temp[i] * 10.0) / 10.0);
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    static class CommandHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                String body = readRequestBody(exchange.getRequestBody());
                String cmd = body.startsWith("cmd=") ? body.substring(4) : null;
                if (cmd != null) {
                    if (cmd.startsWith("speed_")) { int mult = Integer.parseInt(cmd.substring(6)); setSpeed(mult); respondOk(exchange); return; }
                    switch (cmd) {
                        case "start": noFireMode = false; resetModel(); startAutoStep(); break;
                        case "pause": stopAutoStep(); break;
                        case "resume": startAutoStep(); break;
                        case "step": stopAutoStep(); model.schedule.step(model); break;
                        case "reset": noFireMode = false; resetModel(); break;
                        case "togglePersons": model.showPersons = !model.showPersons; break;
                        case "map_simple":
                            model.mapMode = "simple";
                            resetModel();
                            System.out.println("[Map] 切换到简单矩形房间");
                            break;
                        case "map_building":
                            model.mapMode = "building";
                            resetModel();
                            System.out.println("[Map] 切换到BIM大楼模型");
                            break;
                        case "noFire":
                            noFireMode = true;
                            boolean wasRunning = autoStepRunning;
                            if (wasRunning) stopAutoStep();
                            initModel();
                            if (wasRunning) startAutoStep();
                            System.out.println("[Mode] 无火模式已开启（仅观察人员行为）");
                            break;
                    }
                }
            }
            respondOk(exchange);
        }
        private String readRequestBody(InputStream is) throws IOException {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buf = new byte[1024]; int len;
            while ((len = is.read(buf)) != -1) result.write(buf,0,len);
            return result.toString(StandardCharsets.UTF_8.name());
        }
    }

    static class ListMapsHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            File[] files = new File(".").listFiles((d,n)->n.endsWith(".map"));
            StringBuilder json = new StringBuilder("{\"maps\":[");
            if (files != null) { for (int i=0; i<files.length; i++) { if (i>0) json.append(","); json.append("\"").append(escapeJson(files[i].getName())).append("\""); } }
            json.append("]}");
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        }
        private String escapeJson(String s) { return s.replace("\\","\\\\").replace("\"","\\\""); }
    }

    static class LoadMapHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            if (query==null || !query.startsWith("name=")) { exchange.sendResponseHeaders(400,-1); return; }
            String mapName = URLDecoder.decode(query.substring(5), "UTF-8");
            try { loadMapFile(mapName); respondOk(exchange); } catch (Exception e) { e.printStackTrace(); exchange.sendResponseHeaders(500,-1); }
        }
    }

    static class SaveMapHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            String body = readRequestBody(exchange.getRequestBody());
            String name = null;
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String key = URLDecoder.decode(pair.substring(0,eq), "UTF-8");
                String val = URLDecoder.decode(pair.substring(eq+1), "UTF-8");
                if ("name".equals(key)) name = val;
            }
            if (name == null) name = "custom.map";
            if (!name.endsWith(".map")) name += ".map";
            int[][] obstacles = new int[model.gridWidth][model.gridHeight];
            for (int i=0; i<model.gridWidth; i++) for (int j=0; j<model.gridHeight; j++) obstacles[i][j] = model.obstacleGrid.get(i,j,0);
            saveMapFile(name, obstacles);
            respondOk(exchange);
        }
        private String readRequestBody(InputStream is) throws IOException {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buf = new byte[1024]; int len;
            while ((len = is.read(buf)) != -1) result.write(buf,0,len);
            return result.toString(StandardCharsets.UTF_8.name());
        }
    }

    static class UploadMapImageHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            String body = readRequestBody(exchange.getRequestBody());
            String imageData = null; int threshold = 128;
            try {
                String[] parts = body.split(",");
                for (String part : parts) {
                    if (part.contains("\"imageData\"")) {
                        int start = part.indexOf(":")+1; int end = part.lastIndexOf("\"");
                        if (end<0) end=part.length();
                        imageData = part.substring(start,end).trim();
                        if (imageData.startsWith("\"") && imageData.endsWith("\"")) imageData = imageData.substring(1, imageData.length()-1);
                    } else if (part.contains("\"threshold\"")) {
                        String val = part.substring(part.indexOf(":")+1).trim();
                        threshold = Integer.parseInt(val);
                    }
                }
            } catch (Exception e) { e.printStackTrace(); exchange.sendResponseHeaders(400,-1); return; }
            if (imageData == null || imageData.isEmpty()) { exchange.sendResponseHeaders(400,-1); return; }
            try {
                byte[] imageBytes = Base64.getDecoder().decode(imageData);
                ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes);
                BufferedImage original = ImageIO.read(bais);
                if (original == null) throw new IOException("无法解码图片");
                BufferedImage scaled = new BufferedImage(model.gridWidth, model.gridHeight, BufferedImage.TYPE_BYTE_GRAY);
                Graphics2D g = scaled.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(original, 0, 0, model.gridWidth, model.gridHeight, null);
                g.dispose();
                int[][] obstacles = new int[model.gridWidth][model.gridHeight];
                for (int x=0; x<model.gridWidth; x++) for (int y=0; y<model.gridHeight; y++) { int rgb = scaled.getRGB(x,y); int gray = (rgb >> 16) & 0xFF; obstacles[x][y] = (gray < threshold) ? 1 : 0; }
                customObstacles = obstacles; customInside = null; customFireSources = null;
                resetModel();
                String response = "{\"status\":\"ok\",\"width\":"+model.gridWidth+",\"height\":"+model.gridHeight+"}";
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(response.getBytes(StandardCharsets.UTF_8)); }
            } catch (Exception e) { e.printStackTrace(); exchange.sendResponseHeaders(500,-1); }
        }
        private String readRequestBody(InputStream is) throws IOException {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buf = new byte[1024]; int len;
            while ((len = is.read(buf)) != -1) result.write(buf,0,len);
            return result.toString(StandardCharsets.UTF_8.name());
        }
    }

    static class SetBoundaryHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            String body = readRequestBody(exchange.getRequestBody());
            List<int[]> points = new ArrayList<>();
            try {
                String json = body.trim();
                int start = json.indexOf("["); int end = json.lastIndexOf("]");
                if (start<0 || end<0) throw new Exception("Invalid format");
                String arrayStr = json.substring(start, end+1);
                String[] entries = arrayStr.split("\\],\\[");
                for (String entry : entries) {
                    entry = entry.replace("[","").replace("]","");
                    String[] coords = entry.split(",");
                    if (coords.length == 2) { int x = Integer.parseInt(coords[0].trim()); int y = Integer.parseInt(coords[1].trim()); points.add(new int[]{x,y}); }
                }
            } catch (Exception e) { e.printStackTrace(); exchange.sendResponseHeaders(400,-1); return; }
            if (points.size() < 3) { exchange.sendResponseHeaders(400,-1); return; }
            fillInsideFromPolygon(points);
            respondOk(exchange);
        }
        private String readRequestBody(InputStream is) throws IOException {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buf = new byte[1024]; int len;
            while ((len = is.read(buf)) != -1) result.write(buf,0,len);
            return result.toString(StandardCharsets.UTF_8.name());
        }
    }

    static class SetFireSourcesHandler implements HttpHandler {
        @Override public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405,-1); return; }
            String body = readRequestBody(exchange.getRequestBody());
            List<int[]> sources = new ArrayList<>();
            try {
                String json = body.trim();
                int start = json.indexOf("["); int end = json.lastIndexOf("]");
                if (start<0 || end<0) throw new Exception("Invalid format");
                String arrayStr = json.substring(start, end+1);
                String[] entries = arrayStr.split("\\],\\[");
                for (String entry : entries) {
                    entry = entry.replace("[","").replace("]","");
                    String[] coords = entry.split(",");
                    if (coords.length == 2) { int x = Integer.parseInt(coords[0].trim()); int y = Integer.parseInt(coords[1].trim()); sources.add(new int[]{x,y}); }
                }
            } catch (Exception e) { e.printStackTrace(); exchange.sendResponseHeaders(400,-1); return; }
            List<int[]> valid = new ArrayList<>();
            for (int[] p : sources) {
                // 方案C修复: 火源z不再写死0(可能在地下/楼板下), 改为找该列最低室内格
                int zf = model.findLowestInteriorZ(p[0], p[1]);
                if (zf >= 0) valid.add(new int[]{p[0], p[1], zf});
            }
            customFireSources = valid.isEmpty()? null : valid;
            resetModel();
            respondOk(exchange);
        }
        private String readRequestBody(InputStream is) throws IOException {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] buf = new byte[1024]; int len;
            while ((len = is.read(buf)) != -1) result.write(buf,0,len);
            return result.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String buildStateJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"step\":").append(model.schedule.getSteps()).append(",");
        sb.append("\"currentTime\":").append(model.currentTime).append(",");
        sb.append("\"active\":").append(model.getActivePersonCount()).append(",");
        sb.append("\"deadFire\":").append(model.getDeadByFire()).append(",");
        sb.append("\"deadSmoke\":").append(model.getDeadBySmoke()).append(",");
        sb.append("\"escaped\":").append(model.getEscapedCount()).append(",");
        sb.append("\"total\":").append(model.personCount).append(",");
        sb.append("\"width\":").append(model.gridWidth).append(",");
        sb.append("\"height\":").append(model.gridHeight).append(",");
        sb.append("\"depth\":").append(model.gridDepth).append(",");

        // ★ 网格数据压缩: stride=2 (x,y 各降采样一半) + 数值取整, 载荷从 ~12MB 降到 ~1.5MB
        int stride = 2;
        sb.append("\"stride\":").append(stride).append(",");

        sb.append("\"fire\":[");
        for (int z=0; z<model.gridDepth; z++) {
            if (z>0) sb.append(",");
            sb.append("[");
            int ix=0;
            for (int i=0; i<model.gridWidth; i+=stride) {
                if (ix>0) sb.append(",");
                sb.append("[");
                int iy=0;
                for (int j=0; j<model.gridHeight; j+=stride) {
                    if (iy>0) sb.append(",");
                    double val = model.fireGrid.get(i, j, z);
                    if (model.obstacleGrid.get(i, j, z) == 1 || model.insideGrid.get(i, j, z) == 0) val = FireEscape.T_AMBIENT;
                    sb.append(((double)((long)(val*10.0)))/10.0);
                    iy++;
                }
                sb.append("]");
                ix++;
            }
            sb.append("]");
        }
        sb.append("],");

        sb.append("\"smoke\":[");
        for (int z=0; z<model.gridDepth; z++) {
            if (z>0) sb.append(",");
            sb.append("[");
            int ix=0;
            for (int i=0; i<model.gridWidth; i+=stride) {
                if (ix>0) sb.append(",");
                sb.append("[");
                int iy=0;
                for (int j=0; j<model.gridHeight; j+=stride) {
                    if (iy>0) sb.append(",");
                    double val = model.smokeGrid.get(i, j, z);
                    if (model.obstacleGrid.get(i, j, z) == 1 || model.insideGrid.get(i, j, z) == 0) val = 0.0;
                    // 方案A修复: 0.01 精度 + 四舍五入, 低浓度烟可见
                    sb.append(Math.round(val*100.0)/100.0);
                    iy++;
                }
                sb.append("]");
                ix++;
            }
            sb.append("]");
        }
        sb.append("],");

        sb.append("\"obstacle\":[");
        for (int z=0; z<model.gridDepth; z++) {
            if (z>0) sb.append(",");
            sb.append("[");
            int ix=0;
            for (int i=0; i<model.gridWidth; i+=stride) {
                if (ix>0) sb.append(",");
                sb.append("[");
                int iy=0;
                for (int j=0; j<model.gridHeight; j+=stride) {
                    if (iy>0) sb.append(",");
                    int val = model.obstacleGrid.get(i, j, z);
                    // 2x2 块取最大(墙/楼梯/出口不丢失)
                    if (i+1 < model.gridWidth) val = Math.max(val, model.obstacleGrid.get(i+1, j, z));
                    if (j+1 < model.gridHeight) val = Math.max(val, model.obstacleGrid.get(i, j+1, z));
                    if (i+1 < model.gridWidth && j+1 < model.gridHeight) val = Math.max(val, model.obstacleGrid.get(i+1, j+1, z));
                    sb.append(val);
                    iy++;
                }
                sb.append("]");
                ix++;
            }
            sb.append("]");
        }
        sb.append("],");

        sb.append("\"inside\":[");
        for (int z=0; z<model.gridDepth; z++) {
            if (z>0) sb.append(",");
            sb.append("[");
            int ix=0;
            for (int i=0; i<model.gridWidth; i+=stride) {
                if (ix>0) sb.append(",");
                sb.append("[");
                int iy=0;
                for (int j=0; j<model.gridHeight; j+=stride) {
                    if (iy>0) sb.append(",");
                    int val = model.insideGrid.get(i, j, z);
                    if (i+1 < model.gridWidth) val = Math.max(val, model.insideGrid.get(i+1, j, z));
                    if (j+1 < model.gridHeight) val = Math.max(val, model.insideGrid.get(i, j+1, z));
                    if (i+1 < model.gridWidth && j+1 < model.gridHeight) val = Math.max(val, model.insideGrid.get(i+1, j+1, z));
                    sb.append(val);
                    iy++;
                }
                sb.append("]");
                ix++;
            }
            sb.append("]");
        }
        sb.append("],");

        sb.append("\"fireSources\":[");
        boolean firstSrc=true;
        int srcIdx=0;
        for (int[] p : model.fireSourcePositions) {
            if (!firstSrc) sb.append(",");
            double hr = srcIdx < model.fireSourceHeatRelease.length ? model.fireSourceHeatRelease[srcIdx] : 0.0;
            double fh = srcIdx < model.fireSourceFlameHeight.length ? model.fireSourceFlameHeight[srcIdx] : 0.0;
            double pr = srcIdx < model.fireSourcePlumeRadius.length ? model.fireSourcePlumeRadius[srcIdx] : 0.0;
            sb.append("{\"x\":").append(p[0]).append(",\"y\":").append(p[1]).append(",\"z\":").append(p[2])
              .append(",\"heatRelease\":").append(hr)
              .append(",\"flameHeight\":").append(fh)
              .append(",\"plumeRadius\":").append(pr).append("}");
            firstSrc=false;
            srcIdx++;
        }
        sb.append("],");
        sb.append("\"fireSourceCount\":").append(model.fireSourcePositions.size()).append(",");

        // ★ 出口位置（供小地图绘制绿色出口标记）
        sb.append("\"exits\":[");
        boolean firstExit=true;
        for (int[] p : model.exits) {
            if (!firstExit) sb.append(",");
            sb.append("{\"x\":").append(p[0]).append(",\"y\":").append(p[1]).append(",\"z\":").append(p[2]).append("}");
            firstExit=false;
        }
        sb.append("],");

        // ★ 楼板层 z 索引（与 Java 端 zGround/zRoof 检测一致：墙体覆盖率 > 40% 的 z 切片）
        sb.append("\"floorSlabs\":[");
        java.util.List<Integer> slabs = new ArrayList<>();
        for (int z=0; z<model.gridDepth; z++) {
            int wallCount=0;
            for (int i=0; i<model.gridWidth; i++)
                for (int j=0; j<model.gridHeight; j++)
                    if (model.obstacleGrid.get(i,j,z)==1) wallCount++;
            if (wallCount > model.gridWidth * model.gridHeight * 0.4) slabs.add(z);
        }
        for (int i=0; i<slabs.size(); i++) {
            if (i>0) sb.append(",");
            sb.append(slabs.get(i));
        }
        sb.append("],");

        // ★ 坐标系统参数（让前端无需单独 fetch semantic_info.json）
        sb.append("\"voxelSize\":").append(model.deltaX).append(",");
        sb.append("\"originOffset\":[")
                .append(model.semanticInfo.originOffset[0]).append(",")
                .append(model.semanticInfo.originOffset[1]).append(",")
                .append(model.semanticInfo.originOffset[2]).append("],");

        if (model.showPersons) {
            sb.append("\"persons\":[");
            boolean first=true;
            for (Person p : model.persons) {
                if (p!=null && p.alive && !p.escaped) {
                    if (!first) sb.append(",");
                    sb.append("[").append(p.getX()).append(",").append(p.getY()).append(",").append(p.getZ()).append("]");
                    first=false;
                }
            }
            sb.append("]");
        } else {
            sb.append("\"persons\":[]");
        }
        sb.append("}");
        return sb.toString();
    }

    private void loadHtmlContent() throws IOException {
        String customPath = System.getProperty("fireescape.webpage");
        if (customPath != null && !customPath.isEmpty()) { File f = new File(customPath); if (f.exists()) { htmlContent = readFile(f); return; } }
        File local = new File("fireescape.html");
        if (local.exists()) { htmlContent = readFile(local); return; }
        try (InputStream is = getClass().getResourceAsStream("/fireescape.html")) {
            if (is != null) { htmlContent = readStream(is); return; }
        }
        throw new FileNotFoundException("未找到 fireescape.html");
    }
    private String readFile(File f) throws IOException { try (FileInputStream fis = new FileInputStream(f)) { return readStream(fis); } }
    private String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int len;
        while ((len = is.read(buf)) != -1) result.write(buf,0,len);
        return result.toString(StandardCharsets.UTF_8.name());
    }

    // ================================================================
    // ★ 一键启动模块（Launcher） + 内嵌 BIMBridge
    // ================================================================

    /**
     * 静态内部类：BIMBridge（替代外部独立类，防止找不到类编译报错）
     */
    public static class BIMBridge {
        public static void runPythonBIMProcessor(String pythonPath, String scriptPath, String inputIfc, String outputDir)
                throws IOException, InterruptedException {
            ProcessBuilder pb;
            if (inputIfc != null && !inputIfc.isEmpty()) {
                pb = new ProcessBuilder(pythonPath, scriptPath, "--input", inputIfc, "--output", outputDir);
            } else {
                // 针对测试模式，没有输入文件时传入 --test
                pb = new ProcessBuilder(pythonPath, scriptPath, "--test", "--output", outputDir);
            }
            pb.redirectErrorStream(true);
            System.out.println("[BIMBridge] 正在调用 Python 脚本...");
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[Python] " + line);
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("BIM 处理失败，退出码: " + exitCode);
            }
            System.out.println("[BIMBridge] Python 执行成功！");
        }
    }

    /**
     * 一键启动：自动检测体素文件 → 启动仿真 → 启动 HTTP 服务器 → 打开浏览器
     */
    public static void launch() {
        System.out.println("========================================");
        System.out.println("  🔥 火灾逃生仿真系统 - 一键启动");
        System.out.println("========================================");
        try {
            FireEscape sim = new FireEscape(System.currentTimeMillis());
            FireEscapeHttpServer server = new FireEscapeHttpServer(sim);
            server.start();
            Thread.sleep(500);
            if (autoOpenBrowser) {
                openBrowser("http://localhost:" + serverPort);
            }
            System.out.println("========================================");
            System.out.println("  ✅ 启动完成！");
            System.out.println("  🌐 访问地址: http://localhost:" + serverPort);
            System.out.println("  按 Ctrl+C 停止服务");
            System.out.println("========================================");
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("❌ 启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 带 BIM 处理的一键启动：先调用 Python 处理 IFC，再启动仿真
     */
    public static void launchWithBIM(String ifcPath, String outputDir) {
        System.out.println("========================================");
        System.out.println("  🔥 火灾逃生仿真系统 - BIM 模式启动");
        System.out.println("========================================");
        System.out.println("📐 IFC 文件: " + ifcPath);
        System.out.println("📂 输出目录: " + outputDir);
        try {
            System.out.println("\n[1/4] 正在调用 Python BIM 处理模块...");
            String pythonPath = "python";
            String scriptPath = "project_root/bim_processor/run.py";
            BIMBridge.runPythonBIMProcessor(pythonPath, scriptPath, ifcPath, outputDir);
            System.out.println("✅ BIM 处理完成");

            System.out.println("\n[2/4] 正在初始化仿真引擎...");
            FireEscape sim = new FireEscape(System.currentTimeMillis());

            System.out.println("\n[3/4] 正在启动 Web 服务器...");
            FireEscapeHttpServer server = new FireEscapeHttpServer(sim);
            server.start();
            System.out.println("✅ Web 服务器已启动");

            Thread.sleep(500);
            if (autoOpenBrowser) {
                System.out.println("\n[4/4] 正在打开浏览器...");
                openBrowser("http://localhost:" + serverPort);
            }
            System.out.println("\n========================================");
            System.out.println("  ✅ BIM 模式启动完成！");
            System.out.println("  🌐 访问地址: http://localhost:" + serverPort);
            System.out.println("  按 Ctrl+C 停止服务");
            System.out.println("========================================");
            Thread.currentThread().join();
        } catch (Exception e) {
            System.err.println("❌ 启动失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 用测试数据一键启动（3 层迷宫）
     */
    public static void launchWithTestData() {
        System.out.println("========================================");
        System.out.println("  🔥 火灾逃生仿真系统 - 测试模式");
        System.out.println("========================================");
        try {
            File voxelFile = new File("test_output/voxel_grid.voxel");
            if (!voxelFile.exists()) {
                System.out.println("[INFO] 未找到测试体素数据，尝试自动生成...");
                try {
                    String pythonPath = "python";
                    String scriptPath = "project_root/bim_processor/run.py";
                    // 调用内部类产生测试数据
                    BIMBridge.runPythonBIMProcessor(pythonPath, scriptPath, null, "test_output");
                } catch (Exception e) {
                    System.out.println("[WARN] 自动生成失败，请手动运行: ");
                    System.out.println("  cd project_root/bim_processor");
                    System.out.println("  python run.py --test --output ../../test_output --voxel-size 0.5");
                }
            }
            launch();
        } catch (Exception e) {
            System.err.println("❌ 启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 自动打开浏览器
     */
    private static void openBrowser(String url) {
        System.out.println("🌐 正在打开浏览器: " + url);
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception e) {}
        try {
            String os = System.getProperty("os.name").toLowerCase();
            Runtime rt = Runtime.getRuntime();
            if (os.contains("win")) {
                rt.exec("rundll32 url.dll,FileProtocolHandler " + url);
            } else if (os.contains("mac")) {
                rt.exec("open " + url);
            } else if (os.contains("nix") || os.contains("nux")) {
                rt.exec("xdg-open " + url);
            }
        } catch (Exception e) {
            System.out.println("[WARN] 无法自动打开浏览器，请手动访问: " + url);
        }
    }

    public static void setPort(int port) { serverPort = port; }
    public static void setAutoOpenBrowser(boolean enable) { autoOpenBrowser = enable; }

    public static void main(String[] args) throws IOException {
        String mode = "normal";
        String ifcPath = null;
        String outputDir = "test_output";
        int port = 8080;
        boolean noBrowser = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--test": mode = "test"; break;
                case "--bim":
                    mode = "bim";
                    if (i + 1 < args.length) { ifcPath = args[++i]; }
                    break;
                case "--output":
                    if (i + 1 < args.length) { outputDir = args[++i]; }
                    break;
                case "--port":
                    if (i + 1 < args.length) { port = Integer.parseInt(args[++i]); }
                    break;
                case "--no-browser": noBrowser = true; break;
                case "--help":
                case "-h": printHelp(); return;
            }
        }
        setPort(port);
        setAutoOpenBrowser(!noBrowser);

        switch (mode) {
            case "test": launchWithTestData(); break;
            case "bim":
                if (ifcPath == null) {
                    System.err.println("❌ --bim 模式需要指定 IFC 文件路径");
                    printHelp(); System.exit(1);
                }
                launchWithBIM(ifcPath, outputDir); break;
            default: launch(); break;
        }
    }

    private static void printHelp() {
        System.out.println("火灾逃生仿真系统 - 启动参数");
        System.out.println();
        System.out.println("用法: java sim.app.fireescape.FireEscapeHttpServer [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --test              测试模式（用 3 层迷宫测试数据）");
        System.out.println("  --bim <ifc路径>     BIM 模式（先处理 IFC 再启动）");
        System.out.println("  --output <目录>     BIM 输出目录（默认: test_output）");
        System.out.println("  --port <端口>       HTTP 服务器端口（默认: 8080）");
        System.out.println("  --no-browser        不自动打开浏览器");
        System.out.println("  -h, --help          显示帮助信息");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  # 普通启动（自动检测体素文件）");
        System.out.println("  java sim.app.fireescape.FireEscapeHttpServer");
        System.out.println();
        System.out.println("  # 测试模式");
        System.out.println("  java sim.app.fireescape.FireEscapeHttpServer --test");
        System.out.println();
        System.out.println("  # BIM 模式，指定 IFC 文件");
        System.out.println("  java sim.app.fireescape.FireEscapeHttpServer --bim 一号办公楼.ifc");
        System.out.println();
        System.out.println("  # 指定端口，不打开浏览器");
        System.out.println("  java sim.app.fireescape.FireEscapeHttpServer --port 9090 --no-browser");
    }
}
