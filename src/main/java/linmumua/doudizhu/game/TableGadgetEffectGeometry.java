package linmumua.doudizhu.game;

/**
 * 三道具效果共用的纯几何与时序计算。
 *
 * <p>这里不读取 Bukkit 状态，也不生成实体；把动画的边界条件集中在可直接单测的
 * 数值函数中，避免渲染服务和测试各自抄一份抛物线或 tick 终点逻辑。
 */
public final class TableGadgetEffectGeometry {
    private TableGadgetEffectGeometry() {
    }

    public static double clampProgress(int tick, int durationTicks) {
        if (durationTicks <= 0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, tick / (double) durationTicks));
    }

    public static boolean expired(int tick, int durationTicks) {
        return durationTicks <= 0 || tick >= durationTicks;
    }

    /**
     * 短抛物线位置：水平插值，垂直方向额外叠加 arc * 4p(1-p)，首尾严格落在端点。
     */
    public static Point projectilePosition(Point start, Point end, int tick, int durationTicks, double arc) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("抛物线端点不能为空。");
        }
        double progress = clampProgress(tick, durationTicks);
        double lift = arc * 4.0 * progress * (1.0 - progress);
        return new Point(
            start.x() + (end.x() - start.x()) * progress,
            start.y() + (end.y() - start.y()) * progress + lift,
            start.z() + (end.z() - start.z()) * progress
        );
    }

    public static boolean withinRadius(Point point, Point center, double radius) {
        if (point == null || center == null || !Double.isFinite(radius) || radius < 0.0) {
            return false;
        }
        double dx = point.x() - center.x();
        double dy = point.y() - center.y();
        double dz = point.z() - center.z();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** 返回水幕当前高度，始终不越过 bodyHeight。 */
    public static double waterHeight(double bodyHeight, int tick, int durationTicks) {
        if (!Double.isFinite(bodyHeight) || bodyHeight <= 0.0) {
            return 0.0;
        }
        return bodyHeight * clampProgress(tick, durationTicks);
    }

    /** 头顶固定时，水幕显示实体中心应向下偏移半个当前高度。 */
    public static double waterDisplayCenterY(double bodyMaxY, double bodyHeight, int tick, int durationTicks) {
        return bodyMaxY - waterHeight(bodyHeight, tick, durationTicks) * 0.5;
    }

    /** 头顶固定时，水幕底边随高度增加向脚部下降。 */
    public static double waterBottomY(double bodyMaxY, double bodyHeight, int tick, int durationTicks) {
        return bodyMaxY - waterHeight(bodyHeight, tick, durationTicks);
    }

    /** 水效果由水幕和头顶水桶两个 Display 组成，投掷效果只有一个。 */
    public static int entityCost(boolean waterEffect) {
        return waterEffect ? 2 : 1;
    }

    public static int clampConcurrentLimit(int requested, int hardLimit) {
        if (hardLimit <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(requested, hardLimit));
    }

    public record Point(double x, double y, double z) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("几何坐标必须是有限数值。");
            }
        }
    }
}
