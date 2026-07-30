package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.ui.TypewriterTextStyle;
import org.bukkit.entity.Display;
import org.junit.jupiter.api.Test;

/**
 * Interaction 从底边向上生长，判定框必须以实际渲染文字的竖直中心为基准对齐。
 *
 * TextDisplay 的 apply 会把文字整体上移一段基准位移（CENTER 且无背景板时 0.03）。
 * 判定框的 Y 坐标是文字实体位置而非渲染中心，不补这段位移框就会整体低于文字：
 * action-label-scale 默认 0.20 时框高只有 0.045，而 0.03 占了三分之二，
 * 表现就是"框比文字矮"、文字上半部分点不到。
 */
class ButtonLabelCoverageTest {
    private static final float JOIN_LABEL_SCALE = 0.46f;
    private static final float ACTION_LABEL_SCALE = 0.20f;
    private static final double JOIN_LABEL_HEIGHT = 0.18;
    private static final double ACTION_LABEL_HEIGHT = 0.18;
    // 按钮统一用 CENTER、无背景板；必须从 apply 取，不能写死，apply 改了这里才跟着对。
    private static final double BASE_LIFT =
        TypewriterTextStyle.baseTranslationFor(Display.Billboard.CENTER, false).y();

    @Test
    void glyphCenterSitsAtBoxCenter() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight("准备", ACTION_LABEL_SCALE);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(ACTION_LABEL_HEIGHT, boxHeight);
        double top = bottom + boxHeight;
        double glyphCenter = ACTION_LABEL_HEIGHT + BASE_LIFT;

        assertTrue(glyphCenter > bottom, "字形中心必须高于框底");
        assertTrue(glyphCenter < top, "字形中心必须低于框顶");
        assertEquals(boxHeight / 2.0, glyphCenter - bottom, 1.0E-9, "字形中心应在框的正中间");
    }

    @Test
    void joinButtonGlyphCenterSitsAtBoxCenter() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight("加入座位1", JOIN_LABEL_SCALE);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(JOIN_LABEL_HEIGHT, boxHeight);
        double glyphCenter = JOIN_LABEL_HEIGHT + BASE_LIFT;

        assertEquals(boxHeight / 2.0, glyphCenter - bottom, 1.0E-9);
    }

    @Test
    void tinyLabelScaleStillCentersItsExactHitbox() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight("准备", 0.05f);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(ACTION_LABEL_HEIGHT, boxHeight);
        double top = bottom + boxHeight;
        double glyphCenter = ACTION_LABEL_HEIGHT + BASE_LIFT;

        assertTrue(glyphCenter > bottom && glyphCenter < top);
    }

    @Test
    void hugeLabelScaleStillCentersItsExactHitbox() {
        float boxHeight = PhysicalTableManager.resolveHitboxHeight("加入座位1", 5.0f);
        double bottom = PhysicalTableManager.hitboxBottomForLabel(JOIN_LABEL_HEIGHT, boxHeight);
        double top = bottom + boxHeight;
        double glyphCenter = JOIN_LABEL_HEIGHT + BASE_LIFT;

        assertTrue(glyphCenter > bottom && glyphCenter < top);
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

