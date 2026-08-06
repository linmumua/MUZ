package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 判定几何必须是悬停动画的不动点：牌怎么动，「点得中哪张」都不能跟着动。
 *
 * <p>抖动的成因是一个闭环：命中 → 牌变形 → 判定区跟着变 → 脱靶 → 牌复位 → 又命中。
 * 掐断它有两个必要条件，这个类分别守住：
 * <ul>
 *   <li>牌面所在的平面不能随悬停移动——所以悬停只放大长宽，厚度恒定，且不再有法向平移；</li>
 *   <li>判定矩形必须覆盖动画扫过的<b>每一帧</b>，而不只是静止态和完全悬停态两个端点。</li>
 * </ul>
 *
 * <p>第二条尤其容易漏：缩放随进度线性插值，抬升却走动画曲线，两者形状不同，
 * 中间帧完全可能跑到两个端点的并集之外。
 */
class HandCardHoverFixedPointTest {
    private static final Path MANAGER = Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    /** 静止态 Y 缩放，取服务器实配 private-card-scale 0.8。 */
    private static final double REST_SCALE_Y = 0.8;

    /** 悬停放大倍数，取 render.card-hover.scale 默认值。 */
    private static final double HOVER_SCALE = 1.08;

    private static final double MAX_SCALE_Y = REST_SCALE_Y * HOVER_SCALE;

    /**
     * 抬升上限：选中与悬停两项相加，取 config.yml 默认 0.18 + 0.06。
     * 两项会同时生效（选中的牌也能被悬停），所以包络必须按和来算。
     */
    private static final double NOMINAL_MAX_LIFT = 0.24;

    /** BACK_OUT 曲线的钳位上界，见 PhysicalTableManager.backOut。 */
    private static final double BACK_OUT_CLAMP = 1.15;

    /** 采样步长：一次动画最长几十 tick，这个密度远细于任何真实帧。 */
    private static final double STEP = 0.001;

    /**
     * 牌在进度 p 这一帧的 Y 缩放。
     *
     * <p>照抄 PhysicalTableManager.privateCardScale：放大倍率对原始进度线性插值，
     * <b>不走</b>动画曲线。这个不对称是下面那条覆盖用例的全部意义所在。
     */
    private static double frameScaleY(double progress) {
        return REST_SCALE_Y * (1.0 + (HOVER_SCALE - 1.0) * progress);
    }

    /** 四条动画曲线，照抄 PhysicalTableManager 的实现。 */
    private static double curve(String name, double p) {
        return switch (name) {
            case "LINEAR" -> p;
            case "EASE_OUT" -> 1.0 - Math.pow(1.0 - p, 3.0);
            case "EASE_IN_OUT" -> p < 0.5 ? 4.0 * p * p * p : 1.0 - Math.pow(-2.0 * p + 2.0, 3.0) / 2.0;
            case "BACK_OUT" -> {
                double c1 = 1.70158;
                double c3 = c1 + 1.0;
                double value = 1.0 + c3 * Math.pow(p - 1.0, 3.0) + c1 * Math.pow(p - 1.0, 2.0);
                yield Math.max(0.0, Math.min(BACK_OUT_CLAMP, value));
            }
            default -> throw new IllegalArgumentException(name);
        };
    }

    /** 生产传给 envelope 的抬升上界：名义上限乘曲线过冲，见 pickHandCard。 */
    private static double liftBound() {
        return NOMINAL_MAX_LIFT * BACK_OUT_CLAMP;
    }

    private static HandCardPickGeometry.Envelope envelope() {
        // 横向下限传 0：这个用例验的是竖直方向的 hover 不动点，横向不参与，
        // 补下限只会让半宽被牌距接管、掩盖掉竖直方向要测的东西。
        return HandCardPickGeometry.envelope(
            REST_SCALE_Y, REST_SCALE_Y, MAX_SCALE_Y, MAX_SCALE_Y, liftBound(), 0.0);
    }

    @Test
    @DisplayName("包络覆盖动画的每一帧，而不只是静止态与完全悬停态两个端点")
    void envelopeCoversEveryAnimationFrame() {
        HandCardPickGeometry.Envelope envelope = envelope();
        double envelopeBottom = envelope.centerVOffset() - envelope.halfHeight();
        double envelopeTop = envelope.centerVOffset() + envelope.halfHeight();

        for (String name : new String[] {"LINEAR", "EASE_OUT", "EASE_IN_OUT", "BACK_OUT"}) {
            for (double p = 0.0; p <= 1.0 + 1.0e-9; p += STEP) {
                double scaleY = frameScaleY(p);
                double lift = NOMINAL_MAX_LIFT * curve(name, p);
                double frameBottom = lift + HandCardPickGeometry.centerVOffset(scaleY)
                    - HandCardPickGeometry.halfHeight(scaleY);
                double frameTop = lift + HandCardPickGeometry.centerVOffset(scaleY)
                    + HandCardPickGeometry.halfHeight(scaleY);

                assertTrue(
                    frameBottom >= envelopeBottom - 1.0e-9,
                    name + " 曲线在 p=" + p + " 时牌下缘 " + frameBottom
                        + " 低于包络下界 " + envelopeBottom + "：这一帧牌的最下沿点不中，抖动闭环成立"
                );
                assertTrue(
                    frameTop <= envelopeTop + 1.0e-9,
                    name + " 曲线在 p=" + p + " 时牌上缘 " + frameTop
                        + " 高于包络上界 " + envelopeTop + "：这一帧牌的最上沿点不中，抖动闭环成立"
                );
                assertTrue(
                    HandCardPickGeometry.halfWidth(scaleY) <= envelope.halfWidth() + 1.0e-9,
                    name + " 曲线在 p=" + p + " 时牌比包络还宽，牌两侧多伸出的部分点不到"
                );
            }
        }
    }

    /**
     * 包络只由「静止 / 最大」两组参数决定，与当帧进度无关。
     *
     * 这条是不动点性质的直接表述：同一手牌无论正被悬停到哪一步，
     * pickHandCard 拿到的判定矩形都是同一个，因此「命中谁」不可能被自己的输出改写。
     */
    @Test
    @DisplayName("包络与当帧动画进度无关：判定矩形在整段动画里恒定")
    void envelopeIsConstantThroughoutTheAnimation() {
        HandCardPickGeometry.Envelope first = envelope();
        HandCardPickGeometry.Envelope again = envelope();

        assertEquals(first, again, "同样的静止/最大参数必须给出同一个包络");
        assertTrue(first.halfHeight() > HandCardPickGeometry.halfHeight(REST_SCALE_Y),
            "包络必须比静止态高，否则抬起后的牌落在判定区外");
        assertTrue(first.halfWidth() >= HandCardPickGeometry.halfWidth(MAX_SCALE_Y),
            "包络必须按放大后的宽度取，否则牌放大后多伸出的一圈点不到");
    }

    /**
     * 悬停只放大长宽，厚度恒定。
     *
     * 厚度就是牌的法向尺寸，它决定牌面平面在世界里的位置。一旦厚度随悬停变化，
     * 牌面会沿法向前后挪，射线与它的交点跟着漂移——这是包络管不到的第二条抖动通路，
     * 因为包络只约束牌面内的 u/v 范围，不约束平面自身的位置。
     */
    @Test
    @DisplayName("悬停缩放不碰厚度：法向尺寸不含悬停因子")
    void hoverScalingLeavesThicknessAlone() throws IOException {
        String source = Files.readString(MANAGER);
        String body = section(source, "private Vector3f privateCardScale(float hoverProgress)", "\n    }");

        assertTrue(
            body.contains("plugin.getPrivateCardDepthScale() * baseFactor"),
            "厚度必须只乘静止态的 baseFactor，当前实现：" + body
        );
        assertFalse(
            body.contains("getPrivateCardDepthScale() * faceFactor")
                || body.contains("getPrivateCardDepthScale() * hoverFactor"),
            "厚度乘上了悬停因子，牌面会沿法向前后挪，交点跟着漂移"
        );
        assertTrue(
            body.contains("plugin.getPrivateCardWidthScale() * faceFactor")
                && body.contains("plugin.getPrivateCardHeightScale() * faceFactor"),
            "长宽必须吃悬停因子，否则悬停完全没有放大反馈"
        );
    }

    /**
     * 悬停不产生任何法向平移。
     *
     * 曾经有一项 render.card-hover.backward-offset 把悬停牌沿法向往桌里推。
     * 平移和厚度变化是同一个问题的两种形态：都让牌面平面动起来，
     * 使交点漂移约 offset × tan(入射角)。这一项已整体删除，这里守住它不复活。
     */
    @Test
    @DisplayName("渲染循环里没有任何悬停派生的法向平移")
    void hoverNeverTranslatesAlongTheNormal() throws IOException {
        String source = Files.readString(MANAGER);

        assertFalse(
            source.contains("BackwardOffset") || source.contains("backwardOffset"),
            "悬停向后偏移又回来了：沿法向平移会让射线交点跟着悬停漂移"
        );
    }

    private static String section(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "源码里找不到 " + from + "，这条测试的锚点已失效");
        int end = source.indexOf(to, start);
        assertTrue(end > start, "源码里找不到 " + from + " 之后的结束锚点");
        return source.substring(start, end);
    }
}
