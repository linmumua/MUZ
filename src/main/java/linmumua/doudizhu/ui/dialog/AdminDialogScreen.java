package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.DoudizhuPlugin.FurnitureType;
import linmumua.doudizhu.DoudizhuPlugin.PlayActionKind;
import linmumua.doudizhu.room.TableLevel;
import linmumua.doudizhu.ui.HandInventoryHolder.AdminPage;
import linmumua.doudizhu.ui.MuzTheme;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 管理菜单十九页的 Dialog 版：页面渲染 + 导航。
 *
 * 页内按钮完全由 {@link AdminMenuModel} 声明（旧箱子版 {@code HandGuiService.openAdminModels}
 * 画、{@code HandGuiListener.handleAdminClick} 处理的同一套动作），本类只负责把
 * 每个 {@link AdminMenuModel.Kind} 落到实际的语义：
 * <ul>
 *   <li>数值/布尔调节 → push {@link AdminSettingDialogScreen}。</li>
 *   <li>声音/动作/颜色/AI 编辑 → push dialog-personal 的编辑屏（父表：这些编辑屏归 personal）。</li>
 *   <li>家具外观、支付模式、筹码模板、场次倍率、在线玩家筹码 → 直接调既有 plugin 方法。</li>
 * </ul>
 *
 * 打开前与每次回调的 {@code muz.admin} 复核、会话代次、player lane 由 core 负责；
 * 本类在回调里只读实时玩家状态（主手物品、在线列表）并调既有 plugin 方法，
 * 不自己排调度任务，失败一律经 {@code ctx.error} 明确提示。
 */
public final class AdminDialogScreen implements MuzDialogScreen {
    /** 经济页最多列出多少位在线玩家的筹码速调入口（沿用旧 GUI 的 7 人上限）。 */
    private static final int MAX_CHIP_TARGETS = 7;

    private final DoudizhuPlugin plugin;
    private final AdminPage page;

    public AdminDialogScreen(DoudizhuPlugin plugin, AdminPage page) {
        this.plugin = plugin;
        this.page = page;
    }

    /** 管理菜单根屏。由 core 的 {@code /muz admin} 入口构造。 */
    public static AdminDialogScreen home(DoudizhuPlugin plugin) {
        return new AdminDialogScreen(plugin, AdminPage.HOME);
    }

    @Override
    public String screenKey() {
        return page == AdminPage.HOME ? "admin.home" : "admin.page:" + page.name();
    }

    @Override
    public String permission() {
        return "muz.admin";
    }

    @Override
    public Dialog build(MuzDialogContext ctx) {
        if (!ctx.hasPermission("muz.admin")) {
            // core 打开前已复核；这里做第二道保险，返回一个只读提示屏而不是管理内容。
            return MuzDialogFactory.screen(
                MuzTheme.danger("需要管理员权限"),
                true,
                List.of(MuzDialogFactory.body(MuzTheme.muted("这个菜单需要管理员权限才能打开。"))),
                List.of(),
                List.of(),
                ctx.exit(MuzTheme.danger("关闭")),
                1
            );
        }
        AdminMenuModel.Page model = AdminMenuModel.page(page);

        List<DialogBody> body = new ArrayList<>();
        body.add(MuzDialogFactory.body(MuzTheme.accent("管理 · " + model.title())));
        for (String note : model.notes()) {
            body.add(MuzDialogFactory.body(MuzTheme.muted(note)));
        }
        appendPageSummary(ctx, body);

        List<ActionButton> actions = new ArrayList<>();
        for (AdminMenuModel.Entry entry : model.entries()) {
            appendEntry(ctx, actions, entry);
        }

        if (model.parent() != null) {
            actions.add(ctx.action(MuzTheme.warm("返回"), () -> ctx.back()));
        }
        actions.add(ctx.action(MuzTheme.warm("管理首页"), () -> ctx.service().openRoot(ctx.playerId(), home(plugin))));
        actions.add(ctx.action(MuzTheme.warning("重新载入"), () -> {
            if (!ctx.hasPermission("muz.admin")) {
                ctx.error("这个菜单需要管理员权限才能打开。");
                return;
            }
            plugin.reloadPluginState(ctx.player());
            ctx.open(this);
        }));

        return MuzDialogFactory.screen(
            MuzTheme.accent("管理菜单 · " + model.title()),
            true,
            body,
            List.of(),
            actions,
            ctx.exit(MuzTheme.danger("关闭")),
            2
        );
    }

    /** 首页与经济页的实时概览行，对应旧 GUI 的 noteItem 概览。 */
    private void appendPageSummary(MuzDialogContext ctx, List<DialogBody> body) {
        if (page == AdminPage.HOME) {
            // 与 TableGadgetBarHudService.hudBarSnapshot / TableGadgetService.tick 同口径：
            // 表格服务尚未装配或已拆解时按 0 张展示，不让摘要把整屏管理菜单打成打不开。
            linmumua.doudizhu.game.TableManager manager = plugin.getTableManager();
            int tableCount = manager == null ? 0 : manager.getTables().size();
            body.add(MuzDialogFactory.body(MuzTheme.accent("斗地主 " + tableCount + " 张")));
            body.add(MuzDialogFactory.body(MuzTheme.muted("支付模式 · " + (plugin.isChipPaymentEnabled() ? "筹码" : "金币"))));
        } else if (page == AdminPage.GLOBAL_ECONOMY) {
            body.add(MuzDialogFactory.body(MuzTheme.muted("当前 Provider · " + plugin.vaultProviderSummary())));
            body.add(MuzDialogFactory.body(MuzTheme.muted("已注册 · " + plugin.vaultProvidersSummary())));
            body.add(MuzDialogFactory.body(MuzTheme.muted("偏好顺序 · " + plugin.vaultPreferredProvidersSummary())));
            body.add(MuzDialogFactory.body(MuzTheme.muted("数据库 · " + plugin.databaseStatusSummary())));
            body.add(MuzDialogFactory.body(MuzTheme.muted("筹码占位符 · %muz_chip_<玩家>%")));
            body.add(MuzDialogFactory.body(MuzTheme.muted("当前筹码物品 · " + describeChipItem(plugin.chipPaymentItem()))));
        } else if (page == AdminPage.DDZ_BOTS) {
            body.add(MuzDialogFactory.body(MuzTheme.muted(plugin.isBotAiEnabled()
                ? "AI 出牌已启用，异常时自动切回内置机器人。"
                : "使用内置机器人出牌，会留炸弹和王炸。")));
        } else if (page == AdminPage.DDZ_AI) {
            body.add(MuzDialogFactory.body(MuzTheme.muted("AI 状态 · " + plugin.aiStatusSummary())));
        }
    }

    private void appendEntry(MuzDialogContext ctx, List<ActionButton> actions, AdminMenuModel.Entry entry) {
        switch (entry.kind()) {
            case OPEN_PAGE -> {
                AdminPage target = (AdminPage) entry.target();
                actions.add(ctx.action(MuzTheme.accent(entry.label()), () -> ctx.push(new AdminDialogScreen(plugin, target))));
            }
            case SETTING -> {
                linmumua.doudizhu.DoudizhuPlugin.AdminSetting setting =
                    (linmumua.doudizhu.DoudizhuPlugin.AdminSetting) entry.target();
                String value = plugin.adminSettingValue(setting);
                actions.add(ctx.action(MuzTheme.accent(setting.label() + " · " + value),
                    () -> ctx.push(new AdminSettingDialogScreen(plugin, setting))));
            }
            case SELECTION_SOUND_EDITOR -> {
                int index = (Integer) entry.target();
                actions.add(ctx.action(MuzTheme.accent(entry.label()), () -> ctx.push(selectionSoundEditor(index))));
            }
            case SELECTION_SOUND_RESTORE -> {
                int index = (Integer) entry.target();
                actions.add(ctx.action(MuzTheme.muted(entry.label()), () -> {
                    if (!admin(ctx)) {
                        return;
                    }
                    plugin.setSelectionSoundProfileDefinition(index, plugin.defaultSelectionSoundProfile(index));
                    notify(ctx, "选牌音效方案 " + (index + 1) + " 已经恢复默认");
                    ctx.open(this);
                }));
            }
            case PLAY_ACTION_KIND -> {
                PlayActionKind kind = (PlayActionKind) entry.target();
                actions.add(ctx.action(MuzTheme.accent(entry.label()), () -> ctx.push(playActionKind(kind))));
            }
            case SOUND_PICKER -> {
                AdminMenuModel.SoundTarget target = (AdminMenuModel.SoundTarget) entry.target();
                actions.add(ctx.action(MuzTheme.accent(entry.label()), () -> ctx.push(soundPicker(target))));
            }
            case AI_FIELD -> {
                AdminMenuModel.AiField field = (AdminMenuModel.AiField) entry.target();
                actions.add(ctx.action(MuzTheme.accent(entry.label()), () -> ctx.push(aiField(field))));
            }
            case GLOBAL_COLOR -> {
                AdminMenuModel.GlobalColor color = (AdminMenuModel.GlobalColor) entry.target();
                String value = color == AdminMenuModel.GlobalColor.HOVER_GLOW
                    ? plugin.hoverGlowColorLabel()
                    : plugin.selectedGlowColorLabel();
                actions.add(ctx.action(MuzTheme.accent(entry.label() + " · " + value), () -> ctx.push(globalColor(color))));
            }
            case FURNITURE_APPLY -> {
                FurnitureType type = (FurnitureType) entry.target();
                actions.add(ctx.action(MuzTheme.accent(entry.label()), () -> applyHeldItemToFurniture(ctx, type)));
            }
            case FURNITURE_RESET -> {
                FurnitureType type = (FurnitureType) entry.target();
                actions.add(ctx.action(MuzTheme.accent(entry.label()), () -> {
                    if (!admin(ctx)) {
                        return;
                    }
                    plugin.resetFurnitureDisplayItem(type);
                    notify(ctx, type.label() + "外观已经恢复默认");
                    ctx.open(this);
                }));
            }
            case PAYMENT_TOGGLE -> actions.add(ctx.action(MuzTheme.accent(entry.label() + " · " + plugin.paymentModeLabel()), () -> {
                if (!admin(ctx)) {
                    return;
                }
                plugin.setChipPaymentEnabled(!plugin.isChipPaymentEnabled());
                notify(ctx, "支付模式已经切到" + (plugin.isChipPaymentEnabled() ? "筹码" : "金币"));
                ctx.open(this);
            }));
            case CHIP_TEMPLATE_SET -> actions.add(ctx.action(MuzTheme.accent(entry.label()), () -> {
                if (!admin(ctx)) {
                    return;
                }
                ItemStack item = ctx.player().getInventory().getItemInMainHand();
                if (item == null || item.getType().isAir()) {
                    ctx.error("请先把要作为筹码的物品拿在主手。");
                    return;
                }
                plugin.setChipPaymentItem(item);
                notify(ctx, "实体筹码匹配模板已保存；不会自动兑换或发放物品");
                ctx.open(this);
            }));
            case CHIP_TEMPLATE_RESET -> actions.add(ctx.action(MuzTheme.accent(entry.label()), () -> {
                if (!admin(ctx)) {
                    return;
                }
                plugin.setChipPaymentItem(null);
                notify(ctx, "筹码外观已经恢复默认");
                ctx.open(this);
            }));
            case ROOM_MULTIPLIER -> {
                TableLevel level = (TableLevel) entry.target();
                actions.add(ctx.action(MuzTheme.accent(entry.label() + " ＋"), () -> adjustRoomMultiplier(ctx, level, true, 1)));
                actions.add(ctx.action(MuzTheme.accent(entry.label() + " －"), () -> adjustRoomMultiplier(ctx, level, false, 1)));
                actions.add(ctx.action(MuzTheme.muted(entry.label() + " ＋×10"), () -> adjustRoomMultiplier(ctx, level, true, 10)));
                actions.add(ctx.action(MuzTheme.muted(entry.label() + " －×10"), () -> adjustRoomMultiplier(ctx, level, false, 10)));
            }
            case ROOM_ECONOMY -> {
                TableLevel level = (TableLevel) entry.target();
                actions.add(ctx.action(MuzTheme.accent(entry.label() + " · " + (plugin.isRoomLevelEconomyConfigured(level) ? "开" : "关")), () -> {
                    if (!admin(ctx)) {
                        return;
                    }
                    boolean enabled = plugin.toggleRoomLevelEconomy(level);
                    notify(ctx, plugin.roomDisplayLabel(level) + "经济现在已" + (enabled ? "开启" : "关闭"));
                    ctx.open(this);
                }));
            }
            case CHIP_BALANCE_PANEL -> appendChipBalanceButtons(ctx, actions);
            case CHAIR_PANEL_LAYOUT -> actions.add(ctx.action(MuzTheme.accent(entry.label()), () -> {
                // 布局存储未装配时明确提示，不让玩家点进一个 NPE。
                ChairPanelLayoutPort port = plugin.chairPanelLayoutPort();
                if (port == null) {
                    ctx.error("面板布局存储尚未装配，稍后再试（详情见服务端日志）。");
                    return;
                }
                ctx.push(new ChairPanelLayoutDialogScreen(port));
            }));
        }
    }

    /**
     * 在线玩家筹码速调，对应旧 GUI 的 slot45..51（左 +1 / 右 −1 / 中键精确输入）。
     *
     * 在线名单消费连接生命周期维护的**不可变 UUID 快照**（{@code playerPresence}），
     * 不再在管理者 lane 枚举在线玩家——区域化核心上在管理者 lane 遍历玩家列表不安全，
     * 这是父叙述者点名的要求。
     */
    private void appendChipBalanceButtons(MuzDialogContext ctx, List<ActionButton> actions) {
        List<UUID> online = onlineTargetIds();
        if (online.isEmpty()) {
            actions.add(ctx.action(MuzTheme.muted("暂无在线玩家"), () -> { }));
            return;
        }
        int rendered = 0;
        for (UUID targetId : online) {
            if (rendered++ >= MAX_CHIP_TARGETS) {
                break;
            }
            String name = targetName(targetId);
            actions.add(ctx.action(Component.text(name + " ＋1"), () -> adjustChip(ctx, targetId, name, 1)));
            actions.add(ctx.action(Component.text(name + " －1"), () -> adjustChip(ctx, targetId, name, -1)));
            actions.add(ctx.action(MuzTheme.muted(name + " · 精确…"), () -> {
                if (!admin(ctx)) {
                    return;
                }
                ctx.push(new ChipBalanceScreen(plugin, targetId, name));
            }));
        }
    }

    /**
     * 调整某位在线玩家的筹码。
     *
     * 筹码读写落在 PhysicalChipService，其 {@code requireOwnerLane} 在区域化核心上会拒绝
     * 「当前 lane 不拥有目标玩家 region」的调用（连只读也拒）。因此必须把操作投递到**目标玩家
     * 自己的 player lane**（{@code enqueuePlayer(targetId, …)}），完成后由 core 的 service 把
     * 提示与重绘再投回管理者 lane。绝不从管理者 lane 直接改别人背包。
     */
    private void adjustChip(MuzDialogContext ctx, UUID targetId, String name, int delta) {
        if (!admin(ctx)) {
            return;
        }
        output().enqueuePlayer(targetId, target -> {
            int next;
            try {
                next = plugin.adjustChipBalance(targetId, delta);
            } catch (RuntimeException failure) {
                ctx.error(name + " 的筹码调整失败：" + failureMessage(failure));
                return;
            }
            ctx.message(MuzTheme.success("已经记下了 · " + name + " 的筹码现在是 " + next));
            // 刷新管理者当前屏（重绘由 core 投回管理者 lane）。
            ctx.service().refresh(ctx.playerId());
        });
    }

    /**
     * 在线玩家的不可变 UUID 快照（连接生命周期维护的 playerPresence，见 PhysicalTableManager）。
     *
     * 只读快照、任何 lane 安全；**不**调在线玩家枚举 API。
     */
    private List<UUID> onlineTargetIds() {
        List<UUID> snapshot = plugin.onlinePlayerIdsSnapshot();
        return snapshot == null ? List.of() : snapshot;
    }

    /** 目标玩家显示名；查不到时回落到 UUID 前 8 位。 */
    private String targetName(UUID targetId) {
        String name = plugin.resolvePlayerName(targetId);
        if (name == null || name.isBlank()) {
            return targetId.toString().substring(0, 8);
        }
        return name;
    }

    private static String failureMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    /** 场次倍率加减，步长沿用旧 GUI（FUN 固定 10，其余至少 10，且随当前值放大）。 */
    private void adjustRoomMultiplier(MuzDialogContext ctx, TableLevel level, boolean increase, int multiplier) {
        if (!admin(ctx)) {
            return;
        }
        double current = plugin.roomMultiplier(level);
        double step = Math.max(1.0, level == TableLevel.FUN ? 10.0 : Math.max(10.0, current / 10.0));
        double delta = step * Math.max(1, multiplier);
        double next = Math.max(0.0, current + (increase ? delta : -delta));
        plugin.setRoomLevelMultiplier(level, next);
        notify(ctx, plugin.roomDisplayLabel(level) + "倍率现在是 " + plugin.formatMultiplier(next));
        ctx.open(this);
    }

    private void applyHeldItemToFurniture(MuzDialogContext ctx, FurnitureType type) {
        if (!admin(ctx)) {
            return;
        }
        ItemStack item = ctx.player().getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            ctx.error("先把你想拿来当" + type.label() + "外观的物品放到主手里。");
            return;
        }
        if (type == FurnitureType.CHAIR && !plugin.canUseHeldItemAsChairFurniture(item)) {
            ctx.error("椅子只支持 CE 家具、CE 方块物品或原版方块，先把对应物品拿到主手。");
            return;
        }
        plugin.setFurnitureDisplayItem(type, item);
        notify(ctx, type.label() + "外观已经换成主手物品");
        ctx.open(this);
    }

    /** 玩家输出门面：目标玩家的筹码读写一律经它投递到目标自己的 player lane。 */
    private linmumua.doudizhu.game.PlayerOutputDispatcher output() {
        return plugin.getActionBarOverlayService().outputDispatcher();
    }

    private boolean admin(MuzDialogContext ctx) {
        if (ctx.hasPermission("muz.admin")) {
            return true;
        }
        ctx.error("这个菜单需要管理员权限才能打开。");
        return false;
    }

    private void notify(MuzDialogContext ctx, String text) {
        ctx.message(MuzTheme.success("已经记下了 · " + text));
        ctx.player().playSound(ctx.player().getLocation(), "minecraft:block.note_block.pling", 0.55f, 1.35f);
    }

    private String describeChipItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return "未设置";
        }
        return itemStack.getType().name() + "（完整元数据，1件=1筹码）";
    }

    // ————————————————————————————————————————————————————————————————
    // 集成接缝：管理页把「声音/动作/颜色/AI 编辑」导航交给 dialog-personal 的编辑屏
    // （父叙述者最终归属表：这些编辑屏归 personal，每回调复核 muz.admin、回原管理页）。
    // 下面每个方法只构造并返回对方的 MuzDialogScreen，签名与 personal 已落盘的工厂一致
    // （SoundActionDialogScreen.*(plugin, …)、AiSettingsDialogScreen.forField(plugin, AiField)、
    // PlayerSettingsDialogScreen.ColorScreen(plugin, ColorTarget)）。这里是唯一的跨屏耦合点：
    // 对方工厂若改名，只需改动这几行。
    // ————————————————————————————————————————————————————————————————

    private MuzDialogScreen selectionSoundEditor(int profileIndex) {
        return SoundActionDialogScreen.selectionSoundEditor(plugin, profileIndex);
    }

    private MuzDialogScreen playActionKind(PlayActionKind kind) {
        return SoundActionDialogScreen.playActionKind(plugin, kind);
    }

    private MuzDialogScreen soundPicker(AdminMenuModel.SoundTarget target) {
        return switch (target) {
            case COUNTDOWN -> SoundActionDialogScreen.countdownSound(plugin);
            case UNREADY_WARNING -> SoundActionDialogScreen.unreadyWarningSound(plugin);
            case PLACEMENT_BLOCKED -> SoundActionDialogScreen.placementBlockedSound(plugin);
        };
    }

    private MuzDialogScreen aiField(AdminMenuModel.AiField field) {
        return AiSettingsDialogScreen.field(plugin, field.name());
    }

    private MuzDialogScreen globalColor(AdminMenuModel.GlobalColor color) {
        return PlayerSettingsDialogScreen.globalColor(plugin, color.name());
    }

    /**
     * 精确筹码输入屏（ADMIN_CHIP_BALANCE，属本代理），嵌在本文件里避免多开文件。
     *
     * 对应旧 GUI 中键打开、输入非负整数后 `plugin.setChipBalance`。
     */
    static final class ChipBalanceScreen implements MuzDialogScreen {
        private final DoudizhuPlugin plugin;
        private final UUID targetId;
        private final String targetName;

        ChipBalanceScreen(DoudizhuPlugin plugin, UUID targetId, String targetName) {
            this.plugin = plugin;
            this.targetId = targetId;
            this.targetName = targetName;
        }

        @Override
        public String screenKey() {
            return "admin.chip:" + targetId;
        }

        @Override
        public String permission() {
            return "muz.admin";
        }

        @Override
        public Dialog build(MuzDialogContext ctx) {
            // 不在这里读目标筹码：getChipBalance 与 setChipBalance 一样带 owner-lane 门禁，
            // 管理者 lane 读别人筹码会抛。当前数量改为确认后从目标 lane 反馈。
            List<DialogBody> body = new ArrayList<>();
            body.add(MuzDialogFactory.body(MuzTheme.accent(targetName + " · 精确设置筹码")));
            body.add(MuzDialogFactory.body(MuzTheme.muted("输入非负整数并确认；不会自动兑换或发放物品。")));
            body.add(MuzDialogFactory.body(MuzTheme.muted("当前数量在确认时会从该玩家自己的 lane 读取并反馈。")));

            List<DialogInput> inputs = List.of(
                MuzDialogFactory.textInput("value", Component.text("新的筹码数量"), "", 12)
            );
            List<ActionButton> actions = new ArrayList<>();
            actions.add(ctx.action(MuzTheme.accent("确认"), view -> {
                if (!ctx.hasPermission("muz.admin")) {
                    ctx.error("这个菜单需要管理员权限才能打开。");
                    return;
                }
                int value;
                try {
                    value = DialogInputParsers.parseRequiredInt(ctx.text(view, "value"));
                    if (value < 0) {
                        throw new IllegalArgumentException("筹码数量不能为负数。");
                    }
                } catch (IllegalArgumentException exception) {
                    ctx.error(exception.getMessage());
                    return;
                }
                // 写别人筹码必须投递到目标自己的 player lane（owner-lane 门禁）。
                plugin.getActionBarOverlayService().outputDispatcher().enqueuePlayer(targetId, target -> {
                    int actual;
                    try {
                        actual = plugin.setChipBalance(targetId, value);
                    } catch (RuntimeException failure) {
                        String detail = failure.getMessage();
                        ctx.error(targetName + " 的筹码设置失败："
                            + (detail == null || detail.isBlank() ? failure.getClass().getSimpleName() : detail));
                        return;
                    }
                    ctx.message(MuzTheme.success("已经记下了 · " + targetName + " 的筹码现在是 " + actual));
                    ctx.back();
                });
            }));
            actions.add(ctx.action(MuzTheme.warm("返回"), () -> ctx.back()));

            return MuzDialogFactory.screen(
                MuzTheme.accent("精确设置筹码"),
                true,
                body,
                inputs,
                actions,
                ctx.exit(MuzTheme.danger("关闭")),
                2
            );
        }
    }
}
