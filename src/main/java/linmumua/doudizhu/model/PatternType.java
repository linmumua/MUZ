package linmumua.doudizhu.model;

public enum PatternType {
    SINGLE("单牌", false),
    PAIR("对子", false),
    TRIPLE("三张", false),
    TRIPLE_WITH_SINGLE("三带一", false),
    TRIPLE_WITH_PAIR("三带二", false),
    FOUR_WITH_TWO_SINGLES("四带二", false),
    FOUR_WITH_TWO_PAIRS("四带两对", false),
    PAIR_STRAIGHT("连对", false),
    STRAIGHT("顺子", false),
    AIRPLANE("飞机", false),
    AIRPLANE_WITH_SINGLES("飞机带单翼", false),
    AIRPLANE_WITH_PAIRS("飞机带双翼", false),
    BOMB("炸弹", true),
    JOKER_BOMB("王炸", true);

    private final String displayName;
    private final boolean bombFamily;

    PatternType(String displayName, boolean bombFamily) {
        this.displayName = displayName;
        this.bombFamily = bombFamily;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isBombFamily() {
        return bombFamily;
    }
}


