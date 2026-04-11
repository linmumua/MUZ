package dev.codex.doudizhu.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class DoudizhuDeck {
    private DoudizhuDeck() {
    }

    public static List<DoudizhuCard> shuffled(Random random) {
        List<DoudizhuCard> cards = new ArrayList<>(54);
        int id = 1;
        List<CardSuit> suits = List.of(CardSuit.SPADES, CardSuit.HEARTS, CardSuit.CLUBS, CardSuit.DIAMONDS);
        List<CardRank> ranks = List.of(
            CardRank.THREE, CardRank.FOUR, CardRank.FIVE, CardRank.SIX, CardRank.SEVEN, CardRank.EIGHT,
            CardRank.NINE, CardRank.TEN, CardRank.JACK, CardRank.QUEEN, CardRank.KING, CardRank.ACE, CardRank.TWO
        );
        for (CardRank rank : ranks) {
            for (CardSuit suit : suits) {
                cards.add(new DoudizhuCard(id++, rank, suit));
            }
        }
        cards.add(new DoudizhuCard(id++, CardRank.SMALL_JOKER, CardSuit.JOKER));
        cards.add(new DoudizhuCard(id, CardRank.BIG_JOKER, CardSuit.JOKER));
        Collections.shuffle(cards, random);
        return cards;
    }
}

