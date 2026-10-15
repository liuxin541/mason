package sim.app.fireescape;

import java.io.*;

public class BIMBridge {

    public static void runPythonBIMProcessor(String pythonPath, String scriptPath,
                                             String inputIfc, String outputDir)
            throws IOException, InterruptedException {

        // 构建命令行
        ProcessBuilder pb = new ProcessBuilder(
                pythonPath, scriptPath,
                "--input", inputIfc,
                "--output", outputDir
        );

        // 合并错误与输出流，方便调试
        pb.redirectErrorStream(true);

        System.out.println("[BIMBridge] 正在调用 Python 脚本...");
        Process process = pb.start();

        // 实时打印 Python 的输出日志
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[Python] " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("BIM 处理失败，Python 退出码: " + exitCode);
        }

        System.out.println("[BIMBridge] Python 处理成功完成！");
    }
}