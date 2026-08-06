package linmumua.doudizhu.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MoveGenerator 是 bot 的合法性闸口：GameTable.applyMoveResolution 不校验压制，
 * 所以这里生成的每一手都必须自洽。这些测试锁住"不生成非法牌"和"能生成该有的牌型"。
 */
class MoveGeneratorTest {
    private static int nextId = 1;

    private static List<DoudizhuCard> hand(String... labels) {
        List<DoudizhuCard> cards = new ArrayList<>();
        for (String label : labels) {
            cards.add(new DoudizhuCard(nextId++, rank(label), CardSuit.SPADES));
        }
        return cards;
    }

    private static CardRank rank(String label) {
        return switch (label) {
            case "3" -> CardRank.THREE;
            case "4" -> CardRank.FOUR;
            case "5" -> CardRank.FIVE;
            case "6" -> CardRank.SIX;
            case "7" -> CardRank.SEVEN;
            case "8" -> CardRank.EIGHT;
            case "9" -> CardRank.NINE;
            case "10" -> CardRank.TEN;
            case "J" -> CardRank.JACK;
            case "Q" -> CardRank.QUEEN;
            case "K" -> CardRank.KING;
            case "A" -> CardRank.ACE;
            case "2" -> CardRank.TWO;
            case "w" -> CardRank.SMALL_JOKER;
            case "W" -> CardRank.BIG_JOKER;
            default -> throw new IllegalArgumentException("未知点数 " + label);
        };
    }

    private static CardPattern patternOf(String... labels) {
        return PatternAnalyzer.analyze(hand(labels)).orElseThrow();
    }

    private static boolean hasType(List<List<DoudizhuCard>> moves, PatternType type) {
        return moves.stream()
            .map(PatternAnalyzer::analyze)
            .filter(java.util.Optional::isPresent)
            .map(java.util.Optional::get)
            .anyMatch(pattern -> pattern.type() == type);
    }

    @Test
    void everyLeadCandidateIsALegalPattern() {
        List<DoudizhuCard> cards = hand("3", "3", "3", "4", "4", "5", "6", "7", "8", "9", "10", "2", "2", "w", "W");
        List<List<DoudizhuCard>> moves = MoveGenerator.leadCandidates(cards);

        assertFalse(moves.isEmpty(), "应该能生成首出候选");
        for (List<DoudizhuCard> move : moves) {
            assertTrue(
                PatternAnalyzer.analyze(move).isPresent(),
                "生成了无法解析的牌型：" + labels(move)
            );
            assertTrue(cards.containsAll(move), "生成的牌不在手牌里：" + labels(move));
        }
    }

    @Test
    void everyFollowCandidateActuallyBeatsTheTarget() {
        List<DoudizhuCard> cards = hand("4", "4", "4", "5", "5", "6", "7", "8", "9", "10", "J", "2", "2", "2", "W");
        List<CardPattern> targets = List.of(
            patternOf("3"),
            patternOf("3", "3"),
            patternOf("3", "3", "3"),
            patternOf("3", "3", "3", "6"),
            patternOf("3", "4", "5", "6", "7"),
            patternOf("3", "3", "4", "4", "5", "5"),
            patternOf("3", "3", "3", "4", "4", "4")
        );

        for (CardPattern target : targets) {
            for (List<DoudizhuCard> move : MoveGenerator.followCandidates(cards, target)) {
                CardPattern pattern = PatternAnalyzer.analyze(move).orElse(null);
                assertTrue(pattern != null, "生成了无法解析的牌型：" + labels(move));
                assertTrue(
                    pattern.canBeat(target),
                    "生成了压不过 " + target.type() + " 的牌：" + labels(move)
                );
            }
        }
    }

    /**
     * 改造前跟牌对顺子直接返回空，bot 只能过或炸。这里锁住能用更大的顺子压。
     */
    @Test
    void canFollowStraightWithBiggerStraight() {
        List<DoudizhuCard> cards = hand("6", "7", "8", "9", "10", "J", "2");
        CardPattern target = patternOf("3", "4", "5", "6", "7");

        List<List<DoudizhuCard>> moves = MoveGenerator.followCandidates(cards, target);

        assertTrue(hasType(moves, PatternType.STRAIGHT), "应该能用更大的顺子压过顺子");
        for (List<DoudizhuCard> move : moves) {
            CardPattern pattern = PatternAnalyzer.analyze(move).orElseThrow();
            if (pattern.type() == PatternType.STRAIGHT) {
                assertEquals(5, move.size(), "压顺子时长度必须一致");
            }
        }
    }

    @Test
    void canFollowPairStraightAndAirplane() {
        List<DoudizhuCard> pairChainHand = hand("6", "6", "7", "7", "8", "8", "9", "9");
        assertTrue(
            hasType(MoveGenerator.followCandidates(pairChainHand, patternOf("3", "3", "4", "4", "5", "5")), PatternType.PAIR_STRAIGHT),
            "应该能用更大的连对压过连对"
        );

        List<DoudizhuCard> airplaneHand = hand("8", "8", "8", "9", "9", "9", "3", "4");
        assertTrue(
            hasType(MoveGenerator.followCandidates(airplaneHand, patternOf("3", "3", "3", "4", "4", "4")), PatternType.AIRPLANE),
            "应该能用更大的飞机压过飞机"
        );
    }

    /** 顺子只能到 A：2 和王不进顺子。 */
    @Test
    void straightNeverIncludesTwoOrJokers() {
        List<DoudizhuCard> cards = hand("10", "J", "Q", "K", "A", "2", "w", "W");

        for (List<DoudizhuCard> move : MoveGenerator.leadCandidates(cards)) {
            CardPattern pattern = PatternAnalyzer.analyze(move).orElseThrow();
            if (pattern.type() != PatternType.STRAIGHT) {
                continue;
            }
            assertTrue(
                move.stream().allMatch(card -> card.rank().chainAllowed()),
                "顺子里混进了不能连的牌：" + labels(move)
            );
        }
    }

    private static String labels(List<DoudizhuCard> move) {
        return move.stream().map(card -> card.rank().label()).toList().toString();
    }
}
