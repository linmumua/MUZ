package linmumua.doudizhu.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合法出牌候选生成器。
 * 只负责"能出哪些"，不做任何策略判断；要出哪一手由 SimpleBotBrain 打分决定。
 * 生成的每一手都会过一遍 PatternAnalyzer 校验，跟牌还会额外用 canBeat 复核，
 * 避免把非法牌型交给 GameTable。GameTable 的 applyMoveResolution 不做压制校验，
 * 所以这一层必须自己把住。
 */
public final class MoveGenerator {
    /** 带牌的搭子候选上限。带牌组合会指数膨胀，规则型 bot 只取少量"最不心疼"的搭配。 */
    private static final int MAX_KICKER_CHOICES = 2;

    private MoveGenerator() {
    }

    /**
     * 首出时的全部候选牌型。
     * @param hand 当前手牌
     * @return 去重后的候选，每一手都是合法牌型
     */
    public static List<List<DoudizhuCard>> leadCandidates(List<DoudizhuCard> hand) {
        return candidates(hand, null);
    }

    /**
     * 跟牌时能压过 target 的全部候选。
     * @param hand 当前手牌
     * @param target 上家牌型，为 null 时等同首出
     * @return 去重后的候选，每一手都能压过 target
     */
    public static List<List<DoudizhuCard>> followCandidates(List<DoudizhuCard> hand, CardPattern target) {
        if (target == null) {
            return leadCandidates(hand);
        }
        return candidates(hand, target);
    }

    private static List<List<DoudizhuCard>> candidates(List<DoudizhuCard> hand, CardPattern target) {
        if (hand == null || hand.isEmpty()) {
            return List.of();
        }
        List<DoudizhuCard> sorted = new ArrayList<>(hand);
        sorted.sort(Comparator.comparingInt((DoudizhuCard card) -> card.rank().strength())
            .thenComparingInt(card -> card.suit().ordinal()));
        Map<CardRank, Integer> counts = counts(sorted);
        List<List<DoudizhuCard>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 目标牌型决定只需要生成哪几类。首出时 target 为 null，全类都生成。
        PatternType only = target == null ? null : target.type();
        if (only == null || only == PatternType.SINGLE) {
            addGroups(result, seen, sorted, counts, 1, target);
        }
        if (only == null || only == PatternType.PAIR) {
            addGroups(result, seen, sorted, counts, 2, target);
        }
        if (only == null || only == PatternType.TRIPLE) {
            addGroups(result, seen, sorted, counts, 3, target);
        }
        if (only == null || only == PatternType.TRIPLE_WITH_SINGLE) {
            addAttached(result, seen, sorted, counts, 3, 1, 1, target);
        }
        if (only == null || only == PatternType.TRIPLE_WITH_PAIR) {
            addAttached(result, seen, sorted, counts, 3, 2, 1, target);
        }
        if (only == null || only == PatternType.STRAIGHT) {
            addChains(result, seen, sorted, counts, 1, 5, target);
        }
        if (only == null || only == PatternType.PAIR_STRAIGHT) {
            addChains(result, seen, sorted, counts, 2, 3, target);
        }
        if (only == null || only == PatternType.AIRPLANE) {
            addChains(result, seen, sorted, counts, 3, 2, target);
        }
        if (only == null || only == PatternType.AIRPLANE_WITH_SINGLES) {
            addAirplaneWithKickers(result, seen, sorted, counts, 1, target);
        }
        if (only == null || only == PatternType.AIRPLANE_WITH_PAIRS) {
            addAirplaneWithKickers(result, seen, sorted, counts, 2, target);
        }
        if (only == null || only == PatternType.FOUR_WITH_TWO_SINGLES) {
            addAttached(result, seen, sorted, counts, 4, 1, 2, target);
        }
        if (only == null || only == PatternType.FOUR_WITH_TWO_PAIRS) {
            addAttached(result, seen, sorted, counts, 4, 2, 2, target);
        }
        // 炸弹和王炸能压任何非炸牌型，所以不受 only 限制，始终生成。
        addGroups(result, seen, sorted, counts, 4, target);
        addJokerBomb(result, seen, sorted, counts, target);
        return result;
    }

    /** 单张、对子、三张、炸弹：同点数取 amount 张。 */
    private static void addGroups(
        List<List<DoudizhuCard>> result,
        Set<String> seen,
        List<DoudizhuCard> sorted,
        Map<CardRank, Integer> counts,
        int amount,
        CardPattern target
    ) {
        for (Map.Entry<CardRank, Integer> entry : counts.entrySet()) {
            if (entry.getValue() < amount) {
                continue;
            }
            accept(result, seen, cardsOfRank(sorted, entry.getKey(), amount), target);
        }
    }

    /**
     * 顺子、连对、飞机（不带牌）。
     * @param repeat 每个点数要几张：1=单顺，2=连对，3=飞机
     * @param minChain 最短链长
     */
    private static void addChains(
        List<List<DoudizhuCard>> result,
        Set<String> seen,
        List<DoudizhuCard> sorted,
        Map<CardRank, Integer> counts,
        int repeat,
        int minChain,
        CardPattern target
    ) {
        List<CardRank> usable = chainUsableRanks(counts, repeat);
        if (usable.size() < minChain) {
            return;
        }
        // 跟牌时链长必须和上家完全一致，首出则枚举所有长度。
        int fixedChain = target == null ? -1 : target.chainLength();
        for (int start = 0; start < usable.size(); start++) {
            for (int length = minChain; start + length <= usable.size(); length++) {
                List<CardRank> window = usable.subList(start, start + length);
                // 先判连续再判长度：断开后再拉长也不可能连上，直接换起点。
                if (!isConsecutive(window)) {
                    break;
                }
                if (fixedChain > 0 && length != fixedChain) {
                    continue;
                }
                List<DoudizhuCard> move = new ArrayList<>(length * repeat);
                for (CardRank rank : window) {
                    move.addAll(cardsOfRank(sorted, rank, repeat));
                }
                accept(result, seen, move, target);
            }
        }
    }

    /** 三带一、三带二、四带二单、四带二对。 */
    private static void addAttached(
        List<List<DoudizhuCard>> result,
        Set<String> seen,
        List<DoudizhuCard> sorted,
        Map<CardRank, Integer> counts,
        int coreAmount,
        int kickerAmount,
        int kickerGroups,
        CardPattern target
    ) {
        for (Map.Entry<CardRank, Integer> entry : counts.entrySet()) {
            if (entry.getValue() < coreAmount) {
                continue;
            }
            CardRank core = entry.getKey();
            List<List<DoudizhuCard>> kickerSets = pickKickers(
                sorted,
                counts,
                Set.of(core),
                kickerAmount,
                kickerGroups
            );
            for (List<DoudizhuCard> kickers : kickerSets) {
                List<DoudizhuCard> move = new ArrayList<>(cardsOfRank(sorted, core, coreAmount));
                move.addAll(kickers);
                accept(result, seen, move, target);
            }
        }
    }

    /** 飞机带翅膀：连续三张 + 等量的单张或对子搭子。 */
    private static void addAirplaneWithKickers(
        List<List<DoudizhuCard>> result,
        Set<String> seen,
        List<DoudizhuCard> sorted,
        Map<CardRank, Integer> counts,
        int kickerAmount,
        CardPattern target
    ) {
        List<CardRank> triples = chainUsableRanks(counts, 3);
        if (triples.size() < 2) {
            return;
        }
        int fixedChain = target == null ? -1 : target.chainLength();
        for (int start = 0; start < triples.size(); start++) {
            for (int length = 2; start + length <= triples.size(); length++) {
                List<CardRank> window = triples.subList(start, start + length);
                if (!isConsecutive(window)) {
                    break;
                }
                if (fixedChain > 0 && length != fixedChain) {
                    continue;
                }
                List<List<DoudizhuCard>> kickerSets = pickKickers(
                    sorted,
                    counts,
                    Set.copyOf(window),
                    kickerAmount,
                    length
                );
                for (List<DoudizhuCard> kickers : kickerSets) {
                    List<DoudizhuCard> move = new ArrayList<>();
                    for (CardRank rank : window) {
                        move.addAll(cardsOfRank(sorted, rank, 3));
                    }
                    move.addAll(kickers);
                    accept(result, seen, move, target);
                }
            }
        }
    }

    private static void addJokerBomb(
        List<List<DoudizhuCard>> result,
        Set<String> seen,
        List<DoudizhuCard> sorted,
        Map<CardRank, Integer> counts,
        CardPattern target
    ) {
        if (!counts.containsKey(CardRank.SMALL_JOKER) || !counts.containsKey(CardRank.BIG_JOKER)) {
            return;
        }
        List<DoudizhuCard> move = new ArrayList<>(2);
        move.addAll(cardsOfRank(sorted, CardRank.SMALL_JOKER, 1));
        move.addAll(cardsOfRank(sorted, CardRank.BIG_JOKER, 1));
        accept(result, seen, move, target);
    }

    /**
     * 挑带牌搭子。
     * 按"最不心疼"排序后只取前几种：孤张优先，其次小牌，尽量不动炸弹和成对的牌。
     * 全组合枚举会指数膨胀，规则型 bot 没必要。
     * @param excluded 已被主牌占用的点数
     * @param kickerAmount 每个搭子几张：1=单，2=对
     * @param kickerGroups 需要几个搭子
     * @return 若干套搭子，凑不齐时返回空
     */
    private static List<List<DoudizhuCard>> pickKickers(
        List<DoudizhuCard> sorted,
        Map<CardRank, Integer> counts,
        Set<CardRank> excluded,
        int kickerAmount,
        int kickerGroups
    ) {
        List<CardRank> pool = counts.entrySet().stream()
            .filter(entry -> !excluded.contains(entry.getKey()))
            .filter(entry -> entry.getValue() >= kickerAmount)
            // 王不适合当单搭子：拆散王炸的代价远大于收益。
            .filter(entry -> !(kickerAmount == 1 && entry.getKey().isJoker() && hasJokerBomb(counts)))
            .map(Map.Entry::getKey)
            .sorted(Comparator
                .comparingInt((CardRank rank) -> kickerRegret(counts, rank, kickerAmount))
                .thenComparingInt(CardRank::strength))
            .toList();
        if (pool.size() < kickerGroups) {
            return List.of();
        }

        List<List<DoudizhuCard>> sets = new ArrayList<>(MAX_KICKER_CHOICES);
        // 第一套取最不心疼的若干个；第二套整体后移一位，给打分器一点选择余地。
        for (int offset = 0; offset < MAX_KICKER_CHOICES; offset++) {
            if (pool.size() < kickerGroups + offset) {
                break;
            }
            List<DoudizhuCard> kickers = new ArrayList<>(kickerAmount * kickerGroups);
            for (int index = 0; index < kickerGroups; index++) {
                kickers.addAll(cardsOfRank(sorted, pool.get(index + offset), kickerAmount));
            }
            sets.add(kickers);
        }
        return sets;
    }

    /**
     * 拆这张牌当搭子有多心疼，越小越舍得出。
     * 炸弹最心疼，其次是三张和对子，孤张最舍得。
     */
    private static int kickerRegret(Map<CardRank, Integer> counts, CardRank rank, int kickerAmount) {
        int owned = counts.getOrDefault(rank, 0);
        if (owned == 4) {
            return 100;
        }
        if (kickerAmount == 1 && owned == 3) {
            return 40;
        }
        if (kickerAmount == 1 && owned == 2) {
            return 20;
        }
        if (kickerAmount == 2 && owned == 3) {
            return 30;
        }
        if (rank.isJoker()) {
            return 50;
        }
        // 2 和 A 是控牌，不轻易当搭子送走。
        if (rank == CardRank.TWO) {
            return 15;
        }
        if (rank == CardRank.ACE) {
            return 8;
        }
        return 0;
    }

    /**
     * 收候选。
     * 这里是唯一的合法性闸口：先要能被解析成牌型，跟牌时还要真的压得过。
     * 带牌搭子是贪心挑的，可能凑出非法形状（比如三带一挑到一个对子），必须靠这里挡掉。
     */
    private static void accept(
        List<List<DoudizhuCard>> result,
        Set<String> seen,
        List<DoudizhuCard> move,
        CardPattern target
    ) {
        if (move == null || move.isEmpty()) {
            return;
        }
        CardPattern pattern = PatternAnalyzer.analyze(move).orElse(null);
        if (pattern == null) {
            return;
        }
        if (target != null && !pattern.canBeat(target)) {
            return;
        }
        String key = move.stream()
            .map(card -> Integer.toString(card.id()))
            .sorted()
            .reduce((left, right) -> left + "," + right)
            .orElse("");
        if (!seen.add(key)) {
            return;
        }
        result.add(List.copyOf(move));
    }

    /** 能进顺子的点数，按大小升序。2 和王不能进顺子。 */
    private static List<CardRank> chainUsableRanks(Map<CardRank, Integer> counts, int repeat) {
        return counts.entrySet().stream()
            .filter(entry -> entry.getValue() >= repeat)
            .map(Map.Entry::getKey)
            .filter(CardRank::chainAllowed)
            .sorted(CardRank.NATURAL)
            .toList();
    }

    private static boolean isConsecutive(List<CardRank> ranks) {
        for (int index = 1; index < ranks.size(); index++) {
            if (ranks.get(index).strength() != ranks.get(index - 1).strength() + 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasJokerBomb(Map<CardRank, Integer> counts) {
        return counts.containsKey(CardRank.SMALL_JOKER) && counts.containsKey(CardRank.BIG_JOKER);
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
