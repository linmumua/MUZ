package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.world.PhysicalTableManager.Vector;
import org.junit.jupiter.api.Test;

/**
 * 座位偏移必须相对各自座位朝向生效。
 * 回归目标：同一个「向左」设置，三个座位应各自朝自己的左边走，
 * 而不是所有座位一起朝同一个世界方向平移。
 */
class SeatOffsetOrientationTest {
    private static final double EPS = 1.0E-9;

    /**
     * 这是本次 bug 的核心症状：同一个横向偏移，三个座位不能朝同一个世界方向走。
     * 若生产代码漏掉按座位取轴，三个结果会完全相同，本用例即失败。
     */
    @Test
    void sameLateralOffsetMovesEachSeatAlongItsOwnAxis() {
        Vector seat0 = PhysicalTableManager.seatRelativeOffset(0, 0.0f, 0.5, 0.0, 0.0);
        Vector seat1 = PhysicalTableManager.seatRelativeOffset(1, 0.0f, 0.5, 0.0, 0.0);
        Vector seat2 = PhysicalTableManager.seatRelativeOffset(2, 0.0f, 0.5, 0.0, 0.0);

        // 0 号座位在桌子 -Z 侧、面朝 +Z，它的左手边是局部 +X。
        assertEquals(0.5, seat0.x(), EPS);
        assertEquals(0.0, seat0.z(), EPS);
        // 1 号座位在 -X 侧、面朝 +X，它的左手边是局部 -Z。
        assertEquals(0.0, seat1.x(), EPS);
        assertEquals(-0.5, seat1.z(), EPS);
        // 2 号座位在 +X 侧、面朝 -X，它的左手边是局部 +Z。
        assertEquals(0.0, seat2.x(), EPS);
        assertEquals(0.5, seat2.z(), EPS);

        assertTrue(differs(seat0, seat1), "0 号与 1 号座位的位移方向必须不同");
        assertTrue(differs(seat0, seat2), "0 号与 2 号座位的位移方向必须不同");
        assertTrue(differs(seat1, seat2), "1 号与 2 号座位的位移方向必须不同");
    }

    /**
     * 三个座位的纵深偏移都应朝桌心靠近，即互相指向彼此对面，而非同向。
     */
    @Test
    void depthOffsetPointsTowardTableCenterForEverySeat() {
        Vector seat0 = PhysicalTableManager.seatRelativeOffset(0, 0.0f, 0.0, 0.0, 0.3);
        Vector seat1 = PhysicalTableManager.seatRelativeOffset(1, 0.0f, 0.0, 0.0, 0.3);
        Vector seat2 = PhysicalTableManager.seatRelativeOffset(2, 0.0f, 0.0, 0.0, 0.3);

        // 0 号在 -Z 侧，朝桌心是 +Z。
        assertEquals(0.3, seat0.z(), EPS);
        assertEquals(0.0, seat0.x(), EPS);
        // 1 号在 -X 侧，朝桌心是 +X。
        assertEquals(0.3, seat1.x(), EPS);
        // 2 号在 +X 侧，朝桌心是 -X，必须与 1 号反向。
        assertEquals(-0.3, seat2.x(), EPS);
    }

    /**
     * 桌子转了，偏移就得跟着转：否则桌子摆成 90 度后所有文字都会飞到错误的一侧。
     */
    @Test
    void offsetRotatesWithTableYaw() {
        Vector atZero = PhysicalTableManager.seatRelativeOffset(0, 0.0f, 0.5, 0.0, 0.0);
        Vector atNinety = PhysicalTableManager.seatRelativeOffset(0, 90.0f, 0.5, 0.0, 0.0);

        // yaw=0 时 0 号座位的左手边是世界 +X。
        assertEquals(0.5, atZero.x(), EPS);
        assertEquals(0.0, atZero.z(), EPS);
        // 桌子转 90 度后，同一个「左」变成世界 +Z。
        assertEquals(0.0, atNinety.x(), EPS);
        assertEquals(0.5, atNinety.z(), EPS);
    }

    /**
     * 桌子转 180 度必须让偏移完全反向，这是旋转真的生效的最强信号。
     */
    @Test
    void oppositeTableYawInvertsOffset() {
        Vector atZero = PhysicalTableManager.seatRelativeOffset(1, 0.0f, 0.4, 0.0, 0.25);
        Vector flipped = PhysicalTableManager.seatRelativeOffset(1, 180.0f, 0.4, 0.0, 0.25);

        assertEquals(-atZero.x(), flipped.x(), EPS);
        assertEquals(-atZero.z(), flipped.z(), EPS);
    }

    /**
     * 垂直偏移是世界 Y 轴，绝不能被 yaw 旋转带偏。
     */
    @Test
    void verticalOffsetIsIndependentOfYawAndSeat() {
        for (float yaw : new float[] {0.0f, 45.0f, 90.0f, 180.0f, -90.0f}) {
            for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
                Vector offset = PhysicalTableManager.seatRelativeOffset(seatIndex, yaw, 0.3, 1.25, 0.2);
                assertEquals(1.25, offset.y(), EPS, "座位 " + seatIndex + " yaw " + yaw + " 的垂直偏移被旋转污染");
            }
        }
    }

    /**
     * 旋转只改方向不改长度，顺便保证 lateral/depth 两路没有互相串味。
     */
    @Test
    void rotationPreservesOffsetMagnitude() {
        double lateral = 0.4;
        double depth = 0.3;
        double expected = Math.hypot(lateral, depth);

        for (float yaw : new float[] {0.0f, 37.0f, 90.0f, 213.0f}) {
            for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
                Vector offset = PhysicalTableManager.seatRelativeOffset(seatIndex, yaw, lateral, 0.0, depth);
                double actual = Math.hypot(offset.x(), offset.z());
                assertEquals(expected, actual, 1.0E-9, "座位 " + seatIndex + " yaw " + yaw + " 的水平位移长度被旋转改变");
            }
        }
    }

    /**
     * 纵深轴必须指向【牌桌内侧】，不是指向玩家。
     *
     * 这条守的是 seatDepthAxis 这根轴的方向语义本身。它现在有两个用处：
     * 手牌压层（index 越大越往桌心压，保证前面的牌盖住后面的）与
     * pickHandCard 里把玩家视线投影到牌面坐标系。方向反了，压层顺序
     * 和射线的深度排序会同时颠倒，而这两处都不可能靠单元测试之外的
     * 手段发现——只能进服肉眼看。
     *
     * 判据用点积而不是逐座位写死坐标：座位方位以后若调整，
     * 这条断言表达的仍然是"朝桌心"这个意图本身。
     */
    @Test
    void depthAxisPushesCardsTowardTheTableCentre() {
        // handCenter 的局部坐标：0 号在 -Z 侧，1 号在 -X 侧，2 号在 +X 侧。
        // 朝桌心的方向就是 normalize(-handCenter)。
        double[][] handCentre = {{0.0, -1.0}, {-1.0, 0.0}, {1.0, 0.0}};

        for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
            Vector depth = PhysicalTableManager.seatDepthAxis(seatIndex);
            double towardCentreX = -handCentre[seatIndex][0];
            double towardCentreZ = -handCentre[seatIndex][1];
            double dot = depth.x() * towardCentreX + depth.z() * towardCentreZ;

            assertTrue(
                dot > 0.0,
                "座位 " + seatIndex + " 的纵深轴点积 " + dot
                    + " 不为正，说明牌被推向玩家而不是牌桌内侧"
            );
        }
    }

    /**
     * 座位轴本身必须两两正交且归一化，否则「左右」和「前后」会互相串味。
     */
    @Test
    void seatAxesAreOrthonormalPerSeat() {
        for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
            Vector lateral = PhysicalTableManager.seatLateralAxis(seatIndex);
            Vector depth = PhysicalTableManager.seatDepthAxis(seatIndex);

            assertEquals(1.0, Math.hypot(lateral.x(), lateral.z()), EPS, "座位 " + seatIndex + " 横向轴未归一化");
            assertEquals(1.0, Math.hypot(depth.x(), depth.z()), EPS, "座位 " + seatIndex + " 纵深轴未归一化");
            double dot = lateral.x() * depth.x() + lateral.z() * depth.z();
            assertEquals(0.0, dot, EPS, "座位 " + seatIndex + " 的横向轴与纵深轴不正交");
        }
    }

    private static boolean differs(Vector left, Vector right) {
        return Math.abs(left.x() - right.x()) > 1.0E-6 || Math.abs(left.z() - right.z()) > 1.0E-6;
    }
}
