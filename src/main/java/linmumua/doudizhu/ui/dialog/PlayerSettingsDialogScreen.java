package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.GameTable;
import linmumua.doudizhu.ui.MuzTheme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * 个人设置 Dialog（对应旧 {@code HandGuiService.openSettings} / {@code ViewMode.SETTINGS}）。
 *
 * <p>这一屏把旧的九格箱子「个人设置」整页搬到原生 Dialog：点数标签开关、选牌音效方案入口、
 * 出牌动作方案入口、预览色 / 选中色 RGB 输入、恢复默认、关闭。旧箱子里的左键 / 右键语义
 * （左键试听并切换、右键只切换）在任何 Dialog 里都不存在，因此子页把「试听」与「启用」
 * 拆成两个显式按钮，见 {@link SoundActionDialogScreen}。
 *
 * <p>【权限】个人侧无权限门槛，因此 {@link #permission()} 保持默认 {@code null}；
 * 全局颜色编辑屏（{@link ColorScreen} 的全局目标）单独返回 {@code muz.admin}。
 *
 * <p>【owner lane / 代次】本屏的 {@code build} 与所有按钮回调都由 core 在 player lane 内调用，
 * 且 core 已复核「权限 + 会话代次 + 关闭期」；因此这里不自己切线程、不自己判权限。
 * 提示统一走 {@code ctx.message/ctx.error}，试听音效走既有 player lane 出口
 * （{@link linmumua.doudizhu.game.PlayerOutputDispatcher#playSound}），不直接调用裸 Player API。
 */
public final class PlayerSettingsDialogScreen implements MuzDialogScreen {

    /** 个人设置主页的稳定唯一 id。 */
    public static final String KEY = "settings";

    private final DoudizhuPlugin plugin;

    public PlayerSettingsDialogScreen(DoudizhuPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public String screenKey() {
        return KEY;
    }

    @Override
    public Dialog build(MuzDialogContext ctx) {
        UUID playerId = ctx.playerId();

        boolean cardLabels = plugin.isCardLabelsEnabledFor(playerId);
        DoudizhuPlugin.OptionProfile selectionProfile =
            plugin.getSelectionSoundProfile(plugin.getPlayerSelectionSoundProfileIndex(playerId));
        String previewColor = playerPreviewColorDisplayLabel(playerId);
        String selectedColor = playerSelectionColorDisplayLabel(playerId);

        List<DialogBody> body = new ArrayList<>();
        body.add(MuzDialogFactory.body(MuzTheme.accent("个人设置")));
        body.add(MuzDialogFactory.body(MuzTheme.muted("点数标签 · " + (cardLabels ? "开启" : "关闭"))));
        body.add(MuzDialogFactory.body(MuzTheme.muted(
            "选牌音效 · " + SoundActionDialogScreen.selectionSoundDisplayLabel(selectionProfile))));
        body.add(MuzDialogFactory.body(MuzTheme.muted(
            "预览色 · " + previewColor + "　选中色 · " + selectedColor)));

        List<ActionButton> actions = new ArrayList<>();
        actions.add(ctx.action(
            noItalic(MuzTheme.accent("点数标签 · " + (cardLabels ? "开启" : "关闭"))),
            () -> {
                boolean now = plugin.toggleCardLabelsFor(playerId);
                ctx.message(MuzTheme.success("点数标签现在已" + (now ? "开启" : "关闭")));
                refreshTable(playerId);
                ctx.service().refresh(playerId);
            }
        ));
        actions.add(ctx.action(
            noItalic(MuzTheme.accent("选牌音效 · "
                + SoundActionDialogScreen.selectionSoundDisplayLabel(selectionProfile))),
            () -> ctx.push(SoundActionDialogScreen.playerSelection(plugin))
        ));
        actions.add(ctx.action(
            noItalic(MuzTheme.accent("出牌动作")),
            () -> ctx.push(SoundActionDialogScreen.playerActionKindMenu(plugin))
        ));
        actions.add(ctx.action(
            noItalic(MuzTheme.accent("预览色 · " + previewColor)),
            () -> ctx.push(new ColorScreen(plugin, ColorTarget.PLAYER_PREVIEW))
        ));
        actions.add(ctx.action(
            noItalic(MuzTheme.accent("选中色 · " + selectedColor)),
            () -> ctx.push(new ColorScreen(plugin, ColorTarget.PLAYER_SELECTED))
        ));
        actions.add(ctx.action(
            noItalic(MuzTheme.warning("恢复默认")),
            () -> {
                plugin.resetPlayerVisualSettings(playerId);
                ctx.message(MuzTheme.success("个人显示已经恢复成默认样子"));
                refreshTable(playerId);
                ctx.service().refresh(playerId);
            }
        ));

        ActionButton exit = ctx.exit(noItalic(MuzTheme.muted("关闭")));
        return MuzDialogFactory.screen(
            noItalic(MuzTheme.accent("个人设置")),
            true,
            body,
            List.of(),
            actions,
            exit,
            2
        );
    }

    /**
     * 个人预览色 / 选中色的展示名。
     *
     * <p>旧实现（{@code HandGuiService} 私有方法）在玩家未覆盖时展示「跟随全局 · <全局标签>」——
     * 该文案逻辑原样保留，保证显示等价。
     */
    private String playerPreviewColorDisplayLabel(UUID playerId) {
        String label = plugin.previewGlowColorLabel(playerId);
        return "默认(全局)".equals(label) ? "跟随全局 · " + plugin.hoverGlowColorLabel() : label;
    }

    private String playerSelectionColorDisplayLabel(UUID playerId) {
        String label = plugin.selectionGlowColorLabel(playerId);
        return "默认(全局)".equals(label) ? "跟随全局 · " + plugin.selectedGlowColorLabel() : label;
    }

    /** 设置变更后按既有路径刷新该玩家所在牌桌；不在牌桌内则无事发生。 */
    private void refreshTable(UUID playerId) {
        GameTable table = plugin.getTableManager().getTableOf(playerId);
        if (table != null) {
            plugin.getPhysicalTableManager().refresh(table);
        }
    }

    static Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 管理侧导航入口：按字符串键取全局颜色编辑屏（{@code HOVER_GLOW} / {@code SELECTED_GLOW}，
     * 大小写不敏感）。未知键抛 {@link IllegalArgumentException}，不静默回退。
     */
    public static ColorScreen globalColor(DoudizhuPlugin plugin, String key) {
        String normalized = key == null ? "" : key.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "HOVER_GLOW" -> new ColorScreen(plugin, ColorTarget.GLOBAL_HOVER);
            case "SELECTED_GLOW" -> new ColorScreen(plugin, ColorTarget.GLOBAL_SELECTED);
            default -> throw new IllegalArgumentException("未知的全局颜色键：" + key);
        };
    }

    /** 颜色输入的目标：个人两色（无权限门槛）+ 全局两色（{@code muz.admin}）。 */
    public enum ColorTarget {
        PLAYER_PREVIEW("settings.color.preview", null, "预览色"),
        PLAYER_SELECTED("settings.color.selected", null, "选中色"),
        GLOBAL_HOVER("admin.color.hover", "muz.admin", "全局预览色"),
        GLOBAL_SELECTED("admin.color.selected", "muz.admin", "全局选择色");

        private final String key;
        private final String permission;
        private final String label;

        ColorTarget(String key, String permission, String label) {
            this.key = key;
            this.permission = permission;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String permission() {
            return permission;
        }

        public String label() {
            return label;
        }

        public boolean personal() {
            return this == PLAYER_PREVIEW || this == PLAYER_SELECTED;
        }
    }

    /**
     * RGB 颜色输入屏（个人预览/选中色 + 全局预览/选择色共用）。
     *
     * <p>旧实现走「聊天栏输入」，Dialog 版改用对话框内的文本输入框。输入格式与旧解析完全一致
     * （{@code 255,226,92} 或 {@code F9B5B5}），直接复用 core 的
     * {@link DialogInputParsers#parseRgbRequired(String)} 与 {@link DialogInputParsers#formatRgb(int, int, int)}，
     * 不另写一套解析。非法输入明确报错并保持本屏，不静默钳值。
     */
    public static final class ColorScreen implements MuzDialogScreen {

        private final DoudizhuPlugin plugin;
        private final ColorTarget target;

        public ColorScreen(DoudizhuPlugin plugin, ColorTarget target) {
            this.plugin = Objects.requireNonNull(plugin, "plugin");
            this.target = Objects.requireNonNull(target, "target");
        }

        @Override
        public String screenKey() {
            return target.key();
        }

        @Override
        public String permission() {
            return target.permission();
        }

        @Override
        public Dialog build(MuzDialogContext ctx) {
            UUID playerId = ctx.playerId();
            org.bukkit.Color current = currentColor(playerId);
            String initial = DialogInputParsers.formatRgb(current.getRed(), current.getGreen(), current.getBlue());

            List<DialogBody> body = new ArrayList<>();
            body.add(MuzDialogFactory.body(MuzTheme.accent(target.label())));
            body.add(MuzDialogFactory.body(MuzTheme.muted("当前颜色 · " + initial)));
            body.add(MuzDialogFactory.body(MuzTheme.muted("格式：255,226,92 或 F9B5B5。")));
            if (target.personal()) {
                body.add(MuzDialogFactory.body(MuzTheme.muted("预览色与选中色不能相同。")));
            }

            List<DialogInput> inputs = new ArrayList<>();
            inputs.add(MuzDialogFactory.textInput("value", noItalic(MuzTheme.accent("颜色")), initial, 16));

            List<ActionButton> actions = new ArrayList<>();
            actions.add(ctx.action(noItalic(MuzTheme.success("应用")), view -> {
                String raw = ctx.text(view, "value");
                int[] rgb;
                try {
                    rgb = DialogInputParsers.parseRgbRequired(raw);
                } catch (IllegalArgumentException exception) {
                    ctx.error(exception.getMessage() == null
                        ? "颜色格式我还没认出来，请写成 255,226,92 或 F9B5B5。"
                        : exception.getMessage());
                    return;
                }
                org.bukkit.Color color = org.bukkit.Color.fromRGB(rgb[0], rgb[1], rgb[2]);
                try {
                    apply(playerId, color);
                } catch (IllegalArgumentException exception) {
                    ctx.error(exception.getMessage() == null ? "这个颜色用不了。" : exception.getMessage());
                    return;
                }
                ctx.message(MuzTheme.success(target.label() + "已更新"));
                refreshTable(plugin, playerId);
                ctx.back();
            }));
            if (target.personal()) {
                actions.add(ctx.action(noItalic(MuzTheme.muted("跟随全局")), () -> {
                    clearOverride(playerId);
                    ctx.message(MuzTheme.success(target.label() + "已恢复为跟随全局"));
                    refreshTable(plugin, playerId);
                    ctx.back();
                }));
            }

            ActionButton exit = ctx.exit(noItalic(MuzTheme.muted("取消")));
            return MuzDialogFactory.screen(
                noItalic(MuzTheme.accent(target.label())),
                true,
                body,
                inputs,
                actions,
                exit,
                2
            );
        }

        private org.bukkit.Color currentColor(UUID playerId) {
            return switch (target) {
                case PLAYER_PREVIEW -> plugin.previewGlowColorFor(playerId);
                case PLAYER_SELECTED -> plugin.selectionGlowColorFor(playerId);
                case GLOBAL_HOVER -> plugin.hoverGlowColor();
                case GLOBAL_SELECTED -> plugin.selectedGlowColor();
            };
        }

        private void apply(UUID playerId, org.bukkit.Color color) {
            switch (target) {
                case PLAYER_PREVIEW -> plugin.setPlayerPreviewGlowColor(playerId, color);
                case PLAYER_SELECTED -> plugin.setPlayerSelectionGlowColor(playerId, color);
                case GLOBAL_HOVER -> plugin.setHoverGlowColor(color);
                case GLOBAL_SELECTED -> plugin.setSelectedGlowColor(color);
            }
        }

        /** 个人色「跟随全局」：删除个人覆盖（旧 {@code cyclePreviewGlowColor} / {@code cycleSelectionGlowColor} 语义）。 */
        private void clearOverride(UUID playerId) {
            switch (target) {
                case PLAYER_PREVIEW -> plugin.cyclePreviewGlowColor(playerId);
                case PLAYER_SELECTED -> plugin.cycleSelectionGlowColor(playerId);
                default -> {
                }
            }
        }
    }

    private static void refreshTable(DoudizhuPlugin plugin, UUID playerId) {
        GameTable table = plugin.getTableManager().getTableOf(playerId);
        if (table != null) {
            plugin.getPhysicalTableManager().refresh(table);
        }
    }
}
