package sim.app.fireescape;

import sim.field.grid.IntGrid3D;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.List;

public class VoxelLoader {

    public static void loadVoxel(String filePath, int expectedWidth, int expectedHeight, int expectedDepth,
                                 IntGrid3D obstacleGrid, IntGrid3D fuelGrid, IntGrid3D insideGrid,
                                 List<int[]> exits) throws IOException {

        try (FileInputStream fis = new FileInputStream(filePath);
             FileChannel fc = fis.getChannel()) {

            // 读取整个文件到 ByteBuffer
            ByteBuffer buffer = ByteBuffer.allocate((int) fc.size());
            fc.read(buffer);
            buffer.flip();
            // ★ 关键：设置小端序（匹配 Python struct.pack('<...')）
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // 1. 读取 Header (32 字节)
            byte[] magic = new byte[5];
            buffer.get(magic);
            if (!new String(magic).equals("VOXEL")) {
                throw new IOException("无效的 .voxel 文件签名");
            }

            int version = buffer.getShort() & 0xFFFF;
            int width = buffer.getInt();
            int height = buffer.getInt();
            int depth = buffer.getInt();
            float voxelSize = buffer.getFloat();

            // 跳过 9 字节预留位
            buffer.position(buffer.position() + 9);

            // 校验尺寸是否与 Java 模拟器一致
            if (width != expectedWidth || height != expectedHeight || depth != expectedDepth) {
                throw new IllegalArgumentException(String.format(
                        "体素尺寸与模拟器不匹配! 预期: %dx%dx%d, 实际: %dx%dx%d",
                        expectedWidth, expectedHeight, expectedDepth, width, height, depth));
            }

            // 2. 解析体素数据 (z -> y -> x)，先填充 obstacleGrid
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int type = buffer.get() & 0xFF; // 无符号 byte 转为 int
                        obstacleGrid.set(x, y, z, type);
                    }
                }
            }

            // 3. ★ 采用 2D 射线扫描法 (Sweep Line / Even-Odd Rule) 精确判定建筑内部。
            //    原理：对每一列 (x,y) 从下往上 (z=0..depth) 扫描，
            //    每遇到一个墙体/楼梯就翻转一次 inside 状态。
            //    这样能正确处理 L 形、U 形等不规则轮廓的建筑，避免 AABB 包围盒法
            //    将建筑“空缺处”（如天井、缺角）误判为室内。
            //    注意：此方法要求 Python 端已将楼板/屋顶 slab 也体素化为 WALL，
            //    否则楼层间无分隔会导致穿透判定错误。
            int wallCount = 0;

            // 4. 计算 insideGrid / fuelGrid / exits
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    boolean inside = false;
                    for (int z = 0; z < depth; z++) {
                        int type = obstacleGrid.get(x, y, z);
                        if (type == FireEscape.TYPE_WALL) {
                            insideGrid.set(x, y, z, 0);
                            fuelGrid.set(x, y, z, 0);
                            inside = !inside;
                            wallCount++;
                        } else if (type == FireEscape.TYPE_STAIR) {
                            insideGrid.set(x, y, z, 1);
                            fuelGrid.set(x, y, z, 0);
                        } else {
                            if (inside) {
                                insideGrid.set(x, y, z, 1);
                                if (type == FireEscape.TYPE_FUEL) {
                                    fuelGrid.set(x, y, z, 80);
                                } else {
                                    fuelGrid.set(x, y, z, 0);
                                }
                                if (type == FireEscape.TYPE_EXIT) {
                                    exits.add(new int[]{x, y, z});
                                }
                            } else {
                                insideGrid.set(x, y, z, 0);
                                fuelGrid.set(x, y, z, 0);
                            }
                        }
                    }
                }
            }
            System.out.println("[VoxelLoader] 射线扫描完成: 墙体/楼梯翻转次数 = " + wallCount);
        }
    }
}