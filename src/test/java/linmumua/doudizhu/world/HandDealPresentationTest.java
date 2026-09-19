package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 锁定开局发牌展示的固定槽位、左右顺序与整圈角度连续性。
 */
class HandDealPresentationTest {
    @Test
    void keepsSeventeenSlotsCenteredAndOrderedLeftToRight() {
        assertEquals(17, HandDealPresentation.SLOT_COUNT);
        assertEquals(-8.0, HandDealPresentation.slotOffset(0));
        assertEquals(0.0, HandDealPresentation.slotOffset(8));
        assertEquals(8.0, HandDealPresentation.slotOffset(16));
        for (int index = 1; index < HandDealPresentation.SLOT_COUNT; index++) {
            assertTrue(HandDealPresentation.slotOffset(index) > HandDealPresentation.slotOffset(index - 1));
        }
    }

    @Test
    void preservesContinuousRotationAtHalfAndFullTurn() {
        HandDealPresentation presentation = new HandDealPresentation();
        presentation.activate();
        assertEquals(0.0, presentation.continuousDegrees(0.0, true));
        assertEquals(90.0, presentation.continuousDegrees(90.0, true));
        assertEquals(180.0, presentation.continuousDegrees(180.0, true));
        assertEquals(270.0, presentation.continuousDegrees(270.0, true));
        assertEquals(360.0, presentation.continuousDegrees(360.0, true));
        assertEquals(360.0, presentation.continuousDegrees(0.0, false));
    }

    @Test
    void resetClearsReusableOpeningSlotsAndAngleCache() {
        HandDealPresentation presentation = new HandDealPresentation();
        presentation.activate();
        presentation.seat(java.util.UUID.randomUUID());
        presentation.continuousDegrees(360.0, true);
        presentation.reset(0.0);
        assertTrue(presentation.seats().isEmpty());
        assertEquals(0.0, presentation.continuousDegrees(0.0, true));
        assertTrue(!presentation.active());
    }
}
