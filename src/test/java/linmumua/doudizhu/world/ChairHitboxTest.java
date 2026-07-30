package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.world.PhysicalTableManager.Vector;
import org.junit.jupiter.api.Test;

/**
 * 按钮判定框只由实际文字内容与 TextDisplay 缩放决定。
 */
class ChairHitboxTest {
    private static final float JOIN_LABEL_SCALE = 0.46f;
    private static final float ACTION_LABEL_SCALE = 0.20f;

    @Test
    void joinHitboxMatchesRenderedTextExactly() {
        float width = PhysicalTableManager.resolveHitboxWidth("加入座位1", JOIN_LABEL_SCALE, true);
        float height = PhysicalTableManager.resolveHitboxHeight("加入座位1", JOIN_LABEL_SCALE);

        assertEquals(0.5405f, width, 1.0E-6f);
        assertEquals(0.1035f, height, 1.0E-6f);
    }

    @Test
    void tinyTextProducesTinyHitboxWithoutArtificialMinimum() {
        float width = PhysicalTableManager.resolveHitboxWidth("准备", 0.001f, true);
        float height = PhysicalTableManager.resolveHitboxHeight("准备", 0.001f);

        assertTrue(width < 0.001f);
        assertTrue(height < 0.001f);
    }

    @Test
    void hugeTextIsNotSilentlyClamped() {
        float width = PhysicalTableManager.resolveHitboxWidth("加入座位1", 5.0f, true);
        float height = PhysicalTableManager.resolveHitboxHeight("加入座位1", 5.0f);

        assertEquals(5.875f, width, 1.0E-6f);
        assertEquals(1.125f, height, 1.0E-6f);
    }

    @Test
    void biggerLabelScaleProducesProportionallyBiggerHitbox() {
        float actionWidth = PhysicalTableManager.resolveHitboxWidth("准备", ACTION_LABEL_SCALE, true);
        float doubledScaleWidth = PhysicalTableManager.resolveHitboxWidth("准备", ACTION_LABEL_SCALE * 2.0f, true);

        assertEquals(actionWidth * 2.0f, doubledScaleWidth, 1.0E-6f);
    }

    @Test
    void lateralOffsetFollowsSeatAxis() {
        Vector lateralAxis = new Vector(0.0, 0.0, -1.0);

        Vector adjustment = PhysicalTableManager.chairHitboxAdjustment(lateralAxis, 0.25, 0.02);

        assertEquals(0.0, adjustment.x(), 1.0E-9);
        assertEquals(-0.25, adjustment.z(), 1.0E-9);
        assertEquals(0.02, adjustment.y(), 1.0E-9);
    }
}
