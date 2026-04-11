package dev.codex.doudizhu.zhajinhua;

import dev.codex.doudizhu.model.CardRank;
import dev.codex.doudizhu.model.CardSuit;
import dev.codex.doudizhu.model.DoudizhuCard;
import java.util.List;

public record ZjhHand(List<DoudizhuCard> cards, ZjhHandType type, List<Integer> compareValues, int suitScore) {
    public String displayName() {
        return type.displayName();
    }

    public String cardsText() {
        return cards.stream().map(DoudizhuCard::displayLabel).reduce((left, right) -> left + " " + right).orElse("");
    }

    public static int rankValue(CardRank rank) {
        return switch (rank) {
            case ACE -> 14;
            case KING -> 13;
            case QUEEN -> 12;
            case JACK -> 11;
            case TEN -> 10;
            case NINE -> 9;
            case EIGHT -> 8;
            case SEVEN -> 7;
            case SIX -> 6;
            case FIVE -> 5;
            case FOUR -> 4;
            case THREE -> 3;
            case TWO -> 2;
            case SMALL_JOKER, BIG_JOKER -> 0;
        };
    }

    public static int suitValue(CardSuit suit) {
        return switch (suit) {
            case SPADES -> 4;
            case HEARTS -> 3;
            case CLUBS -> 2;
            case DIAMONDS -> 1;
            case JOKER -> 0;
        };
    }
}
