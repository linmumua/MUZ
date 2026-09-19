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
        List<UUID> seats();
        void setOpeningPhase(GamePhase phase);
        void appendOpeningCard(UUID playerId, DoudizhuCard card);
        void assignOpeningBottomCards(List<DoudizhuCard> cards);
        void sortOpeningHands();
        void refreshPhysicalTable();
        void tickOpeningActionBar();
        void onOpeningRevealWindowStarted();
        void onOpeningRevealWindowFinished();
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
        this.active = true;
        support.setOpeningPhase(GamePhase.DEALING);
        refresh();
        if (support.canScheduleTasks()) {
            int scheduledEpoch = epoch;
            task = support.scheduler().runTimer(0L, 1L, handle -> {
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
            support.tickOpeningActionBar();
            return false;
        }
        if (waitingTicks > 0) {
            waitingTicks--;
            support.tickOpeningActionBar();
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
            support.refreshPhysicalTable();
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

    private void refresh() {
        support.refreshPhysicalTable();
        support.tickOpeningActionBar();
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
