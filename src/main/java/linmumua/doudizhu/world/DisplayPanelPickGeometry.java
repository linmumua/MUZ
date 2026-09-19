package linmumua.doudizhu.world;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * 显示面板的通用平面矩形求交。
 *
 * <p>面板不依赖实体碰撞箱：视线先与面板所在平面求交，再在面板自己的 right/up
 * 坐标系内判断是否落在矩形中。调用方可以据此让悬停和右键严格使用同一份拾取结果。
 */
public final class DisplayPanelPickGeometry {
    private static final double EPSILON = 1.0e-9;

    private DisplayPanelPickGeometry() {
    }

    /** 一个以 center 为中心、由 right/up 定义朝向的平面矩形。 */
    public record Rectangle(
        Location center,
        Vector normal,
        Vector right,
        Vector up,
        double halfWidth,
        double halfHeight
    ) {
        public Rectangle {
            Objects.requireNonNull(center, "center");
            Objects.requireNonNull(normal, "normal");
            Objects.requireNonNull(right, "right");
            Objects.requireNonNull(up, "up");
            if (!finitePositive(halfWidth) || !finitePositive(halfHeight)) {
                throw new IllegalArgumentException("面板半宽和半高必须为正数");
            }
            if (normal.lengthSquared() < EPSILON || right.lengthSquared() < EPSILON || up.lengthSquared() < EPSILON) {
                throw new IllegalArgumentException("面板法线和坐标轴不能为零向量");
            }
            normal = normal.clone().normalize();
            right = right.clone().normalize();
            up = up.clone().normalize();
        }

        private static boolean finitePositive(double value) {
            return Double.isFinite(value) && value > 0.0;
        }
    }

    /** 一次命中，distance 是从视线起点到平面的距离。 */
    public record Hit(double distance, double localX, double localY) {
    }

    /**
     * 求视线与矩形的最近正向交点。
     *
     * @return 命中结果；平行、背向、越界或超出最大距离时返回 {@code null}
     */
    public static Hit intersect(
        Location eye,
        Vector direction,
        Rectangle rectangle,
        double maxDistance
    ) {
        if (eye == null || direction == null || rectangle == null
            || !sameWorld(eye, rectangle.center())
            || !Double.isFinite(maxDistance) || maxDistance < 0.0
            || direction.lengthSquared() < EPSILON) {
            return null;
        }
        Vector ray = direction.clone().normalize();
        Vector normal = rectangle.normal();
        double denominator = ray.dot(normal);
        if (Math.abs(denominator) <= EPSILON) {
            return null;
        }
        Vector toCenter = rectangle.center().toVector().subtract(eye.toVector());
        double distance = toCenter.dot(normal) / denominator;
        if (!Double.isFinite(distance) || distance < -EPSILON || distance > maxDistance + EPSILON) {
            return null;
        }
        Vector point = eye.toVector().add(ray.clone().multiply(Math.max(0.0, distance)));
        Vector local = point.subtract(rectangle.center().toVector());
        double localX = local.dot(rectangle.right());
        double localY = local.dot(rectangle.up());
        if (Math.abs(localX) > rectangle.halfWidth() + EPSILON
            || Math.abs(localY) > rectangle.halfHeight() + EPSILON) {
            return null;
        }
        return new Hit(Math.max(0.0, distance), localX, localY);
    }

    private static boolean sameWorld(Location first, Location second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.getWorld() == null || second.getWorld() == null) {
            return first.getWorld() == null && second.getWorld() == null;
        }
        return first.getWorld().equals(second.getWorld());
    }

    /** 判断方块命中是否挡在面板之前。 */
    public static boolean occluded(Hit panelHit, double blockDistance) {
        return panelHit != null && Double.isFinite(blockDistance)
            && blockDistance <= panelHit.distance() + EPSILON;
    }

    /** 由固定 yaw 创建竖直面板，yaw 为 Minecraft 世界角度。 */
    public static Rectangle vertical(Location center, double width, double height, float yaw) {
        double radians = Math.toRadians(yaw);
        Vector normal = new Vector(-Math.sin(radians), 0.0, Math.cos(radians));
        Vector right = new Vector(Math.cos(radians), 0.0, Math.sin(radians));
        Vector up = new Vector(0.0, 1.0, 0.0);
        return new Rectangle(center, normal, right, up, width * 0.5, height * 0.5);
    }
}
