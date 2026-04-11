package dev.codex.doudizhu.model;

public record CardPattern(PatternType type, CardRank primaryRank, int totalCards, int chainLength) {
    public boolean canBeat(CardPattern other) {
        if (type == PatternType.JOKER_BOMB) {
            return true;
        }
        if (other.type == PatternType.JOKER_BOMB) {
            return false;
        }
        if (type == PatternType.BOMB && !other.type.isBombFamily()) {
            return true;
        }
        if (!type.isBombFamily() && other.type.isBombFamily()) {
            return false;
        }
        if (type != other.type) {
            return false;
        }
        if (totalCards != other.totalCards || chainLength != other.chainLength) {
            return false;
        }
        return primaryRank.strength() > other.primaryRank.strength();
    }

    public String displayName() {
        return type.displayName();
    }
}

