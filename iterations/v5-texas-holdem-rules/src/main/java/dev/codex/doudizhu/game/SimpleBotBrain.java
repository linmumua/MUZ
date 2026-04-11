package dev.codex.doudizhu.game;

import dev.codex.doudizhu.model.CardPattern;
import dev.codex.doudizhu.model.CardRank;
import dev.codex.doudizhu.model.DoudizhuCard;
import dev.codex.doudizhu.model.PatternType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class SimpleBotBrain {
    private static final Comparator<DoudizhuCard> ASCENDING =
        Comparator.comparing((DoudizhuCard card) -> card.rank().strength())
            .thenComparing(card -> card.suit().ordinal());

    private SimpleBotBrain() {
    }

    public static int chooseBid(List<DoudizhuCard> hand) {
        Map<CardRank, Integer> counts = counts(hand);
        long highCards = hand.stream().filter(card -> card.rank().strength() >= CardRank.ACE.strength()).count();
        long bombs = counts.values().stream().filter(count -> count == 4).count();
        boolean jokerBomb = counts.containsKey(CardRank.SMALL_JOKER) && counts.containsKey(CardRank.BIG_JOKER);
        int score = (int) highCards + (int) bombs * 2 + (jokerBomb ? 3 : 0);
        if (score >= 8) {
            return 3;
        }
        if (score >= 5) {
            return 2;
        }
        if (score >= 3) {
            return 1;
        }
        return 0;
    }

    public static List<DoudizhuCard> choosePlay(List<DoudizhuCard> hand, CardPattern target) {
        if (hand == null || hand.isEmpty()) {
            return List.of();
        }
        List<DoudizhuCard> sorted = new ArrayList<>(hand);
        sorted.sort(ASCENDING);
        Map<CardRank, Integer> counts = counts(sorted);

        if (target == null) {
            return leadMove(sorted, counts);
        }

        List<DoudizhuCard> sameType = switch (target.type()) {
            case SINGLE -> higherSingle(sorted, target.primaryRank());
            case PAIR -> higherGroup(sorted, counts, 2, target.primaryRank());
            case TRIPLE -> higherGroup(sorted, counts, 3, target.primaryRank());
            case TRIPLE_WITH_SINGLE -> higherTripleWithSingle(sorted, counts, target.primaryRank());
            case TRIPLE_WITH_PAIR -> higherTripleWithPair(sorted, counts, target.primaryRank());
            case FOUR_WITH_TWO_SINGLES -> higherFourWithSingles(sorted, counts, target.primaryRank());
            case FOUR_WITH_TWO_PAIRS -> higherFourWithPairs(sorted, counts, target.primaryRank());
            case BOMB -> higherBomb(sorted, counts, target.primaryRank());
            case JOKER_BOMB -> List.of();
            default -> List.of();
        };
        if (!sameType.isEmpty()) {
            return sameType;
        }
        if (!target.type().isBombFamily()) {
            List<DoudizhuCard> bomb = anyBomb(sorted, counts);
            if (!bomb.isEmpty()) {
                return bomb;
            }
        }
        return jokerBomb(sorted, counts);
    }

    private static List<DoudizhuCard> leadMove(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts) {
        CardRank singleRank = counts.entrySet().stream()
            .filter(entry -> entry.getValue() == 1)
            .map(Map.Entry::getKey)
            .min(Comparator.comparingInt(CardRank::strength))
            .orElse(null);
        if (singleRank != null) {
            return cardsOfRank(sorted, singleRank, 1);
        }

        CardRank pairRank = counts.entrySet().stream()
            .filter(entry -> entry.getValue() >= 2)
            .map(Map.Entry::getKey)
            .min(Comparator.comparingInt(CardRank::strength))
            .orElse(null);
        if (pairRank != null) {
            return cardsOfRank(sorted, pairRank, 2);
        }

        CardRank tripleRank = counts.entrySet().stream()
            .filter(entry -> entry.getValue() >= 3)
            .map(Map.Entry::getKey)
            .min(Comparator.comparingInt(CardRank::strength))
            .orElse(null);
        if (tripleRank != null) {
            return cardsOfRank(sorted, tripleRank, 3);
        }

        return List.of(sorted.getFirst());
    }

    private static List<DoudizhuCard> higherSingle(List<DoudizhuCard> sorted, CardRank current) {
        return sorted.stream()
            .filter(card -> card.rank().strength() > current.strength())
            .findFirst()
            .map(List::of)
            .orElse(List.of());
    }

    private static List<DoudizhuCard> higherGroup(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts, int amount, CardRank current) {
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() >= amount && entry.getKey().strength() > current.strength())
            .map(Map.Entry::getKey)
            .min(Comparator.comparingInt(CardRank::strength))
            .map(rank -> cardsOfRank(sorted, rank, amount))
            .orElse(List.of());
    }

    private static List<DoudizhuCard> higherTripleWithSingle(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts, CardRank current) {
        for (CardRank triple : ascendingRanks(counts, 3, current)) {
            List<DoudizhuCard> move = new ArrayList<>(cardsOfRank(sorted, triple, 3));
            DoudizhuCard attachment = sorted.stream().filter(card -> card.rank() != triple).findFirst().orElse(null);
            if (attachment != null) {
                move.add(attachment);
                return move;
            }
        }
        return List.of();
    }

    private static List<DoudizhuCard> higherTripleWithPair(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts, CardRank current) {
        for (CardRank triple : ascendingRanks(counts, 3, current)) {
            for (CardRank pair : ascendingRanks(counts, 2, null)) {
                if (pair == triple) {
                    continue;
                }
                List<DoudizhuCard> move = new ArrayList<>(cardsOfRank(sorted, triple, 3));
                move.addAll(cardsOfRank(sorted, pair, 2));
                return move;
            }
        }
        return List.of();
    }

    private static List<DoudizhuCard> higherFourWithSingles(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts, CardRank current) {
        for (CardRank bomb : ascendingRanks(counts, 4, current)) {
            List<DoudizhuCard> move = new ArrayList<>(cardsOfRank(sorted, bomb, 4));
            List<DoudizhuCard> attachments = sorted.stream().filter(card -> card.rank() != bomb).limit(2).toList();
            if (attachments.size() == 2) {
                move.addAll(attachments);
                return move;
            }
        }
        return List.of();
    }

    private static List<DoudizhuCard> higherFourWithPairs(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts, CardRank current) {
        for (CardRank bomb : ascendingRanks(counts, 4, current)) {
            List<CardRank> pairs = ascendingRanks(counts, 2, null).stream().filter(rank -> rank != bomb).limit(2).toList();
            if (pairs.size() == 2) {
                List<DoudizhuCard> move = new ArrayList<>(cardsOfRank(sorted, bomb, 4));
                move.addAll(cardsOfRank(sorted, pairs.get(0), 2));
                move.addAll(cardsOfRank(sorted, pairs.get(1), 2));
                return move;
            }
        }
        return List.of();
    }

    private static List<DoudizhuCard> higherBomb(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts, CardRank current) {
        for (CardRank bomb : ascendingRanks(counts, 4, current)) {
            return cardsOfRank(sorted, bomb, 4);
        }
        return jokerBomb(sorted, counts);
    }

    private static List<DoudizhuCard> anyBomb(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts) {
        for (CardRank bomb : ascendingRanks(counts, 4, null)) {
            return cardsOfRank(sorted, bomb, 4);
        }
        return jokerBomb(sorted, counts);
    }

    private static List<DoudizhuCard> jokerBomb(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts) {
        if (counts.containsKey(CardRank.SMALL_JOKER) && counts.containsKey(CardRank.BIG_JOKER)) {
            List<DoudizhuCard> move = new ArrayList<>(2);
            move.addAll(cardsOfRank(sorted, CardRank.SMALL_JOKER, 1));
            move.addAll(cardsOfRank(sorted, CardRank.BIG_JOKER, 1));
            return move;
        }
        return List.of();
    }

    private static List<CardRank> ascendingRanks(Map<CardRank, Integer> counts, int minCount, CardRank higherThan) {
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() >= minCount)
            .map(Map.Entry::getKey)
            .filter(rank -> higherThan == null || rank.strength() > higherThan.strength())
            .sorted(Comparator.comparingInt(CardRank::strength))
            .toList();
    }

    private static List<DoudizhuCard> cardsOfRank(List<DoudizhuCard> sorted, CardRank rank, int amount) {
        return sorted.stream().filter(card -> card.rank() == rank).limit(amount).toList();
    }

    private static Map<CardRank, Integer> counts(List<DoudizhuCard> hand) {
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        for (DoudizhuCard card : hand) {
            counts.merge(card.rank(), 1, Integer::sum);
        }
        return counts;
    }
}
