package linmumua.doudizhu.game;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.config.ChairSidePanelSettings;
import linmumua.doudizhu.world.PhysicalTableManager;
import linmumua.doudizhu.world.TableSeatPose;

/**
 * 椅侧面板的**椅子锚点冻结快照**持有者：入座时拍一次，之后任何 lane 只读它。
 *
 * <p>【数据来源：{@code TableSeatPose}，不是自己算的椅子几何】世界层已经把「椅子在哪、朝哪、
 * 横向轴朝哪、谁坐在上面」做成纯姿态快照（{@code PhysicalTableManager.tableSeatPoseOf(UUID)}，
 * 内部复用 {@code chairLocation} 与 {@code handCardYaw}，全程不解析实体、不读区块）。
 * 本类只做**面板层**该做的事：把那份姿态按配置层布局摊成两块 {@link ChairPanelAnchor}。
 *
 * <p>【为什么仍要冻结】{@code tableSeatPoseOf} 本身可在任意 lane 调用，但面板几何必须在
 * 「入座那一刻」定住：否则玩家换座、换桌、或牌桌被重建时，已打开的面板坐标会悄悄跟不上。
 * 所以按「入座拍一次、离桌作废、必要时 {@link #capture} 重拍」的生命周期管理。
 *
 * <p>【不做什么】不打开/关闭面板（那是 {@link GadgetPanelService} + MuMenu 桥的事）、
 * 不读玩家实体、不写配置、不定义任何面板尺寸默认值（尺寸只有一个来源：配置层）。
 */
public class ChairPanelAnchorService {
    private final DoudizhuPlugin plugin;

    /** 玩家 UUID → 冻结的两块面板锚点。写入在入座/刷新路径，读取在 player lane，故用并发容器。 */
    private final Map<UUID, List<ChairPanelAnchor>> frozen = new ConcurrentHashMap<>();

    public ChairPanelAnchorService(DoudizhuPlugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * 拍一次该玩家所坐椅子的面板锚点快照（入座/换座/桌重建后调用）。
     *
     * <p>可安全在任意 lane 调用：底层只读内存快照、不解析实体。无条件不成立（不在座、未放置、
     * 布局关闭）时清掉旧快照并返回空列表。
     *
     * @param playerId 玩家 UUID
     * @param settings 配置层布局；null 或未启用时不产出面板并清掉旧快照
     * @return 冻结后的不可变锚点列表（{@code [left, right]}），无条件不成立时为空列表
     */
    public List<ChairPanelAnchor> capture(UUID playerId, ChairSidePanelSettings settings) {
        if (playerId == null) {
            return List.of();
        }
        TableSeatPose pose = seatPose(playerId);
        return store(playerId, pose == null ? List.of() : geometryOf(pose, playerId, settings));
    }

    /**
     * 用外部**已经算好**的姿态重拍快照（Dialog 编辑后应用新布局时使用）。
     *
     * @param pose 座位姿态（世界层纯快照），null 等价于清空
     */
    public List<ChairPanelAnchor> captureFrom(UUID playerId, TableSeatPose pose,
        ChairSidePanelSettings settings) {
        if (playerId == null) {
            return List.of();
        }
        return store(playerId, pose == null ? List.of() : geometryOf(pose, playerId, settings));
    }

    /**
     * 只读冻结快照；任何 lane 都可调用。
     *
     * @return 不可变列表；未拍过快照、或快照已被清掉时返回空列表（调用方据此提示「锚点未就绪」）
     */
    public List<ChairPanelAnchor> frozen(UUID playerId) {
        if (playerId == null) {
            return List.of();
        }
        List<ChairPanelAnchor> current = frozen.get(playerId);
        return current == null ? List.of() : current;
    }

    /** 该玩家是否已有可用快照（等价于 {@link #frozen} 非空）。 */
    public boolean hasSnapshot(UUID playerId) {
        return !frozen(playerId).isEmpty();
    }

    /** 离桌/断线/关桌时清掉快照；任何 lane 都可调用（只动本类自己的并发表）。 */
    public void forget(UUID playerId) {
        if (playerId != null) {
            frozen.remove(playerId);
        }
    }

    /** 停机清理。 */
    public void shutdown() {
        frozen.clear();
    }

    /**
     * 当前的牌桌座位姿态快照（只读、任意 lane 安全）；不在座/未放置返回 null。
     *
     * <p>供 Dialog 编辑面板位置时读取基准姿态：编辑应相对这个基准改偏移，
     * 而不是自己反推椅子坐标。
     */
    public TableSeatPose seatPose(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        PhysicalTableManager physical = plugin.getPhysicalTableManager();
        return physical == null ? null : physical.tableSeatPoseOf(playerId);
    }

    /** 该玩家的座位姿态（从牌桌姿态里取自己那一格）；不在座返回 null。 */
    public TableSeatPose.Seat seatOf(UUID playerId) {
        TableSeatPose pose = seatPose(playerId);
        return pose == null ? null : pose.seatOf(playerId);
    }

    private List<ChairPanelAnchor> store(UUID playerId, List<ChairPanelAnchor> anchors) {
        if (anchors.isEmpty()) {
            frozen.remove(playerId);
        } else {
            frozen.put(playerId, anchors);
        }
        return anchors;
    }

    /** 姿态 + 配置 → 两块锚点；任何前置缺失都返回空列表。 */
    private List<ChairPanelAnchor> geometryOf(TableSeatPose pose, UUID playerId,
        ChairSidePanelSettings settings) {
        TableSeatPose.Seat seat = pose.seatOf(playerId);
        if (seat == null) {
            return List.of();
        }
        List<ChairPanelAnchor> anchors = ChairPanelGeometry.panels(pose, seat, settings);
        return anchors.isEmpty() ? List.of() : List.copyOf(anchors);
    }
}
