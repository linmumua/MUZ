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
    private static final Map<CardRank, Integer> SAMPLE_COUNTS = buildSampleCounts();

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
        hud.render(player, previous, current, next, SAMPLE_CARDS, SAMPLE_COUNTS, rows, true);
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

    private static Map<CardRank, Integer> buildSampleCounts() {
        EnumMap<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        for (CardRank rank : CardRank.values()) {
            counts.put(rank, rank.isJoker() ? 1 : 4);
        }
        counts.put(CardRank.THREE, 0);
        counts.put(CardRank.FOUR, 1);
        counts.put(CardRank.FIVE, 12);
        return Map.copyOf(counts);
    }
}
