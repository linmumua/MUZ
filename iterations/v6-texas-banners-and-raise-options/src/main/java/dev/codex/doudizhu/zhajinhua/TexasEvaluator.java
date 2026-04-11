package dev.codex.doudizhu.zhajinhua;

import dev.codex.doudizhu.model.CardRank;
import dev.codex.doudizhu.model.CardSuit;
import dev.codex.doudizhu.model.DoudizhuCard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TexasEvaluator {
    private TexasEvaluator() {
    }

    public static TexasHand evaluateBest(List<DoudizhuCard> cards) {
        if (cards == null || cards.size() < 5) {
            throw new IllegalArgumentException("德州扑克至少需要 5 张牌来评估。");
        }
        TexasHand best = null;
        int size = cards.size();
        for (int a = 0; a < size - 4; a++) {
            for (int b = a + 1; b < size - 3; b++) {
                for (int c = b + 1; c < size - 2; c++) {
                    for (int d = c + 1; d < size - 1; d++) {
                        for (int e = d + 1; e < size; e++) {
                            TexasHand candidate = evaluateFive(List.of(cards.get(a), cards.get(b), cards.get(c), cards.get(d), cards.get(e)));
                            if (best == null || compare(candidate, best) > 0) {
                                best = candidate;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    public static int compare(TexasHand left, TexasHand right) {
        int typeCompare = Integer.compare(left.type().power(), right.type().power());
        if (typeCompare != 0) {
            return typeCompare;
        }
        int max = Math.max(left.compareValues().size(), right.compareValues().size());
        for (int index = 0; index < max; index++) {
            int leftValue = index < left.compareValues().size() ? left.compareValues().get(index) : 0;
            int rightValue = index < right.compareValues().size() ? right.compareValues().get(index) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static TexasHand evaluateFive(List<DoudizhuCard> cards) {
        List<DoudizhuCard> sorted = new ArrayList<>(cards);
        sorted.sort(Comparator.comparingInt((DoudizhuCard card) -> rankValue(card.rank())).reversed());
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (DoudizhuCard card : sorted) {
            counts.merge(rankValue(card.rank()), 1, Integer::sum);
        }

        boolean flush = sorted.stream().map(DoudizhuCard::suit).distinct().count() == 1;
        List<Integer> straightValues = straightValues(sorted);
        boolean straight = !straightValues.isEmpty();

        if (flush && straight) {
            if (straightValues.getFirst() == 14 && straightValues.get(1) == 13) {
                return new TexasHand(TexasHandType.ROYAL_FLUSH, straightValues, sorted);
            }
            return new TexasHand(TexasHandType.STRAIGHT_FLUSH, straightValues, sorted);
        }

        int four = rankWithCount(counts, 4);
        if (four > 0) {
            int kicker = counts.keySet().stream().filter(value -> value != four).findFirst().orElse(0);
            return new TexasHand(TexasHandType.FOUR_OF_A_KIND, List.of(four, kicker), sorted);
        }

        int three = rankWithCount(counts, 3);
        int pair = highestPairExcept(counts, three);
        if (three > 0 && pair > 0) {
            return new TexasHand(TexasHandType.FULL_HOUSE, List.of(three, pair), sorted);
        }

        if (flush) {
            return new TexasHand(TexasHandType.FLUSH, sorted.stream().map(card -> rankValue(card.rank())).toList(), sorted);
        }

        if (straight) {
            return new TexasHand(TexasHandType.STRAIGHT, straightValues, sorted);
        }

        if (three > 0) {
            List<Integer> kickers = counts.keySet().stream().filter(value -> value != three).sorted(Comparator.reverseOrder()).toList();
            return new TexasHand(TexasHandType.THREE_OF_A_KIND, List.of(three, kickers.get(0), kickers.get(1)), sorted);
        }

        List<Integer> pairs = counts.entrySet().stream()
            .filter(entry -> entry.getValue() == 2)
            .map(Map.Entry::getKey)
            .sorted(Comparator.reverseOrder())
            .toList();
        if (pairs.size() >= 2) {
            int kicker = counts.keySet().stream().filter(value -> value != pairs.get(0) && value != pairs.get(1)).findFirst().orElse(0);
            return new TexasHand(TexasHandType.TWO_PAIR, List.of(pairs.get(0), pairs.get(1), kicker), sorted);
        }
        if (pairs.size() == 1) {
            List<Integer> kickers = counts.keySet().stream().filter(value -> value != pairs.get(0)).sorted(Comparator.reverseOrder()).toList();
            return new TexasHand(TexasHandType.ONE_PAIR, List.of(pairs.get(0), kickers.get(0), kickers.get(1), kickers.get(2)), sorted);
        }
        return new TexasHand(TexasHandType.HIGH_CARD, sorted.stream().map(card -> rankValue(card.rank())).toList(), sorted);
    }

    private static List<Integer> straightValues(List<DoudizhuCard> cards) {
        List<Integer> values = cards.stream()
            .map(card -> rankValue(card.rank()))
            .distinct()
            .sorted(Comparator.reverseOrder())
            .toList();
        if (values.size() != 5) {
            return List.of();
        }
        boolean consecutive = true;
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1) - 1 != values.get(index)) {
                consecutive = false;
                break;
            }
        }
        if (consecutive) {
            return values;
        }
        if (values.equals(List.of(14, 5, 4, 3, 2))) {
            return List.of(5, 4, 3, 2, 1);
        }
        return List.of();
    }

    private static int rankWithCount(Map<Integer, Integer> counts, int target) {
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() == target)
            .map(Map.Entry::getKey)
            .max(Integer::compareTo)
            .orElse(0);
    }

    private static int highestPairExcept(Map<Integer, Integer> counts, int except) {
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() >= 2 && entry.getKey() != except)
            .map(Map.Entry::getKey)
            .max(Integer::compareTo)
            .orElse(0);
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
}
