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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * 牌桌对局中的屏幕额外道具栏。
 *
 * <p>不打开 Bukkit Inventory GUI。八个虚拟道具槽和第九个语音入口通过 ActionBar
 * 显示，玩家使用滚轮或数字键选择槽位，右键沿既有桌内道具路由执行；选择第九槽时
 * 右键打开仅自己可见的实体语音面板。
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

    public void start() {
        if (stopped) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player.getUniqueId());
        }
        task = plugin.scheduler().runTimer(1L, 2L, this::tick);
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

    /** 仅服务牌桌 PLAYING 真人；其它阶段主动清掉上一帧额外栏。 */
    private void tick() {
        if (stopped) {
            return;
        }
        PlayerOutputDispatcher output = actionBarOverlay.outputDispatcher();
        for (GameTable table : plugin.getTableManager().getTables()) {
            if (table.getPhase() != GamePhase.PLAYING || !gadgets.settings().enabled()) {
                continue;
            }
            for (UUID playerId : table.getSeats()) {
                if (table.isBot(playerId) || output.currentPlayer(playerId) == null) {
                    continue;
                }
                if (!bars.containsKey(playerId)) {
                    refresh(playerId);
                }
                Component bar = render(playerId);
                Component overlay = actionBarOverlay.currentOverlay(playerId);
                output.sendActionBar(playerId, compose(bar, overlay, playerId));
            }
        }
    }

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /* 字体不可测时正文改走聊天，每人每 10 秒最多一次，避免刷屏。 */
    private static final long CHAT_FALLBACK_INTERVAL_MILLIS = 10_000L;
    private final Map<UUID, Long> lastChatFallback = new ConcurrentHashMap<>();
    private final Map<UUID, Component> lastChatFallbackMessage = new ConcurrentHashMap<>();

    /**
     * 组合九格栏与普通提示，保证九格栏在屏幕上的位置固定不动。
     *
     * <p>客户端按 ActionBar 的【总前进量】居中。若直接把提示拼在栏前，提示长度一变，总宽随之变化，
     * 栏就会左右漂移。这里沿用 {@link HotbarActionBarLayout} 的固定宽度算式：栏先画，再用负空格回退，
     * 正文以栏的中线居中叠加，最后补偿到净前进量恒等于栏宽 —— 客户端居中的始终是栏本身。
     *
     * <p>栏与正文是 {@link Component#empty()} 下的兄弟节点，栏不会继承正文颜色被染色。
     * 正文宽度必须用已验证的客户端字体快照精确测量；测不准时不猜，栏保持固定、正文改发聊天（限频）。
     */
    private Component compose(Component bar, Component overlay, UUID playerId) {
        if (overlay == null) {
            return bar;
        }
        int barWidth = SLOT_COUNT * PackAssets.GADGET_BAR_CELL_ADVANCE;
        java.util.OptionalInt measured = measureOverlay(overlay);
        if (!offsetService.isAvailable() || measured.isEmpty()) {
            chatFallback(playerId, overlay);
            return bar;
        }
        HotbarActionBarLayout.Layout layout = HotbarActionBarLayout.calculate(barWidth, measured.getAsInt());
        return Component.empty()
            .append(bar)
            .append(offsetComponent(layout.afterGlyphOffset()))
            .append(overlay)
            .append(offsetComponent(layout.afterTextOffset()));
    }

    private java.util.OptionalInt measureOverlay(Component overlay) {
        HudOverlayRuntimeState state = plugin.getHudOverlayRuntimeState();
        linmumua.doudizhu.assets.HotbarFontMetrics metrics = state == null ? null : state.verifiedHotbarFontMetrics();
        return metrics == null ? java.util.OptionalInt.empty() : metrics.measure(overlay);
    }

    private Component offsetComponent(int pixels) {
        if (pixels == 0) {
            return Component.empty();
        }
        String mini = offsetService.offset(pixels);
        return mini.isEmpty() ? Component.empty() : MINI.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    private void chatFallback(UUID playerId, Component overlay) {
        // 同一条提示只发一次：tick 每 2 tick 执行、提示在对局中持续存在，只按时间限频会让
        // 字体不可测的服务器整局每 10 秒重复刷同一句。内容变化才重发，且仍受 10 秒限频。
        if (overlay.equals(lastChatFallbackMessage.get(playerId))) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastChatFallback.get(playerId);
        if (last != null && now - last < CHAT_FALLBACK_INTERVAL_MILLIS) {
            return;
        }
        lastChatFallback.put(playerId, now);
        lastChatFallbackMessage.put(playerId, overlay);
        actionBarOverlay.outputDispatcher().sendMessage(playerId, overlay);
    }

    private Component render(UUID playerId) {
        VirtualGadgetBar bar = bar(playerId);
        int selected = selectedIndex(playerId);
        boolean canOverlayLayers = offsetService.isAvailable();
        StringBuilder text = new StringBuilder();
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            int kind = slot == VirtualGadgetBar.BUBBLE_SLOT
                ? PackAssets.GADGET_BAR_SPEECH
                : PackAssets.gadgetBarKind(bar.slot(slot));
            text.append(PackAssets.gadgetBarBaseGlyphText());
            if (canOverlayLayers && kind != PackAssets.GADGET_BAR_EMPTY) {
                // 图标与底图共用一个 22px 槽位：先回退到底图起点，再让图标自身承担本槽 advance。
                text.append(offsetService.offset(-PackAssets.GADGET_BAR_CELL_ADVANCE));
                text.append(PackAssets.gadgetBarIconGlyphText(kind));
            }
            if (canOverlayLayers && slot == selected) {
                // 选框独立叠加，保持底图/图标的净前进量不变。
                text.append(offsetService.offset(-PackAssets.GADGET_BAR_CELL_ADVANCE));
                text.append(PackAssets.gadgetBarSelectGlyphText());
            }
        }
        // 位图字形会乘以文字颜色；显式白色保证贴图原色显示，不被上下文颜色染色。
        return MINI.deserialize(text.toString())
            .color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false);
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
        lastChatFallback.remove(playerId);
        lastChatFallbackMessage.remove(playerId);
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

    public void clearTable(GameTable table) {
        if (table == null) {
            return;
        }
        actionBarOverlay.outputDispatcher().sendActionBar(table.getSeats(), Component.empty());
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
        lastChatFallback.clear();
        lastChatFallbackMessage.clear();
    }
}
