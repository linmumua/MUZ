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
        int cardsPerSeat = batchIndex < 5 ? 3 : 2;
        if (deckIndex >= 51) {
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
                if (batchIndex >= 6) {
                    support.assignOpeningBottomCards(new ArrayList<>(deck.subList(51, 54)));
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

    private void beginFlip() {
        flipping = true;
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
    }

    boolean isActive() {
        return active;
    }

    boolean isRevealWindowOpen() {
        return active && revealRemainingTicks > 0;
    }

    int openingRemainingTicks() {
        return isRevealWindowOpen() ? revealRemainingTicks : 0;
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
        if (flipping) {
            return settings.messages().flipping();
        }
        if (isRevealWindowOpen()) {
            return settings.messages().revealing().replace("%seconds%", String.valueOf(openingRemainingSeconds()));
        }
        return settings.messages().dealing();
    }
}
