package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.DoudizhuPlugin.AdminSetting;
import org.junit.jupiter.api.Test;

/**
 * 锁住「哪些设置使用固定步长」这份名单。
 *
 * 这条测试补的是一个真实覆盖缺口：把 HandGuiListener.adjust 里
 * setting.hasFixedStep() 那个分支整个删掉之后，
 * 原有全部测试依然通过——因为 AdjustmentStepSemanticsTest 测的是
 * AdminSettingArithmetic 的算术，而「哪些项该用固定步长」的判定
 * 在 AdminSetting 枚举上，此前没有任何断言覆盖。
 *
 * AdminSetting 是枚举，读它不需要启动 Bukkit，所以能直接断言。
 */
class FixedStepSettingsTest {
    /** 压层类：固定 0.0001，全局步长最细也只有 0.01，不够用。 */
    @Test
    void layerDepthSettingsUseTenThousandthStep() {
        assertFixedStep(AdminSetting.CARD_DEPTH_OFFSET, 0.0001);
    }

    /** 角度类：固定 1 度，用 0.01 度调角度没有意义。 */
    @Test
    void angleSettingsUseOneDegreeStep() {
        assertFixedStep(AdminSetting.CHAIR_ROTATION_DEGREES, 1.0);
        assertFixedStep(AdminSetting.BUTTON_ARC_SMALL_ANGLE, 1.0);
        assertFixedStep(AdminSetting.BUTTON_ARC_LARGE_ANGLE, 1.0);
    }

    /** 名单必须正好是这 4 项，多一项少一项都要在这里暴露。 */
    @Test
    void exactlyFourSettingsUseFixedStep() {
        long count = java.util.Arrays.stream(AdminSetting.values())
            .filter(AdminSetting::hasFixedStep)
            .count();

        assertEquals(
            4,
            count,
            "固定步长名单变了。当前有固定步长的项："
                + java.util.Arrays.stream(AdminSetting.values())
                    .filter(AdminSetting::hasFixedStep)
                    .map(Enum::name)
                    .toList()
        );
    }

    /** 常见的普通设置必须仍然跟随全局步长，不能被误标成固定。 */
    @Test
    void ordinarySettingsStillFollowGlobalStep() {
        assertFalse(AdminSetting.TABLE_SPAWN_OFFSET_Y.hasFixedStep(), "桌子高度应当跟随全局步长");
        assertFalse(AdminSetting.HAND_SPACING.hasFixedStep(), "手牌间距应当跟随全局步长");
        assertFalse(AdminSetting.CHAIR_DISTANCE.hasFixedStep(), "椅子离桌距离应当跟随全局步长");
        assertFalse(AdminSetting.HOVER_CARD_SCALE.hasFixedStep(), "悬停放大倍数应当跟随全局步长");
        assertFalse(AdminSetting.BUTTON_HITBOX_LATERAL.hasFixedStep(), "交互箱偏移应当跟随全局步长");
    }

    /** 压层的下限不能高于它的固定步长，否则往下调会被直接夹回去。 */
    @Test
    void layerDepthLowerBoundAllowsItsOwnStep() {
        assertTrue(
            AdminSetting.CARD_DEPTH_OFFSET.minValue() <= 0.0001,
            "下限 " + AdminSetting.CARD_DEPTH_OFFSET.minValue()
                + " 高于固定步长 0.0001，往下调会被立即夹回，等于只能加不能减"
        );
    }

    private static void assertFixedStep(AdminSetting setting, double expected) {
        assertTrue(setting.hasFixedStep(), setting.name() + " 应当使用固定步长");
        assertEquals(
            expected,
            setting.fixedStep(),
            1.0E-12,
            setting.name() + " 的固定步长应当是 " + expected
        );
    }
}
