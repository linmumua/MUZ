package dev.codex.doudizhu.zhajinhua;

public enum TexasStreet {
    PRE_FLOP("翻牌前"),
    FLOP("翻牌圈"),
    TURN("转牌圈"),
    RIVER("河牌圈"),
    SHOWDOWN("摊牌");

    private final String displayName;

    TexasStreet(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
