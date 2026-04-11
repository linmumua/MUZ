package dev.codex.doudizhu.model;

import java.util.ArrayList;
import java.util.List;

public final class MoveAdvisor {
    private MoveAdvisor() {
    }

    public static boolean hasAnyBeatingMove(List<DoudizhuCard> hand, CardPattern target) {
        if (hand == null || hand.isEmpty()) {
            return false;
        }
        if (target == null) {
            return true;
        }
        if (target.type() == PatternType.JOKER_BOMB) {
            return false;
        }

        if (canBeatWithCombination(hand, target.totalCards(), target)) {
            return true;
        }
        if (!target.type().isBombFamily() && canBeatWithCombination(hand, 4, target)) {
            return true;
        }
        return containsJokerBomb(hand);
    }

    private static boolean containsJokerBomb(List<DoudizhuCard> hand) {
        boolean small = hand.stream().anyMatch(card -> card.rank() == CardRank.SMALL_JOKER);
        boolean big = hand.stream().anyMatch(card -> card.rank() == CardRank.BIG_JOKER);
        return small && big;
    }

    private static boolean canBeatWithCombination(List<DoudizhuCard> hand, int size, CardPattern target) {
        if (size <= 0 || size > hand.size()) {
            return false;
        }
        return search(hand, target, size, 0, new ArrayList<>(size));
    }

    private static boolean search(
        List<DoudizhuCard> hand,
        CardPattern target,
        int targetSize,
        int startIndex,
        List<DoudizhuCard> chosen
    ) {
        if (chosen.size() == targetSize) {
            return PatternAnalyzer.analyze(chosen)
                .map(pattern -> pattern.canBeat(target))
                .orElse(false);
        }

        int remainingNeeded = targetSize - chosen.size();
        for (int index = startIndex; index <= hand.size() - remainingNeeded; index++) {
            chosen.add(hand.get(index));
            if (search(hand, target, targetSize, index + 1, chosen)) {
                return true;
            }
            chosen.remove(chosen.size() - 1);
        }
        return false;
    }
}

