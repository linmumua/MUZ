package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.world.PhysicalTableManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 开局时间线接入 GameTable 的行为回归。
 *
 * <p>GameTable 构造器会装配 Bukkit、资源包和多个运行期服务，因此这里沿用现有测试的
 * Unsafe 夹具，只补齐本轮实际调用路径需要的字段；开局协调器、资格判断、公开倍率和
 * reset/redeal 均直接调用生产方法，不复制实现逻辑。
 */
class GameTableOpeningFlowTest {
    /**
     * 【机制升级：分批明牌】窗口现在在首批（每人 3 张）发完即开，所以这里的公共倍率由旧的固定 ×2
     * 变为「首批窗口冻结值 = 3」。三条意图不变并继续断言：三个人可以各自独立明牌、多人不叠乘、
     * 同一人重复点击被拒且不改变已生效倍率。
     */
    @Test
    void threeHumansCanRevealIndependentlyAndRepeatedRevealDoesNotStackPublicFactor() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        GameTable table = openingTable(List.of(first, second, third), Set.of(), Map.of());

        withPlayers(Map.of(
            first, player(first, "first", true),
            second, player(second, "second", true),
            third, player(third, "third", true)
        ), () -> {
            RoundOpeningCoordinator coordinator = startReveal(table);
            assertTrue(coordinator.isRevealWindowOpen());
            assertTrue(table.isRevealWindowOpen(), "首批窗口开启后牌桌必须同步可见");
            assertEquals(1, table.openingCompletedDealBatches(), "窗口开在首批（每人 3 张）完成的那一刻");
            assertEquals(3, table.getHand(first).size());
            assertEquals(3, table.currentRevealMultiplier(), "首批窗口可冻结倍率为 3");
            assertTrue(table.canRevealHand(first));
            assertTrue(table.canRevealHand(second));
            assertTrue(table.canRevealHand(third));

            table.revealHand(first);
            table.revealHand(second);
            table.revealHand(third);

            assertTrue(table.isHandRevealed(first));
            assertTrue(table.isHandRevealed(second));
            assertTrue(table.isHandRevealed(third));
            assertEquals(3, table.publicRevealMultiplier(), "多人明牌仍只有一个公共倍率，不得叠乘成 9");
            assertEquals(1, table.getRevealCount(first));
            IllegalStateException repeated = assertThrows(IllegalStateException.class,
                () -> table.revealHand(first), "重复明牌必须拒绝");
            assertEquals(3, table.publicRevealMultiplier(), "重复点击不得叠加或改变已冻结倍率");
            assertEquals(1, table.getRevealCount(first), "重复点击不得把计数变成 2");
            assertFalse(repeated.getMessage() == null || repeated.getMessage().isBlank(), "拒绝必须带玩家可读原因");
            return null;
        });
    }

    @Test
    void offlineHumanAndBotCannotRevealButOnlineHumanCan() throws Exception {
        UUID online = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        GameTable table = openingTable(List.of(online, offline, bot), Set.of(bot), Map.of(bot, "Bot-1"));

        withPlayers(Map.of(
            online, player(online, "online", true),
            offline, player(offline, "offline", false),
            bot, player(bot, "bot", true)
        ), () -> {
            startReveal(table);
            assertTrue(table.canRevealHand(online));
            assertFalse(table.canRevealHand(offline), "离线真人不应获得明牌资格");
            assertFalse(table.canRevealHand(bot), "机器人不应获得明牌资格");
            assertThrows(IllegalStateException.class, () -> table.revealHand(offline));
            assertThrows(IllegalStateException.class, () -> table.revealHand(bot));
            table.revealHand(online);
            assertEquals(3, table.publicRevealMultiplier(), "首批窗口明牌冻结为 3 倍");
            return null;
        });
    }

    /**
     * 【机制升级：窗口跨越剩余发牌】明牌窗口现在从首批一直开到「发牌完成 + 翻转 + 尾部倒计时」，
     * 所以窗口开着期间不能再断言「必须处于 REVEALING」；但两条既有意图保留并加强：
     * 开局阶段（发牌/明牌窗口）不得叫分，窗口截止后必须先进叫分且明牌请求被拒。
     * 顺带覆盖 17+3 完整发牌：窗口关闭时三家各 17 张、底牌 3 张、54 张唯一。
     */
    @Test
    void revealAfterWindowExpiryIsRejectedAndBiddingIsRejectedDuringBothOpeningPhases() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        GameTable table = openingTable(List.of(first, second, third), Set.of(), Map.of());

        withPlayers(Map.of(
            first, player(first, "first", true),
            second, player(second, "second", true),
            third, player(third, "third", true)
        ), () -> {
            RoundOpeningCoordinator coordinator = openingCoordinator(table);
            coordinator.start(deck(54), settings(3, 8, 3));
            assertEquals(GamePhase.DEALING, table.getPhase());
            assertThrows(IllegalStateException.class, () -> table.bid(player(first, "first", true), 1));

            int revealGuard = 0;
            while (!coordinator.isRevealWindowOpen() && revealGuard++ < 200) {
                coordinator.tick();
            }
            assertTrue(coordinator.isRevealWindowOpen(), "测试夹具必须在有限 tick 内进入明牌窗口");
            assertNotEquals(GamePhase.BIDDING, table.getPhase(), "窗口开着时不得已经进入叫分");
            assertThrows(IllegalStateException.class, () -> table.bid(player(first, "first", true), 1));

            int expiryGuard = 0;
            while (coordinator.isRevealWindowOpen() && expiryGuard++ < 400) {
                coordinator.tick();
            }
            assertFalse(coordinator.isRevealWindowOpen(), "明牌窗口必须在有限 tick 内结束");
            assertEquals(GamePhase.BIDDING, table.getPhase());
            assertEquals(0, table.currentRevealMultiplier(), "窗口关闭后没有可冻结倍率");
            assertThrows(IllegalStateException.class, () -> table.revealHand(first), "过期回调不能再明牌");
            assertFalse(table.isHandRevealed(first), "过期点击不得写入手牌公开状态");

            for (UUID seat : table.getSeats()) {
                assertEquals(17, table.getHand(seat).size(), "17+3 完整发牌：每家 17 张");
            }
            assertEquals(3, table.getBottomCards().size(), "17+3 完整发牌：底牌 3 张");
            assertEquals(54, table.getHand(first).size() + table.getHand(second).size()
                + table.getHand(third).size() + table.getBottomCards().size(), "54 张牌必须全部落地且不重复");
            return null;
        });
    }

    /**
     * 【机制升级：阶梯上限 3】明牌公共倍率上限从 2 提到阶梯上限 3，因此「钳到几」必须由
     * 真实结算链路（live/resolved 核心分）钉死，而不是靠一个恰好同值的数字蒙对。
     * 三条意图保留：live 与 resolved 必须一致、明牌因子只在 1..3 之间、炸弹仍独立相乘。
     */
    @Test
    void liveAndResolvedCoreScoreAgreeAndClampRevealToTheLadderMaximum() throws Exception {
        GameTable table = openingTable(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), Set.of(), Map.of());
        UUID landlord = table.getSeats().getFirst();
        setField(table, "landlord", landlord);
        setField(table, "highestBid", 3);
        setField(table, "revealMultiplier", 3);
        setField(table, "bombMultiplier", 4);
        Map<UUID, Integer> playedHands = new HashMap<>();
        playedHands.put(landlord, 2);
        playedHands.put(table.getSeats().get(1), 1);
        playedHands.put(table.getSeats().get(2), 1);
        setField(table, "playedHandCounts", playedHands);

        assertEquals(36, invokeInt(table, "liveCoreScore"), "首批 3 倍必须真的进入核心分（3×3×4）");
        assertEquals(36, invokeInt(table, "resolvedCoreScore", false));

        setField(table, "revealMultiplier", 99);
        assertEquals(36, invokeInt(table, "liveCoreScore"), "公共明牌因子上限是阶梯上限 3，不是 2");
        assertEquals(36, invokeInt(table, "resolvedCoreScore", false));

        setField(table, "revealMultiplier", 1);
        assertEquals(12, invokeInt(table, "liveCoreScore"), "未明牌时明牌因子必须是 1，不能抹成 0");
        assertEquals(12, invokeInt(table, "resolvedCoreScore", false));
    }

    @Test
    void resetAndRedealClearRevealMultiplierCountsAndOpeningState() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        GameTable table = openingTable(List.of(first, second, third), Set.of(), Map.of());
        withPlayers(Map.of(
            first, player(first, "first", true),
            second, player(second, "second", true),
            third, player(third, "third", true)
        ), () -> {
            setField(table, "revealMultiplier", 3);
            setField(table, "revealedHandPlayers", new java.util.HashSet<>(Set.of(first, second)));
            invoke(table, "resetRemainingRankCounts");
            invoke(table, "prepareFreshRoundState");

            assertEquals(GamePhase.DEALING, table.getPhase());
            assertEquals(1, table.publicRevealMultiplier());
            assertEquals(54, table.getRemainingCounts().values().stream().mapToInt(Integer::intValue).sum());
            assertFalse(table.isHandRevealed(first));

            setField(table, "revealMultiplier", 3);
            setField(table, "revealedHandPlayers", new java.util.HashSet<>(Set.of(first)));
            invoke(table, "resetRoundStateForLobby");

            assertEquals(GamePhase.LOBBY, table.getPhase());
            assertEquals(1, table.publicRevealMultiplier());
            assertTrue(table.getRemainingCounts().isEmpty(), "回大厅后不能残留上一局记牌器");
            assertFalse(table.isHandRevealed(first));

            invoke(table, "prepareFreshRoundState");
            assertEquals(GamePhase.DEALING, table.getPhase());
            assertEquals(1, table.publicRevealMultiplier(), "重新发牌必须重新从公共 ×1 开始");
            assertEquals(54, table.getRemainingCounts().values().stream().mapToInt(Integer::intValue).sum());
            return null;
        });
    }

    /**
     * 【点击冻结 + 不倒退】这是本轮最核心的行为：倍率由「点击那一刻已完成几批」决定并冻结，
     * 之后发到第三批（阶梯已降到 1）时，公共倍率必须仍然是先前的 3，绝不能跟着阶梯降回去。
     * 后明牌的玩家允许加入，但只能取 max，既不能叠乘也不得把整局拉低。
     */
    @Test
    void revealFreezesTheLadderValueAtClickAndNeverDowngradesLaterWindows() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        GameTable table = openingTable(List.of(first, second, third), Set.of(), Map.of());

        withPlayers(Map.of(
            first, player(first, "first", true),
            second, player(second, "second", true),
            third, player(third, "third", true)
        ), () -> {
            RoundOpeningCoordinator coordinator = openingCoordinator(table);
            coordinator.start(deck(54), settings(4, 20, 60));

            // 首批未发完：窗口未开，没有可冻结倍率（0 而不是 1）。
            assertEquals(0, table.openingCompletedDealBatches());
            assertFalse(table.isRevealWindowOpen());
            assertEquals(0, table.currentRevealMultiplier(), "窗口未开时可冻结倍率必须是 0");
            assertEquals(1, table.publicRevealMultiplier(), "未明牌时表级倍率仍是 1");
            assertFalse(table.canRevealHand(first));
            assertFalse(table.getRevealWindowSnapshot(first).youRevealed());
            assertEquals("明牌 ×1", table.openingRevealButtonLabel(), "窗口未开时按钮不得显示 ×0");

            tickUntil(table, () -> table.openingCompletedDealBatches() >= 1);
            assertEquals(3, table.getHand(first).size(), "首批恰好每人 3 张");
            assertEquals(3, table.getHand(second).size());
            assertEquals(3, table.getHand(third).size());
            assertTrue(table.isRevealWindowOpen(), "首批 3 张发完必须立刻开窗（发牌期就能点）");
            assertTrue(table.isOpeningRevealWindow(), "窗口语义必须已扩宽到发牌期");
            assertEquals(3, table.currentRevealMultiplier(), "首批窗口的可冻结倍率是 3");
            assertEquals("明牌 ×3", table.openingRevealButtonLabel());
            // 窗口文案的 %multiplier% 必须替换成当刻倍率的裸数字（模板自带「倍」字）。
            assertTrue(table.openingRevealLabel().contains("3 倍"),
                "窗口文案必须显示当刻倍率 3，实际：" + table.openingRevealLabel());
            var snapshotAtFirstBatch = table.getRevealWindowSnapshot(first);
            assertTrue(snapshotAtFirstBatch.open());
            assertEquals(1, snapshotAtFirstBatch.completedDealBatches());
            assertEquals(3, snapshotAtFirstBatch.currentMultiplier());
            assertEquals(1, snapshotAtFirstBatch.appliedMultiplier(), "还没人点，表级倍率仍是 1");
            assertFalse(snapshotAtFirstBatch.youRevealed());

            table.revealHand(first);
            assertTrue(table.isHandRevealed(first));
            assertEquals(3, table.publicRevealMultiplier(), "首家明牌按当刻倍率冻结为 3");
            assertEquals(1, table.getRevealCount(first));
            assertEquals(3, table.getRevealWindowSnapshot(first).appliedMultiplier());
            assertTrue(table.getRevealWindowSnapshot(first).youRevealed());
            assertFalse(table.getRevealWindowSnapshot(second).youRevealed(), "快照必须区分视角玩家");

            // 第二批：阶梯降到 2，但已冻结的 3 不得回退。
            tickUntil(table, () -> table.openingCompletedDealBatches() >= 2);
            assertEquals(6, table.getHand(first).size(), "第二批每人再 3 张");
            assertTrue(table.isRevealWindowOpen(), "批次之间窗口不得关闭");
            assertEquals(2, table.currentRevealMultiplier(), "次批窗口的可冻结倍率是 2");
            assertEquals(3, table.publicRevealMultiplier(), "已冻结的 3 倍不能被次批窗口降回 2");

            // 第三批：阶梯到下限 1，后到的人仍可明牌，但只能取 max。
            tickUntil(table, () -> table.openingCompletedDealBatches() >= 3);
            assertEquals(9, table.getHand(first).size());
            assertEquals(1, table.currentRevealMultiplier(), "第三批起可冻结倍率是下限 1");
            assertEquals(3, table.publicRevealMultiplier());
            assertEquals("明牌 ×1", table.openingRevealButtonLabel(), "按钮必须显示当刻（1 倍）而不是已生效的 3 倍");
            assertTrue(table.openingRevealLabel().contains("1 倍"),
                "窗口文案必须显示当刻 1 倍，实际：" + table.openingRevealLabel());
            assertTrue(table.canRevealHand(third), "下限窗口仍允许其他真人明牌");
            table.revealHand(third);
            assertTrue(table.isHandRevealed(third));
            assertEquals(3, table.publicRevealMultiplier(), "1 倍明牌不得把整局从 3 倍拉低");
            assertEquals(1, table.getRevealCount(third));
            assertEquals(1, table.getRevealWindowSnapshot(third).currentMultiplier());
            assertEquals(3, table.getRevealWindowSnapshot(third).appliedMultiplier());

            // 窗口关闭后：可冻结倍率归 0，但已生效倍率必须保留给结算。
            tickUntil(table, () -> !table.isRevealWindowOpen());
            assertEquals(0, table.currentRevealMultiplier(), "窗口关闭后可冻结倍率归 0");
            assertEquals(3, table.publicRevealMultiplier(), "结算前已生效倍率不得被清零");
            assertEquals(GamePhase.BIDDING, table.getPhase(), "窗口关闭后才进叫分");
            for (UUID seat : table.getSeats()) {
                assertEquals(17, table.getHand(seat).size(), "17+3：每家 17 张");
            }
            assertEquals(3, table.getBottomCards().size(), "17+3：底牌 3 张");
            return null;
        });
    }

    /**
     * 未发（首批未完成）与已过期（窗口关闭）两个边界都必须拒绝，且拒绝不得留下任何副作用：
     * 不写 revealedHandPlayers、不改倍率、不计次数。旧局则通过重新开局验证状态已被彻底重置。
     */
    @Test
    void revealBeforeFirstBatchAndAfterExpiryAreRejectedWithoutSideEffects() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        GameTable table = openingTable(List.of(first, second, third), Set.of(), Map.of());

        withPlayers(Map.of(
            first, player(first, "first", true),
            second, player(second, "second", true),
            third, player(third, "third", true)
        ), () -> {
            RoundOpeningCoordinator coordinator = openingCoordinator(table);
            coordinator.start(deck(54), settings(4, 20, 60));

            IllegalStateException tooEarly = assertThrows(IllegalStateException.class,
                () -> table.revealHand(first), "首批未发完不得明牌");
            assertEquals("不可明", tooEarly.getMessage(), "未到窗口应报「现在不能明牌」，而不是「已经明牌」");
            assertFalse(table.isHandRevealed(first), "被拒的点击不得写入手牌公开状态");
            assertEquals(0, table.getRevealCount(first));
            assertEquals(1, table.publicRevealMultiplier(), "被拒的点击不得改动倍率");

            tickUntil(table, () -> table.openingCompletedDealBatches() >= 1);
            assertTrue(table.canRevealHand(first), "首批窗口内必须能明牌");

            tickUntil(table, () -> !table.isRevealWindowOpen());
            assertEquals(GamePhase.BIDDING, table.getPhase());
            IllegalStateException expired = assertThrows(IllegalStateException.class,
                () -> table.revealHand(first), "过期点击必须被拒");
            assertEquals("不可明", expired.getMessage());
            assertFalse(table.isHandRevealed(first), "过期点击不得补写公开状态");
            assertEquals(0, table.getRevealCount(first));
            assertEquals(1, table.publicRevealMultiplier(), "过期点击不得改动倍率");
            assertFalse(table.canRevealHand(first));

            // 旧局：新一局开局后，上一局的明牌状态与倍率都不得残留。
            setField(table, "revealMultiplier", 3);
            setField(table, "revealedHandPlayers", new java.util.HashSet<>(Set.of(first)));
            invoke(table, "prepareFreshRoundState");
            coordinator.start(deck(54), settings(4, 20, 60));
            assertEquals(1, table.publicRevealMultiplier(), "新局必须从 ×1 重新开始");
            assertFalse(table.isHandRevealed(first));
            assertFalse(table.isRevealWindowOpen(), "新局首批未发完不得继承旧局窗口");
            assertFalse(table.canRevealHand(first));
            assertThrows(IllegalStateException.class, () -> table.revealHand(first));
            return null;
        });
    }

    /**
     * 【结算/UI 同源】明牌倍率只有一份事实：牌桌字段。实时状态文本、核心分、结算视图
     * 都必须读它，不能各自按批次或秒数重算，否则会出现「界面写 ×3、结算按 ×1」的错账。
     */
    @Test
    void settlementAndLiveViewsReadTheSameFrozenRevealMultiplier() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        GameTable table = openingTable(List.of(first, second, third), Set.of(), Map.of());

        withPlayers(Map.of(
            first, player(first, "first", true),
            second, player(second, "second", true),
            third, player(third, "third", true)
        ), () -> {
            RoundOpeningCoordinator coordinator = openingCoordinator(table);
            coordinator.start(deck(54), settings(4, 20, 60));
            tickUntil(table, () -> table.openingCompletedDealBatches() >= 1);
            table.revealHand(first);

            setField(table, "phase", GamePhase.PLAYING);
            setField(table, "landlord", first);
            setField(table, "highestBid", 3);
            setField(table, "bombMultiplier", 4);
            // 固定出牌手数，排除春天/反春对核心分的二次影响（本轮只验证「明牌倍率同源」）。
            Map<UUID, Integer> playedHands = new HashMap<>();
            playedHands.put(first, 2);
            playedHands.put(second, 1);
            playedHands.put(third, 1);
            setField(table, "playedHandCounts", playedHands);

            String status = table.liveMultiplierStatusText();
            assertTrue(status.contains("明牌 x3"), "实时状态必须显示真实生效的 3 倍，实际：" + status);

            int live = invokeInt(table, "liveCoreScore");
            int resolved = invokeInt(table, "resolvedCoreScore", false);
            assertEquals(36, live, "3×3×4：明牌 3 倍必须真的进分");
            assertEquals(live, resolved, "实时与结算核心分在无春天时必须一致");

            RoundSettlementCoordinator.RoundSettlement settlement =
                new RoundSettlementCoordinator.RoundSettlement(List.of(first), true, Map.of(), Map.of());
            RoundSettlementView view = (RoundSettlementView) invokeWithType(table, "settlementView",
                RoundSettlementCoordinator.RoundSettlement.class, settlement);
            assertEquals(3, view.revealMultiplier(), "结算摘要必须读同一份明牌倍率");
            assertEquals(36, view.resolvedCoreScore(), "结算核心分必须含 3 倍明牌");
            assertEquals(GameTable.coreScoreFor(view.highestBid(), table.publicRevealMultiplier(),
                view.bombMultiplier(), 1), view.resolvedCoreScore(),
                "结算核心分必须等于用同一份倍率重算的结果");
            return null;
        });
    }

    @Test
    void cancellingOldOpeningTimelineBeforeStartingNewOneDoesNotLeakOldCardsOrPhase() throws Exception {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        GameTable table = openingTable(seats, Set.of(), Map.of());
        OpeningSupport support = new OpeningSupport(table);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        setField(table, "roundOpeningCoordinator", coordinator);
        coordinator.start(deck(54), settings(3, 8, 6));
        coordinator.tick();
        assertEquals(1, table.getHand(seats.getFirst()).size());

        coordinator.cancel();
        assertFalse(coordinator.isActive());
        int refreshesAfterCancel = support.refreshes;
        coordinator.tick();
        assertEquals(refreshesAfterCancel, support.refreshes);

        setField(table, "hands", new HashMap<>());
        coordinator.start(deck(54), settings(3, 8, 6));
        coordinator.tick();
        assertEquals(1, table.getHand(seats.getFirst()).size(), "新局只应收到新时间线的第一张牌");
        assertEquals(GamePhase.DEALING, table.getPhase());
    }

    private static GameTable openingTable(List<UUID> seats, Set<UUID> bots, Map<UUID, String> botNames) throws Exception {
        GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        // Unsafe 分配出的插件实例没有 PluginMeta，而 PhysicalTableManager 构造器要用
        // NamespacedKey(plugin, ...) 取 namespace，所以必须先补插件元数据桩再构造管理器（顺序不能反）。
        setField(plugin, "pluginMeta", pluginMetaStub());
        // revealHand 的通知投递要经 plugin.scheduler() 建任务；Unsafe 分配的插件没有 server，
        // 直接落到 PaperSchedulerBackend 会 NPE，所以装一个只记录、不执行的后端替身（行为测试不建 Bukkit 任务）。
        setField(plugin, "scheduler", new MuzScheduler(new NoopSchedulerBackend()));
        setField(plugin, "physicalTableManager", new PhysicalTableManager(plugin));
        setPluginLogger(plugin);
        setField(table, "plugin", plugin);
        setField(table, "name", "opening-test");
        setField(table, "seats", new ArrayList<>(seats));
        setField(table, "readyPlayers", Collections.newSetFromMap(new HashMap<UUID, Boolean>()));
        setField(table, "totalScores", new LinkedHashMap<UUID, Integer>());
        setField(table, "bids", new LinkedHashMap<UUID, Integer>());
        setField(table, "tieBreakBids", new LinkedHashMap<UUID, Integer>());
        setField(table, "roles", new HashMap<UUID, PlayerRole>());
        setField(table, "hands", new HashMap<UUID, List<DoudizhuCard>>());
        setField(table, "selections", new HashMap<UUID, Set<Integer>>());
        setField(table, "playedHandCounts", new HashMap<UUID, Integer>());
        setField(table, "remainingRankCounts", new EnumMap<CardRank, Integer>(CardRank.class));
        setField(table, "botNames", new LinkedHashMap<>(botNames));
        setField(table, "revealedHandPlayers", new java.util.HashSet<UUID>());
        setField(table, "farmerBoostChoices", new LinkedHashMap<UUID, Integer>());
        setField(table, "recentLobbyEntries", new ArrayList<net.kyori.adventure.text.Component>());
        setField(table, "recentTrickEntries", new ArrayList<>());
        setField(table, "random", new Random(1L));
        setField(table, "phase", GamePhase.LOBBY);
        setField(table, "bombMultiplier", 1);
        setField(table, "revealMultiplier", 1);
        setField(table, "effectCoordinator", new TableEffectCoordinator(plugin, new Random(1L), table::getSeats, Bukkit::getPlayer));
        setField(table, "actionBarOverlay", new ActionBarOverlayService(plugin));
        setField(table, "timedOutPlayCoordinator", unsafe().allocateInstance(TimedOutPlayCoordinator.class));
        setField(table, "openingSettings", settings(3, 8, 6));
        return table;
    }

    private static RoundOpeningCoordinator startReveal(GameTable table) throws Exception {
        RoundOpeningCoordinator coordinator = openingCoordinator(table);
        coordinator.start(deck(54), settings(3, 8, 6));
        int guard = 0;
        while (!coordinator.isRevealWindowOpen() && guard++ < 200) {
            coordinator.tick();
        }
        assertTrue(coordinator.isRevealWindowOpen(), "测试夹具必须进入真实明牌窗口");
        return coordinator;
    }

    private static RoundOpeningCoordinator openingCoordinator(GameTable table) throws Exception {
        OpeningSupport support = new OpeningSupport(table);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        setField(table, "roundOpeningCoordinator", coordinator);
        return coordinator;
    }

    /**
     * 测试模板带 {@code %multiplier%}，用来断言「按钮/窗口文案读的是当刻可冻结倍率」；
     * 生产默认文案来自 config.yml（另由配置 owner 维护），这里刻意不依赖配置文件。
     */
    private static RoundOpeningSettings settings(int batchInterval, int flipTicks, int revealTicks) {
        return new RoundOpeningSettings(
            batchInterval,
            flipTicks,
            revealTicks,
            new RoundOpeningSettings.Messages(
                "发牌",
                "翻转",
                "明牌 %seconds% 秒 %multiplier% 倍",
                "%player% 明牌 %multiplier% 倍",
                "明牌 ×%multiplier%",
                "结束",
                "已明",
                "机器人",
                "不可明"
            )
        );
    }

    /** 推进真实协调器直到条件成立；时间线提前结束或超出上限都视为用例前提失败。 */
    private static void tickUntil(GameTable table, java.util.function.BooleanSupplier condition) {
        RoundOpeningCoordinator coordinator = table == null ? null : coordinatorOf(table);
        if (coordinator == null) {
            throw new AssertionError("牌桌没有装配开局协调器");
        }
        int guard = 0;
        while (!condition.getAsBoolean()) {
            if (guard++ > 400) {
                throw new AssertionError("条件未在 400 tick 内满足");
            }
            // 关闭窗口的那一 tick 本身会返回 true 并让条件成立，不能把它当成「提前结束」。
            if (coordinator.tick() && !condition.getAsBoolean()) {
                throw new AssertionError("时间线在条件满足前就结束了");
            }
        }
    }

    private static RoundOpeningCoordinator coordinatorOf(GameTable table) {
        try {
            Field field = findField(GameTable.class, "roundOpeningCoordinator");
            field.setAccessible(true);
            return (RoundOpeningCoordinator) field.get(table);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object invokeWithType(GameTable table, String methodName, Class<?> parameterType, Object argument)
        throws Exception {
        Method method = GameTable.class.getDeclaredMethod(methodName, parameterType);
        method.setAccessible(true);
        return method.invoke(table, argument);
    }

    private static List<DoudizhuCard> deck(int size) {
        List<DoudizhuCard> deck = new ArrayList<>();
        CardRank[] ranks = CardRank.values();
        CardSuit[] suits = CardSuit.values();
        for (int id = 0; id < size; id++) {
            deck.add(new DoudizhuCard(id, ranks[id % ranks.length], suits[id % suits.length]));
        }
        return deck;
    }

    private static int invokeInt(GameTable table, String methodName, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int index = 0; index < args.length; index++) {
            types[index] = args[index] instanceof Boolean ? boolean.class : args[index].getClass();
        }
        Method method = GameTable.class.getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return (int) method.invoke(table, args);
    }

    private static Object invoke(GameTable table, String methodName, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int index = 0; index < args.length; index++) {
            types[index] = args[index].getClass();
        }
        Method method = GameTable.class.getDeclaredMethod(methodName, types);
        method.setAccessible(true);
        return method.invoke(table, args);
    }

    private static Player player(UUID id, String name, boolean online) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "isOnline" -> online;
                case "getPlayerProfile" -> null;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static <T> T withPlayers(Map<UUID, Player> players, Callable<T> action) throws Exception {
        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Server previous = (Server) serverField.get(null);
        Server server = (Server) Proxy.newProxyInstance(
            Server.class.getClassLoader(),
            new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getPlayer") && args != null && args.length == 1) {
                    if (args[0] instanceof UUID uuid) {
                        return players.get(uuid);
                    }
                    if (args[0] instanceof String name) {
                        return players.values().stream().filter(player -> player.getName().equals(name)).findFirst().orElse(null);
                    }
                }
                return defaultValue(method.getReturnType());
            }
        );
        serverField.set(null, server);
        try {
            return action.call();
        } finally {
            serverField.set(null, previous);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }

    private static void setPluginLogger(DoudizhuPlugin plugin) throws Exception {
        setField(plugin, "logger", Logger.getLogger("GameTableOpeningFlowTest"));
    }

    /**
     * 只登记不执行的后端替身：开局行为测试只关心状态机（阶段、明牌、倍数），
     * 不应因为一次通知投递就在测试里真的建 Bukkit 任务（也因此不依赖 Bukkit 服务器实例）。
     */
    private static final class NoopSchedulerBackend implements linmumua.doudizhu.scheduler.SchedulerBackend {
        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period,
                                                 java.util.function.Consumer<MuzScheduler.TaskHandle> task) {
            return new NoopTaskHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runRegion(org.bukkit.Location location, long delay, long period,
                                                 java.util.function.Consumer<MuzScheduler.TaskHandle> task) {
            return new NoopTaskHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runEntity(org.bukkit.entity.Entity entity, long delay, long period,
                                                 java.util.function.Consumer<MuzScheduler.TaskHandle> task) {
            return new NoopTaskHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runPlayer(org.bukkit.entity.Player player, long delay, long period,
                                                 java.util.function.Consumer<MuzScheduler.TaskHandle> task) {
            return new NoopTaskHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runAsync(long delay, long period,
                                                java.util.function.Consumer<MuzScheduler.TaskHandle> task) {
            return new NoopTaskHandle();
        }
    }

    private static final class NoopTaskHandle implements MuzScheduler.TaskHandle {
        @Override
        public void cancel() {
        }
    }

    /**
     * Unsafe 分配的插件实例没有 PluginMeta，而 {@code new NamespacedKey(plugin, …)} 需要
     * {@code plugin.getPluginMeta().namespace()}。这里给一个最小桩：只保证 namespace/name 可用，
     * 其余集合类方法返回空集合，避免后续调用点被 null 砸中。
     */
    private static Object pluginMetaStub() {
        return java.lang.reflect.Proxy.newProxyInstance(
            GameTableOpeningFlowTest.class.getClassLoader(),
            new Class<?>[] {io.papermc.paper.plugin.configuration.PluginMeta.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("namespace".equals(name) || "getName".equals(name)) {
                    return "muz";
                }
                if ("toString".equals(name)) {
                    return "MUZ-PluginMetaStub";
                }
                if ("equals".equals(name)) {
                    return args != null && args.length == 1 && proxy == args[0];
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == int.class) {
                    return 0;
                }
                if (java.util.List.class.isAssignableFrom(returnType)) {
                    return java.util.List.of();
                }
                if (java.util.Set.class.isAssignableFrom(returnType)) {
                    return java.util.Set.of();
                }
                return null;
            });
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class OpeningSupport implements RoundOpeningCoordinator.Support {
        private final GameTable table;
        private int refreshes;

        private OpeningSupport(GameTable table) {
            this.table = table;
        }

        @Override
        public boolean canScheduleTasks() {
            return false;
        }

        @Override
        public MuzScheduler scheduler() {
            throw new AssertionError("该行为测试不应创建 Bukkit 任务");
        }

        @Override
        public List<UUID> seats() {
            return table.getSeats();
        }

        @Override
        public void setOpeningPhase(GamePhase phase) {
            setPhase(phase);
        }

        @Override
        public void appendOpeningCard(UUID playerId, DoudizhuCard card) {
            try {
                Field field = GameTable.class.getDeclaredField("hands");
                field.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<UUID, List<DoudizhuCard>> hands = (Map<UUID, List<DoudizhuCard>>) field.get(table);
                hands.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(card);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public void assignOpeningBottomCards(List<DoudizhuCard> cards) {
            try {
                setField(table, "bottomCards", List.copyOf(cards));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public void sortOpeningHands() {
            for (UUID seat : table.getSeats()) {
                List<DoudizhuCard> hand = new ArrayList<>(table.getHand(seat));
                hand.sort(DoudizhuCard.ORDER);
                try {
                    Field field = GameTable.class.getDeclaredField("hands");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<UUID, List<DoudizhuCard>> hands = (Map<UUID, List<DoudizhuCard>>) field.get(table);
                    hands.put(seat, hand);
                } catch (ReflectiveOperationException exception) {
                    throw new AssertionError(exception);
                }
            }
        }

        @Override
        public void refreshPhysicalTable() {
            refreshes++;
        }

        @Override
        public void tickOpeningActionBar() {
        }

        @Override
        public void onOpeningRevealWindowStarted() {
            setPhase(GamePhase.REVEALING);
        }

        @Override
        public void onOpeningRevealWindowFinished() {
            setPhase(GamePhase.BIDDING);
        }

        private void setPhase(GamePhase phase) {
            try {
                setField(table, "phase", phase);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
