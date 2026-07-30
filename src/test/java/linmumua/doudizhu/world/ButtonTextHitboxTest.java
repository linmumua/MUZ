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
    void chineseGlyphsDropTheTrailingSpacingPixel() {
        // 全角字 advance 9 像素里含 1 像素字间距。两个字累加 advance 是 18，
        // 但最后那 1 像素是空白，墨迹只有 17；加粗每字再多 1 像素，得 19。
        assertEquals(17, PhysicalTableManager.textPixelWidth("准备", false));
        assertEquals(19, PhysicalTableManager.textPixelWidth("准备", true));
    }

    @Test
    void digitsAndNarrowAsciiUseTheirOwnAdvance() {
        assertEquals(26, PhysicalTableManager.textPixelWidth("叫1分", true));
        assertEquals(4, PhysicalTableManager.textPixelWidth("il", false));
        assertEquals(6, PhysicalTableManager.textPixelWidth("il", true));
    }

    @Test
    void singleGlyphKeepsItsInkWidth() {
        // 单字不能因为扣间距就退化成 0，否则判定框会塌成一条线。
        assertEquals(8, PhysicalTableManager.textPixelWidth("准", false));
        assertEquals(1, PhysicalTableManager.textPixelWidth(".", false));
    }

    @Test
    void multilineWidthUsesTheWidestLine() {
        assertEquals(19, PhysicalTableManager.textPixelWidth("准备\ni", true));
    }

    @Test
    void emptyTextHasNoInk() {
        assertEquals(0, PhysicalTableManager.textPixelWidth("", true));
    }

    @Test
    void componentWidthIncludesBoldAndDisplayScale() {
        Component label = Component.text("准备").decoration(TextDecoration.BOLD, true);

        assertEquals(19 * 0.20f / 40.0f, PhysicalTableManager.resolveHitboxWidth(label, 0.20f), 1.0E-6f);
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

        // 加入座位1 = 4 个全角字（加粗后各 10）+ 1 个数字（加粗后 7），advance 47，
        // 扣掉行尾那 1 像素字间距得墨迹 46。
        assertEquals(46 * 0.46f / 40.0f, PhysicalTableManager.resolveHitboxWidth(label, 0.46f), 1.0E-6f);
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
