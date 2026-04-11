package dev.codex.doudizhu.model;

import java.util.Comparator;

public enum CardRank {
    THREE("3", 3, true),
    FOUR("4", 4, true),
    FIVE("5", 5, true),
    SIX("6", 6, true),
    SEVEN("7", 7, true),
    EIGHT("8", 8, true),
    NINE("9", 9, true),
    TEN("10", 10, true),
    JACK("J", 11, true),
    QUEEN("Q", 12, true),
    KING("K", 13, true),
    ACE("A", 14, true),
    TWO("2", 15, false),
    SMALL_JOKER("小王", 16, false),
    BIG_JOKER("大王", 17, false);

    public static final Comparator<CardRank> NATURAL = Comparator.comparingInt(CardRank::strength);
    public static final Comparator<CardRank> DESCENDING = NATURAL.reversed();

    private final String label;
    private final int strength;
    private final boolean chainAllowed;

    CardRank(String label, int strength, boolean chainAllowed) {
        this.label = label;
        this.strength = strength;
        this.chainAllowed = chainAllowed;
    }

    public String label() {
        return label;
    }

    public int strength() {
        return strength;
    }

    public boolean chainAllowed() {
        return chainAllowed;
    }

    public boolean isJoker() {
        return this == SMALL_JOKER || this == BIG_JOKER;
    }
}

