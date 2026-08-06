package linmumua.doudizhu.world;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

/**
 * 牌桌实体的共用几何工具方法。
 *
 * <p>被 {@link PhysicalTableManager} 和 {@link linmumua.doudizhu.mahjong.MahjongTableManager}
 * 共用。两者都需要用视线射线判断玩家正在看哪张桌子，算法完全相同，因此必须共用，
 * 避免改一处漏一处。
 */
public final class TableEntityGeometry {

    private TableEntityGeometry() {
    }

    /**
     * 计算视线射线到球形目标的距离。
     *
     * <p>把目标视为一个以 {@code center} 为圆心、{@code radius} 为半径的球体，
     * 返回视线射线（起点 {@code eye}，方向 {@code direction}）到该球最近投影距离；
     * 若射线不命中或距离超出 {@code maxDistance}，返回 -1。
     *
     * <p>共用于 {@link PhysicalTableManager} 和
     * {@link linmumua.doudizhu.mahjong.MahjongTableManager} 的「目视选桌」逻辑。
     *
     * @param eye         射线起点（玩家眼睛位置）
     * @param direction   射线方向（已归一化）
     * @param center      球心（桌子中心）
     * @param radius      判定半径
     * @param maxDistance  最大有效距离
     * @return 命中时的投影距离，未命中返回 -1
     */
    public static double sightDistance(Location eye, Vector direction, Location center, double radius, double maxDistance) {
        if (eye == null || center == null || eye.getWorld() == null || center.getWorld() == null) {
            return -1.0;
        }
        if (!eye.getWorld().equals(center.getWorld())) {
            return -1.0;
        }
        Vector offset = center.toVector().subtract(eye.toVector());
        double projection = offset.dot(direction);
        if (projection < 0.0 || projection > maxDistance) {
            return -1.0;
        }
        Vector closest = eye.toVector().add(direction.clone().multiply(projection));
        double radiusSquared = radius * radius;
        return closest.distanceSquared(center.toVector()) <= radiusSquared ? projection : -1.0;
    }

    /**
     * 将实体标记为牌桌保护实体：不可破坏、不持久化、无重力、添加保护 tag。
     *
     * <p>共用于 {@link PhysicalTableManager}（tag = {@code "muz_table_protected"}）和
     * {@link linmumua.doudizhu.mahjong.MahjongTableManager}（tag = {@code "muz_mahjong_protected"}）。
     * 两者 tag 不同但保护逻辑完全一致，因此必须共用，避免改一处漏一处。
     *
     * @param entity 需要保护的实体
     * @param tag    scoreboard tag 字符串
     */
    public static void protectEntity(Entity entity, String tag) {
        entity.setInvulnerable(true);
        entity.setPersistent(false);
        entity.setGravity(false);
        entity.addScoreboardTag(tag);
    }
}
