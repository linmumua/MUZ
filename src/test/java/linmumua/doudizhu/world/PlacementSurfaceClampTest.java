package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

/**
 * 钉住"桌椅贴地时，脚下的地板方块自身不得被判成阻挡"。
 *
 * 真实症状：玩家在平地上放牌桌，提示"桌面位置被方块挡住了"，
 * 红色高亮指向桌子下方 3x3 的地板方块——被判成阻挡的正是玩家站的地板。
 * 根因是 TABLE/CHAIR_PLACEMENT_MIN_Y = -0.10 让检测区往中心下方多探 0.10 格，
 * 伸进了用来支撑桌子的那一格地板。
 *
 * 这里所有几何量（半径、MIN_Y/MAX_Y、SUPPORT_SURFACE_LIFT、下标取整、钳位）
 * 都直接引用生产常量与生产方法，不再抄一份数字：
 * 之前 PlacementObstructionTest 把半径写死成 0.95，而生产值早已是 1.20，
 * 测试因此完全无法感知检测几何的变化，这个 bug 就是这样进生产的。
 */
class PlacementSurfaceClampTest {
    /* 地板方块所在层，占据世界 Y 64.0~65.0 */
    private static final int FLOOR_BLOCK_Y = 64;

    /* 地板上表面，也就是玩家站立面 */
    private static final double SURFACE_Y = FLOOR_BLOCK_Y + PhysicalTableManager.SUPPORT_SURFACE_LIFT;

    /* 用户服实测配置：table.spawn-offset-y = -0.55，chair.visual-vertical-offset = 0.35 */
    private static final double USER_SPAWN_OFFSET_Y = -0.55;
    private static final double USER_CHAIR_VERTICAL_OFFSET = 0.35;

    /* config.yml 出厂默认：table.spawn-offset-y = -0.52，chair.visual-vertical-offset = -0.04 */
    private static final double DEFAULT_SPAWN_OFFSET_Y = -0.52;
    private static final double DEFAULT_CHAIR_VERTICAL_OFFSET = -0.04;

    /* config.yml 出厂默认的桌面与椅子高度，与 TableSurfaceBaselineTest 取同一份 */
    private static final double TABLE_DISPLAY_HEIGHT = 0.55;
    private static final double CHAIR_BASE_HEIGHT = 0.20;

    /** 复刻 placementAnchor：锚点 = 地板方块坐标 + spawnOffsetY，负偏移让它落在方块内部。 */
    private static double anchorY(double spawnOffsetY) {
        return FLOOR_BLOCK_Y + spawnOffsetY;
    }

    /** 复刻 previewTableCenter：锚点 + tableDisplayHeight + 支撑面补偿。 */
    private static double tableCenterY(double spawnOffsetY) {
        return anchorY(spawnOffsetY) + TABLE_DISPLAY_HEIGHT + PhysicalTableManager.SUPPORT_SURFACE_LIFT;
    }

    /** 复刻 previewChairBases：lift 由 chairVisualAdjustment 的 y 分量带入。 */
    private static double chairCenterY(double spawnOffsetY, double chairVerticalOffset) {
        return anchorY(spawnOffsetY)
            + CHAIR_BASE_HEIGHT
            + chairVerticalOffset
            + PhysicalTableManager.SUPPORT_SURFACE_LIFT;
    }

    /** 走生产钳位逻辑算出检测区，再取实际会被扫描的最低方块层。 */
    private static int lowestScannedBlockY(double centerY, double minYOffset, double surfaceY) {
        BoundingBox area = PlacementObstruction.scanArea(
            0.5,
            centerY,
            0.5,
            PhysicalTableManager.TABLE_PLACEMENT_RADIUS,
            PlacementObstruction.clampedMinYOffset(centerY, minYOffset, surfaceY),
            PhysicalTableManager.TABLE_PLACEMENT_MAX_Y
        );
        return PlacementObstruction.firstBlockIndex(area.getMinY());
    }

    /** 钳位后扫描下界的世界 Y 坐标。 */
    private static double clampedMinWorldY(double centerY, double minYOffset, double surfaceY) {
        return centerY + PlacementObstruction.clampedMinYOffset(centerY, minYOffset, surfaceY);
    }

    /**
     * 断言 1：桌面检测钳位后不得探到站立面以下，扫描的最低方块层必须高于地板层。
     * 失败条件：桌面 MIN_Y 的钳位被去掉，或 supportSurfaceY 算错（比如漏减 spawnOffsetY）。
     */
    @Test
    void tableScanNeverReachesIntoTheFloorBlockItStandsOn() {
        for (double spawnOffsetY : new double[] {USER_SPAWN_OFFSET_Y, DEFAULT_SPAWN_OFFSET_Y}) {
            double centerY = tableCenterY(spawnOffsetY);
            double minWorldY = clampedMinWorldY(centerY, PhysicalTableManager.TABLE_PLACEMENT_MIN_Y, SURFACE_Y);

            assertTrue(
                minWorldY >= SURFACE_Y - 1.0E-9,
                "桌面扫描下界探到站立面以下了，spawnOffsetY=" + spawnOffsetY + " 下界=" + minWorldY
            );
            assertTrue(
                lowestScannedBlockY(centerY, PhysicalTableManager.TABLE_PLACEMENT_MIN_Y, SURFACE_Y) > FLOOR_BLOCK_Y,
                "桌面检测把地板方块层 " + FLOOR_BLOCK_Y + " 纳入扫描了，spawnOffsetY=" + spawnOffsetY
            );
        }
    }

    /**
     * 断言 2：椅子检测同样不得探进地板。
     * 出厂默认那组椅心本身就在地板方块内部（64.64），所以只有钳位能救；
     * 失败条件：椅子那几处漏传 surfaceY，或钳位改成只取 max(minYOffset, 0)。
     */
    @Test
    void chairScanNeverReachesIntoTheFloorBlockEvenWhenChairCentreSitsInsideIt() {
        double userChairY = chairCenterY(USER_SPAWN_OFFSET_Y, USER_CHAIR_VERTICAL_OFFSET);
        double defaultChairY = chairCenterY(DEFAULT_SPAWN_OFFSET_Y, DEFAULT_CHAIR_VERTICAL_OFFSET);

        // 先确认出厂默认这组的椅心确实埋在地板方块里，否则下面的断言就失去了针对性
        assertTrue(
            defaultChairY < SURFACE_Y,
            "出厂默认的椅子中心应当落在地板方块内部，实际=" + defaultChairY
        );

        for (double chairCentreY : new double[] {userChairY, defaultChairY}) {
            double minWorldY = clampedMinWorldY(chairCentreY, PhysicalTableManager.CHAIR_PLACEMENT_MIN_Y, SURFACE_Y);

            assertTrue(
                minWorldY >= SURFACE_Y - 1.0E-9,
                "椅子扫描下界探到站立面以下了，椅心=" + chairCentreY + " 下界=" + minWorldY
            );
            assertTrue(
                lowestScannedBlockY(chairCentreY, PhysicalTableManager.CHAIR_PLACEMENT_MIN_Y, SURFACE_Y) > FLOOR_BLOCK_Y,
                "椅子检测把地板方块层 " + FLOOR_BLOCK_Y + " 纳入扫描了，椅心=" + chairCentreY
            );
        }
    }

    /**
     * 断言 3：回归证明。不钳位时地板方块层确实会被扫进去，说明这个 bug 真实存在。
     * 失败条件：把钳位当默认行为写死（surfaceY 不再可选），这条就会变绿失败——
     * 它同时锁住"钳位是唯一修复手段"这个前提。
     */
    @Test
    void withoutClampTheFloorBlockIsScannedWhichIsTheOriginalBug() {
        double tableCentreY = tableCenterY(USER_SPAWN_OFFSET_Y);
        double chairCentreY = chairCenterY(DEFAULT_SPAWN_OFFSET_Y, DEFAULT_CHAIR_VERTICAL_OFFSET);

        assertEquals(
            FLOOR_BLOCK_Y,
            lowestScannedBlockY(tableCentreY, PhysicalTableManager.TABLE_PLACEMENT_MIN_Y, Double.NEGATIVE_INFINITY),
            "不钳位时桌面检测本应探进地板层，这条断言用于证明原 bug 存在"
        );
        assertEquals(
            FLOOR_BLOCK_Y,
            lowestScannedBlockY(chairCentreY, PhysicalTableManager.CHAIR_PLACEMENT_MIN_Y, Double.NEGATIVE_INFINITY),
            "不钳位时椅子检测本应探进地板层，这条断言用于证明原 bug 存在"
        );
    }

    /**
     * 断言 4：钳位只砍掉支撑面以下，站立面之上的真实障碍必须照旧扫到。
     * 失败条件：把 MIN_Y 直接加大到放弃下方检测，或钳位误把上界一起抬走，
     * 那样桌面高度范围内的墙就漏判了。
     */
    @Test
    void clampStillScansRealObstaclesAboveTheSurface() {
        double centerY = tableCenterY(USER_SPAWN_OFFSET_Y);
        BoundingBox area = PlacementObstruction.scanArea(
            0.5,
            centerY,
            0.5,
            PhysicalTableManager.TABLE_PLACEMENT_RADIUS,
            PlacementObstruction.clampedMinYOffset(
                centerY, PhysicalTableManager.TABLE_PLACEMENT_MIN_Y, SURFACE_Y
            ),
            PhysicalTableManager.TABLE_PLACEMENT_MAX_Y
        );

        int lowest = PlacementObstruction.firstBlockIndex(area.getMinY());
        int highest = PlacementObstruction.lastBlockIndex(area.getMaxY());

        // 站立面正上方那一格就是桌子要占的空间，墙放在那里必须被判成阻挡
        assertEquals(FLOOR_BLOCK_Y + 1, lowest, "站立面上方第一格必须仍在扫描范围内");
        assertTrue(highest >= FLOOR_BLOCK_Y + 1, "桌面高度范围内的方块层必须仍被扫描，实际上界层=" + highest);
        assertTrue(area.getMaxY() > SURFACE_Y, "检测区上界必须高过站立面，否则等于什么都不检测");
    }

    /**
     * 断言 5：firstBlockIndex 的 epsilon 对称性。
     * 下界正好等于整格边界时只是共面接触，不能多扫下面一格。
     * 失败条件：epsilon 被去掉，或符号写成 min - 1.0E-7。
     */
    @Test
    void firstBlockIndexDoesNotStepDownWhenLowerBoundSitsExactlyOnGridBoundary() {
        assertEquals(FLOOR_BLOCK_Y + 1, PlacementObstruction.firstBlockIndex(SURFACE_Y));
        // 与 lastBlockIndex 对称：上界贴边不多扫上面一格，下界贴边不多扫下面一格
        assertEquals(FLOOR_BLOCK_Y, PlacementObstruction.lastBlockIndex(SURFACE_Y));
        // 只要真的越过边界往下一点，那一格就必须回到扫描范围
        assertEquals(FLOOR_BLOCK_Y, PlacementObstruction.firstBlockIndex(SURFACE_Y - 1.0E-3));
    }
}
