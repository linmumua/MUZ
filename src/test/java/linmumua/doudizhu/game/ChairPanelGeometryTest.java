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
 * 椅侧面板几何的行为测试：锁的是 linmumua 明确的需求，而不是实现细节。
 *
 * <p>需求原话：「菜单大小为 1.5 宽 1 格高，左右对称，在面向椅子左右各一个面板」，
 * 并明确「不是 1.5 像素或 Dialog 尺寸」「不随玩家随意转头漂移」。主仲裁补充的镜像语义是：
 * <b>镜像 lateral/yaw/roll 取反、depth/vertical/pitch 保持，世界平面保持正确手性、文字正向</b>
 * （不是把中文/图标纹理水平翻转）。因此本测试断言：
 * <ol>
 *   <li><b>世界尺寸</b>是 1.5 × 1.0 <b>方块</b>（不是像素）；</li>
 *   <li><b>左右严格镜像</b>：位置关于坐者矢状面对称，朝向/翻滚取反、俯仰保持；</li>
 *   <li><b>纹理不翻转</b>：几何只产出数值位姿，不产出任何镜像/负缩放标记；</li>
 *   <li><b>与视线无关</b>：签名里根本没有眼睛/视线参数。</li>
 * </ol>
 */
class ChairPanelGeometryTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final double EPSILON = 1.0e-6;

    /** 造一个坐在原点附近的座位：就座朝向 30°，右手轴按 yaw 推导。 */
    private static TableSeatPose.Seat seat(double x, double y, double z, float facingYaw) {
        double radians = Math.toRadians(facingYaw);
        // 坐者右手轴：yaw=0（朝 +Z）时右手指 -X。
        double rightX = -Math.cos(radians);
        double rightZ = -Math.sin(radians);
        return new TableSeatPose.Seat(
            0, UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
            new TableSeatPose.Pose(x, y, z, facingYaw, 0.0f), rightX, rightZ);
    }

    @Test
    void 两块面板的世界尺寸是1点5乘1方块而不是像素() {
        List<ChairPanelAnchor> panels = ChairPanelGeometry.panels(
            WORLD, seat(10.0, 64.0, 10.0, 0.0f), ChairSidePanelSettings.defaults());

        assertEquals(2, panels.size(), "椅侧必须有两块面板");
        for (ChairPanelAnchor panel : panels) {
            assertEquals(1.5, panel.widthBlocks(), EPSILON, "面板世界宽度必须是 1.5 格");
            assertEquals(1.0, panel.heightBlocks(), EPSILON, "面板世界高度必须是 1 格");
            assertTrue(panel.widthBlocks() < 2.0, "面板宽度不可能是像素量级（150 之类）");
        }
    }

    @Test
    void 左右两块位置与朝向严格镜像且纹理不翻转() {
        // 左右互为镜像的默认布局：lateral 左负右正，yaw/roll 取反，depth/vertical/pitch 相同。
        ChairSidePanelSettings.PanelLayout left =
            new ChairSidePanelSettings.PanelLayout(-1.2, 0.3, 0.55, 0.0, 12.0, 8.0);
        ChairSidePanelSettings.PanelLayout right =
            new ChairSidePanelSettings.PanelLayout(1.2, 0.3, 0.55, 0.0, -12.0, -8.0);
        ChairSidePanelSettings settings =
            new ChairSidePanelSettings(true, 1.5, 1.0, left, right);

        float facing = 41.0f;
        List<ChairPanelAnchor> panels = ChairPanelGeometry.panels(
            WORLD, seat(5.0, 64.0, -3.0, facing), settings);
        ChairPanelAnchor l = panels.get(0);
        ChairPanelAnchor r = panels.get(1);

        assertEquals(ChairPanelGeometry.PANEL_ID_LEFT, l.panelId());
        assertEquals(ChairPanelGeometry.PANEL_ID_RIGHT, r.panelId());

        // 等高（vertical 保持，不镜像）
        assertEquals(l.y(), r.y(), EPSILON, "两块面板必须等高：vertical 不参与镜像");

        // 沿「坐者右手轴」的投影互为相反数：lateral 取反的直接结果。
        double radians = Math.toRadians(facing);
        double rightX = -Math.cos(radians);
        double rightZ = -Math.sin(radians);
        double lProj = (l.x() - 5.0) * rightX + (l.z() + 3.0) * rightZ;
        double rProj = (r.x() - 5.0) * rightX + (r.z() + 3.0) * rightZ;
        assertEquals(-lProj, rProj, 1.0e-9, "沿右手轴的投影必须互为相反数");
        assertTrue(lProj < 0.0, "左侧面板必须落在坐者的左手一侧（lateral 为负）");

        // depth 保持：沿面朝轴的投影必须相同（不是相反数）。
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double lForward = (l.x() - 5.0) * forwardX + (l.z() + 3.0) * forwardZ;
        double rForward = (r.x() - 5.0) * forwardX + (r.z() + 3.0) * forwardZ;
        assertEquals(lForward, rForward, 1.0e-9, "depth 不参与镜像：两块面板必须同样靠前/靠后");

        // 朝向：yaw 关于「面板→坐者」基准互为相反数；pitch 保持；roll 取反。
        float lBase = ChairPanelGeometry.viewerYawToward(l.x() - 5.0, l.z() + 3.0);
        float rBase = ChairPanelGeometry.viewerYawToward(r.x() - 5.0, r.z() + 3.0);
        float lYawDelta = ChairPanelGeometry.normalizeDegrees(l.yawDegrees() - lBase);
        float rYawDelta = ChairPanelGeometry.normalizeDegrees(r.yawDegrees() - rBase);
        assertEquals(-lYawDelta, rYawDelta, 1.0e-3, "面板 yaw 必须关于基准互为相反数（右侧取反）");
        assertEquals(12.0f, lYawDelta, 1.0e-3, "左侧配置 yaw 应原样生效");
        assertEquals(l.pitchDegrees(), r.pitchDegrees(), 1.0e-6, "pitch 不参与镜像");
        assertEquals(-l.rollDegrees(), r.rollDegrees(), 1.0e-6, "roll 参与镜像且取反");
    }

    @Test
    void 面板基准朝向是正面看着坐着的人() {
        // 配置偏转为 0 时，面板应正对坐者：法线 = 从面板指向坐者的方向。
        // 覆盖 depth=0（正侧方）与 depth>0（侧前方）两种，锁住「不是写死 +180°」。
        for (double depth : new double[] {0.0, 0.35}) {
            ChairSidePanelSettings.PanelLayout layout =
                new ChairSidePanelSettings.PanelLayout(-1.2, depth, 0.55, 0.0, 0.0, 0.0);
            ChairSidePanelSettings settings =
                new ChairSidePanelSettings(true, 1.5, 1.0, layout, ChairSidePanelSettings.mirrored(layout));
            double seatX = 4.0;
            double seatZ = -2.0;
            for (ChairPanelAnchor panel : ChairPanelGeometry.panels(
                WORLD, seat(seatX, 64.0, seatZ, 37.0f), settings)) {
                // 该面板指向坐者的方向
                double toSeatX = seatX - panel.x();
                double toSeatZ = seatZ - panel.z();
                double length = Math.hypot(toSeatX, toSeatZ);
                double dirX = toSeatX / length;
                double dirZ = toSeatZ / length;
                // 面板法线 = -forward(yaw) = (sin yaw, 0, -cos yaw)
                double yawRadians = Math.toRadians(panel.yawDegrees());
                double normalX = Math.sin(yawRadians);
                double normalZ = -Math.cos(yawRadians);
                double dot = normalX * dirX + normalZ * dirZ;
                assertEquals(1.0, dot, 1.0e-6,
                    "面板 " + panel.panelId() + " 的法线必须指向坐者（depth=" + depth + "）");
            }
        }
    }

    @Test
    void 位置只随椅子姿态变化不含任何视线输入() {
        TableSeatPose.Seat a = seat(0.0, 64.0, 0.0, 0.0f);
        TableSeatPose.Seat b = seat(0.0, 64.0, 0.0, 90.0f);
        List<ChairPanelAnchor> first =
            ChairPanelGeometry.panels(WORLD, a, ChairSidePanelSettings.defaults());
        List<ChairPanelAnchor> second =
            ChairPanelGeometry.panels(WORLD, b, ChairSidePanelSettings.defaults());

        // 同一座位、椅子转 90°：朝向整体加 90°，标识与尺寸不变。
        for (int index = 0; index < first.size(); index++) {
            assertEquals(
                ChairPanelGeometry.normalizeDegrees(first.get(index).yawDegrees() + 90.0f),
                ChairPanelGeometry.normalizeDegrees(second.get(index).yawDegrees()),
                1.0e-3, "面板朝向必须随椅子朝向整体旋转");
            assertEquals(first.get(index).panelId(), second.get(index).panelId());
            assertEquals(first.get(index).widthBlocks(), second.get(index).widthBlocks(), EPSILON);
        }
    }

    @Test
    void 关闭布局或缺失姿态时不产出面板() {
        TableSeatPose.Seat seat = seat(0.0, 64.0, 0.0, 0.0f);
        assertTrue(ChairPanelGeometry.panels(
            WORLD, seat, ChairSidePanelSettings.defaults().withEnabled(false)).isEmpty(),
            "enabled=false 时必须不产出面板");
        assertTrue(ChairPanelGeometry.panels(WORLD, seat, null).isEmpty());
        assertTrue(ChairPanelGeometry.panels(WORLD, null, ChairSidePanelSettings.defaults()).isEmpty());
        TableSeatPose.Seat noChair = new TableSeatPose.Seat(0, null, null, 0.0, 0.0);
        assertTrue(ChairPanelGeometry.panels(WORLD, noChair, ChairSidePanelSettings.defaults()).isEmpty());
    }

    @Test
    void 三轴全部生效后不再报告不支持的倾斜() {
        ChairSidePanelSettings.PanelLayout tilted =
            new ChairSidePanelSettings.PanelLayout(-1.2, 0.0, 0.55, 15.0, 0.0, 10.0);
        ChairSidePanelSettings settings = new ChairSidePanelSettings(
            true, 1.5, 1.0, tilted, tilted);
        // 1.10.64 起面板内置渲染，pitch/roll 进入坐标轴合成，因此这里必须为空。
        // 断言方向反转是机制变更（旧实现依赖 MuMenu 只有单轴），不是弱化：
        // 真正要守的是「不要再谎报降级」，由 ChairPanelSurfaceTest 验算三轴确实生效。
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
