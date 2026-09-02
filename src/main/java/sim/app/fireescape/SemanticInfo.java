package sim.app.fireescape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SemanticInfo {
    public int floorCount;
    public List<Integer> floorSlabs = new ArrayList<>();
    public List<int[]> stairPositions = new ArrayList<>();

    public static SemanticInfo loadFromJson(String filePath) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(filePath)));
        SemanticInfo info = new SemanticInfo();

        // 提取楼层数
        Pattern floorPattern = Pattern.compile("\"floors\"\\s*:\\s*(\\d+)");
        Matcher fm = floorPattern.matcher(content);
        if (fm.find()) {
            info.floorCount = Integer.parseInt(fm.group(1));
        }

        // 提取楼板层 z 坐标
        Pattern slabPattern = Pattern.compile("\"floor_slabs\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher sm = slabPattern.matcher(content);
        if (sm.find()) {
            String[] nums = sm.group(1).split(",");
            for (String num : nums) {
                num = num.trim();
                if (!num.isEmpty()) {
                    info.floorSlabs.add(Integer.parseInt(num));
                }
            }
        }

        // 提取所有楼梯坐标
        Pattern stairPattern = Pattern.compile("\\[(\\d+),(\\d+)\\]");
        Matcher stm = stairPattern.matcher(content);
        while (stm.find()) {
            int x = Integer.parseInt(stm.group(1));
            int y = Integer.parseInt(stm.group(2));
            info.stairPositions.add(new int[]{x, y});
        }

        return info;
    }
}
