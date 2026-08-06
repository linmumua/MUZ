package linmumua.doudizhu.game;

import linmumua.doudizhu.model.CardPattern;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.model.MoveGenerator;
import linmumua.doudizhu.model.PatternAnalyzer;
import linmumua.doudizhu.model.PatternType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 规则型出牌大脑。
 *
 * 思路参考公开的规则型斗地主 AI 常见做法（RLCard 的 doudizhu 规则 baseline、
 * songbaoming/DouDiZhu 的拆牌与跟牌优先级、longhuihu 的"牌力 - 手数惩罚"评分）：
 *   1. 用 MoveGenerator 枚举合法候选；
 *   2. 对每个候选模拟出掉，把剩余手牌贪心拆牌，估算"还要几手才能走完"；
 *   3. 用 剩余牌力 - 手数惩罚 + 局势修正 打分，选最高的一手；
 *   4. 炸弹和王炸默认保留，只在收官或对手快跑完时才放开。
 *
 * 定位是"会一点"，不追求最优：拆牌是贪心不回溯，带牌搭子只取少量候选。
 */
public final class SimpleBotBrain {
    private static final Comparator<DoudizhuCard> ASCENDING =
        Comparator.comparing((DoudizhuCard card) -> card.rank().strength())
            .thenComparing(card -> card.suit().ordinal());

    private SimpleBotBrain() {
    }

    public record PlayContext(
        boolean leadTurn,
        int selfRemainingCards,
        int minOpponentRemainingCards,
        boolean teammateLead
    ) {
        public PlayContext {
            selfRemainingCards = Math.max(0, selfRemainingCards);
            minOpponentRemainingCards = minOpponentRemainingCards <= 0 ? Integer.MAX_VALUE : minOpponentRemainingCards;
        }

        public PlayContext(boolean leadTurn, int selfRemainingCards, int minOpponentRemainingCards) {
            this(leadTurn, selfRemainingCards, minOpponentRemainingCards, false);
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

    /**
     * 叫分。
     * 打分权重参考公开的规则型实现：大牌和炸弹权重最高，成型结构次之，孤张多则压低。
     */
    public static int chooseBid(List<DoudizhuCard> hand) {
        int score = handStrengthScore(hand);
        if (score >= 18) {
            return 3;
        }
        if (score >= 13) {
            return 2;
        }
        if (score >= 8) {
            return 1;
        }
        return 0;
    }

    public static boolean chooseDouble(List<DoudizhuCard> hand) {
        return handStrengthScore(hand) >= 13;
    }

    public static List<DoudizhuCard> choosePlay(List<DoudizhuCard> hand, CardPattern target) {
        int handSize = hand == null ? 0 : hand.size();
        return choosePlay(hand, target, new PlayContext(target == null, handSize, Integer.MAX_VALUE));
    }

    /**
     * 选这一手出什么。
     * @param target 上家牌型，null 表示自己首出
     * @return 要出的牌；空表示不要
     */
    public static List<DoudizhuCard> choosePlay(List<DoudizhuCard> hand, CardPattern target, PlayContext context) {
        if (hand == null || hand.isEmpty()) {
            return List.of();
        }
        List<DoudizhuCard> sorted = new ArrayList<>(hand);
        sorted.sort(ASCENDING);
        PlayContext resolved = context == null
            ? new PlayContext(target == null, sorted.size(), Integer.MAX_VALUE)
            : context;

        if (target == null) {
            return chooseLead(sorted, resolved);
        }
        return chooseFollow(sorted, target, resolved);
    }

    /**
     * 首出。
     * 枚举候选，模拟出掉后给剩余手牌打分，选让剩下最好走的一手。
     */
    private static List<DoudizhuCard> chooseLead(List<DoudizhuCard> sorted, PlayContext context) {
        List<List<DoudizhuCard>> candidates = MoveGenerator.leadCandidates(sorted);
        if (candidates.isEmpty()) {
            // 兜底：正常不会走到，留着防止手牌里出现无法组牌的残局。
            return List.of(sorted.getFirst());
        }

        List<DoudizhuCard> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (List<DoudizhuCard> move : candidates) {
            CardPattern pattern = PatternAnalyzer.analyze(move).orElse(null);
            if (pattern == null) {
                continue;
            }
            double score = scoreRemaining(remaining(sorted, move));

            // 一手走完直接拉满，优先级高于任何其他考量。
            if (move.size() == sorted.size()) {
                score += 1000.0;
            }

            // 首出不轻易动炸弹和王炸：留着换牌权更值。
            if (pattern.type().isBombFamily() && move.size() < sorted.size()) {
                score -= 60.0;
            }

            // 同样能出的情况下偏向成型结构，别一张张丢单牌。
            if (pattern.type() == PatternType.SINGLE) {
                score -= 4.0;
            }

            // 别先把 A/2/王 这些控牌撒出去，除非已经进残局。
            if (!context.closingWindow()) {
                score -= 3.0 * countControlCards(move);
            }

            // 对手只剩一两张时，单张就是送牌权，尽量走多张压制。
            if (context.opponentVeryLow() && move.size() == 1) {
                score -= 12.0;
            }

            if (score > bestScore) {
                bestScore = score;
                best = move;
            }
        }
        return best == null ? List.of(sorted.getFirst()) : best;
    }

    /**
     * 跟牌。
     * 默认走同型最小压制；炸弹要过保留判断；队友领出时倾向让牌。
     * @return 要压的牌；空表示不要
     */
    private static List<DoudizhuCard> chooseFollow(List<DoudizhuCard> sorted, CardPattern target, PlayContext context) {
        List<List<DoudizhuCard>> candidates = MoveGenerator.followCandidates(sorted, target);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<DoudizhuCard> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (List<DoudizhuCard> move : candidates) {
            CardPattern pattern = PatternAnalyzer.analyze(move).orElse(null);
            if (pattern == null) {
                continue;
            }
            boolean bomb = pattern.type() == PatternType.BOMB;
            boolean rocket = pattern.type() == PatternType.JOKER_BOMB;
            boolean winsNow = move.size() == sorted.size();

            // 炸弹和王炸先过保留判断，不满足条件就不进候选。
            if (bomb && !winsNow && !shouldSpendBomb(context, target, sorted.size())) {
                continue;
            }
            if (rocket && !winsNow && !shouldSpendJokerBomb(context, target, sorted.size())) {
                continue;
            }
            // 队友领出时不用炸弹去压自己人。
            if (context.teammateLead() && (bomb || rocket) && !winsNow) {
                continue;
            }

            double score = scoreRemaining(remaining(sorted, move));
            if (winsNow) {
                score += 1000.0;
            }
            // 压得越省越好：同型里天然偏向最小的那一手。
            score -= 0.5 * pattern.primaryRank().strength();
            if (bomb) {
                score -= 25.0;
            }
            if (rocket) {
                score -= 45.0;
            }
            if (!context.closingWindow()) {
                score -= 2.0 * countControlCards(move);
            }
            // 对手快跑完了，压制的价值上升。
            if (context.opponentVeryLow()) {
                score += 15.0;
            } else if (context.opponentLow()) {
                score += 6.0;
            }

            if (score > bestScore) {
                bestScore = score;
                best = move;
            }
        }
        if (best == null) {
            return List.of();
        }
        return shouldPassInstead(best, target, context) ? List.of() : best;
    }

    /**
     * 压得上但值不值得压。
     * 两种情况选择不要：队友已经领出且我要动大牌，以及对手牌还多、我却得拿控牌去压一手小牌。
     */
    private static boolean shouldPassInstead(List<DoudizhuCard> move, CardPattern target, PlayContext context) {
        if (move.size() == context.selfRemainingCards()) {
            return false;
        }
        if (context.closingWindow() || context.opponentVeryLow()) {
            return false;
        }
        int control = countControlCards(move);
        if (context.teammateLead()) {
            // 队友领出：只肯用便宜牌接应，动控牌就让给队友。
            return control > 0;
        }
        // 对手还剩不少牌时，别为了压一手小牌就把 A/2/王 拆出去。
        boolean smallTarget = target.primaryRank().strength() <= CardRank.TEN.strength();
        return control > 0 && smallTarget && context.minOpponentRemainingCards() >= 6;
    }

    /**
     * 给剩余手牌打分。越高表示越好走。
     * 主项是"还要几手"，这是规则型实现里的共识指标：手数越少越接近赢。
     * 控牌数作为次要项，避免为了少一手就把大牌全拆了。
     */
    private static double scoreRemaining(List<DoudizhuCard> remaining) {
        if (remaining.isEmpty()) {
            return 500.0;
        }
        int hands = estimateHands(remaining);
        Map<CardRank, Integer> counts = counts(remaining);
        double control = 0.0;
        for (Map.Entry<CardRank, Integer> entry : counts.entrySet()) {
            if (entry.getKey().isJoker()) {
                control += 2.5;
            } else if (entry.getKey() == CardRank.TWO) {
                control += 1.5;
            } else if (entry.getKey() == CardRank.ACE) {
                control += 0.8;
            }
            if (entry.getValue() == 4) {
                control += 4.0;
            }
        }
        // 孤张越多越难走，单独再罚一次。
        long lone = counts.values().stream().filter(count -> count == 1).count();
        return -10.0 * hands + control - 0.6 * lone;
    }

    /**
     * 贪心估算这手牌还要几手才能出完。
     * 顺序：王炸 -> 炸弹 -> 飞机 -> 连对 -> 顺子 -> 三张 -> 对子 -> 单张。
     * 不回溯、不比较多种拆法，够用就行。
     */
    private static int estimateHands(List<DoudizhuCard> remaining) {
        Map<CardRank, Integer> counts = counts(remaining);
        int hands = 0;

        if (counts.containsKey(CardRank.SMALL_JOKER) && counts.containsKey(CardRank.BIG_JOKER)) {
            consume(counts, CardRank.SMALL_JOKER, 1);
            consume(counts, CardRank.BIG_JOKER, 1);
            hands++;
        }
        hands += takeAll(counts, 4);
        hands += takeChains(counts, 3, 2);
        hands += takeChains(counts, 2, 3);
        hands += takeChains(counts, 1, 5);
        hands += takeAll(counts, 3);
        hands += takeAll(counts, 2);
        hands += takeAll(counts, 1);
        return hands;
    }

    /** 把所有恰好 amount 张的点数各算一手并扣掉。 */
    private static int takeAll(Map<CardRank, Integer> counts, int amount) {
        int hands = 0;
        for (CardRank rank : List.copyOf(counts.keySet())) {
            while (counts.getOrDefault(rank, 0) >= amount) {
                consume(counts, rank, amount);
                hands++;
            }
        }
        return hands;
    }

    /**
     * 抽连牌并各算一手。
     * @param repeat 每点几张：1=单顺，2=连对，3=飞机
     * @param minChain 最短链长
     */
    private static int takeChains(Map<CardRank, Integer> counts, int repeat, int minChain) {
        int hands = 0;
        boolean found = true;
        while (found) {
            found = false;
            List<CardRank> usable = counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= repeat)
                .map(Map.Entry::getKey)
                .filter(CardRank::chainAllowed)
                .sorted(CardRank.NATURAL)
                .toList();
            // 取最长的一段连续区间，长度够就算一手。
            int runStart = 0;
            for (int index = 1; index <= usable.size(); index++) {
                boolean broken = index == usable.size()
                    || usable.get(index).strength() != usable.get(index - 1).strength() + 1;
                if (!broken) {
                    continue;
                }
                int runLength = index - runStart;
                if (runLength >= minChain) {
                    for (int cursor = runStart; cursor < index; cursor++) {
                        consume(counts, usable.get(cursor), repeat);
                    }
                    hands++;
                    found = true;
                    break;
                }
                runStart = index;
            }
        }
        return hands;
    }

    private static void consume(Map<CardRank, Integer> counts, CardRank rank, int amount) {
        int left = counts.getOrDefault(rank, 0) - amount;
        if (left <= 0) {
            counts.remove(rank);
        } else {
            counts.put(rank, left);
        }
    }

    private static List<DoudizhuCard> remaining(List<DoudizhuCard> sorted, List<DoudizhuCard> move) {
        List<DoudizhuCard> left = new ArrayList<>(sorted);
        left.removeAll(move);
        return left;
    }

    private static int countControlCards(List<DoudizhuCard> move) {
        return (int) move.stream()
            .filter(card -> card.rank().isJoker()
                || card.rank() == CardRank.TWO
                || card.rank() == CardRank.ACE)
            .count();
    }

    /**
     * 舍不舍得炸。
     * 规则型实现的共识：默认留着，只在收官、对手快跑完或对方也拿炸弹压过来时才用。
     */
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

    /** 王炸比炸弹更保守：基本只用于阻止对手直接走或自己收官。 */
    private static boolean shouldSpendJokerBomb(PlayContext context, CardPattern target, int handSize) {
        if (context == null || context.leadTurn()) {
            return false;
        }
        if (context.selfRemainingCards() <= 2 || context.minOpponentRemainingCards() <= 1) {
            return true;
        }
        return target != null && target.type() == PatternType.BOMB && handSize <= 4;
    }

    /**
     * 手牌强度打分，用于叫分和加倍。
     * 权重参考公开的规则型评分做法：以 10 为基准点，大牌和炸弹权重最高，
     * 成型的顺子/连对小幅加分，孤张扣分。
     */
    private static int handStrengthScore(List<DoudizhuCard> hand) {
        if (hand == null || hand.isEmpty()) {
            return 0;
        }
        Map<CardRank, Integer> counts = counts(hand);
        int score = 0;
        for (Map.Entry<CardRank, Integer> entry : counts.entrySet()) {
            CardRank rank = entry.getKey();
            int owned = entry.getValue();
            if (rank == CardRank.BIG_JOKER) {
                score += 7;
            } else if (rank == CardRank.SMALL_JOKER) {
                score += 6;
            } else if (rank == CardRank.TWO) {
                score += 5 * Math.min(owned, 3);
            } else if (rank.strength() > CardRank.TEN.strength()) {
                score += (rank.strength() - CardRank.TEN.strength()) * Math.min(owned, 2);
            }
            if (owned == 4) {
                score += 7;
            }
        }
        if (counts.containsKey(CardRank.SMALL_JOKER) && counts.containsKey(CardRank.BIG_JOKER)) {
            score += 6;
        }
        // 手数越少越好走，这里把估出来的手数折成扣分。
        score -= Math.max(0, estimateHands(new ArrayList<>(hand)) - 6);
        return Math.max(0, score);
    }

    private static Map<CardRank, Integer> counts(List<DoudizhuCard> hand) {
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        for (DoudizhuCard card : hand) {
            counts.merge(card.rank(), 1, Integer::sum);
        }
        return counts;
    }
}
