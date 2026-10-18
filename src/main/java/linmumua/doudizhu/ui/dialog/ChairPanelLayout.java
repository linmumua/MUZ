package linmumua.doudizhu.ui.dialog;

import java.util.Locale;
import java.util.Objects;
import linmumua.doudizhu.config.AdminSettingArithmetic;
import linmumua.doudizhu.config.ChairSidePanelSettings;
import linmumua.doudizhu.config.ChairSidePanelSettings.PanelLayout;
import linmumua.doudizhu.config.ChairSidePanelSettings.Side;

/**
 * 椅子左右面板布局的**编辑侧视图模型**。
 *
 * <p>【它不是第二份 schema】默认值、镜像算式、字段校验的真源都在
 * {@link ChairSidePanelSettings}。本类只是把那份快照包一层，补上编辑屏需要的三件事：
 * <ol>
 *   <li>按「字段枚举」读写单个分量（{@link ChairPanelLayoutField}），而不是让屏自己拼六个参数；</li>
 *   <li>落库统一按四位小数（{@link #storePrecision}），与既有管理项一致；</li>
 *   <li>尺寸等比联动：宽高比必须与承载面板的菜单画布一致，否则创建时会被宽高比校验拒绝。</li>
 * </ol>
 *
 * <p>镜像：{@link #withMirrored(Side)} 直接把一侧镜像到另一侧，算式**只**来自
 * {@link ChairSidePanelSettings#mirrored(PanelLayout)}（lateral/yaw/roll 取反，
 * depth/vertical/pitch 不变）。本类不重复实现它，避免两处算式漂移。
 *
 * <p>编辑只动被编辑的那一侧：单侧改动绝不顺手把同一个非对称值写给两侧——
 * 需求点名的那类 bug（左右写成同一个 x）在结构上不可能发生。要对称就显式按镜像按钮。
 */
public record ChairPanelLayout(ChairSidePanelSettings settings) {
    /**
     * 角度项的固定步长（度）。
     *
     * <p>放在本类而不是字段枚举里：Java 的 enum 常量参数不能引用**同一枚举**中后声明的静态字段，
     * 否则是 illegal forward reference。它是编译期常量，跨类引用没有初始化顺序问题。
     */
    public static final double ROTATION_STEP = 1.0;

    /**
     * 四位取整的安全上限。
     *
     * <p>{@link AdminSettingArithmetic#roundToStorePrecision} 是 {@code Math.round(v * 10000) / 10000}，
     * 当 {@code |v|} 大到乘积超出 long/double 的整数精度区时，取整会把值改成完全不同的数。
     * 布局的偏移与角度按用户要求**不设魔法上限**，所以这里在超区时保持原值：
     * 宁可少一次取整，也不能静默改写数值。
     */
    private static final double ROUNDING_SAFE_LIMIT = 1.0E12;

    public ChairPanelLayout {
        settings = Objects.requireNonNull(settings, "面板布局快照不能为空");
    }

    /** 出厂布局（真源在 schema：启停开、1.5 × 1.0、左右互为精确镜像）。 */
    public static ChairPanelLayout defaults() {
        return new ChairPanelLayout(ChairSidePanelSettings.defaults());
    }

    /** 由配置快照构造。 */
    public static ChairPanelLayout of(ChairSidePanelSettings settings) {
        return new ChairPanelLayout(settings);
    }

    /** 交回配置层落盘用的快照。 */
    public ChairSidePanelSettings toSettings() {
        return settings;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    public double width() {
        return settings.width();
    }

    public double height() {
        return settings.height();
    }

    /** 当前宽高比；出厂 1.5 : 1.0 = 3 : 2。 */
    public double aspectRatio() {
        return settings.width() / settings.height();
    }

    /** 左右两块面板当前是否**精确**互为镜像（用于概览屏如实显示「现在到底对不对称」）。 */
    public boolean exactlyMirrored() {
        return settings.right().equals(ChairSidePanelSettings.mirrored(settings.left()));
    }

    /** 读取某个字段的当前值。 */
    public double valueOf(ChairPanelLayoutField field) {
        Objects.requireNonNull(field, "字段不能为空");
        return switch (field) {
            case WIDTH -> settings.width();
            case HEIGHT -> settings.height();
            default -> field.read(settings.layout(field.panelSide()));
        };
    }

    /**
     * 把某个字段改成给定值。
     *
     * <p>只作用于该字段所属的那一侧（尺寸项除外）；另一侧原样保留。
     * 值先按四位精度归一，再交给 schema 的构造器做有限性与正数校验。
     *
     * @throws IllegalArgumentException 非有限值；或尺寸改动会导致宽/高不为正
     */
    public ChairPanelLayout withFieldValue(ChairPanelLayoutField field, double value) {
        Objects.requireNonNull(field, "字段不能为空");
        double normalized = storePrecision(value);
        return switch (field) {
            case WIDTH -> withWidth(normalized);
            case HEIGHT -> withHeight(normalized);
            default -> {
                Side side = field.panelSide();
                yield new ChairPanelLayout(settings.withLayout(side, field.write(settings.layout(side), normalized)));
            }
        };
    }

    /** 切换总开关。 */
    public ChairPanelLayout withEnabled(boolean enabled) {
        return new ChairPanelLayout(settings.withEnabled(enabled));
    }

    /**
     * 把 {@code from} 侧镜像到另一侧。
     *
     * <p>这是「左右对称」的唯一产生方式；算式委托给 schema，本类不自己算。
     *
     * <p>【几何含义，渲染侧必须照此实现】这里得到的是**几何镜像**：面板中心与朝向都对称，
     * 但**不是**把贴图水平翻转。三轴取反那组值构成正交变换 {@code R' = M·R·M}
     * （{@code M = diag(-1,1,1)}，{@code det(M) = -1} 被两侧夹住后 {@code det(R') = +1}），
     * 所以右面板的自身坐标系仍是右手系：文字正向、不需要负 scale、不需要翻 U。
     * 渲染侧要从旋转矩阵构造平面基（normal/up/right 由右手定则得出），不要用负缩放或镜像采样。
     */
    public ChairPanelLayout withMirrored(Side from) {
        Objects.requireNonNull(from, "镜像来源侧不能为空");
        PanelLayout source = settings.layout(from);
        return new ChairPanelLayout(
            settings.withLayout(from.opposite(), ChairSidePanelSettings.mirrored(source))
        );
    }

    /**
     * 文本输入的唯一入口：解析 + 校验 + 四位归一。
     *
     * <p>屏的「确认数字」直接用它，测试也直接打这条路径，避免「屏里一套、测试测另一套」。
     *
     * @throws IllegalArgumentException 输入为空 / 非数字 / 非有限 / 尺寸不为正
     */
    public static ChairPanelLayout applyRaw(ChairPanelLayout layout, ChairPanelLayoutField field, String raw) {
        if (layout == null || field == null) {
            throw new IllegalArgumentException("布局与字段不能为空");
        }
        return layout.withFieldValue(field, field.parse(raw));
    }

    /**
     * 尺寸等比联动：改宽度，高度按当前比例缩放。
     *
     * <p>为什么尺寸不能两个独立改：宽高比由承载面板的菜单画布决定。锚定面板创建时会用
     * {@code pixelsPerBlock = canvas.width / widthBlocks} 反解、再用同一个 ppb 校验高度，
     * 比例不符直接拒绝且**不创建任何实体**（失败是静默的）。所以「单独改宽」等于把面板弄没。
     */
    private ChairPanelLayout withWidth(double value) {
        double nextHeight = value / aspectRatio();
        if (!(value > 0.0) || !(nextHeight > 0.0) || !Double.isFinite(nextHeight)) {
            throw new IllegalArgumentException(
                "面板尺寸必须大于 0：宽度 " + format(value) + " 会让高度变成 " + format(nextHeight)
            );
        }
        return new ChairPanelLayout(settings.withSize(value, nextHeight));
    }

    /** 尺寸等比联动：改高度，宽度按当前比例缩放；理由与 {@link #withWidth} 相同。 */
    private ChairPanelLayout withHeight(double value) {
        double nextWidth = value * aspectRatio();
        if (!(value > 0.0) || !(nextWidth > 0.0) || !Double.isFinite(nextWidth)) {
            throw new IllegalArgumentException(
                "面板尺寸必须大于 0：高度 " + format(value) + " 会让宽度变成 " + format(nextWidth)
            );
        }
        return new ChairPanelLayout(settings.withSize(nextWidth, value));
    }

    /**
     * 四位精度归一（保留既有管理项的落库精度）。
     *
     * <p>负零统一归成 +0.0：{@code -0.0 == 0.0} 数值上等价，但 record 相等与显示会不一致，
     * 镜像出来的 {@code -0.0000} 也容易让人误以为左右不同。
     */
    static double storePrecision(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("数值必须是有限值：" + value);
        }
        if (Math.abs(value) > ROUNDING_SAFE_LIMIT) {
            // 超出四位取整可安全表示的量级：保持原值，绝不静默改成别的数。
            return value;
        }
        double rounded = AdminSettingArithmetic.roundToStorePrecision(value);
        return rounded == 0.0 ? 0.0 : rounded;
    }

    /** 四位小数的显示格式（与落库精度一致，避免界面看起来「点了没反应」）。 */
    public static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** 单侧位姿的显示文本，供概览屏与测试共用；分量顺序与 schema 一致。 */
    public static String describe(PanelLayout layout) {
        if (layout == null) {
            return "（未设置）";
        }
        return "位置 (lateral " + format(layout.lateral()) + ", vertical " + format(layout.vertical())
            + ", depth " + format(layout.depth()) + ")"
            + " 旋转 (pitch " + format(layout.pitch()) + ", yaw " + format(layout.yaw())
            + ", roll " + format(layout.roll()) + ")";
    }
}
