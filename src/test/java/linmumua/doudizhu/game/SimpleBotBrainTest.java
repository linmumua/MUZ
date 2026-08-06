package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.model.CardPattern;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.model.PatternAnalyzer;
import linmumua.doudizhu.model.PatternType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锁住规则型 bot 的关键行为，而不是锁死具体出哪张牌。
 * 这些断言对应改造前的真实缺陷：跟不了顺子、首出只丢单牌、乱炸、压队友。
 */
class SimpleBotBrainTest {
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

    private static String labels(List<DoudizhuCard> move) {
        return move.stream().map(card -> card.rank().label()).toList().toString();
    }

    /**
     * 改造前跟牌遇到顺子直接返回空，只能过或炸。
     */
    @Test
    void followsStraightInsteadOfPassing() {
        List<DoudizhuCard> cards = hand("6", "7", "8", "9", "10", "J", "3");
        CardPattern target = patternOf("3", "4", "5", "6", "7");

        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(
            cards,
            target,
            new SimpleBotBrain.PlayContext(false, cards.size(), 8, false)
        );

        assertFalse(move.isEmpty(), "有更大的顺子却选择了不要");
        CardPattern played = PatternAnalyzer.analyze(move).orElseThrow();
        assertEquals(PatternType.STRAIGHT, played.type(), "应该用顺子压顺子，实际出了 " + labels(move));
        assertTrue(played.canBeat(target), "出的牌压不过上家：" + labels(move));
    }

    /**
     * 改造前首出只会从最小单张开始丢，顺子被一张张拆散。
     */
    @Test
    void leadsWithStraightRatherThanDumpingSingles() {
        List<DoudizhuCard> cards = hand("3", "4", "5", "6", "7", "K", "2");

        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(cards, null);

        assertTrue(move.size() > 1, "手里有顺子却首出单张：" + labels(move));
        CardPattern played = PatternAnalyzer.analyze(move).orElseThrow();
        assertEquals(PatternType.STRAIGHT, played.type(), "应该首出顺子，实际出了 " + labels(move));
    }

    /** 首出不该主动把炸弹拆出去，除非能一手走完。 */
    @Test
    void doesNotLeadBombWhileHoldingOtherOptions() {
        List<DoudizhuCard> cards = hand("9", "9", "9", "9", "3", "4", "6", "8");

        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(cards, null);
        CardPattern played = PatternAnalyzer.analyze(move).orElseThrow();

        assertFalse(played.type().isBombFamily(), "首出就把炸弹丢了：" + labels(move));
    }

    /** 王炸留着，不为压一手小对子就拆。 */
    @Test
    void keepsJokerBombAgainstSmallPair() {
        List<DoudizhuCard> cards = hand("w", "W", "5", "6", "8", "9", "J", "Q");
        CardPattern target = patternOf("4", "4");

        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(
            cards,
            target,
            new SimpleBotBrain.PlayContext(false, cards.size(), 9, false)
        );

        if (!move.isEmpty()) {
            CardPattern played = PatternAnalyzer.analyze(move).orElseThrow();
            assertFalse(
                played.type() == PatternType.JOKER_BOMB,
                "为压一手小对子就用了王炸：" + labels(move)
            );
        }
    }

    /** 队友领出时不用控牌去压自己人。 */
    @Test
    void letsTeammateKeepInitiative() {
        List<DoudizhuCard> cards = hand("2", "2", "5", "6", "8", "9", "J", "Q");
        CardPattern target = patternOf("K", "K");

        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(
            cards,
            target,
            new SimpleBotBrain.PlayContext(false, cards.size(), 9, true)
        );

        assertTrue(move.isEmpty(), "队友领出还去压，出了：" + labels(move));
    }

    /** 对手领出同样一手时该压，别把让牌逻辑用错对象。 */
    @Test
    void stillBeatsOpponentLead() {
        List<DoudizhuCard> cards = hand("2", "2", "5", "6", "8", "9", "J", "Q");
        CardPattern target = patternOf("K", "K");

        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(
            cards,
            target,
            new SimpleBotBrain.PlayContext(false, cards.size(), 2, false)
        );

        assertFalse(move.isEmpty(), "对手快跑完了还不压");
        assertTrue(PatternAnalyzer.analyze(move).orElseThrow().canBeat(target), "出的牌压不过：" + labels(move));
    }

    /** 能一手走完就必须走完。 */
    @Test
    void finishesWhenItCan() {
        List<DoudizhuCard> cards = hand("7", "8", "9", "10", "J");

        List<DoudizhuCard> move = SimpleBotBrain.choosePlay(cards, null);

        assertEquals(5, move.size(), "能一手走完却没走：" + labels(move));
    }

    /** 好牌该叫 3 分，烂牌不叫。 */
    @Test
    void bidsHighOnStrongHandAndPassesOnWeakHand() {
        List<DoudizhuCard> strong = hand("w", "W", "2", "2", "2", "A", "A", "K", "K", "9", "9", "9", "8", "7", "6", "5", "4");
        List<DoudizhuCard> weak = hand("3", "4", "5", "7", "8", "10", "J", "Q", "3", "4", "6", "7", "9", "10", "J", "5", "6");

        assertEquals(3, SimpleBotBrain.chooseBid(strong), "这手牌该叫 3 分");
        assertEquals(0, SimpleBotBrain.chooseBid(weak), "这手烂牌不该叫分");
    }
}
