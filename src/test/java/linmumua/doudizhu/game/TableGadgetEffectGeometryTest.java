package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 道具动画的几何与时序契约。
 *
 * <p>测试直接执行计算辅助类，不靠检查中文日志或实现源码字符串；这样可以锁住
 * 飞行端点、命中越界、水幕时限和并发上限的实际数值语义。
 */
class TableGadgetEffectGeometryTest {
    @Test
    void 抛物线十tick首尾落在端点且中段抬升() {
        TableGadgetEffectGeometry.Point start = new TableGadgetEffectGeometry.Point(0.0, 1.6, 0.0);
        TableGadgetEffectGeometry.Point end = new TableGadgetEffectGeometry.Point(4.0, 1.0, 0.0);

        TableGadgetEffectGeometry.Point first = TableGadgetEffectGeometry.projectilePosition(start, end, 0, 10, 0.65);
        TableGadgetEffectGeometry.Point middle = TableGadgetEffectGeometry.projectilePosition(start, end, 5, 10, 0.65);
        TableGadgetEffectGeometry.Point last = TableGadgetEffectGeometry.projectilePosition(start, end, 10, 10, 0.65);

        assertEquals(start, first);
        assertEquals(end, last);
        assertTrue(middle.y() > 1.3, "抛物线中段必须高于线性插值");
    }

    @Test
    void 命中范围外移动目标判定为落空() {
        TableGadgetEffectGeometry.Point impact = new TableGadgetEffectGeometry.Point(4.0, 1.0, 0.0);
        TableGadgetEffectGeometry.Point movedAway = new TableGadgetEffectGeometry.Point(5.2, 1.0, 0.0);

        assertFalse(TableGadgetEffectGeometry.withinRadius(impact, movedAway, 0.90));
        assertTrue(TableGadgetEffectGeometry.withinRadius(impact, movedAway, 1.25));
    }

    @Test
    void 水幕十六tick从头向脚推进且顶部保持锚定() {
        assertEquals(0.0, TableGadgetEffectGeometry.waterHeight(1.8, 0, 16), 1.0E-9);
        assertEquals(0.9, TableGadgetEffectGeometry.waterHeight(1.8, 8, 16), 1.0E-9);
        assertEquals(1.8, TableGadgetEffectGeometry.waterHeight(1.8, 16, 16), 1.0E-9);
        assertEquals(1.8, TableGadgetEffectGeometry.waterHeight(1.8, 99, 16), 1.0E-9);

        assertEquals(2.0, TableGadgetEffectGeometry.waterDisplayCenterY(2.0, 1.8, 0, 16), 1.0E-9);
        assertEquals(1.55, TableGadgetEffectGeometry.waterDisplayCenterY(2.0, 1.8, 8, 16), 1.0E-9);
        assertEquals(1.1, TableGadgetEffectGeometry.waterDisplayCenterY(2.0, 1.8, 16, 16), 1.0E-9);
        assertEquals(0.2, TableGadgetEffectGeometry.waterBottomY(2.0, 1.8, 16, 16), 1.0E-9);
        assertEquals(2.0, TableGadgetEffectGeometry.waterBottomY(2.0, 1.8, 0, 16), 1.0E-9);
    }

    @Test
    void 时限与并发上限不会产生越界值() {
        assertFalse(TableGadgetEffectGeometry.expired(9, 10));
        assertTrue(TableGadgetEffectGeometry.expired(10, 10));
        assertTrue(TableGadgetEffectGeometry.expired(11, 10));
        assertEquals(1, TableGadgetEffectGeometry.entityCost(false));
        assertEquals(2, TableGadgetEffectGeometry.entityCost(true));
        assertEquals(0, TableGadgetEffectGeometry.clampConcurrentLimit(0, 128));
        assertEquals(32, TableGadgetEffectGeometry.clampConcurrentLimit(32, 128));
        assertEquals(128, TableGadgetEffectGeometry.clampConcurrentLimit(999, 128));
        assertEquals(0, TableGadgetEffectGeometry.clampConcurrentLimit(32, 0));
    }
}
