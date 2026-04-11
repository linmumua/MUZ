package dev.codex.doudizhu.zhajinhua;

public enum TexasHandType {
    HIGH_CARD("高牌", 1),
    ONE_PAIR("一对", 2),
    TWO_PAIR("两对", 3),
    THREE_OF_A_KIND("三条", 4),
    STRAIGHT("顺子", 5),
    FLUSH("同花", 6),
    FULL_HOUSE("葫芦", 7),
    FOUR_OF_A_KIND("四条", 8),
    STRAIGHT_FLUSH("同花顺", 9),
    ROYAL_FLUSH("皇家同花顺", 10);

    private final String displayName;
    private final int power;

    TexasHandType(String displayName, int power) {
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
