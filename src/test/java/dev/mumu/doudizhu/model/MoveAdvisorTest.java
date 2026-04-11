package dev.mumu.doudizhu.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoveAdvisorTest {
    @Test
    void detectsBombAsResponseToStraight() {
        List<DoudizhuCard> hand = cards(
            CardRank.FIVE, CardRank.FIVE, CardRank.FIVE, CardRank.FIVE,
            CardRank.SEVEN, CardRank.EIGHT
        );
        CardPattern target = PatternAnalyzer.analyze(cards(
            CardRank.THREE, CardRank.FOUR, CardRank.FIVE, CardRank.SIX, CardRank.SEVEN
        )).orElseThrow();
        assertTrue(MoveAdvisor.hasAnyBeatingMove(hand, target));
    }

    @Test
    void returnsFalseWhenHandCannotBeatCurrentSingle() {
        List<DoudizhuCard> hand = cards(CardRank.THREE, CardRank.FOUR, CardRank.SIX);
        CardPattern target = PatternAnalyzer.analyze(cards(CardRank.KING)).orElseThrow();
        assertFalse(MoveAdvisor.hasAnyBeatingMove(hand, target));
    }

    @Test
    void detectsJokerBombAgainstBomb() {
        List<DoudizhuCard> hand = cards(CardRank.SMALL_JOKER, CardRank.BIG_JOKER, CardRank.FIVE);
        CardPattern target = PatternAnalyzer.analyze(cards(
            CardRank.ACE, CardRank.ACE, CardRank.ACE, CardRank.ACE
        )).orElseThrow();
        assertTrue(MoveAdvisor.hasAnyBeatingMove(hand, target));
    }

    private static List<DoudizhuCard> cards(CardRank... ranks) {
        List<DoudizhuCard> cards = new ArrayList<>();
        CardSuit[] suits = CardSuit.values();
        for (int index = 0; index < ranks.length; index++) {
            CardRank rank = ranks[index];
            CardSuit suit = rank.isJoker() ? CardSuit.JOKER : suits[index % 4];
            cards.add(new DoudizhuCard(index + 1, rank, suit));
        }
        return cards;
    }
}


