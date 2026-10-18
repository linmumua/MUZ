package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    /**
     * 【机制升级：分批明牌窗口】明牌窗口不再等到翻转结束才开，而是「首批每人 3 张发完的那一刻」开启，
     * 并跨越剩余发牌、360° 翻转与 revealTicks 尾部。
     *
     * <p>旧用例断言的「翻转结束前窗口必须关闭、窗口首值恰为 revealTicks」编码的是旧的单窗口机制，
     * 已被本轮新机制取代；但发牌节奏（每座位批次起点固定间隔）、180° 中点排序、360° 最终帧完整保留、
     * 17+3 完整发牌、窗口截止后才进叫分这些意图一字未减，继续在这里断言。
     */
    @Test
    void dealCadenceFinalFlipFrameAndFirstBatchRevealWindow() {
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

        // 首批（每人 3 张）发完之前窗口必须关闭：此刻没有可冻结的倍率。
        for (int tick = 0; tick <= 9; tick++) {
            support.currentTick = tick;
            coordinator.tick();
            assertFalse(coordinator.isRevealWindowOpen(), "首批 3 张未发完前不得开窗（tick " + tick + "）");
            assertEquals(0, coordinator.openingRemainingTicks(), "窗口未开时不得有剩余倒计时");
        }

        support.currentTick = 10;
        coordinator.tick();
        assertEquals(3, support.hands.get(seats.get(0)).size());
        assertEquals(3, support.hands.get(seats.get(1)).size());
        assertEquals(3, support.hands.get(seats.get(2)).size());
        // 发牌顺序必须在「180° 中点排序」之前断言：排序之后手牌会按牌力重排，顺序不再代表发牌顺序。
        assertEquals(List.of(53, 52, 51), ids(support.hands.get(seats.get(0))), "发牌阶段保持洗牌顺序");
        assertTrue(coordinator.isRevealWindowOpen(), "首批 3 张发完那一刻必须开启明牌窗口");
        assertTrue(coordinator.openingRemainingTicks() > 0, "窗口一开启就必须带真实倒计时");
        // 窗口开着但仍在发牌：文案必须同时说明「还在发牌」和「现在能明牌、还剩多久」，
        // 不能只报发牌进度（玩家看不到可明牌），也不能用倒计时把发牌进度顶掉。
        String labelAtOpen = coordinator.openingRevealLabel();
        assertTrue(labelAtOpen.contains("发牌"), "发牌尚未结束时应保留发牌进度，实际：" + labelAtOpen);
        assertTrue(labelAtOpen.contains("明牌 " + coordinator.openingRemainingSeconds()),
            "必须显示明牌窗口的剩余秒数（真实倒计时），实际：" + labelAtOpen);

        Integer flipEndTick = null;
        Integer closeTick = null;
        for (int tick = 11; tick <= 400; tick++) {
            double degreesBefore = coordinator.openingFlipDegrees();
            support.currentTick = tick;
            boolean finished = coordinator.tick();
            if (flipEndTick == null && support.phase == GamePhase.REVEALING) {
                // 翻转结束由生产回调 setOpeningPhase(REVEALING) 标记，不依赖文案优先级。
                flipEndTick = tick;
                assertEquals(360.0, degreesBefore, "翻转结束前一 tick 就必须已经呈现完整的 360 度最终帧");
            }
            if (finished) {
                closeTick = tick;
                break;
            }
            assertTrue(coordinator.isRevealWindowOpen(), "剩余发牌与翻转期间窗口必须一直开着（tick " + tick + "）");
        }

        assertNotNull(flipEndTick, "时间线必须经过翻转结束");
        assertNotNull(closeTick, "时间线必须在有限 tick 内结束");
        assertEquals(flipEndTick + 60, closeTick, "窗口必须在翻转结束 + revealTicks 处关闭（真实倒计时，不提前也不顺延）");
        assertEquals(GamePhase.BIDDING, support.phase, "窗口截止后才进叫分");
        assertFalse(coordinator.isActive());
        assertEquals(0, coordinator.openingRemainingTicks());
        assertFalse(coordinator.isRevealWindowOpen());

        // 完整发牌与既有渲染意图（顺序、节奏、中点排序、最终帧）继续按原样断言。
        assertEquals(expectedDealTicks(), support.dealTicks, "每个座位批次起点间隔固定为 4 tick");
        assertEquals(17, support.hands.get(seats.get(0)).size());
        assertEquals(17, support.hands.get(seats.get(1)).size());
        assertEquals(17, support.hands.get(seats.get(2)).size());
        // 排序会重排顺序，这里只断言「首批那三张确实留在这手牌里」，顺序断言已在排序前做过。
        Set<Integer> firstSeatIds = new HashSet<>();
        support.hands.get(seats.get(0)).forEach(card -> firstSeatIds.add(card.id()));
        assertTrue(firstSeatIds.containsAll(List.of(53, 52, 51)), "排序不得丢牌或换牌");
        assertEquals(3, support.bottomCards.size());
        assertEquals(54, uniqueCards(support), "54 张牌必须全部唯一");
        assertEquals(1, support.sortCount, "180° 中点只排序一次");
        // 翻转角度的 360° 断言已在「翻转结束前一 tick」做过：窗口关闭后 openingFlipDegrees() 按设计回到 0，
        // 所以在收尾处再断言 360 会是错的（那是拿关闭后的值去证明翻转帧）。
    }

    /**
     * 【真实倒计时】显示出来的剩余 tick 必须等于「距离窗口真正关闭还有多少 tick」，
     * 不能只在翻转后的尾段才倒数（那会让玩家以为窗口只剩几秒，实际还能点几十秒）。
     *
     * <p>旧用例逐 tick 断言「窗口首值 = revealTicks」，那是旧单窗口实现的内部常量；
     * 现在窗口跨越剩余发牌 + 翻转 + revealTicks 尾部，所以改为用**实际关闭 tick**反推，
     * 断言不依赖实现内部的初值写法，反而更强：任何提前结束或固定文案都会变红。
     */
    @Test
    void revealCountdownEqualsTheRealRemainingWindowAndCancelInvalidatesIt() {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        FakeSupport support = new FakeSupport(seats);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        int revealTicks = 5;
        coordinator.start(deckInDescendingIds(), settings(3, 8, revealTicks));

        List<int[]> samples = new ArrayList<>();
        Integer closeTick = null;
        Integer flipEndTick = null;
        Integer remainingAtFlipEnd = null;
        for (int tick = 0; tick <= 400; tick++) {
            support.currentTick = tick;
            boolean finished = coordinator.tick();
            if (flipEndTick == null && support.phase == GamePhase.REVEALING) {
                // 翻转结束由生产回调 setOpeningPhase(REVEALING) 标记，不依赖文案优先级。
                flipEndTick = tick;
                remainingAtFlipEnd = coordinator.openingRemainingTicks();
            }
            if (finished) {
                closeTick = tick;
                break;
            }
            if (coordinator.isRevealWindowOpen()) {
                samples.add(new int[] {tick, coordinator.openingRemainingTicks(), coordinator.openingRemainingSeconds()});
            }
        }

        assertNotNull(closeTick, "时间线必须在有限 tick 内结束");
        assertFalse(samples.isEmpty(), "窗口必须在时间线内真实开启过");
        assertNotNull(flipEndTick, "时间线必须经过翻转结束");
        assertEquals(revealTicks, remainingAtFlipEnd, "翻转结束那 tick 的剩余倒计时必须恰好等于 revealTicks（尾部沿用旧语义）");

        int firstRemaining = samples.getFirst()[1];
        assertTrue(firstRemaining > revealTicks * 3,
            "窗口开启时的倒计时必须覆盖剩余发牌+翻转，不能只显示尾部 " + revealTicks + " tick；实际 " + firstRemaining);

        int lastRemaining = -1;
        for (int[] sample : samples) {
            int tick = sample[0];
            int remaining = sample[1];
            int seconds = sample[2];
            // 发牌/翻转阶段的剩余量是预测值（实现允许 ±1 tick 误差），但必须贴着真实关闭点，不能是固定值。
            assertTrue(Math.abs(remaining - (closeTick - tick)) <= 1,
                "tick " + tick + " 的剩余倒计时必须贴近真实关闭点（应约 " + (closeTick - tick) + "，实际 " + remaining + "）");
            assertEquals((remaining + 19) / 20, seconds, "剩余秒数必须由剩余 tick 向上取整得到");
            assertTrue(lastRemaining < 0 || remaining <= lastRemaining, "倒计时必须单调不增，不能在批次边界重置");
            assertTrue(seconds >= 1, "窗口开着时就不得显示 0 秒");
            lastRemaining = remaining;
        }

        assertEquals(GamePhase.BIDDING, support.phase, "窗口截止后才进叫分");
        assertFalse(coordinator.isActive());
        assertEquals(0, coordinator.openingRemainingTicks());
        assertEquals(0, coordinator.currentRevealMultiplier(), "窗口关闭后没有可冻结倍率");
        int refreshes = support.refreshes;

        coordinator.cancel();
        coordinator.tick();
        assertEquals(refreshes, support.refreshes);
        assertFalse(coordinator.isRevealWindowOpen());
    }

    /**
     * 【3→2→1 阶梯按实际已发批次推进】倍率只看「已完成批数」，不看墙上时间：
     * 1 批 → 3、2 批 → 2、3..6 批 → 1（下限，永不为 0/负），窗口关闭后为 0（不可点）。
     * 同时确认「第二批也是每人 3 张」，与首批同宽。
     */
    @Test
    void revealMultiplierLadderFollowsCompletedDealBatches() {
        List<UUID> seats = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        FakeSupport support = new FakeSupport(seats);
        RoundOpeningCoordinator coordinator = new RoundOpeningCoordinator(support);
        coordinator.start(deckInDescendingIds(), settings(4, 20, 60));

        assertEquals(0, coordinator.completedDealBatches(), "开局尚未发完任何一批");
        assertEquals(0, coordinator.currentRevealMultiplier(), "窗口未开时当前倍率为 0（不是 1）");
        assertFalse(coordinator.isRevealWindowOpen());

        int[] expectedMultiplier = {3, 2, 1, 1, 1, 1};
        for (int batch = 1; batch <= 6; batch++) {
            // lambda 只能捕获 effectively final 变量，循环变量要另存一份。
            int targetBatch = batch;
            tickUntil(coordinator,
                () -> coordinator.completedDealBatches() >= targetBatch, () -> "第 " + targetBatch + " 批未在有限 tick 内完成");
            assertEquals(batch, coordinator.completedDealBatches());
            int expectedCardsPerSeat = batch <= 5 ? 3 * batch : 17;
            for (UUID seat : seats) {
                assertEquals(expectedCardsPerSeat, support.hands.get(seat).size(),
                    "第 " + batch + " 批结束时每座位应已有 " + expectedCardsPerSeat + " 张");
            }
            assertTrue(coordinator.isRevealWindowOpen(), "第 " + batch + " 批发完后窗口必须仍开着");
            assertEquals(expectedMultiplier[batch - 1], coordinator.currentRevealMultiplier(),
                "第 " + batch + " 批完成后的可冻结倍率必须按 3→2→1 阶梯推进");
        }
        assertEquals(17, support.hands.get(seats.get(0)).size());
        assertEquals(3, support.bottomCards.size());
        assertEquals(54, uniqueCards(support), "阶梯推进不能漏发或多发牌");

        tickUntil(coordinator, () -> !coordinator.isRevealWindowOpen(), () -> "窗口未在有限 tick 内关闭");
        assertEquals(0, coordinator.currentRevealMultiplier(), "窗口关闭后不存在可冻结倍率");
        assertEquals(0, coordinator.openingRemainingTicks());
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
            assertFalse(coordinator.isRevealWindowOpen(), "新局在首批发完之前不得继承旧局的明牌窗口");
            assertEquals(0, coordinator.openingRemainingTicks(), "新局不得继承旧局倒计时");
        } finally {
            serverField.set(null, previous);
        }
    }

    /**
     * 【机制升级：3→2→1 阶梯】明牌公共倍率不再是固定 ×2，而是按已发批次取 3/2/1（下限 1）。
     * 硬上限随之从 2 提到 3，但「多人明牌不叠乘」与「炸弹/春天独立」两条意图不变：
     * 上限之外的任何值都只能被钳到 3，不能累加，也不能被 0 抹平。
     */
    @Test
    void coreScoreClampsPublicRevealToTheLadderMaximumWithoutStacking() {
        assertEquals(72, GameTable.coreScoreFor(3, 3, 4, 2), "首批 3 倍必须真的进分");
        assertEquals(72, GameTable.coreScoreFor(3, 9, 4, 2), "多人明牌不能叠乘，也不能突破阶梯上限 3");
        assertEquals(24, GameTable.coreScoreFor(3, 2, 4, 1), "阶梯 2 倍仍是合法档位");
        assertEquals(12, GameTable.coreScoreFor(3, 1, 4, 1), "第三批及以后下限为 1 倍");
        assertEquals(12, GameTable.coreScoreFor(3, 0, 4, 1), "窗口外的 0 不得把明牌因子抹成 0 或负数");
    }

    private static List<Integer> ids(List<DoudizhuCard> cards) {
        return cards.stream().map(DoudizhuCard::id).toList();
    }

    /**
     * 推进真实协调器直到条件成立；时间线提前结束或超出上限都视为用例前提失败。
     *
     * <p>注意「关闭窗口的那一 tick」既返回 true 又让条件成立，不能把它当成提前结束。
     */
    private static void tickUntil(RoundOpeningCoordinator coordinator,
                                  java.util.function.BooleanSupplier condition,
                                  java.util.function.Supplier<String> failureMessage) {
        int guard = 0;
        while (!condition.getAsBoolean()) {
            if (guard++ > 400) {
                throw new AssertionError(failureMessage.get() + "（已推进 " + guard + " tick）");
            }
            if (coordinator.tick() && !condition.getAsBoolean()) {
                throw new AssertionError(failureMessage.get() + "（时间线已结束）");
            }
        }
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
     *
     * <p>分批明牌窗口同步纳入这条隔离要求：渲染异常不得让首批窗口「从未开启」或提前关闭。
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
        boolean sawRevealWindow = false;
        for (int tick = 0; tick < 400 && !finished; tick++) {
            support.currentTick = tick;
            finished = coordinator.tick();
            sawRevealWindow |= coordinator.isRevealWindowOpen();
        }

        assertTrue(finished, "渲染失败不得让发牌时间线停住");
        assertEquals(GamePhase.BIDDING, support.phase, "明牌窗口结束后必须进入叫分");
        assertEquals(17, support.hands.get(seats.get(0)).size());
        assertEquals(3, support.bottomCards.size());
        assertTrue(sawRevealWindow, "渲染失败不得让首批明牌窗口整段消失");
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
