package linmumua.doudizhu.game;

import linmumua.doudizhu.config.MuzYamlConfig;

/** 牌桌道具运行期配置快照；读取只访问内存中的 MuzYamlConfig，不执行文件 I/O。 */
public record TableGadgetSettings(
    boolean enabled,
    double range,
    int cooldownTicks,
    int flightTicks,
    int waterTicks,
    int maxActive
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

    /**
     * 严格解析 interaction 段。缺键使用默认值；已存在但类型或范围不正确时直接抛出，
     * 防止配置错误被 getInt/getDouble 的 fallback 静默吞掉。
     */
    public static TableGadgetSettings load(MuzYamlConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("TableGadgetSettings 需要有效配置。");
        }
        boolean enabled = readBoolean(config, "hotbar-hud.interaction.enabled", DEFAULT_ENABLED);
        double range = readDouble(config, "hotbar-hud.interaction.range", DEFAULT_RANGE,
            MIN_RANGE, MAX_RANGE);
        int cooldownTicks = readInt(config, "hotbar-hud.interaction.cooldown-ticks", DEFAULT_COOLDOWN_TICKS,
            MIN_COOLDOWN_TICKS, MAX_COOLDOWN_TICKS);
        int flightTicks = readInt(config, "hotbar-hud.interaction.flight-ticks", DEFAULT_FLIGHT_TICKS,
            MIN_FLIGHT_TICKS, MAX_FLIGHT_TICKS);
        int waterTicks = readInt(config, "hotbar-hud.interaction.water-ticks", DEFAULT_WATER_TICKS,
            MIN_WATER_TICKS, MAX_WATER_TICKS);
        int maxActive = readInt(config, "hotbar-hud.interaction.max-active", DEFAULT_MAX_ACTIVE,
            MIN_MAX_ACTIVE, MAX_MAX_ACTIVE);
        return new TableGadgetSettings(enabled, range, cooldownTicks, flightTicks, waterTicks, maxActive);
    }

    private static boolean readBoolean(MuzYamlConfig config, String path, boolean fallback) {
        if (!config.contains(path)) {
            return fallback;
        }
        Object value = config.get(path);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException(path + " 必须是 true 或 false。");
    }

    private static int readInt(MuzYamlConfig config, String path, int fallback, int min, int max) {
        if (!config.contains(path)) {
            return fallback;
        }
        Object value = config.get(path);
        if (!(value instanceof Number number)
            || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException(path + " 必须是整数（范围 " + min + ".." + max + "）。");
        }
        int result = number.intValue();
        if (result < min || result > max) {
            throw new IllegalArgumentException(path + " 超出范围（" + min + ".." + max + "）：" + result);
        }
        return result;
    }

    private static double readDouble(MuzYamlConfig config, String path, double fallback, double min, double max) {
        if (!config.contains(path)) {
            return fallback;
        }
        Object value = config.get(path);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " 必须是数字（范围 " + min + ".." + max + "）。");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < min || result > max) {
            throw new IllegalArgumentException(path + " 超出范围（" + min + ".." + max + "）：" + result);
        }
        return result;
    }
}
