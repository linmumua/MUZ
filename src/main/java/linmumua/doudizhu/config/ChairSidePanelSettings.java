package linmumua.doudizhu.config;

import java.util.Objects;

/**
 * 椅侧双面板布局快照：每把椅子左右各一块世界面板的位置与旋转。
 *
 * <p>【唯一配置源】{@code config.yml} 的 {@code chair-side-panels} 段是这份布局的唯一定义处。
 * 本类只负责「读配置 → 校验 → 不可变快照」与「快照 → 写回配置键」，不持有 Bukkit 对象、
 * 不做 IO、不知道渲染实现。Dialog 编辑、世界渲染与跨插件桥接都必须消费同一份快照，
 * 任何一侧都不许再写第二份默认值或第二套镜像算式。
 *
 * <p>【坐标系】所有位置与旋转都是<b>椅子局部坐标系</b>，不是世界坐标：原点是该座位椅子的
 * 挂载点，水平方向的「左右」相对椅背朝向定义。因此三个座位共用同一套参数——椅子转过去，
 * 面板跟着转，不需要按座位各配一份（按座位独立覆盖属未实现的扩展，见下方边界说明）。
 *
 * <p>【镜像语义】{@link #mirrored(PanelLayout)} 把一侧参数镜像到另一侧：
 * {@code lateral} 取反、{@code yaw} 取反、{@code roll} 取反，{@code depth} /
 * {@code vertical} / {@code pitch} 保持不变。这不是经验近似：对「yaw → pitch → roll」
 * 的复合旋转做平面反射 {@code M = diag(-1, 1, 1)} 后共轭 {@code M·R·M}，因 {@code M} 是
 * 对合且共轭对乘法可分配，逐轴结果恰好是上述换算，三种角度的复合仍然精确。
 *
 * <p>【取值边界】偏移单位方块、角度单位度，全部接受任意有限值，<b>不设魔法上限</b>
 * （面板该摆多远、转到什么角度是布局自由）。但 NaN、正负无穷、非数字一律抛
 * {@link IllegalArgumentException} 明确报错，绝不静默钳成"看起来合法"的值；
 * 宽高额外要求为正数，因为 0 或负尺寸无法构成可渲染、可点击的面板。
 */
public record ChairSidePanelSettings(
    boolean enabled,
    double width,
    double height,
    PanelLayout left,
    PanelLayout right
) {
    /** 配置段根路径；读写两侧都从这里拼键，避免键名散落各处。 */
    public static final String ROOT = "chair-side-panels";

    public static final boolean DEFAULT_ENABLED = true;
    /** 单块面板的成品世界尺寸：1.5 方块宽 × 1.0 方块高（用户指定，不是像素也不是 GUI 尺寸）。 */
    public static final double DEFAULT_WIDTH = 1.5;
    public static final double DEFAULT_HEIGHT = 1.0;

    /**
     * 默认左右偏移：椅子模型宽约 0.875、面板宽 1.5，两者半宽之和为 1.19，
     * 取 1.2 让两块面板分别贴住椅子两侧、彼此留出约 1 厘米级间隙而不重叠。
     */
    public static final double DEFAULT_LATERAL = 1.2;
    public static final double DEFAULT_DEPTH = 0.0;
    /** 默认竖直偏移取 0.55，让 1.0 高的面板以椅子挂载点上方为中心、不压到座位。 */
    public static final double DEFAULT_VERTICAL = 0.55;
    public static final double DEFAULT_PITCH = 0.0;
    public static final double DEFAULT_YAW = 0.0;
    public static final double DEFAULT_ROLL = 0.0;

    public ChairSidePanelSettings {
        requirePositiveFinite(width, ROOT + ".width");
        requirePositiveFinite(height, ROOT + ".height");
        left = Objects.requireNonNull(left, ROOT + ".left");
        right = Objects.requireNonNull(right, ROOT + ".right");
    }

    /** 面板所在的一侧。符号约定集中在这里，镜像与默认值都复用它。 */
    public enum Side {
        LEFT("left", -1.0),
        RIGHT("right", 1.0);

        private final String key;
        private final double sign;

        Side(String key, double sign) {
            this.key = key;
            this.sign = sign;
        }

        /** 该侧在配置里的键名（{@code left} / {@code right}）。 */
        public String key() {
            return key;
        }

        /** 该侧的左右符号：左侧 -1、右侧 +1；{@code lateral} 的符号语义来自这里。 */
        public double sign() {
            return sign;
        }

        /** 另一侧。 */
        public Side opposite() {
            return this == LEFT ? RIGHT : LEFT;
        }
    }

    /**
     * 单块面板的局部布局。
     *
     * <p>六个分量全部允许任意有限值（见类注释的取值边界），只拒绝非有限值。
     * 校验放在紧凑构造器里，因此任何构造路径都不可能造出含 NaN / 无穷的实例，
     * 下游几何计算不必再逐字段判。
     */
    public record PanelLayout(
        double lateral,
        double depth,
        double vertical,
        double pitch,
        double yaw,
        double roll
    ) {
        public PanelLayout {
            requireFinite(lateral, "lateral");
            requireFinite(depth, "depth");
            requireFinite(vertical, "vertical");
            requireFinite(pitch, "pitch");
            requireFinite(yaw, "yaw");
            requireFinite(roll, "roll");
            // 归一 -0.0 为 0.0：镜像会把 0 取反成 -0.0，而 record 的 equals 走 Double.compare，
            // 判定 -0.0 与 0.0 不相等。不归一的话「出厂默认」和「从配置读回的同一份值」会不相等，
            // 还会把 -0.0 写进 YAML。
            lateral = normalizeZero(lateral);
            depth = normalizeZero(depth);
            vertical = normalizeZero(vertical);
            pitch = normalizeZero(pitch);
            yaw = normalizeZero(yaw);
            roll = normalizeZero(roll);
        }

        /** 该布局是否位于椅子的左侧（{@code lateral} 为负）；0 视为右侧，与 {@link Side} 的符号约定一致。 */
        public Side side() {
            return lateral < 0 ? Side.LEFT : Side.RIGHT;
        }
    }

    /** 出厂默认布局：左右互为精确镜像。 */
    public static ChairSidePanelSettings defaults() {
        PanelLayout left = new PanelLayout(
            -DEFAULT_LATERAL, DEFAULT_DEPTH, DEFAULT_VERTICAL, DEFAULT_PITCH, DEFAULT_YAW, DEFAULT_ROLL);
        return new ChairSidePanelSettings(DEFAULT_ENABLED, DEFAULT_WIDTH, DEFAULT_HEIGHT, left, mirrored(left));
    }

    /**
     * 从主配置读一份严格快照。
     *
     * <p>缺键取默认值（老配置升级后因此仍然可用，再由 {@code ensureConfigIntegrity} 的
     * 缺键补全把键写回文件）；键存在但类型或数值非法时直接抛异常，不 fallback——
     * 布局错位比启动失败更难排查。
     */
    public static ChairSidePanelSettings from(MuzYamlConfig config) {
        Objects.requireNonNull(config, "config");
        // 先确认段本身是映射：若写成 `chair-side-panels: false` 这种形状，
        // 后面每个子键都会读成"缺键"从而静默套默认值，把一次配置错误变成"看起来正常"。
        requireSection(config, ROOT);
        requireSection(config, layoutPath(Side.LEFT));
        requireSection(config, layoutPath(Side.RIGHT));
        return new ChairSidePanelSettings(
            readBoolean(config, ROOT + ".enabled", DEFAULT_ENABLED),
            readPositiveFinite(config, ROOT + ".width", DEFAULT_WIDTH),
            readPositiveFinite(config, ROOT + ".height", DEFAULT_HEIGHT),
            readLayout(config, Side.LEFT),
            readLayout(config, Side.RIGHT)
        );
    }

    /** 段存在但不是映射时明确报错；缺键照常允许（老配置由缺键补全补齐）。 */
    private static void requireSection(MuzYamlConfig config, String path) {
        Object value = config.get(path);
        if (value != null && !(value instanceof java.util.Map<?, ?>)) {
            throw new IllegalArgumentException(path + " 必须是映射（键值对），实际是：" + value);
        }
    }

    /** 取某一侧的布局。 */
    public PanelLayout layout(Side side) {
        return Objects.requireNonNull(side, "side") == Side.LEFT ? left : right;
    }

    /** 换掉某一侧的布局，返回新快照（本类是只读的，Dialog 编辑靠它产出新值）。 */
    public ChairSidePanelSettings withLayout(Side side, PanelLayout layout) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(layout, "layout");
        return side == Side.LEFT
            ? new ChairSidePanelSettings(enabled, width, height, layout, right)
            : new ChairSidePanelSettings(enabled, width, height, left, layout);
    }

    /** 换掉尺寸，返回新快照。 */
    public ChairSidePanelSettings withSize(double width, double height) {
        return new ChairSidePanelSettings(enabled, width, height, left, right);
    }

    /** 总开关翻转后的新快照。 */
    public ChairSidePanelSettings withEnabled(boolean enabled) {
        return new ChairSidePanelSettings(enabled, width, height, left, right);
    }

    /**
     * 把一侧布局镜像到另一侧：{@code lateral}、{@code yaw}、{@code roll} 取反，
     * 其余三个分量保持不变。派生关系见类注释（对复合旋转同样精确）。
     */
    public static PanelLayout mirrored(PanelLayout layout) {
        Objects.requireNonNull(layout, "layout");
        return new PanelLayout(
            -layout.lateral(),
            layout.depth(),
            layout.vertical(),
            layout.pitch(),
            -layout.yaw(),
            -layout.roll()
        );
    }

    /**
     * 把这份快照写回配置对象（只在内存里改键，落盘由调用方负责）。
     *
     * <p>键路径与 {@link #from(MuzYamlConfig)} 完全对称：读写共用 {@link #layoutPath}，
     * 不允许两边各拼一份字符串。
     */
    public void writeTo(MuzYamlConfig config) {
        Objects.requireNonNull(config, "config");
        config.set(ROOT + ".enabled", enabled);
        config.set(ROOT + ".width", width);
        config.set(ROOT + ".height", height);
        writeLayout(config, Side.LEFT, left);
        writeLayout(config, Side.RIGHT, right);
    }

    /** 某一侧布局在配置里的根路径，例如 {@code chair-side-panels.left}。 */
    public static String layoutPath(Side side) {
        return ROOT + "." + Objects.requireNonNull(side, "side").key();
    }

    private static void writeLayout(MuzYamlConfig config, Side side, PanelLayout layout) {
        String base = layoutPath(side);
        config.set(base + ".lateral", layout.lateral());
        config.set(base + ".depth", layout.depth());
        config.set(base + ".vertical", layout.vertical());
        config.set(base + ".pitch", layout.pitch());
        config.set(base + ".yaw", layout.yaw());
        config.set(base + ".roll", layout.roll());
    }

    private static PanelLayout readLayout(MuzYamlConfig config, Side side) {
        String base = layoutPath(side);
        // 缺键时按「该侧的默认值」补，而不是一律用左值镜像：
        // 用户可能只写了 left，此时 right 应回到出厂默认（它本来就是 left 的镜像）。
        PanelLayout fallback = defaults().layout(side);
        return new PanelLayout(
            readFinite(config, base + ".lateral", fallback.lateral()),
            readFinite(config, base + ".depth", fallback.depth()),
            readFinite(config, base + ".vertical", fallback.vertical()),
            readFinite(config, base + ".pitch", fallback.pitch()),
            readFinite(config, base + ".yaw", fallback.yaw()),
            readFinite(config, base + ".roll", fallback.roll())
        );
    }

    private static boolean readBoolean(MuzYamlConfig config, String path, boolean fallback) {
        Object value = config.get(path);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IllegalArgumentException(path + " 必须是 true 或 false，实际是：" + value);
    }

    private static double readFinite(MuzYamlConfig config, String path, double fallback) {
        Object value = config.get(path);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " 必须是数字，实际是：" + value);
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            // NaN / 无穷会让下游的旋转与 AABB 计算全部失效，必须在这里拦住。
            throw new IllegalArgumentException(path + " 必须是有限数字（不接受 NaN 或无穷），实际是：" + result);
        }
        return result;
    }

    private static double readPositiveFinite(MuzYamlConfig config, String path, double fallback) {
        double value = readFinite(config, path, fallback);
        if (value <= 0) {
            throw new IllegalArgumentException(path + " 必须大于 0，实际是：" + value);
        }
        return value;
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("椅侧面板参数 " + field + " 必须是有限数字，实际是：" + value);
        }
    }

    /** 把 -0.0 归一成 0.0；数值不变，只消除表示差异带来的 equals / YAML 写出差异。 */
    private static double normalizeZero(double value) {
        return value == 0.0 ? 0.0 : value;
    }

    private static void requirePositiveFinite(double value, String field) {
        requireFinite(value, field);
        if (value <= 0) {
            throw new IllegalArgumentException(field + " 必须大于 0，实际是：" + value);
        }
    }
}
