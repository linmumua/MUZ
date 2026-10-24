package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.scheduler.MuzScheduler;
import java.util.ArrayList;
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

/**
 * 开局时间线的行为回归：发牌节奏、翻转帧、以及**第 3 批后只开一次的 60 tick 明牌窗口**。
 *
 * <p>【1.10.73 机制】窗口不再是「首批即开、贯穿整条时间线」，而是：
 * <ul>
 *   <li>第 3 批（每人 9 张）发完那一刻打开，持续**恰好 60 tick**；</li>
 *   <li>归零只关窗口，**不**截断发牌 / 翻转 / revealTicks 时间线；</li>
 *   <li>每局只开一次并只公告一次——第 4/5/6 批发完时不得重开，配置间隔 20/21/更大时同样如此。</li>
 * </ul>
 *
 * <p>【为什么 helper 必须推进单调时钟】发牌会把「当前 tick」写进批次记录；只调用
 * {@code coordinator.tick()} 而不推进夹具时钟，会让推进过的牌全部记在 tick 0 上，
 * 于是「批次起点间隔」这类依赖真实时钟的断言变成永远成立的空话。
 */
class RoundOpeningCoordinatorTest {

    /**
     * 发牌节奏、180° 中点排序、360° 最终帧、17+3 完整发牌，以及窗口在第 3 批发完那一刻开启。
     */
    @Test
    void dealCadenceFinalFlipFrameAndWindowOpensAfterThirdBatch() {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        FakeSupport support = new FakeSupport(seats);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        coordinator.start(deckInDescendingIds(), settings(4, 20, 60));

        assertEquals(GamePhase.DEALING, support.phase);
        assertEquals(0.0, coordinator.openingFlipDegrees(), "发牌完成后翻转应从 0 度开始");

        // 第 1、2 批：窗口必须仍关着（每座 3 张，共 9 张为一批）。
        advanceUntil(support, coordinator, () -> totalCards(support) >= 9,
            "首批未在有限 tick 内发完");
        assertFalse(coordinator.isRevealWindowOpen(), "第 1 批发完不得开窗");
        assertEquals(0, coordinator.openingRemainingTicks(), "窗口关着时不得有剩余倒计时");
        assertFalse(coordinator.openingRevealLabel().contains("明牌"),
            "第 1 批不得出现可明牌提示，实际：" + coordinator.openingRevealLabel());

        advanceUntil(support, coordinator, () -> coordinator.completedDealBatches() >= 2,
            "第 2 批未在有限 tick 内发完");
        assertFalse(coordinator.isRevealWindowOpen(), "第 2 批发完不得开窗");

        // 发牌顺序必须在「180° 中点排序」之前断言：排序之后手牌会按牌力重排。
        assertEquals(List.of(53, 52, 51), ids(support.hands.get(seats.get(0))).subList(0, 3),
            "发牌阶段保持洗牌顺序");

        // 第 3 批：窗口必须在这一刻打开，且剩余正好 60 tick、倍率 3。
        advanceUntil(support, coordinator, () -> coordinator.completedDealBatches() >= 3,
            "第 3 批未在有限 tick 内发完");
        assertEquals(3, coordinator.completedDealBatches());
        assertEquals(27, totalCards(support), "第 3 批是 3 座 × 9 张 = 27 张");
        for (UUID seat : seats) {
            assertEquals(9, support.hands.get(seat).size(),
                "第 3 批后每座累计 9 张（3 批 × 每批 3 张）");
        }
        assertTrue(coordinator.isRevealWindowOpen(), "第 3 批发完那一刻窗口必须打开");
        assertEquals(60, coordinator.openingRemainingTicks(), "窗口开启时剩余必须是完整的 60 tick");
        assertEquals(3, coordinator.currentRevealMultiplier(), "窗口开启批次的可冻结倍率是 3");
        assertTrue(coordinator.openingRevealLabel().contains("3"),
            "窗口文案必须报告当刻倍率 3，实际：" + coordinator.openingRevealLabel());

        // 继续推进：翻转结束前一刻必须已呈现完整 360°，窗口关闭后才进叫分。
        Integer flipEndTick = null;
        Integer closeTick = null;
        while (closeTick == null) {
            double degreesBefore = coordinator.openingFlipDegrees();
            boolean finished = advanceOne(support, coordinator);
            if (flipEndTick == null && support.phase == GamePhase.REVEALING) {
                flipEndTick = support.currentTick;
                assertEquals(360.0, degreesBefore,
                    "翻转结束前一 tick 就必须已经呈现完整的 360 度最终帧");
            }
            if (finished) {
                closeTick = support.currentTick;
            } else if (support.currentTick > 4000) {
                throw new AssertionError("时间线未在有限 tick 内结束");
            }
        }

        assertNotNull(flipEndTick, "时间线必须经过翻转结束");
        assertEquals(GamePhase.BIDDING, support.phase, "窗口截止后才进叫分");
        assertFalse(coordinator.isActive());

        // 完整发牌与既有渲染意图（顺序、节奏、中点排序、最终帧）继续按原样断言。
        assertEquals(expectedDealTicks(4), support.dealTicks,
            "每个座位批次起点间隔固定为 4 tick");
        assertEquals(17, support.hands.get(seats.get(0)).size());
        assertEquals(17, support.hands.get(seats.get(1)).size());
        assertEquals(17, support.hands.get(seats.get(2)).size());
        Set<Integer> firstSeatIds = new HashSet<>();
        support.hands.get(seats.get(0)).forEach(card -> firstSeatIds.add(card.id()));
        assertTrue(firstSeatIds.containsAll(List.of(53, 52, 51)), "排序不得丢牌或换牌");
        assertEquals(3, support.bottomCards.size());
        assertEquals(54, uniqueCards(support), "54 张牌必须全部唯一");
        assertEquals(1, support.sortCount, "180° 中点只排序一次");
    }

    /**
     * 窗口必须**恰好**持续 60 tick：开启那 tick 剩余 60，之后每 tick 减一，归零即关；
     * 关闭**不**截断时间线，后续发牌/翻转/revealTicks 仍完整跑完。
     */
    @Test
    void windowLastsExactlySixtyTicksAndDoesNotTruncateTheTimeline() {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        FakeSupport support = new FakeSupport(seats);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        coordinator.start(deckInDescendingIds(), settings(4, 20, 60));

        advanceUntil(support, coordinator, coordinator::isRevealWindowOpen, "窗口从未打开");
        assertEquals(60, coordinator.openingRemainingTicks());

        int observed = 0;
        int previous = Integer.MAX_VALUE;
        boolean sawExpiryRefresh = false;
        while (coordinator.isRevealWindowOpen()) {
            int remaining = coordinator.openingRemainingTicks();
            assertTrue(remaining > 0, "窗口开着时剩余必须为正，实际 " + remaining);
            assertTrue(remaining <= previous, "倒计时必须单调不增");
            previous = remaining;
            observed++;
            int refreshesBefore = support.refreshes;
            advanceOne(support, coordinator);
            if (!coordinator.isRevealWindowOpen()) {
                assertEquals(refreshesBefore + 1, support.refreshes,
                    "窗口归零的同一 tick 必须恰好请求一次世界刷新，移除过期明牌按钮");
                sawExpiryRefresh = true;
            }
        }
        assertEquals(60, observed, "窗口必须恰好持续 60 tick（3 秒）");
        assertTrue(sawExpiryRefresh, "必须真实观察到窗口关闭及对应世界刷新");
        assertFalse(coordinator.isRevealWindowOpen());
        assertEquals(0, coordinator.currentRevealMultiplier(), "关窗后不可冻结任何倍率");

        // 关键：窗口关闭**不**等于时间线结束。间隔 4 时 60 tick 窗口比发牌更长，
        // 关窗时 51 张已发完，但翻转/revealTicks 尾部仍在跑——这正是「关窗只关限时窗口」的证据。
        assertTrue(coordinator.isActive(), "关窗不得让整条开局时间线结束");
        assertEquals(51, totalCards(support), "关窗时发牌应已完整（窗口没有提前掐断，也没有被截断）");
        assertNotEquals(GamePhase.BIDDING, support.phase, "关窗本身不得直接跳到叫分");

        advanceUntil(support, coordinator, () -> support.phase == GamePhase.BIDDING,
            "关窗后时间线未继续推进到叫分");
        for (UUID seat : seats) {
            assertEquals(17, support.hands.get(seat).size(), "关窗后仍必须发满 17 张");
        }
        assertEquals(3, support.bottomCards.size());
        assertEquals(54, uniqueCards(support));
    }

    /**
     * 间隔 20 / 21 / 更大时，窗口都只能开一次、只公告一次。
     *
     * <p>这是 one-shot 闩锁的核心回归：窗口条件「已完成批数 &gt;= 3」在第 4/5/6 批仍然成立，
     * 没有闩锁就会每批之后重开一次并重复公告。间隔越大，重开越容易被玩家看见。
     */
    @Test
    void windowOpensAndAnnouncesOnlyOnceForInterval20And21AndLarge() {
        for (int interval : new int[] {20, 21, 150}) {
            List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            FakeSupport support = new FakeSupport(seats);
            RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
            coordinator.start(deckInDescendingIds(), settings(interval, 20, 5));

            int opens = 0;
            boolean wasOpen = false;
            int guard = 0;
            while (coordinator.isActive() && guard++ < 20000) {
                boolean open = coordinator.isRevealWindowOpen();
                if (open && !wasOpen) {
                    opens++;
                }
                wasOpen = open;
                advanceOne(support, coordinator);
            }
            assertFalse(coordinator.isActive(), "interval=" + interval + " 时时间线必须结束");
            assertEquals(GamePhase.BIDDING, support.phase);
            assertEquals(1, opens, "interval=" + interval + " 时窗口只允许打开一次");
            assertEquals(1, support.revealWindowAnnouncements,
                "interval=" + interval + " 时明牌公告只允许出现一次");
            for (UUID seat : seats) {
                assertEquals(17, support.hands.get(seat).size(),
                    "interval=" + interval + " 时仍必须发满 17 张");
            }
            assertEquals(54, uniqueCards(support));
        }
    }

    /**
     * 窗口内阶梯必须真的走 3→2→1：间隔 4 时窗口（60 tick）覆盖第 3~6 批，
     * 因此能依次观察到 3 / 2 / 1 三档——这正是「3→2→1 可用」的证据。
     */
    @Test
    void ladderIsThreeThenTwoThenOneInsideTheWindow() {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        FakeSupport support = new FakeSupport(seats);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        coordinator.start(deckInDescendingIds(), settings(4, 20, 60));

        advanceUntil(support, coordinator, coordinator::isRevealWindowOpen, "窗口从未打开");
        Map<Integer, Integer> multiplierAtBatch = new LinkedHashMap<>();
        int guard = 0;
        while (coordinator.isRevealWindowOpen() && guard++ < 400) {
            int batch = coordinator.completedDealBatches();
            if (batch >= 3 && coordinator.isRevealWindowOpen()) {
                multiplierAtBatch.putIfAbsent(batch, coordinator.currentRevealMultiplier());
            }
            advanceOne(support, coordinator);
        }
        assertEquals(3, multiplierAtBatch.get(3), "第 3 批（窗口开启）必须是 3 倍");
        assertEquals(2, multiplierAtBatch.get(4), "第 4 批必须是 2 倍");
        assertEquals(1, multiplierAtBatch.get(5), "第 5 批起必须是下限 1 倍");
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

            assertEquals(cardsBeforeRestart, support.hands.get(seats.getFirst()).size(),
                "旧定时回调不得向新局发牌");
            assertTrue(capture.cancelledTasks > 0, "旧回调应通过自身句柄取消");
            assertEquals(GamePhase.DEALING, support.phase);
            assertFalse(coordinator.isRevealWindowOpen(), "新局在第 3 批之前不得继承旧局的明牌窗口");
            assertEquals(0, coordinator.openingRemainingTicks(), "新局不得继承旧局倒计时");
        } finally {
            serverField.set(null, previous);
        }
    }

    /**
     * 渲染异常不得让发牌时间线停住，也不得让窗口整段消失。
     */
    @Test
    void renderingFailuresNeverStopTheDealingTimeline() {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        FakeSupport support = new FakeSupport(seats);
        support.failRendering = true;
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        coordinator.start(deckInDescendingIds(), settings(4, 20, 60));

        boolean finished = false;
        boolean sawRevealWindow = false;
        int guard = 0;
        while (!finished && guard++ < 4000) {
            finished = advanceOne(support, coordinator);
            sawRevealWindow |= coordinator.isRevealWindowOpen();
        }

        assertTrue(finished, "渲染失败不得让发牌时间线停住");
        assertEquals(GamePhase.BIDDING, support.phase, "明牌窗口结束后必须进入叫分");
        assertEquals(17, support.hands.get(seats.get(0)).size());
        assertEquals(3, support.bottomCards.size());
        assertTrue(sawRevealWindow, "渲染失败不得让明牌窗口整段消失");
        assertTrue(support.reportedFailures >= 1, "渲染失败必须留日志，不能静默吞掉");
        assertTrue(support.reportedFailures <= 3,
            "失败日志必须限频，实际记录 " + support.reportedFailures + " 次");
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 推进一 tick：**先单调推进夹具时钟，再驱动协调器**。
     *
     * @return coordinator.tick() 的返回值（时间线是否结束）
     */
    private static boolean advanceOne(FakeSupport support, RoundOpeningCoordinator coordinator) {
        support.currentTick++;
        return coordinator.tick();
    }

    /** 推进到条件成立；时间线提前结束或超出上限都视为用例前提失败。 */
    private static void advanceUntil(FakeSupport support,
                                     RoundOpeningCoordinator coordinator,
                                     java.util.function.BooleanSupplier condition,
                                     String failureMessage) {
        int guard = 0;
        while (!condition.getAsBoolean()) {
            if (guard++ > 20000) {
                throw new AssertionError(failureMessage + "（已推进 " + guard + " tick）");
            }
            if (advanceOne(support, coordinator) && !condition.getAsBoolean()) {
                throw new AssertionError(failureMessage + "（时间线已结束）");
            }
        }
    }

    private static int totalCards(FakeSupport support) {
        return support.hands.values().stream().mapToInt(List::size).sum();
    }

    private static List<Integer> ids(List<DoudizhuCard> cards) {
        return cards.stream().map(DoudizhuCard::id).toList();
    }

    private static RoundOpeningSettings settings(int batchInterval, int flipTicks, int revealTicks) {
        return new RoundOpeningSettings(
            batchInterval,
            flipTicks,
            revealTicks,
            new RoundOpeningSettings.Messages(
                "发牌", "翻转", "明牌 %seconds% %multiplier%", "明牌", "明牌 ×%multiplier%",
                "结束", "已明", "机器人", "不可明")
        );
    }

    /**
     * 给定批次间隔下每张牌的落桌 tick（发牌顺序是「一座连发 N 张」再换人）。
     *
     * <p>【为什么整体 +1】{@link #advanceOne} 先推进夹具时钟、再驱动 {@code tick()}，
     * 因此第 1 张牌落在 tick 1 而不是 0。这个偏移是夹具的时钟语义，不是发牌逻辑的一部分；
     * 真正要守的是**相邻座位批次起点相差 interval**，下面的断言同时覆盖两者。
     */
    private static List<Integer> expectedDealTicks(int interval) {
        List<Integer> ticks = new ArrayList<>();
        int batchStart = 1;
        for (int batch = 0; batch < 6; batch++) {
            int cardsPerSeat = batch < 5 ? 3 : 2;
            int batchSpan = interval * 3;
            for (int seat = 0; seat < 3; seat++) {
                int seatStart = batchStart + seat * interval;
                for (int card = 0; card < cardsPerSeat; card++) {
                    ticks.add(seatStart + card);
                }
            }
            batchStart += batchSpan;
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
        private boolean failRendering;
        private int reportedFailures;
        /** 明牌窗口公告次数：one-shot 闩锁的核心观测点。 */
        private int revealWindowAnnouncements;

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
        public void onOpeningRevealBecameAvailable(int multiplier) {
            revealWindowAnnouncements++;
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
