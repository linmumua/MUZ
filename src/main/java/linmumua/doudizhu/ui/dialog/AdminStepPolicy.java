package linmumua.doudizhu.ui.dialog;

import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;

/**
 * 管理数值调节的「本次点击走多大步长」策略，纯函数，可脱离 Bukkit 单测。
 *
 * 这里保留三条旧语义，一条都不能丢（{@code FixedStepSettingsTest} /
 * {@code AdjustmentStepSemanticsTest} 锁的是同一批约束）：
 * <ul>
 *   <li><b>固定步长项</b>（压层 0.0001、角度 1）忽略玩家选的步长；</li>
 *   <li><b>整数项</b>走自己声明的整数步长；</li>
 *   <li>其余项<b>跟随</b>玩家在设置屏里选的步长（预设或自定义）。</li>
 * </ul>
 *
 * 步长选择搬进设置屏后仍要「换个设置不用重挑步长」，所以跟随项有一份默认值
 * （与旧三档循环的首档 0.01 一致）。
 */
final class AdminStepPolicy {
    /** 步长预设：0.0001（最细）到 1（最粗）。仅「跟随项」可从中挑选。 */
    static final double[] PRESETS = {0.0001, 0.001, 0.01, 0.1, 1.0};

    /** 跟随项的默认步长，与旧 GUI 的三档循环首档一致。 */
    static final double DEFAULT_STEP = 0.01;

    private AdminStepPolicy() {
    }

    /** 该项是否允许玩家改步长（固定步长项与整数项不吃玩家选的全局步长）。 */
    static boolean adjustable(AdminSetting setting) {
        return !setting.hasFixedStep() && !setting.integerSetting();
    }

    /**
     * 本次点击实际生效的步长。
     *
     * @param setting 目标设置
     * @param playerStep 玩家当前选的步长（仅跟随项会用到；非法时回落到默认）
     * @return 实际步长
     */
    static double effectiveStep(AdminSetting setting, double playerStep) {
        if (setting.hasFixedStep()) {
            return setting.fixedStep();
        }
        if (setting.integerSetting()) {
            // 整数项的旧语义就是 (int) step，且至少 1，避免 0 步长点不动。
            return Math.max(1.0, Math.floor(setting.step()));
        }
        return Double.isFinite(playerStep) && playerStep > 0.0 ? playerStep : DEFAULT_STEP;
    }

    /** 步长的显示文本：整数不带小数点，小数去掉多余的 0。 */
    static String label(double step) {
        if (!Double.isFinite(step)) {
            return String.valueOf(step);
        }
        if (step == Math.rint(step) && Math.abs(step) < 1.0E9) {
            return String.valueOf((long) step);
        }
        return java.math.BigDecimal.valueOf(step).stripTrailingZeros().toPlainString();
    }
}
