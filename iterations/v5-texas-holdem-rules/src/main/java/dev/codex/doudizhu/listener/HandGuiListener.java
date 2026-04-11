package dev.codex.doudizhu.listener;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.game.GameTable;
import dev.codex.doudizhu.ui.HandInventoryHolder;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HandGuiListener implements Listener {
    private static final long CLICK_COOLDOWN_MILLIS = 200L;
    private final DoudizhuPlugin plugin;
    private final Map<UUID, Long> lastClickAt = new ConcurrentHashMap<>();

    public HandGuiListener(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof HandInventoryHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!holder.viewerId().equals(player.getUniqueId())) {
            player.closeInventory();
            return;
        }
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(inventory)) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = lastClickAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < CLICK_COOLDOWN_MILLIS) {
            return;
        }
        lastClickAt.put(player.getUniqueId(), now);

        int rawSlot = event.getRawSlot();
        try {
            switch (holder.viewMode()) {
                case SETTINGS -> handleSettingsClick(player, rawSlot);
                case SETTINGS_SELECTION_SOUND_PICKER, SETTINGS_PLAY_ACTION_PICKER -> handlePlayerPickerClick(player, holder.editorTarget(), rawSlot);
                case ADMIN_COUNTDOWN_SOUND_EDITOR -> handleCountdownEditorClick(player, rawSlot);
                case ADMIN_MODELS, ADMIN_SELECTION_SOUND_EDITOR, ADMIN_PLAY_ACTION_EDITOR ->
                    handleAdminClick(player, holder.adminPage(), rawSlot, event.isLeftClick(), event.isShiftClick());
            }
        } catch (RuntimeException exception) {
            player.sendMessage(message(exception.getMessage()));
            plugin.getHandGuiService().refreshSettingsIfOpen(player);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof HandInventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onAsyncChat(AsyncChatEvent event) {
        if (!plugin.getHandGuiService().hasPendingSoundInput(event.getPlayer().getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> plugin.getHandGuiService().handlePendingSoundInput(event.getPlayer(), plain));
    }

    private void handleSettingsClick(Player player, int rawSlot) {
        GameTable table = plugin.getTableManager().getTableOf(player);
        switch (rawSlot) {
            case 10 -> plugin.toggleCardLabelsFor(player.getUniqueId());
            case 12 -> plugin.toggleOpponentPreviewFor(player.getUniqueId());
            case 14 -> plugin.toggleSelectionSoundFor(player.getUniqueId());
            case 15 -> {
                plugin.getHandGuiService().openSelectionSoundPicker(player);
                return;
            }
            case 16 -> {
                plugin.getHandGuiService().openPlayActionPicker(player);
                return;
            }
            case 23 -> plugin.resetPlayerVisualSettings(player.getUniqueId());
            case 24 -> {
                player.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }
        if (table != null) {
            plugin.getPhysicalTableManager().refresh(table);
        }
        plugin.getHandGuiService().openSettings(player);
    }

    private void handlePlayerPickerClick(Player player, HandInventoryHolder.EditorTarget target, int rawSlot) {
        if (rawSlot == 22) {
            plugin.getHandGuiService().openSettings(player);
            return;
        }
        if (rawSlot == 24) {
            player.closeInventory();
            return;
        }
        int index = rawSlot - 10;
        if (index < 0 || index > 3) {
            return;
        }
        if (target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION) {
            plugin.setPlayerSelectionSoundProfileIndex(player.getUniqueId(), index);
            plugin.getHandGuiService().openSelectionSoundPicker(player);
        } else if (target == HandInventoryHolder.EditorTarget.PLAYER_PLAY_ACTION) {
            plugin.setPlayerPlayActionProfileIndex(player.getUniqueId(), index);
            plugin.getHandGuiService().openPlayActionPicker(player);
        }
        GameTable table = plugin.getTableManager().getTableOf(player);
        if (table != null) {
            plugin.getPhysicalTableManager().refresh(table);
        }
    }

    private void handleCountdownEditorClick(Player player, int rawSlot) {
        if (rawSlot == 32) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.AUDIO);
            return;
        }
        if (rawSlot == 33) {
            player.closeInventory();
            return;
        }
        if (rawSlot == 31) {
            plugin.getHandGuiService().beginCustomInput(player, HandInventoryHolder.EditorTarget.ADMIN_COUNTDOWN, -1);
            return;
        }
        String preset = plugin.getHandGuiService().soundPresetSpec(HandInventoryHolder.EditorTarget.ADMIN_COUNTDOWN, rawSlot);
        if (preset == null) {
            return;
        }
        plugin.setCountdownSoundSpec(preset);
        DoudizhuPlugin.ConfiguredSound sound = plugin.countdownSound();
        if (sound.volume() > 0.0f) {
            player.playSound(player.getLocation(), sound.key(), sound.volume(), sound.pitch());
        }
        plugin.getHandGuiService().openCountdownSoundPicker(player);
    }

    private void handleAdminClick(Player player, HandInventoryHolder.AdminPage page, int rawSlot, boolean leftClick, boolean shiftClick) {
        if (!player.hasPermission("muz.admin")) {
            throw new IllegalStateException("你没有权限使用管理员菜单。");
        }
        if (rawSlot == 45) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.MODELS);
            return;
        }
        if (rawSlot == 46) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.TABLE);
            return;
        }
        if (rawSlot == 47) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.CARDS);
            return;
        }
        if (rawSlot == 48) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.HITBOX);
            return;
        }
        if (rawSlot == 49) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.AUDIO);
            return;
        }
        if (rawSlot == 50) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.PLAYER_OPTIONS);
            return;
        }
        if (rawSlot == 51) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.BOTS);
            return;
        }
        if (rawSlot == 52) {
            plugin.reloadPluginState();
            plugin.getHandGuiService().openAdminModels(player, page == null ? HandInventoryHolder.AdminPage.MODELS : page);
            return;
        }
        if (rawSlot == 53) {
            player.closeInventory();
            return;
        }

        int multiplier = shiftClick ? 10 : 1;
        HandInventoryHolder.AdminPage current = page == null ? HandInventoryHolder.AdminPage.MODELS : page;
        switch (current) {
            case MODELS -> handleAdminModelsPage(player, rawSlot);
            case TABLE -> handleAdminTablePage(player, rawSlot, leftClick, multiplier, current);
            case CARDS -> handleAdminCardsPage(player, rawSlot, leftClick, multiplier, current);
            case HITBOX -> handleAdminHitboxPage(player, rawSlot, leftClick, multiplier, current);
            case AUDIO -> handleAdminAudioPage(player, rawSlot, leftClick, multiplier, current);
            case PLAYER_OPTIONS -> handleAdminPlayerOptionsPage(player, rawSlot, leftClick);
            case BOTS -> handleAdminBotsPage(player, rawSlot, leftClick, multiplier, current);
            case SEAT -> handleAdminTablePage(player, rawSlot, leftClick, multiplier, current);
        }
    }

    private void handleAdminModelsPage(Player player, int rawSlot) {
        switch (rawSlot) {
            case 10 -> applyHeldItemToFurniture(player, DoudizhuPlugin.FurnitureType.TABLE);
            case 12 -> applyHeldItemToFurniture(player, DoudizhuPlugin.FurnitureType.CHAIR);
            case 14 -> plugin.resetFurnitureDisplayItem(DoudizhuPlugin.FurnitureType.TABLE);
            case 16 -> plugin.resetFurnitureDisplayItem(DoudizhuPlugin.FurnitureType.CHAIR);
            default -> {
                return;
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.MODELS);
    }

    private void handleAdminTablePage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.TABLE_SPAWN_OFFSET_Y, increase, multiplier, page);
            case 11 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_DISTANCE, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_HEIGHT, increase, multiplier, page);
            case 13 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_SCALE, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_ROLL_DEGREES, increase, multiplier, page);
            case 19 -> adjust(player, DoudizhuPlugin.AdminSetting.CHAIR_VISUAL_LATERAL, increase, multiplier, page);
            case 20 -> adjust(player, DoudizhuPlugin.AdminSetting.CHAIR_VISUAL_VERTICAL, increase, multiplier, page);
            case 21 -> adjust(player, DoudizhuPlugin.AdminSetting.STATUS_HEIGHT, increase, multiplier, page);
            case 22 -> adjust(player, DoudizhuPlugin.AdminSetting.PLAY_DETAIL_HEIGHT, increase, multiplier, page);
            default -> {
            }
        }
    }

    private void handleAdminCardsPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.PRIVATE_CARD_SCALE, increase, multiplier, page);
            case 11 -> adjust(player, DoudizhuPlugin.AdminSetting.PUBLIC_TRICK_CARD_SCALE, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.PRIVATE_CARD_WIDTH_SCALE, increase, multiplier, page);
            case 13 -> adjust(player, DoudizhuPlugin.AdminSetting.PRIVATE_CARD_HEIGHT_SCALE, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.PRIVATE_CARD_DEPTH_SCALE, increase, multiplier, page);
            case 15 -> adjust(player, DoudizhuPlugin.AdminSetting.PUBLIC_CARD_WIDTH_SCALE, increase, multiplier, page);
            case 16 -> adjust(player, DoudizhuPlugin.AdminSetting.PUBLIC_CARD_HEIGHT_SCALE, increase, multiplier, page);
            case 17 -> adjust(player, DoudizhuPlugin.AdminSetting.PUBLIC_CARD_DEPTH_SCALE, increase, multiplier, page);
            case 19 -> adjust(player, DoudizhuPlugin.AdminSetting.HAND_SPACING, increase, multiplier, page);
            case 20 -> adjust(player, DoudizhuPlugin.AdminSetting.PUBLIC_TRICK_SPACING, increase, multiplier, page);
            case 21 -> adjust(player, DoudizhuPlugin.AdminSetting.PUBLIC_TRICK_HEIGHT, increase, multiplier, page);
            case 22 -> adjust(player, DoudizhuPlugin.AdminSetting.CARD_DEPTH_OFFSET, increase, multiplier, page);
            case 23 -> adjust(player, DoudizhuPlugin.AdminSetting.HOVER_CARD_SCALE, increase, multiplier, page);
            case 24 -> adjust(player, DoudizhuPlugin.AdminSetting.HOVER_CARD_LIFT, increase, multiplier, page);
            case 28 -> adjust(player, DoudizhuPlugin.AdminSetting.GLOBAL_HAND_LATERAL, increase, multiplier, page);
            case 29 -> adjust(player, DoudizhuPlugin.AdminSetting.GLOBAL_HAND_VERTICAL, increase, multiplier, page);
            case 30 -> adjust(player, DoudizhuPlugin.AdminSetting.GLOBAL_HAND_DEPTH, increase, multiplier, page);
            case 31 -> adjust(player, DoudizhuPlugin.AdminSetting.LABELS_ENABLED, increase, multiplier, page);
            case 32 -> adjust(player, DoudizhuPlugin.AdminSetting.DUPLICATE_ONLY, increase, multiplier, page);
            default -> {
            }
        }
    }

    private void handleAdminHitboxPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_LATERAL, increase, multiplier, page);
            case 11 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_DEPTH, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_VERTICAL, increase, multiplier, page);
            case 13 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_WIDTH, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_HEIGHT, increase, multiplier, page);
            case 19 -> adjust(player, DoudizhuPlugin.AdminSetting.CARD_HITBOX_LATERAL, increase, multiplier, page);
            case 20 -> adjust(player, DoudizhuPlugin.AdminSetting.CARD_HITBOX_DEPTH, increase, multiplier, page);
            case 21 -> adjust(player, DoudizhuPlugin.AdminSetting.CARD_HITBOX_VERTICAL, increase, multiplier, page);
            case 22 -> adjust(player, DoudizhuPlugin.AdminSetting.CARD_HITBOX_LENGTH, increase, multiplier, page);
            case 23 -> adjust(player, DoudizhuPlugin.AdminSetting.CARD_HITBOX_WIDTH, increase, multiplier, page);
            case 24 -> adjust(player, DoudizhuPlugin.AdminSetting.CARD_HITBOX_HEIGHT, increase, multiplier, page);
            case 28 -> adjust(player, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_LATERAL, increase, multiplier, page);
            case 29 -> adjust(player, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_VERTICAL, increase, multiplier, page);
            case 30 -> adjust(player, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_WIDTH, increase, multiplier, page);
            case 31 -> adjust(player, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_HEIGHT, increase, multiplier, page);
            default -> {
            }
        }
    }

    private void handleAdminAudioPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.BGM_VOLUME, increase, multiplier, page);
            case 11 -> adjust(player, DoudizhuPlugin.AdminSetting.EFFECT_VOLUME, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.TURN_COUNTDOWN_SECONDS, increase, multiplier, page);
            case 13 -> plugin.getHandGuiService().openCountdownSoundPicker(player);
            default -> {
            }
        }
    }

    private void handleAdminPlayerOptionsPage(Player player, int rawSlot, boolean leftClick) {
        if (rawSlot >= 10 && rawSlot <= 13) {
            int index = rawSlot - 10;
            if (leftClick) {
                plugin.getHandGuiService().beginCustomInput(player, HandInventoryHolder.EditorTarget.ADMIN_SELECTION_SOUND, index);
            } else {
                plugin.setSelectionSoundProfileDefinition(index, plugin.defaultSelectionSoundProfile(index));
                plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.PLAYER_OPTIONS);
            }
            return;
        }
        if (rawSlot >= 19 && rawSlot <= 22) {
            int index = rawSlot - 19;
            if (leftClick) {
                plugin.getHandGuiService().beginCustomInput(player, HandInventoryHolder.EditorTarget.ADMIN_PLAY_ACTION, index);
            } else {
                plugin.setPlayActionProfileDefinition(index, plugin.defaultPlayActionProfile(index));
                plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.PLAYER_OPTIONS);
            }
        }
    }

    private void handleAdminBotsPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.BOT_DELAY_MIN, increase, multiplier, page);
            case 11 -> adjust(player, DoudizhuPlugin.AdminSetting.BOT_DELAY_MAX, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.HINT_GROUP_LIMIT, increase, multiplier, page);
            default -> {
            }
        }
    }

    private void adjust(Player player, DoudizhuPlugin.AdminSetting setting, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        plugin.adjustAdminSetting(setting, increase, multiplier);
        plugin.getHandGuiService().openAdminModels(player, page);
    }

    private void applyHeldItemToFurniture(Player player, DoudizhuPlugin.FurnitureType type) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            throw new IllegalStateException("请先把要作为" + type.label() + "显示物品的物品拿在主手。");
        }
        plugin.setFurnitureDisplayItem(type, item);
    }

    private Component message(String text) {
        return Component.text(text, NamedTextColor.RED).decoration(TextDecoration.ITALIC, false);
    }
}
