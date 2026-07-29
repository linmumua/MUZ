package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 残留实体清理必须覆盖按钮位置，否则升级前的旧图标会永久留在世界里。
 *
 * purgeResidualWorldArtifacts 原先只扫桌面和三把椅子，半径 0.95。
 * 按钮离桌 2.1 格、椅子离桌 3.1 格，两者相差正好 1.0 格——比半径大 0.05，
 * 刚好够不到。图标还在的年代这没问题，因为 actionEntities 会追踪并回收它们；
 * 图标删掉后那批旧实体就失去了追踪者，成为永久残留。
 */
class ResidualPurgeCoverageTest {
    private static final double CHAIR_DISTANCE = 3.10;
    private static final double BUTTON_DISTANCE = 2.10;
    private static final double PURGE_RADIUS_XZ = 0.95;

    @Test
    void chairHotspotAloneCannotReachButtons() {
        // 这条说明为什么必须把按钮位置单独加进热点，而不是指望椅子那圈扫到。
        double gap = Math.abs(CHAIR_DISTANCE - BUTTON_DISTANCE);

        assertTrue(
            gap > PURGE_RADIUS_XZ,
            "按钮离椅子 " + gap + " 格，清理半径 " + PURGE_RADIUS_XZ + " 格，本该够不到才对"
        );
    }

    @Test
    void tableHotspotAloneCannotReachButtons() {
        assertTrue(
            BUTTON_DISTANCE > PURGE_RADIUS_XZ,
            "按钮离桌心 " + BUTTON_DISTANCE + " 格，桌面那圈也扫不到"
        );
    }

    @Test
    void purgeRadiusCoversWidestButtonArc() {
        // 按钮沿弧线展开，热点放在基点上，要确认最外侧那个也在半径内。
        // 最宽的是 BIDDING 四按钮：大弧 42 度、半径 0.86。
        double widest = lateralOf(0.96, 0.96, 42.0, 0.86);

        assertTrue(
            widest <= PURGE_RADIUS_XZ,
            "最外侧按钮偏移 " + widest + " 格，超出清理半径 " + PURGE_RADIUS_XZ
        );
    }

    @Test
    void purgeRadiusCoversLobbyArc() {
        // LOBBY 三按钮走小弧，跨度更小，一并确认。
        double widest = lateralOf(0.64, 0.64, 30.0, 0.70);

        assertTrue(widest <= PURGE_RADIUS_XZ, "LOBBY 最外侧按钮应在清理半径内");
    }

    private static double lateralOf(double offset, double maxOffset, double arcAngleDegrees, double radius) {
        double normalized = maxOffset <= 0.0001 ? 0.0 : offset / maxOffset;
        return Math.abs(Math.sin(normalized * Math.toRadians(arcAngleDegrees)) * radius);
    }
}
