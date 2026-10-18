package linmumua.doudizhu.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import linmumua.doudizhu.config.ChairSidePanelSettings;
import linmumua.doudizhu.ui.MuzTheme;
import net.kyori.adventure.text.Component;

/**
 * 椅子左右面板的**布局概览**屏：看当前尺寸/两侧位姿、开关与镜像、进入单字段编辑。
 *
 * <p>面板本体是世界里两块真实独立的面板（出厂各 1.5 × 1.0 世界格），位置与旋转都记在
 * **椅子局部坐标**里；本屏只负责改这份布局，不自己摆实体、不自己读配置、不自己落盘
 * （全部经 {@link ChairPanelLayoutPort}）。
 *
 * <p>镜像在这里是**显式动作**而不是隐式模式：schema 存的是两侧各自的六个分量，
 * 所以「对称」由「把左侧镜像到右侧」这类按钮产生，按下即用
 * {@code ChairSidePanelSettings.mirrored} 的算式覆盖目标侧。
 * 单侧编辑只改那一侧——需求点名要避免的「把左右写成同一个非对称坐标」在结构上不可能发生。
 *
 * <p>权限、会话代次、player lane、关闭期早退全部由 core 的 {@link MuzDialogContext} 负责；
 * 本屏只在回调里复核 {@code muz.admin}，不碰调度器、不做 IO。
 */
public final class ChairPanelLayoutDialogScreen implements MuzDialogScreen {
    /** 屏标识，仅用于会话审计。 */
    public static final String KEY = "admin.chair-panel-layout";

    /** 管理侧屏的权限门槛（core 在打开前与每次回调内各复核一次）；单字段屏复用本常量。 */
    public static final String PERMISSION = "muz.admin";

    private final ChairPanelLayoutPort port;

    public ChairPanelLayoutDialogScreen(ChairPanelLayoutPort port) {
        this.port = Objects.requireNonNull(port, "布局读写接缝不能为空");
    }

    @Override
    public String screenKey() {
        return KEY;
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public Dialog build(MuzDialogContext ctx) {
        ChairPanelLayout layout = Objects.requireNonNull(port.read(), "布局快照不能为空");

        List<DialogBody> body = new ArrayList<>();
        body.add(MuzDialogFactory.body(MuzTheme.accent(
            "状态 · " + (layout.enabled() ? "启用" : "停用")
                + "　尺寸 · 宽 " + ChairPanelLayout.format(layout.width())
                + " × 高 " + ChairPanelLayout.format(layout.height())
                + "（比例 " + ChairPanelLayout.format(layout.aspectRatio()) + " : 1）"
        )));
        body.add(MuzDialogFactory.body(MuzTheme.accent(
            "左侧 · " + ChairPanelLayout.describe(layout.toSettings().left())
        )));
        body.add(MuzDialogFactory.body(MuzTheme.accent(
            "右侧 · " + ChairPanelLayout.describe(layout.toSettings().right())
        )));
        body.add(MuzDialogFactory.body(MuzTheme.muted(layout.exactlyMirrored()
            ? "当前左右互为精确镜像。"
            : "当前左右**不**互为镜像（各自独立）。要对称请用下面的镜像按钮。")));
        body.add(MuzDialogFactory.body(MuzTheme.muted(mirrorHint())));
        body.add(MuzDialogFactory.body(MuzTheme.muted(
            "坐标是椅子局部坐标（格）：lateral 向椅子右侧为正、vertical 向上、depth 朝桌子；"
                + "角度单位为度，相对椅子朝向。"
        )));
        body.add(MuzDialogFactory.body(MuzTheme.muted(
            "尺寸联动：改宽或改高会按当前比例等比缩放另一边。比例必须与菜单画布一致（出厂 1.5 : 1.0 = 3 : 2），"
                + "否则锚定面板会被宽高比校验拒绝、直接不创建。"
        )));

        List<ActionButton> actions = new ArrayList<>();
        for (ChairPanelLayoutField field : ChairPanelLayoutField.values()) {
            actions.add(ctx.action(
                Component.text(field.label() + " · " + ChairPanelLayout.format(layout.valueOf(field))),
                () -> openField(ctx, field)
            ));
        }

        actions.add(ctx.action(
            Component.text(layout.enabled() ? "面板 · 启用（点此停用）" : "面板 · 停用（点此启用）"),
            () -> toggleEnabled(ctx)
        ));
        actions.add(ctx.action(MuzTheme.warm("镜像 · 左侧 → 右侧"), () -> mirror(ctx, ChairSidePanelSettings.Side.LEFT)));
        actions.add(ctx.action(MuzTheme.warm("镜像 · 右侧 → 左侧"), () -> mirror(ctx, ChairSidePanelSettings.Side.RIGHT)));
        actions.add(ctx.action(MuzTheme.warm("恢复默认布局"), () -> restoreDefaults(ctx)));
        actions.add(ctx.action(MuzTheme.warm("重新应用（刷新面板）"), () -> reapply(ctx)));
        actions.add(ctx.action(MuzTheme.muted("返回"), ctx::back));

        return MuzDialogFactory.screen(
            MuzTheme.accent("椅子左右面板"),
            true,
            body,
            List.of(),
            actions,
            ctx.exit(MuzTheme.danger("关闭")),
            2
        );
    }

    /**
     * 镜像说明文案。
     *
     * <p>需求明确要求「独立旋转若支持需说明镜像对应」，所以三轴对应关系必须写在玩家可见处，
     * 不能只活在代码注释里。
     *
     * <p>同时要说清一件事：镜像得到的是**几何镜像**（面板中心与朝向都对称），
     * 不是把贴图水平翻转。三个角度的这组取反恰好构成正交变换
     * {@code R' = M·R·M}（M = diag(-1,1,1)，det = +1），因此面板自身的裁剪空间仍是右手系，
     * 文字正向、无需负 scale、也不需要翻 U——渲染侧不要做 flip。
     */
    static String mirrorHint() {
        return "镜像对应 · lateral 取反、yaw 取反、roll 取反；vertical / depth / pitch 保持不变。"
            + "得到的是几何镜像（中心与朝向都对称），面板自身仍是右手系：文字正向，不做贴图翻转。";
    }

    private void openField(MuzDialogContext ctx, ChairPanelLayoutField field) {
        if (!ctx.hasPermission(PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        ctx.push(new ChairPanelFieldDialogScreen(port, field));
    }

    private void toggleEnabled(MuzDialogContext ctx) {
        if (!ctx.hasPermission(PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        // 以「回调时刻」的最新快照为准，不拿 build 时捕获的旧值：玩家可能刚在字段屏改过。
        ChairPanelLayout base = port.read();
        commit(ctx, base.withEnabled(!base.enabled()));
    }

    private void mirror(MuzDialogContext ctx, ChairSidePanelSettings.Side from) {
        if (!ctx.hasPermission(PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        commit(ctx, port.read().withMirrored(from));
    }

    private void restoreDefaults(MuzDialogContext ctx) {
        if (!ctx.hasPermission(PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        commit(ctx, ChairPanelLayout.defaults());
    }

    private void reapply(MuzDialogContext ctx) {
        if (!ctx.hasPermission(PERMISSION)) {
            ctx.error("这个菜单需要管理员权限才能打开。");
            return;
        }
        port.apply(port.read());
        ctx.message(MuzTheme.success("已请求刷新左右面板。"));
        ctx.open(this);
    }

    /**
     * 保存 + 应用 + 重开本屏。
     *
     * <p>保存是异步的（落盘失败会回滚），所以重开放在回调里；失败时先把配置层给出的原因
     * 原样提示给管理员，再按回滚后的真实值重画——不留「界面显示已保存、文件其实是旧值」的假成功。
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

    /**
     * 只在会话仍然有效时重开。
     *
     * <p>回调可能在异步保存完成后才到达，期间玩家可能已经关掉面板或换了页；那时重开会把
     * 面板硬塞回来。代次比对沿用 core 的会话注册表，不新造一套。
     */
    private void reopenIfStillCurrent(MuzDialogContext ctx) {
        if (ctx.service().requestCurrent(ctx.playerId(), ctx.generation())) {
            ctx.open(this);
        }
    }
}
