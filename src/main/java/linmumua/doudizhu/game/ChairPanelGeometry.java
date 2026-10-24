package linmumua.doudizhu.game;

import java.util.List;
import java.util.UUID;
import linmumua.doudizhu.config.ChairSidePanelSettings;
import linmumua.doudizhu.world.TableSeatPose;

/**
 * 把「椅子姿态 + 配置层布局」变成**左右两块世界锚定面板**的纯几何。
 *
 * <p>【职责边界（三方协商的结果）】
 * <ul>
 *   <li>世界层（{@code TableSeatPose}）只给椅子在哪、朝哪、横向轴朝哪，<b>不</b>定义面板尺寸；</li>
 *   <li>配置层（{@link ChairSidePanelSettings}）是尺寸与左右偏移的<b>唯一定义处</b>（1.5 × 1.0 方块）；</li>
 *   <li>本类只做合成：椅子姿态 + 局部位移 → 世界锚点。三处各写一份必然漂移，所以这里不存任何默认值。</li>
 * </ul>
 *
 * <p>【为什么是纯函数、不接收 Player/Entity】用户明确要求面板「不随玩家随意转头漂移」。
 * 把玩家视线从签名里彻底排除，这条要求就<b>在类型层面无法被违反</b>：本类拿不到眼睛、
 * 拿不到视线 yaw，也就不可能算出跟头转动的屏面。
 *
 * <p>【yaw 语义（最容易接反的地方）】配置里的 {@code yaw} 是**叠加在「面板→坐者」基准上的偏转**，
 * 基准由 {@link #viewerYawToward} 按实际坐标算出，因此无论 {@code lateral}/{@code depth} 怎么配，
 * 面板都正对坐着的人。MuMenu 的锚点语义正是「观察者朝该 yaw 看时正对面板」，两者在此对齐。
 * <b>不要</b>改成「就座朝向 + 固定 180°」：那只在 {@code depth == 0} 时成立，
 * 一旦面板前移就会斜着对人（本地测试已用 {@code depth>0} 锁住这条）。
 *
 * <p>【三轴已完整表达】面板由 MUZ 内置渲染（{@link ChairPanelSurface} 合成坐标轴），
 * {@code yaw} / {@code pitch} / {@code roll} 全部生效；{@link #unsupportedTilts} 因此恒为空，
 * 仅为兼容历史调用方保留。
 */
public final class ChairPanelGeometry {
    /** 左侧（坐者视角）面板的稳定标识；同一玩家内唯一。 */
    public static final String PANEL_ID_LEFT = "chair-left";

    /** 右侧（坐者视角）面板的稳定标识。 */
    public static final String PANEL_ID_RIGHT = "chair-right";

    private ChairPanelGeometry() {
    }

    /**
     * 按配置层的布局生成左右两块面板锚点。
     *
     * @param worldId  面板所在世界
     * @param seat     座位姿态（来自世界层的纯快照）
     * @param settings 配置层布局；为 {@code null} 或未启用时返回空列表
     * @return 顺序固定为 {@code [left, right]}；未启用或姿态缺失时为空列表
     */
    public static List<ChairPanelAnchor> panels(
        TableSeatPose pose,
        TableSeatPose.Seat seat,
        ChairSidePanelSettings settings
    ) {
        if (pose == null || pose.worldId() == null || pose.anchor() == null
            || seat == null || seat.chair() == null
            || settings == null || !settings.enabled()) {
            return List.of();
        }
        return List.of(
            panelOf(pose, seat, settings, ChairSidePanelSettings.Side.LEFT),
            panelOf(pose, seat, settings, ChairSidePanelSettings.Side.RIGHT));
    }

    /**
     * 单块面板的锚点。
     *
     * <p>位置合成（全部在水平面内，{@code vertical} 单独加到 Y）：
     * {@code 椅面 + 右手轴 × lateral + 面朝轴 × depth}。
     * 右手轴由世界层给出（{@code lateralX/lateralZ}，已归一化），<b>不</b>在本类自己按 yaw 推导——
     * 那是「左右是哪边」的唯一定义处，重复推导一次就会在偏航角上悄悄错位。
     */
    static ChairPanelAnchor panelOf(
        TableSeatPose pose,
        TableSeatPose.Seat seat,
        ChairSidePanelSettings settings,
        ChairSidePanelSettings.Side side
    ) {
        UUID worldId = pose.worldId();
        ChairSidePanelSettings.PanelLayout layout =
            side == ChairSidePanelSettings.Side.LEFT ? settings.left() : settings.right();
        // 【1.10.73：左右改按**每把椅子自己的**横向轴】旧实现取桌子 yaw 的右轴，
        // 对三把椅子都用同一个世界方向——seat 1/2 会因此把「左右」投到椅子前后方向上，
        // 两块板叠在椅子正前/正后而不是分列两侧。
        // 现在直接用世界层快照里的座位横向轴（+1 = 该座位玩家的右手边，与 Side.RIGHT=+1 同符号），
        // 它由 PhysicalTableManager.seatLateralAxis 按座位局部轴算出、再随桌子 yaw 旋转到世界，
        // 因此恒垂直于「椅子 → 桌心」，且三把椅子各自正确。
        double rightX = seat.lateralX();
        double rightZ = seat.lateralZ();

        // 朝桌心：**统一用「椅子 → 桌心」这一条方向**给两块板做 depth 前移。
        // 若各自用「自己 → 桌心」，两侧的方向不平行，会让两块板一近一远、破坏左右对称
        // （本地几何测试就是靠「到桌心等距」这条不变量发现的）。
        double towardX = pose.anchor().x() - seat.chair().x();
        double towardZ = pose.anchor().z() - seat.chair().z();
        double towardLength = Math.hypot(towardX, towardZ);
        if (towardLength < 1.0e-9) {
            // 椅子正好在桌心（异常配置）：退回就座者正前方，避免除零后整桌面板消失。
            towardX = -Math.sin(Math.toRadians(seat.facingYaw()));
            towardZ = Math.cos(Math.toRadians(seat.facingYaw()));
        } else {
            towardX /= towardLength;
            towardZ /= towardLength;
        }

        double x = seat.chair().x() + rightX * layout.lateral() + towardX * layout.depth();
        double z = seat.chair().z() + rightZ * layout.lateral() + towardZ * layout.depth();
        double y = seat.chair().y() + layout.vertical();

        // 朝向：面板正面朝桌心。
        // `viewerYawToward(dx, dz)` 返回「观察者朝该 yaw 看时 forward 指向 (dx,dz)」，
        // 而面板法线 = -forward。要让**法线**指向桌心，就要让 forward 指向**背离**桌心的方向，
        // 因此这里传「面板 - 桌心」而不是「桌心 - 面板」（差一个 180°，实服会看到板子背面）。
        double toTableX = x - pose.anchor().x();
        double toTableZ = z - pose.anchor().z();
        return new ChairPanelAnchor(
            panelIdOf(side),
            worldId,
            x,
            y,
            z,
            anchorYawDegrees(toTableX, toTableZ, layout.yaw()),
            (float) layout.pitch(),
            (float) layout.roll(),
            settings.width(),
            settings.height());
    }

    /** 侧 → 面板标识。稳定、可跨重启，供会话收口按名字精确关闭。 */
    public static String panelIdOf(ChairSidePanelSettings.Side side) {
        return side == ChairSidePanelSettings.Side.LEFT ? PANEL_ID_LEFT : PANEL_ID_RIGHT;
    }

    /**
     * 面板锚点 yaw = 「面板→坐者」方向 + 配置偏转，归一化到 (-180, 180]。
     *
     * <p>MuMenu 的锚点语义是「观察者朝该 yaw 看时正对面板」，所以基准取
     * **从面板中心看回坐者**的那条视线方向：这样无论 {@code lateral}/{@code depth} 怎么配，
     * 面板都正对坐着的人。配置里的 {@code yaw} 是叠加在这个基准上的偏转。
     *
     * @param toSeatX       面板中心指向坐者的水平向量 X（{@code seat.x - panel.x} 取反即面板减坐者）
     * @param toSeatZ       同上 Z
     * @param configuredYaw 配置里的相对偏转（度）
     */
    public static float anchorYawDegrees(double toSeatX, double toSeatZ, double configuredYaw) {
        if (!Double.isFinite(configuredYaw)) {
            throw new IllegalArgumentException("面板 yaw 偏转必须为有限数字，实际为 " + configuredYaw);
        }
        return normalizeDegrees(viewerYawToward(toSeatX, toSeatZ) + (float) configuredYaw);
    }

    /**
     * 观察者看向给定相对位置时的 yaw：{@code forward = (-sin y, 0, cos y)} 的反解。
     *
     * <p>水平位移为零（面板正好在坐者身上）时返回 0——那是「侧向与前后都配成 0」这种怪异配置，
     * 不该让整桌面板开不出来，取 MC 的 0 作为确定结果。
     */
    public static float viewerYawToward(double dx, double dz) {
        if (Math.abs(dx) < 1.0e-12 && Math.abs(dz) < 1.0e-12) {
            return 0.0f;
        }
        return normalizeDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /**
     * 配置里**无法**被当前 MuMenu 锚点 API 表达的倾斜自由度。
     *
     * <p>【1.10.64 起的语义反转：这里恒返回空】面板改由 MUZ **内置渲染**
     * （{@code ChairPanelDisplayService} + {@link ChairPanelSurface}），三轴 yaw/pitch/roll 都进入
     * 坐标轴合成，不再有「pitch/roll 无处可放」的缺口。方法保留是为了不让历史调用方拿到误导性结论：
     * 现在它一致地回答「没有不支持的倾斜」。**不要**再往这里加判定，真正的降级条件已不存在。
     *
     * @return 恒为空列表
     */
    public static List<String> unsupportedTilts(ChairSidePanelSettings settings) {
        return List.of();
    }

    /** 把 yaw 归一化到 (-180, 180]，去掉 -180/180 的重复表示，便于镜像断言比较。 */
    public static float normalizeDegrees(float yawDegrees) {
        if (!Float.isFinite(yawDegrees)) {
            throw new IllegalArgumentException("yaw 必须为有限数字，实际为 " + yawDegrees);
        }
        float normalized = yawDegrees % 360.0f;
        if (normalized <= -180.0f) {
            normalized += 360.0f;
        } else if (normalized > 180.0f) {
            normalized -= 360.0f;
        }
        // -0.0f 与 0.0f 相等但 toString 不同，统一成 +0，避免断言与日志里出现 -0.0。
        return normalized == 0.0f ? 0.0f : normalized;
    }

    /** 面板标识是否属于椅侧面板（供生命周期收口只处理自己那两块，不误关别的面板）。 */
    public static boolean isChairPanel(String panelId) {
        return PANEL_ID_LEFT.equals(panelId) || PANEL_ID_RIGHT.equals(panelId);
    }
}
