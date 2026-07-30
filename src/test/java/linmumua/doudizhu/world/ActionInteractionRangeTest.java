package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 文字按钮必须由服务端限制在 3 格近距离内，不能依赖客户端 reach。
 */
class ActionInteractionRangeTest {
    @Test
    void exactThreeBlockBoundaryIsAllowed() {
        assertTrue(PhysicalTableManager.isWithinActionInteractionRange(9.0));
    }

    @Test
    void anythingBeyondThreeBlocksIsRejected() {
        assertFalse(PhysicalTableManager.isWithinActionInteractionRange(9.000001));
    }

    @Test
    void distanceUsesNearestPointOnHitbox() {
        double distanceSquared = PhysicalTableManager.distanceSquaredToBox(
            4.0, 0.5, 0.5,
            0.0, 0.0, 0.0,
            1.0, 1.0, 1.0
        );

        assertEquals(9.0, distanceSquared, 1.0E-9);
        assertTrue(PhysicalTableManager.isWithinActionInteractionRange(distanceSquared));
    }

    @Test
    void pointInsideHitboxHasZeroDistance() {
        assertEquals(
            0.0,
            PhysicalTableManager.distanceSquaredToBox(
                0.5, 0.5, 0.5,
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0
            ),
            1.0E-9
        );
    }

    @Test
    void diagonalDistanceCountsAllAxes() {
        assertEquals(
            12.0,
            PhysicalTableManager.distanceSquaredToBox(
                3.0, 3.0, 3.0,
                0.0, 0.0, 0.0,
                1.0, 1.0, 1.0
            ),
            1.0E-9
        );
    }
}
