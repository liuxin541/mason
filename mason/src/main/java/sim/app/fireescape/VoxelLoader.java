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

            // 2. 解析体素数据 (z -> y -> x)
            for (int z = 0; z < depth; z++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int type = buffer.get() & 0xFF; // 无符号 byte 转为 int

                        obstacleGrid.set(x, y, z, type);

                        if (type == FireEscape.TYPE_WALL || type == FireEscape.TYPE_STAIR) {
                            insideGrid.set(x, y, z, 0);
                            fuelGrid.set(x, y, z, 0);
                        } else {
                            insideGrid.set(x, y, z, 1);
                            if (type == FireEscape.TYPE_FUEL) {
                                fuelGrid.set(x, y, z, 80); // 默认燃料量
                            } else if (type == FireEscape.TYPE_EXIT) {
                                // 将出口坐标存入列表
                                exits.add(new int[]{x, y, z});
                            }
                        }
                    }
                }
            }
        }
    }
}