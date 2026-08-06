package linmumua.doudizhu.model;

import java.util.Comparator;

public record DoudizhuCard(int id, CardRank rank, CardSuit suit) {
    public static final Comparator<DoudizhuCard> ORDER =
        Comparator.comparingInt((DoudizhuCard card) -> displayOrder(card.rank()))
            .reversed()
            .thenComparing(card -> card.suit().ordinal());

    /**
     * 已打出的牌摆出来时用的牌序：从小到大（3 &lt; 4 &lt; ... &lt; 2 &lt; 小王 &lt; 大王）。
     *
     * <p>和 {@link #ORDER}（手里那副牌用的从大到小）方向相反，各有各的场合。
     * 桌面中央的公共出牌区和屏幕上的出牌 HUD 必须共用这一份：
     * 各自排一次的话，同一手牌在两个地方的顺序会不一样，看着像出错了。
     */
    public static final Comparator<DoudizhuCard> DISPLAY_ORDER =
        Comparator.comparing(DoudizhuCard::rank, CardRank.NATURAL);

    private static int displayOrder(CardRank rank) {
        return switch (rank) {
            case THREE -> 3;
            case FOUR -> 4;
            case FIVE -> 5;
            case SIX -> 6;
            case SEVEN -> 7;
            case EIGHT -> 8;
            case NINE -> 9;
            case TEN -> 10;
            case JACK -> 11;
            case QUEEN -> 12;
            case KING -> 13;
            case ACE -> 14;
            case TWO -> 15;
            case BIG_JOKER -> 16;
            case SMALL_JOKER -> 17;
        };
    }

    public String displayLabel() {
        if (rank.isJoker()) {
            return rank.label();
        }
        return suit.symbol() + rank.label();
    }
}

