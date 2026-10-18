package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.config.AdminSettingArithmetic;
import org.junit.jupiter.api.Test;

/**
 * 滑块整数步索引映射的意图测试。
 *
 * <p>需求要求：精细档位步长 0.0001 必须能用滑块调到而不被 float 精度损失抹平；
 * 有限值域滑块窗口只能用「当前值 ± 步数」构造，**不得**用设置声明的 min/max 当端点
 * （那等于把用户明确要求放开的硬上下限又收回来）。
 */
class AdminSettingSliderWindowTest {
    /** 0.0001 步长下，整数索引↔double 必须互逆，不能因为 float 精度把相邻档挤成同一个值。 */
    @Test
    void fineStepIndexRoundTripsWithoutPrecisionLoss() {
        double step = 0.0001;
        long index = AdminSettingArithmetic.toStepIndex(0.0103, step);
        assertEquals(103L, index, "0.0103 / 0.0001 应当得到整数索引 103");
        assertEquals(0.0103, AdminSettingArithmetic.fromStepIndex(index, step), 1.0E-12);

        // 相邻两档必须映射回不同的值（精度被抹平就会相等）。
        double low = AdminSettingArithmetic.fromStepIndex(102L, step);
        double high = AdminSettingArithmetic.fromStepIndex(103L, step);
        assertTrue(Math.abs(high - low) > 1.0E-9, "相邻档被精度损失抹成同一个值");
    }

    /** 关键验收：窗口以「原值」为锚，不拖直接确认必须仍是原值，不得被吸成 round(value/step)*step。 */
    @Test
    void windowKeepsOriginalValueAsAnchorWithoutSnapping() {
        AdminSettingArithmetic.SliderWindow window = AdminSettingArithmetic.sliderWindow(0.1234, 0.1, 20);
        assertEquals(0.1234, window.valueAt(window.initialIndex()), 1.0E-12,
            "步长切到 0.1 后不拖直接确认必须保持 0.1234，而不是被吸成 0.1");
        assertEquals(0L, window.initialIndex());
        // ±1 步 = 严格在原值上加/减一个 step。
        assertEquals(0.2234, window.valueAt(1L), 1.0E-12);
        assertEquals(0.0234, window.valueAt(-1L), 1.0E-12);
    }

    /** 相对索引是小范围（-halfSteps..halfSteps），float 精确表示：端点与初始值都是精确 float。 */
    @Test
    void windowEndpointsAreSmallExactFloatRelativeIndices() {
        AdminSettingArithmetic.SliderWindow window = AdminSettingArithmetic.sliderWindow(0.1234, 0.1, 20);
        assertEquals(-20f, window.start());
        assertEquals(20f, window.end());
        assertEquals(0f, window.initial());
        assertEquals(-20L, window.startIndex());
        assertEquals(20L, window.endIndex());
    }

    /** 超大当前值 + 极小步长：相对索引仍能区分相邻档（绝对索引转 float 会跨多步）。 */
    @Test
    void windowSurvivesHugeCenterWithTinyStep() {
        double center = 1_000_000.0;
        double step = 0.0001;
        AdminSettingArithmetic.SliderWindow window = AdminSettingArithmetic.sliderWindow(center, step, 20);
        assertEquals(center, window.valueAt(0L), 1.0E-6);
        double oneStep = window.valueAt(1L);
        assertTrue(Math.abs(oneStep - center) > 1.0E-9, "超大中心下相邻一档被 float 精度抹平");
        assertEquals(center + step, oneStep, 1.0E-6);
    }

    /** 非正 / 非有限步长必须明确拒绝，不得静默当成 1。 */
    @Test
    void nonPositiveOrNonFiniteStepIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AdminSettingArithmetic.toStepIndex(1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> AdminSettingArithmetic.toStepIndex(1.0, -0.5));
        assertThrows(IllegalArgumentException.class, () -> AdminSettingArithmetic.toStepIndex(1.0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> AdminSettingArithmetic.sliderWindow(1.0, 0.0, 5));
    }

    /** halfSteps 为负视作 0：窗口退化为单点（相对索引 0），不抛异常。 */
    @Test
    void negativeHalfStepsCollapsesToSinglePoint() {
        AdminSettingArithmetic.SliderWindow window = AdminSettingArithmetic.sliderWindow(2.0, 0.1, -3);
        assertEquals(0L, window.startIndex());
        assertEquals(0L, window.endIndex());
        assertEquals(0L, window.initialIndex());
        assertEquals(2.0, window.valueAt(0L), 1.0E-12, "退化窗口仍锚在原值");
    }
}
