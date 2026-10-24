package linmumua.doudizhu.world;

import java.util.List;
import java.util.UUID;

/**
 * 牌桌座位的**纯姿态快照**：只有世界坐标、朝向与 UUID，不持有任何 Bukkit 实体。
 *
 * <p>【为什么需要它】椅侧悬浮面板（MuMenu 双 surface）跑在**玩家 lane**，那条 lane 上读
 * {@code PlacedTable} 的实体（{@code Bukkit.getEntity} / {@code Entity#getLocation} /
 * {@code getNearbyEntities}）会直接踩 Folia 的 owner region 门禁。这里把「椅子在哪、
 * 朝哪、谁坐在上面」在**桌 owner 侧**算成不可变数据，面板层拿到的只是数字：
 * 不需要、也不允许再去碰任何活动实体。
 *
 * <p>【为什么不算面板尺寸、不提供左右锚点】几何只有一处来源。本记录只描述**椅子**这一层
 * （{@code chairLocation} / {@code handCardYaw} / 座位横向轴，都是牌桌已有的几何）。
 * 面板画布尺寸与左右偏移属于面板层（MuMenu 画布 + 配置键），两边各写一份必然漂移，
 * 因此 {@code TableSeatPose} 刻意**不**定义任何面板宽度/偏移常量；面板层用
 * {@link Seat#lateralX()} / {@link Seat#lateralZ()} 这一条横向轴，左右各取 {@code ±}
 * 同一份偏移即可保证对称。
 *
 * <p>【不变量】只读已放置牌桌的内存快照，不解析实体、不写世界、不触发区块加载；
 * 重建在飞期间可能返回旧锚点（与 {@code PhysicalTableManager.tableAnchor} 同语义）。
 * 未放置时返回 {@code null}，调用方据此不显示面板，而不是回退去读实体。
 */
public record TableSeatPose(
    String tableName,
    UUID worldId,
    Pose anchor,
    List<Seat> seats
) {
    /**
     * 世界坐标 + 朝向。
     *
     * <p>{@code pitch} 只为姿态可复现而保留；牌桌与椅子几何一律 0（Minecraft 的椅子不倾斜）。
     */
    public record Pose(double x, double y, double z, float yaw, float pitch) {
    }

    /**
     * 单个座位。
     *
     * @param seatIndex   座位号 0..2，与牌桌座位表、椅子几何、按钮偏移用的是同一套编号
     * @param occupantId  该座位当前占位玩家；空座为 {@code null}
     * @param chair       椅面基准点姿态，{@code yaw} 已经是就座者朝向
     * @param lateralX    座位横向轴（归一化、水平面内）的 X 分量；**+1 指向该座位玩家的右手边**
     * @param lateralZ    座位横向轴的 Z 分量；符号语义同 {@code lateralX}
     */
    public record Seat(
        int seatIndex,
        UUID occupantId,
        Pose chair,
        double lateralX,
        double lateralZ
    ) {
        /**
         * 就座者朝向（弧度无关，MC 角度制）。
         *
         * <p>单列成方法，是为了让面板层不必猜「该读 chair 里哪个字段」：
         * 就座朝向就是椅子朝向，两者永远同源，不额外存第二份。
         */
        public float facingYaw() {
            return chair == null ? 0.0f : chair.yaw();
        }
    }

    /** 按座位号取姿态；不存在返回 {@code null}。 */
    public Seat seat(int seatIndex) {
        for (Seat seat : seats) {
            if (seat.seatIndex() == seatIndex) {
                return seat;
            }
        }
        return null;
    }

    /**
     * 取该玩家的座位姿态；不在座返回 {@code null}。
     *
     * <p>面板层按玩家打开菜单时用这个：一次查表就知道该玩家坐在哪、朝哪，
     * 不需要再经 {@code getTableOf} + 实体解析两跳。
     */
    public Seat seatOf(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        for (Seat seat : seats) {
            if (playerId.equals(seat.occupantId())) {
                return seat;
            }
        }
        return null;
    }
}
