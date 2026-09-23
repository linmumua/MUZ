package linmumua.doudizhu.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import linmumua.doudizhu.game.PlayerOutputDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 独立的九格道具箱 GUI。
 *
 * <p>上方 0..7 是玩家虚拟道具快照，8 是固定气泡入口；真实玩家背包只作为
 * Shift 点击复制来源，不会被 GUI 接管或改写。该类自身实现 Listener，主装配只需注册实例。
 */
public class GadgetBoxGuiService implements Listener {
    public enum ActionType {
        SELECTED,
        COPIED,
        REMOVED,
        BUBBLE,
        NOOP,
        IGNORED
    }

    public record Action(
        ActionType type,
        UUID viewerId,
        int slot,
        ItemStack item,
        VirtualGadgetBar snapshot
    ) {
        public Action {
            item = item == null ? null : item.clone();
        }

        @Override
        public ItemStack item() {
            return item == null ? null : item.clone();
        }
    }

    private static final int GUI_SIZE = 9;

    private final VirtualGadgetBarStore store;
    private final Consumer<Action> actionConsumer;
    private final Consumer<UUID> saveListener;
    private final BiConsumer<UUID, Inventory> openInventory;
    private final Consumer<UUID> closeInventory;
    private volatile String title;
    private volatile String bubbleName;
    private final Map<UUID, Integer> selectedSlots = new ConcurrentHashMap<>();

    public GadgetBoxGuiService(VirtualGadgetBarStore store) {
        this(store, action -> { }, playerId -> { }, "MUZ | 道具箱", "语音气泡");
    }

    public GadgetBoxGuiService(VirtualGadgetBarStore store, Consumer<Action> actionConsumer) {
        this(store, actionConsumer, playerId -> { }, "MUZ | 道具箱", "语音气泡");
    }

    public GadgetBoxGuiService(
        VirtualGadgetBarStore store,
        Consumer<Action> actionConsumer,
        String title,
        String bubbleName
    ) {
        this(store, actionConsumer, playerId -> { }, title, bubbleName);
    }

    public GadgetBoxGuiService(
        VirtualGadgetBarStore store,
        Consumer<Action> actionConsumer,
        Consumer<UUID> saveListener,
        String title,
        String bubbleName
    ) {
        this(store, actionConsumer, saveListener, title, bubbleName,
            GadgetBoxGuiService::openInventoryDirect,
            GadgetBoxGuiService::closeInventoryDirect);
    }

    /** 生产装配入口：GUI 仍在主线程创建，最终开关窗口交给 player owner lane。 */
    public GadgetBoxGuiService(
        VirtualGadgetBarStore store,
        Consumer<Action> actionConsumer,
        Consumer<UUID> saveListener,
        String title,
        String bubbleName,
        PlayerOutputDispatcher output
    ) {
        this(store, actionConsumer, saveListener, title, bubbleName,
            (playerId, inventory) -> output.openInventory(playerId, inventory),
            output::closeInventory);
    }

    private GadgetBoxGuiService(
        VirtualGadgetBarStore store,
        Consumer<Action> actionConsumer,
        Consumer<UUID> saveListener,
        String title,
        String bubbleName,
        BiConsumer<UUID, Inventory> openInventory,
        Consumer<UUID> closeInventory
    ) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.actionConsumer = java.util.Objects.requireNonNull(actionConsumer, "actionConsumer");
        this.saveListener = java.util.Objects.requireNonNull(saveListener, "saveListener");
        this.openInventory = java.util.Objects.requireNonNull(openInventory, "openInventory");
        this.closeInventory = java.util.Objects.requireNonNull(closeInventory, "closeInventory");
        this.title = title == null || title.isBlank() ? "MUZ | 道具箱" : title;
        this.bubbleName = bubbleName == null || bubbleName.isBlank() ? "语音气泡" : bubbleName;
    }

    private static void openInventoryDirect(UUID playerId, Inventory inventory) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.openInventory(inventory);
        }
    }

    private static void closeInventoryDirect(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.closeInventory();
        }
    }

    /** 打开或重新创建道具箱 GUI；配置编辑 GUI 只允许在 Bukkit 主线程操作。 */
    public void open(Player player) {
        requirePrimaryThread("open");
        java.util.Objects.requireNonNull(player, "player");
        VirtualGadgetBar snapshot = store.load(player.getUniqueId());
        int selected = normalizeSelection(player.getUniqueId(), snapshot);
        GadgetBoxInventoryHolder holder = new GadgetBoxInventoryHolder(player.getUniqueId(), snapshot);
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, title);
        holder.setInventory(inventory);
        render(inventory, snapshot, selected);
        openInventory.accept(player.getUniqueId(), inventory);
    }

    /** 只刷新当前已打开的道具箱；未打开时不创建新窗口。 */
    public void refresh(Player player) {
        requirePrimaryThread("refresh");
        if (player == null) {
            return;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        if (!(top.getHolder() instanceof GadgetBoxInventoryHolder holder)
            || !holder.viewerId().equals(player.getUniqueId())) {
            return;
        }
        VirtualGadgetBar snapshot = store.load(player.getUniqueId());
        holder.setSnapshot(snapshot);
        int selected = normalizeSelection(player.getUniqueId(), snapshot);
        render(top, snapshot, selected);
    }

    /** 主装配可直接转发 InventoryClickEvent。所有道具箱点击都会被取消。 */
    @EventHandler
    public void handleClick(InventoryClickEvent event) {
        requirePrimaryThread("handleClick");
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GadgetBoxInventoryHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || !holder.viewerId().equals(player.getUniqueId())) {
            return;
        }
        if (event.getClickedInventory() == null) {
            emit(new Action(ActionType.IGNORED, player.getUniqueId(), event.getRawSlot(), null, holder.snapshot()));
            return;
        }
        if (event.getClickedInventory().equals(top)) {
            handleTopClick(player, holder, event);
            return;
        }
        if (event.getClickedInventory().equals(player.getInventory()) && event.isShiftClick()) {
            handleCopy(player, holder, event.getCurrentItem());
            return;
        }
        emit(new Action(ActionType.IGNORED, player.getUniqueId(), event.getRawSlot(), null, holder.snapshot()));
    }

    /** 道具箱打开期间禁止拖拽，避免 Bukkit 绕过单击路径修改上方快照。 */
    @EventHandler
    public void handleDrag(InventoryDragEvent event) {
        requirePrimaryThread("handleDrag");
        if (event.getView().getTopInventory().getHolder() instanceof GadgetBoxInventoryHolder) {
            event.setCancelled(true);
        }
    }

    public int selectedSlot(UUID playerId) {
        return selectedSlots.getOrDefault(playerId, -1);
    }

    public ItemStack selectedItem(UUID playerId) {
        requirePrimaryThread("selectedItem");
        int selected = selectedSlot(playerId);
        if (selected < 0 || selected >= VirtualGadgetBar.SLOT_COUNT) {
            return null;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.getOpenInventory().getTopInventory().getHolder() instanceof GadgetBoxInventoryHolder holder) {
            return holder.snapshot().slot(selected);
        }
        return store.load(playerId).slot(selected);
    }

    /** 供主装配或其它 UI 直接设置虚拟选择，不修改持久化快照。 */
    public void select(UUID playerId, int slot) {
        if (playerId == null || slot < 0 || slot >= VirtualGadgetBar.SLOT_COUNT) {
            throw new IllegalArgumentException("只能选择虚拟道具栏 0..7 槽位");
        }
        selectedSlots.put(playerId, slot);
    }

    public void close(Player player) {
        requirePrimaryThread("close");
        if (player != null && player.getOpenInventory().getTopInventory().getHolder() instanceof GadgetBoxInventoryHolder) {
            closeInventory.accept(player.getUniqueId());
        }
    }

    public void reloadAppearance(String title, String bubbleName) {
        this.title = title == null || title.isBlank() ? "MUZ | 道具箱" : title;
        this.bubbleName = bubbleName == null || bubbleName.isBlank() ? "语音气泡" : bubbleName;
    }

    public void shutdown() {
        selectedSlots.clear();
    }

    private void handleTopClick(Player player, GadgetBoxInventoryHolder holder, InventoryClickEvent event) {
        requirePrimaryThread("handleTopClick");
        int slot = event.getRawSlot();
        if (slot == VirtualGadgetBar.BUBBLE_SLOT) {
            emit(new Action(event.isRightClick() ? ActionType.BUBBLE : ActionType.NOOP,
                player.getUniqueId(), slot, GadgetBoxItems.bubble(bubbleName), holder.snapshot()));
            return;
        }
        if (slot < 0 || slot >= VirtualGadgetBar.SLOT_COUNT) {
            emit(new Action(ActionType.IGNORED, player.getUniqueId(), slot, null, holder.snapshot()));
            return;
        }
        if (event.isShiftClick() && event.isRightClick()) {
            VirtualGadgetBar next = holder.snapshot().remove(slot);
            int selected = selectedSlots.getOrDefault(player.getUniqueId(), -1);
            int nextSelected = selected == slot ? -1 : selected > slot ? selected - 1 : selected;
            saveAndRender(player, holder, next, nextSelected);
            emit(new Action(ActionType.REMOVED, player.getUniqueId(), slot, null, next));
            return;
        }
        if (event.isShiftClick() || (!event.isLeftClick() && !event.isRightClick())) {
            emit(new Action(ActionType.NOOP, player.getUniqueId(), slot, null, holder.snapshot()));
            return;
        }
        ItemStack selected = holder.snapshot().slot(slot);
        if (selected == null) {
            emit(new Action(ActionType.NOOP, player.getUniqueId(), slot, null, holder.snapshot()));
            return;
        }
        selectedSlots.put(player.getUniqueId(), slot);
        emit(new Action(ActionType.SELECTED, player.getUniqueId(), slot, selected, holder.snapshot()));
        closeInventory.accept(player.getUniqueId());
    }

    private void handleCopy(Player player, GadgetBoxInventoryHolder holder, ItemStack source) {
        if (source == null || source.getType().isAir()) {
            emit(new Action(ActionType.NOOP, player.getUniqueId(), -1, null, holder.snapshot()));
            return;
        }
        int slot = holder.snapshot().firstEmpty();
        if (slot < 0) {
            emit(new Action(ActionType.NOOP, player.getUniqueId(), -1, source, holder.snapshot()));
            return;
        }
        ItemStack copy = source.clone();
        copy.setAmount(1);
        VirtualGadgetBar next = holder.snapshot().withSlot(slot, copy);
        saveAndRender(player, holder, next, selectedSlots.getOrDefault(player.getUniqueId(), slot));
        emit(new Action(ActionType.COPIED, player.getUniqueId(), slot, copy, next));
    }

    private void saveAndRender(Player player, GadgetBoxInventoryHolder holder, VirtualGadgetBar next, int selected) {
        store.save(player.getUniqueId(), next);
        notifySaved(player.getUniqueId());
        holder.setSnapshot(next);
        int normalized = normalizeSelection(selected, next);
        if (normalized < 0) {
            selectedSlots.remove(player.getUniqueId());
        } else {
            selectedSlots.put(player.getUniqueId(), normalized);
        }
        render(holder.getInventory(), next, normalized);
    }

    // 预览通知属于附属观察者：持久化成功后，它的失败不得阻断游戏内快照和重绘。
    void notifySaved(UUID playerId) {
        try {
            saveListener.accept(playerId);
        } catch (RuntimeException exception) {
            java.util.logging.Logger.getLogger(GadgetBoxGuiService.class.getName()).log(
                java.util.logging.Level.WARNING, "道具箱已保存，但预览通知失败：" + playerId, exception);
        }
    }

    private void render(Inventory inventory, VirtualGadgetBar snapshot, int selected) {
        inventory.clear();
        for (int slot = 0; slot < VirtualGadgetBar.SLOT_COUNT; slot++) {
            ItemStack item = snapshot.slot(slot);
            if (item != null && slot == selected) {
                item = item.clone();
                org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setEnchantmentGlintOverride(true);
                    item.setItemMeta(meta);
                }
            }
            inventory.setItem(slot, item);
        }
        inventory.setItem(VirtualGadgetBar.BUBBLE_SLOT, GadgetBoxItems.bubble(bubbleName));
    }

    private int normalizeSelection(UUID playerId, VirtualGadgetBar snapshot) {
        int selected = normalizeSelection(selectedSlots.getOrDefault(playerId, -1), snapshot);
        if (selected < 0) {
            selectedSlots.remove(playerId);
        } else {
            selectedSlots.put(playerId, selected);
        }
        return selected;
    }

    private static int normalizeSelection(int slot, VirtualGadgetBar snapshot) {
        return slot >= 0 && slot < VirtualGadgetBar.SLOT_COUNT && snapshot.slot(slot) != null ? slot : -1;
    }

    private void emit(Action action) {
        actionConsumer.accept(action);
    }

    /** 配置编辑 GUI 的 Bukkit Inventory 读写必须由同步主线程完成。 */
    private static void requirePrimaryThread(String operation) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("道具箱 GUI 操作必须在 Bukkit 主线程执行：" + operation);
        }
    }
}
