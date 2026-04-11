package dev.mumu.doudizhu.zhajinhua;

import dev.mumu.doudizhu.model.CardRank;
import dev.mumu.doudizhu.model.DoudizhuCard;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ZjhEvaluator {
    private ZjhEvaluator() {
    }

    public static ZjhHand evaluate(List<DoudizhuCard> cards) {
        if (cards == null || cards.size() != 3) {
            throw new IllegalArgumentException("炸金花必须恰好 3 张牌。");
        }
        List<DoudizhuCard> sorted = new ArrayList<>(cards);
        sorted.sort(Comparator.comparingInt((DoudizhuCard card) -> ZjhHand.rankValue(card.rank())).thenComparingInt(card -> ZjhHand.suitValue(card.suit())));

        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        for (DoudizhuCard card : sorted) {
            counts.merge(card.rank(), 1, Integer::sum);
        }

        boolean flush = sorted.stream().map(DoudizhuCard::suit).distinct().count() == 1;
        List<Integer> values = sorted.stream().map(card -> ZjhHand.rankValue(card.rank())).toList();
        boolean special235 = values.equals(List.of(2, 3, 5));
        boolean triple = counts.size() == 1;
        boolean straight = isStraight(values);
        boolean pair = counts.size() == 2;
        int suitScore = sorted.stream().mapToInt(card -> ZjhHand.suitValue(card.suit())).sum();

        if (special235) {
            return new ZjhHand(sorted, ZjhHandType.SPECIAL_235, List.of(5, 3, 2), suitScore);
        }
        if (triple) {
            int value = ZjhHand.rankValue(sorted.get(0).rank());
            return new ZjhHand(sorted, ZjhHandType.TRIPLE, List.of(value), suitScore);
        }
        if (straight && flush) {
            return new ZjhHand(sorted, ZjhHandType.STRAIGHT_FLUSH, straightCompare(values), suitScore);
        }
        if (flush) {
            return new ZjhHand(sorted, ZjhHandType.FLUSH, descending(values), suitScore);
        }
        if (straight) {
            return new ZjhHand(sorted, ZjhHandType.STRAIGHT, straightCompare(values), suitScore);
        }
        if (pair) {
            CardRank pairRank = counts.entrySet().stream().filter(entry -> entry.getValue() == 2).findFirst().orElseThrow().getKey();
            CardRank kicker = counts.entrySet().stream().filter(entry -> entry.getValue() == 1).findFirst().orElseThrow().getKey();
            return new ZjhHand(sorted, ZjhHandType.PAIR, List.of(ZjhHand.rankValue(pairRank), ZjhHand.rankValue(kicker)), suitScore);
        }
        return new ZjhHand(sorted, ZjhHandType.HIGH_CARD, descending(values), suitScore);
    }

    public static int compare(ZjhHand left, ZjhHand right) {
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
        return Integer.compare(left.suitScore(), right.suitScore());
    }

    private static boolean isStraight(List<Integer> values) {
        if (values.equals(List.of(2, 3, 14))) {
            return true;
        }
        return values.get(1) == values.get(0) + 1 && values.get(2) == values.get(1) + 1;
    }

    private static List<Integer> straightCompare(List<Integer> values) {
        if (values.equals(List.of(2, 3, 14))) {
            return List.of(3, 2, 1);
        }
        return List.of(values.get(2), values.get(1), values.get(0));
    }

    private static List<Integer> descending(List<Integer> values) {
        List<Integer> reversed = new ArrayList<>(values);
        reversed.sort(Comparator.reverseOrder());
        return reversed;
    }
}

