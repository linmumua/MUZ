package linmumua.doudizhu.model;

import java.util.Comparator;

public record DoudizhuCard(int id, CardRank rank, CardSuit suit) {
    public static final Comparator<DoudizhuCard> ORDER =
        Comparator.comparingInt((DoudizhuCard card) -> displayOrder(card.rank()))
            .reversed()
            .thenComparing(card -> card.suit().ordinal());

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

