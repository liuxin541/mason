package sim.app.fireescape;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * FDS 数据导入器：解析 FDS 输出的温度切片 CSV 文件。
 *
 * 支持的CSV格式：
 *   - 第一行为标题行（可选），程序会自动跳过。
 *   - 后续每行格式：时间, 值1, 值2, ..., 值N
 *     其中 N = width * height，按行优先（x方向优先）存储。
 *
 * 用法示例：
 *   FDSDataImporter importer = new FDSDataImporter("path/to/test_devc.csv", 120, 80);
 *   double[][] tempData = importer.getTemperatureSlice(timeInSeconds);
 */
public class FDSDataImporter {
    private final String csvPath;
    private final int width;
    private final int height;
    private final int totalCells;
    private final List<Double> timeSteps = new ArrayList<>();
    private final List<double[]> dataCache = new ArrayList<>(); // 缓存所有时间步的扁平数据

    /**
     * 构造解析器，并预读取所有数据。
     *
     * @param csvPath FDS 输出的 CSV 文件路径
     * @param width   网格宽度（x方向格子数）
     * @param height  网格高度（y方向格子数）
     * @throws IOException 文件读取失败或格式错误
     */
    public FDSDataImporter(String csvPath, int width, int height) throws IOException {
        this.csvPath = csvPath;
        this.width = width;
        this.height = height;
        this.totalCells = width * height;
        readAllData();
    }

    /**
     * 读取整个CSV文件，缓存所有时间步的数据。
     */
    private void readAllData() throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(csvPath), StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("CSV文件为空");
        }

        // 跳过第一行（假设是标题行）
        int startLine = 0;
        if (lines.get(0).toLowerCase().contains("time") || lines.get(0).contains("Header")) {
            startLine = 1;
        }

        for (int i = startLine; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 2) continue; // 无效行

            try {
                double time = Double.parseDouble(parts[0].trim());
                // 检查数据点数是否匹配
                if (parts.length - 1 != totalCells) {
                    System.err.println("警告：第 " + (i+1) + " 行数据点数量 (" + (parts.length-1)
                            + ") 与预期 (" + totalCells + ") 不符，跳过该行。");
                    continue;
                }
                double[] rowData = new double[totalCells];
                for (int j = 1; j <= totalCells; j++) {
                    rowData[j-1] = Double.parseDouble(parts[j].trim());
                }
                timeSteps.add(time);
                dataCache.add(rowData);
            } catch (NumberFormatException e) {
                System.err.println("解析第 " + (i+1) + " 行时出错: " + e.getMessage());
                // 继续处理下一行
            }
        }

        if (timeSteps.isEmpty()) {
            throw new IOException("未读取到有效数据行");
        }
        System.out.println("成功读取 " + timeSteps.size() + " 个时间步，时间范围 "
                + timeSteps.get(0) + " ~ " + timeSteps.get(timeSteps.size()-1) + " 秒");
    }

    /**
     * 获取所有时间步（秒）。
     */
    public List<Double> getTimeSteps() {
        return Collections.unmodifiableList(timeSteps);
    }

    /**
     * 获取指定时间步的温度数据（二维数组，[x][y]）。
     *
     * @param timeInSeconds 目标时间（秒）
     * @return 二维温度数组，值可能为FDS的原始温度（K）
     * @throws IllegalArgumentException 如果时间不在范围内
     */
    public double[][] getTemperatureSlice(double timeInSeconds) {
        // 查找最近的时间步（可改为精确匹配或插值）
        int index = findNearestTimeIndex(timeInSeconds);
        if (index < 0) {
            throw new IllegalArgumentException("时间 " + timeInSeconds + " 不在有效范围内");
        }
        return flattenTo2D(dataCache.get(index));
    }

    /**
     * 获取指定时间步索引的温度数据。
     */
    public double[][] getTemperatureSlice(int stepIndex) {
        if (stepIndex < 0 || stepIndex >= dataCache.size()) {
            throw new IllegalArgumentException("时间步索引 " + stepIndex + " 超出范围 (0-" + (dataCache.size()-1) + ")");
        }
        return flattenTo2D(dataCache.get(stepIndex));
    }

    /**
     * 将扁平数据转换为二维数组（按行优先，x变化快）。
     */
    private double[][] flattenTo2D(double[] flatData) {
        double[][] grid = new double[width][height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                grid[x][y] = flatData[y * width + x];
            }
        }
        return grid;
    }

    /**
     * 查找最接近目标时间的索引。
     */
    private int findNearestTimeIndex(double target) {
        if (timeSteps.isEmpty()) return -1;
        double minDiff = Double.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < timeSteps.size(); i++) {
            double diff = Math.abs(timeSteps.get(i) - target);
            if (diff < minDiff) {
                minDiff = diff;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * 简单测试方法：读取文件并打印第一个时间步的部分数据。
     */
    public static void main(String[] args) {
        try {
            // 修改为您的实际CSV路径
            String csv = "E:/xuexi/MASON/test/test_devc.csv";
            FDSDataImporter importer = new FDSDataImporter(csv, 120, 80);
            System.out.println("时间步数量: " + importer.getTimeSteps().size());
            System.out.println("第一个时间: " + importer.getTimeSteps().get(0) + " 秒");

            double[][] data = importer.getTemperatureSlice(0.0);
            System.out.println("网格尺寸: " + data.length + " x " + data[0].length);
            System.out.println("中心点温度: " + data[60][40]); // 假设中心在(60,40)
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}