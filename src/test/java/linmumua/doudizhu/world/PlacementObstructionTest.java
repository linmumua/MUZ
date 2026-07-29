package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

class PlacementObstructionTest {
    @Test
    void reasonOnlyResultCarriesNoHighlightTargets() {
        PlacementObstruction obstruction = PlacementObstruction.ofReason("这里暂时还不能放牌桌。");

        assertEquals("这里暂时还不能放牌桌。", obstruction.reason());
        assertTrue(obstruction.blockedBlocks().isEmpty());
    }

    @Test
    void scanAreaCoversConfiguredRadiusAndHeightRange() {
        BoundingBox area = PlacementObstruction.scanArea(10.5, 64.0, -20.5, 0.95, -0.10, 0.95);

        assertEquals(9.55, area.getMinX(), 1.0E-9);
        assertEquals(11.45, area.getMaxX(), 1.0E-9);
        assertEquals(63.90, area.getMinY(), 1.0E-9);
        assertEquals(64.95, area.getMaxY(), 1.0E-9);
        assertEquals(-21.45, area.getMinZ(), 1.0E-9);
        assertEquals(-19.55, area.getMaxZ(), 1.0E-9);
    }

    @Test
    void scanAreaStaysInsideNeighbouringBlockSoFlushSurfacesDoNotBlockPlacement() {
        BoundingBox area = PlacementObstruction.scanArea(10.5, 64.0, 10.5, 0.95, -0.10, 0.95);

        // 桌面检测区域必须停在相邻方块内部，否则贴边的墙面会被误判为阻挡。
        assertTrue(area.getMinX() > 9.0, "检测区域不应触及 x=9 整格边界");
        assertTrue(area.getMaxX() < 12.0, "检测区域不应触及 x=12 整格边界");
        assertTrue(area.getMaxY() < 65.0, "检测区域不应越过上方整格边界");
    }

    @Test
    void chairScanAreaIsNarrowerThanTableScanArea() {
        BoundingBox table = PlacementObstruction.scanArea(0.5, 64.0, 0.5, 0.95, -0.10, 0.95);
        BoundingBox chair = PlacementObstruction.scanArea(0.5, 64.0, 0.5, 0.55, -0.10, 1.05);

        assertTrue(chair.getWidthX() < table.getWidthX(), "椅子水平检测范围应比桌面更窄");
        assertTrue(chair.getHeight() > table.getHeight(), "椅子垂直检测范围应比桌面更高");
    }

    @Test
    void scanRangeStopsAtBlockBelowWhenAreaEndsExactlyOnGridBoundary() {
        // 区域上界正好贴在 y=65 的整格边界时，第 65 格只是共面接触，不应参与阻挡判定。
        assertEquals(64, PlacementObstruction.lastBlockIndex(65.0));
        assertEquals(64, PlacementObstruction.firstBlockIndex(64.0));
    }

    @Test
    void scanRangeStillCoversBlockOnceAreaReachesIntoIt() {
        // 只要越过边界一点，就必须把该格纳入扫描，否则真正重叠的方块会漏判。
        assertEquals(65, PlacementObstruction.lastBlockIndex(65.5));
        assertEquals(65, PlacementObstruction.lastBlockIndex(66.0 - 1.0E-6));
    }

    @Test
    void scanRangeHandlesNegativeCoordinates() {
        assertEquals(-21, PlacementObstruction.firstBlockIndex(-20.5));
        assertEquals(-21, PlacementObstruction.lastBlockIndex(-20.0));
    }

    @Test
    void blockLocalAreaIsShiftedByBlockOriginSoVoxelShapeChecksLineUp() {
        BoundingBox area = PlacementObstruction.scanArea(10.5, 64.0, 10.5, 0.95, -0.10, 0.95);
        BoundingBox local = PlacementObstruction.toBlockLocalArea(area, 10, 64, 10);

        // VoxelShape 用方块局部坐标 0~1，平移方向写反会让判定命中完全错误的方块。
        // 世界坐标 x 为 9.55~11.45，相对 x=10 的方块原点应落在 -0.45~1.45。
        assertEquals(-0.45, local.getMinX(), 1.0E-9);
        assertEquals(1.45, local.getMaxX(), 1.0E-9);
        assertEquals(-0.10, local.getMinY(), 1.0E-9);
        assertEquals(0.95, local.getMaxY(), 1.0E-9);
        assertEquals(area.getWidthX(), local.getWidthX(), 1.0E-9);
    }

    @Test
    void blockLocalAreaOverlapsFullCubeShapeOfTheBlockItSitsIn() {
        BoundingBox area = PlacementObstruction.scanArea(10.5, 64.5, 10.5, 0.30, -0.20, 0.20);
        BoundingBox fullCube = new BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

        // 落在方块内部的区域必须与该方块的整块碰撞箱相交，否则实心方块会漏判。
        assertTrue(fullCube.overlaps(PlacementObstruction.toBlockLocalArea(area, 10, 64, 10)));
        // 同一区域相对上方方块则不应相交。
        assertFalse(fullCube.overlaps(PlacementObstruction.toBlockLocalArea(area, 10, 66, 10)));
    }

    @Test
    void hazardBlocksWithoutCollisionShapeStillBlockPlacement() {
        // 实测确认这些方块 isPassable() 为 true 且没有碰撞箱，
        // 若只看碰撞箱就会允许把牌桌放进岩浆、细雪、蜘蛛网和火里。
        assertTrue(PlacementObstruction.isHazardMaterial(Material.LAVA));
        assertTrue(PlacementObstruction.isHazardMaterial(Material.POWDER_SNOW));
        assertTrue(PlacementObstruction.isHazardMaterial(Material.COBWEB));
        assertTrue(PlacementObstruction.isHazardMaterial(Material.FIRE));
        assertTrue(PlacementObstruction.isHazardMaterial(Material.SOUL_FIRE));
    }

    @Test
    void walkablePassableBlocksAreNotTreatedAsHazards() {
        // 这些是玩家最常踩在脚下的方块，必须保持可以放桌，否则又会回到放不下桌的老问题。
        assertFalse(PlacementObstruction.isHazardMaterial(Material.WATER));
        assertFalse(PlacementObstruction.isHazardMaterial(Material.SHORT_GRASS));
        assertFalse(PlacementObstruction.isHazardMaterial(Material.POPPY));
        assertFalse(PlacementObstruction.isHazardMaterial(Material.TORCH));
        assertFalse(PlacementObstruction.isHazardMaterial(Material.OAK_SIGN));
        assertFalse(PlacementObstruction.isHazardMaterial(Material.SNOW));
        assertFalse(PlacementObstruction.isHazardMaterial(Material.AIR));
    }

    @Test
    void blockedBlocksListRejectsExternalMutation() {
        PlacementObstruction obstruction = PlacementObstruction.ofReason("这里暂时还不能放牌桌。");

        assertThrows(
            UnsupportedOperationException.class,
            () -> obstruction.blockedBlocks().add(null)
        );
    }
}
