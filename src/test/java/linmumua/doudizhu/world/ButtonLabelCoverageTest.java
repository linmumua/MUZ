package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Interaction 从底边向上生长，精确文字判定框必须始终以文字中心对齐。
 */
class ButtonLabelCoverageTest {
    private static final float JOIN_LABEL_SCALE = 0.46f;
    private static final float ACTION_LABEL_SCALE = 0.20f;
    private static final double JOIN_LABEL_HEIGHT = 0.18;
    private static final double ACTION_LABEL_HEIGHT = 0.18;

    @Test
    void actionButtonLabelSitsAtBoxCenter() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight("准备", ACTION_LABEL_SCALE);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(ACTION_LABEL_HEIGHT, boxHeight);
        double top = bottom + boxHeight;

        assertTrue(ACTION_LABEL_HEIGHT > bottom);
        assertTrue(ACTION_LABEL_HEIGHT < top);
        assertEquals(boxHeight / 2.0, ACTION_LABEL_HEIGHT - bottom, 1.0E-9);
    }

    @Test
    void joinButtonLabelSitsAtBoxCenter() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight("加入座位1", JOIN_LABEL_SCALE);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(JOIN_LABEL_HEIGHT, boxHeight);

        assertEquals(boxHeight / 2.0, JOIN_LABEL_HEIGHT - bottom, 1.0E-9);
    }

    @Test
    void tinyLabelScaleStillCentersItsExactHitbox() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight("准备", 0.05f);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(ACTION_LABEL_HEIGHT, boxHeight);
        double top = bottom + boxHeight;

        assertTrue(ACTION_LABEL_HEIGHT > bottom && ACTION_LABEL_HEIGHT < top);
    }

    @Test
    void hugeLabelScaleStillCentersItsExactHitbox() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight("加入座位1", 5.0f);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(JOIN_LABEL_HEIGHT, boxHeight);
        double top = bottom + boxHeight;

        assertTrue(JOIN_LABEL_HEIGHT > bottom && JOIN_LABEL_HEIGHT < top);
    }

    @Test
    void coverageReportCallsOutLabelAboveBoxTop() {
        String report = PhysicalTableManager.describeLabelCoverage(0.02, 0.19, 0.25);

        assertTrue(report.startsWith("否"));
    }

    @Test
    void coverageReportCallsOutLabelBelowBoxBottom() {
        String report = PhysicalTableManager.describeLabelCoverage(0.30, 0.60, 0.18);

        assertTrue(report.startsWith("否"));
    }

    @Test
    void coverageReportConfirmsWhenLabelIsInside() {
        String report = PhysicalTableManager.describeLabelCoverage(0.02, 0.41, 0.18);

        assertTrue(report.startsWith("是"));
    }
}
