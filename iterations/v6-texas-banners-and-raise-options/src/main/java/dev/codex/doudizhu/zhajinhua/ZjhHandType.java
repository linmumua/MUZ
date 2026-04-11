package dev.codex.doudizhu.zhajinhua;

public enum ZjhHandType {
    HIGH_CARD("单张", 1),
    PAIR("对子", 2),
    STRAIGHT("顺子", 3),
    FLUSH("金花", 4),
    STRAIGHT_FLUSH("顺金", 5),
    TRIPLE("豹子", 6),
    SPECIAL_235("235", 7);

    private final String displayName;
    private final int power;

    ZjhHandType(String displayName, int power) {
        this.displayName = displayName;
        this.power = power;
    }

    public String displayName() {
        return displayName;
    }

    public int power() {
        return power;
    }
}
