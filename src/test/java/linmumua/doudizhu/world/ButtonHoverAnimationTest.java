package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 按钮 hover 的抬升与回落。
 *
 * hover 失效过一次：tick() 里没坐下的玩家直接 continue，空位按钮永远拿不到 hover 状态。
 * 状态那半边由 tick() 负责，这里锁住动画这半边的数学，确保指向时会一路涨到顶、
 * 移开后能完全归零，且回落比抬升快，不留拖尾。
 */
class ButtonHoverAnimationTest {
    private static final int INTERPOLATION_TICKS = 8;

    @Test
    void hoveringClimbsAllTheWayToFull() {
        float rise = PhysicalTableManager.hoverRiseStep(INTERPOLATION_TICKS);
        float fall = PhysicalTableManager.hoverFallStep(rise);

        float progress = 0.0f;
        for (int tick = 0; tick < INTERPOLATION_TICKS; tick++) {
            progress = PhysicalTableManager.stepHoverProgress(progress, true, rise, fall);
        }

        assertEquals(1.0f, progress, 1.0E-5f, "配置的 tick 数走完应当刚好到顶");
    }

    @Test
    void progressNeverOvershootsFull() {
        float rise = PhysicalTableManager.hoverRiseStep(INTERPOLATION_TICKS);
        float fall = PhysicalTableManager.hoverFallStep(rise);

        float progress = 1.0f;
        progress = PhysicalTableManager.stepHoverProgress(progress, true, rise, fall);

        assertEquals(1.0f, progress, 1.0E-6f, "到顶后继续指向不能超过 1");
    }

    @Test
    void movingAwayFallsBackToZero() {
        float rise = PhysicalTableManager.hoverRiseStep(INTERPOLATION_TICKS);
        float fall = PhysicalTableManager.hoverFallStep(rise);

        float progress = 1.0f;
        for (int tick = 0; tick < INTERPOLATION_TICKS; tick++) {
            progress = PhysicalTableManager.stepHoverProgress(progress, false, rise, fall);
        }

        assertEquals(0.0f, progress, 1.0E-6f, "鼠标移开后必须完全归零，否则按钮卡在放大状态");
    }

    @Test
    void fallIsFasterThanRiseSoButtonsDoNotTrail() {
        float rise = PhysicalTableManager.hoverRiseStep(INTERPOLATION_TICKS);
        float fall = PhysicalTableManager.hoverFallStep(rise);

        assertTrue(fall > rise, "回落要比抬升快，移开鼠标才不拖尾");
        assertTrue(fall <= 1.0f, "单 tick 回落量不能超过整个进度区间");
    }

    @Test
    void singleTickConfigStillWorks() {
        // interpolation-ticks 配成 0 或负数时不能除出无穷大。
        assertEquals(1.0f, PhysicalTableManager.hoverRiseStep(0), 1.0E-6f);
        assertEquals(1.0f, PhysicalTableManager.hoverRiseStep(-5), 1.0E-6f);
    }

    @Test
    void scaleGrowsFromBaseToHoverPeak() {
        float base = 0.42f;
        float peak = 1.06f;

        assertEquals(base, PhysicalTableManager.hoverButtonScale(base, peak, 0.0f), 1.0E-6f);
        assertEquals(base * peak, PhysicalTableManager.hoverButtonScale(base, peak, 1.0f), 1.0E-6f);
        assertTrue(
            PhysicalTableManager.hoverButtonScale(base, peak, 0.5f) > base,
            "进度过半时按钮应当已经变大，玩家才看得出反馈"
        );
    }
}
