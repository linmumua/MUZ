package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 重建牌桌前必须先把锚点周围的区块拉起来。
 *
 * 区块未加载时 Entity.remove() 是空操作、spawn 出来的实体也留不住，
 * rebuildAllTables 直接重建等于把整桌桌椅按钮扔掉。实测一次重启里 12 张桌
 * 有 10 张锚点区块处于未加载状态，表现就是"重启后没人在附近的桌子整套消失"。
 *
 * 桌椅按钮会铺开到锚点周围两格多，锚点贴着区块边界时必然跨区块，
 * 所以这里锁住 3x3 邻域，别缩回单区块。
 */
class AnchorChunkPreloadTest {
    private static int chunkX(long packed) {
        return (int) (packed >> 32);
    }

    private static int chunkZ(long packed) {
        return (int) packed;
    }

    @Test
    void coversTheFullThreeByThreeNeighbourhood() {
        List<Long> keys = PhysicalTableManager.anchorChunkKeys(1000, 1000);

        assertEquals(9, Set.copyOf(keys).size(), "应当覆盖 3x3 共 9 个区块且不重复");
    }

    @Test
    void neighbourhoodIsCentredOnTheAnchorChunk() {
        // 1000 >> 4 == 62
        List<Long> keys = PhysicalTableManager.anchorChunkKeys(1000, 1000);

        Set<String> actual = keys.stream()
            .map(packed -> chunkX(packed) + ":" + chunkZ(packed))
            .collect(Collectors.toSet());

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                String expected = (62 + dx) + ":" + (62 + dz);
                assertTrue(actual.contains(expected), "缺少区块 " + expected);
            }
        }
    }

    @Test
    void anchorOnChunkBorderStillCoversBothSides() {
        // 桌子锚点落在区块边界上时，椅子会跨到相邻区块，两侧都得加载。
        List<Long> keys = PhysicalTableManager.anchorChunkKeys(1008, 1008);

        Set<Integer> xs = keys.stream().map(AnchorChunkPreloadTest::chunkX).collect(Collectors.toSet());

        assertTrue(xs.contains(62), "锚点所在区块要覆盖");
        assertTrue(xs.contains(63), "边界另一侧的区块也要覆盖");
    }

    @Test
    void negativeCoordinatesRoundTripCorrectly() {
        // -1 >> 4 == -1，位运算打包不能把负数区块坐标弄错。
        List<Long> keys = PhysicalTableManager.anchorChunkKeys(-1, -1);

        Set<String> actual = keys.stream()
            .map(packed -> chunkX(packed) + ":" + chunkZ(packed))
            .collect(Collectors.toSet());

        assertTrue(actual.contains("-1:-1"), "负坐标锚点区块应当正确还原");
        assertTrue(actual.contains("-2:-2"), "负坐标邻接区块应当正确还原");
        assertTrue(actual.contains("0:0"), "跨过原点的邻接区块应当正确还原");
    }

    @Test
    void largeNegativeCoordinatesDoNotCollide() {
        List<Long> keys = PhysicalTableManager.anchorChunkKeys(-30000, 30000);

        assertEquals(9, Set.copyOf(keys).size(), "大负坐标下打包也不能撞键");
        Set<Integer> zs = keys.stream().map(AnchorChunkPreloadTest::chunkZ).collect(Collectors.toSet());
        assertTrue(zs.contains(1875), "30000 >> 4 == 1875");
    }
}
