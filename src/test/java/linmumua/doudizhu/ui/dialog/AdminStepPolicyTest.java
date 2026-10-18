package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import linmumua.doudizhu.config.AdminSettingArithmetic;
import org.junit.jupiter.api.Test;

/**
 * 锁住数值调节的步长策略：固定步长、整数、跟随三条旧语义一条都不能丢。
 *
 * 这是 Dialog 版数值调节的核心判据，也是箱子里 {@code HandGuiListener.adjust}
 * 那段「hasFixedStep ? fixedStep : 玩家全局步长」的等价迁移。旧版靠
 * HandGuiService 的实例字段记步长，测试很难触到；这里抽成纯函数后可以真正断言。
 */
class AdminStepPolicyTest {
    /** 固定步长项无视玩家选的步长——否则压层会被 0.01 抹平、角度会被 0.01 度搅乱。 */
    @Test
    void fixedStepSettingsIgnoreThePlayerStep() {
        assertEquals(0.0001, AdminStepPolicy.effectiveStep(AdminSetting.CARD_DEPTH_OFFSET, 1.0), 1.0E-12);
        assertEquals(1.0, AdminStepPolicy.effectiveStep(AdminSetting.CHAIR_ROTATION_DEGREES, 0.01), 1.0E-12);
        assertEquals(1.0, AdminStepPolicy.effectiveStep(AdminSetting.BUTTON_ARC_SMALL_ANGLE, 0.1), 1.0E-12);
        assertEquals(1.0, AdminStepPolicy.effectiveStep(AdminSetting.BUTTON_ARC_LARGE_ANGLE, 0.1), 1.0E-12);
    }

    /** 整数项走自己声明的整数步长，玩家把步长调到 0.0001 也不该让它走小数。 */
    @Test
    void integerSettingsUseTheirDeclaredIntegerStep() {
        assertEquals(1.0, AdminStepPolicy.effectiveStep(AdminSetting.TURN_COUNTDOWN_SECONDS, 0.0001), 1.0E-12);
        assertEquals(1.0, AdminStepPolicy.effectiveStep(AdminSetting.HOVER_CARD_ANIMATION_TYPE, 0.01), 1.0E-12);
        assertEquals(1.0, AdminStepPolicy.effectiveStep(AdminSetting.BOT_DELAY_MIN, 0.1), 1.0E-12);
    }

    /** 其余项跟随玩家选的步长；这是「换个设置不用重挑步长」那条手感的落点。 */
    @Test
    void ordinarySettingsFollowThePlayerStep() {
        assertEquals(0.1, AdminStepPolicy.effectiveStep(AdminSetting.HAND_SPACING, 0.1), 1.0E-12);
        assertEquals(1.0, AdminStepPolicy.effectiveStep(AdminSetting.BUTTON_DISTANCE, 1.0), 1.0E-12);
        assertEquals(0.0001, AdminStepPolicy.effectiveStep(AdminSetting.STATUS_HEIGHT, 0.0001), 1.0E-12);
    }

    /** 玩家步长非法时回落默认，绝不产生 0 步长（那样按钮会点不动）。 */
    @Test
    void invalidPlayerStepFallsBackToDefault() {
        assertEquals(AdminStepPolicy.DEFAULT_STEP, AdminStepPolicy.effectiveStep(AdminSetting.HAND_SPACING, 0.0), 1.0E-12);
        assertEquals(AdminStepPolicy.DEFAULT_STEP, AdminStepPolicy.effectiveStep(AdminSetting.HAND_SPACING, -1.0), 1.0E-12);
        assertEquals(AdminStepPolicy.DEFAULT_STEP, AdminStepPolicy.effectiveStep(AdminSetting.HAND_SPACING, Double.NaN), 1.0E-12);
        assertEquals(AdminStepPolicy.DEFAULT_STEP, AdminStepPolicy.effectiveStep(AdminSetting.HAND_SPACING, Double.POSITIVE_INFINITY), 1.0E-12);
    }

    /** 「可调步长」判定必须正好是「非固定步长且非整数」。 */
    @Test
    void adjustableMatchesEnumSemantics() {
        for (AdminSetting setting : AdminSetting.values()) {
            boolean expected = !setting.hasFixedStep() && !setting.integerSetting();
            assertEquals(expected, AdminStepPolicy.adjustable(setting), setting + " 的可调步长判定不对");
        }
    }

    /** 固定步长名单必须仍是那 4 项（与 FixedStepSettingsTest 同源），多了少了都要暴露。 */
    @Test
    void exactlyFourSettingsStillUseAFixedStep() {
        long fixed = Arrays.stream(AdminSetting.values()).filter(AdminSetting::hasFixedStep).count();
        assertEquals(4, fixed);
        assertFalse(AdminStepPolicy.adjustable(AdminSetting.CARD_DEPTH_OFFSET));
    }

    /** 预设必须覆盖最细的 0.0001 到最粗的 1，且单调递增。 */
    @Test
    void presetsSpanFineToCoarse() {
        assertEquals(0.0001, AdminStepPolicy.PRESETS[0], 1.0E-12, "最细预设应是 0.0001");
        assertEquals(1.0, AdminStepPolicy.PRESETS[AdminStepPolicy.PRESETS.length - 1], 1.0E-12, "最粗预设应是 1");
        for (int i = 1; i < AdminStepPolicy.PRESETS.length; i++) {
            assertTrue(AdminStepPolicy.PRESETS[i] > AdminStepPolicy.PRESETS[i - 1], "预设必须递增");
        }
    }

    /**
     * 滑块中心必须锚在**原始值**上，不得被步长网格吸附。
     * 步长 0.01→0.1、当前值 0.1234 时，相对索引 0（打开不拖直接确认）必须仍是 0.1234，
     * 而不是 round(0.1234/0.1)*0.1=0.1。这是父叙述者点名的验收。
     */
    @Test
    void sliderCenterStaysOnTheOriginalValueNotTheStepGrid() {
        for (double step : new double[] {0.1, 0.01, 1.0, 0.0001}) {
            AdminSettingArithmetic.SliderWindow window = AdminSettingArithmetic.sliderWindow(0.1234, step, 20);
            assertEquals(0.1234, window.valueAt(0), 1.0E-12, "步长 " + step + " 下不拖应保持原值");
        }
    }

    /** 相对索引 ±k ＝ 原值 ± k*step（从原值起算，不是从步网格起算）。 */
    @Test
    void sliderDeltaMovesByStepFromTheOriginalValue() {
        AdminSettingArithmetic.SliderWindow coarse = AdminSettingArithmetic.sliderWindow(0.1234, 0.1, 20);
        assertEquals(0.2234, coarse.valueAt(1), 1.0E-12, "step 0.1 的 +1 步应是原值 +0.1");
        assertEquals(0.0234, coarse.valueAt(-1), 1.0E-12, "step 0.1 的 -1 步应是原值 -0.1");

        AdminSettingArithmetic.SliderWindow fine = AdminSettingArithmetic.sliderWindow(0.1234, 0.01, 20);
        assertEquals(0.1334, fine.valueAt(1), 1.0E-12, "step 0.01 的 +1 步应是原值 +0.01");
        assertEquals(0.1134, fine.valueAt(-1), 1.0E-12, "step 0.01 的 -1 步应是原值 -0.01");
    }

    /** 窗口用相对索引：端点 ±halfSteps、初始 0（＝原值本身），float 可精确表示。 */
    @Test
    void sliderWindowUsesSmallRelativeIndicesWithZeroInitial() {
        AdminSettingArithmetic.SliderWindow window = AdminSettingArithmetic.sliderWindow(0.1234, 0.1, 20);

        assertEquals(-20f, window.start(), 0.0f);
        assertEquals(20f, window.end(), 0.0f);
        assertEquals(0f, window.initial().floatValue(), 0.0f, "初始相对索引必须是 0");
        assertEquals(0L, window.initialIndex());
        assertEquals(0.1234, window.valueAt(0), 1.0E-12);
        assertEquals(0.1234, window.valueAt(window.initialIndex()), 1.0E-12);
    }

    /**
     * 大值 + 极小步长：相对索引转 float 不失真，拖一格恰好一步
     * （绝对长索引 1e6/1e-4=1e10 转 float 会并档、跨多步）。
     */
    @Test
    void hugeValueWithTinyStepStillMovesExactlyOneStepPerIndex() {
        AdminSettingArithmetic.SliderWindow window = AdminSettingArithmetic.sliderWindow(1_000_000.0, 0.0001, 20);

        assertEquals(1000000.0001, window.valueAt(1), 1.0E-9);
        assertEquals(999999.9999, window.valueAt(-1), 1.0E-9);
        assertEquals(1_000_000.0, window.valueAt(0), 1.0E-9);
        assertEquals(1000000.0020, window.valueAt(20), 1.0E-9);
    }

    /** 步长文本：整数不带小数点，小数保留有效位。 */
    @Test
    void labelFormatsStepsReadably() {
        assertEquals("1", AdminStepPolicy.label(1.0));
        assertEquals("0.1", AdminStepPolicy.label(0.1));
        assertEquals("0.01", AdminStepPolicy.label(0.01));
        assertEquals("0.0001", AdminStepPolicy.label(0.0001));
    }
}
