package linmumua.doudizhu.room;

import java.util.Locale;

public enum TableLevel {
    LOW("low", "低级场", 100.0, true),
    MID("mid", "中级场", 1000.0, true),
    HIGH("high", "高级场", 10000.0, true),
    FUN("fun", "娱乐场", 0.0, false);

    private final String key;
    private final String defaultLabel;
    private final double defaultMultiplier;
    private final boolean defaultEconomyEnabled;

    TableLevel(String key, String defaultLabel, double defaultMultiplier, boolean defaultEconomyEnabled) {
        this.key = key;
        this.defaultLabel = defaultLabel;
        this.defaultMultiplier = defaultMultiplier;
        this.defaultEconomyEnabled = defaultEconomyEnabled;
    }

    public String key() {
        return key;
    }

    public String defaultLabel() {
        return defaultLabel;
    }

    public double defaultMultiplier() {
        return defaultMultiplier;
    }

    public boolean defaultEconomyEnabled() {
        return defaultEconomyEnabled;
    }

    public static TableLevel parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        for (TableLevel level : values()) {
            if (level.key.equals(normalized)) {
                return level;
            }
        }
        return null;
    }
}
