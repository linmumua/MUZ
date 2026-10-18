package linmumua.doudizhu.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import linmumua.doudizhu.game.TableGadgetBarHudService.GadgetSlotSnapshot;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import org.junit.jupiter.api.Test;

/**
 * 道具栏动态标签与 PAPI 快照的纯函数测试。
 *
 * <p>【为什么只测纯函数】PAPI 的 {@code onRequest} 需要真实的 {@code OfflinePlayer} 与插件实例；
 * 但真正需要守住的约定是「标签名解析」「空槽判定」「快照不可变」这三条纯逻辑——它们决定面板按钮
 * 显示什么。快照的构建（player lane 上把 ItemStack 拍成字符串）由 {@code TableGadgetBarHudService}
 * 侧重绘路径保证，见其 {@code publishSnapshot} 的注释。
 */
class MuzGadgetPlaceholderTest {
    /**
     * 标签名解析：{@code slot_/empty_/selected_<n>} 一律用户 1-based，转成内部 0-based；非法返回 -1。
     *
     * <p>失败条件：把 1-based 当 0-based（面板「槽 1」会显示槽 2）、或对非数字后缀抛异常（面板报未知占位符）。
     */
    @Test
    void 槽位后缀按用户一基解析() {
        assertEquals(0, MuzPlaceholderExpansion.gadgetSlotIndex("slot_1"));
        assertEquals(7, MuzPlaceholderExpansion.gadgetSlotIndex("slot_8"));
        assertEquals(7, MuzPlaceholderExpansion.gadgetSlotIndex("empty_8"));
        assertEquals(0, MuzPlaceholderExpansion.gadgetSlotIndex("selected_1"));
        assertEquals(-1, MuzPlaceholderExpansion.gadgetSlotIndex("slot_0"), "0 越界（用户从 1 起）");
        assertEquals(-1, MuzPlaceholderExpansion.gadgetSlotIndex("slot_x"), "非数字后缀不能崩");
        assertEquals(-1, MuzPlaceholderExpansion.gadgetSlotIndex("nope"), "未知键返回不可解析");
    }

    /**
     * 空槽判定读的是快照里的 empty 列表；越界按「空」处理（防御，不应发生）。
     *
     * <p>失败条件：索引错位（读成相邻槽）或越界抛异常。
     */
    @Test
    void 空槽判定读快照且越界安全() {
        GadgetSlotSnapshot snapshot = new GadgetSlotSnapshot(
            List.of("EGG", "", "TOMATO"), List.of(false, true, false), 0);

        assertFalse(MuzPlaceholderExpansion.gadgetSlotEmpty(snapshot, 0));
        assertTrue(MuzPlaceholderExpansion.gadgetSlotEmpty(snapshot, 1), "第 2 格是空槽");
        assertFalse(MuzPlaceholderExpansion.gadgetSlotEmpty(snapshot, 2));
        assertTrue(MuzPlaceholderExpansion.gadgetSlotEmpty(snapshot, 99), "越界按空处理，不能抛");
    }

    /**
     * 快照必须不可变（PAPI 可能被别的插件在任意线程调用，只允许无副作用读）。
     *
     * <p>失败条件：构造期不做防御性拷贝，导致调用方能反向改写已发布的快照。
     */
    @Test
    void 快照不可变且防御性拷贝() {
        List<String> names = new ArrayList<>(List.of("EGG", "", "TOMATO"));
        List<Boolean> empty = new ArrayList<>(List.of(false, true, false));
        GadgetSlotSnapshot snapshot = new GadgetSlotSnapshot(names, empty, VirtualGadgetBar.BUBBLE_SLOT);

        // 改原列表不得影响快照。
        names.set(0, "MUTATED");
        empty.set(0, true);
        assertEquals("EGG", snapshot.slotNames().get(0));
        assertFalse(snapshot.empty().get(0));

        // 快照自身不可变。
        assertThrows(UnsupportedOperationException.class, () -> snapshot.slotNames().add("X"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.empty().add(true));
    }

    /**
     * 未发布快照时 {@code selected} 表示法：语音格必须回 {@code voice}，道具格回用户 1-based。
     *
     * <p>这里直接验算记录里的映射约定（{@code BUBBLE_SLOT} 语义），与 PAPI 分支同一常量来源。
     */
    @Test
    void 选中态语音格用voice表示() {
        assertEquals(8, VirtualGadgetBar.BUBBLE_SLOT, "语音格常量语义：8 表示第九格");

        GadgetSlotSnapshot voice = new GadgetSlotSnapshot(
            List.of("", "", "", "", "", "", "", ""), List.of(true, true, true, true, true, true, true, true),
            VirtualGadgetBar.BUBBLE_SLOT);
        int selected = voice.selectedSlot();
        assertEquals("voice", selected == VirtualGadgetBar.BUBBLE_SLOT ? "voice" : Integer.toString(selected + 1));

        GadgetSlotSnapshot first = new GadgetSlotSnapshot(
            List.of("EGG", "", "", "", "", "", "", ""), List.of(false, true, true, true, true, true, true, true), 0);
        assertEquals(1, first.selectedSlot() + 1, "槽 0 对应用户索引 1");
    }
}
