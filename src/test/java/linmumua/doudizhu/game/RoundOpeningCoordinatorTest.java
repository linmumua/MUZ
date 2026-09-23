package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.scheduler.MuzScheduler;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Proxy;
import java.util.UUID;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

class RoundOpeningCoordinatorTest {
    @Test
    void usesDeterministicBatchStartsAndKeepsThe360DegreeFrameBeforeReveal() {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        FakeSupport support = new FakeSupport(seats);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        RoundOpeningSettings settings = new RoundOpeningSettings(
            4,
            20,
            60,
            new RoundOpeningSettings.Messages("发牌", "翻转", "明牌 %seconds%", "明牌", "明牌 ×2", "结束", "已明", "机器人", "不可明")
        );
        List<DoudizhuCard> deck = deckInDescendingIds();

        coordinator.start(deck, settings);
        assertEquals(GamePhase.DEALING, support.phase);
        assertEquals(0.0, coordinator.openingFlipDegrees(), "发牌完成后翻转应从 0 度开始");
        for (int tick = 0; tick <= 69; tick++) {
            support.currentTick = tick;
            coordinator.tick();
        }

        assertEquals(expectedDealTicks(), support.dealTicks, "每个座位批次起点间隔固定为 4 tick");
        assertEquals(17, support.hands.get(seats.get(0)).size());
        assertEquals(17, support.hands.get(seats.get(1)).size());
        assertEquals(17, support.hands.get(seats.get(2)).size());
        assertEquals(List.of(53, 52, 51), ids(support.hands.get(seats.get(0)).subList(0, 3)), "发牌阶段保持洗牌顺序");
        assertEquals(3, support.bottomCards.size());
        assertEquals(54, uniqueCards(support), "54 张牌必须全部唯一");
        assertEquals(0.0, coordinator.openingFlipDegrees(), "最终一张牌发完时翻转尚未推进");

        support.currentTick = 70;
        coordinator.tick();
        assertEquals(18.0, coordinator.openingFlipDegrees());
        for (int tick = 71; tick <= 79; tick++) {
            support.currentTick = tick;
            coordinator.tick();
        }
        assertEquals(180.0, coordinator.openingFlipDegrees());
        assertEquals(1, support.sortCount);
        assertEquals(GamePhase.DEALING, support.phase, "翻转阶段仍属于 DEALING");

        for (int tick = 80; tick <= 89; tick++) {
            support.currentTick = tick;
            coordinator.tick();
        }
        assertEquals(360.0, coordinator.openingFlipDegrees());
        assertEquals(GamePhase.DEALING, support.phase, "360 度最终帧必须完整保留一个 tick");
        int refreshesAtFinalFrame = support.refreshes;

        support.currentTick = 90;
        coordinator.tick();
        assertEquals(GamePhase.REVEALING, support.phase);
        assertEquals(360.0, coordinator.openingFlipDegrees());
        assertEquals(60, coordinator.openingRemainingTicks());
        assertEquals(refreshesAtFinalFrame + 1, support.refreshes, "进入明牌窗口只刷新一次视觉状态");
        int actionBarsAtRevealStart = support.actionBarTicks;

        for (int tick = 91; tick <= 149; tick++) {
            support.currentTick = tick;
            coordinator.tick();
        }
        assertEquals(1, coordinator.openingRemainingTicks());
        assertEquals(actionBarsAtRevealStart + 59, support.actionBarTicks, "明牌倒计时每 tick 都应广播");
        int refreshesBeforeFinish = support.refreshes;
        support.currentTick = 150;
        assertTrue(coordinator.tick(), "结束时间线返回 true，通知调度器取消定时任务");
        assertEquals(GamePhase.BIDDING, support.phase, "截止 tick 先结束窗口再进入叫地主");
        assertFalse(coordinator.isActive());
        assertEquals(0, coordinator.openingRemainingTicks());
        assertEquals(refreshesBeforeFinish, support.refreshes, "窗口截止不应以剩余 0 的 REVEALING 刷新发牌文案");
    }

    @Test
    void revealDeadlineUsesConfiguredTicksAndCancelInvalidatesManualTimeline() {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        FakeSupport support = new FakeSupport(seats);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        RoundOpeningSettings settings = new RoundOpeningSettings(
            3,
            8,
            5,
            new RoundOpeningSettings.Messages("发牌", "翻转", "明牌 %seconds%", "明牌", "明牌 ×2", "结束", "已明", "机器人", "不可明")
        );
        coordinator.start(deckInDescendingIds(), settings);
        int tick = 0;
        while (!coordinator.isRevealWindowOpen()) {
            support.currentTick = tick++;
            coordinator.tick();
        }
        assertEquals(5, coordinator.openingRemainingTicks());
        for (int remaining = 5; remaining > 1; remaining--) {
            support.currentTick = tick++;
            coordinator.tick();
        }
        assertEquals(1, coordinator.openingRemainingTicks());
        int refreshes = support.refreshes;
        support.currentTick = tick;
        assertTrue(coordinator.tick(), "结束时间线返回 true，通知调度器取消定时任务");
        assertEquals(GamePhase.BIDDING, support.phase);
        assertFalse(coordinator.isActive());
        assertEquals(refreshes, support.refreshes);

        coordinator.cancel();
        coordinator.tick();
        assertEquals(refreshes, support.refreshes);
        assertFalse(coordinator.isRevealWindowOpen());
    }

    @Test
    void staleScheduledCallbackCannotDealAfterRestart() throws Exception {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        SchedulerCapture capture = new SchedulerCapture();
        FakeSupport support = new FakeSupport(seats, capture.scheduler);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        java.lang.reflect.Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Server previous = Bukkit.getServer();
        try {
            serverField.set(null, capture.server);
            coordinator.start(deckInDescendingIds(), settings(4, 8, 5));
            Runnable oldCallback = capture.callbacks.getFirst();
            int cardsBeforeRestart = support.hands.get(seats.getFirst()).size();
            coordinator.start(deckInDescendingIds(), settings(4, 8, 5));
            assertEquals(2, capture.callbacks.size());

            oldCallback.run();

            assertEquals(cardsBeforeRestart, support.hands.get(seats.getFirst()).size(), "旧定时回调不得向新局发牌");
            assertTrue(capture.cancelledTasks > 0, "旧回调应通过自身句柄取消");
            assertEquals(GamePhase.DEALING, support.phase);
        } finally {
            serverField.set(null, previous);
        }
    }

    @Test
    void coreScoreIncludesPublicRevealAtMostOnceAndKeepsBombSeparate() {
        assertEquals(48, GameTable.coreScoreFor(3, 2, 4, 2));
        assertEquals(48, GameTable.coreScoreFor(3, 9, 4, 2), "多人明牌不能叠乘");
        assertEquals(24, GameTable.coreScoreFor(3, 2, 4, 1));
    }

    private static List<Integer> ids(List<DoudizhuCard> cards) {
        return cards.stream().map(DoudizhuCard::id).toList();
    }

    private static RoundOpeningSettings settings(int batchInterval, int flipTicks, int revealTicks) {
        return new RoundOpeningSettings(
            batchInterval,
            flipTicks,
            revealTicks,
            new RoundOpeningSettings.Messages("发牌", "翻转", "明牌 %seconds%", "明牌", "明牌 ×2", "结束", "已明", "机器人", "不可明")
        );
    }

    private static List<Integer> expectedDealTicks() {
        List<Integer> ticks = new ArrayList<>();
        for (int batch = 0; batch < 6; batch++) {
            int cardsPerSeat = batch < 5 ? 3 : 2;
            int batchStart = batch * 12;
            for (int seat = 0; seat < 3; seat++) {
                int seatStart = batchStart + seat * 4;
                for (int card = 0; card < cardsPerSeat; card++) {
                    ticks.add(seatStart + card);
                }
            }
        }
        return ticks;
    }

    private static int uniqueCards(FakeSupport support) {
        Set<DoudizhuCard> cards = new HashSet<>(support.bottomCards);
        support.hands.values().forEach(cards::addAll);
        return cards.size();
    }

    private static List<DoudizhuCard> deckInDescendingIds() {
        List<DoudizhuCard> cards = new ArrayList<>();
        CardRank[] ranks = CardRank.values();
        CardSuit[] suits = CardSuit.values();
        int id = 0;
        for (int rank = 0; rank < 13; rank++) {
            for (int suit = 0; suit < 4; suit++) {
                cards.add(new DoudizhuCard(id++, ranks[rank], suits[suit]));
            }
        }
        cards.add(new DoudizhuCard(id++, CardRank.SMALL_JOKER, CardSuit.JOKER));
        cards.add(new DoudizhuCard(id, CardRank.BIG_JOKER, CardSuit.JOKER));
        cards.sort((left, right) -> Integer.compare(right.id(), left.id()));
        return cards;
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        return null;
    }

    private static final class SchedulerCapture {
        private final List<Runnable> callbacks = new ArrayList<>();
        private final Server server;
        private final MuzScheduler scheduler;
        private int cancelledTasks;

        private SchedulerCapture() {
            BukkitTask task = (BukkitTask) Proxy.newProxyInstance(
                BukkitTask.class.getClassLoader(),
                new Class<?>[] {BukkitTask.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("cancel")) {
                        cancelledTasks++;
                        return null;
                    }
                    if (method.getName().equals("getTaskId")) {
                        return 1;
                    }
                    if (method.getName().equals("isCancelled")) {
                        return cancelledTasks > 0;
                    }
                    return defaultValue(method.getReturnType());
                }
            );
            BukkitScheduler bukkitScheduler = (BukkitScheduler) Proxy.newProxyInstance(
                BukkitScheduler.class.getClassLoader(),
                new Class<?>[] {BukkitScheduler.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("cancelTask")) {
                        cancelledTasks++;
                        return null;
                    }
                    if (method.getName().equals("runTaskTimer") && args != null && args.length == 4 && args[1] instanceof Runnable runnable) {
                        callbacks.add(runnable);
                        return task;
                    }
                    return defaultValue(method.getReturnType());
                }
            );
            GlobalRegionScheduler globalRegionScheduler = (GlobalRegionScheduler) Proxy.newProxyInstance(
                GlobalRegionScheduler.class.getClassLoader(),
                new Class<?>[] {GlobalRegionScheduler.class},
                (proxy, method, args) -> {
                    if (args != null && args.length > 1 && args[1] instanceof Consumer consumer) {
                        ScheduledTask scheduledTask = (ScheduledTask) Proxy.newProxyInstance(
                            ScheduledTask.class.getClassLoader(),
                            new Class<?>[] {ScheduledTask.class},
                            (taskProxy, taskMethod, taskArgs) -> {
                                if (taskMethod.getName().equals("cancel")) {
                                    cancelledTasks++;
                                    return null;
                                }
                                if (taskMethod.getName().equals("isCancelled")) {
                                    return cancelledTasks > 0;
                                }
                                return defaultValue(taskMethod.getReturnType());
                            }
                        );
                        callbacks.add(() -> consumer.accept(scheduledTask));
                        return scheduledTask;
                    }
                    return defaultValue(method.getReturnType());
                }
            );
            server = (Server) Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getScheduler")) {
                        return bukkitScheduler;
                    }
                    if (method.getName().equals("getGlobalRegionScheduler")) {
                        return globalRegionScheduler;
                    }
                    return defaultValue(method.getReturnType());
                }
            );
            Plugin plugin = (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, args) -> method.getName().equals("getServer")
                    ? server
                    : defaultValue(method.getReturnType())
            );
            scheduler = new MuzScheduler(plugin);
        }
    }

    /**
     * 实服现象：对局中途发牌动画停住并刷屏报错。调度门面在周期回调抛异常时会取消任务，
     * 所以只要渲染异常能逃出 tick()，发牌 timer 就被永久取消、牌桌停在 DEALING。
     * 这里让每一次渲染都抛异常，时间线仍必须走完发牌、翻转、明牌窗口并进入叫分，
     * 且 tick() 不得向调度器抛出异常，失败日志必须限频。
     */
    @Test
    void renderingFailuresNeverStopTheDealingTimeline() {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        FakeSupport support = new FakeSupport(seats);
        support.failRendering = true;
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        RoundOpeningSettings settings = new RoundOpeningSettings(
            4,
            20,
            60,
            new RoundOpeningSettings.Messages("发牌", "翻转", "明牌 %seconds%", "明牌", "明牌 ×2", "结束", "已明", "机器人", "不可明")
        );

        coordinator.start(deckInDescendingIds(), settings);
        boolean finished = false;
        for (int tick = 0; tick < 400 && !finished; tick++) {
            support.currentTick = tick;
            finished = coordinator.tick();
        }

        assertTrue(finished, "渲染失败不得让发牌时间线停住");
        assertEquals(GamePhase.BIDDING, support.phase, "明牌窗口结束后必须进入叫分");
        assertEquals(17, support.hands.get(seats.get(0)).size());
        assertEquals(3, support.bottomCards.size());
        assertTrue(support.reportedFailures >= 1, "渲染失败必须留日志，不能静默吞掉");
        assertTrue(support.reportedFailures <= 3,
            "失败日志必须限频，实际记录 " + support.reportedFailures + " 次");
    }

    private static final class FakeSupport implements RoundOpeningCoordinator.Support {
        private final List<UUID> seats;
        private final Map<UUID, List<DoudizhuCard>> hands = new LinkedHashMap<>();
        private final List<Integer> dealTicks = new ArrayList<>();
        private final MuzScheduler scheduler;
        private final boolean canScheduleTasks;
        private GamePhase phase = GamePhase.LOBBY;
        private List<DoudizhuCard> bottomCards = List.of();
        private int sortCount;
        private int refreshes;
        private int actionBarTicks;
        private int currentTick;
        /* 为 true 时每次渲染都抛异常，模拟实服刷屏报错。 */
        private boolean failRendering;
        private int reportedFailures;

        private FakeSupport(List<UUID> seats) {
            this(seats, null);
        }

        private FakeSupport(List<UUID> seats, MuzScheduler scheduler) {
            this.seats = seats;
            this.scheduler = scheduler;
            this.canScheduleTasks = scheduler != null;
            for (UUID seat : seats) {
                hands.put(seat, new ArrayList<>());
            }
        }

        @Override
        public boolean canScheduleTasks() {
            return canScheduleTasks;
        }

        @Override
        public MuzScheduler scheduler() {
            if (scheduler == null) {
                throw new AssertionError("测试时间线不应创建 Bukkit 任务");
            }
            return scheduler;
        }

        @Override
        public List<UUID> seats() {
            return seats;
        }

        @Override
        public void setOpeningPhase(GamePhase phase) {
            this.phase = phase;
        }

        @Override
        public void appendOpeningCard(UUID playerId, DoudizhuCard card) {
            hands.get(playerId).add(card);
            dealTicks.add(currentTick);
        }

        @Override
        public void assignOpeningBottomCards(List<DoudizhuCard> cards) {
            bottomCards = List.copyOf(cards);
        }

        @Override
        public void sortOpeningHands() {
            hands.values().forEach(hand -> hand.sort(DoudizhuCard.ORDER));
            sortCount++;
        }

        @Override
        public void refreshPhysicalTable() {
            refreshes++;
            if (failRendering) {
                throw new IllegalStateException("模拟 Folia 跨 region 渲染失败");
            }
        }

        @Override
        public void reportRenderFailure(String step, RuntimeException failure, int count) {
            reportedFailures++;
        }

        @Override
        public void tickOpeningActionBar() {
            actionBarTicks++;
            if (failRendering) {
                throw new IllegalStateException("模拟 ActionBar 渲染失败");
            }
        }

        @Override
        public void onOpeningRevealWindowStarted() {
        }

        @Override
        public void onOpeningRevealWindowFinished() {
            phase = GamePhase.BIDDING;
        }
    }
}
