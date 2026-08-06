package linmumua.doudizhu.ui;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HandGuiServiceHitboxStepTest {

    @Test
    void hitboxAdjustmentStepStartsAtFinePrecisionAndCyclesThroughAllChoices() {
        HandGuiService service = new HandGuiService(null);
        UUID playerId = UUID.randomUUID();
        UUID otherPlayerId = UUID.randomUUID();

        assertEquals(0.01, service.hitboxAdjustmentStep(playerId));
        assertEquals("0.01", service.hitboxAdjustmentStepLabel(playerId));
        assertEquals("0.1", service.cycleHitboxAdjustmentStep(playerId));
        assertEquals(0.1, service.hitboxAdjustmentStep(playerId));
        assertEquals("1", service.cycleHitboxAdjustmentStep(playerId));
        assertEquals(1.0, service.hitboxAdjustmentStep(playerId));
        assertEquals(0.01, service.hitboxAdjustmentStep(otherPlayerId), "未切换步长的玩家应保持默认精度");
        assertEquals("0.01", service.cycleHitboxAdjustmentStep(playerId));
    }
}
