package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 相邻文字按钮的精确判定框不能重叠，否则点击一个会误触旁边按钮。
 */
class ButtonHitboxSpacingTest {
    private static final float ACTION_LABEL_SCALE = 0.20f;
    private static final double ARC_ANGLE_SMALL = 30.0;
    private static final double ARC_ANGLE_LARGE = 42.0;
    private static final double ARC_RADIUS_SMALL = 0.70;
    private static final double ARC_RADIUS_LARGE = 0.86;

    private static double lateralOf(double offset, double maxOffset, double arcAngleDegrees, double radius) {
        double normalized = maxOffset <= 0.0001 ? 0.0 : offset / maxOffset;
        return Math.sin(normalized * Math.toRadians(arcAngleDegrees)) * radius;
    }

    private static double minimumGap(double[] offsets, double arcAngleDegrees, double radius) {
        double maxOffset = 0.0;
        for (double offset : offsets) {
            maxOffset = Math.max(maxOffset, Math.abs(offset));
        }
        double smallest = Double.MAX_VALUE;
        for (int i = 0; i + 1 < offsets.length; i++) {
            double a = lateralOf(offsets[i], maxOffset, arcAngleDegrees, radius);
            double b = lateralOf(offsets[i + 1], maxOffset, arcAngleDegrees, radius);
            smallest = Math.min(smallest, Math.abs(b - a));
        }
        return smallest;
    }

    @Test
    void lobbyButtonsDoNotOverlap() {
        double gap = minimumGap(new double[] {-0.64, 0.0, 0.64}, ARC_ANGLE_SMALL, ARC_RADIUS_SMALL);
        float widest = PhysicalTableManager.resolveHitboxWidth("准备", ACTION_LABEL_SCALE, true);

        assertTrue(gap > widest, "LOBBY 相邻按钮判定框重叠：间距=" + gap + " 框宽=" + widest);
    }

    @Test
    void biddingButtonsDoNotOverlap() {
        double gap = minimumGap(new double[] {-0.96, -0.32, 0.32, 0.96}, ARC_ANGLE_LARGE, ARC_RADIUS_LARGE);
        float widest = PhysicalTableManager.resolveHitboxWidth("叫1分", ACTION_LABEL_SCALE, true);

        assertTrue(gap > widest, "BIDDING 相邻按钮判定框重叠：间距=" + gap + " 框宽=" + widest);
    }

    /**
     * 叫分阶段多出"明牌"后是 5 个按钮，弧线上的间距会被压缩。
     * 这里锁住压缩后仍不重叠，否则玩家想点"叫3分"会误触"明牌"。
     */
    @Test
    void biddingButtonsWithRevealDoNotOverlap() {
        double gap = minimumGap(new double[] {-0.96, -0.48, 0.0, 0.48, 0.96}, ARC_ANGLE_LARGE, ARC_RADIUS_LARGE);
        float widest = Math.max(
            PhysicalTableManager.resolveHitboxWidth("叫1分", ACTION_LABEL_SCALE, true),
            PhysicalTableManager.resolveHitboxWidth("明牌", ACTION_LABEL_SCALE, true)
        );

        assertTrue(gap > widest, "BIDDING 五按钮判定框重叠：间距=" + gap + " 框宽=" + widest);
    }

    @Test
    void doublingButtonsDoNotReceiveArtificialExtraWidth() {
        double gap = minimumGap(new double[] {-0.40, 0.40}, ARC_ANGLE_SMALL, ARC_RADIUS_SMALL);
        float width = PhysicalTableManager.resolveHitboxWidth("加倍", ACTION_LABEL_SCALE, true);

        assertTrue(gap > width, "加倍按钮判定框重叠：间距=" + gap + " 框宽=" + width);
    }

    @Test
    void largerConfiguredTextStillUsesItsExactWidth() {
        double gap = minimumGap(new double[] {-0.64, 0.0, 0.64}, ARC_ANGLE_SMALL, ARC_RADIUS_SMALL);
        float widthAtLargerScale = PhysicalTableManager.resolveHitboxWidth("准备", 0.30f, true);

        assertTrue(gap > widthAtLargerScale, "文字缩放到 0.30 后判定框重叠：间距=" + gap + " 框宽=" + widthAtLargerScale);
    }
}
