package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/** 平面面板拾取的纯几何契约。 */
class DisplayPanelPickGeometryTest {
    @Test
    void 正对面板命中并返回局部坐标() {
        Location center = new Location(null, 0.0, 2.0, 0.0);
        DisplayPanelPickGeometry.Rectangle rectangle =
            new DisplayPanelPickGeometry.Rectangle(
                center, new Vector(0.0, 0.0, 1.0), new Vector(1.0, 0.0, 0.0),
                new Vector(0.0, 1.0, 0.0), 1.0, 0.5);

        DisplayPanelPickGeometry.Hit hit = DisplayPanelPickGeometry.intersect(
            new Location(null, 0.25, 2.1, -3.0), new Vector(0.0, 0.0, 1.0), rectangle, 6.0);

        assertNotNull(hit);
        assertEquals(3.0, hit.distance(), 1.0e-9);
        assertEquals(0.25, hit.localX(), 1.0e-9);
        assertEquals(0.1, hit.localY(), 1.0e-9);
    }

    @Test
    void 平行背向超距和越界均不命中() {
        DisplayPanelPickGeometry.Rectangle rectangle =
            new DisplayPanelPickGeometry.Rectangle(
                new Location(null, 0.0, 0.0, 0.0), new Vector(0.0, 0.0, 1.0),
                new Vector(1.0, 0.0, 0.0), new Vector(0.0, 1.0, 0.0), 1.0, 1.0);

        assertNull(DisplayPanelPickGeometry.intersect(
            new Location(null, 0.0, 0.0, -1.0), new Vector(1.0, 0.0, 0.0), rectangle, 6.0));
        assertNull(DisplayPanelPickGeometry.intersect(
            new Location(null, 0.0, 0.0, 1.0), new Vector(0.0, 0.0, 1.0), rectangle, 6.0));
        assertNull(DisplayPanelPickGeometry.intersect(
            new Location(null, 0.0, 0.0, -1.0), new Vector(0.0, 0.0, 1.0), rectangle, 0.5));
        assertNull(DisplayPanelPickGeometry.intersect(
            new Location(null, 1.1, 0.0, -1.0), new Vector(0.0, 0.0, 1.0), rectangle, 6.0));
    }

    @Test
    void 方块命中不晚于面板时视线被遮挡() {
        DisplayPanelPickGeometry.Hit panelHit = new DisplayPanelPickGeometry.Hit(3.0, 0.0, 0.0);
        assertTrue(DisplayPanelPickGeometry.occluded(panelHit, 2.0));
        assertTrue(DisplayPanelPickGeometry.occluded(panelHit, 3.0));
        assertFalse(DisplayPanelPickGeometry.occluded(panelHit, 3.01));
        assertFalse(DisplayPanelPickGeometry.occluded(panelHit, Double.POSITIVE_INFINITY));
    }

    @Test
    void vertical按yaw生成正交坐标系() {
        DisplayPanelPickGeometry.Rectangle rectangle = DisplayPanelPickGeometry.vertical(
            new Location(null, 0.0, 0.0, 0.0), 2.0, 4.0, 90.0f);
        assertEquals(1.0, rectangle.halfWidth(), 1.0e-9);
        assertEquals(2.0, rectangle.halfHeight(), 1.0e-9);
        assertEquals(1.0, rectangle.normal().length(), 1.0e-9);
        assertEquals(1.0, rectangle.right().length(), 1.0e-9);
        assertEquals(0.0, rectangle.normal().dot(rectangle.right()), 1.0e-9);
    }
}
