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
import linmumua.doudizhu.action.CeActionExecutor;
import linmumua.doudizhu.game.ActionBarOverlayService;
import linmumua.doudizhu.game.GameTable;
import linmumua.doudizhu.game.PlayerOutputDispatcher;
import linmumua.doudizhu.ui.MuzTheme;
import net.kyori.adventure.text.Component;

/**
 * 音效 / 动作选择与编辑 Dialog。
 *
 * <p>两种使用方共用同一个类，由 {@link Kind} 区分：
 * <ul>
 *   <li><b>个人侧</b>（无权限门槛）：选牌音效方案（试听 / 启用）、出牌动作牌型菜单、单牌型动作方案（试听 / 启用）。
 *       对应旧 {@code HandGuiService.openSelectionSoundPicker / openPlayActionKindMenu / openPlayActionPicker}
 *       与 {@code previewPlayerOption}。</li>
 *   <li><b>管理侧</b>（{@code muz.admin}，每次回调由 core 复核）：选牌音效方案编辑、动作牌型子页、
 *       动作方案编辑、倒计时 / 未准备提醒 / 放置阻挡警告音效选择。对应旧
 *       {@code openAdminSelectionSoundEditor / openAdminPlayActionKindPicker / openAdminPlayActionEditor /
 *       openCountdownSoundPicker / openUnreadyWarningSoundPicker / openPlacementBlockedSoundPicker}。</li>
 * </ul>
 *
 * <p>【旧左/右键语义如何落到 Dialog】旧箱子用「左键试听并切换 / 右键只切换」表达两种动作；
 * Dialog 没有左右键，因此拆成两个显式按钮：「试听」与「启用」。管理侧的「左键进编辑 / 右键恢复默认」
 * 同样拆成「编辑」与「恢复默认」。
 *
 * <p>【自定义输入】旧实现让玩家在聊天栏粘贴 `显示名 || 动作指令` 之类的文本；Dialog 版改用
 * 对话框内的文本输入框 + 「应用自定义」按钮，解析逻辑与旧实现一致（见
 * {@link #selectionProfileFromSpec} / {@link #parseActionProfileInput}）。
 *
 * <p>【权限 / lane / 代次】由 core 的 {@link MuzDialogContext} 统一保证：回调只在 player lane 内触发，
 * 且已复核权限、会话代次与关闭期，因此本类不自己切线程、不自己判权限。试听音效走既有 player lane
 * 出口（{@link PlayerOutputDispatcher#playSound}），动作预览复用
 * {@link CeActionExecutor#previewPlayProfile}（其内部同样走 {@code PlayerOutputDispatcher}）。
 */
public final class SoundActionDialogScreen implements MuzDialogScreen {

    /** 个人 / 管理共用的屏种类。 */
    public enum Kind {
        PLAYER_SELECTION("settings.selection", null),
        PLAYER_ACTION_KIND("settings.action", null),
        PLAYER_ACTION_PICKER("settings.action.picker", null),
        ADMIN_SELECTION_EDITOR("admin.sound.selection", "muz.admin"),
        ADMIN_ACTION_KIND("admin.action.kind", "muz.admin"),
        ADMIN_ACTION_EDITOR("admin.action.editor", "muz.admin"),
        ADMIN_COUNTDOWN("admin.sound.countdown", "muz.admin"),
        ADMIN_UNREADY("admin.sound.unready", "muz.admin"),
        ADMIN_PLACEMENT("admin.sound.placement", "muz.admin");

        private final String baseKey;
        private final String permission;

        Kind(String baseKey, String permission) {
            this.baseKey = baseKey;
            this.permission = permission;
        }

        public String baseKey() {
            return baseKey;
        }

        public String permission() {
            return permission;
        }
    }

    /** 每档最多 4 个方案，与 {@code DoudizhuPlugin.PLAYER_OPTION_PROFILE_COUNT} 一致。 */
    private static final int MAX_PROFILE_SLOTS = 4;

    private final DoudizhuPlugin plugin;
    private final Kind kind;
    private final DoudizhuPlugin.PlayActionKind actionKind;
    private final int profileIndex;

    private SoundActionDialogScreen(
        DoudizhuPlugin plugin,
        Kind kind,
        DoudizhuPlugin.PlayActionKind actionKind,
        int profileIndex
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.actionKind = actionKind;
        this.profileIndex = profileIndex;
    }

    // ------------------------------------------------------------------
    // 静态工厂
    // ------------------------------------------------------------------

    public static SoundActionDialogScreen playerSelection(DoudizhuPlugin plugin) {
        return new SoundActionDialogScreen(plugin, Kind.PLAYER_SELECTION, null, -1);
    }

    public static SoundActionDialogScreen playerActionKindMenu(DoudizhuPlugin plugin) {
        return new SoundActionDialogScreen(plugin, Kind.PLAYER_ACTION_KIND, null, -1);
    }

    public static SoundActionDialogScreen playerActionPicker(DoudizhuPlugin plugin, DoudizhuPlugin.PlayActionKind actionKind) {
        return new SoundActionDialogScreen(plugin, Kind.PLAYER_ACTION_PICKER, Objects.requireNonNull(actionKind, "actionKind"), -1);
    }

    public static SoundActionDialogScreen selectionSoundEditor(DoudizhuPlugin plugin, int profileIndex) {
        return new SoundActionDialogScreen(plugin, Kind.ADMIN_SELECTION_EDITOR, null, clampSlot(profileIndex));
    }

    public static SoundActionDialogScreen playActionKind(DoudizhuPlugin plugin, DoudizhuPlugin.PlayActionKind actionKind) {
        return new SoundActionDialogScreen(plugin, Kind.ADMIN_ACTION_KIND, Objects.requireNonNull(actionKind, "actionKind"), -1);
    }

    public static SoundActionDialogScreen actionEditor(DoudizhuPlugin plugin, DoudizhuPlugin.PlayActionKind actionKind, int profileIndex) {
        return new SoundActionDialogScreen(
            plugin,
            Kind.ADMIN_ACTION_EDITOR,
            Objects.requireNonNull(actionKind, "actionKind"),
            clampSlot(profileIndex)
        );
    }

    public static SoundActionDialogScreen countdownSound(DoudizhuPlugin plugin) {
        return new SoundActionDialogScreen(plugin, Kind.ADMIN_COUNTDOWN, null, -1);
    }

    public static SoundActionDialogScreen unreadyWarningSound(DoudizhuPlugin plugin) {
        return new SoundActionDialogScreen(plugin, Kind.ADMIN_UNREADY, null, -1);
    }

    public static SoundActionDialogScreen placementBlockedSound(DoudizhuPlugin plugin) {
        return new SoundActionDialogScreen(plugin, Kind.ADMIN_PLACEMENT, null, -1);
    }

    private static int clampSlot(int index) {
        return Math.clamp(index, 0, MAX_PROFILE_SLOTS - 1);
    }

    // ------------------------------------------------------------------
    // MuzDialogScreen
    // ------------------------------------------------------------------

    @Override
    public String screenKey() {
        return switch (kind) {
            case PLAYER_ACTION_PICKER -> kind.baseKey() + ":" + actionKind.key();
            case ADMIN_SELECTION_EDITOR -> kind.baseKey() + ":" + profileIndex;
            case ADMIN_ACTION_KIND -> kind.baseKey() + ":" + actionKind.key();
            case ADMIN_ACTION_EDITOR -> kind.baseKey() + ":" + actionKind.key() + ":" + profileIndex;
            default -> kind.baseKey();
        };
    }

    @Override
    public String permission() {
        return kind.permission();
    }

    @Override
    public Dialog build(MuzDialogContext ctx) {
        return switch (kind) {
            case PLAYER_SELECTION -> buildPlayerSelection(ctx);
            case PLAYER_ACTION_KIND -> buildPlayerActionKind(ctx);
            case PLAYER_ACTION_PICKER -> buildPlayerActionPicker(ctx);
            case ADMIN_SELECTION_EDITOR -> buildAdminSelectionEditor(ctx);
            case ADMIN_ACTION_KIND -> buildAdminActionKind(ctx);
            case ADMIN_ACTION_EDITOR -> buildAdminActionEditor(ctx);
            case ADMIN_COUNTDOWN -> buildAdminSound(ctx, "倒计时音效", plugin.getCountdownSoundSpec());
            case ADMIN_UNREADY -> buildAdminSound(ctx, "未准备提醒音", plugin.getUnreadyWarningSoundSpec());
            case ADMIN_PLACEMENT -> buildAdminSound(ctx, "放置阻挡警告音", plugin.getPlacementBlockedSoundSpec());
        };
    }

    // ------------------------------------------------------------------
    // 个人侧：选牌音效方案
    // ------------------------------------------------------------------

    private Dialog buildPlayerSelection(MuzDialogContext ctx) {
        UUID playerId = ctx.playerId();
        List<DoudizhuPlugin.OptionProfile> profiles = plugin.getSelectionSoundProfiles();
        int selected = plugin.getPlayerSelectionSoundProfileIndex(playerId);

        List<DialogBody> body = new ArrayList<>();
        body.add(line(MuzTheme.accent("选牌音效方案")));
        body.add(line(MuzTheme.muted("当前正在使用 " + selectionSoundDisplayLabel(profiles.get(selected)) + "。")));
        body.add(line(MuzTheme.muted("「试听」播放一次，「启用」切换为该方案。")));

        List<ActionButton> actions = new ArrayList<>();
        int limit = Math.min(profiles.size(), MAX_PROFILE_SLOTS);
        for (int index = 0; index < limit; index++) {
            int slot = index;
            String name = selectionSoundDisplayLabel(profiles.get(index));
            actions.add(ctx.action(
                PlayerSettingsDialogScreen.noItalic(MuzTheme.muted("试听 · " + name)),
                () -> previewSelectionSound(ctx, slot)
            ));
            actions.add(ctx.action(
                PlayerSettingsDialogScreen.noItalic(MuzTheme.accent((index == selected ? "✓ " : "") + "启用 · " + name)),
                () -> {
                    plugin.setPlayerSelectionSoundProfileIndex(playerId, slot);
                    ctx.message(MuzTheme.success("选牌音效已经切到 " + name));
                    refreshTable(playerId);
                    ctx.service().refresh(playerId);
                }
            ));
        }

        return screen("选牌音效方案", body, List.of(), actions, ctx);
    }

    private void previewSelectionSound(MuzDialogContext ctx, int index) {
        DoudizhuPlugin.SelectionSound sound = plugin.selectionSoundForProfile(index);
        if (sound.volume() <= 0.0f) {
            ctx.message(MuzTheme.warning("这套方案是静音，不会播放提示音。"));
            return;
        }
        playSound(ctx.playerId(), sound.key(), sound.volume(), sound.selectedPitch());
        ctx.message(MuzTheme.accent("正在试听 · " + selectionSoundDisplayLabel(plugin.getSelectionSoundProfile(index))));
    }

    // ------------------------------------------------------------------
    // 个人侧：出牌动作
    // ------------------------------------------------------------------

    private Dialog buildPlayerActionKind(MuzDialogContext ctx) {
        List<DialogBody> body = new ArrayList<>();
        body.add(line(MuzTheme.accent("动作设置")));
        body.add(line(MuzTheme.muted("先选牌型，再选方案。每种牌型都单独保存。")));

        List<ActionButton> actions = new ArrayList<>();
        for (DoudizhuPlugin.PlayActionKind value : DoudizhuPlugin.PlayActionKind.values()) {
            actions.add(ctx.action(
                PlayerSettingsDialogScreen.noItalic(MuzTheme.accent(value.label())),
                () -> ctx.push(playerActionPicker(plugin, value))
            ));
        }
        return screen("动作设置", body, List.of(), actions, ctx);
    }

    private Dialog buildPlayerActionPicker(MuzDialogContext ctx) {
        UUID playerId = ctx.playerId();
        List<DoudizhuPlugin.OptionProfile> profiles = plugin.getPlayActionProfiles(actionKind);
        int selected = plugin.getPlayerPlayActionProfileIndex(playerId, actionKind);

        List<DialogBody> body = new ArrayList<>();
        body.add(line(MuzTheme.accent(actionKind.label() + " · 动作方案")));
        body.add(line(MuzTheme.muted("当前方案 · " + profiles.get(selected).label() + "。")));
        body.add(line(MuzTheme.muted("「试听」预览一次，「启用」切换为该方案。")));

        List<ActionButton> actions = new ArrayList<>();
        int limit = Math.min(profiles.size(), MAX_PROFILE_SLOTS);
        for (int index = 0; index < limit; index++) {
            int slot = index;
            String name = profiles.get(index).label();
            actions.add(ctx.action(
                PlayerSettingsDialogScreen.noItalic(MuzTheme.muted("试听 · " + name)),
                () -> CeActionExecutor.previewPlayProfile(plugin, ctx.player(), plugin.getPlayActionProfile(actionKind, slot))
            ));
            actions.add(ctx.action(
                PlayerSettingsDialogScreen.noItalic(MuzTheme.accent((index == selected ? "✓ " : "") + "启用 · " + name)),
                () -> {
                    plugin.setPlayerPlayActionProfileIndex(playerId, actionKind, slot);
                    ctx.message(MuzTheme.success(actionKind.label() + " 动作已经切到 " + name));
                    refreshTable(playerId);
                    ctx.service().refresh(playerId);
                }
            ));
        }
        return screen(actionKind.label() + " · 动作方案", body, List.of(), actions, ctx);
    }

    // ------------------------------------------------------------------
    // 管理侧：选牌音效方案编辑
    // ------------------------------------------------------------------

    private Dialog buildAdminSelectionEditor(MuzDialogContext ctx) {
        DoudizhuPlugin.OptionProfile current = plugin.getSelectionSoundProfile(profileIndex);
        List<DialogBody> body = new ArrayList<>();
        body.add(line(MuzTheme.accent("选牌音效方案 " + (profileIndex + 1))));
        body.add(line(MuzTheme.muted("当前 · " + current.label())));
        body.add(line(MuzTheme.muted("当前规格 · " + current.spec())));
        body.add(line(MuzTheme.muted("格式：音效名 [音量] [选中音高] [取消音高]。")));

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(MuzDialogFactory.textInput("value",
            PlayerSettingsDialogScreen.noItalic(MuzTheme.accent("自定义音效")), "", 128));

        List<ActionButton> actions = new ArrayList<>();
        for (Preset preset : selectionSoundPresets()) {
            actions.add(ctx.action(
                PlayerSettingsDialogScreen.noItalic(MuzTheme.accent(preset.title())),
                () -> applySelectionProfile(ctx, profileIndex, selectionProfileFromSpec(plugin, preset.spec()))
            ));
        }
        actions.add(ctx.action(
            PlayerSettingsDialogScreen.noItalic(MuzTheme.success("应用自定义")),
            view -> {
                String raw = ctx.text(view, "value");
                if (DialogInputParsers.isCancel(raw)) {
                    ctx.error("输入为空，没有改动。");
                    return;
                }
                applySelectionProfile(ctx, profileIndex, selectionProfileFromSpec(plugin, raw));
            }
        ));
        actions.add(ctx.action(
            PlayerSettingsDialogScreen.noItalic(MuzTheme.warning("恢复默认")),
            () -> applySelectionProfile(ctx, profileIndex, plugin.defaultSelectionSoundProfile(profileIndex))
        ));

        return screen("选牌音效方案 " + (profileIndex + 1), body, inputs, actions, ctx);
    }

    private void applySelectionProfile(MuzDialogContext ctx, int index, DoudizhuPlugin.OptionProfile profile) {
        try {
            plugin.setSelectionSoundProfileDefinition(index, profile);
        } catch (IllegalArgumentException exception) {
            ctx.error(exception.getMessage() == null ? "这套音效没能记下。" : exception.getMessage());
            return;
        }
        ctx.message(MuzTheme.success("选牌音效方案 " + (index + 1) + " 已更新"));
        ctx.service().refresh(ctx.playerId());
    }

    // ------------------------------------------------------------------
    // 管理侧：动作牌型子页 + 动作编辑
    // ------------------------------------------------------------------

    private Dialog buildAdminActionKind(MuzDialogContext ctx) {
        List<DialogBody> body = new ArrayList<>();
        body.add(line(MuzTheme.accent(actionKind.label() + " · 动作槽")));
        body.add(line(MuzTheme.muted("「编辑」进入该动作槽，「恢复默认」还原为出厂方案。")));

        List<ActionButton> actions = new ArrayList<>();
        for (int index = 0; index < MAX_PROFILE_SLOTS; index++) {
            int slot = index;
            DoudizhuPlugin.OptionProfile profile = plugin.getPlayActionProfile(actionKind, index);
            actions.add(ctx.action(
                PlayerSettingsDialogScreen.noItalic(MuzTheme.accent("编辑 · 动作 " + (index + 1) + " · " + profile.label())),
                () -> ctx.push(actionEditor(plugin, actionKind, slot))
            ));
            actions.add(ctx.action(
                PlayerSettingsDialogScreen.noItalic(MuzTheme.warning("恢复默认 · 动作 " + (index + 1))),
                () -> applyActionProfile(ctx, actionKind, slot, plugin.defaultPlayActionProfile(slot))
            ));
        }
        return screen(actionKind.label() + " · 动作槽", body, List.of(), actions, ctx);
    }

    private Dialog buildAdminActionEditor(MuzDialogContext ctx) {
        DoudizhuPlugin.OptionProfile current = plugin.getPlayActionProfile(actionKind, profileIndex);
        List<DialogBody> body = new ArrayList<>();
        body.add(line(MuzTheme.accent(actionKind.label() + " · 动作 " + (profileIndex + 1))));
        body.add(line(MuzTheme.muted("当前 · " + current.label())));
        body.add(line(MuzTheme.muted("当前规格 · " + current.spec())));
        body.add(line(MuzTheme.muted("格式：显示名 || 动作指令。")));

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(MuzDialogFactory.textInput("value",
            PlayerSettingsDialogScreen.noItalic(MuzTheme.accent("显示名 || 动作指令")), "", 512));

        List<ActionButton> actions = new ArrayList<>();
        actions.add(ctx.action(
            PlayerSettingsDialogScreen.noItalic(MuzTheme.accent("试听当前方案")),
            () -> CeActionExecutor.previewPlayProfile(plugin, ctx.player(), plugin.getPlayActionProfile(actionKind, profileIndex))
        ));
        for (Preset preset : playActionPresets()) {
            actions.add(ctx.action(
                PlayerSettingsDialogScreen.noItalic(MuzTheme.accent(preset.title())),
                () -> applyActionProfile(ctx, actionKind, profileIndex, parseActionProfileInput(plugin, preset.spec()))
            ));
        }
        actions.add(ctx.action(
            PlayerSettingsDialogScreen.noItalic(MuzTheme.success("应用自定义")),
            view -> {
                String raw = ctx.text(view, "value");
                if (DialogInputParsers.isCancel(raw)) {
                    ctx.error("输入为空，没有改动。");
                    return;
                }
                try {
                    applyActionProfile(ctx, actionKind, profileIndex, parseActionProfileInput(plugin, raw));
                } catch (IllegalArgumentException exception) {
                    ctx.error(exception.getMessage() == null ? "格式还不对。" : exception.getMessage());
                }
            }
        ));
        actions.add(ctx.action(
            PlayerSettingsDialogScreen.noItalic(MuzTheme.warning("恢复默认")),
            () -> applyActionProfile(ctx, actionKind, profileIndex, plugin.defaultPlayActionProfile(profileIndex))
        ));

        return screen(actionKind.label() + " · 动作 " + (profileIndex + 1), body, inputs, actions, ctx);
    }

    private void applyActionProfile(MuzDialogContext ctx, DoudizhuPlugin.PlayActionKind kind,
        int index, DoudizhuPlugin.OptionProfile profile) {
        try {
            plugin.setPlayActionProfileDefinition(kind, index, profile);
        } catch (IllegalArgumentException exception) {
            ctx.error(exception.getMessage() == null ? "这套动作没能记下。" : exception.getMessage());
            return;
        }
        ctx.message(MuzTheme.success(kind.label() + " 动作 " + (index + 1) + " 已更新"));
        ctx.service().refresh(ctx.playerId());
    }

    // ------------------------------------------------------------------
    // 管理侧：倒计时 / 未准备 / 放置阻挡 音效
    // ------------------------------------------------------------------

    private Dialog buildAdminSound(MuzDialogContext ctx, String title, String currentSpec) {
        SoundSpec current = SoundSpec.parse(currentSpec);
        List<DialogBody> body = new ArrayList<>();
        body.add(line(MuzTheme.accent(title)));
        body.add(line(MuzTheme.muted("当前 · " + currentSpec)));
        body.add(line(MuzTheme.muted("点预设会立刻试听并保存；音量与音高也能用下面的滑块微调后再应用。")));

        List<DialogInput> inputs = new ArrayList<>();
        inputs.add(MuzDialogFactory.textInput("value",
            PlayerSettingsDialogScreen.noItalic(MuzTheme.accent("自定义音效（留空则沿用当前音效）")), "", 128));
        // 音量/音高用滑块而不是让人手写：既避免记格式，也能边拖边听（配合「试听当前」）。
        inputs.add(MuzDialogFactory.numberRange("volume",
            PlayerSettingsDialogScreen.noItalic(MuzTheme.accent("音量")), 0.0f, 1.0f, 0.1f, current.volume()));
        inputs.add(MuzDialogFactory.numberRange("pitch",
            PlayerSettingsDialogScreen.noItalic(MuzTheme.accent("音高")), 0.5f, 2.0f, 0.1f, current.pitch()));

        List<ActionButton> actions = new ArrayList<>();
        for (Preset preset : countdownPresets()) {
            SoundSpec presetSound = SoundSpec.parse(preset.spec());
            actions.add(ctx.action(
                PlayerSettingsDialogScreen.noItalic(MuzTheme.accent(preset.title())),
                // 预设自带调好的音量/音高：点一下既保存也立刻发声，管理员能直接听到效果。
                () -> applyAndPreviewSound(ctx, presetSound)
            ));
        }
        actions.add(ctx.action(
            PlayerSettingsDialogScreen.noItalic(MuzTheme.accent("试听当前")),
            view -> previewSound(ctx, soundFromView(ctx, view, current))
        ));
        actions.add(ctx.action(
            PlayerSettingsDialogScreen.noItalic(MuzTheme.success("应用自定义")),
            view -> {
                String raw = ctx.text(view, "value");
                SoundSpec typed = soundFromView(ctx, view, current);
                if (DialogInputParsers.isCancel(raw) && typed.key().isBlank()) {
                    ctx.error("输入为空，没有改动。");
                    return;
                }
                applyAndPreviewSound(ctx, typed);
            }
        ));
        return screen(title, body, inputs, actions, ctx);
    }

    /**
     * 读取「文本框里的音效名 + 滑块的音量音高」。
     *
     * <p>文本框只负责**音效名**：让它同时承担音量音高，会与滑块形成两个真值来源，
     * 玩家改了滑块又被文本覆盖。因此这里只用文本里解析出的 key，音量与音高一律取滑块。
     */
    private SoundSpec soundFromView(MuzDialogContext ctx, io.papermc.paper.dialog.DialogResponseView view,
        SoundSpec fallback) {
        SoundSpec typed = SoundSpec.parse(ctx.text(view, "value"));
        String key = typed.key().isBlank() ? fallback.key() : typed.key();
        Float volume = ctx.number(view, "volume");
        Float pitch = ctx.number(view, "pitch");
        return new SoundSpec(
            key,
            volume == null ? fallback.volume() : volume,
            pitch == null ? fallback.pitch() : pitch);
    }

    /** 保存并立刻试听；保存失败时只报错、不出声（避免「听着像成功、其实没存下」）。 */
    private void applyAndPreviewSound(MuzDialogContext ctx, SoundSpec sound) {
        if (!applySound(ctx, SoundSpec.spec(sound.key(), sound.volume(), sound.pitch()))) {
            return;
        }
        previewSound(ctx, sound);
    }

    private void previewSound(MuzDialogContext ctx, SoundSpec sound) {
        if (sound.key() == null || sound.key().isBlank()) {
            ctx.error("还没填音效名。");
            return;
        }
        if (sound.volume() <= 0.0f) {
            ctx.message(MuzTheme.warning("音量是 0，这次等同静音，不会出声。"));
            return;
        }
        playSound(ctx.playerId(), sound.key(), sound.volume(), sound.pitch());
        ctx.message(MuzTheme.accent("正在试听 · " + sound.key()
            + "  音量 " + SoundSpec.format(sound.volume())
            + "  音高 " + SoundSpec.format(sound.pitch())));
    }

    /**
     * 保存音效规格。
     *
     * @return 是否真的保存成功；调用方据此决定要不要试听——失败还出声会让人以为已经生效
     */
    private boolean applySound(MuzDialogContext ctx, String spec) {
        try {
            switch (kind) {
                case ADMIN_COUNTDOWN -> plugin.setCountdownSoundSpec(spec);
                case ADMIN_UNREADY -> plugin.setUnreadyWarningSoundSpec(spec);
                case ADMIN_PLACEMENT -> plugin.setPlacementBlockedSoundSpec(spec);
                default -> {
                    return false;
                }
            }
        } catch (IllegalArgumentException exception) {
            ctx.error(exception.getMessage() == null ? "这个音效没能记下。" : exception.getMessage());
            return false;
        }
        ctx.message(MuzTheme.success(labelOf(kind) + "已更新"));
        ctx.service().refresh(ctx.playerId());
        return true;
    }

    private static String labelOf(Kind kind) {
        return switch (kind) {
            case ADMIN_COUNTDOWN -> "倒计时音效";
            case ADMIN_UNREADY -> "未准备提醒音";
            case ADMIN_PLACEMENT -> "放置阻挡警告音";
            default -> "设置";
        };
    }

    // ------------------------------------------------------------------
    // 共用
    // ------------------------------------------------------------------

    /** 统一的屏构建：业务按钮 + 返回（栈回退）+ 关闭。 */
    private Dialog screen(String title, List<DialogBody> body, List<DialogInput> inputs,
        List<ActionButton> actions, MuzDialogContext ctx) {
        List<ActionButton> all = new ArrayList<>(actions);
        all.add(ctx.action(PlayerSettingsDialogScreen.noItalic(MuzTheme.muted("返回")), ctx::back));
        ActionButton exit = ctx.exit(PlayerSettingsDialogScreen.noItalic(MuzTheme.muted("关闭")));
        return MuzDialogFactory.screen(
            PlayerSettingsDialogScreen.noItalic(MuzTheme.accent(title)),
            true,
            body,
            inputs,
            all,
            exit,
            2
        );
    }

    private static DialogBody line(Component component) {
        return MuzDialogFactory.body(component);
    }

    private void refreshTable(UUID playerId) {
        GameTable table = plugin.getTableManager().getTableOf(playerId);
        if (table != null) {
            plugin.getPhysicalTableManager().refresh(table);
        }
    }

    private void playSound(UUID playerId, String key, float volume, float pitch) {
        PlayerOutputDispatcher output = outputDispatcher();
        if (output != null) {
            output.playSound(playerId, key, volume, pitch);
        }
    }

    private PlayerOutputDispatcher outputDispatcher() {
        ActionBarOverlayService overlay = plugin.getActionBarOverlayService();
        return overlay == null ? null : overlay.outputDispatcher();
    }

    // ------------------------------------------------------------------
    // 纯函数：标签翻译与解析（无 Bukkit 依赖，可单测）
    // ------------------------------------------------------------------

    /** 由音效规格翻译出中文显示名；认不出的返回「自定义音效」。对旧 {@code HandGuiService.selectionSoundLabel} 等价。 */
    public static String selectionSoundLabel(String spec) {
        if (spec == null) {
            return "自定义音效";
        }
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

    /** 方案显示名：优先用音效规格翻译出的名字，翻译不出再退回方案自身标签（与旧 {@code selectionSoundDisplayLabel} 等价）。 */
    public static String selectionSoundDisplayLabel(DoudizhuPlugin.OptionProfile profile) {
        String translated = selectionSoundLabel(profile.spec());
        return translated == null || translated.isBlank() ? profile.label() : translated;
    }

    /** 由音效规格生成方案（标签由规格翻译，规格经插件归一化）；与旧 {@code selectionProfileFromSpec} 等价。 */
    public static DoudizhuPlugin.OptionProfile selectionProfileFromSpec(DoudizhuPlugin plugin, String spec) {
        String normalized = plugin.normalizeSelectionSoundSpec(spec);
        return new DoudizhuPlugin.OptionProfile(selectionSoundLabel(normalized), normalized);
    }

    /** 解析 `显示名 || 动作指令`；格式不对抛 {@link IllegalArgumentException}（中文）；与旧 {@code parseProfileInput(soundProfile=false)} 等价。 */
    public static DoudizhuPlugin.OptionProfile parseActionProfileInput(DoudizhuPlugin plugin, String rawInput) {
        String[] parts = rawInput.split("\\|\\|", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("格式还不对，请写成 `显示名 || 配置内容`。");
        }
        String label = parts[0].trim();
        String spec = parts[1].trim();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("显示名先别留空，给这套方案起个名字吧。");
        }
        return new DoudizhuPlugin.OptionProfile(label, plugin.normalizePlayActionSpec(spec));
    }

    /** 一个可选预设（标题 / 规格 / 说明）。 */
    /**
     * 一条音效规格：{@code 音效名 音量 音高}。
     *
     * <p>只用一位小数格式化，与配置层 `normalizeSoundSpec` 的存储精度一致；
     * 解析时容错（缺音量/音高按默认值补），因为它只用于**预填滑块初值**，
     * 真正写入前仍会经过插件侧校验。
     */
    public record SoundSpec(String key, float volume, float pitch) {
        /** 默认音量：与旧预设的常见量级一致。 */
        public static final float DEFAULT_VOLUME = 0.4f;
        /** 默认音高：原版音高。 */
        public static final float DEFAULT_PITCH = 1.0f;

        /** 容错解析；空串得到「无音效名 + 默认音量音高」。 */
        public static SoundSpec parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) {
                return new SoundSpec("", DEFAULT_VOLUME, DEFAULT_PITCH);
            }
            String[] parts = value.split("[ \\t]+");
            String key = parts.length == 0 ? "" : parts[0];
            float volume = parts.length >= 2 ? numberOr(parts[1], DEFAULT_VOLUME) : DEFAULT_VOLUME;
            float pitch = parts.length >= 3 ? numberOr(parts[2], DEFAULT_PITCH) : DEFAULT_PITCH;
            return new SoundSpec(key, volume, pitch);
        }

        /** 组装成配置层接受的规格字符串。 */
        public static String spec(String key, float volume, float pitch) {
            return (key == null ? "" : key.trim()) + " " + format(volume) + " " + format(pitch);
        }

        /** 一位小数格式化（与存储精度一致）。 */
        public static String format(float value) {
            return String.format(java.util.Locale.ROOT, "%.1f", value);
        }

        private static float numberOr(String text, float fallback) {
            try {
                float parsed = Float.parseFloat(text);
                return Float.isFinite(parsed) ? parsed : fallback;
            } catch (NumberFormatException exception) {
                return fallback;
            }
        }
    }

    public record Preset(String title, String spec, String description) {
    }

    /** 倒计时 / 未准备提醒 / 放置阻挡共用的音效预设（数据与旧 {@code HandGuiService.countdownPresets} 一致）。 */
    public static List<Preset> countdownPresets() {
        return List.of(
            new Preset("默认倒计时", "minecraft:block.note_block.hat 0.45 1.00", "轻量且清晰。"),
            new Preset("钟声提醒", "minecraft:block.note_block.bell 0.30 1.08", "更像提醒铃。"),
            new Preset("铁砧提醒", "minecraft:block.anvil.land 0.18 1.10", "更有压迫感。"),
            new Preset("木牌提醒", "minecraft:block.hanging_sign.place 0.35 1.00", "木质轻提示。"),
            new Preset("洞穴提醒", "minecraft:ambient.cave 0.20 1.00", "偏氛围。"),
            new Preset("按钮提醒", "minecraft:ui.button.click 0.28 0.95", "短促清晰。"),
            new Preset("静音", "minecraft:block.note_block.hat 0.00 1.00", "保留倒计时文字但静音。")
        );
    }

    /** 选牌音效方案预设（数据与旧 {@code HandGuiService.selectionSoundPresets} 一致）。 */
    public static List<Preset> selectionSoundPresets() {
        return List.of(
            new Preset("清脆提示", "minecraft:block.note_block.pling 0.35 1.18 0.92", "轻亮、反馈明确。"),
            new Preset("告示牌提示", "minecraft:block.hanging_sign.place 0.35 1.12 0.92", "木质、较柔和。"),
            new Preset("洞穴提示", "minecraft:ambient.cave 0.25 1.00 0.90", "偏氛围感。"),
            new Preset("钟声提示", "minecraft:block.note_block.bell 0.28 1.10 0.96", "更像提醒铃。"),
            new Preset("金属提示", "minecraft:block.iron_trapdoor.open 0.24 1.22 0.98", "偏机械、清脆。"),
            new Preset("按钮提示", "minecraft:ui.button.click 0.30 1.06 0.92", "短促、利落。"),
            new Preset("静音", "minecraft:block.note_block.hat 0.00 1.00 1.00", "不播放选牌音效。")
        );
    }

    /** 出牌动作方案预设（数据与旧 {@code HandGuiService.playActionPresets} 一致）。 */
    public static List<Preset> playActionPresets() {
        return List.of(
            new Preset("无操作", "type: none", "不额外执行任何提示。"),
            new Preset("聊天提示", "type: message; message: <#8FD4FF>出牌完成</#8FD4FF><dark_gray> · </dark_gray><#F1D398><arg:pattern></#F1D398>", "向玩家发送聊天提示。"),
            new Preset("动作栏提示", "type: actionbar; actionbar: <#9AA8B6><arg:player.name></#9AA8B6><dark_gray> · </dark_gray><#F1D398><arg:pattern></#F1D398>", "在动作栏显示提示。"),
            new Preset("播放音效", "type: play_sound; sound: minecraft:entity.player.levelup; volume: 0.35; pitch: 1.05; source: master", "播放一个成功音效。"),
            new Preset("标题提示", "type: title; title: <gradient:#8FD4FF:#F1D398><bold>出牌已确认</bold></gradient>; subtitle: <#9AA8B6><arg:player.name></#9AA8B6><dark_gray> · </dark_gray><#F1D398><arg:pattern></#F1D398>; fade-in: 5; stay: 30; fade-out: 10", "弹出标题和副标题。"),
            new Preset("控制台命令", "type: command; command: say <arg:player.name> 打出了 <arg:pattern>", "由控制台执行命令。"),
            new Preset("玩家命令", "type: command; command: help; as-player: true", "由玩家自己执行命令。")
        );
    }
}
