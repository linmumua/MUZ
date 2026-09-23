package linmumua.doudizhu.game;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.compat.CraftEngineOffsetService;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import linmumua.doudizhu.ui.VirtualGadgetBarStore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 牌桌对局中的九格道具栏数据源。
 *
 * <p>不打开 Bukkit Inventory GUI。八个虚拟道具槽和第九个语音入口由本服务维护数据与选择状态，
 * 九格栏【本身】已经并入出牌 HUD（BossBar 第四行），由 {@link TrickHudService} 经
 * {@link #hudBarGlyphText(UUID)} 读取只读字形快照后渲染；本服务不再拼 ActionBar、也不碰 ActionBar
 * 槽位（那是普通状态提示的），所以道具栏与状态提示不再互相覆盖闪烁。
 * 玩家仍用滚轮/数字键选择槽位，右键沿既有桌内道具路由执行；选择第九槽时右键打开仅自己可见的实体语音面板。
 */
public final class TableGadgetBarHudService implements Listener {
    public static final int SLOT_COUNT = VirtualGadgetBar.SLOT_COUNT + 1;

    private final DoudizhuPlugin plugin;
    private final VirtualGadgetBarStore store;
    private final TableGadgetService gadgets;
    private final ActionBarOverlayService actionBarOverlay;
    private final CraftEngineOffsetService offsetService;
    private final Consumer<Player> voiceOpener;
    private final Map<UUID, VirtualGadgetBar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> selectedSlots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loading = new ConcurrentHashMap<>();
    private final Map<UUID, RefreshState> refreshStates = new ConcurrentHashMap<>();
    private MuzScheduler.TaskHandle task;
    private volatile boolean stopped;

    public TableGadgetBarHudService(
        DoudizhuPlugin plugin,
        VirtualGadgetBarStore store,
        TableGadgetService gadgets,
        ActionBarOverlayService actionBarOverlay,
        CraftEngineOffsetService offsetService,
        Consumer<Player> voiceOpener
    ) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.gadgets = java.util.Objects.requireNonNull(gadgets, "gadgets");
        this.actionBarOverlay = java.util.Objects.requireNonNull(actionBarOverlay, "actionBarOverlay");
        this.offsetService = java.util.Objects.requireNonNull(offsetService, "offsetService");
        this.voiceOpener = java.util.Objects.requireNonNull(voiceOpener, "voiceOpener");
    }

    /**
     * 开局/入座时预取一次的玩家列表。
     *
     * <p>并入 HUD 后本服务不再有每秒派发 ActionBar 的 tick，也就没有「顺手把没加载过的玩家补上」的时机。
     * 所以这里保留一个【低频】预取定时器：只为「当前在打、且本服务还没加载过其道具数据」的玩家触发异步读取。
     * 真正的显示重绘由 {@link TrickHudService} 的周期刷新自然带上。
     */
    public void start() {
        if (stopped) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player.getUniqueId());
        }
        task = plugin.scheduler().runTimer(1L, 40L, this::prefetchActivePlayers);
    }

    /**
     * 为「在打且数据未加载」的玩家补一次异步读取。
     *
     * <p>【这是数据预取，不是渲染】：每秒只处理尚未加载的玩家，已加载的直接跳过，因此稳态下几乎空转。
     * 不再合成任何 ActionBar，也不再读取普通提示叠加。
     */
    private void prefetchActivePlayers() {
        if (stopped) {
            return;
        }
        for (GameTable table : plugin.getTableManager().getTables()) {
            if (table.getPhase() != GamePhase.PLAYING || !gadgets.settings().enabled()) {
                continue;
            }
            for (UUID playerId : table.getSeats()) {
                if (table.isBot(playerId)) {
                    continue;
                }
                if (!bars.containsKey(playerId)) {
                    refresh(playerId);
                }
            }
        }
    }

    public void refresh(UUID playerId) {
        if (stopped || playerId == null) {
            return;
        }
        RefreshState state = refreshStates.computeIfAbsent(playerId, ignored -> new RefreshState());
        long generation = state.currentGeneration();
        if (loading.putIfAbsent(playerId, generation) != null) {
            return;
        }
        CompletableFuture
            .supplyAsync(() -> store.loadRaw(playerId), ForkJoinPool.commonPool())
            .whenComplete((raw, failure) -> {
                loading.remove(playerId, generation);
                if (failure != null) {
                    plugin.getLogger().warning("读取玩家桌内道具栏失败 " + playerId + ": " + failure.getMessage());
                    return;
                }
                PlayerOutputDispatcher output = actionBarOverlay.outputDispatcher();
                output.runPlayer(playerId, ignored -> {
                    if (!refreshResultAllowed(stopped, state, generation)) {
                        return;
                    }
                    try {
                        bars.put(playerId, store.decodeRaw(raw));
                        normalizeSelection(playerId);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("解码玩家桌内道具栏失败 " + playerId + ": " + exception.getMessage());
                    }
                });
            });
    }

    public int selectedIndex(UUID playerId) {
        return selectedSlots.getOrDefault(playerId, 0);
    }

    public boolean isVoiceSelected(UUID playerId) {
        return selectedIndex(playerId) == VirtualGadgetBar.BUBBLE_SLOT;
    }

    public void select(UUID playerId, int slot) {
        if (playerId == null || slot < 0 || slot >= SLOT_COUNT) {
            return;
        }
        selectedSlots.put(playerId, slot);
        if (slot < VirtualGadgetBar.SLOT_COUNT) {
            ItemStack item = bar(playerId).slot(slot);
            if (item == null) {
                gadgets.clear(playerId);
            } else {
                gadgets.select(playerId, slot, item);
            }
        } else {
            gadgets.clear(playerId);
        }
    }

    /**
     * 九格栏的只读字形快照，供 {@link TrickHudService} 拼出出牌 HUD 的第四行。
     *
     * <p>返回的字符串已经套好 {@code muz_gadget_bar} 字体标签与白色，调用方直接把它接进第四行；
     * 该玩家不该显示这道栏（不在 PLAYING、机器人、道具功能关闭、字体偏移层不可用）时返回 {@code null}，
     * 第四行整条不产出，其余三行照旧。
     *
     * <p>【为什么在 Service 侧取字体偏移而不是回退】：并入 BossBar 后栏不再是客户端居中的主体，
     * 但底图/图标/选框三层仍靠 CraftEngine 的负空格叠回同一槽位；偏移不可用时回退会让三层错位，
     * 不如整行不显示。宽度恒等于 {@link PackAssets#gadgetBarRowAdvance()}，与 TrickHudView 同源。
     */
    public String hudBarGlyphText(UUID playerId) {
        if (stopped || playerId == null || !gadgets.settings().enabled()) {
            return null;
        }
        if (!offsetService.isAvailable()) {
            return null;
        }
        GameTable table = plugin.getTableManager().getTableOf(playerId);
        if (table == null || table.getPhase() != GamePhase.PLAYING || table.isBot(playerId)) {
            return null;
        }
        return renderGlyphs(playerId);
    }

    /** 拼九格栏三层字形；底层是纯字符串，调用方负责套字体颜色上下文。 */
    private String renderGlyphs(UUID playerId) {
        VirtualGadgetBar bar = bar(playerId);
        int selected = selectedIndex(playerId);
        StringBuilder text = new StringBuilder();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            int kind = slot == VirtualGadgetBar.BUBBLE_SLOT
                ? PackAssets.GADGET_BAR_SPEECH
                : PackAssets.gadgetBarKind(bar.slot(slot));
            text.append(PackAssets.gadgetBarBaseGlyphText());
            if (kind != PackAssets.GADGET_BAR_EMPTY) {
                // 图标与底图共用一个 22px 槽位：先回退到底图起点，再让图标自身承担本槽 advance。
                text.append(offsetService.offset(-PackAssets.GADGET_BAR_CELL_ADVANCE));
                text.append(PackAssets.gadgetBarIconGlyphText(kind));
            }
            if (slot == selected) {
                // 选框独立叠加，保持底图/图标的净前进量不变。
                text.append(offsetService.offset(-PackAssets.GADGET_BAR_CELL_ADVANCE));
                text.append(PackAssets.gadgetBarSelectGlyphText());
            }
        }
        return text.toString();
    }

    private VirtualGadgetBar bar(UUID playerId) {
        return bars.getOrDefault(playerId, VirtualGadgetBar.defaults());
    }

    private void normalizeSelection(UUID playerId) {
        int selected = selectedIndex(playerId);
        if (selected < 0 || selected >= SLOT_COUNT) {
            select(playerId, 0);
            return;
        }
        select(playerId, selected);
    }

    /** 右键语音入口由监听器调用，避免把面板实体逻辑塞进本 HUD。 */
    public boolean tryOpenVoice(Player player) {
        if (player == null || !player.isOnline() || !isVoiceSelected(player.getUniqueId())) {
            return false;
        }
        GameTable table = plugin.getTableManager().getTableOf(player);
        if (table == null || table.getPhase() != GamePhase.PLAYING || table.isBot(player.getUniqueId())) {
            return false;
        }
        voiceOpener.accept(player);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        invalidateRefresh(playerId);
        refresh(playerId);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        invalidateRefresh(playerId);
        bars.remove(playerId);
        selectedSlots.remove(playerId);
    }

    @EventHandler
    public void onHeldChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        GameTable table = plugin.getTableManager().getTableOf(player);
        if (table == null || table.getPhase() != GamePhase.PLAYING || table.isBot(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        select(player.getUniqueId(), Math.max(0, Math.min(SLOT_COUNT - 1, event.getNewSlot())));
        // 并入 BossBar 后选槽不会自己触发重绘（本服务已经不派发 ActionBar），
        // 所以这里主动让该玩家的出牌 HUD 立刻重画一次，选框才会跟着移动到新槽位。
        redrawHud(table, player);
    }

    public void onSaved(UUID playerId) {
        if (playerId == null) {
            return;
        }
        invalidateRefresh(playerId);
        bars.remove(playerId);
        selectedSlots.remove(playerId);
        refresh(playerId);
    }

    /**
     * 拆桌、回 LOBBY、停服时收尾。
     *
     * <p>【不再清 ActionBar】：并入 BossBar 后九格栏不在 ActionBar 上，而 ActionBar 现在只承载普通
     * 状态提示；往这里发空 ActionBar 会把别人正在显示的状态提示一并擦掉。栏本身随第四行自然隐藏即可。
     * 这里丢掉该桌座位的道具数据缓存，避免换桌后沿用上一桌的选中槽。
     */
    public void clearTable(GameTable table) {
        if (table == null) {
            return;
        }
        for (UUID playerId : table.getSeats()) {
            bars.remove(playerId);
            selectedSlots.remove(playerId);
        }
        // 主动让仍在线的同桌玩家重画一次 HUD。注意回 LOBBY 路径上本方法在切阶段之前调用，
        // 此时重画的是默认九格栏，要等阶段切到 LOBBY 后的下一次刷新才整条隐藏；关桌路径则立即消失。
        // redrawHud 内部已自行投递到 player lane 与桌 owner lane，这里不再额外包一层 runTableNow。
        for (UUID playerId : table.getSeats()) {
            redrawHud(table, playerId);
        }
    }

    /**
     * 让某玩家的出牌 HUD 立刻重画一次（选槽、拆桌收尾时用）。
     *
     * <p>复用 {@link GameTable} 的既有 HUD 刷新入口，与每秒的周期刷新同一条路径，避免另开一套渲染。
     */
    private void redrawHud(GameTable table, Player player) {
        if (table != null && player != null) {
            redrawHud(table, player.getUniqueId());
        }
    }

    private void redrawHud(GameTable table, UUID playerId) {
        if (table == null || playerId == null) {
            return;
        }
        PlayerOutputDispatcher output = actionBarOverlay.outputDispatcher();
        output.runPlayer(playerId, player -> plugin.getTableManager().runTableNow(table, () -> {
            if (plugin.getTableManager().getTableOf(player) == table) {
                table.refreshTrickHudFor(player);
            }
        }));
    }

    private void invalidateRefresh(UUID playerId) {
        refreshStates.computeIfAbsent(playerId, ignored -> new RefreshState()).invalidate();
        loading.remove(playerId);
    }

    static boolean refreshResultAllowed(boolean serviceStopped, RefreshState state, long generation) {
        return !serviceStopped && state != null && state.matches(generation);
    }

    static final class RefreshState {
        private final AtomicLong generation = new AtomicLong();

        long currentGeneration() {
            return generation.get();
        }

        void invalidate() {
            generation.incrementAndGet();
        }

        boolean matches(long expectedGeneration) {
            return generation.get() == expectedGeneration;
        }
    }

    public void shutdown() {
        stopped = true;
        if (plugin.getTableManager() != null) {
            for (GameTable table : plugin.getTableManager().getTables()) {
                clearTable(table);
            }
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        bars.clear();
        selectedSlots.clear();
        loading.clear();
        refreshStates.clear();
    }
}
