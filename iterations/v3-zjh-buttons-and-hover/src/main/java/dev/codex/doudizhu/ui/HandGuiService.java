package dev.codex.doudizhu.ui;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.game.GameTable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class HandGuiService {
    private static final int SETTINGS_SIZE = 27;
    private static final int PICKER_SIZE = 27;
    private static final int ADMIN_SIZE = 54;
    private static final int COUNTDOWN_EDITOR_SIZE = 36;

    private final DoudizhuPlugin plugin;
    private final Map<UUID, InputSession> pendingInputs = new ConcurrentHashMap<>();

    public HandGuiService(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public void openSettings(Player player) {
        openSettings(player, plugin.getTableManager().getTableOf(player));
    }

    public void openSettings(Player player, GameTable table) {
        HandInventoryHolder holder = new HandInventoryHolder(table == null ? "" : table.getName(), player.getUniqueId(), HandInventoryHolder.ViewMode.SETTINGS);
        Inventory inventory = Bukkit.createInventory(holder, SETTINGS_SIZE, "斗地主 | 个人设置");
        holder.setInventory(inventory);
        inventory.setItem(4, item(Material.BOOK, "你的个人偏好", List.of(
            "点数标签: " + bool(plugin.isCardLabelsEnabledFor(player.getUniqueId())),
            "对手出牌对比: " + bool(plugin.isOpponentPreviewEnabledFor(player.getUniqueId())),
            "选牌音效开关: " + bool(plugin.isSelectionSoundEnabledFor(player.getUniqueId())),
            "音效方案: " + plugin.getSelectionSoundProfile(plugin.getPlayerSelectionSoundProfileIndex(player.getUniqueId())).label(),
            "出牌动作: " + plugin.getPlayActionProfile(plugin.getPlayerPlayActionProfileIndex(player.getUniqueId())).label()
        )));
        inventory.setItem(10, toggleItem(Material.NAME_TAG, "牌面点数标签", plugin.isCardLabelsEnabledFor(player.getUniqueId())));
        inventory.setItem(12, toggleItem(Material.SPYGLASS, "对手出牌对比", plugin.isOpponentPreviewEnabledFor(player.getUniqueId())));
        inventory.setItem(14, toggleItem(Material.NOTE_BLOCK, "选牌音效开关", plugin.isSelectionSoundEnabledFor(player.getUniqueId())));
        inventory.setItem(15, item(Material.JUKEBOX, "选牌音效方案", List.of("当前: " + plugin.getSelectionSoundProfile(plugin.getPlayerSelectionSoundProfileIndex(player.getUniqueId())).label(), "点击选择管理员提供的方案。")));
        inventory.setItem(16, item(Material.COMMAND_BLOCK, "出牌执行操作", List.of("当前: " + plugin.getPlayActionProfile(plugin.getPlayerPlayActionProfileIndex(player.getUniqueId())).label(), "点击选择管理员提供的 CE 方案。")));
        inventory.setItem(23, item(Material.BOOK, "恢复默认偏好", List.of("清空你的个人偏好。")));
        inventory.setItem(24, item(Material.BARRIER, "关闭菜单", List.of("关闭当前菜单。")));
        player.openInventory(inventory);
    }

    public void openSelectionSoundPicker(Player player) {
        openPlayerProfilePicker(player, HandInventoryHolder.EditorTarget.PLAYER_SELECTION, "斗地主 | 选牌音效方案");
    }

    public void openPlayActionPicker(Player player) {
        openPlayerProfilePicker(player, HandInventoryHolder.EditorTarget.PLAYER_PLAY_ACTION, "斗地主 | 出牌执行操作");
    }

    private void openPlayerProfilePicker(Player player, HandInventoryHolder.EditorTarget target, String title) {
        GameTable table = plugin.getTableManager().getTableOf(player);
        HandInventoryHolder.ViewMode mode = target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION
            ? HandInventoryHolder.ViewMode.SETTINGS_SELECTION_SOUND_PICKER
            : HandInventoryHolder.ViewMode.SETTINGS_PLAY_ACTION_PICKER;
        HandInventoryHolder holder = new HandInventoryHolder(table == null ? "" : table.getName(), player.getUniqueId(), mode, target, -1);
        Inventory inventory = Bukkit.createInventory(holder, PICKER_SIZE, title);
        holder.setInventory(inventory);
        List<DoudizhuPlugin.OptionProfile> profiles = target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION
            ? plugin.getSelectionSoundProfiles()
            : plugin.getPlayActionProfiles();
        int selected = target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION
            ? plugin.getPlayerSelectionSoundProfileIndex(player.getUniqueId())
            : plugin.getPlayerPlayActionProfileIndex(player.getUniqueId());
        inventory.setItem(4, item(Material.BOOK, "当前方案", List.of("当前: " + profiles.get(selected).label(), "这些方案由管理员统一维护。")));
        for (int index = 0; index < profiles.size() && index < 4; index++) {
            int slot = 10 + index;
            inventory.setItem(slot, item(
                target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION ? Material.NOTE_BLOCK : Material.COMMAND_BLOCK,
                (index == selected ? "已选 | " : "") + profiles.get(index).label(),
                List.of(profiles.get(index).spec(), index == selected ? "当前正在使用。" : "点击切换。")
            ));
        }
        inventory.setItem(22, item(Material.ARROW, "返回个人设置", List.of("返回上一页。")));
        inventory.setItem(24, item(Material.BARRIER, "关闭菜单", List.of("关闭当前菜单。")));
        player.openInventory(inventory);
    }

    public void openAdminModels(Player player) {
        openAdminModels(player, HandInventoryHolder.AdminPage.MODELS);
    }

    public void openAdminModels(Player player, HandInventoryHolder.AdminPage page) {
        HandInventoryHolder holder = new HandInventoryHolder("", player.getUniqueId(), HandInventoryHolder.ViewMode.ADMIN_MODELS, page);
        Inventory inventory = Bukkit.createInventory(holder, ADMIN_SIZE, "斗地主 | 管理菜单 | " + pageTitle(page));
        holder.setInventory(inventory);
        inventory.setItem(4, item(Material.WRITABLE_BOOK, "当前分类: " + pageTitle(page), List.of("管理员菜单按桌面 / 牌面 / 碰撞 / 音频 / 玩家选项 / 机器人分类。", "左键增加，右键减少，Shift 乘 10。")));
        inventory.setItem(45, item(Material.ITEM_FRAME, "模型", List.of("切换到模型设置。")));
        inventory.setItem(46, item(Material.OAK_STAIRS, "桌面", List.of("切换到桌面与按钮设置。")));
        inventory.setItem(47, item(Material.PAPER, "牌面", List.of("切换到手牌与预览设置。")));
        inventory.setItem(48, item(Material.STRUCTURE_VOID, "碰撞", List.of("切换到碰撞箱设置。")));
        inventory.setItem(49, item(Material.NOTE_BLOCK, "音频", List.of("切换到音频设置。")));
        inventory.setItem(50, item(Material.BOOK, "玩家选项", List.of("管理玩家可选方案。")));
        inventory.setItem(51, item(Material.CLOCK, "机器人", List.of("管理 bot 和压测设置。")));
        inventory.setItem(52, item(Material.COMPASS, "校验并重载", List.of("热重载配置与牌桌。")));
        inventory.setItem(53, item(Material.BARRIER, "关闭菜单", List.of("关闭当前菜单。")));

        switch (page) {
            case MODELS -> {
                inventory.setItem(10, item(Material.CARTOGRAPHY_TABLE, "主手设为桌子", List.of("把主手物品写入桌子显示配置。")));
                inventory.setItem(12, item(Material.OAK_STAIRS, "主手设为椅子", List.of("把主手物品写入椅子显示配置。")));
                inventory.setItem(14, item(Material.BRUSH, "桌子恢复默认", List.of("恢复默认桌子模型。")));
                inventory.setItem(16, item(Material.BRUSH, "椅子恢复默认", List.of("恢复默认椅子模型。")));
            }
            case TABLE, SEAT -> {
                inventory.setItem(10, adminSettingItem(Material.LODESTONE, DoudizhuPlugin.AdminSetting.TABLE_SPAWN_OFFSET_Y));
                inventory.setItem(11, adminSettingItem(Material.STONE_BUTTON, DoudizhuPlugin.AdminSetting.BUTTON_DISTANCE));
                inventory.setItem(12, adminSettingItem(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, DoudizhuPlugin.AdminSetting.BUTTON_HEIGHT));
                inventory.setItem(13, adminSettingItem(Material.SMALL_AMETHYST_BUD, DoudizhuPlugin.AdminSetting.BUTTON_SCALE));
                inventory.setItem(14, adminSettingItem(Material.COMPASS, DoudizhuPlugin.AdminSetting.BUTTON_ROLL_DEGREES));
                inventory.setItem(19, adminSettingItem(Material.OAK_STAIRS, DoudizhuPlugin.AdminSetting.CHAIR_VISUAL_LATERAL));
                inventory.setItem(20, adminSettingItem(Material.OAK_STAIRS, DoudizhuPlugin.AdminSetting.CHAIR_VISUAL_VERTICAL));
                inventory.setItem(21, adminSettingItem(Material.OAK_SIGN, DoudizhuPlugin.AdminSetting.STATUS_HEIGHT));
                inventory.setItem(22, adminSettingItem(Material.WRITABLE_BOOK, DoudizhuPlugin.AdminSetting.PLAY_DETAIL_HEIGHT));
            }
            case CARDS -> {
                inventory.setItem(10, adminSettingItem(Material.PAPER, DoudizhuPlugin.AdminSetting.PRIVATE_CARD_SCALE));
                inventory.setItem(11, adminSettingItem(Material.MAP, DoudizhuPlugin.AdminSetting.PUBLIC_TRICK_CARD_SCALE));
                inventory.setItem(12, adminSettingItem(Material.PAPER, DoudizhuPlugin.AdminSetting.PRIVATE_CARD_WIDTH_SCALE));
                inventory.setItem(13, adminSettingItem(Material.PAPER, DoudizhuPlugin.AdminSetting.PRIVATE_CARD_HEIGHT_SCALE));
                inventory.setItem(14, adminSettingItem(Material.PAPER, DoudizhuPlugin.AdminSetting.PRIVATE_CARD_DEPTH_SCALE));
                inventory.setItem(15, adminSettingItem(Material.MAP, DoudizhuPlugin.AdminSetting.PUBLIC_CARD_WIDTH_SCALE));
                inventory.setItem(16, adminSettingItem(Material.MAP, DoudizhuPlugin.AdminSetting.PUBLIC_CARD_HEIGHT_SCALE));
                inventory.setItem(17, adminSettingItem(Material.MAP, DoudizhuPlugin.AdminSetting.PUBLIC_CARD_DEPTH_SCALE));
                inventory.setItem(19, adminSettingItem(Material.STRING, DoudizhuPlugin.AdminSetting.HAND_SPACING));
                inventory.setItem(20, adminSettingItem(Material.LEAD, DoudizhuPlugin.AdminSetting.PUBLIC_TRICK_SPACING));
                inventory.setItem(21, adminSettingItem(Material.SCAFFOLDING, DoudizhuPlugin.AdminSetting.PUBLIC_TRICK_HEIGHT));
                inventory.setItem(22, adminSettingItem(Material.LIGHT_BLUE_CANDLE, DoudizhuPlugin.AdminSetting.CARD_DEPTH_OFFSET));
                inventory.setItem(23, adminSettingItem(Material.SPYGLASS, DoudizhuPlugin.AdminSetting.HOVER_CARD_SCALE));
                inventory.setItem(24, adminSettingItem(Material.FEATHER, DoudizhuPlugin.AdminSetting.HOVER_CARD_LIFT));
                inventory.setItem(28, adminSettingItem(Material.RAIL, DoudizhuPlugin.AdminSetting.GLOBAL_HAND_LATERAL));
                inventory.setItem(29, adminSettingItem(Material.LADDER, DoudizhuPlugin.AdminSetting.GLOBAL_HAND_VERTICAL));
                inventory.setItem(30, adminSettingItem(Material.TARGET, DoudizhuPlugin.AdminSetting.GLOBAL_HAND_DEPTH));
                inventory.setItem(31, adminSettingItem(Material.NAME_TAG, DoudizhuPlugin.AdminSetting.LABELS_ENABLED));
                inventory.setItem(32, adminSettingItem(Material.CHAINMAIL_CHESTPLATE, DoudizhuPlugin.AdminSetting.DUPLICATE_ONLY));
            }
            case HITBOX -> {
                inventory.setItem(10, adminSettingItem(Material.BARRIER, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_LATERAL));
                inventory.setItem(11, adminSettingItem(Material.BARRIER, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_DEPTH));
                inventory.setItem(12, adminSettingItem(Material.BARRIER, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_VERTICAL));
                inventory.setItem(13, adminSettingItem(Material.SLIME_BALL, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_WIDTH));
                inventory.setItem(14, adminSettingItem(Material.SLIME_BALL, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_HEIGHT));
                inventory.setItem(19, adminSettingItem(Material.STRUCTURE_VOID, DoudizhuPlugin.AdminSetting.CARD_HITBOX_LATERAL));
                inventory.setItem(20, adminSettingItem(Material.STRUCTURE_VOID, DoudizhuPlugin.AdminSetting.CARD_HITBOX_DEPTH));
                inventory.setItem(21, adminSettingItem(Material.STRUCTURE_VOID, DoudizhuPlugin.AdminSetting.CARD_HITBOX_VERTICAL));
                inventory.setItem(22, adminSettingItem(Material.HONEYCOMB, DoudizhuPlugin.AdminSetting.CARD_HITBOX_WIDTH));
                inventory.setItem(23, adminSettingItem(Material.HONEYCOMB, DoudizhuPlugin.AdminSetting.CARD_HITBOX_HEIGHT));
                inventory.setItem(28, adminSettingItem(Material.MINECART, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_LATERAL));
                inventory.setItem(29, adminSettingItem(Material.MINECART, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_VERTICAL));
                inventory.setItem(30, adminSettingItem(Material.SADDLE, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_WIDTH));
                inventory.setItem(31, adminSettingItem(Material.SADDLE, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_HEIGHT));
            }
            case AUDIO -> {
                inventory.setItem(10, adminSettingItem(Material.MUSIC_DISC_CAT, DoudizhuPlugin.AdminSetting.BGM_VOLUME));
                inventory.setItem(11, adminSettingItem(Material.NOTE_BLOCK, DoudizhuPlugin.AdminSetting.EFFECT_VOLUME));
                inventory.setItem(12, adminSettingItem(Material.CLOCK, DoudizhuPlugin.AdminSetting.TURN_COUNTDOWN_SECONDS));
                inventory.setItem(13, item(Material.JUKEBOX, "倒计时音效", List.of("打开默认项，并支持自定义输入。", plugin.getCountdownSoundSpec())));
            }
            case PLAYER_OPTIONS -> {
                inventory.setItem(10, profileItem(Material.NOTE_BLOCK, "选牌音效方案 1", plugin.getSelectionSoundProfile(0)));
                inventory.setItem(11, profileItem(Material.NOTE_BLOCK, "选牌音效方案 2", plugin.getSelectionSoundProfile(1)));
                inventory.setItem(12, profileItem(Material.NOTE_BLOCK, "选牌音效方案 3", plugin.getSelectionSoundProfile(2)));
                inventory.setItem(13, profileItem(Material.NOTE_BLOCK, "选牌音效方案 4", plugin.getSelectionSoundProfile(3)));
                inventory.setItem(19, profileItem(Material.COMMAND_BLOCK, "出牌动作方案 1", plugin.getPlayActionProfile(0)));
                inventory.setItem(20, profileItem(Material.COMMAND_BLOCK, "出牌动作方案 2", plugin.getPlayActionProfile(1)));
                inventory.setItem(21, profileItem(Material.COMMAND_BLOCK, "出牌动作方案 3", plugin.getPlayActionProfile(2)));
                inventory.setItem(22, profileItem(Material.COMMAND_BLOCK, "出牌动作方案 4", plugin.getPlayActionProfile(3)));
                inventory.setItem(31, item(Material.OAK_SIGN, "编辑方式", List.of("左键编辑该方案。", "右键恢复默认。", "出牌动作使用单行 CE 语法。")));
            }
            case BOTS -> {
                inventory.setItem(10, adminSettingItem(Material.CLOCK, DoudizhuPlugin.AdminSetting.BOT_DELAY_MIN));
                inventory.setItem(11, adminSettingItem(Material.CLOCK, DoudizhuPlugin.AdminSetting.BOT_DELAY_MAX));
                inventory.setItem(12, adminSettingItem(Material.BOOKSHELF, DoudizhuPlugin.AdminSetting.HINT_GROUP_LIMIT));
                inventory.setItem(19, item(Material.COMMAND_BLOCK, "压测命令", List.of("/muz debug add [数量]", "创建自循环 bot 压测桌。", "bot 会使用这里配置的随机思考时间。")));
            }
        }
        player.openInventory(inventory);
    }

    public void openAdminSelectionSoundEditor(Player player) {
        openAdminModels(player, HandInventoryHolder.AdminPage.PLAYER_OPTIONS);
    }

    public void openAdminPlayActionEditor(Player player) {
        openAdminModels(player, HandInventoryHolder.AdminPage.PLAYER_OPTIONS);
    }

    public void openCountdownSoundPicker(Player player) {
        HandInventoryHolder holder = new HandInventoryHolder("", player.getUniqueId(), HandInventoryHolder.ViewMode.ADMIN_COUNTDOWN_SOUND_EDITOR, HandInventoryHolder.EditorTarget.ADMIN_COUNTDOWN, -1);
        Inventory inventory = Bukkit.createInventory(holder, COUNTDOWN_EDITOR_SIZE, "斗地主 | 倒计时音效");
        holder.setInventory(inventory);
        inventory.setItem(4, item(Material.CLOCK, "当前倒计时音效", List.of(plugin.getCountdownSoundSpec(), "格式: 音效名 [音量] [音高]")));
        List<SoundPreset> presets = countdownPresets();
        for (int index = 0; index < presets.size() && index < 7; index++) {
            int slot = 10 + index;
            inventory.setItem(slot, item(presets.get(index).material(), presets.get(index).title(), List.of(presets.get(index).description(), presets.get(index).spec())));
        }
        inventory.setItem(31, item(Material.OAK_SIGN, "自定义输入", List.of("点击后在聊天栏输入自定义音效。")));
        inventory.setItem(32, item(Material.ARROW, "返回音频页", List.of("返回管理员音频页面。")));
        inventory.setItem(33, item(Material.BARRIER, "关闭菜单", List.of("关闭当前菜单。")));
        player.openInventory(inventory);
    }

    public void refreshSettingsIfOpen(Player player) {
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof HandInventoryHolder holder)) {
            return;
        }
        if (!holder.viewerId().equals(player.getUniqueId())) {
            return;
        }
        switch (holder.viewMode()) {
            case SETTINGS -> openSettings(player);
            case SETTINGS_SELECTION_SOUND_PICKER -> openSelectionSoundPicker(player);
            case SETTINGS_PLAY_ACTION_PICKER -> openPlayActionPicker(player);
            case ADMIN_COUNTDOWN_SOUND_EDITOR -> openCountdownSoundPicker(player);
            case ADMIN_SELECTION_SOUND_EDITOR, ADMIN_PLAY_ACTION_EDITOR, ADMIN_MODELS -> openAdminModels(player, holder.adminPage() == null ? HandInventoryHolder.AdminPage.MODELS : holder.adminPage());
        }
    }

    public boolean hasPendingSoundInput(UUID playerId) {
        return pendingInputs.containsKey(playerId);
    }

    public void beginCustomInput(Player player, HandInventoryHolder.EditorTarget target, int profileIndex) {
        pendingInputs.put(player.getUniqueId(), new InputSession(target, profileIndex));
        player.closeInventory();
        switch (target) {
            case ADMIN_SELECTION_SOUND -> player.sendMessage(component("请输入 `显示名 || 音效名 [音量] [选中音高] [取消音高]`", NamedTextColor.AQUA));
            case ADMIN_PLAY_ACTION -> player.sendMessage(component("请输入 `显示名 || CE 单行动作语法`", NamedTextColor.AQUA));
            case ADMIN_COUNTDOWN -> player.sendMessage(component("请输入 `音效名 [音量] [音高]`", NamedTextColor.AQUA));
            default -> {
                pendingInputs.remove(player.getUniqueId());
                return;
            }
        }
        player.sendMessage(component("输入 cancel 或 取消 可以退出。", NamedTextColor.YELLOW));
    }

    public void handlePendingSoundInput(Player player, String rawInput) {
        InputSession session = pendingInputs.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        String trimmed = rawInput == null ? "" : rawInput.trim();
        if (trimmed.equalsIgnoreCase("cancel") || trimmed.equalsIgnoreCase("取消")) {
            pendingInputs.remove(player.getUniqueId());
            reopenAfterInput(player, session.target());
            return;
        }

        try {
            switch (session.target()) {
                case ADMIN_SELECTION_SOUND -> plugin.setSelectionSoundProfileDefinition(session.profileIndex(), parseProfileInput(trimmed, true));
                case ADMIN_PLAY_ACTION -> plugin.setPlayActionProfileDefinition(session.profileIndex(), parseProfileInput(trimmed, false));
                case ADMIN_COUNTDOWN -> plugin.setCountdownSoundSpec(trimmed);
                default -> {
                }
            }
            pendingInputs.remove(player.getUniqueId());
            reopenAfterInput(player, session.target());
        } catch (IllegalArgumentException exception) {
            player.sendMessage(component(exception.getMessage(), NamedTextColor.RED));
            player.sendMessage(component("请重新输入，或输入 cancel 取消。", NamedTextColor.YELLOW));
        }
    }

    public String soundPresetSpec(HandInventoryHolder.EditorTarget target, int rawSlot) {
        if (target != HandInventoryHolder.EditorTarget.ADMIN_COUNTDOWN) {
            return null;
        }
        int index = rawSlot - 10;
        List<SoundPreset> presets = countdownPresets();
        return index >= 0 && index < presets.size() ? presets.get(index).spec() : null;
    }

    public void closeHands(GameTable table) {
        for (UUID seat : table.getSeats()) {
            Player player = Bukkit.getPlayer(seat);
            if (player != null && player.getOpenInventory().getTopInventory().getHolder() instanceof HandInventoryHolder holder
                && holder.tableName().equalsIgnoreCase(table.getName())) {
                player.closeInventory();
            }
        }
    }

    private void reopenAfterInput(Player player, HandInventoryHolder.EditorTarget target) {
        switch (target) {
            case ADMIN_SELECTION_SOUND, ADMIN_PLAY_ACTION -> openAdminModels(player, HandInventoryHolder.AdminPage.PLAYER_OPTIONS);
            case ADMIN_COUNTDOWN -> openCountdownSoundPicker(player);
            default -> openSettings(player);
        }
    }

    private DoudizhuPlugin.OptionProfile parseProfileInput(String rawInput, boolean soundProfile) {
        String[] parts = rawInput.split("\\|\\|", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("请输入 `显示名 || 配置内容`。");
        }
        String label = parts[0].trim();
        String spec = parts[1].trim();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("显示名不能为空。");
        }
        return new DoudizhuPlugin.OptionProfile(label, soundProfile ? plugin.normalizeSelectionSoundSpec(spec) : plugin.normalizePlayActionSpec(spec));
    }

    private List<SoundPreset> countdownPresets() {
        return List.of(
            new SoundPreset(Material.CLOCK, "默认倒计时", "minecraft:block.note_block.hat 0.45 1.00", "轻量且清晰。"),
            new SoundPreset(Material.BELL, "钟声提醒", "minecraft:block.note_block.bell 0.30 1.08", "更像提醒铃。"),
            new SoundPreset(Material.ANVIL, "铁砧提醒", "minecraft:block.anvil.land 0.18 1.10", "更有压迫感。"),
            new SoundPreset(Material.OAK_SIGN, "木牌提醒", "minecraft:block.hanging_sign.place 0.35 1.00", "木质轻提示。"),
            new SoundPreset(Material.SCULK, "洞穴提醒", "minecraft:ambient.cave 0.20 1.00", "偏氛围。"),
            new SoundPreset(Material.STONE_BUTTON, "按钮提醒", "minecraft:ui.button.click 0.28 0.95", "短促清晰。"),
            new SoundPreset(Material.IRON_BARS, "静音", "minecraft:block.note_block.hat 0.00 1.00", "保留倒计时文字但静音。")
        );
    }

    private ItemStack adminSettingItem(Material material, DoudizhuPlugin.AdminSetting setting) {
        return item(material, setting.label() + ": " + plugin.adminSettingValue(setting), List.of("左键增加，右键减少。", "Shift 点击按 10 倍步长。", "路径: " + setting.path()));
    }

    private ItemStack toggleItem(Material material, String title, boolean enabled) {
        return item(material, title + ": " + bool(enabled), List.of("点击切换。"));
    }

    private ItemStack profileItem(Material material, String title, DoudizhuPlugin.OptionProfile profile) {
        return item(material, title + " | " + profile.label(), List.of(profile.spec(), "左键编辑，右键恢复默认。"));
    }

    private ItemStack item(Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(component(title, NamedTextColor.AQUA));
        meta.lore(lore.stream().map(line -> component(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private Component component(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private String pageTitle(HandInventoryHolder.AdminPage page) {
        return switch (page) {
            case MODELS -> "模型";
            case TABLE, SEAT -> "桌面";
            case CARDS -> "牌面";
            case HITBOX -> "碰撞";
            case AUDIO -> "音频";
            case PLAYER_OPTIONS -> "玩家选项";
            case BOTS -> "机器人";
        };
    }

    private String bool(boolean value) {
        return value ? "开启" : "关闭";
    }

    private record SoundPreset(Material material, String title, String spec, String description) {
    }

    private record InputSession(HandInventoryHolder.EditorTarget target, int profileIndex) {
    }
}
