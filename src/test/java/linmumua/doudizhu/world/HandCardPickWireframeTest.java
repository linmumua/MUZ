package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import linmumua.doudizhu.world.HandCardPickGeometry.CardQuad;
import linmumua.doudizhu.world.HandCardPickGeometry.Envelope;
import linmumua.doudizhu.world.HandCardPickGeometry.Hit;
import linmumua.doudizhu.world.HandCardPickGeometry.WireDot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 调试线框必须就是真正的判定边界。
 *
 * <p>这组用例的价值全在最后一条：线框要是另算一遍几何，调试就会「显示一套、判定另一套」，
 * 那比没有调试更糟——照着框去点却点不到，人会去改判定，而判定本来是对的。
 */
class HandCardPickWireframeTest {

    private static final double SCALE = 0.8;

    private static Envelope sampleEnvelope() {
        return HandCardPickGeometry.envelope(
            SCALE, SCALE, SCALE * 1.08, SCALE * 1.08, 0.06 * 1.15, 0.05);
    }

    @Test
    @DisplayName("线框点数恒定：每边固定段数，实体数不随包络尺寸浮动")
    void wireframeDotCountIsConstant() {
        int expected = 4 * HandCardPickGeometry.SEGMENTS_PER_EDGE;

        // 三种差异很大的包络，点数必须一样——渲染侧的池化复用依赖这个恒定性
        assertEquals(expected, HandCardPickGeometry.wireframe(0.0, sampleEnvelope()).size(),
            "常规包络的线框点数不对");
        assertEquals(expected, HandCardPickGeometry.wireframe(
                1.5, new Envelope(0.01, 0.0, 0.01)).size(),
            "极小包络的线框点数不对");
        assertEquals(expected, HandCardPickGeometry.wireframe(
                -2.0, new Envelope(3.0, 1.0, 5.0)).size(),
            "极大包络的线框点数不对");
    }

    @Test
    @DisplayName("线框包围盒等于包络本身：不多画也不少画")
    void wireframeBoundsMatchEnvelope() {
        double centerU = 0.37;
        Envelope env = sampleEnvelope();
        List<WireDot> dots = HandCardPickGeometry.wireframe(centerU, env);

        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (WireDot dot : dots) {
            // 横向段的中点不到边缘，要把它自己的长度算进去才是真正的横向跨度
            double halfSpanU = dot.horizontal() ? dot.length() * 0.5 : 0.0;
            double halfSpanV = dot.horizontal() ? 0.0 : dot.length() * 0.5;
            minU = Math.min(minU, dot.u() - halfSpanU);
            maxU = Math.max(maxU, dot.u() + halfSpanU);
            minV = Math.min(minV, dot.v() - halfSpanV);
            maxV = Math.max(maxV, dot.v() + halfSpanV);
        }
        assertEquals(centerU - env.halfWidth(), minU, 1.0e-12, "线框左界不等于包络左界");
        assertEquals(centerU + env.halfWidth(), maxU, 1.0e-12, "线框右界不等于包络右界");
        assertEquals(env.centerVOffset() - env.halfHeight(), minV, 1.0e-12, "线框下界不等于包络下界");
        assertEquals(env.centerVOffset() + env.halfHeight(), maxV, 1.0e-12, "线框上界不等于包络上界");
    }

    @Test
    @DisplayName("每段都落在边上，且同边内等距无缝")
    void segmentsTileEachEdgeWithoutGaps() {
        double centerU = -0.2;
        Envelope env = sampleEnvelope();
        List<WireDot> dots = HandCardPickGeometry.wireframe(centerU, env);

        double left = centerU - env.halfWidth();
        double right = centerU + env.halfWidth();
        double bottom = env.centerVOffset() - env.halfHeight();
        double top = env.centerVOffset() + env.halfHeight();

        List<Double> bottomEdge = new ArrayList<>();
        for (WireDot dot : dots) {
            if (dot.horizontal()) {
                // 横向段只可能在下边或上边
                assertTrue(Math.abs(dot.v() - bottom) < 1.0e-12 || Math.abs(dot.v() - top) < 1.0e-12,
                    "横向段没有落在上下边上，v=" + dot.v());
                if (Math.abs(dot.v() - bottom) < 1.0e-12) {
                    bottomEdge.add(dot.u());
                }
            } else {
                assertTrue(Math.abs(dot.u() - left) < 1.0e-12 || Math.abs(dot.u() - right) < 1.0e-12,
                    "竖向段没有落在左右边上，u=" + dot.u());
            }
        }

        // 下边的段首尾相接：相邻中点间距必须恰好等于段长，否则线框有缝或重叠
        bottomEdge.sort(Double::compareTo);
        assertEquals(HandCardPickGeometry.SEGMENTS_PER_EDGE, bottomEdge.size(), "下边段数不对");
        double step = (right - left) / HandCardPickGeometry.SEGMENTS_PER_EDGE;
        for (int i = 1; i < bottomEdge.size(); i++) {
            assertEquals(step, bottomEdge.get(i) - bottomEdge.get(i - 1), 1.0e-12,
                "下边第 " + i + " 段与前一段不相接：线框会出现缝或重叠");
        }
    }

    @Test
    @DisplayName("零尺寸包络退化成重合点，不抛异常")
    void degenerateEnvelopeCollapsesInsteadOfThrowing() {
        List<WireDot> dots = HandCardPickGeometry.wireframe(0.5, new Envelope(0.0, 0.0, 0.0));

        assertEquals(4 * HandCardPickGeometry.SEGMENTS_PER_EDGE, dots.size(), "退化时点数仍应恒定");
        for (WireDot dot : dots) {
            assertEquals(0.5, dot.u(), 1.0e-12, "退化时所有点应重合在中心");
            assertEquals(0.0, dot.v(), 1.0e-12, "退化时所有点应重合在中心");
            assertEquals(0.0, dot.length(), 1.0e-12, "退化时段长应为 0");
        }
    }

    /**
     * 线框就是判定边界：角点内侧必命中，外侧必不命中。
     *
     * <p>这是这组用例的核心。线框若另算一遍几何，就会「显示一套、判定另一套」——
     * 照着框点却点不到，人会去改本来正确的判定。所以必须把两者钉在一起。
     *
     * <p>失败条件：让 {@code wireframe} 读不同于 {@code intersect} 的尺寸，
     * 比如自己再加一圈余量。
     */
    @Test
    @DisplayName("线框角点内侧命中、外侧脱靶：画出来的就是真判定边界")
    void wireframeCornersCoincideWithPickBoundary() {
        double centerU = 0.0;
        Envelope env = sampleEnvelope();
        CardQuad quad = new CardQuad(7, 0, centerU, env.centerVOffset(), 0.0,
            env.halfWidth(), env.halfHeight());

        double left = centerU - env.halfWidth();
        double right = centerU + env.halfWidth();
        double bottom = env.centerVOffset() - env.halfHeight();
        double top = env.centerVOffset() + env.halfHeight();
        double nudge = 1.0e-4;

        double[][] corners = {
            {left, bottom, +1, +1},
            {right, bottom, -1, +1},
            {left, top, +1, -1},
            {right, top, -1, -1}
        };
        for (double[] corner : corners) {
            double u = corner[0];
            double v = corner[1];
            double inU = u + nudge * corner[2];
            double inV = v + nudge * corner[3];
            assertNotNull(pickAt(quad, inU, inV),
                String.format("角点 (%.4f, %.4f) 内侧应当命中，线框比判定区大了", u, v));

            double outU = u - nudge * corner[2];
            double outV = v - nudge * corner[3];
            assertNull(pickAt(quad, outU, outV),
                String.format("角点 (%.4f, %.4f) 外侧应当脱靶，线框比判定区小了", u, v));
        }
    }

    /**
     * 牌本体框必须严格小于包络框——这正是「不严丝合缝」的量化来源。
     *
     * <p>两者若一样大，调试显示就失去意义（三层框叠在一起看不出差别），
     * 也说明包络没有并进抬升，抖动闭环会从那条缝回来。
     */
    @Test
    @DisplayName("牌本体框严格小于包络框：差值就是不严丝合缝的量")
    void cardBodyIsStrictlySmallerThanEnvelope() {
        Envelope body = HandCardPickGeometry.cardBody(SCALE, SCALE);
        Envelope env = sampleEnvelope();

        assertEquals(HandCardPickGeometry.halfWidth(SCALE), body.halfWidth(), 1.0e-12,
            "牌本体半宽应当就是模型盒半宽");
        assertEquals(HandCardPickGeometry.halfHeight(SCALE), body.halfHeight(), 1.0e-12,
            "牌本体半高应当就是模型盒半高");
        assertTrue(env.halfHeight() > body.halfHeight() + 1.0e-9,
            "包络半高必须严格大于牌本体：否则 hover 抬升没被并进来，"
                + "牌抬起时会冲出判定区，抖动闭环从这条缝回来");
    }

    private static Hit pickAt(CardQuad quad, double u, double v) {
        // 从牌前方沿 n 轴正向看过去，方向已归一化
        return HandCardPickGeometry.intersect(quad, u, v, -2.0, 0.0, 0.0, 1.0, 10.0);
    }
}
