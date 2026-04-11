package dev.codex.doudizhu.model;

public enum CardSuit {
    CLUBS("♣"),
    DIAMONDS("♦"),
    HEARTS("♥"),
    SPADES("♠"),
    JOKER("");

    private final String symbol;

    CardSuit(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}

