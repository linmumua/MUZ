package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.world.PhysicalTableManager.ButtonAction;
import org.junit.jupiter.api.Test;

/**
 * 判定框必须罩住按钮文字。
 *
 * 图标删掉后玩家点的就是文字，而 Interaction 实体是从底边往上长的。
 * 原先判定框底边放在按钮基座（+0.02），普通动作按钮高只有 0.20×0.85=0.17，
 * 框顶到 0.19，而文字挂在 action-label-height=0.18 —— 只剩 0.01 余量，
 * 稍微调小 action-label-scale 文字就掉出框外，按钮彻底点不到。
 * 改成以文字为中心对齐后，余量恒等于半个框高。
 */
class ButtonLabelCoverageTest {
    private static final float JOIN_LABEL_SCALE = 0.46f;
    private static final float ACTION_LABEL_SCALE = 0.20f;
    private static final double JOIN_LABEL_HEIGHT = 0.18;
    private static final double ACTION_LABEL_HEIGHT = 0.18;

    @Test
    void actionButtonLabelSitsAtBoxCenter() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight(ButtonAction.READY, ACTION_LABEL_SCALE);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(ACTION_LABEL_HEIGHT, boxHeight);
        double top = bottom + boxHeight;

        assertTrue(ACTION_LABEL_HEIGHT > bottom, "文字必须高于框底");
        assertTrue(ACTION_LABEL_HEIGHT < top, "文字必须低于框顶");
        assertEquals(boxHeight / 2.0, ACTION_LABEL_HEIGHT - bottom, 1.0E-9, "文字应落在框的正中间");
    }

    @Test
    void joinButtonLabelSitsAtBoxCenter() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight(ButtonAction.JOIN, JOIN_LABEL_SCALE);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(JOIN_LABEL_HEIGHT, boxHeight);

        assertEquals(boxHeight / 2.0, JOIN_LABEL_HEIGHT - bottom, 1.0E-9);
    }

    @Test
    void tinyLabelScaleStillKeepsLabelInsideBox() {
        // 这是改动前会崩的场景：文字缩放调小后旧算法框顶会低于文字。
        float boxHeight = PhysicalTableManager.resolveHitboxHeight(ButtonAction.READY, 0.05f);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(ACTION_LABEL_HEIGHT, boxHeight);
        double top = bottom + boxHeight;

        assertTrue(ACTION_LABEL_HEIGHT > bottom && ACTION_LABEL_HEIGHT < top, "极小文字也必须留在框内");
    }

    @Test
    void hugeLabelScaleStillKeepsLabelInsideBox() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight(ButtonAction.JOIN, 5.0f);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(JOIN_LABEL_HEIGHT, boxHeight);
        double top = bottom + boxHeight;

        assertTrue(JOIN_LABEL_HEIGHT > bottom && JOIN_LABEL_HEIGHT < top, "超大文字也必须留在框内");
    }

    @Test
    void coverageReportCallsOutLabelAboveBoxTop() {
        // 旧算法的失败形态：框顶低于文字。诊断必须说得出来。
        String report = PhysicalTableManager.describeLabelCoverage(0.02, 0.19, 0.25);

        assertTrue(report.startsWith("否"), "文字高出框顶时要报否，实际=" + report);
    }

    @Test
    void coverageReportCallsOutLabelBelowBoxBottom() {
        String report = PhysicalTableManager.describeLabelCoverage(0.30, 0.60, 0.18);

        assertTrue(report.startsWith("否"), "文字低于框底时要报否，实际=" + report);
    }

    @Test
    void coverageReportConfirmsWhenLabelIsInside() {
        String report = PhysicalTableManager.describeLabelCoverage(0.02, 0.41, 0.18);

        assertTrue(report.startsWith("是"), "文字在框内时应报是，实际=" + report);
    }
}
