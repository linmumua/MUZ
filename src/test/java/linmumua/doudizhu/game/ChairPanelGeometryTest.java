package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import linmumua.doudizhu.config.ChairSidePanelSettings;
import linmumua.doudizhu.world.TableSeatPose;
import org.junit.jupiter.api.Test;

/**
 * 椅侧面板几何的行为测试：锁的是用户明确的需求，而不是实现细节。
 *
 * <p>需求原话：「菜单大小为 1.5 宽 1 格高，左右对称，在面向椅子左右各一个面板」，
 * 并明确「不是 1.5 像素或 Dialog 尺寸」「不随玩家随意转头漂移」。
 *
 * <p>【1.10.73：横向轴必须按**每把椅子**算】旧实现拿「桌子 yaw 的右轴」给三把椅子共用，
 * 对 seat 0 恰好等于其右手边，对 seat 1/2 却把「左右」投到了椅子前后方向上——
 * 表现是同一份配置摆出来的两块板，在某些座位上左右互换或叠到椅子正前/正后。
 * 现在横向轴取自世界层快照（{@code PhysicalTableManager.seatLateralAxis} 随桌子 yaw 旋转），
 * 本测试因此覆盖 **3 个座位 × 2 个桌 yaw**，并逐条验算：
 * <ol>
 *   <li>左右轴垂直于「椅子 → 桌心」；</li>
 *   <li>{@code Side.LEFT}（lateral = -1.2）落在坐者左侧、{@code Side.RIGHT}（+1.2）落在右侧；</li>
 *   <li>两块板的面板法线都指向桌心（同一张桌子，同一朝向语义）；</li>
 *   <li>depth 沿「椅子 → 桌心」前移，与横向轴无关。</li>
 * </ol>
 */
class ChairPanelGeometryTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final double EPSILON = 1.0e-6;

    /** 椅子到桌心的距离，与生产默认 {@code render.layout.chair-distance = 2.5} 一致。 */
    private static final double CHAIR_DISTANCE = 2.5;

    /** 桌心世界坐标（Pose 锚点）。 */
    private static final double TABLE_X = 0.0;
    private static final double TABLE_Z = 0.0;

    /**
     * 造一个真实手性的座位：就座朝向 + 权威的「玩家右手边」横向轴。
     *
     * <p>右手轴由 {@code right = (-cos yaw, 0, -sin yaw)} 给出（与 Minecraft 视线一致：
     * yaw=0 朝 +Z 时右手朝 -X），这正是 {@code PhysicalTableManager} 现在填进
     * {@code TableSeatPose.Seat.lateralX/lateralZ} 的语义。
     */
    private static TableSeatPose.Seat seat(int seatIndex, double x, double z, float facingYaw) {
        double radians = Math.toRadians(facingYaw);
        double rightX = -Math.cos(radians);
        double rightZ = -Math.sin(radians);
        return new TableSeatPose.Seat(
            seatIndex, UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
            new TableSeatPose.Pose(x, 64.0, z, facingYaw, 0.0f), rightX, rightZ);
    }

    /** 桌锚点在原点、给定桌子 yaw 的姿态快照。 */
    private static TableSeatPose poseOf(float tableYaw, TableSeatPose.Seat seat) {
        return new TableSeatPose(
            "T", WORLD,
            new TableSeatPose.Pose(TABLE_X, 64.0, TABLE_Z, tableYaw, 0.0f),
            List.of(seat));
    }

    /**
     * 按桌子 yaw 生成三把椅子：座位方位与 {@code PhysicalTableManager} 一致
     * （seat 0 = 局部 -Z、seat 1 = 局部 -X、seat 2 = 局部 +X；就座朝向为 tableYaw、-90、+90）。
     */
    private static TableSeatPose.Seat seatFor(int seatIndex, float tableYaw) {
        float seatYaw = switch (seatIndex) {
            case 0 -> tableYaw;
            case 1 -> tableYaw - 90.0f;
            default -> tableYaw + 90.0f;
        };
        // 由**就座朝向**反推椅子位置，而不是另写一套旋转：就座者朝向桌心，
        // 因此椅子在桌心沿「视线反方向」退 3 格。MC 视线 forward = (-sin yaw, cos yaw)，
        // 于是 chair = -3·forward = (3 sin yaw, -3 cos yaw)。
        // 这样位置与朝向天然自洽，测试真正要验的是 ChairPanelGeometry 有没有用对 seat.lateral 与椅子→桌心方向。
        double radians = Math.toRadians(seatYaw);
        return seat(seatIndex, CHAIR_DISTANCE * Math.sin(radians), -CHAIR_DISTANCE * Math.cos(radians), seatYaw);
    }

    @Test
    void 两块面板的世界尺寸是1点5乘1方块而不是像素() {
        TableSeatPose.Seat seat = seat(0, 0.0, -3.0, 0.0f);
        List<ChairPanelAnchor> panels = ChairPanelGeometry.panels(
            poseOf(0.0f, seat), seat, ChairSidePanelSettings.defaults());

        assertEquals(2, panels.size(), "椅侧必须有两块面板");
        for (ChairPanelAnchor panel : panels) {
            assertEquals(1.5, panel.widthBlocks(), EPSILON, "面板世界宽度必须是 1.5 格");
            assertEquals(1.0, panel.heightBlocks(), EPSILON, "面板世界高度必须是 1 格");
            assertTrue(panel.widthBlocks() < 2.0, "面板宽度不可能是像素量级（150 之类）");
        }
    }

    /**
     * 核心回归：三座位 × 两桌 yaw，左右轴垂直于椅子→桌心、LEFT 在左、RIGHT 在右。
     *
     * <p>旧实现（统一用桌 yaw 右轴）在这里会挂掉 seat 1/2：它们的「左右」被投到椅子前后方向上，
     * 与椅子→桌心平行而非垂直。
     */
    @Test
    void 三座位两桌yaw下左右轴垂直于椅子到桌心且左右符号正确() {
        for (float tableYaw : new float[] {0.0f, 37.0f}) {
            for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
                TableSeatPose.Seat seat = seatFor(seatIndex, tableYaw);
                TableSeatPose pose = poseOf(tableYaw, seat);
                List<ChairPanelAnchor> panels =
                    ChairPanelGeometry.panels(pose, seat, ChairSidePanelSettings.defaults());
                assertEquals(2, panels.size(), "seat " + seatIndex + " 必须产出两块面板");
                ChairPanelAnchor left = panels.get(0);
                ChairPanelAnchor right = panels.get(1);
                assertEquals(ChairPanelGeometry.PANEL_ID_LEFT, left.panelId());
                assertEquals(ChairPanelGeometry.PANEL_ID_RIGHT, right.panelId());

                // 椅子 → 桌心 的单位方向（depth 沿它前移，横向轴必须垂直于它）。
                double towardX = TABLE_X - seat.chair().x();
                double towardZ = TABLE_Z - seat.chair().z();
                double towardLength = Math.hypot(towardX, towardZ);
                double dirX = towardX / towardLength;
                double dirZ = towardZ / towardLength;

                // 左右连线（right - left）必须垂直于椅子→桌心：与朝向的点积为 0。
                double linkX = right.x() - left.x();
                double linkZ = right.z() - left.z();
                double linkLength = Math.hypot(linkX, linkZ);
                assertEquals(2.4, linkLength, 1.0e-6,
                    "两板间距必须是 lateral 的 2 倍（±1.2），seat " + seatIndex + " / yaw " + tableYaw);
                assertEquals(0.0, (linkX * dirX + linkZ * dirZ) / linkLength, 1.0e-9,
                    "左右轴必须垂直于椅子→桌心（seat " + seatIndex + " / yaw " + tableYaw + "）");

                // 符号：RIGHT 必须落在坐者右手边。坐者右手边 = (-cos seatYaw, -sin seatYaw)。
                double seatYawRadians = Math.toRadians(seat.facingYaw());
                double playerRightX = -Math.cos(seatYawRadians);
                double playerRightZ = -Math.sin(seatYawRadians);
                assertTrue((linkX * playerRightX + linkZ * playerRightZ) > 0.0,
                    "RIGHT 必须落在坐者右手边（seat " + seatIndex + " / yaw " + tableYaw + "）");
                // 等价地：LEFT 面板相对椅子的位移必须指回坐者左手边。
                double leftDx = left.x() - seat.chair().x();
                double leftDz = left.z() - seat.chair().z();
                assertTrue((leftDx * playerRightX + leftDz * playerRightZ) < 0.0,
                    "LEFT 必须落在坐者左手边（seat " + seatIndex + " / yaw " + tableYaw + "）");

                // 两板法线都必须指向桌心（配置 yaw 为 0）。
                for (ChairPanelAnchor panel : panels) {
                    double toTableX = TABLE_X - panel.x();
                    double toTableZ = TABLE_Z - panel.z();
                    double length = Math.hypot(toTableX, toTableZ);
                    double yawRadians = Math.toRadians(panel.yawDegrees());
                    double normalX = Math.sin(yawRadians);
                    double normalZ = -Math.cos(yawRadians);
                    assertEquals(1.0, normalX * (toTableX / length) + normalZ * (toTableZ / length),
                        1.0e-6, "面板法线必须指向桌心（seat " + seatIndex + " / yaw " + tableYaw + "）");
                }
            }
        }
    }

    /**
     * 【法线语义：朝桌心】默认布局下，三个座位的坐者都必须落在面板**正面半空间**。
     *
     * <p>保持批准方案「法线朝桌心」，不改成为「朝坐者」。但必须验证这不是纸上结论：
     * 面板横向偏移 1.2 格、纵深 0.45 格，坐者眼位约在椅面。以面板中心为原点、
     * 法线为 n，则 {@code n·(chair − panel)} 就是坐者在正面还是背面。
     *
     * <p>推导（同一坐标系，座位局部）：设 toward 为椅子→桌心单位向量、a 为坐者右手边单位向量、
     * L 为椅距、d 为 depth、lat 为 |lateral|，则
     * {@code n·(chair−panel) ∝ lat² + d² − d·L}。默认 lat=1.2、d=0.45、L=2.5 →
     * {@code 1.44 + 0.2025 − 1.125 = +0.5175 > 0}，即坐者在正面。
     * 正视的临界条件是 {@code L < (lat² + d²)/d = 3.65}。
     *
     * <p>这条同时兜住「镜像命中」：只要坐者在正面，客户端看到的左右与
     * {@code ChairPanelSurface.cellAt} 用的 right 轴一致，不会出现点左选右。
     */
    @Test
    void 默认布局下三个座位的坐者都位于面板正面半空间() {
        ChairSidePanelSettings defaults = ChairSidePanelSettings.defaults();
        for (float tableYaw : new float[] {0.0f, 37.0f}) {
            for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
                TableSeatPose.Seat seat = seatFor(seatIndex, tableYaw);
                TableSeatPose pose = poseOf(tableYaw, seat);
                List<ChairPanelAnchor> panels = ChairPanelGeometry.panels(pose, seat, defaults);
                assertEquals(2, panels.size());
                for (ChairPanelAnchor panel : panels) {
                    double yawRadians = Math.toRadians(panel.yawDegrees());
                    double normalX = Math.sin(yawRadians);
                    double normalZ = -Math.cos(yawRadians);
                    double eyeX = seat.chair().x() - panel.x();
                    double eyeZ = seat.chair().z() - panel.z();
                    double dot = normalX * eyeX + normalZ * eyeZ;
                    assertTrue(dot > 0.0,
                        "坐者必须位于面板正面（seat " + seatIndex + " / yaw " + tableYaw
                            + " / " + panel.panelId() + "），实际 dot=" + dot);
                }
            }
        }
    }

    /**
     * 反证：正视结论依赖默认 lateral/depth 比例，不是无条件成立。
     *
     * <p>把 lateral 压到 0.1、depth 加到 1.5（L=2.5）时 {@code lat²+d²−d·L = 0.01+2.25−3.75 < 0}，
     * 坐者会落到背面。留这条是为了让「配置可把 owner 放到背面」这个已知边界可被看见，
     * 而不是把默认布局的结论当成普适定理（运行时还有 {@code click()} 只认正面命中兜底）。
     */
    @Test
    void 极端配置下坐者会落到背面说明该结论依赖默认比例() {
        ChairSidePanelSettings.PanelLayout odd =
            new ChairSidePanelSettings.PanelLayout(0.1, 1.5, 0.55, 0.0, 0.0, 0.0);
        ChairSidePanelSettings settings =
            new ChairSidePanelSettings(true, 1.5, 1.0, odd, ChairSidePanelSettings.mirrored(odd));
        TableSeatPose.Seat seat = seatFor(0, 0.0f);
        List<ChairPanelAnchor> panels = ChairPanelGeometry.panels(poseOf(0.0f, seat), seat, settings);
        boolean anyBackSide = false;
        for (ChairPanelAnchor panel : panels) {
            double yawRadians = Math.toRadians(panel.yawDegrees());
            double dot = Math.sin(yawRadians) * (seat.chair().x() - panel.x())
                + (-Math.cos(yawRadians)) * (seat.chair().z() - panel.z());
            anyBackSide |= dot <= 0.0;
        }
        assertTrue(anyBackSide, "极端比例下应出现背面情形，用于证明默认结论不是普适的");
    }

    /** depth 必须沿「椅子 → 桌心」前移同样的距离，与左右侧无关。 */
    @Test
    void depth沿椅子到桌心前移且两侧一致() {
        TableSeatPose.Seat seat = seatFor(1, 21.0f);
        TableSeatPose pose = poseOf(21.0f, seat);
        ChairSidePanelSettings.PanelLayout flat =
            new ChairSidePanelSettings.PanelLayout(-1.2, 0.0, 0.55, 0.0, 0.0, 0.0);
        ChairSidePanelSettings noDepth =
            new ChairSidePanelSettings(true, 1.5, 1.0, flat, ChairSidePanelSettings.mirrored(flat));
        ChairSidePanelSettings withDepth =
            noDepth.withLayout(ChairSidePanelSettings.Side.LEFT,
                    new ChairSidePanelSettings.PanelLayout(-1.2, 0.4, 0.55, 0.0, 0.0, 0.0))
                .withLayout(ChairSidePanelSettings.Side.RIGHT,
                    new ChairSidePanelSettings.PanelLayout(1.2, 0.4, 0.55, 0.0, 0.0, 0.0));

        double towardX = TABLE_X - seat.chair().x();
        double towardZ = TABLE_Z - seat.chair().z();
        double length = Math.hypot(towardX, towardZ);
        double dirX = towardX / length;
        double dirZ = towardZ / length;

        List<ChairPanelAnchor> base = ChairPanelGeometry.panels(pose, seat, noDepth);
        List<ChairPanelAnchor> moved = ChairPanelGeometry.panels(pose, seat, withDepth);
        for (int index = 0; index < 2; index++) {
            double shiftX = moved.get(index).x() - base.get(index).x();
            double shiftZ = moved.get(index).z() - base.get(index).z();
            assertEquals(0.4, shiftX * dirX + shiftZ * dirZ, 1.0e-9,
                "depth 必须沿椅子→桌心前移 0.4（第 " + index + " 块）");
        }
    }

    /** pitch/roll 都进入锚点，且左右互为镜像（roll 取反、pitch 保持）。 */
    @Test
    void 俯仰保持翻滚取反() {
        ChairSidePanelSettings.PanelLayout left =
            new ChairSidePanelSettings.PanelLayout(-1.2, 0.3, 0.55, 12.0, 10.0, 8.0);
        ChairSidePanelSettings settings =
            new ChairSidePanelSettings(true, 1.5, 1.0, left, ChairSidePanelSettings.mirrored(left));
        TableSeatPose.Seat seat = seatFor(2, -13.0f);

        List<ChairPanelAnchor> panels = ChairPanelGeometry.panels(poseOf(-13.0f, seat), seat, settings);
        ChairPanelAnchor l = panels.get(0);
        ChairPanelAnchor r = panels.get(1);
        assertEquals(12.0f, l.pitchDegrees(), EPSILON, "pitch 必须原样进入锚点");
        assertEquals(l.pitchDegrees(), r.pitchDegrees(), EPSILON, "pitch 不参与镜像");
        assertEquals(8.0f, l.rollDegrees(), EPSILON);
        assertEquals(-8.0f, r.rollDegrees(), EPSILON, "roll 参与镜像且取反");
    }

    @Test
    void 关闭布局或缺失姿态时不产出面板() {
        TableSeatPose.Seat seat = seat(0, 0.0, -3.0, 0.0f);
        assertTrue(ChairPanelGeometry.panels(
            poseOf(0.0f, seat), seat, ChairSidePanelSettings.defaults().withEnabled(false)).isEmpty(),
            "enabled=false 时必须不产出面板");
        assertTrue(ChairPanelGeometry.panels(poseOf(0.0f, seat), seat, null).isEmpty());
        assertTrue(ChairPanelGeometry.panels(null, seat, ChairSidePanelSettings.defaults()).isEmpty());
        TableSeatPose.Seat noChair = new TableSeatPose.Seat(0, null, null, 0.0, 0.0);
        assertTrue(ChairPanelGeometry.panels(poseOf(0.0f, noChair), noChair,
            ChairSidePanelSettings.defaults()).isEmpty());
    }

    @Test
    void 三轴全部生效后不再报告不支持的倾斜() {
        ChairSidePanelSettings.PanelLayout tilted =
            new ChairSidePanelSettings.PanelLayout(-1.2, 0.0, 0.55, 15.0, 0.0, 10.0);
        ChairSidePanelSettings settings = new ChairSidePanelSettings(
            true, 1.5, 1.0, tilted, tilted);
        // 1.10.64 起面板内置渲染，pitch/roll 进入坐标轴合成，因此这里必须为空。
        assertTrue(ChairPanelGeometry.unsupportedTilts(settings).isEmpty(),
            "内置渲染支持三轴后不应再报告不支持的倾斜");
        assertTrue(ChairPanelGeometry.unsupportedTilts(ChairSidePanelSettings.defaults()).isEmpty());
    }

    @Test
    void yaw归一化把正负180折叠成同一表示() {
        assertEquals(180.0f, ChairPanelGeometry.normalizeDegrees(-180.0f), 1.0e-4);
        assertEquals(0.0f, ChairPanelGeometry.normalizeDegrees(360.0f), 1.0e-4);
        assertEquals(-90.0f, ChairPanelGeometry.normalizeDegrees(270.0f), 1.0e-4);
        assertThrows(IllegalArgumentException.class,
            () -> ChairPanelGeometry.normalizeDegrees(Float.NaN));
    }

    @Test
    void 面板标识判据只认椅侧两块() {
        assertTrue(ChairPanelGeometry.isChairPanel(ChairPanelGeometry.PANEL_ID_LEFT));
        assertTrue(ChairPanelGeometry.isChairPanel(ChairPanelGeometry.PANEL_ID_RIGHT));
        assertFalse(ChairPanelGeometry.isChairPanel("muz-gadget-position-test"));
        assertFalse(ChairPanelGeometry.isChairPanel(null));
    }

    @Test
    void 非法锚点必须被拒绝而不是静默钳制() {
        assertThrows(IllegalArgumentException.class,
            () -> new ChairPanelAnchor("x", WORLD, 0, 0, 0, 0, 0, 0, 0, 1), "零宽度必须拒绝");
        assertThrows(IllegalArgumentException.class,
            () -> new ChairPanelAnchor("x", WORLD, 0, 0, 0, 0, 0, 0, 1, 0), "零高度必须拒绝");
        assertThrows(IllegalArgumentException.class,
            () -> new ChairPanelAnchor("x", WORLD, 0, 0, 0, Float.NaN, 0, 0, 1, 1), "NaN yaw 必须拒绝");
        assertThrows(IllegalArgumentException.class,
            () -> new ChairPanelAnchor("x", WORLD, 0, 0, 0, 0, Float.NaN, 0, 1, 1), "NaN pitch 必须拒绝");
        assertThrows(IllegalArgumentException.class,
            () -> new ChairPanelAnchor("x", WORLD, 0, 0, 0, 0, 0, Float.NaN, 1, 1), "NaN roll 必须拒绝");
        assertThrows(IllegalArgumentException.class,
            () -> new ChairPanelAnchor(" ", WORLD, 0, 0, 0, 0, 0, 0, 1, 1), "空标识必须拒绝");
        assertThrows(IllegalArgumentException.class,
            () -> new ChairPanelAnchor("x", null, 0, 0, 0, 0, 0, 0, 1, 1), "空世界必须拒绝");
    }

    @Test
    void 倾斜标记只在非零时出现() {
        ChairPanelAnchor flat = new ChairPanelAnchor("x", WORLD, 0, 0, 0, 0, 0, 0, 1.5, 1.0);
        assertFalse(flat.hasUnsupportedTilt());
        ChairPanelAnchor tilted = flat.withPose(0, 0, 0, 0, 5.0f, 0.0f);
        assertTrue(tilted.hasUnsupportedTilt(), "pitch 非零必须被标记为需要倾斜自由度");
    }
}
