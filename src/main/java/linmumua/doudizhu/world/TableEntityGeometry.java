package linmumua.doudizhu.world;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.Set;

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

    /** 牌桌自有实体的保护 tag：由 {@link PhysicalTableManager} 写入，也用于残留清理的归属判定。 */
    public static final String TABLE_PROTECTED_TAG = "muz_table_protected";

    /** 麻将自有实体的保护 tag：由 {@link linmumua.doudizhu.mahjong.MahjongTableManager} 写入。 */
    public static final String MAHJONG_PROTECTED_TAG = "muz_mahjong_protected";

    /**
     * 共享保护链认的全部保护 tag。
     *
     * <p>两个子领域各自写自己的 tag，但「这个实体是否受保护」的判定必须只有一份：
     * 分头写会让破坏保护在某一边漏掉。新增子领域时在这里登记，不要各写各的字符串。
     *
     * <p>注意这只是保护判定的集合，<b>不是</b>归属判定：残留清理要区分「无人认领的牌桌实体」，
     * 那里只能认 {@link #TABLE_PROTECTED_TAG}，否则麻将实体会被当成缺 owner 的牌桌实体。
     */
    public static final Set<String> PROTECTED_TAGS = Set.of(TABLE_PROTECTED_TAG, MAHJONG_PROTECTED_TAG);

    /**
     * 该实体是否带任一保护 tag。
     *
     * <p>只查实体自身，不遍历 vehicle 链：链的遍历策略由调用方决定（牌桌保护链要追乘客，
     * 家具判定则不需要）。
     */
    public static boolean hasProtectionTag(Entity entity) {
        if (entity == null) {
            return false;
        }
        Set<String> tags = entity.getScoreboardTags();
        for (String tag : PROTECTED_TAGS) {
            if (tags.contains(tag)) {
                return true;
            }
        }
        return false;
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
     * <p>共用于 {@link PhysicalTableManager}（tag = {@link #TABLE_PROTECTED_TAG}）和
     * {@link linmumua.doudizhu.mahjong.MahjongTableManager}（tag = {@link #MAHJONG_PROTECTED_TAG}）。
     * 两者 tag 不同但保护逻辑完全一致，因此必须共用，避免改一处漏一处；tag 字符串本身
     * 也只在 {@link #PROTECTED_TAGS} 里登记一次，保护判定统一走 {@link #hasProtectionTag(Entity)}。
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
