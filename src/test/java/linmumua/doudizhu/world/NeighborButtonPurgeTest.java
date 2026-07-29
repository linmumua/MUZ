package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 清场按坐标扫，不能把邻桌正在用的按钮删掉。
 *
 * 为了回收升级前遗留的旧图标，清场热点加进了按钮基点（半径 0.95）。
 * 但按钮离桌只有 2.01 格，两桌面对面摆放时，A 的前座基点会正对 B 的前座基点：
 * 两基点距离 = 桌间距 - 4.02。桌间距 5 格时基点只隔 0.98 格，落在清场半径内，
 * 重建 A 就会顺手删掉 B 的按钮——而 5 格间距是完全合理的摆法（椅子离桌就有 3.1 格）。
 * 代码里靠 isTrackedActionEntity 豁免仍被追踪的实体来解决，这里锁住几何前提。
 */
class NeighborButtonPurgeTest {
    private static final double BUTTON_DISTANCE = 2.010;
    private static final double PURGE_RADIUS_XZ = 0.95;
    private static final double WIDEST_ARC_OFFSET = 0.575;

    /** 面对面摆放时两桌前座按钮基点的距离。 */
    private static double facingBaseGap(double tableGap) {
        return Math.abs(tableGap - 2 * BUTTON_DISTANCE);
    }

    @Test
    void fiveBlockGapPutsNeighborButtonsInsidePurgeRadius() {
        // 这条证明豁免逻辑不是多余的防御，而是必需的。
        // 基点本身相距 0.98 格，略超半径；但按钮沿弧线展开，最外侧那个会伸进来。
        double gap = facingBaseGap(5.0);

        assertTrue(
            gap <= PURGE_RADIUS_XZ + WIDEST_ARC_OFFSET,
            "桌间距 5 格时邻桌按钮相距 " + gap + " 格，应当落在清场范围内"
        );
    }

    @Test
    void fourBlockGapNearlyOverlapsNeighborButtons() {
        double gap = facingBaseGap(4.0);

        assertTrue(gap < 0.1, "桌间距 4 格时两桌按钮几乎重合，实测相距 " + gap);
    }

    @Test
    void dangerBandCoversPlausibleSpacings() {
        // 危险区间上界 = 2 * 按钮距离 + 半径 + 弧线跨度。
        double upperBound = 2 * BUTTON_DISTANCE + PURGE_RADIUS_XZ + WIDEST_ARC_OFFSET;

        assertTrue(upperBound > 5.0, "危险区间应当覆盖 5 格这种常见摆法，实测上界 " + upperBound);
    }

    @Test
    void neighborHandCardsAlsoFallInsideButtonHotspot() {
        // 豁免不能只覆盖按钮。手牌离桌 1.62 格、按钮 2.01 格，只差 0.39 格，
        // 面对面摆放时 A 的按钮热点会扫到 B 的手牌判定框。
        double handDistance = 1.62;
        double gapAtFourAndHalf = Math.abs(4.5 - BUTTON_DISTANCE - handDistance);

        assertTrue(
            gapAtFourAndHalf <= PURGE_RADIUS_XZ,
            "桌间距 4.5 格时 A 的按钮热点距 B 的手牌 " + gapAtFourAndHalf + " 格，应当落在清场半径内"
        );
    }

    @Test
    void wideSpacingIsOutsideDangerBand() {
        // 6 格以上就安全了，说明这不是无解问题，只是需要豁免判断。
        double gap = facingBaseGap(6.0);

        assertTrue(
            gap > PURGE_RADIUS_XZ + WIDEST_ARC_OFFSET,
            "桌间距 6 格时邻桌基点应当在清场范围外，实测相距 " + gap
        );
    }
}
