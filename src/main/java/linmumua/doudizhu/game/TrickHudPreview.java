package linmumua.doudizhu.game;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import org.bukkit.entity.Player;

/**
 * 未进牌桌时给 HUD 调试棒使用的假数据预览。
 *
 * <p>预览只复用正式 {@link TrickHudService#render}，不复制排版或头像渲染逻辑；
 * 调试棒的行覆盖仍由插件按玩家 UUID 保存，正式牌桌 HUD 不会共享预览状态。
 */
public final class TrickHudPreview {
    private static final UUID BOT_LEFT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOT_RIGHT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final List<DoudizhuCard> SAMPLE_CARDS = List.of(
        new DoudizhuCard(1, CardRank.TEN, CardSuit.SPADES),
        new DoudizhuCard(2, CardRank.JACK, CardSuit.HEARTS),
        new DoudizhuCard(3, CardRank.QUEEN, CardSuit.DIAMONDS),
        new DoudizhuCard(4, CardRank.SMALL_JOKER, CardSuit.JOKER),
        new DoudizhuCard(5, CardRank.BIG_JOKER, CardSuit.JOKER)
    );
    private static final CounterSnapshot SAMPLE_COUNTERS = buildSampleCounters();

    private final DoudizhuPlugin plugin;
    private final TrickHudService hud;

    public TrickHudPreview(DoudizhuPlugin plugin) {
        this.plugin = plugin;
        this.hud = new TrickHudService(
            plugin,
            plugin.getCraftEngineOffsetService(),
            plugin.getPlayerHeadRenderer());
    }

    /** 使用左/右假 bot、中间当前玩家地主的固定样例显示预览。 */
    public void show(Player player) {
        if (player == null) {
            return;
        }
        TrickHudService.Seat previous = new TrickHudService.Seat(
            BOT_LEFT, true, PlayerRole.FARMER);
        TrickHudService.Seat current = new TrickHudService.Seat(
            player.getUniqueId(), false, PlayerRole.LANDLORD);
        TrickHudService.Seat next = new TrickHudService.Seat(
            BOT_RIGHT, true, PlayerRole.FARMER);
        int rows = plugin.hudRowOverride(player.getUniqueId());
        hud.render(player, previous, current, next, SAMPLE_CARDS,
            SAMPLE_COUNTERS.playedCounts(), SAMPLE_COUNTERS.remainingCounts(), rows, true);
    }

    public void hide(Player player) {
        if (player != null) {
            hud.hide(player);
        }
    }

    public void reloadSettings() {
        hud.reloadSettings();
    }

    public void hideAll() {
        hud.hideAll();
    }

    /**
     * 调试棒与 Debug Web 共用的五张固定牌面 fixture。
     *
     * <p>返回同一份不可变列表，顺序固定为 10、J、Q、小王、大王；Web 预览不能另抄一套
     * 牌序，否则调试棒和页面会出现「都是样例但不是同一局」的假象。
     */
    public static List<DoudizhuCard> sampleCards() {
        return SAMPLE_CARDS;
    }

    /**
     * 调试棒与 Debug Web 共用的固定记牌器 fixture。
     *
     * <p>只维护 remaining 一份状态，played 由初始牌数减剩余张数推导：3 覆盖 0 已出，
     * 4 覆盖 1 已出，5 覆盖普通牌 4 已出，小王/大王分别覆盖 0/1 已出。
     */
    public static CounterSnapshot counterSnapshot() {
        return SAMPLE_COUNTERS;
    }

    private static CounterSnapshot buildSampleCounters() {
        EnumMap<CardRank, Integer> remaining = new EnumMap<>(CardRank.class);
        for (CardRank rank : CardRank.values()) {
            remaining.put(rank, rank.isJoker() ? 1 : 4);
        }
        remaining.put(CardRank.FOUR, 3);
        remaining.put(CardRank.FIVE, 0);
        remaining.put(CardRank.BIG_JOKER, 0);
        Map<CardRank, Integer> immutableRemaining = Map.copyOf(remaining);
        return new CounterSnapshot(immutableRemaining, TrickHudService.playedCountsFromRemaining(immutableRemaining));
    }

    public record CounterSnapshot(Map<CardRank, Integer> remainingCounts, Map<CardRank, Integer> playedCounts) {
        public CounterSnapshot {
            remainingCounts = Map.copyOf(remainingCounts);
            playedCounts = Map.copyOf(playedCounts);
        }

        public int remaining(CardRank rank) {
            return remainingCounts.getOrDefault(rank, rank.isJoker() ? 1 : 4);
        }

        public int played(CardRank rank) {
            return playedCounts.getOrDefault(rank, 0);
        }

        public boolean exhausted(CardRank rank) {
            return remaining(rank) == 0;
        }
    }
}
