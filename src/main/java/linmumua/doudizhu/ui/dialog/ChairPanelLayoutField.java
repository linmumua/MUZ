package linmumua.doudizhu.ui.dialog;

import linmumua.doudizhu.config.AdminSettingArithmetic;
import linmumua.doudizhu.config.ChairSidePanelSettings;
import linmumua.doudizhu.config.ChairSidePanelSettings.PanelLayout;
import linmumua.doudizhu.config.ChairSidePanelSettings.Side;

/**
 * 椅子面板布局可编辑字段的元数据表。
 *
 * <p>刻意**不**并进 {@code DoudizhuPlugin.AdminSetting}：那张表是 19 页管理菜单的「一项一个标量」
 * 模型，而面板布局的 14 个数值同属两块面板的位姿，塞进去会让「这一项属于哪一侧、对应哪个分量」
 * 无处安放。这里单独一份枚举，只服务布局编辑屏。
 *
 * <p>分量命名与 schema 一致（{@link PanelLayout}）：{@code lateral} 左右、{@code vertical} 上下、
 * {@code depth} 前后、{@code pitch}/{@code yaw}/{@code roll} 角度。不另造 x/y/z 三套叫法，
 * 免得跨 owner 读代码时要反复换算。
 *
 * <p>步长语义沿用管理设置屏既有规则（不新造一套）：
 * <ul>
 *   <li><b>角度项</b>固定 {@link #ROTATION_STEP} 度，不吃玩家选的微调步长
 *       ——与 {@code CHAIR_ROTATION_DEGREES}、{@code BUTTON_ARC_*} 同口径；</li>
 *   <li><b>尺寸/位置项</b>跟随玩家在屏里选的步长（预设或自定义），声明步长只作兜底。</li>
 * </ul>
 *
 * <p>取值边界：按用户要求**不设魔法上下限**，只拒绝非有限值与「尺寸不为正」；
 * 这两条由 {@link ChairPanelLayout} 与 schema 构造器统一落实，枚举这里只做文本层校验。
 */
public enum ChairPanelLayoutField {
    /** 面板宽度（左右共用；改它高度按当前比例等比联动）。 */
    WIDTH(Scope.SHARED, Axis.SIZE, 0.05, 0.0, "面板宽度（联动高度）"),
    /** 面板高度（左右共用；改它宽度按当前比例等比联动）。 */
    HEIGHT(Scope.SHARED, Axis.SIZE, 0.05, 0.0, "面板高度（联动宽度）"),

    LEFT_LATERAL(Scope.LEFT, Axis.LATERAL, 0.05, 0.0, "左侧 · 左右 lateral"),
    LEFT_VERTICAL(Scope.LEFT, Axis.VERTICAL, 0.05, 0.0, "左侧 · 上下 vertical"),
    LEFT_DEPTH(Scope.LEFT, Axis.DEPTH, 0.05, 0.0, "左侧 · 前后 depth"),
    LEFT_PITCH(Scope.LEFT, Axis.PITCH, ChairPanelLayout.ROTATION_STEP, ChairPanelLayout.ROTATION_STEP, "左侧 · pitch"),
    LEFT_YAW(Scope.LEFT, Axis.YAW, ChairPanelLayout.ROTATION_STEP, ChairPanelLayout.ROTATION_STEP, "左侧 · yaw"),
    LEFT_ROLL(Scope.LEFT, Axis.ROLL, ChairPanelLayout.ROTATION_STEP, ChairPanelLayout.ROTATION_STEP, "左侧 · roll"),

    RIGHT_LATERAL(Scope.RIGHT, Axis.LATERAL, 0.05, 0.0, "右侧 · 左右 lateral"),
    RIGHT_VERTICAL(Scope.RIGHT, Axis.VERTICAL, 0.05, 0.0, "右侧 · 上下 vertical"),
    RIGHT_DEPTH(Scope.RIGHT, Axis.DEPTH, 0.05, 0.0, "右侧 · 前后 depth"),
    RIGHT_PITCH(Scope.RIGHT, Axis.PITCH, ChairPanelLayout.ROTATION_STEP, ChairPanelLayout.ROTATION_STEP, "右侧 · pitch"),
    RIGHT_YAW(Scope.RIGHT, Axis.YAW, ChairPanelLayout.ROTATION_STEP, ChairPanelLayout.ROTATION_STEP, "右侧 · yaw"),
    RIGHT_ROLL(Scope.RIGHT, Axis.ROLL, ChairPanelLayout.ROTATION_STEP, ChairPanelLayout.ROTATION_STEP, "右侧 · roll");

    /** 角度项的固定步长（度）。真源见 {@link ChairPanelLayout#ROTATION_STEP}。 */
    public static final double ROTATION_STEP = ChairPanelLayout.ROTATION_STEP;

    /** 字段归属：左/右，或左右共用（尺寸）。 */
    public enum Scope {
        SHARED,
        LEFT,
        RIGHT
    }

    /** 字段对应的分量轴。 */
    public enum Axis {
        /** 面板尺寸（宽/高）。 */
        SIZE,
        LATERAL,
        VERTICAL,
        DEPTH,
        PITCH,
        YAW,
        ROLL
    }

    private final Scope scope;
    private final Axis axis;
    private final double declaredStep;
    private final double fixedStep;
    private final String label;

    ChairPanelLayoutField(Scope scope, Axis axis, double declaredStep, double fixedStep, String label) {
        this.scope = scope;
        this.axis = axis;
        this.declaredStep = declaredStep;
        this.fixedStep = fixedStep;
        this.label = label;
    }

    public Scope scope() {
        return scope;
    }

    public Axis axis() {
        return axis;
    }

    public String label() {
        return label;
    }

    /** 是否左右共用（尺寸项）。 */
    public boolean shared() {
        return scope == Scope.SHARED;
    }

    /** 该字段所属的一侧；尺寸项不属于任何一侧，调用即错误。 */
    public Side panelSide() {
        return switch (scope) {
            case LEFT -> Side.LEFT;
            case RIGHT -> Side.RIGHT;
            case SHARED -> throw new IllegalArgumentException("尺寸字段不属于任何一侧：" + this);
        };
    }

    /**
     * 该字段的出厂值。
     *
     * <p>按「出厂布局的实际值」算出来，而不是再抄一份常量：抄一份就会在 schema 改默认值时漂移
     * （{@code fieldDefaultsMatchTheSchemaDefaults} 会因此变红）。
     */
    public double defaultValue() {
        ChairSidePanelSettings defaults = ChairSidePanelSettings.defaults();
        return switch (scope) {
            case SHARED -> this == WIDTH ? defaults.width() : defaults.height();
            case LEFT -> read(defaults.left());
            case RIGHT -> read(defaults.right());
        };
    }

    /** 该字段自己声明的步长（跟随项在玩家没选步长时的兜底）。 */
    public double declaredStep() {
        return declaredStep;
    }

    /** 是否钉死步长（角度项）。 */
    public boolean hasFixedStep() {
        return Double.isFinite(fixedStep) && fixedStep > 0.0;
    }

    /** 钉死的步长；未钉死返回 0。 */
    public double fixedStep() {
        return fixedStep;
    }

    /** 本次点击实际生效的步长：固定步长项忽略玩家选的步长，其余跟随（非法则回落声明值）。 */
    public double effectiveStep(double playerStep) {
        return hasFixedStep() ? fixedStep : AdminSettingArithmetic.effectiveStep(playerStep, declaredStep);
    }

    /** 角度项（pitch/yaw/roll）。 */
    public boolean rotation() {
        return axis == Axis.PITCH || axis == Axis.YAW || axis == Axis.ROLL;
    }

    /**
     * 从一侧布局里读出本字段的值。
     *
     * @throws IllegalArgumentException 本字段是尺寸项（不属于任何一侧）
     */
    public double read(PanelLayout layout) {
        if (layout == null) {
            throw new IllegalArgumentException("侧布局不能为空");
        }
        return switch (axis) {
            case SIZE -> throw new IllegalArgumentException("尺寸字段不属于任何一侧：" + this);
            case LATERAL -> layout.lateral();
            case VERTICAL -> layout.vertical();
            case DEPTH -> layout.depth();
            case PITCH -> layout.pitch();
            case YAW -> layout.yaw();
            case ROLL -> layout.roll();
        };
    }

    /**
     * 把一个新值写进一侧布局，其余分量原样保留。
     *
     * <p>参数顺序严格按 {@link PanelLayout} 的声明（lateral, depth, vertical, pitch, yaw, roll）：
     * depth 与 vertical 挨着且名字相近，写反了不会报错、只会让面板上下前后颠倒。
     *
     * @throws IllegalArgumentException 本字段是尺寸项（不属于任何一侧）
     */
    public PanelLayout write(PanelLayout layout, double value) {
        if (layout == null) {
            throw new IllegalArgumentException("侧布局不能为空");
        }
        return switch (axis) {
            case SIZE -> throw new IllegalArgumentException("尺寸字段不属于任何一侧：" + this);
            case LATERAL -> new PanelLayout(value, layout.depth(), layout.vertical(),
                layout.pitch(), layout.yaw(), layout.roll());
            case DEPTH -> new PanelLayout(layout.lateral(), value, layout.vertical(),
                layout.pitch(), layout.yaw(), layout.roll());
            case VERTICAL -> new PanelLayout(layout.lateral(), layout.depth(), value,
                layout.pitch(), layout.yaw(), layout.roll());
            case PITCH -> new PanelLayout(layout.lateral(), layout.depth(), layout.vertical(),
                value, layout.yaw(), layout.roll());
            case YAW -> new PanelLayout(layout.lateral(), layout.depth(), layout.vertical(),
                layout.pitch(), value, layout.roll());
            case ROLL -> new PanelLayout(layout.lateral(), layout.depth(), layout.vertical(),
                layout.pitch(), layout.yaw(), value);
        };
    }

    /**
     * 一次点击（± 一步 / ± ×10）后的新值。
     *
     * <p>刻意**不**接收上下限：布局字段没有声明区间，把某个区间当硬边界会把已经放开的幅度收回去。
     */
    public double stepBy(double current, double playerStep, int multiplier, boolean increase) {
        double delta = effectiveStep(playerStep) * Math.max(1, multiplier);
        double next = current + (increase ? delta : -delta);
        return ChairPanelLayout.storePrecision(next);
    }

    /**
     * 解析玩家输入的原始文本。
     *
     * <p>拒绝空、非数字、NaN、±无穷与「尺寸不为正」；数值范围本身不设上限（用户要求放开）。
     * 四位精度与最终校验由 {@link ChairPanelLayout#withFieldValue} 统一落实，避免两处各一套。
     *
     * @throws IllegalArgumentException 非法输入，消息可直接提示给玩家
     */
    public double parse(String raw) {
        double value = DialogInputParsers.parseFiniteDoubleRequired(raw);
        if (axis == Axis.SIZE && value <= 0.0) {
            throw new IllegalArgumentException(label() + "必须大于 0：" + raw);
        }
        return value;
    }
}
