package linmumua.doudizhu.game;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
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
    private final Map<UUID, Boolean> loading = new ConcurrentHashMap<>();
    private MuzScheduler.TaskHandle task;
    private boolean stopped;

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
        if (stopped || playerId == null || loading.putIfAbsent(playerId, true) != null) {
            return;
        }
        CompletableFuture
            .supplyAsync(() -> store.loadRaw(playerId), ForkJoinPool.commonPool())
            .whenComplete((raw, failure) -> plugin.scheduler().runLater(0L, () -> {
                loading.remove(playerId);
                if (stopped || failure != null) {
                    if (failure != null) {
                        plugin.getLogger().warning("读取玩家桌内道具栏失败 " + playerId + ": " + failure.getMessage());
                    }
                    return;
                }
                try {
                    bars.put(playerId, store.decodeRaw(raw));
                    normalizeSelection(playerId);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("解码玩家桌内道具栏失败 " + playerId + ": " + exception.getMessage());
                }
            }));
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
        for (GameTable table : plugin.getTableManager().getTables()) {
            if (table.getPhase() != GamePhase.PLAYING || !gadgets.settings().enabled()) {
                continue;
            }
            for (UUID playerId : table.getSeats()) {
                if (table.isBot(playerId)) {
                    continue;
                }
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    continue;
                }
                if (!bars.containsKey(playerId)) {
                    refresh(playerId);
                }
                Component bar = render(playerId);
                Component overlay = actionBarOverlay.currentOverlay(playerId);
                player.sendActionBar(overlay == null
                    ? bar
                    : overlay.append(Component.text("  ")).append(bar));
            }
        }
    }

    private static final MiniMessage MINI = MiniMessage.miniMessage();

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
        return MINI.deserialize(text.toString()).decoration(TextDecoration.ITALIC, false);
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
        refresh(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        bars.remove(playerId);
        selectedSlots.remove(playerId);
        loading.remove(playerId);
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
        bars.remove(playerId);
        selectedSlots.remove(playerId);
        refresh(playerId);
    }

    public void clearTable(GameTable table) {
        if (table == null) {
            return;
        }
        for (UUID playerId : table.getSeats()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendActionBar(Component.empty());
            }
        }
    }

    public void shutdown() {
        if (!stopped && plugin.getTableManager() != null) {
            for (GameTable table : plugin.getTableManager().getTables()) {
                clearTable(table);
            }
        }
        stopped = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        bars.clear();
        selectedSlots.clear();
        loading.clear();
    }
}
