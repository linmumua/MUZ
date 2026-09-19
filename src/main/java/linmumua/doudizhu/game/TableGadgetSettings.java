package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import linmumua.doudizhu.config.MuzYamlConfig;

/** 牌桌道具运行期配置快照；读取只访问内存中的 MuzYamlConfig，不执行文件 I/O。 */
public record TableGadgetSettings(
    boolean enabled,
    double range,
    int cooldownTicks,
    int flightTicks,
    int waterTicks,
    int maxActive,
    Gui gui,
    Panel panel,
    Voices voices
) {
    public static final boolean DEFAULT_ENABLED = true;
    public static final double DEFAULT_RANGE = 6.0;
    public static final int DEFAULT_COOLDOWN_TICKS = 40;
    public static final int DEFAULT_FLIGHT_TICKS = 10;
    public static final int DEFAULT_WATER_TICKS = 16;
    public static final int DEFAULT_MAX_ACTIVE = 32;

    public static final double MIN_RANGE = 1.0;
    public static final double MAX_RANGE = 6.0;
    public static final int MIN_COOLDOWN_TICKS = 1;
    public static final int MAX_COOLDOWN_TICKS = 200;
    public static final int MIN_FLIGHT_TICKS = 1;
    public static final int MAX_FLIGHT_TICKS = 40;
    public static final int MIN_WATER_TICKS = 1;
    public static final int MAX_WATER_TICKS = 60;
    public static final int MIN_MAX_ACTIVE = 1;
    public static final int MAX_MAX_ACTIVE = 64;

    public static final String DEFAULT_GUI_TITLE = "桌内道具";
    public static final String DEFAULT_GUI_BUBBLE_NAME = "语音";
    public static final boolean DEFAULT_PANEL_ENABLED = true;
    public static final double DEFAULT_PANEL_FORWARD_OFFSET = 1.5;
    public static final double DEFAULT_PANEL_VERTICAL_OFFSET = -0.35;
    public static final double DEFAULT_PANEL_WIDTH = 1.8;
    public static final double DEFAULT_PANEL_ROW_HEIGHT = 0.32;
    public static final double DEFAULT_PANEL_ROW_GAP = 0.08;
    public static final int DEFAULT_PANEL_MAX_ENTRIES = 8;
    public static final int DEFAULT_PANEL_HOVER_INTERVAL_TICKS = 2;
    public static final int DEFAULT_PANEL_VOICE_COOLDOWN_TICKS = 40;
    public static final boolean DEFAULT_VOICES_ENABLED = true;
    public static final float DEFAULT_VOICE_VOLUME = 1.0F;
    public static final float DEFAULT_VOICE_PITCH = 1.0F;

    private static final String NEW_INTERACTION = "table-gadgets.interaction.";
    private static final String OLD_INTERACTION = "hotbar-hud.interaction.";
    private static final List<Voice> DEFAULT_VOICE_ENTRIES = List.of(
        new Voice("wait", "快点吧，我等到花儿都谢了", "doudizhu.v1", "table", DEFAULT_VOICE_VOLUME, DEFAULT_VOICE_PITCH),
        new Voice("cooperate", "和你合作真是太愉快了", "doudizhu.v2", "table", DEFAULT_VOICE_VOLUME, DEFAULT_VOICE_PITCH),
        new Voice("praise", "你的牌打得也太好了", "doudizhu.v3", "table", DEFAULT_VOICE_VOLUME, DEFAULT_VOICE_PITCH),
        new Voice("urge", "{sender} 催促 {target} 尽快出牌", "doudizhu.v4", "current-turn",
            DEFAULT_VOICE_VOLUME, DEFAULT_VOICE_PITCH)
    );

    /**
     * 严格解析 table-gadgets。新 interaction 键是唯一真源；新键缺失时逐字段回退旧键。
     * 已存在但类型或范围不正确时直接抛出，防止配置错误被 fallback 静默吞掉。
     */
    public static TableGadgetSettings load(MuzYamlConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("TableGadgetSettings 需要有效配置。");
        }
        boolean enabled = readBoolean(config, NEW_INTERACTION + "enabled", OLD_INTERACTION + "enabled", DEFAULT_ENABLED);
        double range = readDouble(config, NEW_INTERACTION + "range", OLD_INTERACTION + "range", DEFAULT_RANGE,
            MIN_RANGE, MAX_RANGE);
        int cooldownTicks = readInt(config, NEW_INTERACTION + "cooldown-ticks", OLD_INTERACTION + "cooldown-ticks",
            DEFAULT_COOLDOWN_TICKS, MIN_COOLDOWN_TICKS, MAX_COOLDOWN_TICKS);
        int flightTicks = readInt(config, NEW_INTERACTION + "flight-ticks", OLD_INTERACTION + "flight-ticks",
            DEFAULT_FLIGHT_TICKS, MIN_FLIGHT_TICKS, MAX_FLIGHT_TICKS);
        int waterTicks = readInt(config, NEW_INTERACTION + "water-ticks", OLD_INTERACTION + "water-ticks",
            DEFAULT_WATER_TICKS, MIN_WATER_TICKS, MAX_WATER_TICKS);
        int maxActive = readInt(config, NEW_INTERACTION + "max-active", OLD_INTERACTION + "max-active",
            DEFAULT_MAX_ACTIVE, MIN_MAX_ACTIVE, MAX_MAX_ACTIVE);

        Gui gui = new Gui(
            readString(config, "table-gadgets.gui.title", DEFAULT_GUI_TITLE, 1, 64),
            readString(config, "table-gadgets.gui.bubble-name", DEFAULT_GUI_BUBBLE_NAME, 1, 32)
        );
        Panel panel = new Panel(
            readBoolean(config, "table-gadgets.panel.enabled", DEFAULT_PANEL_ENABLED),
            readDouble(config, "table-gadgets.panel.forward-offset", null,
                DEFAULT_PANEL_FORWARD_OFFSET, 0.5, 4.0),
            readDouble(config, "table-gadgets.panel.vertical-offset", null,
                DEFAULT_PANEL_VERTICAL_OFFSET, -2.0, 2.0),
            readDouble(config, "table-gadgets.panel.width", null, DEFAULT_PANEL_WIDTH, 0.4, 4.0),
            readDouble(config, "table-gadgets.panel.row-height", null,
                DEFAULT_PANEL_ROW_HEIGHT, 0.1, 1.0),
            readDouble(config, "table-gadgets.panel.row-gap", null, DEFAULT_PANEL_ROW_GAP, 0.0, 1.0),
            readInt(config, "table-gadgets.panel.max-entries", DEFAULT_PANEL_MAX_ENTRIES, 1, 32),
            readInt(config, "table-gadgets.panel.hover-interval-ticks",
                DEFAULT_PANEL_HOVER_INTERVAL_TICKS, 1, 20),
            readInt(config, "table-gadgets.panel.voice-cooldown-ticks",
                DEFAULT_PANEL_VOICE_COOLDOWN_TICKS, 1, 200)
        );
        return new TableGadgetSettings(enabled, range, cooldownTicks, flightTicks, waterTicks, maxActive,
            gui, panel, readVoices(config));
    }

    /** 兼容现有道具服务调用的交互模型视图。 */
    public Interaction interaction() {
        return new Interaction(enabled, range, cooldownTicks, flightTicks, waterTicks, maxActive);
    }

    /** 保留旧的六参数构造方式，新增模型使用默认配置。 */
    public TableGadgetSettings(boolean enabled, double range, int cooldownTicks, int flightTicks,
                               int waterTicks, int maxActive) {
        this(enabled, range, cooldownTicks, flightTicks, waterTicks, maxActive,
            new Gui(DEFAULT_GUI_TITLE, DEFAULT_GUI_BUBBLE_NAME),
            new Panel(DEFAULT_PANEL_ENABLED, DEFAULT_PANEL_FORWARD_OFFSET, DEFAULT_PANEL_VERTICAL_OFFSET,
                DEFAULT_PANEL_WIDTH, DEFAULT_PANEL_ROW_HEIGHT, DEFAULT_PANEL_ROW_GAP,
                DEFAULT_PANEL_MAX_ENTRIES, DEFAULT_PANEL_HOVER_INTERVAL_TICKS,
                DEFAULT_PANEL_VOICE_COOLDOWN_TICKS),
            new Voices(DEFAULT_VOICES_ENABLED, DEFAULT_VOICE_ENTRIES));
    }

    private static Voices readVoices(MuzYamlConfig config) {
        boolean enabled = readBoolean(config, "table-gadgets.voices.enabled", DEFAULT_VOICES_ENABLED);
        String path = "table-gadgets.voices.entries";
        if (!hasKey(config, path)) {
            return new Voices(enabled, DEFAULT_VOICE_ENTRIES);
        }
        Object raw = config.get(path);
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(path + " 必须是列表。");
        }
        if (list.isEmpty() || list.size() > 32) {
            throw new IllegalArgumentException(path + " 条目数量必须在 1..32 范围内。");
        }
        List<Voice> entries = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            Object value = list.get(index);
            String itemPath = path + "[" + index + "]";
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(itemPath + " 必须是映射。");
            }
            entries.add(new Voice(
                readMapString(map, itemPath + ".id", "id", 1, 32),
                readMapString(map, itemPath + ".text", "text", 1, 128),
                readMapString(map, itemPath + ".sound", "sound", 1, 128),
                readTarget(map, itemPath + ".target"),
                readMapFloat(map, itemPath + ".volume", "volume", 0.0F, 2.0F),
                readMapFloat(map, itemPath + ".pitch", "pitch", 0.5F, 2.0F)
            ));
        }
        return new Voices(enabled, entries);
    }

    private static String readTarget(Map<?, ?> map, String path) {
        String target = readMapString(map, path, "target", 1, 32);
        if (!target.equals("actor") && !target.equals("target")
            && !target.equals("current-turn") && !target.equals("table")) {
            throw new IllegalArgumentException(path + " 必须是 actor、target、current-turn 或 table。");
        }
        return target;
    }

    private static String readString(MuzYamlConfig config, String path, String fallback, int min, int max) {
        if (!hasKey(config, path)) {
            return fallback;
        }
        Object value = config.get(path);
        return validateString(value, path, min, max);
    }

    private static String readMapString(Map<?, ?> map, String path, String key, int min, int max) {
        return validateString(map.get(key), path, min, max);
    }

    private static String validateString(Object value, String path, int min, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() < min || text.length() > max) {
            throw new IllegalArgumentException(path + " 必须是长度 " + min + ".." + max + " 的非空字符串。");
        }
        return text;
    }

    private static float readMapFloat(Map<?, ?> map, String path, String key, float min, float max) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " 必须是数字（范围 " + min + ".." + max + "）。");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < min || result > max) {
            throw new IllegalArgumentException(path + " 超出范围（" + min + ".." + max + "）：" + result);
        }
        return (float) result;
    }

    private static boolean readBoolean(MuzYamlConfig config, String path, boolean fallback) {
        return readBoolean(config, path, null, fallback);
    }

    private static boolean readBoolean(MuzYamlConfig config, String path, String fallbackPath, boolean fallback) {
        String actualPath = hasKey(config, path) ? path : fallbackPath;
        return actualPath == null || !hasKey(config, actualPath) ? fallback : readBooleanValue(config, actualPath);
    }

    private static boolean readBooleanValue(MuzYamlConfig config, String path) {
        Object value = config.get(path);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException(path + " 必须是 true 或 false。");
    }

    private static int readInt(MuzYamlConfig config, String path, int fallback, int min, int max) {
        if (!hasKey(config, path)) {
            return fallback;
        }
        Object value = config.get(path);
        if (!(value instanceof Number number)
            || number.doubleValue() != Math.rint(number.doubleValue())
            || number.doubleValue() < Integer.MIN_VALUE || number.doubleValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(path + " 必须是整数（范围 " + min + ".." + max + "）。");
        }
        int result = number.intValue();
        if (result < min || result > max) {
            throw new IllegalArgumentException(path + " 超出范围（" + min + ".." + max + "）：" + result);
        }
        return result;
    }

    private static int readInt(MuzYamlConfig config, String path, String fallbackPath, int fallback, int min, int max) {
        String actualPath = hasKey(config, path) ? path : fallbackPath;
        if (actualPath == null || !hasKey(config, actualPath)) {
            return fallback;
        }
        return readInt(config, actualPath, fallback, min, max);
    }

    private static double readDouble(MuzYamlConfig config, String path, String fallbackPath, double fallback,
                                     double min, double max) {
        String actualPath = hasKey(config, path) ? path : fallbackPath;
        if (actualPath == null || !hasKey(config, actualPath)) {
            return fallback;
        }
        Object value = config.get(actualPath);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(actualPath + " 必须是数字（范围 " + min + ".." + max + "）。");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < min || result > max) {
            throw new IllegalArgumentException(actualPath + " 超出范围（" + min + ".." + max + "）：" + result);
        }
        return result;
    }

    /** contains() 将显式 null 视为缺失；这里按原始映射区分“缺键”和“键值为 null”。 */
    private static boolean hasKey(MuzYamlConfig config, String path) {
        Object current = config.rawRoot();
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return false;
            }
            current = map.get(part);
        }
        return true;
    }

    public record Interaction(boolean enabled, double range, int cooldownTicks, int flightTicks, int waterTicks,
                              int maxActive) { }

    public record Gui(String title, String bubbleName) {
        public String bubble() {
            return bubbleName;
        }
    }

    public record Panel(
        boolean enabled,
        double forwardOffset,
        double verticalOffset,
        double width,
        double rowHeight,
        double rowGap,
        int maxEntries,
        int hoverIntervalTicks,
        int voiceCooldownTicks
    ) { }

    public record Voice(String id, String text, String sound, String target, float volume, float pitch) { }

    public record Voices(boolean enabled, List<Voice> entries) {
        public Voices {
            entries = List.copyOf(entries);
        }

        public List<Voice> voices() {
            return entries;
        }
    }
}
