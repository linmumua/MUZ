package linmumua.doudizhu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * config.yml 里写 0 表示"这一项没调过，用源码里固化的默认值"。
 *
 * 这套语义只有三方对齐才成立：config.yml 出厂写 0、源码读取点带着调好的默认值、
 * 管理菜单显示的也是默认值而不是 0。任何一方掉队都会造成静默的视觉退化——
 * 最典型的是菜单显示 0，玩家按一下调整就从 0 起步，等于把调好的布局清掉，
 * 而且插件不会报任何错，只是牌桌看起来"不对了"。
 *
 * 少数项的 0 是它的真实取值（音量 0 是静音、倒计时 0 是关闭），
 * 这些必须按字面值读，否则玩家永远调不到 0 那一档。
 */
class ZeroMeansDefaultTest {
    @Test
    void zeroFallsBackToCodeDefault() {
        // 这是整套机制的地基：config 写 0 → 用源码默认值
        assertEquals(-0.52, DoudizhuPlugin.zeroMeansDefault(0.0, -0.52));
        assertEquals(30, DoudizhuPlugin.zeroMeansDefault(0, 30));
    }

    @Test
    void nonZeroOverridesCodeDefault() {
        // 玩家填了数字就必须生效，否则配置文件形同虚设
        assertEquals(0.8, DoudizhuPlugin.zeroMeansDefault(0.8, -0.52));
        assertEquals(45, DoudizhuPlugin.zeroMeansDefault(45, 30));
    }

    @Test
    void negativeValueIsNotTreatedAsUnset() {
        // 负值是一档有效设置，不是"没设置"。
        // 牌桌上大量偏移项本来就要往负方向调（垂直偏移 -0.04、座位信息 -0.22），
        // 若把负值当未设定弹回默认，玩家会发现往下调怎么都调不动。
        assertEquals(-0.9, DoudizhuPlugin.zeroMeansDefault(-0.9, 0.5));
        assertEquals(-3, DoudizhuPlugin.zeroMeansDefault(-3, 7));
    }

    @Test
    void settingsWhereZeroIsRealValueAreExcluded() {
        // 这些项的 0 是一档真实设置，不能被换成默认值。
        // 音量被弹回 0.55 意味着玩家关不掉声音；倒计时被弹回意味着关不掉倒计时；
        // 机器人最短思考被弹回意味着调不出"立刻出牌"。都是玩家能直接感知的功能失效。
        for (DoudizhuPlugin.AdminSetting setting : List.of(
            DoudizhuPlugin.AdminSetting.BGM_VOLUME,
            DoudizhuPlugin.AdminSetting.EFFECT_VOLUME,
            DoudizhuPlugin.AdminSetting.TURN_COUNTDOWN_SECONDS,
            DoudizhuPlugin.AdminSetting.BOT_DELAY_MIN
        )) {
            assertTrue(
                setting.zeroIsRealValue(),
                setting.path() + " 的 0 是真实取值，必须排除在'0=取默认'之外"
            );
        }
    }

    @Test
    void ordinaryRenderSettingsUseZeroMeansDefault() {
        // 反向守护：普通渲染项若被误加进排除名单，它在 config 里的 0
        // 就会被当成"真的要 0"，牌桌上对应的偏移/缩放会直接塌成 0。
        for (DoudizhuPlugin.AdminSetting setting : List.of(
            DoudizhuPlugin.AdminSetting.TABLE_SPAWN_OFFSET_Y,
            DoudizhuPlugin.AdminSetting.CHAIR_VISUAL_VERTICAL,
            DoudizhuPlugin.AdminSetting.CHAIR_DISTANCE
        )) {
            assertFalse(
                setting.zeroIsRealValue(),
                setting.path() + " 是普通可归零项，不该被排除"
            );
        }
    }

    @Test
    void zeroedConfigKeysAllHaveZeroAwareReadPoints() throws IOException {
        // config.yml 里归零了、源码却还用 yamlConfig().getDouble() 直读的项，
        // 读出来就是 0——牌桌上那一项直接塌成 0，插件不报错，只是"看起来坏了"。
        // 这是本次改造最容易漏的一类错，必须由测试守住。
        Map<String, Double> zeroed = parseZeroedConfigKeys();
        Map<String, String> readPoints = parseZeroAwareReadPoints();

        List<String> missing = new ArrayList<>();
        for (String path : zeroed.keySet()) {
            if (!readPoints.containsKey(path)) {
                missing.add(path);
            }
        }

        assertTrue(
            missing.isEmpty(),
            "这些项在 config.yml 里是 0，但源码没走 cfgDouble/cfgInt，读出来会是 0: " + missing
        );
    }

    @Test
    void zeroAwareReadPointsAllHaveZeroedConfigKeys() throws IOException {
        // 反向守护：源码按"0=取默认"读，config.yml 里却还写着出厂值。
        // 这种情况不会立刻出错，但配置文件失去了"我改过什么"的表意能力——
        // 玩家分不清哪些是自己调的，哪些是出厂值。
        Map<String, Double> zeroed = parseZeroedConfigKeys();
        Map<String, String> readPoints = parseZeroAwareReadPoints();

        List<String> notZeroed = new ArrayList<>();
        for (String path : readPoints.keySet()) {
            // 只查 config.yml 里确实存在的键；隐藏配置（如 integration.mahjong.*）不在文件里，跳过
            if (configContainsKey(path) && !zeroed.containsKey(path)) {
                notZeroed.add(path);
            }
        }

        assertTrue(
            notZeroed.isEmpty(),
            "这些项源码按 0=取默认读，config.yml 里却没归零: " + notZeroed
        );
    }

    @Test
    void annotatedDefaultsMatchSourceDefaults() throws IOException {
        // 行尾注释里的"默认 x"是玩家唯一能看到的默认值说明。
        // 标错了玩家会按错的基准去调，而且插件不会有任何提示。
        Map<String, Double> annotated = parseZeroedConfigKeys();
        Map<String, String> readPoints = parseZeroAwareReadPoints();

        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, Double> entry : annotated.entrySet()) {
            String source = readPoints.get(entry.getKey());
            if (source == null) {
                continue;
            }
            double sourceDefault = Double.parseDouble(source.replace("f", ""));
            if (Math.abs(sourceDefault - entry.getValue()) > 1.0e-9) {
                mismatched.add(entry.getKey() + "（注释 " + entry.getValue() + " ≠ 源码 " + sourceDefault + "）");
            }
        }

        assertTrue(mismatched.isEmpty(), "行尾注释标的默认值和源码不一致: " + mismatched);
    }

    /**
     * 解析 config.yml，取出所有"值为 0 且行尾标了默认值"的项。
     *
     * @return 路径到注释里标注的默认值
     * @throws IOException 读不到 config.yml
     */
    private Map<String, Double> parseZeroedConfigKeys() throws IOException {
        Pattern zeroLine = Pattern.compile("^([A-Za-z0-9_-]+):\\s*0\\s+#\\s*默认\\s*(-?[0-9.]+)\\s*$");
        Map<String, Double> zeroed = new LinkedHashMap<>();
        for (PathedLine line : readConfigLines()) {
            Matcher m = zeroLine.matcher(line.trimmed);
            if (m.matches()) {
                zeroed.put(line.path, Double.parseDouble(m.group(2)));
            }
        }
        return zeroed;
    }

    /**
     * config.yml 里是否存在这个键（不论值是多少）。
     *
     * @param path 配置路径
     * @return 存在返回 true
     * @throws IOException 读不到 config.yml
     */
    private boolean configContainsKey(String path) throws IOException {
        for (PathedLine line : readConfigLines()) {
            if (line.path.equals(path) && line.trimmed.matches("^[A-Za-z0-9_-]+:\\s*\\S.*$")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从源码里抓出所有 cfgDouble/cfgInt 读取点及其源码默认值。
     *
     * @return 路径到源码里写的默认值字面量
     * @throws IOException 读不到源码
     */
    private Map<String, String> parseZeroAwareReadPoints() throws IOException {
        String src = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java"),
            StandardCharsets.UTF_8
        );
        Map<String, String> found = new LinkedHashMap<>();
        // 先把所有读取点的路径收全：只锚定 cfgXxx("路径", 这一段，
        // 不管默认值是字面量、变量还是嵌套调用（如 action-delay-max-ticks 要从 legacy 键回退）
        Matcher any = Pattern.compile("cfg(?:Double|Int)\\(\"([^\"]+)\",").matcher(src);
        while (any.find()) {
            found.put(any.group(1), null);
        }
        // 再单独补上默认值是纯数字字面量的那些，供注释一致性比对使用
        Matcher literal = Pattern.compile("cfg(?:Double|Int)\\(\"([^\"]+)\",\\s*(-?[0-9.]+f?)\\)").matcher(src);
        while (literal.find()) {
            found.put(literal.group(1), literal.group(2));
        }
        return found;
    }

    /**
     * 逐行读 config.yml，并算出每行的完整点分路径。
     *
     * @return 带路径的行列表
     * @throws IOException 读不到 config.yml
     */
    private List<PathedLine> readConfigLines() throws IOException {
        List<PathedLine> result = new ArrayList<>();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (in == null) {
                throw new IOException("classpath 里没有 config.yml");
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String[]> stack = new ArrayList<>();
            for (String raw : content.split("\n")) {
                String trimmed = raw.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
                    continue;
                }
                Matcher key = Pattern.compile("^([A-Za-z0-9_-]+):(.*)$").matcher(trimmed);
                if (!key.matches()) {
                    continue;
                }
                int indent = raw.length() - raw.stripLeading().length();
                while (!stack.isEmpty() && Integer.parseInt(stack.get(stack.size() - 1)[1]) >= indent) {
                    stack.remove(stack.size() - 1);
                }
                StringBuilder path = new StringBuilder();
                for (String[] parent : stack) {
                    path.append(parent[0]).append('.');
                }
                path.append(key.group(1));
                if (key.group(2).trim().isEmpty()) {
                    stack.add(new String[] {key.group(1), String.valueOf(indent)});
                    continue;
                }
                result.add(new PathedLine(path.toString(), trimmed));
            }
        }
        return result;
    }

    /**
     * config.yml 的一行，带算好的完整路径。
     *
     * @param path 点分路径
     * @param trimmed 去掉首尾空白的原始行
     */
    private record PathedLine(String path, String trimmed) {
    }
}
