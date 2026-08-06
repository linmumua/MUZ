package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import linmumua.doudizhu.config.AdminSettingArithmetic;
import org.junit.jupiter.api.Test;

/**
 * 管理菜单的调整范围已按用户要求放开，不再按声明的 min/max 夹紧。
 *
 * 设计取舍：{@link AdminSettingArithmetic#nextValue} 是纯函数，
 * 保留按传入区间夹紧的能力（AdjustmentStepSemanticsTest 仍在校验这个能力），
 * 放开的做法是让调用方传 ±无穷，而不是把夹紧从算术里挖掉。
 * 这样"能不能夹紧"和"要不要夹紧"分开，前者仍受测试保护。
 */
class AdminSettingRangeReleasedTest {
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");
    private static final Path GUI =
        Path.of("src/main/java/linmumua/doudizhu/ui/HandGuiService.java");

    /**
     * 传 ±无穷时 nextValue 不得夹紧，否则"放开"这件事无从实现。
     * 失败条件：nextValue 里加了与传入区间无关的硬边界。
     */
    @Test
    void infiniteBoundsMeanNoClampAtAll() {
        double far = AdminSettingArithmetic.nextValue(
            999.0, 1.0, 0.05, 1, true,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY
        );
        double negative = AdminSettingArithmetic.nextValue(
            -999.0, 1.0, 0.05, 1, false,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY
        );

        assertEquals(1000.0, far, 1.0E-9, "上行被夹住了，调整范围没有真正放开");
        assertEquals(-1000.0, negative, 1.0E-9, "下行被夹住了，调整范围没有真正放开");
    }

    /**
     * 小数项的调用方必须传 ±无穷，不能传声明的 min/max。
     * 失败条件：把 setting.minValue() / setting.maxValue() 传回 nextValue。
     */
    @Test
    void decimalSettingsPassInfiniteBoundsInsteadOfDeclaredRange() throws IOException {
        String body = methodBody(
            PLUGIN,
            "public void adjustAdminSetting(AdminSetting setting, boolean increase, int multiplier, double stepOverride)"
        );

        assertTrue(
            body.contains("Double.NEGATIVE_INFINITY") && body.contains("Double.POSITIVE_INFINITY"),
            "没有传 ±无穷，声明的 min/max 会重新变成硬边界"
        );
        assertTrue(
            !body.contains("setting.minValue()") && !body.contains("setting.maxValue()"),
            "又把声明的 min/max 传进去了，调整范围会重新被夹紧"
        );
    }

    /**
     * 整数项同样不得再夹紧。
     * 失败条件：整数分支里恢复 Math.max(min, Math.min(max, next)) 那一行。
     */
    @Test
    void integerSettingsAreNotClampedEither() throws IOException {
        String body = methodBody(
            PLUGIN,
            "public void adjustAdminSetting(AdminSetting setting, boolean increase, int multiplier, double stepOverride)"
        );

        assertTrue(
            !body.contains("(int) setting.minValue()"),
            "整数项又按声明区间夹紧了"
        );
    }

    /**
     * GUI 不得再显示"可调范围"：范围已不生效，显示出来只会误导。
     * 失败条件：把那行 lore 加回去，或重新引入 adminSettingRange。
     */
    @Test
    void guiNoLongerAdvertisesARangeThatDoesNotApply() throws IOException {
        String source = Files.readString(GUI);

        assertTrue(!source.contains("可调范围"), "GUI 又显示了不生效的可调范围");
        assertTrue(!source.contains("adminSettingRange"), "adminSettingRange 又回来了，会显示不生效的区间");
    }

    private static String methodBody(Path file, String signature) throws IOException {
        String source = Files.readString(file);
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
