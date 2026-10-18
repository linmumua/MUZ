package linmumua.doudizhu.game;

import org.bukkit.util.Vector;

/**
 * 椅侧面板的**纯几何**：把锚点（中心 / 三轴角度 / 世界宽高）变成世界右手系坐标轴，
 * 并把视线命中的局部坐标映射到 3×3 九宫里。
 *
 * <p>【为什么单独一个纯函数类】面板显示（{@code ItemDisplay} 摆放）、命中判定（射线求交后的格位）、
 * 以及 Dialog 改完几何后的重绘都必须用**同一份**坐标轴与同一份分区规则。任何一处自己再推导一次
 * yaw，都会出现「看到的格子」和「点到的格子」差一格这种只在实际游玩时才发现的错位。
 * 这里不引用 Bukkit 服务端对象，只依赖向量与标量，因此可以在没有服务端的测试里逐条验算。
 *
 * <p>【轴的定义（与 Minecraft / MuMenu 一致）】{@link ChairPanelAnchor#yawDegrees()} 是
 * **观察者看向面板时的 yaw**，因此面板法线就是观察者视线的反向：
 * {@code normal = -forward(yaw, pitch)}，{@code forward = (-sin yaw·cos pitch, -sin pitch, cos yaw·cos pitch)}。
 * 右手轴 {@code right = worldUp × normal}，上轴 {@code up = normal × right}，两者复合出的是
 * **行列式 +1 的右手正交基**——这正是「左右镜像」要求的：镜像翻转位置与朝向，但文字与图标保持正向，
 * 不能用负缩放或翻转 UV 充数。
 *
 * <p>【九宫格】3 列 × 3 行共九格，索引 0..7 是八个真实道具槽，索引 8（右下角）是语音入口。
 * 用户界面上它是「一排排的格子」，内部一律 0-based，命令层的用户索引 1-based 由
 * {@code GadgetPanelService} 转换，不要在这里混用两套编号。
 */
public final class ChairPanelSurface {
    /** 列数。与八槽 + 语音入口的九格布局绑定，改动必须同步测试与文档。 */
    public static final int COLUMNS = 3;

    /** 行数。 */
    public static final int ROWS = 3;

    /** 总格数（含语音）。 */
    public static final int CELL_COUNT = COLUMNS * ROWS;

    /** 语音入口所在格（右下角）。 */
    public static final int VOICE_CELL = CELL_COUNT - 1;

    /** 落在面板矩形之外。 */
    public static final int OUTSIDE = -1;

    private static final double EPSILON = 1.0e-9;

    private ChairPanelSurface() {
    }

    /**
     * 面板的一组世界坐标轴。
     *
     * @param right  面板自身向右（观察者视角）的单位向量
     * @param up     面板自身向上的单位向量
     * @param normal 面板正面法线（指向观察者）的单位向量
     */
    public record Axes(Vector right, Vector up, Vector normal) {
    }

    /**
     * 由锚点的三轴角度合成世界坐标轴。
     *
     * <p>{@code pitch} 会让面板前倾/后仰，{@code roll} 绕面板法线自转。三轴都**真实生效**：
     * 旧实现只能竖直（up 恒为 (0,1,0)），配置里的俯仰与翻滚无处可放；内置渲染不再有那个缺口。
     *
     * @throws IllegalArgumentException 任一角度非有限数
     */
    public static Axes axes(float yawDegrees, float pitchDegrees, float rollDegrees) {
        requireFinite(yawDegrees, "yaw");
        requireFinite(pitchDegrees, "pitch");
        requireFinite(rollDegrees, "roll");
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        // 法线 = -视线方向；视线方向按 Minecraft 约定：yaw 0 朝 +Z，pitch 为正表示向下看。
        Vector normal = new Vector(
            Math.sin(yaw) * Math.cos(pitch),
            Math.sin(pitch),
            -Math.cos(yaw) * Math.cos(pitch));
        if (normal.lengthSquared() < EPSILON) {
            throw new IllegalArgumentException("面板法线退化：yaw=" + yawDegrees + " pitch=" + pitchDegrees);
        }
        normal.normalize();
        Vector right = new Vector(0.0, 1.0, 0.0).crossProduct(normal);
        if (right.lengthSquared() < EPSILON) {
            // pitch 恰为 ±90°：世界 up 与法线平行，叉积退化。取任意稳定水平轴，避免面板整体塌成零向量。
            right = new Vector(1.0, 0.0, 0.0).crossProduct(normal);
        }
        right.normalize();
        Vector up = normal.clone().crossProduct(right).normalize();
        double roll = Math.toRadians(rollDegrees);
        if (Math.abs(roll) > EPSILON) {
            right = rotateAround(right, normal, roll);
            up = rotateAround(up, normal, roll);
        }
        return new Axes(right, up, normal);
    }

    /**
     * 把面板局部坐标映射成格位索引。
     *
     * <p>局部原点在面板中心，X 向右、Y 向上，取值范围是 {@code ±halfWidth} / {@code ±halfHeight}。
     * 落在面板外的点返回 {@link #OUTSIDE}，调用方据此拒绝点击（不能默默就近取一格）。
     *
     * @return {@code 0..8}，或 {@link #OUTSIDE}
     */
    public static int cellAt(double localX, double localY, double halfWidth, double halfHeight) {
        if (!Double.isFinite(localX) || !Double.isFinite(localY)
            || !Double.isFinite(halfWidth) || !Double.isFinite(halfHeight)
            || halfWidth <= 0.0 || halfHeight <= 0.0) {
            return OUTSIDE;
        }
        if (Math.abs(localX) > halfWidth + EPSILON || Math.abs(localY) > halfHeight + EPSILON) {
            return OUTSIDE;
        }
        double u = (localX + halfWidth) / (2.0 * halfWidth);
        double v = (halfHeight - localY) / (2.0 * halfHeight);
        int column = clamp((int) Math.floor(u * COLUMNS), COLUMNS);
        // 从顶部往下数：局部 Y 最大那行是第 0 行，与玩家看到的排列一致。
        int row = clamp((int) Math.floor(v * ROWS), ROWS);
        return row * COLUMNS + column;
    }

    /**
     * 某一格中心的面板局部坐标。
     *
     * @param cell  {@code 0..8}
     * @param width 面板世界宽度（格）
     * @param height 面板世界高度（格）
     * @return 长度为 2 的数组：{@code [localX, localY]}
     */
    public static double[] cellCenter(int cell, double width, double height) {
        if (cell < 0 || cell >= CELL_COUNT) {
            throw new IllegalArgumentException("格位索引必须在 0.." + (CELL_COUNT - 1) + "，实际为 " + cell);
        }
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0.0 || height <= 0.0) {
            throw new IllegalArgumentException("面板宽高必须为正有限数，实际为 " + width + " × " + height);
        }
        double cellWidth = width / COLUMNS;
        double cellHeight = height / ROWS;
        int column = cell % COLUMNS;
        int row = cell / COLUMNS;
        return new double[] {
            -width * 0.5 + cellWidth * (column + 0.5),
            height * 0.5 - cellHeight * (row + 0.5)
        };
    }

    /** 某一格的图标缩放：留出格间空隙，避免相邻图标连成一片。 */
    public static float iconScale(double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0.0 || height <= 0.0) {
            throw new IllegalArgumentException("面板宽高必须为正有限数");
        }
        double byWidth = width / COLUMNS * 0.62;
        double byHeight = height / ROWS * 0.62;
        return (float) Math.max(0.05, Math.min(byWidth, byHeight));
    }

    private static int clamp(int value, int upperExclusive) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, upperExclusive - 1);
    }

    private static Vector rotateAround(Vector vector, Vector axis, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        Vector k = axis.clone().normalize();
        return vector.clone().multiply(cos)
            .add(k.clone().crossProduct(vector).multiply(sin))
            .add(k.clone().multiply(k.dot(vector) * (1.0 - cos)));
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " 必须为有限数字，实际为 " + value);
        }
    }
}
