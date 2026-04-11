package dev.mumu.doudizhu.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PatternAnalyzerTest {
    @Test
    void identifiesStraight() {
        CardPattern pattern = analyze(
            CardRank.THREE, CardRank.FOUR, CardRank.FIVE, CardRank.SIX, CardRank.SEVEN
        );
        assertEquals(PatternType.STRAIGHT, pattern.type());
        assertEquals(CardRank.SEVEN, pattern.primaryRank());
    }

    @Test
    void identifiesBombAndBeatsStraight() {
        CardPattern bomb = analyze(
            CardRank.KING, CardRank.KING, CardRank.KING, CardRank.KING
        );
        CardPattern straight = analyze(
            CardRank.SIX, CardRank.SEVEN, CardRank.EIGHT, CardRank.NINE, CardRank.TEN
        );
        assertEquals(PatternType.BOMB, bomb.type());
        assertTrue(bomb.canBeat(straight));
    }

    @Test
    void identifiesAirplaneWithSingleWingsUsingPairAttachments() {
        CardPattern pattern = analyze(
            CardRank.THREE, CardRank.THREE, CardRank.THREE,
            CardRank.FOUR, CardRank.FOUR, CardRank.FOUR,
            CardRank.SEVEN, CardRank.SEVEN
        );
        assertEquals(PatternType.AIRPLANE_WITH_SINGLES, pattern.type());
        assertEquals(CardRank.FOUR, pattern.primaryRank());
    }

    @Test
    void rejectsFourWithTwoSingleWhenWingIsDoubleJoker() {
        Optional<CardPattern> pattern = PatternAnalyzer.analyze(cards(
            CardRank.FIVE, CardRank.FIVE, CardRank.FIVE, CardRank.FIVE,
            CardRank.SMALL_JOKER, CardRank.BIG_JOKER
        ));
        assertTrue(pattern.isEmpty());
    }

    @Test
    void pairMustMatchTypeAndLengthToBeat() {
        CardPattern pairOfJacks = analyze(CardRank.JACK, CardRank.JACK);
        CardPattern pairOfQueens = analyze(CardRank.QUEEN, CardRank.QUEEN);
        CardPattern triple = analyze(CardRank.THREE, CardRank.THREE, CardRank.THREE);
        assertTrue(pairOfQueens.canBeat(pairOfJacks));
        assertFalse(pairOfJacks.canBeat(triple));
    }

    private static CardPattern analyze(CardRank... ranks) {
        return PatternAnalyzer.analyze(cards(ranks)).orElseThrow();
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

