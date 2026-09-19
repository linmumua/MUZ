package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 固定 Hotbar 坐标必须独立于正文宽度保持整数且总前进量恒定。 */
class HotbarActionBarLayoutTest {
    @Test
    void 偶数正文宽度使用固定客户端居中坐标() {
        HotbarActionBarLayout.Layout layout = HotbarActionBarLayout.calculate(69, 100);

        assertEquals(69, layout.hotbarWidth());
        assertEquals(-16, layout.messageStart());
        assertEquals(286, layout.iconLeft(640));
        assertEquals(270, layout.messageLeft(640));
        assertEquals(69, layout.totalWidth());
    }

    @Test
    void 奇数正文宽度仍使用floor且不改变固定宽度() {
        HotbarActionBarLayout.Layout layout = HotbarActionBarLayout.calculate(69, 101);

        assertEquals(-16, layout.messageStart());
        assertEquals(286, layout.iconLeft(640));
        assertEquals(270, layout.messageLeft(640));
        assertEquals(69, layout.totalWidth());
    }

    @Test
    void 资源档位advance变化时布局仍保持自身固定总宽() {
        HotbarActionBarLayout.Layout layout = HotbarActionBarLayout.calculate(73, 100);

        assertEquals(73, layout.hotbarWidth());
        assertEquals(73, layout.totalWidth());
        assertEquals(284, layout.iconLeft(640));
    }

    @Test
    void 负宽度拒绝而非静默取整() {
        assertThrows(IllegalArgumentException.class, () -> HotbarActionBarLayout.calculate(-1, 100));
        assertThrows(IllegalArgumentException.class, () -> HotbarActionBarLayout.calculate(69, -1));
    }
}
