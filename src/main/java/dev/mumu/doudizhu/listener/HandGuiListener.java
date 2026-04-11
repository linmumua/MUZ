package dev.mumu.doudizhu.listener;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.game.GameTable;
import dev.mumu.doudizhu.ui.HistoryInventoryHolder;
import dev.mumu.doudizhu.ui.HandInventoryHolder;
import dev.mumu.doudizhu.ui.MuzTheme;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import java.util.List;
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
        if (inventory.getHolder() instanceof HistoryInventoryHolder historyHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            if (!historyHolder.viewerId().equals(player.getUniqueId())) {
                player.closeInventory();
                return;
            }
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(inventory)) {
                return;
            }
            handleHistoryClick(player, historyHolder, event.getRawSlot());
            return;
        }
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
                case SETTINGS_ACTION_KIND_MENU -> handlePlayerActionKindClick(player, rawSlot);
                case SETTINGS_SELECTION_SOUND_PICKER, SETTINGS_PLAY_ACTION_PICKER -> handlePlayerPickerClick(player, holder.editorTarget(), holder.playActionKind(), rawSlot, event.isLeftClick(), event.isRightClick());
                case ADMIN_PLAY_ACTION_KIND_PICKER -> handleAdminPlayActionKindPickerClick(player, holder.playActionKind(), rawSlot, event.isLeftClick(), event.isRightClick());
                case ADMIN_SELECTION_SOUND_EDITOR -> handleAdminSelectionSoundEditorClick(player, holder.profileIndex(), rawSlot);
                case ADMIN_PLAY_ACTION_EDITOR -> handleAdminPlayActionEditorClick(player, holder.playActionKind(), holder.profileIndex(), rawSlot);
                case ADMIN_COUNTDOWN_SOUND_EDITOR -> handleConfiguredSoundEditorClick(player, holder.editorTarget(), rawSlot);
                case ADMIN_MODELS ->
                    handleAdminClick(player, holder.adminPage(), rawSlot, event.isLeftClick(), event.isRightClick(), event.getClick() == ClickType.MIDDLE, event.isShiftClick());
            }
        } catch (RuntimeException exception) {
            player.sendMessage(message(exception.getMessage()));
            plugin.getHandGuiService().refreshSettingsIfOpen(player);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof HandInventoryHolder
            || event.getView().getTopInventory().getHolder() instanceof HistoryInventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAsyncChat(AsyncChatEvent event) {
        // HARD-CODED:
        // Normal player chat must stay completely untouched by MUZ.
        // We only intercept chat here while a GUI text-input session is pending, including silent color-input sessions,
        // and we must never turn regular chat messages into overhead text, fake holograms, or any other world effect
        // unless the user explicitly asks.
        boolean pendingSound = plugin.getHandGuiService().hasPendingSoundInput(event.getPlayer().getUniqueId());
        boolean pendingColor = plugin.getHandGuiService().hasPendingSignInput(event.getPlayer().getUniqueId());
        if (!pendingSound && !pendingColor) {
            return;
        }
        String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
        event.viewers().clear();
        event.message(Component.empty());
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pendingSound) {
                plugin.getHandGuiService().handlePendingSoundInput(event.getPlayer(), plain);
            } else {
                plugin.getHandGuiService().handlePendingSignInput(event.getPlayer(), plain);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLegacyAsyncChat(AsyncPlayerChatEvent event) {
        boolean pendingSound = plugin.getHandGuiService().hasPendingSoundInput(event.getPlayer().getUniqueId());
        boolean pendingColor = plugin.getHandGuiService().hasPendingSignInput(event.getPlayer().getUniqueId());
        if (!pendingSound && !pendingColor) {
            return;
        }
        String plain = event.getMessage();
        event.getRecipients().clear();
        event.setMessage("");
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pendingSound) {
                plugin.getHandGuiService().handlePendingSoundInput(event.getPlayer(), plain);
            } else {
                plugin.getHandGuiService().handlePendingSignInput(event.getPlayer(), plain);
            }
        });
    }

    private void handleSettingsClick(Player player, int rawSlot) {
        GameTable table = plugin.getTableManager().getTableOf(player);
        switch (rawSlot) {
            case 10 -> {
                boolean enabled = plugin.toggleCardLabelsFor(player.getUniqueId());
                notifySettingSaved(player, "点数标签现在已" + (enabled ? "开启" : "关闭"));
            }
            case 12 -> {
                boolean enabled = plugin.toggleOpponentPreviewFor(player.getUniqueId());
                notifySettingSaved(player, "对手出牌对比现在已" + (enabled ? "开启" : "关闭"));
            }
            case 14 -> {
                plugin.getHandGuiService().openSelectionSoundPicker(player);
                return;
            }
            case 16 -> {
                plugin.getHandGuiService().openPlayActionPicker(player);
                return;
            }
            case 19 -> {
                plugin.getHandGuiService().beginRgbSignInput(player, HandInventoryHolder.EditorTarget.PLAYER_PREVIEW_GLOW);
                return;
            }
            case 21 -> {
                plugin.getHandGuiService().beginRgbSignInput(player, HandInventoryHolder.EditorTarget.PLAYER_SELECTED_GLOW);
                return;
            }
            case 23 -> {
                plugin.resetPlayerVisualSettings(player.getUniqueId());
                notifySettingSaved(player, "个人显示已经恢复成默认样子");
            }
            case 25 -> {
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

    private void handleHistoryClick(Player player, HistoryInventoryHolder holder, int rawSlot) {
        if (holder.mode() == HistoryInventoryHolder.Mode.DETAIL) {
            if (rawSlot == 45) {
                plugin.getHandGuiService().openHistory(player, holder.targetPlayerId(), holder.targetName(), holder.page());
            }
            return;
        }
        switch (rawSlot) {
            case 45 -> plugin.getHandGuiService().openHistory(player, holder.targetPlayerId(), holder.targetName(), Math.max(1, holder.page() - 1));
            case 49 -> plugin.getHandGuiService().openHistory(player, holder.targetPlayerId(), holder.targetName(), holder.page());
            case 53 -> plugin.getHandGuiService().openHistory(player, holder.targetPlayerId(), holder.targetName(), holder.page() + 1);
            default -> {
                ItemStack current = player.getOpenInventory().getTopInventory().getItem(rawSlot);
                if (current == null || !current.hasItemMeta()) {
                    return;
                }
                String marker = current.getItemMeta().getPersistentDataContainer().get(plugin.getTableNameKey(), org.bukkit.persistence.PersistentDataType.STRING);
                if (marker != null && marker.startsWith("history:")) {
                    long matchId = Long.parseLong(marker.substring("history:".length()));
                    plugin.getHandGuiService().openHistoryDetail(player, holder.targetPlayerId(), holder.targetName(), holder.page(), matchId);
                }
            }
        }
    }

    private void handlePlayerActionKindClick(Player player, int rawSlot) {
        if (rawSlot == 22) {
            plugin.getHandGuiService().openSettings(player);
            return;
        }
        if (rawSlot == 24) {
            player.closeInventory();
            return;
        }
        DoudizhuPlugin.PlayActionKind kind = switch (rawSlot) {
            case 10 -> DoudizhuPlugin.PlayActionKind.AIRPLANE;
            case 11 -> DoudizhuPlugin.PlayActionKind.STRAIGHT;
            case 12 -> DoudizhuPlugin.PlayActionKind.PAIR_STRAIGHT;
            case 14 -> DoudizhuPlugin.PlayActionKind.TRIPLE_WITH_SINGLE;
            case 15 -> DoudizhuPlugin.PlayActionKind.BOMB;
            case 16 -> DoudizhuPlugin.PlayActionKind.JOKER_BOMB;
            default -> null;
        };
        if (kind != null) {
            plugin.getHandGuiService().openPlayActionPicker(player, kind);
        }
    }

    private void handlePlayerPickerClick(Player player, HandInventoryHolder.EditorTarget target, DoudizhuPlugin.PlayActionKind kind, int rawSlot, boolean leftClick, boolean rightClick) {
        if (rawSlot == 22) {
            if (target == HandInventoryHolder.EditorTarget.PLAYER_PLAY_ACTION) {
                plugin.getHandGuiService().openPlayActionKindMenu(player);
            } else {
                plugin.getHandGuiService().openSettings(player);
            }
            return;
        }
        if (rawSlot == 24) {
            player.closeInventory();
            return;
        }
        int index = fourChoiceIndex(rawSlot);
        if (index < 0) {
            return;
        }
        if (leftClick) {
            plugin.getHandGuiService().previewPlayerOption(player, target, kind, index);
        }
        if (!rightClick) {
            if (target != HandInventoryHolder.EditorTarget.PLAYER_SELECTION || !leftClick) {
                return;
            }
        }
        if (target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION) {
            plugin.setPlayerSelectionSoundProfileIndex(player.getUniqueId(), index);
            notifySettingSaved(player, "选牌音效已经切到 " + plugin.getSelectionSoundProfiles().get(index).label());
            plugin.getHandGuiService().openSelectionSoundPicker(player);
        } else if (target == HandInventoryHolder.EditorTarget.PLAYER_PLAY_ACTION) {
            if (!rightClick) {
                return;
            }
            plugin.setPlayerPlayActionProfileIndex(player.getUniqueId(), kind, index);
            notifySettingSaved(player, normalizeKind(kind).label() + " 动作已经切到 " + plugin.getPlayActionProfiles(normalizeKind(kind)).get(index).label(), false);
            plugin.getHandGuiService().openPlayActionPicker(player, normalizeKind(kind));
        }
        GameTable table = plugin.getTableManager().getTableOf(player);
        if (table != null) {
            plugin.getPhysicalTableManager().refresh(table);
        }
    }

    private void handleConfiguredSoundEditorClick(Player player, HandInventoryHolder.EditorTarget target, int rawSlot) {
        if (rawSlot == 32) {
            if (target == HandInventoryHolder.EditorTarget.ADMIN_PLACEMENT_BLOCKED_WARNING) {
                plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_FURNITURE);
            } else {
                plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_AUDIO);
            }
            return;
        }
        if (rawSlot == 34) {
            player.closeInventory();
            return;
        }
        if (rawSlot == 30) {
            plugin.getHandGuiService().beginCustomInput(player, target, -1);
            return;
        }
        String preset = plugin.getHandGuiService().soundPresetSpec(target, rawSlot);
        if (preset == null) {
            return;
        }
        if (target == HandInventoryHolder.EditorTarget.ADMIN_UNREADY_WARNING) {
            plugin.setUnreadyWarningSoundSpec(preset);
            notifySettingSaved(player, "未准备提醒音已经换好了");
        } else if (target == HandInventoryHolder.EditorTarget.ADMIN_PLACEMENT_BLOCKED_WARNING) {
            plugin.setPlacementBlockedSoundSpec(preset);
            notifySettingSaved(player, "放置阻挡警告音已经换好了");
        } else {
            plugin.setCountdownSoundSpec(preset);
            notifySettingSaved(player, "倒计时音效已经换好了");
        }
        DoudizhuPlugin.ConfiguredSound sound = switch (target) {
            case ADMIN_UNREADY_WARNING -> plugin.unreadyWarningSound();
            case ADMIN_PLACEMENT_BLOCKED_WARNING -> plugin.placementBlockedWarningSound();
            default -> plugin.countdownSound();
        };
        if (sound.volume() > 0.0f) {
            player.playSound(player.getLocation(), sound.key(), sound.volume(), sound.pitch());
        }
        if (target == HandInventoryHolder.EditorTarget.ADMIN_UNREADY_WARNING) {
            plugin.getHandGuiService().openUnreadyWarningSoundPicker(player);
        } else if (target == HandInventoryHolder.EditorTarget.ADMIN_PLACEMENT_BLOCKED_WARNING) {
            plugin.getHandGuiService().openPlacementBlockedSoundPicker(player);
        } else {
            plugin.getHandGuiService().openCountdownSoundPicker(player);
        }
    }

    private void handleAdminSelectionSoundEditorClick(Player player, int profileIndex, int rawSlot) {
        if (rawSlot == 32) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_PLAYER_OPTIONS);
            return;
        }
        if (rawSlot == 34) {
            player.closeInventory();
            return;
        }
        if (rawSlot == 30) {
            plugin.getHandGuiService().beginCustomInput(player, HandInventoryHolder.EditorTarget.ADMIN_SELECTION_SOUND, profileIndex);
            return;
        }
        int presetIndex = rawSlot - 10;
        List<DoudizhuPlugin.OptionProfile> presets = List.of(
            new DoudizhuPlugin.OptionProfile("清脆提示", "minecraft:block.note_block.pling 0.35 1.18 0.92"),
            new DoudizhuPlugin.OptionProfile("告示牌提示", "minecraft:block.hanging_sign.place 0.35 1.12 0.92"),
            new DoudizhuPlugin.OptionProfile("洞穴提示", "minecraft:ambient.cave 0.25 1.00 0.90"),
            new DoudizhuPlugin.OptionProfile("钟声提示", "minecraft:block.note_block.bell 0.28 1.10 0.96"),
            new DoudizhuPlugin.OptionProfile("金属提示", "minecraft:block.iron_trapdoor.open 0.24 1.22 0.98"),
            new DoudizhuPlugin.OptionProfile("按钮提示", "minecraft:ui.button.click 0.30 1.06 0.92"),
            new DoudizhuPlugin.OptionProfile("静音", "minecraft:block.note_block.hat 0.00 1.00 1.00")
        );
        if (presetIndex < 0 || presetIndex >= presets.size()) {
            return;
        }
        plugin.setSelectionSoundProfileDefinition(profileIndex, presets.get(presetIndex));
        DoudizhuPlugin.SelectionSound sound = plugin.selectionSoundForProfile(profileIndex);
        if (sound.volume() > 0.0f) {
            player.playSound(player.getLocation(), sound.key(), sound.volume(), sound.selectedPitch());
        }
        notifySettingSaved(player, "选牌音效方案 " + (profileIndex + 1) + " 已经更新");
        plugin.getHandGuiService().openAdminSelectionSoundEditor(player, profileIndex);
    }

    private void handleAdminPlayActionKindPickerClick(Player player, DoudizhuPlugin.PlayActionKind kind, int rawSlot, boolean leftClick, boolean rightClick) {
        if (rawSlot == 22) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_PLAYER_OPTIONS);
            return;
        }
        if (rawSlot == 24) {
            player.closeInventory();
            return;
        }
        int index = fourChoiceIndex(rawSlot);
        if (index < 0) {
            return;
        }
        DoudizhuPlugin.PlayActionKind normalizedKind = normalizeKind(kind);
        if (leftClick) {
            plugin.getHandGuiService().openAdminPlayActionEditor(player, normalizedKind, index);
        } else if (rightClick) {
            plugin.setPlayActionProfileDefinition(normalizedKind, index, plugin.defaultPlayActionProfile(index));
            notifySettingSaved(player, normalizedKind.label() + " 动作 " + (index + 1) + " 已经恢复默认", false);
            plugin.getHandGuiService().openAdminPlayActionKindPicker(player, normalizedKind);
        }
    }

    private void handleAdminPlayActionEditorClick(Player player, DoudizhuPlugin.PlayActionKind kind, int profileIndex, int rawSlot) {
        if (rawSlot == 32) {
            plugin.getHandGuiService().openAdminPlayActionKindPicker(player, normalizeKind(kind));
            return;
        }
        if (rawSlot == 34) {
            player.closeInventory();
            return;
        }
        if (rawSlot == 30) {
            plugin.getHandGuiService().beginCustomInput(player, HandInventoryHolder.EditorTarget.ADMIN_PLAY_ACTION, normalizeKind(kind), profileIndex);
            return;
        }
        int presetIndex = rawSlot - 10;
        List<DoudizhuPlugin.OptionProfile> presets = List.of(
            new DoudizhuPlugin.OptionProfile("无操作", "type: none"),
            new DoudizhuPlugin.OptionProfile("聊天提示", "type: message; message: <yellow>你打出了 <arg:pattern></yellow>"),
            new DoudizhuPlugin.OptionProfile("动作栏提示", "type: actionbar; actionbar: <gold><arg:player.name></gold> 打出了 <yellow><arg:pattern></yellow>"),
            new DoudizhuPlugin.OptionProfile("播放音效", "type: play_sound; sound: minecraft:entity.player.levelup; volume: 0.35; pitch: 1.05; source: master"),
            new DoudizhuPlugin.OptionProfile("标题提示", "type: title; title: <green>出牌成功</green>; subtitle: <yellow><arg:pattern></yellow>; fade-in: 5; stay: 30; fade-out: 10"),
            new DoudizhuPlugin.OptionProfile("控制台命令", "type: command; command: say <arg:player.name> 打出了 <arg:pattern>"),
            new DoudizhuPlugin.OptionProfile("玩家命令", "type: command; command: help; as-player: true")
        );
        if (presetIndex < 0 || presetIndex >= presets.size()) {
            return;
        }
        plugin.setPlayActionProfileDefinition(normalizeKind(kind), profileIndex, presets.get(presetIndex));
        dev.mumu.doudizhu.action.CeActionExecutor.previewPlayProfile(plugin, player, presets.get(presetIndex));
        notifySettingSaved(player, normalizeKind(kind).label() + " 动作 " + (profileIndex + 1) + " 已经更新", false);
        plugin.getHandGuiService().openAdminPlayActionEditor(player, normalizeKind(kind), profileIndex);
    }

    private DoudizhuPlugin.PlayActionKind normalizeKind(DoudizhuPlugin.PlayActionKind kind) {
        return kind == null ? DoudizhuPlugin.PlayActionKind.AIRPLANE : kind;
    }

    private void handleAdminClick(Player player, HandInventoryHolder.AdminPage page, int rawSlot, boolean leftClick, boolean rightClick, boolean middleClick, boolean shiftClick) {
        if (!player.hasPermission("muz.admin")) {
            throw new IllegalStateException("这个菜单需要管理员权限才能打开。");
        }
        // 管理菜单采用“首页 -> 斗地主/德州/通用 -> 具体分类页”的结构。
        HandInventoryHolder.AdminPage currentPage = page == null ? HandInventoryHolder.AdminPage.HOME : page;
        if (rawSlot == 44) {
            HandInventoryHolder.AdminPage parent = switch (currentPage) {
                case HOME -> null;
                case DDZ_HOME, TEXAS_HOME, GLOBAL_HOME, GLOBAL_ECONOMY -> HandInventoryHolder.AdminPage.HOME;
                case DDZ_FURNITURE, DDZ_BUTTONS, DDZ_CARDS, DDZ_LABELS, DDZ_TEXT, DDZ_HITBOX, DDZ_AUDIO, DDZ_PLAYER_OPTIONS, DDZ_BOTS -> HandInventoryHolder.AdminPage.DDZ_HOME;
                case TEXAS_FURNITURE, TEXAS_BUTTONS, TEXAS_CARDS, TEXAS_TEXT -> HandInventoryHolder.AdminPage.TEXAS_HOME;
                case GLOBAL_ANIMATION, GLOBAL_HIGHLIGHT, GLOBAL_AVATARS -> HandInventoryHolder.AdminPage.GLOBAL_HOME;
                case GLOBAL_STATUS_AVATARS, GLOBAL_SEAT_AVATARS -> HandInventoryHolder.AdminPage.GLOBAL_AVATARS;
            };
            if (parent != null) {
                plugin.getHandGuiService().openAdminModels(player, parent);
            }
            return;
        }
        if (rawSlot == 49) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.HOME);
            return;
        }
        if (rawSlot == 52) {
            plugin.reloadPluginState(player);
            plugin.getHandGuiService().openAdminModels(player, page == null ? HandInventoryHolder.AdminPage.HOME : page);
            return;
        }
        if (rawSlot == 53) {
            player.closeInventory();
            return;
        }

        int multiplier = shiftClick ? 10 : 1;
        HandInventoryHolder.AdminPage current = currentPage;
        switch (current) {
            case HOME -> handleAdminHomePage(player, rawSlot);
            case DDZ_HOME -> handleAdminDdzHomePage(player, rawSlot);
            case TEXAS_HOME -> handleAdminTexasHomePage(player, rawSlot);
            case GLOBAL_HOME -> handleAdminGlobalHomePage(player, rawSlot);
            case DDZ_FURNITURE -> handleAdminFurniturePage(player, rawSlot, leftClick, multiplier, current);
            case DDZ_BUTTONS -> handleAdminButtonsPage(player, rawSlot, leftClick, multiplier, current);
            case DDZ_CARDS -> handleAdminCardsPage(player, rawSlot, leftClick, multiplier, current);
            case DDZ_LABELS -> handleAdminLabelsPage(player, rawSlot, leftClick, multiplier, current);
            case DDZ_TEXT -> handleAdminTextPage(player, rawSlot, leftClick, multiplier, current);
            case DDZ_HITBOX -> handleAdminHitboxPage(player, rawSlot, leftClick, multiplier, current);
            case DDZ_AUDIO -> handleAdminAudioPage(player, rawSlot, leftClick, multiplier, current);
            case DDZ_PLAYER_OPTIONS -> handleAdminPlayerOptionsPage(player, rawSlot, leftClick);
            case DDZ_BOTS -> handleAdminBotsPage(player, rawSlot, leftClick, multiplier, current);
            case TEXAS_FURNITURE -> handleAdminTexasFurniturePage(player, rawSlot, leftClick, multiplier, current);
            case TEXAS_BUTTONS -> handleAdminTexasButtonsPage(player, rawSlot, leftClick, multiplier, current);
            case TEXAS_CARDS -> handleAdminTexasCardsPage(player, rawSlot, leftClick, multiplier, current);
            case TEXAS_TEXT -> handleAdminTexasTextPage(player, rawSlot, leftClick, multiplier, current);
            case GLOBAL_ECONOMY -> handleAdminEconomyPage(player, rawSlot, leftClick, rightClick, middleClick, multiplier);
            case GLOBAL_ANIMATION -> handleAdminAnimationPage(player, rawSlot, leftClick, multiplier, current);
            case GLOBAL_HIGHLIGHT -> handleAdminHighlightPage(player, rawSlot, leftClick, multiplier, current);
            case GLOBAL_AVATARS -> handleAdminAvatarHomePage(player, rawSlot);
            case GLOBAL_STATUS_AVATARS -> handleAdminStatusAvatarPage(player, rawSlot, leftClick, multiplier, current);
            case GLOBAL_SEAT_AVATARS -> handleAdminSeatAvatarPage(player, rawSlot, leftClick, multiplier, current);
        }
    }

    private void handleAdminHomePage(Player player, int rawSlot) {
        switch (rawSlot) {
            case 19 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_HOME);
            case 21 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.TEXAS_HOME);
            case 23 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_HOME);
            case 25 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_ECONOMY);
            default -> {
            }
        }
    }

    private void handleAdminDdzHomePage(Player player, int rawSlot) {
        switch (rawSlot) {
            case 19 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_FURNITURE);
            case 20 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_BUTTONS);
            case 21 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_CARDS);
            case 22 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_TEXT);
            case 23 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_LABELS);
            case 28 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_HITBOX);
            case 29 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_AUDIO);
            case 30 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_PLAYER_OPTIONS);
            case 31 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_BOTS);
            default -> {
            }
        }
    }

    private void handleAdminTexasHomePage(Player player, int rawSlot) {
        switch (rawSlot) {
            case 20 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.TEXAS_FURNITURE);
            case 22 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.TEXAS_BUTTONS);
            case 29 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.TEXAS_CARDS);
            case 31 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.TEXAS_TEXT);
            default -> {
            }
        }
    }

    private void handleAdminGlobalHomePage(Player player, int rawSlot) {
        switch (rawSlot) {
            case 20 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_ANIMATION);
            case 22 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_HIGHLIGHT);
            case 24 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_AVATARS);
            default -> {
            }
        }
    }

    private void handleAdminAvatarHomePage(Player player, int rawSlot) {
        switch (rawSlot) {
            case 20 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_STATUS_AVATARS);
            case 24 -> plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_SEAT_AVATARS);
            default -> {
            }
        }
    }

    private void handleAdminEconomyPage(Player player, int rawSlot, boolean increase, boolean rightClick, boolean middleClick, int multiplier) {
        switch (rawSlot) {
            case 10 -> {
                plugin.setChipPaymentEnabled(!plugin.isChipPaymentEnabled());
                notifySettingSaved(player, "支付模式已经切到" + (plugin.isChipPaymentEnabled() ? "筹码" : "金币"));
            }
            case 12 -> {
                ItemStack item = player.getInventory().getItemInMainHand();
                if (item == null || item.getType().isAir()) {
                    throw new IllegalStateException("请先把要作为筹码的物品拿在主手。");
                }
                plugin.setChipPaymentItem(item);
                notifySettingSaved(player, "全局筹码外观已经换成主手物品");
            }
            case 14 -> {
                plugin.setChipPaymentItem(null);
                notifySettingSaved(player, "筹码外观已经恢复默认");
            }
            case 19 -> adjustRoomLevel(player, dev.mumu.doudizhu.room.TableLevel.LOW, increase, multiplier);
            case 20 -> adjustRoomLevel(player, dev.mumu.doudizhu.room.TableLevel.MID, increase, multiplier);
            case 21 -> adjustRoomLevel(player, dev.mumu.doudizhu.room.TableLevel.HIGH, increase, multiplier);
            case 22 -> adjustRoomLevel(player, dev.mumu.doudizhu.room.TableLevel.FUN, increase, multiplier);
            case 28 -> toggleRoomEconomy(player, dev.mumu.doudizhu.room.TableLevel.LOW);
            case 29 -> toggleRoomEconomy(player, dev.mumu.doudizhu.room.TableLevel.MID);
            case 30 -> toggleRoomEconomy(player, dev.mumu.doudizhu.room.TableLevel.HIGH);
            case 31 -> toggleRoomEconomy(player, dev.mumu.doudizhu.room.TableLevel.FUN);
            case 45, 46, 47, 48, 49, 50, 51 -> {
                if (middleClick) {
                    beginExactChipBalanceInput(player, rawSlot);
                    return;
                }
                adjustOnlineChipBalance(player, rawSlot, increase || !rightClick, multiplier);
            }
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_ECONOMY);
    }

    private void adjustRoomLevel(Player player, dev.mumu.doudizhu.room.TableLevel level, boolean increase, int multiplier) {
        double current = plugin.roomMultiplier(level);
        double step = Math.max(1.0, level == dev.mumu.doudizhu.room.TableLevel.FUN ? 10.0 : Math.max(10.0, current / 10.0));
        double delta = step * Math.max(1, multiplier);
        double next = Math.max(0.0, current + (increase ? delta : -delta));
        plugin.setRoomLevelMultiplier(level, next);
        notifySettingSaved(player, plugin.roomDisplayLabel(level) + "倍率现在是 " + plugin.formatMultiplier(next));
    }

    private void toggleRoomEconomy(Player player, dev.mumu.doudizhu.room.TableLevel level) {
        boolean enabled = plugin.toggleRoomLevelEconomy(level);
        notifySettingSaved(player, plugin.roomDisplayLabel(level) + "经济现在已" + (enabled ? "开启" : "关闭"));
    }

    private void adjustOnlineChipBalance(Player player, int rawSlot, boolean increase, int multiplier) {
        List<Player> online = Bukkit.getOnlinePlayers().stream()
            .map(target -> (Player) target)
            .sorted(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
            .limit(7)
            .toList();
        int index = rawSlot - 45;
        if (index < 0 || index >= online.size()) {
            return;
        }
        Player target = online.get(index);
        int delta = Math.max(1, multiplier) * (increase ? 1 : -1);
        int next = plugin.adjustChipBalance(target.getUniqueId(), delta);
        notifySettingSaved(player, target.getName() + " 的筹码现在是 " + next);
    }

    private void beginExactChipBalanceInput(Player player, int rawSlot) {
        List<Player> online = Bukkit.getOnlinePlayers().stream()
            .map(target -> (Player) target)
            .sorted(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
            .limit(7)
            .toList();
        int index = rawSlot - 45;
        if (index < 0 || index >= online.size()) {
            return;
        }
        plugin.getHandGuiService().beginChipBalanceInput(player, online.get(index));
    }

    private void handleAdminFurniturePage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> applyHeldItemToFurniture(player, DoudizhuPlugin.FurnitureType.TABLE);
            case 12 -> {
                plugin.resetFurnitureDisplayItem(DoudizhuPlugin.FurnitureType.TABLE);
                notifySettingSaved(player, "桌子外观已经恢复默认");
            }
            case 14 -> applyHeldItemToFurniture(player, DoudizhuPlugin.FurnitureType.CHAIR);
            case 16 -> {
                plugin.resetFurnitureDisplayItem(DoudizhuPlugin.FurnitureType.CHAIR);
                notifySettingSaved(player, "椅子外观已经恢复默认");
            }
            case 19 -> adjust(player, DoudizhuPlugin.AdminSetting.TABLE_SPAWN_OFFSET_Y, increase, multiplier, page);
            case 21 -> adjust(player, DoudizhuPlugin.AdminSetting.CHAIR_VISUAL_LATERAL, increase, multiplier, page);
            case 23 -> adjust(player, DoudizhuPlugin.AdminSetting.CHAIR_VISUAL_VERTICAL, increase, multiplier, page);
            case 25 -> adjust(player, DoudizhuPlugin.AdminSetting.CHAIR_ROTATION_DEGREES, increase, multiplier, page);
            case 27 -> adjust(player, DoudizhuPlugin.AdminSetting.CHAIR_DISTANCE, increase, multiplier, page);
            case 29 -> plugin.getHandGuiService().openPlacementBlockedSoundPicker(player);
            default -> {
            }
        }
        if (rawSlot != 29) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_FURNITURE);
        }
    }

    private void handleAdminButtonsPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_DISTANCE, increase, multiplier, page);
            case 11 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_HEIGHT, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_SCALE, increase, multiplier, page);
            case 13 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_ROLL_DEGREES, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.HOVER_BUTTON_SCALE, increase, multiplier, page);
            case 15 -> adjust(player, DoudizhuPlugin.AdminSetting.HOVER_BUTTON_LIFT, increase, multiplier, page);
            case 19 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_FRONT_BASE_DISTANCE, increase, multiplier, page);
            case 20 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_SIDE_BASE_DISTANCE, increase, multiplier, page);
            case 21 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_DISTANCE_FACTOR, increase, multiplier, page);
            case 22 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_SPACING, increase, multiplier, page);
            case 23 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_ARC_SMALL_ANGLE, increase, multiplier, page);
            case 24 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_ARC_LARGE_ANGLE, increase, multiplier, page);
            case 25 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_ARC_SMALL_RADIUS, increase, multiplier, page);
            case 26 -> adjust(player, DoudizhuPlugin.AdminSetting.BUTTON_ARC_LARGE_RADIUS, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_BUTTONS);
    }

    private void handleAdminStatusAvatarPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.STATUS_AVATAR_SCALE, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.STATUS_AVATAR_LATERAL, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.STATUS_AVATAR_VERTICAL, increase, multiplier, page);
            case 16 -> adjust(player, DoudizhuPlugin.AdminSetting.STATUS_AVATAR_DEPTH, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_STATUS_AVATARS);
    }

    private void handleAdminSeatAvatarPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.SEAT_AVATAR_SCALE, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.SEAT_AVATAR_LATERAL, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.SEAT_AVATAR_VERTICAL, increase, multiplier, page);
            case 16 -> adjust(player, DoudizhuPlugin.AdminSetting.SEAT_AVATAR_DEPTH, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_SEAT_AVATARS);
    }

    private void handleAdminAnimationPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 11 -> adjust(player, DoudizhuPlugin.AdminSetting.HOVER_CARD_INTERPOLATION_TICKS, increase, multiplier, page);
            case 13 -> adjust(player, DoudizhuPlugin.AdminSetting.HOVER_CARD_ANIMATION_TYPE, increase, multiplier, page);
            case 15 -> adjust(player, DoudizhuPlugin.AdminSetting.HOVER_BUTTON_INTERPOLATION_TICKS, increase, multiplier, page);
            case 17 -> adjust(player, DoudizhuPlugin.AdminSetting.HOVER_BUTTON_ANIMATION_TYPE, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_ANIMATION);
    }

    private void handleAdminHighlightPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.HOVER_GLOW_ENABLED, increase, multiplier, page);
            case 12 -> {
                plugin.getHandGuiService().beginRgbSignInput(player, HandInventoryHolder.EditorTarget.ADMIN_HOVER_GLOW);
                return;
            }
            case 28 -> adjust(player, DoudizhuPlugin.AdminSetting.SELECTED_GLOW_ENABLED, increase, multiplier, page);
            case 30 -> {
                plugin.getHandGuiService().beginRgbSignInput(player, HandInventoryHolder.EditorTarget.ADMIN_SELECTED_GLOW);
                return;
            }
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_HIGHLIGHT);
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
            case 31 -> adjust(player, DoudizhuPlugin.AdminSetting.PUBLIC_PREVIEW_ROW_DEPTH_SPACING, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_CARDS);
    }

    private void handleAdminTextPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.STATUS_HEIGHT, increase, multiplier, page);
            case 11 -> adjust(player, DoudizhuPlugin.AdminSetting.PLAY_DETAIL_HEIGHT, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.JOIN_LABEL_HEIGHT, increase, multiplier, page);
            case 13 -> adjust(player, DoudizhuPlugin.AdminSetting.ACTION_LABEL_HEIGHT, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_TEXT);
    }

    private void handleAdminLabelsPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.CARD_LABEL_HEIGHT, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.CARD_LABEL_LATERAL, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.CARD_LABEL_DEPTH, increase, multiplier, page);
            case 28 -> adjust(player, DoudizhuPlugin.AdminSetting.LABELS_ENABLED, increase, multiplier, page);
            case 30 -> adjust(player, DoudizhuPlugin.AdminSetting.DUPLICATE_ONLY, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_LABELS);
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
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_HITBOX);
    }

    private void handleAdminAudioPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.BGM_VOLUME, increase, multiplier, page);
            case 11 -> adjust(player, DoudizhuPlugin.AdminSetting.EFFECT_VOLUME, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.TURN_COUNTDOWN_SECONDS, increase, multiplier, page);
            case 13 -> plugin.getHandGuiService().openCountdownSoundPicker(player);
            case 14 -> plugin.getHandGuiService().openUnreadyWarningSoundPicker(player);
            default -> {
            }
        }
        if (rawSlot != 13 && rawSlot != 14) {
            plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_AUDIO);
        }
    }

    private void handleAdminPlayerOptionsPage(Player player, int rawSlot, boolean leftClick) {
        if (rawSlot >= 10 && rawSlot <= 13) {
            int index = rawSlot - 10;
            if (leftClick) {
                plugin.getHandGuiService().openAdminSelectionSoundEditor(player, index);
            } else {
                plugin.setSelectionSoundProfileDefinition(index, plugin.defaultSelectionSoundProfile(index));
                notifySettingSaved(player, "选牌音效方案 " + (index + 1) + " 已经恢复默认");
                plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_PLAYER_OPTIONS);
            }
            return;
        }
        DoudizhuPlugin.PlayActionKind kind = switch (rawSlot) {
            case 19 -> DoudizhuPlugin.PlayActionKind.AIRPLANE;
            case 20 -> DoudizhuPlugin.PlayActionKind.STRAIGHT;
            case 21 -> DoudizhuPlugin.PlayActionKind.PAIR_STRAIGHT;
            case 28 -> DoudizhuPlugin.PlayActionKind.TRIPLE_WITH_SINGLE;
            case 29 -> DoudizhuPlugin.PlayActionKind.BOMB;
            case 30 -> DoudizhuPlugin.PlayActionKind.JOKER_BOMB;
            default -> null;
        };
        if (kind != null) {
            plugin.getHandGuiService().openAdminPlayActionKindPicker(player, kind);
        }
    }

    private int fourChoiceIndex(int rawSlot) {
        return switch (rawSlot) {
            case 10 -> 0;
            case 12 -> 1;
            case 14 -> 2;
            case 16 -> 3;
            default -> -1;
        };
    }

    private void handleAdminBotsPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.BOT_DELAY_MIN, increase, multiplier, page);
            case 11 -> adjust(player, DoudizhuPlugin.AdminSetting.BOT_DELAY_MAX, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.HINT_GROUP_LIMIT, increase, multiplier, page);
            case 14 -> {
                plugin.getHandGuiService().beginCustomInput(player, HandInventoryHolder.EditorTarget.ADMIN_AI_URL, -1);
                return;
            }
            case 15 -> {
                plugin.getHandGuiService().beginCustomInput(player, HandInventoryHolder.EditorTarget.ADMIN_AI_KEY, -1);
                return;
            }
            case 16 -> {
                plugin.getHandGuiService().beginCustomInput(player, HandInventoryHolder.EditorTarget.ADMIN_AI_MODEL, -1);
                return;
            }
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_BOTS);
    }

    private void handleAdminTexasFurniturePage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_SEAT_DISTANCE, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_SPAWN_FURNITURE, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_DEALER_MARKER_HEIGHT, increase, multiplier, page);
            case 16 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_DEALER_MARKER_RADIUS_FACTOR, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.TEXAS_FURNITURE);
    }

    private void handleAdminTexasButtonsPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_JOIN_BUTTON_HEIGHT, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_ACTION_BUTTON_HEIGHT, increase, multiplier, page);
            case 16 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_ACTION_BUTTON_STEP, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.TEXAS_BUTTONS);
    }

    private void handleAdminTexasCardsPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 10 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_COMMUNITY_CARD_HEIGHT, increase, multiplier, page);
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_COMMUNITY_CARD_SPACING, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_HOLE_CARD_HEIGHT, increase, multiplier, page);
            case 16 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_HOLE_CARD_SPACING, increase, multiplier, page);
            case 22 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_HOLE_RADIUS_FACTOR, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.TEXAS_CARDS);
    }

    private void handleAdminTexasTextPage(Player player, int rawSlot, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        switch (rawSlot) {
            case 12 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_SEAT_LABEL_HEIGHT, increase, multiplier, page);
            case 14 -> adjust(player, DoudizhuPlugin.AdminSetting.TEXAS_STATUS_HEIGHT, increase, multiplier, page);
            default -> {
            }
        }
        plugin.getHandGuiService().openAdminModels(player, HandInventoryHolder.AdminPage.TEXAS_TEXT);
    }

    private void adjust(Player player, DoudizhuPlugin.AdminSetting setting, boolean increase, int multiplier, HandInventoryHolder.AdminPage page) {
        plugin.adjustAdminSetting(setting, increase, multiplier);
        notifySettingSaved(player, setting.label() + " 现在是 " + plugin.adminSettingValue(setting));
        plugin.getHandGuiService().openAdminModels(player, page);
    }

    private void applyHeldItemToFurniture(Player player, DoudizhuPlugin.FurnitureType type) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            throw new IllegalStateException("先把你想拿来当" + type.label() + "外观的物品放到主手里。");
        }
        if (type == DoudizhuPlugin.FurnitureType.CHAIR && !plugin.canUseHeldItemAsChairFurniture(item)) {
            throw new IllegalStateException("椅子只支持 CE 家具、CE 方块物品或原版方块，先把对应物品拿到主手。");
        }
        plugin.setFurnitureDisplayItem(type, item);
        notifySettingSaved(player, type.label() + "外观已经换成主手物品");
    }

    private void notifySettingSaved(Player player, String text) {
        notifySettingSaved(player, text, true);
    }

    private void notifySettingSaved(Player player, String text, boolean playSound) {
        player.sendActionBar(MuzTheme.success("已经记下了 · " + text));
        if (playSound) {
            player.playSound(player.getLocation(), "minecraft:block.note_block.pling", 0.55f, 1.35f);
        }
    }

    private Component message(String text) {
        return MuzTheme.danger(text);
    }
}

