package linmumua.doudizhu.game;

import linmumua.doudizhu.model.CardPattern;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.model.PatternType;
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

    public record PlayContext(boolean leadTurn, int selfRemainingCards, int minOpponentRemainingCards) {
        public PlayContext {
            selfRemainingCards = Math.max(0, selfRemainingCards);
            minOpponentRemainingCards = minOpponentRemainingCards <= 0 ? Integer.MAX_VALUE : minOpponentRemainingCards;
        }

        public boolean closingWindow() {
            return selfRemainingCards <= 4;
        }

        public boolean opponentVeryLow() {
            return minOpponentRemainingCards <= 2;
        }

        public boolean opponentLow() {
            return minOpponentRemainingCards <= 3;
        }
    }

    public static int chooseBid(List<DoudizhuCard> hand) {
        int score = handStrengthScore(hand);
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

    public static boolean chooseDouble(List<DoudizhuCard> hand) {
        return handStrengthScore(hand) >= 5;
    }

    public static List<DoudizhuCard> choosePlay(List<DoudizhuCard> hand, CardPattern target) {
        int handSize = hand == null ? 0 : hand.size();
        return choosePlay(hand, target, new PlayContext(target == null, handSize, Integer.MAX_VALUE));
    }

    public static List<DoudizhuCard> choosePlay(List<DoudizhuCard> hand, CardPattern target, PlayContext context) {
        if (hand == null || hand.isEmpty()) {
            return List.of();
        }
        List<DoudizhuCard> sorted = new ArrayList<>(hand);
        sorted.sort(ASCENDING);
        Map<CardRank, Integer> counts = counts(sorted);
        PlayContext resolved = context == null ? new PlayContext(target == null, sorted.size(), Integer.MAX_VALUE) : context;

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
            case BOMB -> shouldSpendBomb(resolved, target, sorted.size()) ? higherBomb(sorted, counts, target.primaryRank()) : List.of();
            case JOKER_BOMB -> List.of();
            default -> List.of();
        };
        if (!sameType.isEmpty()) {
            return sameType;
        }

        if (!target.type().isBombFamily() && shouldSpendBomb(resolved, target, sorted.size())) {
            List<DoudizhuCard> bomb = anyBomb(sorted, counts);
            if (!bomb.isEmpty()) {
                return bomb;
            }
        }

        if (shouldSpendJokerBomb(resolved, target, sorted.size())) {
            return jokerBomb(sorted, counts);
        }
        return List.of();
    }

    private static List<DoudizhuCard> leadMove(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts) {
        CardRank singleRank = lowestRankWithExactCount(counts, 1);
        if (singleRank != null) {
            return cardsOfRank(sorted, singleRank, 1);
        }

        CardRank pairRank = lowestRankWithExactCount(counts, 2);
        if (pairRank != null) {
            return cardsOfRank(sorted, pairRank, 2);
        }

        CardRank tripleRank = lowestRankWithExactCount(counts, 3);
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
        return List.of();
    }

    private static List<DoudizhuCard> anyBomb(List<DoudizhuCard> sorted, Map<CardRank, Integer> counts) {
        for (CardRank bomb : ascendingRanks(counts, 4, null)) {
            return cardsOfRank(sorted, bomb, 4);
        }
        return List.of();
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

    private static boolean shouldSpendBomb(PlayContext context, CardPattern target, int handSize) {
        if (context == null || context.leadTurn()) {
            return false;
        }
        if (context.closingWindow() || context.opponentVeryLow()) {
            return true;
        }
        if (target != null && target.type() == PatternType.BOMB && (context.opponentLow() || handSize <= 6)) {
            return true;
        }
        return handSize <= 5 && context.opponentLow();
    }

    private static boolean shouldSpendJokerBomb(PlayContext context, CardPattern target, int handSize) {
        if (context == null || context.leadTurn()) {
            return false;
        }
        if (context.selfRemainingCards() <= 2 || context.minOpponentRemainingCards() <= 1) {
            return true;
        }
        if (target != null && target.type() == PatternType.BOMB && handSize <= 4) {
            return true;
        }
        return false;
    }

    private static CardRank lowestRankWithExactCount(Map<CardRank, Integer> counts, int exactCount) {
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() == exactCount)
            .map(Map.Entry::getKey)
            .min(Comparator.comparingInt(CardRank::strength))
            .orElse(null);
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

    private static int handStrengthScore(List<DoudizhuCard> hand) {
        Map<CardRank, Integer> counts = counts(hand);
        long highCards = hand.stream().filter(card -> card.rank().strength() >= CardRank.ACE.strength()).count();
        long bombs = counts.values().stream().filter(count -> count == 4).count();
        boolean jokerBomb = counts.containsKey(CardRank.SMALL_JOKER) && counts.containsKey(CardRank.BIG_JOKER);
        return (int) highCards + (int) bombs * 2 + (jokerBomb ? 3 : 0);
    }

    private static Map<CardRank, Integer> counts(List<DoudizhuCard> hand) {
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        for (DoudizhuCard card : hand) {
            counts.merge(card.rank(), 1, Integer::sum);
        }
        return counts;
    }
}
