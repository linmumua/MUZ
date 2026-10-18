package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import linmumua.doudizhu.config.AdminSettingArithmetic;
import org.junit.jupiter.api.Test;

/**
 * 管理菜单的调整范围已按用户要求放开，不再按声明的 min/max 夹紧。
 *
 * <p>设计取舍：{@link AdminSettingArithmetic#nextValue} 是纯函数，
 * 保留按传入区间夹紧的能力（AdjustmentStepSemanticsTest 仍在校验这个能力），
 * 放开的做法是让调用方传 ±无穷，而不是把夹紧从算术里挖掉。
 * 这样"能不能夹紧"和"要不要夹紧"分开，前者仍受测试保护。
 *
 * <p>【迁移补充：1.10.60 管理菜单迁 Dialog】调整入口从箱子 Inventory 换成原生 Dialog，
 * 「不重新施加硬上下限」这条用户要求必须跟着迁移一起守住——否则新 UI 只要在滑块边界上
 * 顺手把 {@code setting.minValue()/maxValue()} 传进去，就等于把之前放开的硬边界又压回来了，
 * 而编译器完全不会提醒。所以这里除了继续守旧 {@code DoudizhuPlugin.adjustAdminSetting} 的
 * ±无穷语义，还新增两条针对新 Dialog 层的契约：
 * <ol>
 *   <li>滑块窗口构造器 {@code AdminSettingArithmetic.sliderWindow} 不得接收声明的 min/max；</li>
 *   <li>{@code ui/dialog} 下的源码不得把声明 min/max 喂进滑块窗口，也不得再显示「可调范围」。</li>
 * </ol>
 */
class AdminSettingRangeReleasedTest {
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");
    private static final Path GUI =
        Path.of("src/main/java/linmumua/doudizhu/ui/HandGuiService.java");
    private static final Path DIALOG_DIR =
        Path.of("src/main/java/linmumua/doudizhu/ui/dialog");

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
     *
     * <p>旧箱子 GUI 与新的 Dialog 数值屏都要扫到——用户要求的是「界面不再显示不生效的范围」，
     * 换了 UI 不等于要求失效。
     */
    @Test
    void guiNoLongerAdvertisesARangeThatDoesNotApply() throws IOException {
        assertNoRangeAdvertising(GUI);
        for (Path file : dialogSources()) {
            assertNoRangeAdvertising(file);
        }
    }

    /**
     * 滑块窗口构造器不得接收声明的 min/max。
     *
     * <p>失败条件：给 {@code AdminSettingArithmetic.sliderWindow} 加一个收 min/max 的重载——
     * 那等于允许调用方把放开的硬上下限重新当成滑块端点。
     */
    @Test
    void sliderWindowBuilderTakesNoDeclaredBounds() {
        Method[] windows = Arrays.stream(AdminSettingArithmetic.class.getMethods())
            .filter(method -> method.getName().equals("sliderWindow"))
            .toArray(Method[]::new);
        assertTrue(windows.length > 0, "找不到 sliderWindow，这条测试的锚点已失效");

        List<String> offenders = new ArrayList<>();
        for (Method method : windows) {
            if (method.getParameterCount() != 3) {
                offenders.add(method.getName() + Arrays.toString(method.getParameterTypes()));
            }
        }
        assertTrue(offenders.isEmpty(),
            "sliderWindow 出现了非 3 参重载，会允许把声明的 min/max 当成滑块边界：" + offenders);
    }

    /**
     * Dialog 数值屏不得把声明的 min/max 喂进滑块窗口。
     *
     * <p>失败条件：{@code sliderWindow(...)} 的实参里出现 {@code setting.minValue()/maxValue()}。
     * 这不是「字符串洁癖」——滑块窗口的边界就是玩家能拖到的边界，把声明区间放上去，
     * 之前"放开硬上下限"的努力就在新 UI 上原地作废了。
     */
    @Test
    void dialogSliderNeverFeedsDeclaredBounds() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : dialogSources()) {
            String source = Files.readString(file);
            String call = callTextOrNull(source, "sliderWindow(");
            if (call == null) {
                continue;
            }
            if (call.contains("minValue") || call.contains("maxValue")) {
                offenders.add(file.getFileName() + "：" + call.replaceAll("\\s+", " "));
            }
        }
        assertTrue(offenders.isEmpty(),
            "Dialog 滑块窗口用了声明的 min/max 当边界，等于把放开的硬上下限又压回来：" + offenders);
    }

    private static void assertNoRangeAdvertising(Path file) throws IOException {
        String source = Files.readString(file);
        assertTrue(!source.contains("可调范围"), file.getFileName() + " 又显示了不生效的可调范围");
        assertTrue(!source.contains("adminSettingRange"), file.getFileName() + " 又出现了 adminSettingRange，会显示不生效的区间");
    }

    private static List<Path> dialogSources() throws IOException {
        try (Stream<Path> stream = Files.walk(DIALOG_DIR)) {
            List<Path> sources = stream
                .filter(path -> path.toString().endsWith(".java"))
                .toList();
            assertFalse(sources.isEmpty(), "找不到 ui/dialog 下的源码，这条测试的锚点已失效");
            return sources;
        }
    }

    /**
     * 抠出第一次出现的 {@code call} 调用表达式（含参数与右括号）。
     * 找不到返回 null；括号不闭合视为锚点失效（fail）。
     */
    private static String callTextOrNull(String source, String call) {
        int start = source.indexOf(call);
        if (start < 0) {
            return null;
        }
        int open = source.indexOf('(', start);
        if (open < 0) {
            fail("找到 " + call + " 却没有左括号，锚点已失效");
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        fail("找不到 " + call + " 的右括号，锚点已失效");
        return null;
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
