package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ObjectComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * 当前出牌 HUD 固定显示在 sendActionBar 所在的经验条正上方。
 *
 * <p>明牌相关断言自 1.10.63 起一律以 {@link GameTable.RevealWindowSnapshot} 为准：
 * 倍率是「第 3 批发完 ×3、第 4 批 ×2、第 5 批及以后固定 ×1」的递减阶梯，窗口在**第 3 批发完后只开 60 tick**
 * （此时阶段仍是 DEALING），因此视图既不能再写死「整局最多 ×2」，也不能只在 REVEALING
 * 才播报窗口。这里锁的正是这两条，而不是某一句具体措辞。
 */
class TableStatusViewsActionBarTest {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final UUID TURN = UUID.fromString("c2b73fa8-6703-3d89-943d-7cc09d51fc9a");
    private static final UUID VIEWER = UUID.fromString("0d88ac75-df02-466d-a59c-19f8384e4732");

    /**
     * 窗口刚在第 3 批发完时打开、还没明牌：现在点会冻结 ×3，限时窗口还剩 15 秒。
     *
     * <p>【1.10.73】窗口与倍率阶梯同锚在第 3 批（每人 9 张），因此「批次 = 3」与「倍率 = 3」必须同时成立；
     * 把批数写成 1/2 是旧「首批即开」语义的残留，会让断言在看不出错的情况下继续变绿。
     */
    private static GameTable.RevealWindowSnapshot windowOpenAtThirdBatch() {
        return new GameTable.RevealWindowSnapshot(true, 15, 3, 1, 3, false, false);
    }

    /** 同一限时窗口内发到第 4 批：阶梯降到 ×2，窗口还剩 9 秒。 */
    private static GameTable.RevealWindowSnapshot windowOpenAtFourthBatch() {
        return new GameTable.RevealWindowSnapshot(true, 9, 2, 1, 4, false, false);
    }

    /** 窗口已开但本人已明牌：当刻倍率已是下限 ×1，本局已生效倍率冻结为 ×3。 */
    private static GameTable.RevealWindowSnapshot windowOpenAndRevealed() {
        return new GameTable.RevealWindowSnapshot(true, 6, 1, 3, 5, true, false);
    }

    /**
     * 抢地主加赛的资格延长：限时窗口早已归零（remainingSeconds = -1），但资格仍在。
     *
     * <p>UI 绝不能把 -1 当成「剩 0 秒」写出来——那正是本轮要修的自相矛盾文案。
     */
    private static GameTable.RevealWindowSnapshot extendedIntoBidding() {
        return new GameTable.RevealWindowSnapshot(true, -1, 1, 1, 6, false, true);
    }

    /**
     * 行动栏身份只放纯文本，不许夹带原生头像对象。
     *
     * <p>屏幕中央那条头像 Title 已整体移除，头像不该顺势挪回行动栏：
     * 行动栏一行的宽度有限，塞进 PLAYER_HEAD 对象会把玩家名挤出可见区。
     */
    @Test
    void currentPlayIdentityStaysPlainTextWithoutHeadObject() {
        Component line = TableStatusViews.currentPlayIdentity(Component.text("NativeProbe"));

        assertEquals("当前出牌 · NativeProbe", PLAIN.serialize(line));
        assertFalse(line instanceof ObjectComponent, "行动栏不应携带头像对象");
        assertTrue(
            line.children().stream().noneMatch(ObjectComponent.class::isInstance),
            "行动栏的子节点里也不许夹带头像对象"
        );
    }

    @Test
    void currentPlayerSeesSelectedCountAndCountdown() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.PLAYING,
            TURN,
            TURN,
            false,
            1,
            12,
            3,
            id -> Component.text("普通身份"),
            id -> Component.text("当前出牌 · NativeProbe"),
            Component.empty(),
            20,
            null
        );

        assertEquals("当前出牌 · NativeProbe · 已选 3 张 | 12 秒", PLAIN.serialize(line));
    }

    @Test
    void otherPlayersDoNotSeePrivateSelectionCount() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.PLAYING,
            VIEWER,
            TURN,
            false,
            1,
            12,
            3,
            id -> Component.text("普通身份"),
            id -> Component.text("当前出牌 · NativeProbe"),
            Component.empty(),
            20,
            null
        );

        String plain = PLAIN.serialize(line);
        assertEquals("当前出牌 · NativeProbe | 12 秒", plain);
        assertFalse(plain.contains("已选"));
    }

    @Test
    void dealingPhaseNeverFallsThroughToTurnPrompt() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.DEALING,
            VIEWER,
            TURN,
            false,
            1,
            0,
            0,
            id -> Component.text("普通身份"),
            id -> Component.text("当前出牌 · NativeProbe"),
            Component.empty(),
            20,
            null
        );

        assertEquals("正在发牌，请稍候。", PLAIN.serialize(line));
    }

    /**
     * 第 3 批发完窗口后仍处于 DEALING：这段时间必须播报窗口，否则玩家只知道在发牌，
     * 不知道当前可以明牌、更不知道倍率。
     */
    @Test
    void dealingPhaseAdvertisesRevealWindowAsSoonAsFirstBatchIsDealt() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.DEALING,
            VIEWER,
            TURN,
            false,
            1,
            0,
            0,
            id -> Component.text("普通身份"),
            id -> Component.text("当前出牌 · NativeProbe"),
            Component.empty(),
            20,
            windowOpenAtThirdBatch()
        );

        String plain = PLAIN.serialize(line);
        assertEquals("明牌窗口 · 现在明牌 x3 · 剩 15 秒", plain);
        assertFalse(plain.contains("正在发牌"), "窗口已开时不能再显示纯发牌文案");
    }

    /**
     * 抢地主加赛的资格延长：**不得**出现「剩 0 秒」。
     *
     * <p>延长期限时窗口早已归零，快照用 remainingSeconds = -1 明确表达「没有倒计时」。
     * 若视图把 -1 钳成 0 再拼上「剩 %s 秒」，玩家会看到「剩 0 秒」却仍然能点明牌——
     * 这条断言就是防那种自相矛盾（本轮真实缺陷之一）。
     */
    @Test
    void extendedIntoBiddingNeverRendersZeroSecondsCountdown() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.BIDDING,
            VIEWER,
            TURN,
            false,
            1,
            0,
            0,
            id -> Component.text("普通身份"),
            id -> Component.text("当前出牌 · NativeProbe"),
            Component.empty(),
            20,
            extendedIntoBidding()
        );

        String plain = PLAIN.serialize(line);
        assertTrue(plain.contains("现在明牌 x1"), "延长期仍必须报告当刻可冻结倍率，实际：" + plain);
        assertTrue(plain.contains("抢地主结束前"), "延长期必须说明资格延续到叫分结束，实际：" + plain);
        assertFalse(plain.contains("剩 0 秒"), "延长期不得写「剩 0 秒」，实际：" + plain);
        assertFalse(plain.contains("剩 -1 秒"), "延长期不得把哨兵 -1 直接拼进文案，实际：" + plain);
    }

    @Test
    void revealingPhaseUsesSnapshotMultiplierInsteadOfHardcodedCap() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.REVEALING,
            VIEWER,
            TURN,
            false,
            1,
            0,
            0,
            id -> Component.text("普通身份"),
            id -> Component.text("当前出牌 · NativeProbe"),
            Component.empty(),
            20,
            windowOpenAtFourthBatch()
        );

        String plain = PLAIN.serialize(line);
        assertEquals("明牌窗口 · 现在明牌 x2 · 剩 9 秒", plain);
        assertFalse(plain.contains("最多"), "明牌倍率已是 3→2→1 阶梯，不能再写死整局上限");
        assertFalse(plain.contains("底牌"));
        assertFalse(plain.contains("已选"));
    }

    /** 窗口关掉但阶段还没切到 BIDDING 的短暂窗口：不许再宣传明牌。 */
    @Test
    void revealingPhaseWithoutOpenWindowFallsBackToWaitingForBidding() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.REVEALING,
            VIEWER,
            TURN,
            false,
            1,
            0,
            0,
            id -> Component.text("普通身份"),
            id -> Component.text("当前出牌 · NativeProbe"),
            Component.empty(),
            20,
            new GameTable.RevealWindowSnapshot(false, 0, 0, 1, 6, false, false)
        );

        assertEquals("明牌窗口已结束 · 等待叫分。", PLAIN.serialize(line));
    }

    /** 已明牌的人该看到已冻结的表级倍率，而不是「你现在还能点 ×1」。 */
    @Test
    void revealedViewerSeesFrozenAppliedMultiplier() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.REVEALING,
            VIEWER,
            TURN,
            false,
            1,
            0,
            0,
            id -> Component.text("普通身份"),
            id -> Component.text("当前出牌 · NativeProbe"),
            Component.empty(),
            20,
            windowOpenAndRevealed()
        );

        assertEquals("已明牌 · 本局明牌 x3", PLAIN.serialize(line));
    }

    /**
     * 桌面状态牌（全桌事实，viewer 传 null）在发牌期也必须跟着窗口走：
     * 这条锁的是「发牌期状态栏看不到可明牌信息」的旧行为已修复。
     */
    @Test
    void tableStatusShowsRevealWindowDuringDealing() {
        String text = TableStatusViews.multiplierStatusText(
            GamePhase.DEALING, 0, null, 1, 1, 0, 2, null, "x1", windowOpenAtThirdBatch());
        String rich = PLAIN.serialize(TableStatusViews.multiplierStatusComponent(
            GamePhase.DEALING, 0, null, 1, 1, 0, 2, null, "x1", windowOpenAtThirdBatch()));

        assertEquals("明牌窗口 · 现在明牌 x3 · 剩 15 秒", text);
        assertTrue(rich.contains("x3"), "富文本状态牌必须显示当刻倍率");
        assertFalse(text.contains("发牌中"), "窗口已开时状态牌不能再显示纯发牌文案");
        assertFalse(text.contains("最多"), "不能再写死整局明牌上限");
    }

    @Test
    void publicRevealMultiplierIsExplainedInLiveAndSettlementViews() {
        String text = TableStatusViews.multiplierStatusText(
            GamePhase.PLAYING, 3, TURN, 4, 3, 0, 2, 1, "x36", null);
        String rich = PLAIN.serialize(TableStatusViews.multiplierStatusComponent(
            GamePhase.PLAYING, 3, TURN, 4, 3, 0, 2, 1, "x36", null));
        String summary = PLAIN.serialize(new RoundSettlementView(
            true, 36, 3, 4, 3, 0, 2, 1, false, "", "x36").summary(Component.text("胜者")));
        assertTrue(text.contains("明牌 x3"), "明牌倍率上限已是 3，结算展示必须能显示 x3");
        assertTrue(rich.contains("明牌") && rich.contains("x3"));
        assertTrue(summary.contains("明牌") && summary.contains("x3"));
        assertTrue(summary.contains("x36"), "明牌来源和实际结算核心倍率必须一起可见");
        assertFalse(text.contains("2 次"), "公共倍率不能描述成每人两次明牌");
    }

    @Test
    void botThinkingKeepsTextIdentityAndSkipsPlayerHeadRenderer() {
        Component line = TableStatusViews.persistentActionBar(
            GamePhase.PLAYING,
            VIEWER,
            TURN,
            true,
            1,
            0,
            0,
            id -> Component.text("Bot-1"),
            id -> {
                fail("机器人不该调用真人 player_head 渲染器");
                return Component.empty();
            },
            Component.empty(),
            20,
            null
        );

        assertEquals("当前由 Bot-1 正在思考。", PLAIN.serialize(line));
        assertTrue(line.children().stream().noneMatch(ObjectComponent.class::isInstance));
    }
}
