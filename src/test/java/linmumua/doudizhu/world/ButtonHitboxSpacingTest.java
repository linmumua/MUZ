package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.world.PhysicalTableManager.ButtonAction;
import org.junit.jupiter.api.Test;

/**
 * 相邻按钮的判定框不能重叠，否则玩家点一个会误触旁边那个。
 *
 * 判定框改成按文字缩放推算后，宽度从固定 0.22 变成 0.53（加入座位）或 0.23（动作按钮）。
 * 加入座位是每个空位一个、彼此隔着整张桌子，不会打架；真正紧的是同一玩家的动作按钮，
 * 它们挤在同一条弧线上。这里按 config 默认几何算出相邻间距，锁住"间距必须大于两个半宽之和"。
 *
 * 实测数据（默认配置）：
 *   LOBBY 三按钮   间距 0.350，框宽 0.230，余量 0.120
 *   BIDDING 四按钮 间距 0.367，框宽 0.230，余量 0.137
 *   DOUBLING 两按钮 间距 0.700，框宽 0.333（含 1.45 倍放大），余量 0.367
 */
class ButtonHitboxSpacingTest {
    private static final float ACTION_LABEL_SCALE = 0.20f;
    private static final double ARC_ANGLE_SMALL = 30.0;
    private static final double ARC_ANGLE_LARGE = 42.0;
    private static final double ARC_RADIUS_SMALL = 0.70;
    private static final double ARC_RADIUS_LARGE = 0.86;

    /** 复刻 actionArcOffset 里的横向位移算法。 */
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
        // 准备 / 开始 / 离开
        double gap = minimumGap(new double[] {-0.64, 0.0, 0.64}, ARC_ANGLE_SMALL, ARC_RADIUS_SMALL);
        float width = PhysicalTableManager.resolveHitboxWidth(ButtonAction.READY, ACTION_LABEL_SCALE);

        assertTrue(gap > width, "LOBBY 相邻按钮判定框重叠：间距=" + gap + " 框宽=" + width);
    }

    @Test
    void biddingButtonsDoNotOverlap() {
        // 不叫 / 叫1分 / 叫2分 / 叫3分，四按钮走大弧
        double gap = minimumGap(new double[] {-0.96, -0.32, 0.32, 0.96}, ARC_ANGLE_LARGE, ARC_RADIUS_LARGE);
        float width = PhysicalTableManager.resolveHitboxWidth(ButtonAction.BID_1, ACTION_LABEL_SCALE);

        assertTrue(gap > width, "BIDDING 相邻按钮判定框重叠：间距=" + gap + " 框宽=" + width);
    }

    @Test
    void doublingButtonsDoNotOverlapDespiteBeingEnlarged() {
        // 加倍只有两个按钮，但判定框带 1.45 倍放大，要单独确认。
        double gap = minimumGap(new double[] {-0.40, 0.40}, ARC_ANGLE_SMALL, ARC_RADIUS_SMALL);
        float width = PhysicalTableManager.resolveHitboxWidth(ButtonAction.DOUBLE_YES, ACTION_LABEL_SCALE);

        assertTrue(gap > width, "加倍按钮判定框重叠：间距=" + gap + " 框宽=" + width);
    }

    @Test
    void thereIsHeadroomToEnlargeLabelsBeforeOverlapping() {
        // 判定框不能手调了，只能通过文字缩放间接调。至少要留出到 0.30 的调节空间，
        // 否则用户把字调大一点按钮就开始互相误触。
        double gap = minimumGap(new double[] {-0.64, 0.0, 0.64}, ARC_ANGLE_SMALL, ARC_RADIUS_SMALL);
        float widthAtLargerScale = PhysicalTableManager.resolveHitboxWidth(ButtonAction.READY, 0.30f);

        assertTrue(
            gap > widthAtLargerScale,
            "文字缩放调到 0.30 就重叠了，调节空间太窄：间距=" + gap + " 框宽=" + widthAtLargerScale
        );
    }
}
