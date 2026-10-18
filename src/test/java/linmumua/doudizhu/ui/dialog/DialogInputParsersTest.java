package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Dialog 输入解析纯函数的意图测试。
 *
 * <p>重点不在「解析对不对」，而在需求要求的两条硬约束：
 * 非法输入必须**明确拒绝**（不静默钳错 / 不 nearest），旧的 RGB 输入格式（十进制三元组与
 * 六位十六进制）必须继续被接受，否则迁移到原生 Dialog 会悄悄丢掉既有用法。
 */
class DialogInputParsersTest {
    @Test
    void rgbAcceptsLegacyDecimalAndHexForms() {
        assertArrayEquals(new int[] {255, 226, 92}, DialogInputParsers.parseRgbRequired("255,226,92"));
        assertArrayEquals(new int[] {249, 181, 181}, DialogInputParsers.parseRgbRequired("F9B5B5"));
        assertArrayEquals(new int[] {249, 181, 181}, DialogInputParsers.parseRgbRequired("#f9b5b5"));
    }

    /** 玩家常从聊天复制粘贴，中文全角逗号必须容错。 */
    @Test
    void rgbToleratesFullWidthCommaAndSpaces() {
        assertArrayEquals(new int[] {1, 2, 3}, DialogInputParsers.parseRgbRequired("1，2，3"));
        assertArrayEquals(new int[] {255, 0, 0}, DialogInputParsers.parseRgbRequired(" 255 0 0 "));
    }

    /** 非法输入必须明确拒绝，不得静默当成某个默认色。 */
    @Test
    void rgbRejectsIllegalInputInsteadOfSilentlyClamping() {
        assertThrows(IllegalArgumentException.class, () -> DialogInputParsers.parseRgbRequired(""));
        assertThrows(IllegalArgumentException.class, () -> DialogInputParsers.parseRgbRequired("300,0,0"));
        assertThrows(IllegalArgumentException.class, () -> DialogInputParsers.parseRgbRequired("1,2"));
        assertThrows(IllegalArgumentException.class, () -> DialogInputParsers.parseRgbRequired("ZZZZZZ"));
    }

    @Test
    void formatRgbProducesUppercaseHex() {
        assertEquals("FFE25C", DialogInputParsers.formatRgb(255, 226, 92));
        // 越界分量按 0..255 收敛：-5 → 0，300 → 255。
        assertEquals("00FF00", DialogInputParsers.formatRgb(-5, 300, 0));
    }

    @Test
    void cancelRecognizesBlankAndCancelWords() {
        assertTrue(DialogInputParsers.isCancel(null));
        assertTrue(DialogInputParsers.isCancel("  "));
        assertTrue(DialogInputParsers.isCancel("取消"));
        assertTrue(DialogInputParsers.isCancel("CANCEL"));
        assertFalse(DialogInputParsers.isCancel("12"));
    }

    /** 滑块/文本共用的有限值解析：NaN 与正负无穷必须拒绝，否则会写进 config.yml。 */
    @Test
    void finiteDoubleRejectsNanAndInfinity() {
        assertEquals(1.25, DialogInputParsers.parseFiniteDoubleRequired(" 1.25 "), 1.0E-12);
        assertThrows(IllegalArgumentException.class, () -> DialogInputParsers.parseFiniteDoubleRequired("NaN"));
        assertThrows(IllegalArgumentException.class, () -> DialogInputParsers.parseFiniteDoubleRequired("Infinity"));
        assertThrows(IllegalArgumentException.class, () -> DialogInputParsers.parseFiniteDoubleRequired("-Infinity"));
        assertThrows(IllegalArgumentException.class, () -> DialogInputParsers.parseFiniteDoubleRequired("abc"));
    }

    @Test
    void requiredIntRejectsDecimals() {
        assertEquals(7, DialogInputParsers.parseRequiredInt("7"));
        assertThrows(IllegalArgumentException.class, () -> DialogInputParsers.parseRequiredInt("1.0"));
    }
}
