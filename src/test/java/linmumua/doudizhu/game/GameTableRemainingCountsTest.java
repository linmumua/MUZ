package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 记牌器的数据源：每个点数还剩几张。
 *
 * <p>用 Unsafe 绕过构造器直接摆字段（沿用 {@code GameTableTrickHudSeatsTest} 的做法）：
 * GameTable 的构造器要 Bukkit 插件实例，单测里起不来，而这几个方法只碰
 * {@code remainingRankCounts} 一个字段。
 *
 * <p>【为什么不用源码扫描】：扣减和初始化都是能直接调的纯数据操作，
 * 调真方法能验证「算出来的数对不对」；扫源码只能验证「代码长什么样」。
 */
class GameTableRemainingCountsTest {
    /**
     * 一副牌初始化：13 个点数各 4 张，大小王各 1 张，合计 54。
     *
     * <p>失败条件：把王也初始化成 4 张，或漏掉某个点数。前者会让记牌器显示
     * 「大王剩 4 张」这种不可能的读数。
     */
    @Test
    void 初始化是完整一副牌且王只有一张() throws Exception {
        GameTable table = freshTable();
        invokeReset(table);
        Map<CardRank, Integer> counts = table.getRemainingCounts();

        assertEquals(CardRank.values().length, counts.size(), "每个点数都要有读数");
        int total = 0;
        for (Map.Entry<CardRank, Integer> entry : counts.entrySet()) {
            int expected = entry.getKey().isJoker() ? 1 : 4;
            assertEquals(expected, entry.getValue().intValue(),
                entry.getKey() + " 的初始张数不对");
            total += entry.getValue();
        }
        assertEquals(54, total, "一副斗地主牌共 54 张");
    }

    /**
     * 出牌按点数逐张扣减。
     *
     * <p>失败条件：扣减写成「每手减 1」而不是「每张减 1」，打对子只会扣掉一张，
     * 记牌器从此一直偏高。
     */
    @Test
    void 出牌按张数扣减而不是按手数() throws Exception {
        GameTable table = freshTable();
        invokeReset(table);
        // 一对 3：两张同点数，必须各扣一次。
        decrement(table, List.of(CardRank.THREE, CardRank.THREE));

        assertEquals(2, table.getRemainingCounts().get(CardRank.THREE).intValue(),
            "打出一对 3 之后应剩 2 张，按手数扣只会剩 3 张");
    }

    /**
     * 扣到 0 就停住，不会变成负数。
     *
     * <p>真实对局里同一点数最多出 4 张，扣不成负数。但托管代打与机器人共用这条路径，
     * 一旦上游出现重复提交，负数会直接显示成「剩 -1 张」。
     *
     * <p>失败条件：把下界判断去掉，改成无条件 left - 1。
     */
    @Test
    void 扣减不会把张数压到负数() throws Exception {
        GameTable table = freshTable();
        invokeReset(table);
        // 故意多扣两次：4 张之后再来两张。
        for (int i = 0; i < 6; i++) {
            decrement(table, List.of(CardRank.FIVE));
        }

        assertEquals(0, table.getRemainingCounts().get(CardRank.FIVE).intValue(),
            "扣到 0 就该停住，负数会在 HUD 上显示成「剩 -1 张」");
    }

    /**
     * 王只有一张，出掉就归零。
     *
     * <p>失败条件：王被当成 4 张点数处理。
     */
    @Test
    void 王出掉之后归零() throws Exception {
        GameTable table = freshTable();
        invokeReset(table);
        decrement(table, List.of(CardRank.BIG_JOKER));

        assertEquals(0, table.getRemainingCounts().get(CardRank.BIG_JOKER).intValue(),
            "大王只有一张，出掉即为 0");
        assertEquals(1, table.getRemainingCounts().get(CardRank.SMALL_JOKER).intValue(),
            "扣大王不该动到小王");
    }

    /**
     * 读数是快照，调用方改不到内部状态。
     *
     * <p>这个字段是记牌器唯一的真相来源，HUD 每帧都读。返回可变引用的话，
     * 渲染层任何一次误写都会静默改掉牌局数据，而且很难追。
     *
     * <p>失败条件：getRemainingCounts 改成直接返回内部 map。
     */
    @Test
    void 读数是不可变快照() throws Exception {
        GameTable table = freshTable();
        invokeReset(table);
        Map<CardRank, Integer> counts = table.getRemainingCounts();

        assertThrows(UnsupportedOperationException.class,
            () -> counts.put(CardRank.THREE, 99),
            "读数必须不可变，否则渲染层能静默改掉牌局数据");
    }

    /**
     * 牌局没开始时返回空表。
     *
     * <p>失败条件：未开局时返回一副满牌，HUD 会在大厅阶段就显示「每样剩 4 张」。
     */
    @Test
    void 未开局时是空表() throws Exception {
        GameTable table = freshTable();

        assertTrue(table.getRemainingCounts().isEmpty(),
            "没发牌就没什么可记的，空表让调用方能据此不画记牌行");
    }

    /**
     * 重置会把上一局的扣减抹掉。
     *
     * <p>失败条件：reset 只补缺失的键而不覆盖已有值，那么第二局开局时记牌器
     * 会带着上一局的残留读数。
     */
    @Test
    void 重置抹掉上一局的残留() throws Exception {
        GameTable table = freshTable();
        invokeReset(table);
        decrement(table, List.of(CardRank.THREE, CardRank.THREE, CardRank.THREE));
        assertEquals(1, table.getRemainingCounts().get(CardRank.THREE).intValue());

        invokeReset(table);

        assertEquals(4, table.getRemainingCounts().get(CardRank.THREE).intValue(),
            "新一局必须回到 4 张，否则记牌器带着上局残留");
    }

    /** 拿一个字段就位、但还没发牌的空桌子。 */
    private static GameTable freshTable() throws Exception {
        GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
        setField(table, "remainingRankCounts", new EnumMap<CardRank, Integer>(CardRank.class));
        return table;
    }

    /** 调生产代码的初始化，不复写一份初始化逻辑。 */
    private static void invokeReset(GameTable table) throws Exception {
        Method method = GameTable.class.getDeclaredMethod("resetRemainingRankCounts");
        method.setAccessible(true);
        method.invoke(table);
    }

    /**
     * 调生产代码的扣减方法。
     *
     * <p>【为什么不整个调 applyMoveResolution】：那个方法还要牌型分析、座位轮转、
     * 计分等一大片状态，摆齐要几十个字段。扣减算式已从它里面抽成独立方法，
     * 这里直接调那一个——不复写副本，生产算式一改这些断言就会失败。
     */
    private static void decrement(GameTable table, List<CardRank> ranks) throws Exception {
        List<DoudizhuCard> move = new java.util.ArrayList<>();
        int id = 0;
        for (CardRank rank : ranks) {
            move.add(new DoudizhuCard(id++, rank, CardSuit.SPADES));
        }
        Method method = GameTable.class.getDeclaredMethod("decrementRemainingRankCounts", List.class);
        method.setAccessible(true);
        method.invoke(table, move);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = GameTable.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
