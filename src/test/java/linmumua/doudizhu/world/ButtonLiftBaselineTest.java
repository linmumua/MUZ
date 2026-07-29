package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.ui.TypewriterTextStyle;
import org.bukkit.entity.Display;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * 按钮 hover 抬升必须叠在文字基准位移之上。
 *
 * 图标删掉后 hover 抬的是 TextDisplay，而 TypewriterTextStyle.apply 给文字设了
 * 一个基准位移（CENTER 且无背景板时 y=0.03）。hover 动画会整体重写 Transformation，
 * 如果直接写 (0, lift, 0)，那 0.03 就被抹掉：hover 触发瞬间文字先往下跳 0.03，
 * 回落时再跳一次。这里直接调用真实的 buttonLiftTranslation，不复刻算法，
 * 否则实现改回 (0, lift, 0) 时测试照样全绿——那种测试等于没写。
 */
class ButtonLiftBaselineTest {
    private static final Vector3f BASE =
        TypewriterTextStyle.baseTranslationFor(Display.Billboard.CENTER, false);

    @Test
    void centerTextHasNonZeroBaseline() {
        // 基准不为零才有"被抹掉"这个风险，这条用来说明后面几条为什么必要。
        assertTrue(BASE.y() > 0.0f, "文字基准位移应当大于 0，实际=" + BASE.y());
    }

    @Test
    void liftStacksOnTopOfBaseline() {
        float lift = 0.03f;

        Vector3f actual = PhysicalTableManager.buttonLiftTranslation(BASE, lift);

        assertEquals(BASE.y() + lift, actual.y(), 1.0E-9f, "抬升必须以基准为起点");
        assertTrue(actual.y() > lift, "叠加后必须高于纯抬升值，否则说明基准被抹掉了");
    }

    @Test
    void restingStateKeepsBaselineNotZero() {
        // hover 结束时 lift=0，此时文字应当回到基准高度，而不是掉到 0。
        Vector3f resting = PhysicalTableManager.buttonLiftTranslation(BASE, 0.0f);

        assertEquals(BASE.y(), resting.y(), 1.0E-9f, "静止时应回到基准而非 0");
        assertTrue(resting.y() > 0.0f, "静止高度不能是 0，否则文字会比平时低");
    }

    @Test
    void horizontalComponentsAreCarriedThrough() {
        // 基准的 x/z 也不能丢，否则文字会横向偏移。
        Vector3f base = new Vector3f(0.11f, 0.03f, -0.07f);

        Vector3f actual = PhysicalTableManager.buttonLiftTranslation(base, 0.05f);

        assertEquals(0.11f, actual.x(), 1.0E-9f, "基准的横向位移不能丢");
        assertEquals(-0.07f, actual.z(), 1.0E-9f, "基准的纵向位移不能丢");
    }

    @Test
    void panelTextBaselineIsAlsoNonZero() {
        Vector3f panelBase = TypewriterTextStyle.baseTranslationFor(Display.Billboard.CENTER, true);

        assertTrue(panelBase.y() > 0.0f, "带背景板的基准位移也应大于 0");
    }

    @Test
    void panelAndPlainBaselinesDiffer() {
        // 基准会随背景板状态变化，所以取基准时不能写死 panel=false，
        // 否则带背景板的按钮文字 hover 时会偏到错误高度。
        Vector3f plain = TypewriterTextStyle.baseTranslationFor(Display.Billboard.CENTER, false);
        Vector3f panel = TypewriterTextStyle.baseTranslationFor(Display.Billboard.CENTER, true);

        assertTrue(
            Math.abs(panel.y() - plain.y()) > 1.0E-6f,
            "带背景板与不带背景板的基准应当不同，否则这条约束没有意义"
        );
    }
}
