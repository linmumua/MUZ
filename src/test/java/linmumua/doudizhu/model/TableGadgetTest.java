package linmumua.doudizhu.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 固定三道具顺序与循环边界的行为测试。 */
class TableGadgetTest {
    @Test
    void approvedOrderIsEggWaterTomato() {
        assertEquals(TableGadget.EGG, TableGadget.fromIndex(0));
        assertEquals(TableGadget.WATER, TableGadget.fromIndex(1));
        assertEquals(TableGadget.TOMATO, TableGadget.fromIndex(2));
    }

    @Test
    void selectionWrapsInBothDirections() {
        assertEquals(0, TableGadget.wrapIndex(3));
        assertEquals(2, TableGadget.wrapIndex(-1));
        assertEquals(TableGadget.TOMATO, TableGadget.fromIndex(-1));
        assertEquals(TableGadget.EGG, TableGadget.fromIndex(6));
    }
}
