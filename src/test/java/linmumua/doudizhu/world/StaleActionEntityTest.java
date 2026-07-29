package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 升级后多余的按钮实体必须全部收掉，不能漏掉落单的那个。
 *
 * 删掉按钮图标前，每个按钮存 3 个实体（图标 + 文字 + 判定框），所以已放置牌桌的
 * actionEntities 长度是 3n。改成每按钮 2 个实体后，如果沿用"按 2 个一组遍历尾部"的写法，
 * 3 个空位按钮的旧数据（9 个实体）在 required=6 时只会覆盖索引 6、7，索引 8 那个
 * 永远处理不到——玩家升级后会看到一个悬空的旧图标，而且它还占着 hover 映射。
 */
class StaleActionEntityTest {
    @Test
    void oldThreeEntityLayoutLeavesNoOrphan() {
        // 3 个空位按钮的旧数据：3 x 3 = 9 个实体，新逻辑只要 3 x 2 = 6 个。
        int stale = PhysicalTableManager.staleActionEntityCount(9, 6);

        assertEquals(3, stale, "9 个旧实体减去 6 个在用的，应当收掉 3 个");
    }

    @Test
    void oddSurplusIsFullyCovered() {
        // 这是按 2 步跳会漏掉的情形：多出来的数量是奇数。
        assertEquals(1, PhysicalTableManager.staleActionEntityCount(7, 6), "多出 1 个也要收");
        assertEquals(3, PhysicalTableManager.staleActionEntityCount(9, 6), "多出 3 个也要收");
        assertEquals(5, PhysicalTableManager.staleActionEntityCount(11, 6), "多出 5 个也要收");
    }

    @Test
    void exactMatchLeavesNothingToCollect() {
        assertEquals(0, PhysicalTableManager.staleActionEntityCount(6, 6), "刚好够用时不该收任何东西");
    }

    @Test
    void fewerThanRequiredNeverGoesNegative() {
        // 首次生成时列表是空的，不能算出负数去做 subList。
        assertEquals(0, PhysicalTableManager.staleActionEntityCount(0, 6), "空列表不该算出负数");
        assertEquals(0, PhysicalTableManager.staleActionEntityCount(4, 6), "不足时也不该算出负数");
    }

    @Test
    void allSeatsOccupiedCollectsEverything() {
        // 三个座位都坐满时没有空位按钮，required=0，旧数据全部要收掉。
        int stale = PhysicalTableManager.staleActionEntityCount(9, 0);

        assertEquals(9, stale, "没有按钮要显示时应当收掉全部");
    }
}
