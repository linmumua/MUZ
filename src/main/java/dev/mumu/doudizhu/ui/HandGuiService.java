package dev.mumu.doudizhu.ui;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.game.GameTable;
import dev.mumu.doudizhu.storage.MatchParticipantRecord;
import dev.mumu.doudizhu.storage.PlayerHistoryEntry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
    private static final int HISTORY_SIZE = 54;
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final DoudizhuPlugin plugin;
    private final Map<UUID, InputSession> pendingInputs = new ConcurrentHashMap<>();
    private final Map<UUID, SignInputSession> pendingSignInputs = new ConcurrentHashMap<>();

    public HandGuiService(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public void openSettings(Player player) {
        openSettings(player, plugin.getTableManager().getTableOf(player));
    }

    public void openSettings(Player player, GameTable table) {
        HandInventoryHolder holder = new HandInventoryHolder(table == null ? "" : table.getName(), player.getUniqueId(), HandInventoryHolder.ViewMode.SETTINGS);
        Inventory inventory = Bukkit.createInventory(holder, SETTINGS_SIZE, "MUMU | 个人设置");
        holder.setInventory(inventory);
        inventory.setItem(4, noteItem(Material.BOOK, "个人设置总览", List.of(
            "点数标签 " + bool(plugin.isCardLabelsEnabledFor(player.getUniqueId())) + " · 出牌对比 " + bool(plugin.isOpponentPreviewEnabledFor(player.getUniqueId())) + "。",
            "选牌音效 · " + selectionSoundDisplayLabel(plugin.getSelectionSoundProfile(plugin.getPlayerSelectionSoundProfileIndex(player.getUniqueId()))) + "。",
            "预览色 · " + plugin.previewGlowColorLabel(player.getUniqueId()) + " | 选中色 · " + plugin.selectionGlowColorLabel(player.getUniqueId()) + "。"
        )));
        inventory.setItem(10, toggleItem(Material.NAME_TAG, "点数标签", plugin.isCardLabelsEnabledFor(player.getUniqueId()), "牌面额外显示数字提示。"));
        inventory.setItem(12, toggleItem(Material.SPYGLASS, "出牌对比", plugin.isOpponentPreviewEnabledFor(player.getUniqueId()), "选牌时顺带看上一手。"));
        inventory.setItem(14, item(Material.NOTE_BLOCK, "选牌音效 · " + selectionSoundDisplayLabel(plugin.getSelectionSoundProfile(plugin.getPlayerSelectionSoundProfileIndex(player.getUniqueId()))), List.of("点开切换方案。")));
        inventory.setItem(16, item(Material.COMMAND_BLOCK, "出牌动作", List.of("按牌型切换动作。")));
        inventory.setItem(19, colorSettingItem(Material.OAK_SIGN, "预览色", playerPreviewColorDisplayLabel(player.getUniqueId()), plugin.previewGlowColorFor(player.getUniqueId()), List.of("鼠标指向牌或按钮时使用。")));
        inventory.setItem(21, colorSettingItem(Material.OAK_SIGN, "选中色", playerSelectionColorDisplayLabel(player.getUniqueId()), plugin.selectionGlowColorFor(player.getUniqueId()), List.of("已经预选的牌会使用这个颜色。")));
        inventory.setItem(23, noteItem(Material.BOOK, "恢复默认", List.of("清空个人偏好。")));
        inventory.setItem(25, closeItem());
        player.openInventory(inventory);
    }

    public void openSelectionSoundPicker(Player player) {
        openPlayerProfilePicker(player, HandInventoryHolder.EditorTarget.PLAYER_SELECTION, "斗地主 | 选牌音效方案");
    }

    public void openPlayActionPicker(Player player) {
        openPlayActionKindMenu(player);
    }

    public void openPlayActionKindMenu(Player player) {
        GameTable table = plugin.getTableManager().getTableOf(player);
        HandInventoryHolder holder = new HandInventoryHolder(table == null ? "" : table.getName(), player.getUniqueId(), HandInventoryHolder.ViewMode.SETTINGS_ACTION_KIND_MENU);
        Inventory inventory = Bukkit.createInventory(holder, PICKER_SIZE, "斗地主 | 动作设置");
        holder.setInventory(inventory);
        inventory.setItem(4, noteItem(Material.COMMAND_BLOCK, "按牌型设置动作", List.of(
            "先选牌型，再选方案。",
            "每种牌型都单独保存。"
        )));
        placePlayerActionKindItems(inventory, player.getUniqueId());
        inventory.setItem(22, backItem("个人设置"));
        inventory.setItem(24, closeItem());
        player.openInventory(inventory);
    }

    public void openPlayActionPicker(Player player, DoudizhuPlugin.PlayActionKind kind) {
        GameTable table = plugin.getTableManager().getTableOf(player);
        HandInventoryHolder holder = new HandInventoryHolder(
            table == null ? "" : table.getName(),
            player.getUniqueId(),
            HandInventoryHolder.ViewMode.SETTINGS_PLAY_ACTION_PICKER,
            HandInventoryHolder.EditorTarget.PLAYER_PLAY_ACTION,
            kind,
            -1
        );
        Inventory inventory = Bukkit.createInventory(holder, PICKER_SIZE, "斗地主 | 动作 | " + kind.label());
        holder.setInventory(inventory);
        List<DoudizhuPlugin.OptionProfile> profiles = plugin.getPlayActionProfiles(kind);
        int selected = plugin.getPlayerPlayActionProfileIndex(player.getUniqueId(), kind);
        inventory.setItem(4, noteItem(actionKindMaterial(kind), kind.label() + " · 动作方案", List.of(
            "当前方案 · " + profiles.get(selected).label() + "。",
            "左键预览，右键启用。"
        )));
        for (int index = 0; index < profiles.size() && index < 4; index++) {
            int slot = fourChoiceSlot(index);
            inventory.setItem(slot, item(
                Material.COMMAND_BLOCK,
                (index == selected ? "当前方案 · " : "候选方案 · ") + profiles.get(index).label(),
                List.of(index == selected ? "当前正在使用。" : "左键预览，右键启用。")
            ));
        }
        inventory.setItem(22, backItem("动作设置"));
        inventory.setItem(24, closeItem());
        player.openInventory(inventory);
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
        inventory.setItem(4, noteItem(Material.BOOK, "当前方案", List.of(
            "正在使用 " + displayProfileLabel(target, profiles.get(selected)) + "。",
            target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION ? "左键试听并切换，右键只切换。" : "左键试听，右键切换。"
        )));
        for (int index = 0; index < profiles.size() && index < 4; index++) {
            int slot = fourChoiceSlot(index);
            inventory.setItem(slot, item(
                target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION ? Material.NOTE_BLOCK : Material.COMMAND_BLOCK,
                (index == selected ? "当前方案 · " : "候选方案 · ") + displayProfileLabel(target, profiles.get(index)),
                List.of(index == selected ? "当前正在使用。" : target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION ? "左键试听并切换，右键只切换。" : "左键试听，右键切换。")
            ));
        }
        inventory.setItem(22, backItem("个人设置"));
        inventory.setItem(24, closeItem());
        player.openInventory(inventory);
    }

    public void openAdminModels(Player player) {
        openAdminModels(player, HandInventoryHolder.AdminPage.HOME);
    }

    public void openHistory(Player viewer, UUID targetPlayerId, String targetName, int page) {
        int normalizedPage = Math.max(1, page);
        HistoryInventoryHolder holder = new HistoryInventoryHolder(viewer.getUniqueId(), targetPlayerId, targetName, normalizedPage, HistoryInventoryHolder.Mode.LIST, -1L);
        Inventory inventory = Bukkit.createInventory(holder, HISTORY_SIZE, "MUMU | 历史战绩 | " + normalizeHistoryName(targetName));
        holder.setInventory(inventory);
        inventory.setItem(4, noteItem(Material.BOOK, "战绩总览", List.of(
            "查看 " + normalizeHistoryName(targetName) + " 的历史对局。",
            "当前第 " + normalizedPage + " 页，点下方箭头翻页。"
        )));
        List<PlayerHistoryEntry> entries = plugin.loadPlayerHistory(targetPlayerId, 36, (normalizedPage - 1) * 36);
        if (entries.isEmpty()) {
            inventory.setItem(22, richItem(Material.BARRIER, MuzTheme.danger("暂无战绩"), List.of(MuzTheme.muted("这里暂时还没有可展示的历史记录。"))));
        } else {
            int slot = 9;
            for (PlayerHistoryEntry entry : entries) {
                if (slot >= 45) {
                    break;
                }
                inventory.setItem(slot, historyItem(entry));
                slot++;
            }
        }
        inventory.setItem(45, noteItem(Material.ARROW, "上一页", List.of("查看更早的记录。")));
        inventory.setItem(49, noteItem(Material.CLOCK, "第 " + normalizedPage + " 页", List.of("点击中间按钮可刷新当前页。")));
        inventory.setItem(53, noteItem(Material.SPECTRAL_ARROW, "下一页", List.of("继续查看后续记录。")));
        viewer.openInventory(inventory);
    }

    public void openHistoryDetail(Player viewer, UUID targetPlayerId, String targetName, int page, long matchId) {
        List<PlayerHistoryEntry> entries = plugin.loadPlayerHistory(targetPlayerId, 100, 0);
        PlayerHistoryEntry selected = entries.stream().filter(entry -> entry.matchId() == matchId).findFirst().orElse(null);
        if (selected == null) {
            openHistory(viewer, targetPlayerId, targetName, page);
            return;
        }
        HistoryInventoryHolder holder = new HistoryInventoryHolder(viewer.getUniqueId(), targetPlayerId, targetName, page, HistoryInventoryHolder.Mode.DETAIL, matchId);
        Inventory inventory = Bukkit.createInventory(holder, HISTORY_SIZE, "MUMU | 战绩详情 | " + normalizeHistoryName(targetName));
        holder.setInventory(inventory);
        inventory.setItem(4, richItem(Material.WRITABLE_BOOK, historyTitle(selected), List.of(
            component("玩家 " + normalizeHistoryName(targetName), NamedTextColor.WHITE),
            component("对局编号 " + matchId, NamedTextColor.GRAY)
        )));
        inventory.setItem(19, historyOverviewCard(selected));
        inventory.setItem(20, historySelfCard(selected));
        inventory.setItem(21, historyRoleCard(selected));
        inventory.setItem(22, historySettlementCard(selected));
        inventory.setItem(23, historyTimeCard(selected));
        inventory.setItem(24, historyLocationCard(selected));
        int participantSlot = 28;
        for (MatchParticipantRecord participant : selected.participants()) {
            if (participantSlot > 34) {
                break;
            }
            inventory.setItem(participantSlot, historyParticipantCard(participant));
            participantSlot++;
        }
        inventory.setItem(45, noteItem(Material.ARROW, "返回列表", List.of("回到历史战绩列表。")));
        viewer.openInventory(inventory);
    }

    public void openAdminModels(Player player, HandInventoryHolder.AdminPage page) {
        // 管理菜单改成“首页 -> 斗地主/德州/通用 -> 具体分类页”的三级结构。
        HandInventoryHolder holder = new HandInventoryHolder("", player.getUniqueId(), HandInventoryHolder.ViewMode.ADMIN_MODELS, page);
        Inventory inventory = Bukkit.createInventory(holder, ADMIN_SIZE, "MUMU | 管理菜单 | " + pageTitle(page));
        holder.setInventory(inventory);
        inventory.setItem(4, noteItem(Material.WRITABLE_BOOK, pageTitle(page) + " · 管理台", adminPageSummary(page)));
        HandInventoryHolder.AdminPage parent = parentPage(page);
        if (parent != null) {
            inventory.setItem(44, backItem(pageTitle(parent)));
        }
        inventory.setItem(49, noteItem(Material.CLOCK, "管理首页", List.of("回到系统总览入口。")));
        inventory.setItem(52, richItem(Material.COMPASS, MuzTheme.warning("重新载入"), List.of(MuzTheme.muted("重读配置并刷新已放置牌桌。"))));
        inventory.setItem(53, closeItem());

        switch (page) {
            case HOME -> {
                inventory.setItem(4, noteItem(Material.WRITABLE_BOOK, "系统概览", List.of(
                    "斗地主 " + plugin.getTableManager().getTables().size() + " 张 · 德州 " + plugin.getZjhManager().getTables().size() + " 张。",
                    "支付模式 · " + (plugin.isChipPaymentEnabled() ? "筹码" : "金币") + "。"
                )));
                inventory.setItem(19, item(Material.PAPER, "斗地主牌桌", List.of("桌椅、按钮、卡牌、文字。")));
                inventory.setItem(21, item(Material.OAK_BOAT, "德州牌桌", List.of("座位、按钮、卡牌、文字。")));
                inventory.setItem(23, item(Material.BOOK, "通用视觉", List.of("动画、高亮、头像。")));
                inventory.setItem(25, item(Material.GOLD_INGOT, "经济与场次", List.of("支付、筹码、场次、数据库。")));
            }
            case DDZ_HOME -> {
                inventory.setItem(19, item(Material.CARTOGRAPHY_TABLE, "桌椅摆位", List.of("桌子、椅子、整桌落点。")));
                inventory.setItem(20, item(Material.STONE_BUTTON, "按钮布局", List.of("距离、弧度、悬停。")));
                inventory.setItem(21, item(Material.PAPER, "卡牌表现", List.of("手牌、预览牌、排布。")));
                inventory.setItem(22, item(Material.OAK_SIGN, "文字标签", List.of("桌面状态、提示、牌面标签。")));
                inventory.setItem(23, item(Material.NAME_TAG, "牌面标签", List.of("点数标签与重复牌显示。")));
                inventory.setItem(28, item(Material.STRUCTURE_VOID, "碰撞交互", List.of("手牌、按钮、椅子点击范围。")));
                inventory.setItem(29, item(Material.NOTE_BLOCK, "音频", List.of("背景音乐、提示音、倒计时。")));
                inventory.setItem(30, item(Material.BOOK, "玩家选项", List.of("音效方案、动作方案。")));
                inventory.setItem(31, item(Material.CLOCK, "机器人", List.of("思考时长、压测入口。")));
            }
            case TEXAS_HOME -> {
                inventory.setItem(20, item(Material.CARTOGRAPHY_TABLE, "桌椅摆位", List.of("座位圈、桌面、按钮位。")));
                inventory.setItem(22, item(Material.STONE_BUTTON, "按钮布局", List.of("加入按钮、操作按钮。")));
                inventory.setItem(29, item(Material.PAPER, "卡牌表现", List.of("公共牌、手牌、半径。")));
                inventory.setItem(31, item(Material.OAK_SIGN, "文字标签", List.of("座位信息、桌面状态。")));
            }
            case GLOBAL_HOME -> {
                inventory.setItem(20, item(Material.COMPARATOR, "动画节奏", List.of("牌和按钮的动画。")));
                inventory.setItem(22, item(Material.SPECTRAL_ARROW, "高亮颜色", List.of("预览色、选中色。")));
                inventory.setItem(24, item(Material.PLAYER_HEAD, "头像组件", List.of("顶栏头像、座位头像。")));
            }
            case GLOBAL_ECONOMY -> {
                inventory.setItem(10, item(Material.GOLD_INGOT, "支付模式 · " + plugin.paymentModeLabel(), List.of("左键直接切换金币或筹码支付。", "金币模式依赖 Vault，筹码模式使用全局余额。")));
                inventory.setItem(12, item(Material.GRAVEL, "主手设为全局筹码", List.of("把你主手物品设置成筹码外观。")));
                inventory.setItem(14, item(Material.BRUSH, "恢复默认筹码", List.of("改回默认的石子筹码外观。")));
                inventory.setItem(19, roomLevelItem(Material.COPPER_INGOT, dev.mumu.doudizhu.room.TableLevel.LOW));
                inventory.setItem(20, roomLevelItem(Material.IRON_INGOT, dev.mumu.doudizhu.room.TableLevel.MID));
                inventory.setItem(21, roomLevelItem(Material.GOLD_INGOT, dev.mumu.doudizhu.room.TableLevel.HIGH));
                inventory.setItem(22, roomLevelItem(Material.EMERALD, dev.mumu.doudizhu.room.TableLevel.FUN));
                inventory.setItem(28, roomLevelToggleItem(Material.LEVER, dev.mumu.doudizhu.room.TableLevel.LOW));
                inventory.setItem(29, roomLevelToggleItem(Material.LEVER, dev.mumu.doudizhu.room.TableLevel.MID));
                inventory.setItem(30, roomLevelToggleItem(Material.LEVER, dev.mumu.doudizhu.room.TableLevel.HIGH));
                inventory.setItem(31, roomLevelToggleItem(Material.LEVER, dev.mumu.doudizhu.room.TableLevel.FUN));
                inventory.setItem(33, item(Material.CHEST, "当前筹码物品", List.of(describeChipItem(plugin.chipPaymentItem()))));
                inventory.setItem(39, item(Material.BEACON, "当前 Provider", List.of(plugin.vaultProviderSummary())));
                inventory.setItem(40, noteItem(Material.PAPER, "常用命令", List.of(
                    "/muz set <牌桌id> <high|mid|low|fun>",
                    "/muz chip mode <gold|chip>",
                    "/muz chip balance <玩家> [数量]"
                )));
                inventory.setItem(41, item(Material.WRITABLE_BOOK, "已注册 Provider", List.of(plugin.vaultProvidersSummary(), "偏好顺序 · " + plugin.vaultPreferredProvidersSummary())));
                inventory.setItem(42, item(Material.NAME_TAG, "筹码占位符", List.of("%muz_chip_<玩家>%", "可用于记分板、菜单或 HUD。")));
                inventory.setItem(43, item(Material.ENDER_CHEST, "数据库状态", List.of(plugin.databaseStatusSummary(), "牌桌与战绩都会写入这里。")));
                int slot = 45;
                for (Player online : onlinePlayersForEconomyPage()) {
                    if (slot > 51) {
                        break;
                    }
                    inventory.setItem(slot, onlineChipItem(online));
                    slot++;
                }
            }
            case DDZ_FURNITURE -> {
                inventory.setItem(10, item(Material.CARTOGRAPHY_TABLE, "主手设为桌子", List.of("把你主手的物品拿来当桌子外观。")));
                inventory.setItem(12, item(Material.BRUSH, "桌子恢复默认", List.of("把桌子外观改回默认。")));
                inventory.setItem(14, item(Material.OAK_STAIRS, "主手设为椅子", List.of("支持 CraftEngine 家具、CraftEngine 方块物品和原版方块。")));
                inventory.setItem(16, item(Material.BRUSH, "椅子恢复默认", List.of("把椅子外观改回默认。")));
                inventory.setItem(19, adminSettingItem(Material.LODESTONE, DoudizhuPlugin.AdminSetting.TABLE_SPAWN_OFFSET_Y));
                inventory.setItem(21, adminSettingItem(Material.OAK_STAIRS, DoudizhuPlugin.AdminSetting.CHAIR_VISUAL_LATERAL));
                inventory.setItem(23, adminSettingItem(Material.OAK_STAIRS, DoudizhuPlugin.AdminSetting.CHAIR_VISUAL_VERTICAL));
                inventory.setItem(25, adminSettingItem(Material.COMPASS, DoudizhuPlugin.AdminSetting.CHAIR_ROTATION_DEGREES));
                inventory.setItem(27, adminSettingItem(Material.MANGROVE_BOAT, DoudizhuPlugin.AdminSetting.CHAIR_DISTANCE));
                inventory.setItem(29, item(Material.BELL, "放置阻挡警告音", List.of("放桌位置被方块挡住时播放。", plugin.getPlacementBlockedSoundSpec())));
            }
            case DDZ_BUTTONS -> {
                inventory.setItem(10, adminSettingItem(Material.STONE_BUTTON, DoudizhuPlugin.AdminSetting.BUTTON_DISTANCE));
                inventory.setItem(11, adminSettingItem(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, DoudizhuPlugin.AdminSetting.BUTTON_HEIGHT));
                inventory.setItem(12, adminSettingItem(Material.SMALL_AMETHYST_BUD, DoudizhuPlugin.AdminSetting.BUTTON_SCALE));
                inventory.setItem(13, adminSettingItem(Material.COMPASS, DoudizhuPlugin.AdminSetting.BUTTON_ROLL_DEGREES));
                inventory.setItem(14, adminSettingItem(Material.STONE_BUTTON, DoudizhuPlugin.AdminSetting.HOVER_BUTTON_SCALE));
                inventory.setItem(15, adminSettingItem(Material.RABBIT_FOOT, DoudizhuPlugin.AdminSetting.HOVER_BUTTON_LIFT));
                inventory.setItem(19, adminSettingItem(Material.IRON_NUGGET, DoudizhuPlugin.AdminSetting.BUTTON_FRONT_BASE_DISTANCE));
                inventory.setItem(20, adminSettingItem(Material.IRON_INGOT, DoudizhuPlugin.AdminSetting.BUTTON_SIDE_BASE_DISTANCE));
                inventory.setItem(21, adminSettingItem(Material.REPEATER, DoudizhuPlugin.AdminSetting.BUTTON_DISTANCE_FACTOR));
                inventory.setItem(22, adminSettingItem(Material.STRING, DoudizhuPlugin.AdminSetting.BUTTON_SPACING));
                inventory.setItem(23, adminSettingItem(Material.COMPASS, DoudizhuPlugin.AdminSetting.BUTTON_ARC_SMALL_ANGLE));
                inventory.setItem(24, adminSettingItem(Material.CLOCK, DoudizhuPlugin.AdminSetting.BUTTON_ARC_LARGE_ANGLE));
                inventory.setItem(25, adminSettingItem(Material.SLIME_BALL, DoudizhuPlugin.AdminSetting.BUTTON_ARC_SMALL_RADIUS));
                inventory.setItem(26, adminSettingItem(Material.MAGMA_CREAM, DoudizhuPlugin.AdminSetting.BUTTON_ARC_LARGE_RADIUS));
            }
            case DDZ_CARDS -> {
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
                inventory.setItem(31, adminSettingItem(Material.WARPED_FUNGUS_ON_A_STICK, DoudizhuPlugin.AdminSetting.PUBLIC_PREVIEW_ROW_DEPTH_SPACING));
            }
            case GLOBAL_ANIMATION -> {
                inventory.setItem(11, adminSettingItem(Material.COMPARATOR, DoudizhuPlugin.AdminSetting.HOVER_CARD_INTERPOLATION_TICKS));
                inventory.setItem(13, adminSettingItem(Material.CLOCK, DoudizhuPlugin.AdminSetting.HOVER_CARD_ANIMATION_TYPE));
                inventory.setItem(15, adminSettingItem(Material.COMPARATOR, DoudizhuPlugin.AdminSetting.HOVER_BUTTON_INTERPOLATION_TICKS));
                inventory.setItem(17, adminSettingItem(Material.CLOCK, DoudizhuPlugin.AdminSetting.HOVER_BUTTON_ANIMATION_TYPE));
                inventory.setItem(22, noteItem(Material.BOOK, "调节提示", List.of(
                    "可切换线性、缓出、缓入缓出、回弹。",
                    "时长越大越柔和。"
                )));
            }
            case GLOBAL_HIGHLIGHT -> {
                inventory.setItem(10, adminSettingItem(Material.SPECTRAL_ARROW, DoudizhuPlugin.AdminSetting.HOVER_GLOW_ENABLED));
                inventory.setItem(12, colorSettingItem(Material.OAK_SIGN, "全局预览色", plugin.hoverGlowColorLabel(), plugin.hoverGlowColor(), List.of("点击后输入 r,g,b 或十六进制。", "这是所有玩家未自定义时的默认预览色。")));
                inventory.setItem(28, adminSettingItem(Material.SPECTRAL_ARROW, DoudizhuPlugin.AdminSetting.SELECTED_GLOW_ENABLED));
                inventory.setItem(30, colorSettingItem(Material.OAK_SIGN, "全局选中色", plugin.selectedGlowColorLabel(), plugin.selectedGlowColor(), List.of("点击后输入 r,g,b 或十六进制。", "这是所有玩家未自定义时的默认选中色。")));
                inventory.setItem(22, noteItem(Material.WRITABLE_BOOK, "颜色说明", List.of(
                    "上半区控制预览发光，下半区控制已选发光。",
                    "颜色支持 r,g,b，也支持十六进制。",
                    "选中的牌被鼠标指向时只会切到预览色，不再额外抬升。"
                )));
            }
            case GLOBAL_AVATARS -> {
                inventory.setItem(20, item(Material.ENDER_EYE, "顶栏头像", List.of("顶部状态头像与名字。")));
                inventory.setItem(24, item(Material.PLAYER_HEAD, "座位头像", List.of("椅子外侧头像与名字。")));
                inventory.setItem(31, noteItem(Material.BOOK, "布局说明", List.of(
                    "头像和名字已经拆开。",
                    "名字固定在头像下方。"
                )));
            }
            case GLOBAL_STATUS_AVATARS -> {
                inventory.setItem(10, adminSettingItem(Material.PLAYER_HEAD, DoudizhuPlugin.AdminSetting.STATUS_AVATAR_SCALE));
                inventory.setItem(12, adminSettingItem(Material.COMPASS, DoudizhuPlugin.AdminSetting.STATUS_AVATAR_LATERAL));
                inventory.setItem(14, adminSettingItem(Material.SCAFFOLDING, DoudizhuPlugin.AdminSetting.STATUS_AVATAR_VERTICAL));
                inventory.setItem(16, adminSettingItem(Material.TARGET, DoudizhuPlugin.AdminSetting.STATUS_AVATAR_DEPTH));
                inventory.setItem(31, noteItem(Material.BOOK, "顶栏头像", List.of(
                    "头像和名字分开生成。",
                    "名字固定显示在头像下方。"
                )));
            }
            case GLOBAL_SEAT_AVATARS -> {
                inventory.setItem(10, adminSettingItem(Material.PLAYER_HEAD, DoudizhuPlugin.AdminSetting.SEAT_AVATAR_SCALE));
                inventory.setItem(12, adminSettingItem(Material.COMPASS, DoudizhuPlugin.AdminSetting.SEAT_AVATAR_LATERAL));
                inventory.setItem(14, adminSettingItem(Material.SCAFFOLDING, DoudizhuPlugin.AdminSetting.SEAT_AVATAR_VERTICAL));
                inventory.setItem(16, adminSettingItem(Material.TARGET, DoudizhuPlugin.AdminSetting.SEAT_AVATAR_DEPTH));
                inventory.setItem(31, noteItem(Material.BOOK, "座位头像", List.of(
                    "适用于斗地主与德州的座位头像。",
                    "名字固定显示在头像下方。"
                )));
            }
            case DDZ_TEXT -> {
                inventory.setItem(10, adminSettingItem(Material.OAK_SIGN, DoudizhuPlugin.AdminSetting.STATUS_HEIGHT));
                inventory.setItem(11, adminSettingItem(Material.WRITABLE_BOOK, DoudizhuPlugin.AdminSetting.PLAY_DETAIL_HEIGHT));
                inventory.setItem(12, adminSettingItem(Material.NAME_TAG, DoudizhuPlugin.AdminSetting.JOIN_LABEL_HEIGHT));
                inventory.setItem(13, adminSettingItem(Material.OAK_SIGN, DoudizhuPlugin.AdminSetting.ACTION_LABEL_HEIGHT));
            }
            case DDZ_LABELS -> {
                inventory.setItem(10, adminSettingItem(Material.OAK_SIGN, DoudizhuPlugin.AdminSetting.CARD_LABEL_HEIGHT));
                inventory.setItem(12, adminSettingItem(Material.COMPASS, DoudizhuPlugin.AdminSetting.CARD_LABEL_LATERAL));
                inventory.setItem(14, adminSettingItem(Material.TARGET, DoudizhuPlugin.AdminSetting.CARD_LABEL_DEPTH));
                inventory.setItem(28, adminSettingItem(Material.NAME_TAG, DoudizhuPlugin.AdminSetting.LABELS_ENABLED));
                inventory.setItem(30, adminSettingItem(Material.CHAINMAIL_CHESTPLATE, DoudizhuPlugin.AdminSetting.DUPLICATE_ONLY));
                inventory.setItem(22, noteItem(Material.BOOK, "牌面标签", List.of(
                    "这里单独调牌上的数字标签。",
                    "高度、左右、前后都在这里。"
                )));
            }
            case DDZ_HITBOX -> {
                inventory.setItem(10, adminSettingItem(Material.BARRIER, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_LATERAL));
                inventory.setItem(11, adminSettingItem(Material.BARRIER, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_DEPTH));
                inventory.setItem(12, adminSettingItem(Material.BARRIER, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_VERTICAL));
                inventory.setItem(13, adminSettingItem(Material.SLIME_BALL, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_WIDTH));
                inventory.setItem(14, adminSettingItem(Material.SLIME_BALL, DoudizhuPlugin.AdminSetting.BUTTON_HITBOX_HEIGHT));
                inventory.setItem(19, adminSettingItem(Material.STRUCTURE_VOID, DoudizhuPlugin.AdminSetting.CARD_HITBOX_LATERAL));
                inventory.setItem(20, adminSettingItem(Material.STRUCTURE_VOID, DoudizhuPlugin.AdminSetting.CARD_HITBOX_DEPTH));
                inventory.setItem(21, adminSettingItem(Material.STRUCTURE_VOID, DoudizhuPlugin.AdminSetting.CARD_HITBOX_VERTICAL));
                inventory.setItem(22, adminSettingItem(Material.HONEYCOMB_BLOCK, DoudizhuPlugin.AdminSetting.CARD_HITBOX_LENGTH));
                inventory.setItem(23, adminSettingItem(Material.HONEYCOMB, DoudizhuPlugin.AdminSetting.CARD_HITBOX_WIDTH));
                inventory.setItem(24, adminSettingItem(Material.HONEY_BLOCK, DoudizhuPlugin.AdminSetting.CARD_HITBOX_HEIGHT));
                inventory.setItem(28, adminSettingItem(Material.MINECART, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_LATERAL));
                inventory.setItem(29, adminSettingItem(Material.MINECART, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_VERTICAL));
                inventory.setItem(30, adminSettingItem(Material.SADDLE, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_WIDTH));
                inventory.setItem(31, adminSettingItem(Material.SADDLE, DoudizhuPlugin.AdminSetting.CHAIR_HITBOX_HEIGHT));
            }
            case DDZ_AUDIO -> {
                inventory.setItem(10, adminSettingItem(Material.MUSIC_DISC_CAT, DoudizhuPlugin.AdminSetting.BGM_VOLUME));
                inventory.setItem(11, adminSettingItem(Material.NOTE_BLOCK, DoudizhuPlugin.AdminSetting.EFFECT_VOLUME));
                inventory.setItem(12, adminSettingItem(Material.CLOCK, DoudizhuPlugin.AdminSetting.TURN_COUNTDOWN_SECONDS));
                inventory.setItem(13, item(Material.JUKEBOX, "倒计时音效", List.of("进入后可选预设，也支持手输 spec。", plugin.getCountdownSoundSpec())));
                inventory.setItem(14, item(Material.BELL, "未准备提醒音", List.of("大厅阶段提醒未准备玩家。", plugin.getUnreadyWarningSoundSpec())));
            }
            case DDZ_PLAYER_OPTIONS -> {
                inventory.setItem(10, profileItem(Material.NOTE_BLOCK, "选牌音效方案 1", plugin.getSelectionSoundProfile(0)));
                inventory.setItem(11, profileItem(Material.NOTE_BLOCK, "选牌音效方案 2", plugin.getSelectionSoundProfile(1)));
                inventory.setItem(12, profileItem(Material.NOTE_BLOCK, "选牌音效方案 3", plugin.getSelectionSoundProfile(2)));
                inventory.setItem(13, profileItem(Material.NOTE_BLOCK, "选牌音效方案 4", plugin.getSelectionSoundProfile(3)));
                placeAdminActionKindItems(inventory);
                inventory.setItem(22, noteItem(Material.OAK_SIGN, "动作分类", List.of(
                    "左键进入对应牌型。",
                    "右键在子页恢复默认。"
                )));
            }
            case DDZ_BOTS -> {
                inventory.setItem(10, adminSettingItem(Material.CLOCK, DoudizhuPlugin.AdminSetting.BOT_DELAY_MIN));
                inventory.setItem(11, adminSettingItem(Material.CLOCK, DoudizhuPlugin.AdminSetting.BOT_DELAY_MAX));
                inventory.setItem(12, adminSettingItem(Material.BOOKSHELF, DoudizhuPlugin.AdminSetting.HINT_GROUP_LIMIT));
                inventory.setItem(14, item(Material.ENDER_EYE, "DeepSeek 链接", List.of(
                    plugin.aiBaseUrl(),
                    "点击后直接输入接口地址。"
                )));
                inventory.setItem(15, item(Material.TRIPWIRE_HOOK, "DeepSeek 密钥", List.of(
                    plugin.aiApiKeyMasked(),
                    "点击后直接输入 API Key。"
                )));
                inventory.setItem(16, item(Material.NAME_TAG, "DeepSeek 模型", List.of(
                    plugin.aiModelName(),
                    "点击后直接输入模型名。"
                )));
                inventory.setItem(19, noteItem(Material.BOOK, "AI 状态", List.of(
                    plugin.aiStatusSummary()
                )));
                inventory.setItem(20, noteItem(Material.COMMAND_BLOCK, "调试命令", List.of(
                    "/muz debug bot 信息",
                    "/muz debug bot 信息 <id> 你好"
                )));
                inventory.setItem(21, noteItem(Material.COMMAND_BLOCK, "压测命令", List.of(
                    "/muz debug add [数量]",
                    "在附近生成观察桌。"
                )));
            }
            case TEXAS_FURNITURE -> {
                inventory.setItem(10, adminSettingItem(Material.MANGROVE_BOAT, DoudizhuPlugin.AdminSetting.TEXAS_SEAT_DISTANCE));
                inventory.setItem(12, adminSettingItem(Material.BARRIER, DoudizhuPlugin.AdminSetting.TEXAS_SPAWN_FURNITURE));
                inventory.setItem(14, adminSettingItem(Material.CLOCK, DoudizhuPlugin.AdminSetting.TEXAS_DEALER_MARKER_HEIGHT));
                inventory.setItem(16, adminSettingItem(Material.COMPASS, DoudizhuPlugin.AdminSetting.TEXAS_DEALER_MARKER_RADIUS_FACTOR));
            }
            case TEXAS_BUTTONS -> {
                inventory.setItem(12, adminSettingItem(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, DoudizhuPlugin.AdminSetting.TEXAS_JOIN_BUTTON_HEIGHT));
                inventory.setItem(14, adminSettingItem(Material.STONE_BUTTON, DoudizhuPlugin.AdminSetting.TEXAS_ACTION_BUTTON_HEIGHT));
                inventory.setItem(16, adminSettingItem(Material.STRING, DoudizhuPlugin.AdminSetting.TEXAS_ACTION_BUTTON_STEP));
            }
            case TEXAS_CARDS -> {
                inventory.setItem(10, adminSettingItem(Material.MAP, DoudizhuPlugin.AdminSetting.TEXAS_COMMUNITY_CARD_HEIGHT));
                inventory.setItem(12, adminSettingItem(Material.LEAD, DoudizhuPlugin.AdminSetting.TEXAS_COMMUNITY_CARD_SPACING));
                inventory.setItem(14, adminSettingItem(Material.PAPER, DoudizhuPlugin.AdminSetting.TEXAS_HOLE_CARD_HEIGHT));
                inventory.setItem(16, adminSettingItem(Material.TRIPWIRE_HOOK, DoudizhuPlugin.AdminSetting.TEXAS_HOLE_CARD_SPACING));
                inventory.setItem(22, adminSettingItem(Material.TARGET, DoudizhuPlugin.AdminSetting.TEXAS_HOLE_RADIUS_FACTOR));
            }
            case TEXAS_TEXT -> {
                inventory.setItem(12, adminSettingItem(Material.OAK_SIGN, DoudizhuPlugin.AdminSetting.TEXAS_SEAT_LABEL_HEIGHT));
                inventory.setItem(14, adminSettingItem(Material.WRITABLE_BOOK, DoudizhuPlugin.AdminSetting.TEXAS_STATUS_HEIGHT));
            }
        }
        fillAdminChrome(inventory);
        player.openInventory(inventory);
    }

    public void openAdminSelectionSoundEditor(Player player) {
        openAdminSelectionSoundEditor(player, 0);
    }

    public void openAdminSelectionSoundEditor(Player player, int profileIndex) {
        int normalized = Math.max(0, Math.min(3, profileIndex));
        HandInventoryHolder holder = new HandInventoryHolder("", player.getUniqueId(), HandInventoryHolder.ViewMode.ADMIN_SELECTION_SOUND_EDITOR, HandInventoryHolder.EditorTarget.ADMIN_SELECTION_SOUND, normalized);
        Inventory inventory = Bukkit.createInventory(holder, COUNTDOWN_EDITOR_SIZE, "斗地主 | 选牌音效方案 " + (normalized + 1));
        holder.setInventory(inventory);
        DoudizhuPlugin.OptionProfile profile = plugin.getSelectionSoundProfile(normalized);
        inventory.setItem(4, noteItem(Material.NOTE_BLOCK, "选牌音效方案 " + (normalized + 1), List.of(
            selectionSoundDisplayLabel(profile),
            profile.spec()
        )));
        List<SoundPreset> presets = selectionSoundPresets();
        for (int index = 0; index < presets.size() && index < 7; index++) {
            int slot = 10 + index;
            inventory.setItem(slot, item(presets.get(index).material(), presets.get(index).title(), List.of(presets.get(index).description(), presets.get(index).spec())));
        }
        inventory.setItem(22, noteItem(Material.BOOK, "操作说明", List.of(
            "点击预设会立即应用。",
            "也可以手动输入 spec。"
        )));
        inventory.setItem(30, noteItem(Material.OAK_SIGN, "自定义输入", List.of("手动输入音效 spec。")));
        inventory.setItem(32, backItem("玩家选项"));
        inventory.setItem(34, closeItem());
        player.openInventory(inventory);
    }

    public void openAdminPlayActionKindPicker(Player player, DoudizhuPlugin.PlayActionKind kind) {
        HandInventoryHolder holder = new HandInventoryHolder(
            "",
            player.getUniqueId(),
            HandInventoryHolder.ViewMode.ADMIN_PLAY_ACTION_KIND_PICKER,
            HandInventoryHolder.EditorTarget.ADMIN_PLAY_ACTION,
            kind,
            -1
        );
        Inventory inventory = Bukkit.createInventory(holder, PICKER_SIZE, "斗地主 | 动作 | " + kind.label());
        holder.setInventory(inventory);
        List<DoudizhuPlugin.OptionProfile> profiles = plugin.getPlayActionProfiles(kind);
        inventory.setItem(4, noteItem(actionKindMaterial(kind), kind.label() + " · 动作槽", List.of(
            "左键进入编辑页。",
            "右键恢复默认。"
        )));
        for (int index = 0; index < profiles.size() && index < 4; index++) {
            int slot = fourChoiceSlot(index);
            inventory.setItem(slot, profileItem(Material.COMMAND_BLOCK, kind.label() + " 动作 " + (index + 1), profiles.get(index)));
        }
        inventory.setItem(22, backItem("玩家选项"));
        inventory.setItem(24, closeItem());
        player.openInventory(inventory);
    }

    public void openAdminPlayActionEditor(Player player, DoudizhuPlugin.PlayActionKind kind, int profileIndex) {
        int normalized = Math.max(0, Math.min(3, profileIndex));
        HandInventoryHolder holder = new HandInventoryHolder("", player.getUniqueId(), HandInventoryHolder.ViewMode.ADMIN_PLAY_ACTION_EDITOR, HandInventoryHolder.EditorTarget.ADMIN_PLAY_ACTION, kind, normalized);
        Inventory inventory = Bukkit.createInventory(holder, COUNTDOWN_EDITOR_SIZE, "斗地主 | " + kind.label() + " 动作 " + (normalized + 1));
        holder.setInventory(inventory);
        DoudizhuPlugin.OptionProfile profile = plugin.getPlayActionProfile(kind, normalized);
        inventory.setItem(4, noteItem(Material.COMMAND_BLOCK, kind.label() + " · 动作 " + (normalized + 1), List.of(
            profile.label(),
            profile.spec()
        )));
        List<ActionPreset> presets = playActionPresets();
        for (int index = 0; index < presets.size() && index < 7; index++) {
            int slot = 10 + index;
            inventory.setItem(slot, item(presets.get(index).material(), presets.get(index).title(), List.of(presets.get(index).description(), presets.get(index).spec())));
        }
        inventory.setItem(22, noteItem(Material.BOOK, "操作说明", List.of(
            "点击预设会直接覆盖当前动作槽。",
            "自定义格式：显示名 || CE 语法。"
        )));
        inventory.setItem(30, noteItem(Material.OAK_SIGN, "自定义输入", List.of("手动输入动作内容。")));
        inventory.setItem(32, backItem(kind.label() + " 动作槽"));
        inventory.setItem(34, closeItem());
        player.openInventory(inventory);
    }

    public void openCountdownSoundPicker(Player player) {
        HandInventoryHolder holder = new HandInventoryHolder("", player.getUniqueId(), HandInventoryHolder.ViewMode.ADMIN_COUNTDOWN_SOUND_EDITOR, HandInventoryHolder.EditorTarget.ADMIN_COUNTDOWN, -1);
        Inventory inventory = Bukkit.createInventory(holder, COUNTDOWN_EDITOR_SIZE, "斗地主 | 倒计时音效");
        holder.setInventory(inventory);
        inventory.setItem(4, noteItem(Material.CLOCK, "当前倒计时音效", List.of(plugin.getCountdownSoundSpec())));
        List<SoundPreset> presets = countdownPresets();
        for (int index = 0; index < presets.size() && index < 7; index++) {
            int slot = 10 + index;
            inventory.setItem(slot, item(presets.get(index).material(), presets.get(index).title(), List.of(presets.get(index).description(), presets.get(index).spec())));
        }
        inventory.setItem(22, noteItem(Material.BOOK, "操作说明", List.of(
            "点击预设会直接替换。",
            "也可以手动输入 spec。"
        )));
        inventory.setItem(30, noteItem(Material.OAK_SIGN, "自定义输入", List.of("手动输入音效 spec。")));
        inventory.setItem(32, backItem("音频设置"));
        inventory.setItem(34, closeItem());
        player.openInventory(inventory);
    }

    public void openUnreadyWarningSoundPicker(Player player) {
        HandInventoryHolder holder = new HandInventoryHolder("", player.getUniqueId(), HandInventoryHolder.ViewMode.ADMIN_COUNTDOWN_SOUND_EDITOR, HandInventoryHolder.EditorTarget.ADMIN_UNREADY_WARNING, -1);
        Inventory inventory = Bukkit.createInventory(holder, COUNTDOWN_EDITOR_SIZE, "斗地主 | 未准备提醒音");
        holder.setInventory(inventory);
        inventory.setItem(4, noteItem(Material.BELL, "当前未准备提醒音", List.of(plugin.getUnreadyWarningSoundSpec())));
        List<SoundPreset> presets = countdownPresets();
        for (int index = 0; index < presets.size() && index < 7; index++) {
            int slot = 10 + index;
            inventory.setItem(slot, item(presets.get(index).material(), presets.get(index).title(), List.of(presets.get(index).description(), presets.get(index).spec())));
        }
        inventory.setItem(22, noteItem(Material.BOOK, "操作说明", List.of(
            "点击预设会直接替换。",
            "也可以手动输入 spec。"
        )));
        inventory.setItem(30, noteItem(Material.OAK_SIGN, "自定义输入", List.of("手动输入音效 spec。")));
        inventory.setItem(32, backItem("音频设置"));
        inventory.setItem(34, closeItem());
        player.openInventory(inventory);
    }

    public void openPlacementBlockedSoundPicker(Player player) {
        HandInventoryHolder holder = new HandInventoryHolder("", player.getUniqueId(), HandInventoryHolder.ViewMode.ADMIN_COUNTDOWN_SOUND_EDITOR, HandInventoryHolder.EditorTarget.ADMIN_PLACEMENT_BLOCKED_WARNING, -1);
        Inventory inventory = Bukkit.createInventory(holder, COUNTDOWN_EDITOR_SIZE, "斗地主 | 放置阻挡警告音");
        holder.setInventory(inventory);
        inventory.setItem(4, noteItem(Material.BELL, "当前放置阻挡警告音", List.of(plugin.getPlacementBlockedSoundSpec())));
        List<SoundPreset> presets = countdownPresets();
        for (int index = 0; index < presets.size() && index < 7; index++) {
            int slot = 10 + index;
            inventory.setItem(slot, item(presets.get(index).material(), presets.get(index).title(), List.of(presets.get(index).description(), presets.get(index).spec())));
        }
        inventory.setItem(22, noteItem(Material.BOOK, "操作说明", List.of(
            "点击预设会直接替换。",
            "也可以手动输入 spec。"
        )));
        inventory.setItem(30, noteItem(Material.OAK_SIGN, "自定义输入", List.of("手动输入音效 spec。")));
        inventory.setItem(32, backItem("桌椅设置"));
        inventory.setItem(34, closeItem());
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
            case SETTINGS_ACTION_KIND_MENU -> openPlayActionKindMenu(player);
            case SETTINGS_SELECTION_SOUND_PICKER -> openSelectionSoundPicker(player);
            case SETTINGS_PLAY_ACTION_PICKER -> openPlayActionPicker(player, holder.playActionKind());
            case ADMIN_PLAY_ACTION_KIND_PICKER -> openAdminPlayActionKindPicker(player, holder.playActionKind());
            case ADMIN_SELECTION_SOUND_EDITOR -> openAdminSelectionSoundEditor(player, holder.profileIndex());
            case ADMIN_PLAY_ACTION_EDITOR -> openAdminPlayActionEditor(player, holder.playActionKind(), holder.profileIndex());
            case ADMIN_COUNTDOWN_SOUND_EDITOR -> {
                if (holder.editorTarget() == HandInventoryHolder.EditorTarget.ADMIN_UNREADY_WARNING) {
                    openUnreadyWarningSoundPicker(player);
                } else if (holder.editorTarget() == HandInventoryHolder.EditorTarget.ADMIN_PLACEMENT_BLOCKED_WARNING) {
                    openPlacementBlockedSoundPicker(player);
                } else {
                    openCountdownSoundPicker(player);
                }
            }
            case ADMIN_MODELS -> openAdminModels(player, holder.adminPage() == null ? HandInventoryHolder.AdminPage.HOME : holder.adminPage());
        }
    }

    public boolean hasPendingSoundInput(UUID playerId) {
        return pendingInputs.containsKey(playerId);
    }

    public boolean hasPendingSignInput(UUID playerId) {
        return pendingSignInputs.containsKey(playerId);
    }

    public void beginCustomInput(Player player, HandInventoryHolder.EditorTarget target, int profileIndex) {
        beginCustomInput(player, target, null, profileIndex);
    }

    public void beginCustomInput(Player player, HandInventoryHolder.EditorTarget target, DoudizhuPlugin.PlayActionKind actionKind, int profileIndex) {
        pendingInputs.put(player.getUniqueId(), new InputSession(target, profileIndex, null, actionKind));
        player.closeInventory();
        switch (target) {
            case ADMIN_SELECTION_SOUND -> player.sendMessage(component("把音效写给我就行，例如 `音效名 [音量] [选中音高] [取消音高]`。", NamedTextColor.AQUA));
            case ADMIN_PLAY_ACTION -> player.sendMessage(component("把动作内容贴进来就行，格式是 `显示名 || CE 单行动作语法`。", NamedTextColor.AQUA));
            case ADMIN_COUNTDOWN -> player.sendMessage(component("直接输入倒计时音效，格式是 `音效名 [音量] [音高]`。", NamedTextColor.AQUA));
            case ADMIN_UNREADY_WARNING -> player.sendMessage(component("直接输入未准备提醒音，格式是 `音效名 [音量] [音高]`。", NamedTextColor.AQUA));
            case ADMIN_PLACEMENT_BLOCKED_WARNING -> player.sendMessage(component("直接输入放置阻挡警告音，格式是 `音效名 [音量] [音高]`。", NamedTextColor.AQUA));
            case ADMIN_AI_URL -> player.sendMessage(component("直接输入 DeepSeek API 链接，例如 `https://api.deepseek.com`。", NamedTextColor.AQUA));
            case ADMIN_AI_KEY -> player.sendMessage(component("直接输入 DeepSeek API Key。", NamedTextColor.AQUA));
            case ADMIN_AI_MODEL -> player.sendMessage(component("直接输入 DeepSeek 模型，例如 `deepseek-chat`。", NamedTextColor.AQUA));
            default -> {
                pendingInputs.remove(player.getUniqueId());
                return;
            }
        }
        player.sendMessage(component("如果想先不改，输入 `cancel` 或 `取消` 就能退出。", NamedTextColor.YELLOW));
    }

    public void beginChipBalanceInput(Player viewer, Player target) {
        if (viewer == null || target == null) {
            return;
        }
        pendingInputs.put(viewer.getUniqueId(), new InputSession(HandInventoryHolder.EditorTarget.ADMIN_CHIP_BALANCE, -1, target.getUniqueId(), null));
        viewer.closeInventory();
        viewer.sendMessage(component("准备修改 " + target.getName() + " 的筹码，直接输入数字就行，可填负数。", NamedTextColor.AQUA));
        viewer.sendMessage(component("当前筹码是 " + plugin.getChipBalance(target.getUniqueId()) + "。", NamedTextColor.YELLOW));
        viewer.sendMessage(component("如果只是看看，输入 `cancel` 或 `取消` 就能返回。", NamedTextColor.YELLOW));
    }

    public void beginRgbSignInput(Player player, HandInventoryHolder.EditorTarget target) {
        pendingSignInputs.put(player.getUniqueId(), new SignInputSession(target, System.currentTimeMillis(), null));
        player.closeInventory();
        // HARD-CODED:
        // All color editors now use silent chat input instead of sign GUI.
        // Do not switch these back to real or virtual signs unless the user explicitly asks.
        // While a color input session is pending, MUZ intercepts the player's chat message and keeps it out of public chat.
        player.sendMessage(component("当前颜色: " + rgbInitialValue(player, target), NamedTextColor.GRAY));
        player.sendMessage(component("直接在聊天栏输入颜色，例如 `255,226,92` 或 `F9B5B5`。", NamedTextColor.AQUA));
        player.sendMessage(component("如果不想改，输入 `cancel` 或 `取消`。", NamedTextColor.YELLOW));
    }

    public void handlePendingSoundInput(Player player, String rawInput) {
        InputSession session = pendingInputs.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        String trimmed = rawInput == null ? "" : rawInput.trim();
        if (trimmed.equalsIgnoreCase("cancel") || trimmed.equalsIgnoreCase("取消")) {
            pendingInputs.remove(player.getUniqueId());
            if (session.target() == HandInventoryHolder.EditorTarget.ADMIN_SELECTION_SOUND) {
                openAdminSelectionSoundEditor(player, session.profileIndex());
                return;
            }
            if (session.target() == HandInventoryHolder.EditorTarget.ADMIN_PLAY_ACTION) {
                openAdminPlayActionEditor(player, session.actionKind(), session.profileIndex());
                return;
            }
            reopenAfterInput(player, session.target());
            return;
        }

        try {
            switch (session.target()) {
                case ADMIN_SELECTION_SOUND -> plugin.setSelectionSoundProfileDefinition(session.profileIndex(), selectionProfileFromSpec(trimmed));
                case ADMIN_PLAY_ACTION -> plugin.setPlayActionProfileDefinition(session.actionKind(), session.profileIndex(), parseProfileInput(trimmed, false));
                case ADMIN_COUNTDOWN -> plugin.setCountdownSoundSpec(trimmed);
                case ADMIN_UNREADY_WARNING -> plugin.setUnreadyWarningSoundSpec(trimmed);
                case ADMIN_PLACEMENT_BLOCKED_WARNING -> plugin.setPlacementBlockedSoundSpec(trimmed);
                case ADMIN_AI_URL -> plugin.setAiBaseUrl(trimmed);
                case ADMIN_AI_KEY -> plugin.setAiApiKey(trimmed);
                case ADMIN_AI_MODEL -> plugin.setAiModelName(trimmed);
                case ADMIN_CHIP_BALANCE -> {
                    if (session.targetPlayerId() == null) {
                        throw new IllegalArgumentException("我没找到这位玩家，先重新打开面板再试一次吧。");
                    }
                    int value = Integer.parseInt(trimmed);
                    plugin.setChipBalance(session.targetPlayerId(), value);
                }
                default -> {
                }
            }
            pendingInputs.remove(player.getUniqueId());
            notifySettingSaved(player, switch (session.target()) {
                case ADMIN_SELECTION_SOUND -> "选牌音效方案 " + (session.profileIndex() + 1) + " 已更新";
                case ADMIN_PLAY_ACTION -> normalizeActionKind(session.actionKind()).label() + " 动作 " + (session.profileIndex() + 1) + " 已更新";
                case ADMIN_COUNTDOWN -> "倒计时音效已更新";
                case ADMIN_UNREADY_WARNING -> "未准备提醒音已更新";
                case ADMIN_PLACEMENT_BLOCKED_WARNING -> "放置阻挡警告音已更新";
                case ADMIN_AI_URL -> "DeepSeek 链接已更新";
                case ADMIN_AI_KEY -> "DeepSeek 密钥已更新";
                case ADMIN_AI_MODEL -> "DeepSeek 模型已更新";
                case ADMIN_CHIP_BALANCE -> "筹码数量已更新";
                default -> "设置已更新";
            }, session.target() != HandInventoryHolder.EditorTarget.ADMIN_PLAY_ACTION);
            if (session.target() == HandInventoryHolder.EditorTarget.ADMIN_SELECTION_SOUND) {
                openAdminSelectionSoundEditor(player, session.profileIndex());
                return;
            }
            if (session.target() == HandInventoryHolder.EditorTarget.ADMIN_PLAY_ACTION) {
                openAdminPlayActionEditor(player, session.actionKind(), session.profileIndex());
                return;
            }
            if (session.target() == HandInventoryHolder.EditorTarget.ADMIN_UNREADY_WARNING) {
                openUnreadyWarningSoundPicker(player);
                return;
            }
            if (session.target() == HandInventoryHolder.EditorTarget.ADMIN_PLACEMENT_BLOCKED_WARNING) {
                openPlacementBlockedSoundPicker(player);
                return;
            }
            reopenAfterInput(player, session.target());
        } catch (IllegalArgumentException exception) {
            player.sendMessage(component(exception.getMessage(), NamedTextColor.RED));
            player.sendMessage(component("这次没有记上，再输一次，或者输入 `cancel` 先退出。", NamedTextColor.YELLOW));
        }
    }

    public void handlePendingSignInput(Player player, String rawInput) {
        SignInputSession session = pendingSignInputs.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        String trimmed = rawInput == null ? "" : rawInput.trim();
        if (trimmed.isBlank() || trimmed.equalsIgnoreCase("cancel") || trimmed.equalsIgnoreCase("取消")) {
            pendingSignInputs.remove(player.getUniqueId());
            reopenAfterSignInput(player, session.target());
            return;
        }

        try {
            switch (session.target()) {
                case PLAYER_PREVIEW_GLOW -> plugin.setPlayerPreviewGlowColor(player.getUniqueId(), parseRgbRequired(trimmed));
                case PLAYER_SELECTED_GLOW -> plugin.setPlayerSelectionGlowColor(player.getUniqueId(), parseRgbRequired(trimmed));
                case ADMIN_HOVER_GLOW -> plugin.setHoverGlowColor(parseRgbRequired(trimmed));
                case ADMIN_SELECTED_GLOW -> plugin.setSelectedGlowColor(parseRgbRequired(trimmed));
                default -> {
                }
            }
            pendingSignInputs.remove(player.getUniqueId());
            notifySettingSaved(player, switch (session.target()) {
                case PLAYER_PREVIEW_GLOW -> "预览色已更新";
                case PLAYER_SELECTED_GLOW -> "选择色已更新";
                case ADMIN_HOVER_GLOW -> "全局预览色已更新";
                case ADMIN_SELECTED_GLOW -> "全局选中色已更新";
                default -> "设置已更新";
            });
            reopenAfterSignInput(player, session.target());
        } catch (IllegalArgumentException exception) {
            player.sendMessage(component(exception.getMessage(), NamedTextColor.RED));
            player.sendMessage(component("再输一次，或者输入 `cancel` 先退出。", NamedTextColor.YELLOW));
        }
    }

    public String soundPresetSpec(HandInventoryHolder.EditorTarget target, int rawSlot) {
        if (target != HandInventoryHolder.EditorTarget.ADMIN_COUNTDOWN
            && target != HandInventoryHolder.EditorTarget.ADMIN_UNREADY_WARNING
            && target != HandInventoryHolder.EditorTarget.ADMIN_PLACEMENT_BLOCKED_WARNING) {
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
            case ADMIN_SELECTION_SOUND, ADMIN_PLAY_ACTION -> openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_PLAYER_OPTIONS);
            case ADMIN_COUNTDOWN -> openCountdownSoundPicker(player);
            case ADMIN_UNREADY_WARNING -> openUnreadyWarningSoundPicker(player);
            case ADMIN_PLACEMENT_BLOCKED_WARNING -> openPlacementBlockedSoundPicker(player);
            case ADMIN_AI_URL, ADMIN_AI_KEY, ADMIN_AI_MODEL -> openAdminModels(player, HandInventoryHolder.AdminPage.DDZ_BOTS);
            case ADMIN_CHIP_BALANCE -> openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_ECONOMY);
            default -> openSettings(player);
        }
    }

    private void reopenAfterSignInput(Player player, HandInventoryHolder.EditorTarget target) {
        switch (target) {
            case PLAYER_PREVIEW_GLOW, PLAYER_SELECTED_GLOW -> openSettings(player);
            case ADMIN_HOVER_GLOW, ADMIN_SELECTED_GLOW -> openAdminModels(player, HandInventoryHolder.AdminPage.GLOBAL_HIGHLIGHT);
            default -> openSettings(player);
        }
    }

    private String rgbInitialValue(Player player, HandInventoryHolder.EditorTarget target) {
        org.bukkit.Color color = switch (target) {
            case PLAYER_PREVIEW_GLOW -> plugin.previewGlowColorFor(player.getUniqueId());
            case PLAYER_SELECTED_GLOW -> plugin.selectionGlowColorFor(player.getUniqueId());
            case ADMIN_HOVER_GLOW -> plugin.hoverGlowColor();
            case ADMIN_SELECTED_GLOW -> plugin.selectedGlowColor();
            default -> null;
        };
        if (color == null) {
            return "";
        }
        return color.getRed() + "," + color.getGreen() + "," + color.getBlue();
    }

    private org.bukkit.Color parseRgbRequired(String raw) {
        org.bukkit.Color color = parseRgb(raw);
        if (color == null) {
            throw new IllegalArgumentException("颜色格式我还没认出来，请写成 `255,226,92` 或 `F9B5B5`。");
        }
        return color;
    }

    private org.bukkit.Color parseRgb(String raw) {
        String normalized = raw.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.matches("(?i)^[0-9a-f]{6}$")) {
            try {
                int red = Integer.parseInt(normalized.substring(0, 2), 16);
                int green = Integer.parseInt(normalized.substring(2, 4), 16);
                int blue = Integer.parseInt(normalized.substring(4, 6), 16);
                return org.bukkit.Color.fromRGB(red, green, blue);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        String[] parts = raw.split("\\s*,\\s*");
        if (parts.length != 3) {
            return null;
        }
        try {
            int red = clampRgb(Integer.parseInt(parts[0]));
            int green = clampRgb(Integer.parseInt(parts[1]));
            int blue = clampRgb(Integer.parseInt(parts[2]));
            return org.bukkit.Color.fromRGB(red, green, blue);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private int clampRgb(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private DoudizhuPlugin.OptionProfile parseProfileInput(String rawInput, boolean soundProfile) {
        String[] parts = rawInput.split("\\|\\|", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("格式还不对，请写成 `显示名 || 配置内容`。");
        }
        String label = parts[0].trim();
        String spec = parts[1].trim();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("显示名先别留空，给这套方案起个名字吧。");
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

    private List<SoundPreset> selectionSoundPresets() {
        return List.of(
            new SoundPreset(Material.NOTE_BLOCK, "清脆提示", "minecraft:block.note_block.pling 0.35 1.18 0.92", "轻亮、反馈明确。"),
            new SoundPreset(Material.OAK_HANGING_SIGN, "告示牌提示", "minecraft:block.hanging_sign.place 0.35 1.12 0.92", "木质、较柔和。"),
            new SoundPreset(Material.SCULK, "洞穴提示", "minecraft:ambient.cave 0.25 1.00 0.90", "偏氛围感。"),
            new SoundPreset(Material.BELL, "钟声提示", "minecraft:block.note_block.bell 0.28 1.10 0.96", "更像提醒铃。"),
            new SoundPreset(Material.IRON_TRAPDOOR, "金属提示", "minecraft:block.iron_trapdoor.open 0.24 1.22 0.98", "偏机械、清脆。"),
            new SoundPreset(Material.STONE_BUTTON, "按钮提示", "minecraft:ui.button.click 0.30 1.06 0.92", "短促、利落。"),
            new SoundPreset(Material.IRON_BARS, "静音", "minecraft:block.note_block.hat 0.00 1.00 1.00", "不播放选牌音效。")
        );
    }

    private List<ActionPreset> playActionPresets() {
        return List.of(
            new ActionPreset(Material.BARRIER, "无操作", "type: none", "不额外执行任何提示。"),
            new ActionPreset(Material.PAPER, "聊天提示", "type: message; message: <#8FD4FF>出牌完成</#8FD4FF><dark_gray> · </dark_gray><#F1D398><arg:pattern></#F1D398>", "向玩家发送聊天提示。"),
            new ActionPreset(Material.CLOCK, "动作栏提示", "type: actionbar; actionbar: <#9AA8B6><arg:player.name></#9AA8B6><dark_gray> · </dark_gray><#F1D398><arg:pattern></#F1D398>", "在动作栏显示提示。"),
            new ActionPreset(Material.BELL, "播放音效", "type: play_sound; sound: minecraft:entity.player.levelup; volume: 0.35; pitch: 1.05; source: master", "播放一个成功音效。"),
            new ActionPreset(Material.NAME_TAG, "标题提示", "type: title; title: <gradient:#8FD4FF:#F1D398><bold>出牌已确认</bold></gradient>; subtitle: <#9AA8B6><arg:player.name></#9AA8B6><dark_gray> · </dark_gray><#F1D398><arg:pattern></#F1D398>; fade-in: 5; stay: 30; fade-out: 10", "弹出标题和副标题。"),
            new ActionPreset(Material.COMMAND_BLOCK, "控制台命令", "type: command; command: say <arg:player.name> 打出了 <arg:pattern>", "由控制台执行命令。"),
            new ActionPreset(Material.REPEATER, "玩家命令", "type: command; command: help; as-player: true", "由玩家自己执行命令。")
        );
    }

    private DoudizhuPlugin.OptionProfile selectionProfileFromSpec(String spec) {
        String normalized = plugin.normalizeSelectionSoundSpec(spec);
        return new DoudizhuPlugin.OptionProfile(selectionSoundLabel(normalized), normalized);
    }

    private String displayProfileLabel(HandInventoryHolder.EditorTarget target, DoudizhuPlugin.OptionProfile profile) {
        return target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION ? selectionSoundDisplayLabel(profile) : profile.label();
    }

    private String selectionSoundDisplayLabel(DoudizhuPlugin.OptionProfile profile) {
        String translated = selectionSoundLabel(profile.spec());
        return translated == null || translated.isBlank() ? profile.label() : translated;
    }

    private String selectionSoundLabel(String spec) {
        String[] parts = spec.split("\\s+");
        if (parts.length == 0 || parts[0].isBlank()) {
            return "自定义音效";
        }
        String key = parts[0].trim();
        return switch (key) {
            case "minecraft:block.note_block.pling" -> "清脆提示";
            case "minecraft:block.hanging_sign.place" -> "告示牌提示";
            case "minecraft:ambient.cave" -> "洞穴提示";
            case "minecraft:block.note_block.bell" -> "钟声提示";
            case "minecraft:block.iron_trapdoor.open" -> "金属提示";
            case "minecraft:ui.button.click" -> "按钮提示";
            case "minecraft:block.note_block.hat" -> spec.contains("0.00") ? "静音" : "木质提示";
            default -> "自定义音效";
        };
    }

    private ItemStack adminSettingItem(Material material, DoudizhuPlugin.AdminSetting setting) {
        List<String> lore = new java.util.ArrayList<>();
        lore.add(adminSettingHint(setting));
        if (setting.booleanSetting()) {
            lore.add("点击切换。");
        } else if (setting.integerSetting()) {
            lore.add("左加右减。");
            lore.add("Shift x10。");
        } else {
            lore.add("左加右减。");
            lore.add("Shift x10。");
        }
        return item(material, setting.label() + " · " + plugin.adminSettingValue(setting), lore);
    }

    private ItemStack toggleItem(Material material, String title, boolean enabled, String note) {
        return item(material, title + " · " + bool(enabled), List.of(note, "点击切换。"));
    }

    private ItemStack profileItem(Material material, String title, DoudizhuPlugin.OptionProfile profile) {
        return item(material, title + " · " + profile.label(), List.of(profile.spec()));
    }

    private void placePlayerActionKindItems(Inventory inventory, UUID playerId) {
        placeActionKindItem(inventory, 10, playerId, DoudizhuPlugin.PlayActionKind.AIRPLANE);
        placeActionKindItem(inventory, 11, playerId, DoudizhuPlugin.PlayActionKind.STRAIGHT);
        placeActionKindItem(inventory, 12, playerId, DoudizhuPlugin.PlayActionKind.PAIR_STRAIGHT);
        placeActionKindItem(inventory, 14, playerId, DoudizhuPlugin.PlayActionKind.TRIPLE_WITH_SINGLE);
        placeActionKindItem(inventory, 15, playerId, DoudizhuPlugin.PlayActionKind.BOMB);
        placeActionKindItem(inventory, 16, playerId, DoudizhuPlugin.PlayActionKind.JOKER_BOMB);
    }

    private void placeActionKindItem(Inventory inventory, int slot, UUID playerId, DoudizhuPlugin.PlayActionKind kind) {
        DoudizhuPlugin.OptionProfile profile = plugin.getPlayActionProfile(kind, plugin.getPlayerPlayActionProfileIndex(playerId, kind));
        inventory.setItem(slot, item(actionKindMaterial(kind), kind.label(), List.of(
            "当前方案 · " + profile.label(),
            "进入后可切换 1-4 号动作。"
        )));
    }

    private void placeAdminActionKindItems(Inventory inventory) {
        placeAdminActionKindItem(inventory, 19, DoudizhuPlugin.PlayActionKind.AIRPLANE);
        placeAdminActionKindItem(inventory, 20, DoudizhuPlugin.PlayActionKind.STRAIGHT);
        placeAdminActionKindItem(inventory, 21, DoudizhuPlugin.PlayActionKind.PAIR_STRAIGHT);
        placeAdminActionKindItem(inventory, 28, DoudizhuPlugin.PlayActionKind.TRIPLE_WITH_SINGLE);
        placeAdminActionKindItem(inventory, 29, DoudizhuPlugin.PlayActionKind.BOMB);
        placeAdminActionKindItem(inventory, 30, DoudizhuPlugin.PlayActionKind.JOKER_BOMB);
    }

    private void placeAdminActionKindItem(Inventory inventory, int slot, DoudizhuPlugin.PlayActionKind kind) {
        DoudizhuPlugin.OptionProfile profile = plugin.getPlayActionProfile(kind, 0);
        inventory.setItem(slot, item(actionKindMaterial(kind), kind.label(), List.of(
            "1 号槽 · " + profile.label(),
            "点击进入 1-4 号动作槽。"
        )));
    }

    private Material actionKindMaterial(DoudizhuPlugin.PlayActionKind kind) {
        return switch (kind) {
            case AIRPLANE -> Material.FIREWORK_ROCKET;
            case STRAIGHT -> Material.BAMBOO;
            case PAIR_STRAIGHT -> Material.CHAINMAIL_BOOTS;
            case TRIPLE_WITH_SINGLE -> Material.TRIDENT;
            case BOMB -> Material.TNT;
            case JOKER_BOMB -> Material.NETHER_STAR;
        };
    }

    private DoudizhuPlugin.PlayActionKind normalizeActionKind(DoudizhuPlugin.PlayActionKind kind) {
        return kind == null ? DoudizhuPlugin.PlayActionKind.AIRPLANE : kind;
    }

    private ItemStack item(Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MuzTheme.accent(title));
        meta.lore(lore.stream().map(MuzTheme::muted).toList());
        item.setItemMeta(meta);
        return item;
    }

    private void fillAdminChrome(Inventory inventory) {
        if (inventory == null) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) != null) {
                continue;
            }
            inventory.setItem(slot, fillerPane(slot));
        }
    }

    private ItemStack fillerPane(int slot) {
        Material material = (slot / 9 == 0 || slot / 9 == 5)
            ? Material.GRAY_STAINED_GLASS_PANE
            : Material.BLACK_STAINED_GLASS_PANE;
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack noteItem(Material material, String title, List<String> lore) {
        return richItem(material, MuzTheme.warm(title), lore.stream().map(MuzTheme::muted).toList());
    }

    private ItemStack colorSettingItem(Material material, String label, String value, org.bukkit.Color color, List<String> lore) {
        return richItem(material, colorSettingTitle(label, value, color), lore.stream().map(MuzTheme::muted).toList());
    }

    private Component colorSettingTitle(String label, String value, org.bukkit.Color color) {
        TextColor textColor = color == null ? NamedTextColor.WHITE : TextColor.color(color.getRed(), color.getGreen(), color.getBlue());
        return MuzTheme.solid(label + " · " + value, textColor, true);
    }

    private String playerPreviewColorDisplayLabel(UUID playerId) {
        String label = plugin.previewGlowColorLabel(playerId);
        return "默认(全局)".equals(label) ? "跟随全局 · " + plugin.hoverGlowColorLabel() : label;
    }

    private String playerSelectionColorDisplayLabel(UUID playerId) {
        String label = plugin.selectionGlowColorLabel(playerId);
        return "默认(全局)".equals(label) ? "跟随全局 · " + plugin.selectedGlowColorLabel() : label;
    }

    private ItemStack backItem(String target) {
        return richItem(Material.ARROW, MuzTheme.warm("返回"), List.of(MuzTheme.muted("回到 " + target + "。")));
    }

    private ItemStack closeItem() {
        return richItem(Material.BARRIER, MuzTheme.danger("关闭"), List.of(MuzTheme.muted("退出当前菜单。")));
    }

    private int fourChoiceSlot(int index) {
        return switch (index) {
            case 0 -> 10;
            case 1 -> 12;
            case 2 -> 14;
            case 3 -> 16;
            default -> throw new IllegalArgumentException("Invalid picker index: " + index);
        };
    }

    private ItemStack richItem(Material material, Component title, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(title);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack loreLineItem(Component line) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MuzTheme.body(" "));
        meta.lore(List.of(line));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack historyOverviewCard(PlayerHistoryEntry entry) {
        boolean doudizhu = entry.match().gameType().equalsIgnoreCase("DOUDIZHU");
        MatchParticipantRecord self = entry.self();
        String outcome = self != null && "WIN".equalsIgnoreCase(self.outcome()) ? "胜利" : "失败";
        NamedTextColor outcomeColor = "胜利".equals(outcome) ? NamedTextColor.GREEN : NamedTextColor.RED;
        return richItem(
            doudizhu ? Material.PAPER : Material.CLOCK,
            component("对局概况", NamedTextColor.YELLOW),
            List.of(
                themeLine("玩法", doudizhu ? "斗地主" : "德州", doudizhu ? NamedTextColor.GOLD : NamedTextColor.AQUA),
                themeLine("结果", outcome, outcomeColor)
            )
        );
    }

    private ItemStack historySelfCard(PlayerHistoryEntry entry) {
        MatchParticipantRecord self = entry.self();
        return richItem(
            Material.PLAYER_HEAD,
            component("你的信息", NamedTextColor.YELLOW),
            self == null
                ? List.of(component("玩家信息缺失", NamedTextColor.GRAY))
                : List.of(
                    MuzTheme.muted("玩家 ")
                        .append(plugin.playerIdentityComponent(self.playerId(), self.playerName(), NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false),
                    themeLine("身份", self.roleLabel(), NamedTextColor.GOLD)
                )
        );
    }

    private ItemStack historyRoleCard(PlayerHistoryEntry entry) {
        boolean doudizhu = entry.match().gameType().equalsIgnoreCase("DOUDIZHU");
        List<Component> lore = new java.util.ArrayList<>();
        if (doudizhu) {
            lore.add(themeLine("地主", findRole(entry, "地主"), NamedTextColor.GOLD));
            lore.add(themeLine("农民阵营", joinRole(entry, "农民"), NamedTextColor.YELLOW));
        } else if (entry.self() != null) {
            lore.add(themeLine("桌上位置", entry.self().roleLabel(), NamedTextColor.GOLD));
        }
        return richItem(Material.NAME_TAG, component("身份关系", NamedTextColor.YELLOW), lore);
    }

    private ItemStack historySettlementCard(PlayerHistoryEntry entry) {
        MatchParticipantRecord self = entry.self();
        List<Component> lore = new java.util.ArrayList<>();
        if (self != null) {
            lore.add(themeLine("个人盈亏", formatSigned(self.settlementDelta()) + normalizeHistoryUnit(self.unitLabel()), self.settlementDelta() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
            if (self.debtAfter() > 0.0001) {
                lore.add(themeLine("当前欠款", formatMoney(self.debtAfter()) + normalizeHistoryUnit(self.unitLabel()), NamedTextColor.RED));
            }
            if (self.bankrupt()) {
                lore.add(component("这一局后已破产", NamedTextColor.RED));
            }
        }
        return richItem(Material.GOLD_INGOT, component("个人结算", NamedTextColor.YELLOW), lore);
    }

    private ItemStack historyTimeCard(PlayerHistoryEntry entry) {
        return richItem(Material.CLOCK, component("时间", NamedTextColor.YELLOW), List.of(component(formatHistoryTimeOnly(entry), NamedTextColor.WHITE)));
    }

    private ItemStack historyLocationCard(PlayerHistoryEntry entry) {
        return richItem(Material.COMPASS, component("地点", NamedTextColor.YELLOW), List.of(component(formatHistoryLocationOnly(entry), NamedTextColor.WHITE)));
    }

    private ItemStack historyParticipantCard(MatchParticipantRecord participant) {
        NamedTextColor deltaColor = participant.settlementDelta() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED;
        return richItem(
            Material.PAPER,
            component(normalizeHistoryName(participant.playerName()), NamedTextColor.WHITE),
            List.of(
                themeLine("身份", participant.roleLabel(), NamedTextColor.GOLD),
                themeLine("输赢", formatSigned(participant.settlementDelta()) + normalizeHistoryUnit(participant.unitLabel()), deltaColor)
            )
        );
    }

    private ItemStack historyItem(PlayerHistoryEntry entry) {
        boolean doudizhu = entry.match().gameType().equalsIgnoreCase("DOUDIZHU");
        ItemStack item = new ItemStack(doudizhu ? Material.PAPER : Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(historyTitle(entry));
        meta.lore(historyLore(entry));
        if (plugin.getTableNameKey() != null) {
            meta.getPersistentDataContainer().set(plugin.getTableNameKey(), org.bukkit.persistence.PersistentDataType.STRING, "history:" + entry.matchId());
        }
        item.setItemMeta(meta);
        return item;
    }

    private Component historyTitle(PlayerHistoryEntry entry) {
        boolean doudizhu = entry.match().gameType().equalsIgnoreCase("DOUDIZHU");
        String label = doudizhu ? "斗地主档案" : "德州档案";
        String outcome = entry.self() != null && "WIN".equalsIgnoreCase(entry.self().outcome()) ? "胜利" : "失败";
        String titleGradient = doudizhu ? "<gradient:#f4c27a:#fff1d6>" : "<gradient:#7dcfff:#dbeafe>";
        String outcomeColor = "胜利".equals(outcome) ? "#86efac" : "#fca5a5";
        return MINI.deserialize(titleGradient + "<bold>" + label + "</bold></gradient><dark_gray> | </dark_gray><color:" + outcomeColor + ">" + outcome + "</color>")
            .decoration(TextDecoration.ITALIC, false);
    }

    private List<Component> historyLore(PlayerHistoryEntry entry) {
        List<Component> lore = new java.util.ArrayList<>();
        MatchParticipantRecord self = entry.self();
        boolean doudizhu = entry.match().gameType().equalsIgnoreCase("DOUDIZHU");
        lore.add(themeLine("玩法", doudizhu ? "斗地主" : "德州", doudizhu ? NamedTextColor.GOLD : NamedTextColor.AQUA));
        lore.add(themeLine("结果", self != null && "WIN".equalsIgnoreCase(self.outcome()) ? "胜利" : "失败", self != null && "WIN".equalsIgnoreCase(self.outcome()) ? NamedTextColor.GREEN : NamedTextColor.RED));
        if (self != null) {
            lore.add(MuzTheme.muted("玩家 ")
                .append(plugin.playerIdentityComponent(self.playerId(), self.playerName(), NamedTextColor.WHITE))
                .append(MuzTheme.divider("  · 身份 "))
                .append(MuzTheme.warm(self.roleLabel()))
                .decoration(TextDecoration.ITALIC, false));
        }
        if (doudizhu) {
            lore.add(themeLine("地主", findRole(entry, "地主"), NamedTextColor.GOLD));
            lore.add(themeLine("农民阵营", joinRole(entry, "农民"), NamedTextColor.YELLOW));
        }
        if (self != null) {
            lore.add(themeLine("个人盈亏", formatSigned(self.settlementDelta()) + normalizeHistoryUnit(self.unitLabel()), self.settlementDelta() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED));
        }
        lore.add(MuzTheme.warning("全桌结算"));
        for (MatchParticipantRecord participant : entry.participants()) {
            NamedTextColor deltaColor = participant.settlementDelta() >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED;
            lore.add(MuzTheme.divider("• ")
                .append(plugin.playerIdentityComponent(participant.playerId(), participant.playerName(), NamedTextColor.WHITE))
                .append(MuzTheme.warm(" · " + participant.roleLabel()))
                .append(MuzTheme.named(" · " + formatSigned(participant.settlementDelta()) + normalizeHistoryUnit(participant.unitLabel()), deltaColor))
                .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(themeLine("时间", formatHistoryTimeOnly(entry), NamedTextColor.WHITE));
        lore.add(themeLine("地点", formatHistoryLocationOnly(entry), NamedTextColor.WHITE));
        return lore;
    }

    private Component themeLine(String label, String value, NamedTextColor valueColor) {
        return MuzTheme.muted(label + " ")
            .append(MuzTheme.named(value, valueColor))
            .decoration(TextDecoration.ITALIC, false);
    }

    private String normalizeHistoryName(String name) {
        return name == null || name.isBlank() ? "未知玩家" : name;
    }

    private String findRole(PlayerHistoryEntry entry, String role) {
        return entry.participants().stream()
            .filter(participant -> role.equals(participant.roleLabel()))
            .map(MatchParticipantRecord::playerName)
            .findFirst()
            .map(this::normalizeHistoryName)
            .orElse("未知");
    }

    private String joinRole(PlayerHistoryEntry entry, String role) {
        List<String> names = entry.participants().stream()
            .filter(participant -> role.equals(participant.roleLabel()))
            .map(MatchParticipantRecord::playerName)
            .map(this::normalizeHistoryName)
            .toList();
        return names.isEmpty() ? "无" : String.join("、", names);
    }

    private String formatHistoryTimeOnly(PlayerHistoryEntry entry) {
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        java.time.ZonedDateTime time = java.time.Instant.ofEpochMilli(entry.match().occurredAt()).atZone(java.time.ZoneId.systemDefault());
        return formatter.format(time);
    }

    private String formatHistoryLocationOnly(PlayerHistoryEntry entry) {
        return entry.match().worldName() + " (" + trimDouble(entry.match().x()) + ", " + trimDouble(entry.match().y()) + ", " + trimDouble(entry.match().z()) + ")";
    }

    private String normalizeHistoryUnit(String unitLabel) {
        return unitLabel == null || unitLabel.isBlank() ? "" : unitLabel;
    }

    private String formatSigned(double value) {
        String prefix = value >= 0 ? "+" : "-";
        return prefix + formatMoney(Math.abs(value));
    }

    private String trimDouble(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private String formatMoney(double value) {
        return plugin.formatCompactAmount(value);
    }

    private Component component(String text, NamedTextColor color) {
        return MuzTheme.named(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private void notifySettingSaved(Player player, String text) {
        notifySettingSaved(player, text, true);
    }

    private void notifySettingSaved(Player player, String text, boolean playSound) {
        player.sendActionBar(component("已经记下了 · " + text, NamedTextColor.GREEN));
        if (playSound) {
            player.playSound(player.getLocation(), "minecraft:block.note_block.pling", 0.55f, 1.35f);
        }
    }

    public void previewPlayerOption(Player player, HandInventoryHolder.EditorTarget target, DoudizhuPlugin.PlayActionKind kind, int index) {
        if (target == HandInventoryHolder.EditorTarget.PLAYER_SELECTION) {
            DoudizhuPlugin.SelectionSound sound = plugin.selectionSoundForProfile(index);
            if (sound.volume() <= 0.0f) {
                player.sendActionBar(component("这套方案是静音，不会播放提示音。", NamedTextColor.YELLOW));
                return;
            }
            player.playSound(player.getLocation(), sound.key(), sound.volume(), sound.selectedPitch());
            player.sendActionBar(component("正在试听 · " + selectionSoundDisplayLabel(plugin.getSelectionSoundProfile(index)), NamedTextColor.AQUA));
            return;
        }
        if (target == HandInventoryHolder.EditorTarget.PLAYER_PLAY_ACTION) {
            dev.mumu.doudizhu.action.CeActionExecutor.previewPlayProfile(
                plugin,
                player,
                plugin.getPlayActionProfile(normalizeActionKind(kind), index)
            );
        }
    }

    private String pageTitle(HandInventoryHolder.AdminPage page) {
        return switch (page) {
            case HOME -> "首页";
            case DDZ_HOME -> "斗地主";
            case TEXAS_HOME -> "德州";
            case GLOBAL_HOME -> "通用视觉";
            case GLOBAL_ECONOMY -> "经济与场次";
            case DDZ_FURNITURE, TEXAS_FURNITURE -> "桌椅";
            case DDZ_BUTTONS, TEXAS_BUTTONS -> "按钮";
            case DDZ_CARDS, TEXAS_CARDS -> "卡牌";
            case DDZ_LABELS -> "牌面标签";
            case DDZ_TEXT, TEXAS_TEXT -> "文字标签";
            case DDZ_HITBOX -> "碰撞交互";
            case DDZ_AUDIO -> "音频";
            case DDZ_PLAYER_OPTIONS -> "玩家选项";
            case DDZ_BOTS -> "机器人";
            case GLOBAL_ANIMATION -> "动画";
            case GLOBAL_HIGHLIGHT -> "预选高亮";
            case GLOBAL_AVATARS -> "头像组件";
            case GLOBAL_STATUS_AVATARS -> "顶栏头像";
            case GLOBAL_SEAT_AVATARS -> "座位头像";
        };
    }

    private List<String> adminPageSummary(HandInventoryHolder.AdminPage page) {
        return switch (page) {
            case HOME -> List.of(
                "先选模块，再进入分类。"
            );
            case DDZ_HOME -> List.of(
                "桌椅、按钮、卡牌、文字。"
            );
            case TEXAS_HOME -> List.of(
                "座位、按钮、卡牌、文字。"
            );
            case GLOBAL_HOME -> List.of(
                "两种玩法共用的视觉参数。"
            );
            case GLOBAL_ECONOMY -> List.of(
                "支付、筹码、场次、数据库。"
            );
            case DDZ_FURNITURE -> List.of(
                "外观替换与摆位微调。"
            );
            case DDZ_BUTTONS, TEXAS_BUTTONS -> List.of(
                "按钮距离、高度与悬停。"
            );
            case DDZ_CARDS, TEXAS_CARDS -> List.of(
                "卡牌尺寸、间距与整体排布。"
            );
            case DDZ_LABELS -> List.of(
                "牌上方数字标签单独放在这里。"
            );
            case DDZ_TEXT, TEXAS_TEXT -> List.of(
                "桌面状态、按钮标签、文字高度。"
            );
            case DDZ_HITBOX -> List.of(
                "只影响点击体验。"
            );
            case DDZ_AUDIO -> List.of(
                "音量、倒计时、未准备提醒。"
            );
            case DDZ_PLAYER_OPTIONS -> List.of(
                "玩家音效与动作方案。"
            );
            case DDZ_BOTS -> List.of(
                "机器人延迟与 DeepSeek 调试。"
            );
            case TEXAS_FURNITURE -> List.of(
                "座位圈、按钮位、外圈标记。"
            );
            case GLOBAL_ANIMATION -> List.of(
                "手牌与按钮动画。"
            );
            case GLOBAL_HIGHLIGHT -> List.of(
                "预览色、选中色与默认高亮。"
            );
            case GLOBAL_AVATARS -> List.of(
                "顶栏头像与座位头像。"
            );
            case GLOBAL_STATUS_AVATARS -> List.of(
                "顶部状态头像与名字。"
            );
            case GLOBAL_SEAT_AVATARS -> List.of(
                "椅子外侧头像与名字。"
            );
        };
    }

    private HandInventoryHolder.AdminPage parentPage(HandInventoryHolder.AdminPage page) {
        return switch (page) {
            case HOME -> null;
            case DDZ_HOME, TEXAS_HOME, GLOBAL_HOME, GLOBAL_ECONOMY -> HandInventoryHolder.AdminPage.HOME;
            case DDZ_FURNITURE, DDZ_BUTTONS, DDZ_CARDS, DDZ_LABELS, DDZ_TEXT, DDZ_HITBOX, DDZ_AUDIO, DDZ_PLAYER_OPTIONS, DDZ_BOTS -> HandInventoryHolder.AdminPage.DDZ_HOME;
            case TEXAS_FURNITURE, TEXAS_BUTTONS, TEXAS_CARDS, TEXAS_TEXT -> HandInventoryHolder.AdminPage.TEXAS_HOME;
            case GLOBAL_ANIMATION, GLOBAL_HIGHLIGHT, GLOBAL_AVATARS -> HandInventoryHolder.AdminPage.GLOBAL_HOME;
            case GLOBAL_STATUS_AVATARS, GLOBAL_SEAT_AVATARS -> HandInventoryHolder.AdminPage.GLOBAL_AVATARS;
        };
    }

    private String describeChipItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return "未设置";
        }
        ItemMeta meta = itemStack.getItemMeta();
        String display = meta != null && meta.hasDisplayName()
            ? PlainTextComponentSerializer.plainText().serialize(meta.displayName())
            : itemStack.getType().name();
        return display + " x1";
    }

    private List<Player> onlinePlayersForEconomyPage() {
        return Bukkit.getOnlinePlayers().stream()
            .map(player -> (Player) player)
            .sorted(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
            .limit(7)
            .toList();
    }

    private ItemStack onlineChipItem(Player player) {
        return item(Material.PLAYER_HEAD, player.getName() + " · 筹码 " + plugin.getChipBalance(player.getUniqueId()), List.of(
            "左键 +1，右键 -1，Shift x10。",
            "中键可直接输入精确数值。",
            "用于快速调试在线玩家筹码。"
        ));
    }

    private String bool(boolean value) {
        return value ? "开启" : "关闭";
    }

    private String adminSettingHint(DoudizhuPlugin.AdminSetting setting) {
        return switch (setting) {
            case TABLE_SPAWN_OFFSET_Y -> "整套桌椅一起升降；方块类按整格，家具类高精度。";
            case BUTTON_DISTANCE -> "桌边按钮离桌子的远近。";
            case BUTTON_HEIGHT -> "桌边按钮整体高低。";
            case BUTTON_SCALE -> "桌边按钮图标大小。";
            case PLAYER_HEAD_SCALE -> "牌桌座位头像的显示大小。";
            case PLAYER_HEAD_SHOW_ID -> "打开后显示头像+名字，关闭后只显示头像。";
            case STATUS_AVATAR_SCALE -> "顶部状态头像的显示大小。";
            case STATUS_AVATAR_LATERAL -> "顶部状态头像左右移动。";
            case STATUS_AVATAR_VERTICAL -> "顶部状态头像上下移动。";
            case STATUS_AVATAR_DEPTH -> "顶部状态头像前后移动。";
            case SEAT_AVATAR_SCALE -> "椅子外侧头像的显示大小。";
            case SEAT_AVATAR_LATERAL -> "椅子外侧头像左右移动。";
            case SEAT_AVATAR_VERTICAL -> "椅子外侧头像上下移动。";
            case SEAT_AVATAR_DEPTH -> "椅子外侧头像前后移动。";
            case BUTTON_ROLL_DEGREES -> "按钮图标本身是否翻转。";
            case JOIN_LABEL_HEIGHT -> "空位上方文字离按钮多高。";
            case ACTION_LABEL_HEIGHT -> "普通按钮文字离按钮多高。";
            case BUTTON_FRONT_BASE_DISTANCE -> "正前方座位那排按钮离桌心多远。";
            case BUTTON_SIDE_BASE_DISTANCE -> "左右两侧座位那排按钮离桌心多远。";
            case BUTTON_DISTANCE_FACTOR -> "总按钮距离变化时，额外推开的速度。";
            case BUTTON_SPACING -> "按钮沿弧线展开时的疏密倍率。";
            case BUTTON_ARC_SMALL_ANGLE -> "按钮较少时展开成弧线的角度。";
            case BUTTON_ARC_LARGE_ANGLE -> "按钮较多时展开成弧线的角度。";
            case BUTTON_ARC_SMALL_RADIUS -> "按钮较少时弧线半径。";
            case BUTTON_ARC_LARGE_RADIUS -> "按钮较多时弧线半径。";
            case CHAIR_VISUAL_LATERAL -> "椅子模型左右挪一点。";
            case CHAIR_VISUAL_VERTICAL -> "椅子模型上下挪一点。";
            case CHAIR_ROTATION_DEGREES -> "椅子整体朝向；方块类会自动按 90 度步进。";
            case CHAIR_DISTANCE -> "椅子离桌子的远近；方块类会按一格一格移动。";
            case STATUS_HEIGHT -> "桌面状态文字的高度。";
            case PLAY_DETAIL_HEIGHT -> "上一手提示文字的高度。";
            case PRIVATE_CARD_SCALE -> "手牌整体基础大小。";
            case PUBLIC_TRICK_CARD_SCALE -> "桌中间预览牌的整体基础大小。";
            case PRIVATE_CARD_WIDTH_SCALE -> "只改手牌宽度。";
            case PRIVATE_CARD_HEIGHT_SCALE -> "只改手牌高度。";
            case PRIVATE_CARD_DEPTH_SCALE -> "只改手牌厚度。";
            case PUBLIC_CARD_WIDTH_SCALE -> "只改预览牌宽度。";
            case PUBLIC_CARD_HEIGHT_SCALE -> "只改预览牌高度。";
            case PUBLIC_CARD_DEPTH_SCALE -> "只改预览牌厚度。";
            case HOVER_CARD_SCALE -> "看向手牌时的放大倍数。";
            case HOVER_CARD_LIFT -> "看向手牌时上浮多少。";
            case HOVER_CARD_INTERPOLATION_TICKS -> "手牌预览动画的过渡时长。";
            case HOVER_CARD_ANIMATION_TYPE -> "手牌预览动画使用哪种速度曲线。";
            case HOVER_BUTTON_SCALE -> "看向按钮时的放大倍数。";
            case HOVER_BUTTON_LIFT -> "看向按钮时上浮多少。";
            case HOVER_BUTTON_INTERPOLATION_TICKS -> "按钮预览动画的过渡时长。";
            case HOVER_BUTTON_ANIMATION_TYPE -> "按钮预览动画使用哪种速度曲线。";
            case HAND_SPACING -> "一排手牌之间的左右间距。";
            case PUBLIC_TRICK_SPACING -> "桌中间预览牌之间的间距。";
            case PUBLIC_PREVIEW_ROW_DEPTH_SPACING -> "预览牌多排时前后错开的程度。";
            case CARD_LABEL_HEIGHT -> "牌上数字标签整体高低。";
            case CARD_LABEL_LATERAL -> "牌上数字标签左右移动。";
            case CARD_LABEL_DEPTH -> "牌上数字标签前后移动。";
            case PUBLIC_TRICK_HEIGHT -> "桌中间预览牌离桌面的高度。";
            case CARD_DEPTH_OFFSET -> "相邻手牌前后错开多少，减少闪烁。";
            case GLOBAL_HAND_LATERAL -> "三家的手牌一起左右平移。";
            case GLOBAL_HAND_VERTICAL -> "三家的手牌一起上下平移。";
            case GLOBAL_HAND_DEPTH -> "三家的手牌一起朝桌心或远离桌心。";
            case HOVER_GLOW_ENABLED -> "鼠标指向牌或按钮时是否发光。";
            case HOVER_GLOW_RED -> "预览发光颜色的红色通道。";
            case HOVER_GLOW_GREEN -> "预览发光颜色的绿色通道。";
            case HOVER_GLOW_BLUE -> "预览发光颜色的蓝色通道。";
            case SELECTED_GLOW_ENABLED -> "预选牌是否发光。";
            case SELECTED_GLOW_RED -> "预选发光颜色的红色通道。";
            case SELECTED_GLOW_GREEN -> "预选发光颜色的绿色通道。";
            case SELECTED_GLOW_BLUE -> "预选发光颜色的蓝色通道。";
            case LABELS_ENABLED -> "牌面数字标签总开关。";
            case DUPLICATE_ONLY -> "只给重复点数的牌显示标签。";
            case BUTTON_HITBOX_LATERAL -> "按钮点击范围左右微调。";
            case BUTTON_HITBOX_DEPTH -> "按钮点击范围前后微调。";
            case BUTTON_HITBOX_VERTICAL -> "按钮点击范围上下微调。";
            case BUTTON_HITBOX_WIDTH -> "按钮点击范围宽度。";
            case BUTTON_HITBOX_HEIGHT -> "按钮点击范围高度。";
            case CARD_HITBOX_LATERAL -> "手牌点击范围左右微调。";
            case CARD_HITBOX_DEPTH -> "手牌点击范围前后微调。";
            case CARD_HITBOX_VERTICAL -> "手牌点击范围上下微调。";
            case CARD_HITBOX_LENGTH -> "手牌点击范围前后长度。";
            case CARD_HITBOX_WIDTH -> "手牌点击范围左右宽度。";
            case CARD_HITBOX_HEIGHT -> "手牌点击范围高度。";
            case CHAIR_HITBOX_LATERAL -> "椅子点击范围左右微调。";
            case CHAIR_HITBOX_VERTICAL -> "椅子点击范围上下微调。";
            case CHAIR_HITBOX_WIDTH -> "椅子点击范围宽度。";
            case CHAIR_HITBOX_HEIGHT -> "椅子点击范围高度。";
            case BGM_VOLUME -> "背景音乐音量。";
            case EFFECT_VOLUME -> "出牌和提示音量。";
            case TURN_COUNTDOWN_SECONDS -> "一回合最多等多久。";
            case BOT_DELAY_MIN -> "机器人最短思考时间。";
            case BOT_DELAY_MAX -> "机器人最长思考时间。";
            case HINT_GROUP_LIMIT -> "提示按钮最多轮播多少组建议。";
            case TEXAS_SPAWN_FURNITURE -> "德州桌是否生成桌子和椅子实体。";
            case TEXAS_SEAT_DISTANCE -> "德州一圈座位整体离中心多远。";
            case TEXAS_SEAT_LABEL_HEIGHT -> "德州座位信息文字高度。";
            case TEXAS_JOIN_BUTTON_HEIGHT -> "德州空位加入按钮高度。";
            case TEXAS_STATUS_HEIGHT -> "德州桌中央状态文字高度。";
            case TEXAS_ACTION_BUTTON_HEIGHT -> "德州操作按钮所在一排的高度。";
            case TEXAS_ACTION_BUTTON_STEP -> "德州操作按钮之间的间距。";
            case TEXAS_COMMUNITY_CARD_HEIGHT -> "德州公共牌高度。";
            case TEXAS_COMMUNITY_CARD_SPACING -> "德州公共牌之间的间距。";
            case TEXAS_HOLE_CARD_HEIGHT -> "德州玩家手牌高度。";
            case TEXAS_HOLE_CARD_SPACING -> "德州同一玩家两张牌的间距。";
            case TEXAS_HOLE_RADIUS_FACTOR -> "德州玩家手牌离各自座位的外扩系数。";
            case TEXAS_DEALER_MARKER_HEIGHT -> "德州按钮位标记高度。";
            case TEXAS_DEALER_MARKER_RADIUS_FACTOR -> "德州按钮位标记离座位的外扩系数。";
        };
    }

    private ItemStack roomLevelItem(Material material, dev.mumu.doudizhu.room.TableLevel level) {
        return item(material, plugin.roomDisplayTag(level), List.of(
            "入场门槛: " + plugin.roomEntryRequirement(level),
            "左键增加倍率，右键降低倍率。",
            "Shift 会按 10 倍步长调整。"
        ));
    }

    private ItemStack roomLevelToggleItem(Material material, dev.mumu.doudizhu.room.TableLevel level) {
        return item(material, plugin.roomDisplayLabel(level) + "经济: " + bool(plugin.isRoomLevelEconomyConfigured(level)), List.of(
            "点击切换该场次是否启用经济。",
            "关闭后该场次不会扣费。"
        ));
    }

    private record SoundPreset(Material material, String title, String spec, String description) {
    }

    private record ActionPreset(Material material, String title, String spec, String description) {
    }

    private record InputSession(HandInventoryHolder.EditorTarget target, int profileIndex, UUID targetPlayerId, DoudizhuPlugin.PlayActionKind actionKind) {
    }

    private record SignInputSession(HandInventoryHolder.EditorTarget target, long openedAt, Object unused) {
    }
}

