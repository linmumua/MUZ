package dev.codex.doudizhu.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class PatternAnalyzer {
    private PatternAnalyzer() {
    }

    public static Optional<CardPattern> analyze(Collection<DoudizhuCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return Optional.empty();
        }

        Map<CardRank, Integer> counts = counts(cards);
        int size = cards.size();

        if (size == 1) {
            return Optional.of(pattern(PatternType.SINGLE, highestRank(counts), size, 1));
        }
        if (size == 2) {
            if (counts.size() == 2 && counts.containsKey(CardRank.SMALL_JOKER) && counts.containsKey(CardRank.BIG_JOKER)) {
                return Optional.of(pattern(PatternType.JOKER_BOMB, CardRank.BIG_JOKER, size, 1));
            }
            if (counts.size() == 1) {
                return Optional.of(pattern(PatternType.PAIR, highestRank(counts), size, 1));
            }
            return Optional.empty();
        }
        if (size == 3 && counts.size() == 1) {
            return Optional.of(pattern(PatternType.TRIPLE, highestRank(counts), size, 1));
        }
        if (size == 4) {
            if (counts.size() == 1) {
                return Optional.of(pattern(PatternType.BOMB, highestRank(counts), size, 1));
            }
            CardRank tripleRank = rankWithCount(counts, 3);
            if (tripleRank != null && counts.size() == 2) {
                return Optional.of(pattern(PatternType.TRIPLE_WITH_SINGLE, tripleRank, size, 1));
            }
            return Optional.empty();
        }
        if (size == 5) {
            CardRank tripleRank = rankWithCount(counts, 3);
            if (tripleRank != null && counts.values().stream().sorted().toList().equals(List.of(2, 3))) {
                return Optional.of(pattern(PatternType.TRIPLE_WITH_PAIR, tripleRank, size, 1));
            }
        }

        Optional<CardPattern> fourWithTwo = analyzeFourWithTwo(counts, size);
        if (fourWithTwo.isPresent()) {
            return fourWithTwo;
        }

        Optional<CardPattern> pairStraight = analyzePairStraight(counts, size);
        if (pairStraight.isPresent()) {
            return pairStraight;
        }

        Optional<CardPattern> straight = analyzeStraight(counts, size);
        if (straight.isPresent()) {
            return straight;
        }

        Optional<CardPattern> airplane = analyzeAirplane(counts, size);
        if (airplane.isPresent()) {
            return airplane;
        }

        Optional<CardPattern> airplaneWithSingles = analyzeAirplaneWithSingles(counts, size);
        if (airplaneWithSingles.isPresent()) {
            return airplaneWithSingles;
        }

        return analyzeAirplaneWithPairs(counts, size);
    }

    private static Map<CardRank, Integer> counts(Collection<DoudizhuCard> cards) {
        return cards.stream()
            .collect(Collectors.toMap(
                DoudizhuCard::rank,
                card -> 1,
                Integer::sum,
                LinkedHashMap::new
            ));
    }

    private static CardPattern pattern(PatternType type, CardRank primaryRank, int totalCards, int chainLength) {
        return new CardPattern(type, primaryRank, totalCards, chainLength);
    }

    private static CardRank highestRank(Map<CardRank, Integer> counts) {
        return counts.keySet().stream().max(CardRank.NATURAL).orElseThrow();
    }

    private static CardRank rankWithCount(Map<CardRank, Integer> counts, int count) {
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() == count)
            .map(Map.Entry::getKey)
            .max(CardRank.NATURAL)
            .orElse(null);
    }

    private static Optional<CardPattern> analyzeFourWithTwo(Map<CardRank, Integer> counts, int size) {
        CardRank bombRank = rankWithCount(counts, 4);
        if (bombRank == null) {
            return Optional.empty();
        }
        if (size == 6) {
            if (counts.getOrDefault(CardRank.SMALL_JOKER, 0) == 1 && counts.getOrDefault(CardRank.BIG_JOKER, 0) == 1) {
                return Optional.empty();
            }
            return Optional.of(pattern(PatternType.FOUR_WITH_TWO_SINGLES, bombRank, size, 1));
        }
        if (size == 8) {
            List<Integer> leftovers = counts.entrySet().stream()
                .filter(entry -> entry.getKey() != bombRank)
                .map(Map.Entry::getValue)
                .sorted()
                .toList();
            if (leftovers.equals(List.of(2, 2))) {
                return Optional.of(pattern(PatternType.FOUR_WITH_TWO_PAIRS, bombRank, size, 1));
            }
        }
        return Optional.empty();
    }

    private static Optional<CardPattern> analyzePairStraight(Map<CardRank, Integer> counts, int size) {
        if (size < 6 || size % 2 != 0) {
            return Optional.empty();
        }
        if (counts.values().stream().anyMatch(value -> value != 2)) {
            return Optional.empty();
        }
        List<CardRank> ranks = sortedRanks(counts.keySet());
        if (!allChainAllowed(ranks) || !isConsecutive(ranks)) {
            return Optional.empty();
        }
        return Optional.of(pattern(PatternType.PAIR_STRAIGHT, ranks.getLast(), size, ranks.size()));
    }

    private static Optional<CardPattern> analyzeStraight(Map<CardRank, Integer> counts, int size) {
        if (size < 5) {
            return Optional.empty();
        }
        if (counts.values().stream().anyMatch(value -> value != 1)) {
            return Optional.empty();
        }
        List<CardRank> ranks = sortedRanks(counts.keySet());
        if (!allChainAllowed(ranks) || !isConsecutive(ranks)) {
            return Optional.empty();
        }
        return Optional.of(pattern(PatternType.STRAIGHT, ranks.getLast(), size, ranks.size()));
    }

    private static Optional<CardPattern> analyzeAirplane(Map<CardRank, Integer> counts, int size) {
        if (size < 6 || size % 3 != 0) {
            return Optional.empty();
        }
        int chainLength = size / 3;
        Optional<List<CardRank>> chain = exactTripletChain(counts, chainLength);
        return chain.map(ranks -> pattern(PatternType.AIRPLANE, ranks.getLast(), size, chainLength));
    }

    private static Optional<CardPattern> analyzeAirplaneWithSingles(Map<CardRank, Integer> counts, int size) {
        if (size < 8 || size % 4 != 0) {
            return Optional.empty();
        }
        int chainLength = size / 4;
        Optional<List<CardRank>> chain = exactTripletChain(counts, chainLength);
        if (chain.isEmpty()) {
            return Optional.empty();
        }

        List<CardRank> leftoverRanks = counts.entrySet().stream()
            .filter(entry -> !chain.get().contains(entry.getKey()))
            .map(Map.Entry::getKey)
            .toList();
        int leftoverCount = counts.entrySet().stream()
            .filter(entry -> !chain.get().contains(entry.getKey()))
            .mapToInt(Map.Entry::getValue)
            .sum();
        if (leftoverCount != chainLength) {
            return Optional.empty();
        }
        if (leftoverRanks.contains(CardRank.SMALL_JOKER) && leftoverRanks.contains(CardRank.BIG_JOKER)) {
            return Optional.empty();
        }
        return Optional.of(pattern(PatternType.AIRPLANE_WITH_SINGLES, chain.get().getLast(), size, chainLength));
    }

    private static Optional<CardPattern> analyzeAirplaneWithPairs(Map<CardRank, Integer> counts, int size) {
        if (size < 10 || size % 5 != 0) {
            return Optional.empty();
        }
        int chainLength = size / 5;
        Optional<List<CardRank>> chain = exactTripletChain(counts, chainLength);
        if (chain.isEmpty()) {
            return Optional.empty();
        }

        List<Integer> leftovers = counts.entrySet().stream()
            .filter(entry -> !chain.get().contains(entry.getKey()))
            .map(Map.Entry::getValue)
            .sorted()
            .toList();
        List<Integer> expected = new ArrayList<>(chainLength);
        for (int index = 0; index < chainLength; index++) {
            expected.add(2);
        }
        if (!leftovers.equals(expected)) {
            return Optional.empty();
        }
        return Optional.of(pattern(PatternType.AIRPLANE_WITH_PAIRS, chain.get().getLast(), size, chainLength));
    }

    private static Optional<List<CardRank>> exactTripletChain(Map<CardRank, Integer> counts, int chainLength) {
        List<CardRank> triplets = counts.entrySet().stream()
            .filter(entry -> entry.getValue() == 3)
            .map(Map.Entry::getKey)
            .sorted(CardRank.NATURAL)
            .toList();
        if (triplets.size() != chainLength) {
            return Optional.empty();
        }
        if (!allChainAllowed(triplets) || !isConsecutive(triplets)) {
            return Optional.empty();
        }
        return Optional.of(triplets);
    }

    private static List<CardRank> sortedRanks(Collection<CardRank> ranks) {
        return ranks.stream().sorted(CardRank.NATURAL).toList();
    }

    private static boolean allChainAllowed(List<CardRank> ranks) {
        return ranks.stream().allMatch(CardRank::chainAllowed);
    }

    private static boolean isConsecutive(List<CardRank> ranks) {
        if (ranks.isEmpty()) {
            return false;
        }
        List<CardRank> sorted = ranks.stream()
            .sorted(Comparator.comparingInt(CardRank::strength))
            .toList();
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index).strength() != sorted.get(index - 1).strength() + 1) {
                return false;
            }
        }
        return true;
    }
}

