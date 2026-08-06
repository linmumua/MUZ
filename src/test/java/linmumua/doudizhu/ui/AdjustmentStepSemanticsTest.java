package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.config.AdminSettingArithmetic;
import org.junit.jupiter.api.Test;

/**
 * 锁住"微调步长"按钮的语义，直接调用生产代码 AdminSettingArithmetic。
 *
 * 修复前有三个症状，都源于同一个设计缺陷——步长只喂给了 hitbox 那一条路径：
 *   1. 步长选 0.01，桌子高度却一次跳 0.05（用的是该项自己写死的 step）
 *   2. Shift 按 10 倍算的是 0.05 而不是 0.01，于是一次跳 0.5
 *   3. 角度始终跳 1（BUTTON_ARC_*_ANGLE 自己的 step 就是 1.0）
 *
 * 还有一层隐藏坑：存储和读取都按一位小数取整，
 * 0.01 的加减在写回时被抹平，表现为连点数字不动。
 *
 * 注意这些断言必须走 AdminSettingArithmetic，
 * 不能在测试里自己重算一份公式——那样生产代码改回去测试也不会失败。
 */
class AdjustmentStepSemanticsTest {
    /** 与 HandGuiService.HITBOX_ADJUSTMENT_STEPS 保持一致。 */
    private static final double[] STEPS = {0.01, 0.1, 1.0};

    /** 桌子高度那一项声明的步长，就是它导致 0.01 变 0.05。 */
    private static final double TABLE_HEIGHT_DECLARED_STEP = 0.05;

    /** 弧度那两项声明的步长。 */
    private static final double ARC_ANGLE_DECLARED_STEP = 1.0;

    /**
     * 上下限给得很宽，这些用例只关心步长算得对不对，不测夹紧。
     * 夹紧单独由 clampsToBounds 覆盖。
     */
    private static double click(double current, double step, double declared, int multiplier, boolean increase) {
        return AdminSettingArithmetic.nextValue(current, step, declared, multiplier, increase, -1000.0, 1000.0);
    }

    @Test
    void stepCycleCoversThreeLevels() {
        assertEquals(3, STEPS.length, "步长循环应当是 0.01 → 0.1 → 1");
        assertEquals(0.01, STEPS[0], 1.0E-9);
        assertEquals(0.1, STEPS[1], 1.0E-9);
        assertEquals(1.0, STEPS[2], 1.0E-9);
    }

    /** 选 0.01 就该走 0.01，不能再变成桌子高度自己的 0.05。 */
    @Test
    void fineStepBeatsDeclaredStep() {
        double next = click(0.18, 0.01, TABLE_HEIGHT_DECLARED_STEP, 1, true);

        assertEquals(0.19, next, 1.0E-9, "步长 0.01 单击应当只加 0.01，不是声明的 0.05");
    }

    /** 角度也要吃步长，不能永远跳 1。 */
    @Test
    void angleFollowsSelectedStep() {
        double next = click(30.0, 0.1, ARC_ANGLE_DECLARED_STEP, 1, true);

        assertEquals(
            30.1,
            AdminSettingArithmetic.nextValue(30.0, 0.1, ARC_ANGLE_DECLARED_STEP, 1, true, 0.0, 90.0),
            1.0E-9,
            "角度应当按选中的 0.1 步长走"
        );
        assertTrue(Math.abs(next - 31.0) > 1.0E-6, "角度又跳回 1 了");
    }

    /** Shift 是当前步长的 10 倍，而不是别的基数的 10 倍。 */
    @Test
    void shiftClickMultipliesCurrentStepByTen() {
        for (double step : STEPS) {
            double plain = click(0.0, step, TABLE_HEIGHT_DECLARED_STEP, 1, true);
            double shifted = click(0.0, step, TABLE_HEIGHT_DECLARED_STEP, 10, true);

            assertEquals(step, plain, 1.0E-9, "单击应当等于步长本身");
            assertEquals(
                AdminSettingArithmetic.roundToStorePrecision(step * 10.0),
                shifted,
                1.0E-9,
                "Shift 应当是步长 " + step + " 的 10 倍"
            );
        }
    }

    /** 0.01 步长下 Shift 应当是 0.1，绝不是修复前那个 0.5。 */
    @Test
    void shiftWithFineStepIsNotTheOldHardcodedJump() {
        double shifted = click(0.0, 0.01, TABLE_HEIGHT_DECLARED_STEP, 10, true);

        assertEquals(0.1, shifted, 1.0E-9, "0.01 步长 Shift 应当是 0.1");
        assertTrue(
            Math.abs(shifted - 0.5) > 1.0E-6,
            "又跳回 0.5 了，说明 Shift 乘的还是写死的 0.05"
        );
    }

    /** 存储精度必须吃住 0.01：连点三次要真的累加 0.03。 */
    @Test
    void storePrecisionKeepsFineSteps() {
        double moved = 0.18;
        for (int i = 0; i < 3; i++) {
            moved = click(moved, 0.01, TABLE_HEIGHT_DECLARED_STEP, 1, true);
        }

        assertEquals(0.21, moved, 1.0E-9, "连点三次 0.01 应当累加 0.03");
    }

    /** 一位小数取整会吃掉 0.01，这条证明存储精度不能退回去。 */
    @Test
    void storePrecisionIsFinerThanOneDecimal() {
        double value = AdminSettingArithmetic.roundToStorePrecision(0.187);

        assertEquals(0.187, value, 1.0E-9, "存储精度退回一位小数了，0.01 步长会失效");
    }

    /** 没给步长时回落到该项声明的步长。 */
    @Test
    void fallsBackToDeclaredStepWhenNoOverride() {
        assertEquals(
            TABLE_HEIGHT_DECLARED_STEP,
            AdminSettingArithmetic.effectiveStep(0.0, TABLE_HEIGHT_DECLARED_STEP),
            1.0E-9,
            "步长为 0 时应当回落到声明值"
        );
        assertEquals(
            TABLE_HEIGHT_DECLARED_STEP,
            AdminSettingArithmetic.effectiveStep(Double.NaN, TABLE_HEIGHT_DECLARED_STEP),
            1.0E-9,
            "步长为 NaN 时应当回落到声明值"
        );
    }

    /** 连续加减要能回到原值，不能因为取整漂移。 */
    @Test
    void increaseThenDecreaseReturnsToOrigin() {
        for (double step : STEPS) {
            double value = 0.55;
            value = click(value, step, TABLE_HEIGHT_DECLARED_STEP, 1, true);
            value = click(value, step, TABLE_HEIGHT_DECLARED_STEP, 1, false);

            assertEquals(0.55, value, 1.0E-9, "步长 " + step + " 加后再减应当回到原值");
        }
    }

    /** 上下限要夹紧。 */
    @Test
    void clampsToBounds() {
        double high = AdminSettingArithmetic.nextValue(4.99, 1.0, 0.05, 1, true, -5.0, 5.0);
        double low = AdminSettingArithmetic.nextValue(-4.99, 1.0, 0.05, 1, false, -5.0, 5.0);

        assertEquals(5.0, high, 1.0E-9, "上限应当夹紧到 5.0");
        assertEquals(-5.0, low, 1.0E-9, "下限应当夹紧到 -5.0");
    }

    /**
     * 压层类设置固定 0.0001 步长，且必须真的能存下来。
     *
     * 这条同时守两件事：固定步长本身生效，以及存储精度足够细。
     * 精度只要退回三位小数，0.0001 就会被取整抹平，
     * 表现为连点多次数字都不动。
     */
    @Test
    void layerDepthUsesFixedTenThousandthStep() {
        double moved = 0.01;
        for (int i = 0; i < 3; i++) {
            // 第二个参数是玩家选的全局步长，这里故意给 1.0（最粗），
            // 传入的固定步长 0.0001 必须压过它
            moved = AdminSettingArithmetic.nextValue(moved, 0.0001, 1.0, 1, true, 0.0001, 1.0);
        }

        assertEquals(0.0103, moved, 1.0E-9, "压层连点三次应当累加 0.0003");
    }

    /** 存储精度必须细到能容纳 0.0001。 */
    @Test
    void storePrecisionHoldsTenThousandths() {
        assertEquals(
            0.0001,
            AdminSettingArithmetic.roundToStorePrecision(0.0001),
            1.0E-12,
            "存储精度不足以容纳 0.0001，压层步长会被抹平"
        );
        assertEquals(
            0.0103,
            AdminSettingArithmetic.roundToStorePrecision(0.0103),
            1.0E-12,
            "四位小数应当被完整保留"
        );
    }

    /** 角度类设置固定 1 度，全局步长再细也不该让它走 0.01 度。 */
    @Test
    void angleUsesFixedOneDegreeStep() {
        for (double globalStep : STEPS) {
            double next = AdminSettingArithmetic.nextValue(30.0, 1.0, globalStep, 1, true, 0.0, 90.0);

            assertEquals(
                31.0,
                next,
                1.0E-9,
                "全局步长 " + globalStep + " 下角度仍应当只加 1 度"
            );
        }
    }

    /**
     * 方块椅角度会被吸附到 90 度整数倍，这是方块椅的固有限制。
     *
     * 后果是方块椅模式下那个按钮实际调不动：固定步长算出 1.0，
     * 吸附后又回到 0。这条断言把该限制固化成已知行为，
     * 避免以后有人误以为是按钮坏了而去改固定步长。
     * CE 家具椅子不走这条路，1 度步长正常生效。
     */
    @Test
    void blockChairRotationSnapsToRightAngles() {
        assertEquals(0.0, AdminSettingArithmetic.snapToBlockChairRotation(1.0), 1.0E-9,
            "方块椅下 1 度会被吸附回 0，按钮调不动");
        assertEquals(90.0, AdminSettingArithmetic.snapToBlockChairRotation(89.0), 1.0E-9);
        assertEquals(90.0, AdminSettingArithmetic.snapToBlockChairRotation(46.0), 1.0E-9);
        assertEquals(180.0, AdminSettingArithmetic.snapToBlockChairRotation(170.0), 1.0E-9);
    }
}
