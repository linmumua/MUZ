package linmumua.doudizhu.game;

import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.scheduler.MuzScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 管理单局从逐批发牌到自愿明牌窗口结束的主线程时间线。
 *
 * <p>协调器只改牌局数据并通知牌桌刷新；手牌实体的旋转、排序中点的视觉表现由渲染层读取牌桌快照完成。
 *
 * <p>【明牌窗口现在覆盖整个发牌期】：窗口在**首批（每人三张）发完那一刻**打开，而不是等翻转结束。
 * 倍率按已完成批数走 {@link RevealMultiplierLadder} 的阶梯（3 → 2 → 1，下限 1），
 * 因此「早明牌倍率高、晚明牌倍率低」是真实可点的选择，而不是一句话术。
 * 窗口直到「发牌结束 + 360° 翻转结束 + revealTicks 尾部」才关闭，倒计时贯穿全程。
 */
final class RoundOpeningCoordinator {
    interface Support {
        boolean canScheduleTasks();
        MuzScheduler scheduler();
        default MuzScheduler.TaskHandle runTableTimer(long delayTicks, long periodTicks, java.util.function.Consumer<MuzScheduler.TaskHandle> task) {
            return scheduler().runTimer(delayTicks, periodTicks, task);
        }
        List<UUID> seats();
        void setOpeningPhase(GamePhase phase);
        void appendOpeningCard(UUID playerId, DoudizhuCard card);
        void assignOpeningBottomCards(List<DoudizhuCard> cards);
        void sortOpeningHands();
        void refreshPhysicalTable();
        void tickOpeningActionBar();

        /**
         * 首批发完、明牌窗口刚打开时的一次性通知（此时 {@code phase} 仍是 DEALING）。
         *
         * <p>与 {@link #onOpeningRevealWindowStarted()} 的区别：后者是「翻转结束、进入 REVEALING 阶段」的
         * 阶段切换通知；本方法只表示「现在起可以明牌」。分成两个回调是因为过去的实现里
         * 「可以明牌」和「进入 REVEALING」是同一时刻，现在不是了——把阶段切换提前到首批会把
         * 发牌期的阶段语义（手牌锁、发牌文案、渲染分支）一起改坏。
         *
         * <p>默认空实现：既有夹具无需实现即可编译。
         *
         * @param multiplier 打开当刻的明牌倍率
         */
        default void onOpeningRevealBecameAvailable(int multiplier) {
        }

        void onOpeningRevealWindowStarted();
        void onOpeningRevealWindowFinished();

        /**
         * 开局渲染失败的日志出口；默认写到标准错误，生产由 GameTable 转到插件日志。
         *
         * @param step 失败步骤名
         * @param failure 异常
         * @param count 本局累计失败次数
         */
        default void reportRenderFailure(String step, RuntimeException failure, int count) {
            System.err.println("[MUZ] 开局渲染失败(" + step + ", 累计 " + count + " 次): " + failure);
        }
    }

    private final Support support;
    private MuzScheduler.TaskHandle task;
    private RoundOpeningSettings settings;
    private List<DoudizhuCard> deck = List.of();
    private int epoch;
    private int batchIndex;
    private int seatIndex;
    private int cardsInSeat;
    private int deckIndex;
    private int waitingTicks;
    private int flipElapsed;
    private int revealRemainingTicks;
    private boolean flipping;
    private boolean sortedAtHalfTurn;
    private boolean active;
    /** 明牌窗口是否已打开；打开后直到时间线结束都不再关闭（结束即整条时间线结束）。 */
    private boolean revealWindowOpen;
    /** 51 张玩家手牌是否已全部发完并进入翻转；用于区分「发牌期」与「翻转/尾部」。 */
    private boolean dealingComplete;

    /** 每批的座位数；斗地主固定三个座位。 */
    private static final int SEATS_PER_BATCH = 3;
    /** 发牌批次数：前五批每人三张、第六批每人两张，共 17 张。 */
    private static final int BATCH_COUNT = 6;
    /** 前五批每人三张；最后一批两张补齐 17，底牌三张另存。 */
    private static final int CARDS_PER_SEAT = 3;
    private static final int CARDS_PER_SEAT_IN_LAST_BATCH = 2;
    /** 玩家手牌总张数（51），底牌三张不在发牌时间线内。 */
    private static final int PLAYER_CARD_COUNT = 51;
    /** 底牌张数，发牌结束后一次性写进牌桌。 */
    private static final int BOTTOM_CARD_COUNT = 3;

    RoundOpeningCoordinator(Support support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    void start(List<DoudizhuCard> deck, RoundOpeningSettings settings) {
        Objects.requireNonNull(deck, "deck");
        Objects.requireNonNull(settings, "settings");
        cancel();
        List<UUID> seats = support.seats();
        if (seats.size() != 3) {
            throw new IllegalStateException("开局发牌需要三个座位。");
        }
        if (deck.size() < 54) {
            throw new IllegalArgumentException("斗地主牌堆必须至少有 54 张牌。");
        }
        this.settings = settings;
        this.deck = List.copyOf(deck);
        this.epoch++;
        this.batchIndex = 0;
        this.seatIndex = 0;
        this.cardsInSeat = 0;
        this.deckIndex = 0;
        this.waitingTicks = 0;
        this.flipElapsed = 0;
        this.revealRemainingTicks = 0;
        this.flipping = false;
        this.sortedAtHalfTurn = false;
        this.renderFailuresSinceLog = 0;
        this.revealWindowOpen = false;
        this.dealingComplete = false;
        this.active = true;
        support.setOpeningPhase(GamePhase.DEALING);
        refresh();
        if (support.canScheduleTasks()) {
            int scheduledEpoch = epoch;
            task = support.runTableTimer(0L, 1L, handle -> {
                if (!active || scheduledEpoch != epoch) {
                    handle.cancel();
                    return;
                }
                if (tick()) {
                    handle.cancel();
                    task = null;
                }
            });
        }
    }

    /**
     * 推进一步时间线。生产环境由 Bukkit 主线程逐 tick 调用，测试可直接调用此方法。
     *
     * @return 明牌窗口结束并已进入后续阶段时返回 true
     */
    boolean tick() {
        if (!active || settings == null) {
            return true;
        }
        if (flipping) {
            return tickFlip();
        }
        if (revealRemainingTicks > 0) {
            revealRemainingTicks--;
            if (revealRemainingTicks == 0) {
                active = false;
                support.onOpeningRevealWindowFinished();
                return true;
            }
            safely("tickOpeningActionBar", support::tickOpeningActionBar);
            return false;
        }
        if (waitingTicks > 0) {
            waitingTicks--;
            safely("tickOpeningActionBar", support::tickOpeningActionBar);
            return false;
        }
        dealOneCard();
        return false;
    }

    private void dealOneCard() {
        List<UUID> seats = support.seats();
        int cardsPerSeat = cardsPerSeatForBatch(batchIndex);
        if (deckIndex >= PLAYER_CARD_COUNT) {
            throw new IllegalStateException("发牌时间线已耗尽手牌却未进入翻转阶段。");
        }
        support.appendOpeningCard(seats.get(seatIndex), deck.get(deckIndex++));
        cardsInSeat++;
        if (cardsInSeat >= cardsPerSeat) {
            cardsInSeat = 0;
            seatIndex++;
            if (seatIndex >= seats.size()) {
                seatIndex = 0;
                batchIndex++;
                openRevealWindowAfterFirstBatch();
                if (batchIndex >= BATCH_COUNT) {
                    support.assignOpeningBottomCards(new ArrayList<>(deck.subList(
                        PLAYER_CARD_COUNT, PLAYER_CARD_COUNT + BOTTOM_CARD_COUNT)));
                    beginFlip();
                } else {
                    waitingTicks = settings.batchIntervalTicks() - cardsPerSeat;
                }
            } else {
                // 批内每 tick 一张；补足批次起点之间的固定间隔。
                waitingTicks = settings.batchIntervalTicks() - cardsPerSeat;
            }
        }
        refresh();
    }

    /**
     * 每一批的每座张数：前五批三张，末批两张。
     *
     * <p>抽成方法是为了让 {@link #remainingDealTicks()} 的推演与真正的发牌循环共用同一处规则，
     * 两边各写一份「batchIndex &lt; 5 ? 3 : 2」迟早会走岔。
     */
    private static int cardsPerSeatForBatch(int batchIndex) {
        return batchIndex < BATCH_COUNT - 1 ? CARDS_PER_SEAT : CARDS_PER_SEAT_IN_LAST_BATCH;
    }

    /**
     * 首批（每人三张）完整发完的那一刻打开明牌窗口。
     *
     * <p>此时 {@code phase} 仍是 DEALING：阶段切换仍由翻转结束负责，本方法只让「可以明牌」这一事实成立，
     * 这样发牌期的锁手牌、发牌文案、渲染分支都不会被提前改坏。
     */
    private void openRevealWindowAfterFirstBatch() {
        if (revealWindowOpen || batchIndex != 1) {
            return;
        }
        revealWindowOpen = true;
        int multiplier = RevealMultiplierLadder.multiplierForCompletedBatches(batchIndex);
        // 通知本身也可能抛（发消息/播音效），不能让它把发牌 timer 打死。
        safely("onOpeningRevealBecameAvailable", () -> support.onOpeningRevealBecameAvailable(multiplier));
    }

    private void beginFlip() {
        flipping = true;
        dealingComplete = true;
        flipElapsed = 0;
        sortedAtHalfTurn = false;
    }

    private boolean tickFlip() {
        if (flipElapsed >= settings.flipTicks()) {
            // 360 度最终帧已经完整保留了一个 tick，本 tick 才切入明牌窗口。
            flipping = false;
            support.setOpeningPhase(GamePhase.REVEALING);
            revealRemainingTicks = settings.revealTicks();
            support.onOpeningRevealWindowStarted();
            safely("refreshPhysicalTable", support::refreshPhysicalTable);
            return false;
        }
        flipElapsed++;
        if (!sortedAtHalfTurn && flipElapsed * 2 >= settings.flipTicks()) {
            sortedAtHalfTurn = true;
            support.sortOpeningHands();
        }
        refresh();
        return false;
    }

    /**
     * 渲染与状态推进隔离。
     *
     * <p>调度门面在周期任务回调抛异常时会取消该任务。发牌状态在调用本方法前已经推进，若渲染
     * （实体刷新、ActionBar）抛出异常把异常带出 tick，发牌 timer 会被永久取消——牌桌停在
     * 「正在发牌」且再也不会前进。渲染失败只影响画面，下一 tick 会重画，因此这里吞掉并限频记录，
     * 保证时间线继续推进到明牌/叫分；不能静默吞，必须留日志。
     */
    private void refresh() {
        safely("refreshPhysicalTable", support::refreshPhysicalTable);
        safely("tickOpeningActionBar", support::tickOpeningActionBar);
    }

    /* 渲染失败每累计 200 次最多记一次（持续失败时约等于每 200 tick 一次），避免异常每 tick 刷屏拖垮 TPS。 */
    private static final int FAILURE_LOG_INTERVAL = 200;
    private int renderFailuresSinceLog;

    private void safely(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            if (renderFailuresSinceLog++ % FAILURE_LOG_INTERVAL == 0) {
                support.reportRenderFailure(step, failure, renderFailuresSinceLog);
            }
        }
    }

    void cancel() {
        epoch++;
        active = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        deck = List.of();
        settings = null;
        batchIndex = 0;
        seatIndex = 0;
        cardsInSeat = 0;
        deckIndex = 0;
        waitingTicks = 0;
        flipElapsed = 0;
        revealRemainingTicks = 0;
        flipping = false;
        sortedAtHalfTurn = false;
        revealWindowOpen = false;
        dealingComplete = false;
    }

    boolean isActive() {
        return active;
    }

    /**
     * 明牌窗口是否开着。**语义已从「REVEALING 阶段内」扩宽为「首批发完之后的整个发牌+翻转+尾部」**，
     * 因此与 {@code GamePhase} 无关：发牌期同样为 true。
     */
    boolean isRevealWindowOpen() {
        return active && revealWindowOpen;
    }

    /** 已完整发完的批次数（0..6）；明牌倍率由它决定，而不是由已流逝的秒数决定。 */
    int completedDealBatches() {
        return batchIndex;
    }

    /**
     * 此刻点明牌会冻结到的倍率。
     *
     * @return 窗口未开时返回 0（不可明牌哨兵值），否则为 1..3
     */
    int currentRevealMultiplier() {
        if (!isRevealWindowOpen()) {
            return 0;
        }
        return RevealMultiplierLadder.multiplierForCompletedBatches(batchIndex);
    }

    /**
     * 窗口剩余 tick。
     *
     * <p>翻转结束后由 {@link #revealRemainingTicks} 权威计数（与改造前完全一致：翻转结束那 tick 置为
     * revealTicks，之后每 tick 减一，减到 0 就地关闭窗口）；发牌与翻转期间返回**预测值**，
     * 让倒计时覆盖整个合法窗口而不是等翻转结束才开始。
     */
    int openingRemainingTicks() {
        if (!isRevealWindowOpen()) {
            return 0;
        }
        if (revealRemainingTicks > 0) {
            return revealRemainingTicks;
        }
        return predictedRemainingTicks();
    }

    /**
     * 发牌/翻转期间的剩余 tick 预测：剩余发牌 tick + 剩余翻转 tick + revealTicks。
     *
     * <p>只用于展示。窗口真正的关闭点由翻转后的权威计数决定，所以这里即使有 ±1 tick 误差
     * 也不会让窗口提前关闭或延后关闭，只影响倒计时显示。之所以不做成每 tick 递减的第二个计数器：
     * 两个计数器一旦在异常路径上不同步，就会出现「显示还有时间但已经不能明牌」的错位。
     */
    private int predictedRemainingTicks() {
        if (settings == null) {
            return 0;
        }
        int ticks = dealingComplete ? 0 : remainingDealTicks();
        if (flipping) {
            ticks += Math.max(0, settings.flipTicks() - flipElapsed);
        } else if (!dealingComplete) {
            ticks += settings.flipTicks();
        }
        return ticks + settings.revealTicks();
    }

    /**
     * 推演「还要多少 tick 才把 51 张发完并进入翻转」，纯算术、无副作用。
     *
     * <p>必须与 {@link #dealOneCard()} 的推进顺序保持一致：每座位批 = 每座张数 + 间隔
     * {@code batchIntervalTicks - 每座张数}；末批（每座两张）在发完后直接进翻转、不加间隔。
     * 有上限护栏，配置再怪也不会死循环。
     */
    private int remainingDealTicks() {
        int interval = settings.batchIntervalTicks();
        int wait = waitingTicks;
        int batch = batchIndex;
        int seat = seatIndex;
        int inSeat = cardsInSeat;
        int cardsDealt = deckIndex;
        int ticks = 0;
        int limit = BATCH_COUNT * SEATS_PER_BATCH * Math.max(1, interval) + PLAYER_CARD_COUNT + 8;
        for (int guard = 0; guard < limit; guard++) {
            if (cardsDealt >= PLAYER_CARD_COUNT) {
                return ticks;
            }
            if (wait > 0) {
                wait--;
                ticks++;
                continue;
            }
            int cardsPerSeat = cardsPerSeatForBatch(batch);
            cardsDealt++;
            ticks++;
            inSeat++;
            if (inSeat < cardsPerSeat) {
                continue;
            }
            inSeat = 0;
            seat++;
            if (seat < SEATS_PER_BATCH) {
                wait = Math.max(0, interval - cardsPerSeat);
                continue;
            }
            seat = 0;
            batch++;
            if (batch >= BATCH_COUNT) {
                return ticks;
            }
            wait = Math.max(0, interval - cardsPerSeat);
        }
        return ticks;
    }

    int openingRemainingSeconds() {
        return (openingRemainingTicks() + 19) / 20;
    }

    double openingFlipDegrees() {
        if (settings == null) {
            return 0.0;
        }
        if (flipping) {
            return 360.0 * flipElapsed / settings.flipTicks();
        }
        return revealRemainingTicks > 0 ? 360.0 : 0.0;
    }

    int openingLayoutSize() {
        return 17;
    }

    RoundOpeningSettings settings() {
        return settings;
    }

    String openingRevealLabel() {
        if (settings == null) {
            return "";
        }
        String progress;
        if (flipping) {
            progress = settings.messages().flipping();
        } else if (!dealingComplete) {
            progress = settings.messages().dealing();
        } else {
            progress = "";
        }
        if (!isRevealWindowOpen()) {
            // 窗口未开（首批未发完）或已关闭：只说进度，不谎报可以明牌。
            return progress.isEmpty() ? settings.messages().dealing() : progress;
        }
        String reveal = RevealMultiplierLadder.withMultiplier(
            settings.messages().revealing(),
            RevealMultiplierLadder.multiplierForCompletedBatches(batchIndex)
        ).replace("%seconds%", String.valueOf(openingRemainingSeconds()));
        if (progress.isEmpty()) {
            return reveal;
        }
        // 发牌/翻转期窗口已开：把「还在发牌/翻转」与「现在能明牌、倍率多少、还剩多久」同时说清楚，
        // 而不是用倒计时把进度顶掉——这两件事玩家在窗口期内都需要知道。
        return progress + " · " + reveal;
    }
}
