package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import linmumua.doudizhu.config.AdminSettingArithmetic;
import linmumua.doudizhu.ui.MuzTheme;
import net.kyori.adventure.text.Component;

/**
 * 椅子面板布局的**单字段数值编辑**屏。
 *
 * <p>交互与 {@link AdminSettingDialogScreen} 同形（同一套手感，玩家不用学第二遍）：
 * <ul>
 *   <li>点击 ± 当前步长；原 Shift 的 ±10 倍改为显式「×10」倍率按钮；</li>
 *   <li>步长可选：预设（0.0001…1）+ 自定义；角度项钉死 1°（与 {@code CHAIR_ROTATION_DEGREES} 同口径）；</li>
 *   <li><b>精确文本与滑块各自独立确认</b>，互不覆盖；</li>
 *   <li>滑块用「当前值 ± N 步」的相对整数索引（{@link AdminSettingArithmetic#sliderWindow}）：
 *       第 0 档恰好等于当前值，不拖直接确认不会被步长网格吸附；</li>
 *   <li>非法输入（空、非数字、NaN、±无穷、尺寸不为正）一律提示并**不写回**，不钳制。</li>
 * </ul>
 *
 * <p>单侧字段只改那一侧：另一侧原样保留，绝不把同一个非对称值写给两侧（需求点名的那类 bug）。
 * 想要左右对称请回概览屏按镜像按钮。
 *
 * <p>不做 IO：保存与应用都经 {@link ChairPanelLayoutPort}，且保存是异步回调式的
 * ——这里绝不等待结果，也绝不在回调里做磁盘操作。
 */
public final class ChairPanelFieldDialogScreen implements MuzDialogScreen {
    /** 屏标识前缀；完整键为 {@code admin.chair-panel-layout:<FIELD>}。 */
    public static final String KEY_PREFIX = "admin.chair-panel-layout:";

    /** 滑块窗口：当前值 ± 这么多步。与管理设置屏取同一档数，手感一致。 */
    private static final int SLIDER_HALF_STEPS = 20;

    /**
     * 「跟随步长」项按玩家记住选的步长，跨字段共享（改一个字段不用重挑步长）。
     *
     * <p>与 {@link AdminSettingDialogScreen} 的 {@code STEP_BY_PLAYER} 同源同用途；那边是私有的，
     * 这里独立保存布局编辑的步长偏好，避免与通用管理设置屏相互覆盖。
     */
    private static final Map<UUID, Double> STEP_BY_PLAYER = new ConcurrentHashMap<>();

    private final ChairPanelLayoutPort port;
    private final ChairPanelLayoutField field;

    public ChairPanelFieldDialogScreen(ChairPanelLayoutPort port, ChairPanelLayoutField field) {
        this.port = Objects.requireNonNull(port, "布局读写接缝不能为空");
        this.field = Objects.requireNonNull(field, "编辑字段不能为空");
    }

    @Override
    public String screenKey() {
        return KEY_PREFIX + field.name();
    }

    @Override
    public String permission() {
        return ChairPanelLayoutDialogScreen.PERMISSION;
    }

    /** 玩家当前选的步长（仅「跟随步长」项有意义）。 */
    private double playerStep(UUID playerId) {
        return STEP_BY_PLAYER.getOrDefault(playerId, AdminStepPolicy.DEFAULT_STEP);
    }

    @Override
    public Dialog build(MuzDialogContext ctx) {
        ChairPanelLayout layout = Objects.requireNonNull(port.read(), "布局快照不能为空");
        double current = layout.valueOf(field);
        double step = field.effectiveStep(playerStep(ctx.playerId()));

        List<DialogBody> body = new ArrayList<>();
        body.add(MuzDialogFactory.body(MuzTheme.accent("当前值 · " + ChairPanelLayout.format(current))));
        body.add(MuzDialogFactory.body(MuzTheme.muted(scopeHint())));
        body.add(MuzDialogFactory.body(MuzTheme.muted(stepLine(step))));
        body.add(MuzDialogFactory.body(MuzTheme.muted(
            "范围 · 任意有限数，按四位小数落库；面板尺寸必须大于 0。非法值会被拒绝，不会被夹到边界。"
        )));

        List<DialogInput> inputs = new ArrayList<>();
        List<ActionButton> actions = new ArrayList<>();

        actions.add(ctx.action(Component.text("− 一步"), () -> click(ctx, false, 1)));
        actions.add(ctx.action(Component.text("+ 一步"), () -> click(ctx, true, 1)));
        actions.add(ctx.action(Component.text("− ×10"), () -> click(ctx, false, 10)));
        actions.add(ctx.action(Component.text("+ ×10"), () -> click(ctx, true, 10)));

        // 精确数字：独立确认，不与滑块互相覆盖。
        inputs.add(MuzDialogFactory.textInput(
            "value",
            Component.text("直接输入数值"),
            ChairPanelLayout.format(current),
            32
        ));
        actions.add(ctx.action(Component.text("确认数字"), view -> applyText(ctx, view)));

        // 滑块窗口＝「当前值 ± N 步」，以原值为锚（相对索引 0 ＝ 当前值）。
        AdminSettingArithmetic.SliderWindow window = AdminSettingArithmetic.sliderWindow(
            current,
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

        // 角度项钉死 1°，不给步长选项（全局 0.0001 调角度没有意义，且会与方块椅 90° 吸附打架）。
        if (!field.hasFixedStep()) {
            for (double preset : AdminStepPolicy.PRESETS) {
                actions.add(ctx.action(Component.text("步长 " + AdminStepPolicy.label(preset)), () -> selectStep(ctx, preset)));
            }
            inputs.add(MuzDialogFactory.textInput(
                "step",
                Component.text("自定义步长"),
                AdminStepPolicy.label(step),
                16
            ));
            actions.add(ctx.action(Component.text("应用自定义步长"), view -> applyCustomStep(ctx, view)));
        }

        actions.add(ctx.action(MuzTheme.warm("恢复本字段默认"), () -> restoreDefault(ctx)));
        actions.add(ctx.action(MuzTheme.muted("返回"), ctx::back));

        return MuzDialogFactory.screen(
            MuzTheme.accent(field.label()),
            true,
            body,
            inputs,
            actions,
            ctx.exit(MuzTheme.danger("关闭")),
            2
        );
    }

    /** 该字段属于哪一侧、改动会影响什么（让玩家按下确认前就知道作用范围）。 */
    private String scopeHint() {
        return switch (field.scope()) {
            case SHARED -> "作用范围 · 左右两块面板共用这个尺寸；改一边，另一边按当前比例等比缩放"
                + "（比例必须与菜单画布一致，否则锚定面板会被宽高比校验拒绝）。";
            case LEFT -> "作用范围 · 仅左侧；右侧保持原样。要左右对称请回概览屏按「镜像 · 左侧 → 右侧」。";
            case RIGHT -> "作用范围 · 仅右侧；左侧保持原样。要左右对称请回概览屏按「镜像 · 右侧 → 左侧」。";
        };
    }

    private String stepLine(double step) {
        if (field.hasFixedStep()) {
            return "步长 · " + AdminStepPolicy.label(step) + "（角度项固定）";
        }
        return "步长 · " + AdminStepPolicy.label(step) + "（跟随微调步长，可改）";
    }

    private void click(MuzDialogContext ctx, boolean increase, int multiplier) {
        if (!ctx.hasPermission(ChairPanelLayoutDialogScreen.PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        ChairPanelLayout base = port.read();
        double current = base.valueOf(field);
        double next = field.stepBy(current, playerStep(ctx.playerId()), multiplier, increase);
        try {
            commit(ctx, base.withFieldValue(field, next));
        } catch (IllegalArgumentException exception) {
            // 尺寸被降到 ≤0、或比例联动算出非正值时走这里：明确提示，不写回。
            ctx.error(exception.getMessage());
        }
    }

    private void applyText(MuzDialogContext ctx, DialogResponseView view) {
        if (!ctx.hasPermission(ChairPanelLayoutDialogScreen.PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        try {
            commit(ctx, ChairPanelLayout.applyRaw(port.read(), field, ctx.text(view, "value")));
        } catch (IllegalArgumentException exception) {
            ctx.error(exception.getMessage());
        }
    }

    private void applySlider(MuzDialogContext ctx, DialogResponseView view, AdminSettingArithmetic.SliderWindow window) {
        if (!ctx.hasPermission(ChairPanelLayoutDialogScreen.PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        Float raw = ctx.number(view, "slider");
        if (raw == null || !Float.isFinite(raw)) {
            ctx.error("滑块值无效。");
            return;
        }
        // 滑块读回的是**相对当前值**的小整数偏移：0 ＝ 原值不动，±k ＝ 原值 ± k*step。
        double value = window.valueAt(Math.round(raw));
        try {
            commit(ctx, port.read().withFieldValue(field, value));
        } catch (IllegalArgumentException exception) {
            // 尺寸项被拖到不为正时走这里：明确拒绝，不静默夹成最小值。
            ctx.error(exception.getMessage());
        }
    }

    private void selectStep(MuzDialogContext ctx, double step) {
        if (!ctx.hasPermission(ChairPanelLayoutDialogScreen.PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        STEP_BY_PLAYER.put(ctx.playerId(), step);
        ctx.open(this);
    }

    private void applyCustomStep(MuzDialogContext ctx, DialogResponseView view) {
        if (!ctx.hasPermission(ChairPanelLayoutDialogScreen.PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        try {
            double parsed = DialogInputParsers.parseFiniteDoubleRequired(ctx.text(view, "step"));
            if (parsed <= 0.0) {
                ctx.error("步长必须大于 0。");
                return;
            }
            STEP_BY_PLAYER.put(ctx.playerId(), parsed);
            ctx.open(this);
        } catch (IllegalArgumentException exception) {
            ctx.error(exception.getMessage());
        }
    }

    private void restoreDefault(MuzDialogContext ctx) {
        if (!ctx.hasPermission(ChairPanelLayoutDialogScreen.PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        // 只恢复「本字段」，不碰其它字段：玩家可能刚调好别的。
        commit(ctx, port.read().withFieldValue(field, field.defaultValue()));
    }

    /**
     * 保存 + 应用 + 重开本屏（与概览屏同一口径：保存成功才应用，失败只提示并按回滚值重画）。
     *
     * <p>保存是异步的，所以「重开」放在回调里；绝不在回调里等待结果。
     */
    private void commit(MuzDialogContext ctx, ChairPanelLayout next) {
        ChairPanelLayoutPort.saveThenApply(
            port,
            next,
            saved -> reopenIfStillCurrent(ctx),
            message -> {
                ctx.error(message);
                reopenIfStillCurrent(ctx);
            }
        );
    }

    /** 只在会话仍然有效时重开：异步保存落地前玩家可能已关面板或换了页。 */
    private void reopenIfStillCurrent(MuzDialogContext ctx) {
        if (ctx.service().requestCurrent(ctx.playerId(), ctx.generation())) {
            ctx.open(this);
        }
    }
}
