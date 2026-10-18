package linmumua.doudizhu.game;

import java.util.UUID;

/**
 * 一块**世界锚定**面板的只读快照：面板中心的世界坐标、观察者朝向角、以及在**世界方块**里的尺寸。
 *
 * <p>【为什么需要这个类型】MUZ 要的是「椅侧左右各一块、1.5 格宽 × 1 格高、不随玩家转头漂移」的面板，
 * 而 MuMenu 既有的 {@code open}/{@code openApplication} 路径是按「玩家眼睛 + 视线 yaw」推导屏面的，
 * 一个玩家同时只有一面、且会随看向重算。MuMenu 为此新增了世界锚定面板入口
 * （{@code openPanel}/{@code updatePanelAnchor}，锚点类型 {@code MuMenuPanelAnchor}），
 * 它的语义是「原点即面板中心、yaw 是观察者朝向、尺寸以世界格为单位」。
 * 本类型就是那套语义在 MUZ 侧的**本地只读镜像**：不依赖 MuMenu 任何类（无编译期依赖），
 * 因此可以纯数学地生成、比对与测试。
 *
 * <p>【单位约定（最容易被写错的地方）】{@link #widthBlocks()}/{@link #heightBlocks()} 是**世界方块**，
 * 不是逻辑像素、也不是 Dialog 尺寸。MuMenu 侧按 {@code pixelsPerBlock = 画布宽 / widthBlocks} 反解，
 * 因此菜单画布的宽高比必须严格等于 {@code widthBlocks : heightBlocks}；不一致时面板会被拒绝打开
 * （MuMenu 明确不拉伸、不就近取整）。本类型只表达「我要多大」，比例是否被接受由调用结果决定。
 *
 * <p>【yaw 约定】与 Minecraft 一致：观察者朝 {@link #yawDegrees()} 看时正对面板；
 * 等价于面板法线 {@code normal = -forward(yaw)}，{@code forward(yaw) = (-sin(yaw), 0, cos(yaw))}。
 * 所以「面板朝向坐在椅子上的人」就填**那个人看向面板时的 yaw**，不需要额外加 180°。
 *
 * @param panelId       调用方自定义的稳定标识（同一玩家内唯一），例如 {@code chair-left}
 * @param worldId       面板所在世界
 * @param x             面板**中心**的世界 X
 * @param y             面板**中心**的世界 Y
 * @param z             面板**中心**的世界 Z
 * @param yawDegrees    观察者朝向角（度），见上
 * @param pitchDegrees  俯仰（度）；仅当 MuMenu 的锚点类型确实接受该自由度时才会被投递
 * @param rollDegrees   翻滚（度）；同上
 * @param widthBlocks   世界宽度（格），必须为正有限数
 * @param heightBlocks  世界高度（格），必须为正有限数
 */
public record ChairPanelAnchor(
    String panelId,
    UUID worldId,
    double x,
    double y,
    double z,
    float yawDegrees,
    float pitchDegrees,
    float rollDegrees,
    double widthBlocks,
    double heightBlocks
) {
    /**
     * 与世界锚定面板一致的尺寸上限（格）。
     *
     * <p>与 MuMenu 的 {@code MuMenuPanelAnchor.MAX_BLOCKS}/{@code AnchoredPlanes.MAX_PANEL_BLOCKS} 同值：
     * 在 MUZ 侧先拒掉离谱参数，可以给玩家一句可读提示，而不是等对面抛异常再翻译。
     */
    public static final double MAX_BLOCKS = 16.0;

    public ChairPanelAnchor {
        if (panelId == null || panelId.isBlank()) {
            throw new IllegalArgumentException("面板标识不能为空");
        }
        if (worldId == null) {
            throw new IllegalArgumentException("面板世界不能为空：" + panelId);
        }
        requireFinite("x", x, panelId);
        requireFinite("y", y, panelId);
        requireFinite("z", z, panelId);
        if (!Float.isFinite(yawDegrees)) {
            throw new IllegalArgumentException("面板 yaw 必须为有限数字：" + panelId + " = " + yawDegrees);
        }
        if (!Float.isFinite(pitchDegrees)) {
            throw new IllegalArgumentException("面板 pitch 必须为有限数字：" + panelId + " = " + pitchDegrees);
        }
        if (!Float.isFinite(rollDegrees)) {
            throw new IllegalArgumentException("面板 roll 必须为有限数字：" + panelId + " = " + rollDegrees);
        }
        requireBlocks("widthBlocks", widthBlocks, panelId);
        requireBlocks("heightBlocks", heightBlocks, panelId);
    }

    /** 面板宽度（格）。命名更直白地对应 MuMenu 的 {@code widthBlocks}。 */
    public double width() {
        return widthBlocks;
    }

    /** 面板高度（格）。 */
    public double height() {
        return heightBlocks;
    }

    /**
     * 返回同标识、同尺寸、仅位置/朝向被替换的副本。
     *
     * <p>供 Dialog 的位置/旋转编辑使用：编辑只应该改姿态，不应该顺手改尺寸（尺寸属于布局配置，
     * 改它会让菜单画布比例失效）。
     */
    public ChairPanelAnchor withPose(
        double newX, double newY, double newZ,
        float newYawDegrees, float newPitchDegrees, float newRollDegrees
    ) {
        return new ChairPanelAnchor(
            panelId, worldId, newX, newY, newZ,
            newYawDegrees, newPitchDegrees, newRollDegrees, widthBlocks, heightBlocks);
    }

    /** 该快照是否要求除 yaw 之外的倾斜自由度（用于如实报告「引擎未支持」而不是静默丢弃）。 */
    public boolean hasUnsupportedTilt() {
        return Math.abs(pitchDegrees) > 1.0e-9 || Math.abs(rollDegrees) > 1.0e-9;
    }

    /** 面板中心到给定世界坐标的 XZ 距离平方；用于「这块板是不是已经离我很远」这类判据。 */
    public double horizontalDistanceSquared(double otherX, double otherZ) {
        double dx = otherX - x;
        double dz = otherZ - z;
        return dx * dx + dz * dz;
    }

    private static void requireFinite(String field, double value, String panelId) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                "面板 " + panelId + " 的 " + field + " 必须为有限数字，实际为 " + value);
        }
    }

    private static void requireBlocks(String field, double value, String panelId) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(
                "面板 " + panelId + " 的 " + field + " 必须为有限正数（世界格），实际为 " + value);
        }
        if (value > MAX_BLOCKS) {
            throw new IllegalArgumentException(
                "面板 " + panelId + " 的 " + field + " 不得超过 " + MAX_BLOCKS + " 格，实际为 " + value);
        }
    }
}
