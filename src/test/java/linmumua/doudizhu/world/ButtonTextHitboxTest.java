package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.ui.TypewriterTextStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

/**
 * 文字按钮的 Interaction 必须严格跟随实际字体像素尺寸。
 */
class ButtonTextHitboxTest {
    @Test
    void chineseGlyphsUseDefaultFontAdvance() {
        assertEquals(18, PhysicalTableManager.textPixelWidth("准备", false));
        assertEquals(20, PhysicalTableManager.textPixelWidth("准备", true));
    }

    @Test
    void digitsAndNarrowAsciiUseTheirOwnAdvance() {
        assertEquals(27, PhysicalTableManager.textPixelWidth("叫1分", true));
        assertEquals(5, PhysicalTableManager.textPixelWidth("il", false));
        assertEquals(7, PhysicalTableManager.textPixelWidth("il", true));
    }

    @Test
    void multilineWidthUsesTheWidestLine() {
        assertEquals(20, PhysicalTableManager.textPixelWidth("准备\ni", true));
    }

    @Test
    void componentWidthIncludesBoldAndDisplayScale() {
        Component label = Component.text("准备").decoration(TextDecoration.BOLD, true);

        assertEquals(0.10f, PhysicalTableManager.resolveHitboxWidth(label, 0.20f), 1.0E-6f);
    }

    @Test
    void longerTextProducesWiderHitboxAtTheSameScale() {
        float shortWidth = PhysicalTableManager.resolveHitboxWidth("准备", 0.20f, true);
        float longWidth = PhysicalTableManager.resolveHitboxWidth("加入座位1", 0.20f, true);

        assertTrue(longWidth > shortWidth);
    }

    @Test
    void hitboxScalesLinearlyWithoutMinimumOrMaximumClamp() {
        float base = PhysicalTableManager.resolveHitboxWidth("准备", 0.20f, true);
        float tiny = PhysicalTableManager.resolveHitboxWidth("准备", 0.01f, true);
        float huge = PhysicalTableManager.resolveHitboxWidth("准备", 4.0f, true);

        assertEquals(base / 20.0f, tiny, 1.0E-6f);
        assertEquals(base * 20.0f, huge, 1.0E-6f);
    }

    @Test
    void runtimeFocusLabelKeepsBoldWidth() {
        Component label = TypewriterTextStyle.focus("加入座位1");

        assertEquals(0.5405f, PhysicalTableManager.resolveHitboxWidth(label, 0.46f), 1.0E-6f);
    }

    @Test
    void changingBetweenJoinAndActionScaleRequiresFreshTextDisplay() {
        assertTrue(PhysicalTableManager.buttonLabelScaleMatches(0.20f, 0.20f, 0.20f, 0.20f));
        assertFalse(PhysicalTableManager.buttonLabelScaleMatches(0.46f, 0.46f, 0.46f, 0.20f));
    }

    @Test
    void heightUsesNinePixelsPerLine() {
        assertEquals(0.045f, PhysicalTableManager.resolveHitboxHeight("准备", 0.20f), 1.0E-6f);
        assertEquals(0.090f, PhysicalTableManager.resolveHitboxHeight("准备\n离开", 0.20f), 1.0E-6f);
    }
}
