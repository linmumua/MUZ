package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import linmumua.doudizhu.config.AdminSettingArithmetic;
import linmumua.doudizhu.ui.MuzTheme;
import net.kyori.adventure.text.Component;

/**
 * 单项管理设置的数值/布尔调节屏。
 *
 * 对应 1.10.59 箱子版里「管理页点一下那枚图标就 ± 一步」的交互，但把步长、倍率、
 * 直接输入与滑块都显式做出来：
 * <ul>
 *   <li>点击 ± 当前步长；原 Shift 的 ±10 倍改为明确的「×10」倍率按钮。</li>
 *   <li>步长本身可设：预设 + 自定义（仅「跟随微调步长」的项；固定步长与整数项保持原语义）。</li>
 *   <li>直接数字输入与滑块各自独立确认，互不覆盖。</li>
 *   <li>滑块用「当前值 ± N 步」构造，索引是**相对原值的小整数偏移**（{@link AdminSettingArithmetic#sliderWindow}）：
 *       偏移 0 就精确等于原值（打开不拖直接确认不会把 0.1234 吸成 0.1），偏移 ±k＝原值 ± k*步长；
 *       相对小整数避免大值 + 极小步长时绝对长索引转 float 的并档跨步。</li>
 *   <li>不再对已放开的设置重新施加声明 min/max 硬限：± 走 {@code adjustAdminSetting}
 *       （内部传 ±无穷），滑块/直接输入走 {@code setAdminSettingAbsolute}，非有限值一律拒绝并提示。</li>
 * </ul>
 *
 * 权限、会话代次、player lane、关闭期早退全部由 core 的 {@link MuzDialogContext} 负责；
 * 本屏只在回调里复核 {@code muz.admin} 并读实时玩家状态，不自己碰调度器。
 */
public final class AdminSettingDialogScreen implements MuzDialogScreen {
    /** 滑块窗口：当前值 ± 这么多步。20 步足够细看两边趋势，又不至于拖不满。 */
    private static final int SLIDER_HALF_STEPS = 20;

    /**
     * 「跟随微调步长」的项，玩家选的步长按玩家记住，跨设置、跨页共享。
     *
     * 旧实现把这份记忆放在 HandGuiService 的实例字段里（一个 0.01/0.1/1 的三档循环）；
     * 迁移后步长选择搬进了每个设置屏，仍要保留「换个设置不用重挑步长」的手感，
     * 所以用一张按玩家 UUID 的静态表，默认 0.01（与旧默认档一致）。
     */
    private static final Map<UUID, Double> STEP_BY_PLAYER = new ConcurrentHashMap<>();

    private final DoudizhuPlugin plugin;
    private final AdminSetting setting;

    public AdminSettingDialogScreen(DoudizhuPlugin plugin, AdminSetting setting) {
        this.plugin = plugin;
        this.setting = setting;
    }

    @Override
    public String screenKey() {
        return "admin.setting:" + setting.name();
    }

    @Override
    public String permission() {
        return "muz.admin";
    }

    /** 玩家当前选的步长（仅「跟随微调步长」的项有意义）。 */
    private double playerStep(UUID playerId) {
        return STEP_BY_PLAYER.getOrDefault(playerId, AdminStepPolicy.DEFAULT_STEP);
    }

    /** 本次点击实际生效的步长（固定步长/整数/跟随三条旧语义见 {@link AdminStepPolicy}）。 */
    private double effectiveStep(UUID playerId) {
        return AdminStepPolicy.effectiveStep(setting, playerStep(playerId));
    }

    private double currentValue() {
        return plugin.adminSettingCurrentDouble(setting);
    }

    @Override
    public Dialog build(MuzDialogContext ctx) {
        List<DialogBody> body = new ArrayList<>();
        body.add(MuzDialogFactory.body(MuzTheme.muted(plugin.getHandGuiService().adminSettingHint(setting))));
        body.add(MuzDialogFactory.body(valueLine()));
        body.add(MuzDialogFactory.body(MuzTheme.muted(stepLine(ctx.playerId()))));
        if (setting == AdminSetting.TABLE_SPAWN_OFFSET_Y) {
            body.add(MuzDialogFactory.body(MuzTheme.muted("整套桌椅一起升降；方块桌只能一格一格动。")));
        }

        List<DialogInput> inputs = new ArrayList<>();
        List<ActionButton> actions = new ArrayList<>();

        if (setting.booleanSetting()) {
            actions.add(ctx.action(MuzTheme.accent("切换（现在是 " + plugin.adminSettingValue(setting) + "）"), () -> {
                if (!ctx.hasPermission("muz.admin")) {
                    ctx.error("这个菜单需要管理员权限才能打开。");
                    return;
                }
                plugin.adjustAdminSetting(setting, true, 1);
                ctx.open(this);
            }));
        } else {
            double step = effectiveStep(ctx.playerId());
            actions.add(ctx.action(Component.text("− 一步"), () -> click(ctx, false, 1)));
            actions.add(ctx.action(Component.text("+ 一步"), () -> click(ctx, true, 1)));
            actions.add(ctx.action(Component.text("− ×10"), () -> click(ctx, false, 10)));
            actions.add(ctx.action(Component.text("+ ×10"), () -> click(ctx, true, 10)));

            // 直接数字输入：无界（不夹声明区间），非有限值拒绝。
            boolean integer = setting.integerSetting();
            inputs.add(MuzDialogFactory.textInput(
                "value",
                Component.text(integer ? "直接输入整数" : "直接输入数值（不受范围限制）"),
                plugin.adminSettingValue(setting),
                32
            ));
            actions.add(ctx.action(Component.text("确认数字"), view -> applyText(ctx, view, integer)));

            // 滑块窗口＝「当前值 ± N 步」，中心锚在原值上（core 的 sliderWindow 用相对索引：
            // 第 0 档正好是原值、不按步长网格吸附；相对小整数也避免大值 + 极小步长时 float 端点并档跨步）。
            // 不传该设置声明的 min/max：那是已放开的硬限，不能再当滑块端点。
            AdminSettingArithmetic.SliderWindow window = AdminSettingArithmetic.sliderWindow(
                currentValue(),
                step,
                SLIDER_HALF_STEPS
            );
            inputs.add(MuzDialogFactory.numberInput(
                "slider",
                Component.text("滑块 · 相对当前 ±" + SLIDER_HALF_STEPS + " 步（0＝当前值）"),
                window.start(),
                window.end(),
                window.initial(),
                1f
            ));
            actions.add(ctx.action(Component.text("确认滑块"), view -> applySlider(ctx, view, window)));

            if (AdminStepPolicy.adjustable(setting)) {
                for (double preset : AdminStepPolicy.PRESETS) {
                    actions.add(ctx.action(Component.text("步长 " + AdminStepPolicy.label(preset)), () -> {
                        if (!ctx.hasPermission("muz.admin")) {
                            ctx.error("这个菜单需要管理员权限才能打开。");
                            return;
                        }
                        STEP_BY_PLAYER.put(ctx.playerId(), preset);
                        ctx.open(this);
                    }));
                }
                inputs.add(MuzDialogFactory.textInput("step", Component.text("自定义步长"), AdminStepPolicy.label(step), 16));
                actions.add(ctx.action(Component.text("应用自定义步长"), view -> {
                    if (!ctx.hasPermission("muz.admin")) {
                        ctx.error("这个菜单需要管理员权限才能打开。");
                        return;
                    }
                    double parsed = DialogInputParsers.parseFiniteDoubleRequired(ctx.text(view, "step"));
                    if (parsed <= 0.0) {
                        ctx.error("步长必须大于 0。");
                        return;
                    }
                    STEP_BY_PLAYER.put(ctx.playerId(), parsed);
                    ctx.open(this);
                }));
            }
        }

        AdminMenuModel.Page owner = pageOf();
        if (owner != null && owner.parent() != null) {
            // 设置屏是从某个管理页 push 进来的，返回即回那一页。
            actions.add(ctx.action(MuzTheme.warm("返回"), () -> ctx.back()));
        }

        return MuzDialogFactory.screen(
            MuzTheme.accent(setting.label()),
            true,
            body,
            inputs,
            actions,
            ctx.exit(MuzTheme.danger("关闭")),
            2
        );
    }

    /** 设置屏挂在哪个管理页下（用于决定是否给「返回」）。按设置项反查其归属页。 */
    private AdminMenuModel.Page pageOf() {
        for (AdminMenuModel.Page page : AdminMenuModel.pages()) {
            if (AdminMenuModel.settingsOf(page.id()).contains(setting)) {
                return page;
            }
        }
        // 没有独立入口的设置（历史遗留项）不在任何页内，不显示返回。
        return null;
    }

    private void click(MuzDialogContext ctx, boolean increase, int multiplier) {
        if (!ctx.hasPermission("muz.admin")) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        plugin.adjustAdminSetting(setting, increase, multiplier, effectiveStep(ctx.playerId()));
        ctx.open(this);
    }

    private void applyText(MuzDialogContext ctx, DialogResponseView view, boolean integer) {
        if (!ctx.hasPermission("muz.admin")) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        try {
            double value = integer
                ? DialogInputParsers.parseRequiredInt(ctx.text(view, "value"))
                : DialogInputParsers.parseFiniteDoubleRequired(ctx.text(view, "value"));
            plugin.setAdminSettingAbsolute(setting, value);
            ctx.open(this);
        } catch (IllegalArgumentException exception) {
            ctx.error(exception.getMessage());
        }
    }

    private void applySlider(MuzDialogContext ctx, DialogResponseView view, AdminSettingArithmetic.SliderWindow window) {
        if (!ctx.hasPermission("muz.admin")) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        Float raw = ctx.number(view, "slider");
        if (raw == null || !Float.isFinite(raw)) {
            ctx.error("滑块值无效。");
            return;
        }
        // 滑块读回的是**相对原值**的小整数偏移：0 ＝ 原值不动（不拖直接确认不会被步长网格吸附），
        // ±k ＝ 原值 ± k*step。valueAt 是唯一取值入口（绝对 fromStepIndex 会重新引入大值精度问题）。
        long relativeIndex = Math.round(raw);
        double value = window.valueAt(relativeIndex);
        plugin.setAdminSettingAbsolute(setting, value);
        ctx.open(this);
    }

    private Component valueLine() {
        return MuzTheme.accent("当前值 · " + plugin.adminSettingValue(setting));
    }

    private String stepLine(UUID playerId) {
        if (setting.booleanSetting()) {
            return "布尔项：点一下切换。";
        }
        if (setting.integerSetting()) {
            return "步长 · " + AdminStepPolicy.label(effectiveStep(playerId)) + "（整数项固定）";
        }
        if (setting.hasFixedStep()) {
            return "步长 · " + AdminStepPolicy.label(setting.fixedStep()) + "（该项固定）";
        }
        return "步长 · " + AdminStepPolicy.label(effectiveStep(playerId)) + "（跟随微调步长，可改）";
    }
}
