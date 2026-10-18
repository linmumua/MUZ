package linmumua.doudizhu.ui.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import org.junit.jupiter.api.Test;

/**
 * 道具箱 Dialog 纯逻辑的行为锁定。
 *
 * <p>【为什么只用「空栏 + 位图掩码」驱动】本仓库测试运行期没有 Paper 的 RegistryAccess，
 * 真实 {@code new ItemStack(Material.X)} 会抛 ExceptionInInitializerError（已实测），
 * 所以凡是需要「非空槽」的场景都用 {@code int} 掩码直接表达占用位，
 * 这样满槽、稀疏、重复槽位这些边界都能被真实断言，而不是只做源码字符串检查。
 */
class GadgetBoxEditorTest {
    private static final Path EDITOR_SOURCE =
        Path.of("src/main/java/linmumua/doudizhu/ui/dialog/GadgetBoxEditor.java");

    @Test
    void 全掩码恰好覆盖八格且空栏零占用() {
        VirtualGadgetBar empty = VirtualGadgetBar.empty();

        assertEquals(0xFF, GadgetBoxEditor.FULL_MASK, "八格位图必须是 8 个 bit");
        assertEquals(0, GadgetBoxEditor.occupiedMask(empty), "空栏不得有占用位");
        assertEquals(0, GadgetBoxEditor.occupiedCount(0));
        assertEquals(0, GadgetBoxEditor.firstEmpty(0), "空栏第一个空槽是 0");
        assertFalse(GadgetBoxEditor.isFull(0));
        assertEquals(-1, GadgetBoxEditor.normalizeSelected(0, empty), "空栏任何选中态都必须收敛为未选中");
    }

    @Test
    void firstEmpty跳过所有已占用位() {
        assertEquals(0, GadgetBoxEditor.firstEmpty(0b1000_0000));
        assertEquals(3, GadgetBoxEditor.firstEmpty(0b0000_0111));
        assertEquals(3, GadgetBoxEditor.firstEmpty(0b0011_0111), "稀疏占用仍取最小空位");
        assertEquals(7, GadgetBoxEditor.firstEmpty(0x7F));
        assertEquals(-1, GadgetBoxEditor.firstEmpty(0xFF));
    }

    @Test
    void 只有八格全满才算满槽() {
        assertFalse(GadgetBoxEditor.isFull(0xFE), "还差第一格就不算满");
        assertFalse(GadgetBoxEditor.isFull(0x7F), "还差最后一格就不算满");
        assertTrue(GadgetBoxEditor.isFull(0xFF));
        assertTrue(GadgetBoxEditor.isFull(0x1FF), "越界高位不得让满槽判定失真");
        assertEquals(8, GadgetBoxEditor.occupiedCount(0xFF));
    }

    @Test
    void 选中态只保留指向已占用可编辑槽的情况() {
        int mask = 0b0000_0101; // 0 与 2 已占用

        assertEquals(0, GadgetBoxEditor.normalizeSelected(0, mask));
        assertEquals(2, GadgetBoxEditor.normalizeSelected(2, mask));
        assertEquals(-1, GadgetBoxEditor.normalizeSelected(1, mask), "空槽上的选中必须失效");
        assertEquals(-1, GadgetBoxEditor.normalizeSelected(7, mask));
        assertEquals(-1, GadgetBoxEditor.normalizeSelected(8, mask), "语音槽不是可编辑槽");
        assertEquals(-1, GadgetBoxEditor.normalizeSelected(-1, mask));
    }

    @Test
    void 重复道具按真实槽位区分而不是按物品类型合并() {
        int twoOccupied = 0b0000_0011; // 两格可以放同种道具，仍是两个独立槽

        assertEquals(0, GadgetBoxEditor.normalizeSelected(0, twoOccupied));
        assertEquals(1, GadgetBoxEditor.normalizeSelected(1, twoOccupied), "重复道具的第二格必须仍可被选中");
        assertEquals(2, GadgetBoxEditor.firstEmpty(twoOccupied), "新道具追加到第一个空槽而不是合并");
    }

    @Test
    void 移除后的选中态左移紧凑与被删槽一致() {
        assertEquals(-1, GadgetBoxEditor.compactedIndex(1, 1), "删掉选中项本身则清空选中");
        assertEquals(2, GadgetBoxEditor.compactedIndex(3, 1), "高于被删槽的选中项左移一位");
        assertEquals(0, GadgetBoxEditor.compactedIndex(0, 1), "低于被删槽的选中项不动");
        assertEquals(-1, GadgetBoxEditor.compactedIndex(-1, 1));
        assertEquals(2, GadgetBoxEditor.compactedIndex(2, 8), "被删槽越界时选中态保持原样");
        assertEquals(-1, GadgetBoxEditor.compactedIndex(9, 1), "越界选中态一律无效");
    }

    @Test
    void 空槽或越界移除一律被忽略且不改动数据() {
        VirtualGadgetBar empty = VirtualGadgetBar.empty();

        for (int slot : new int[] {-1, 0, 7, 8}) {
            GadgetBoxEditor.Edit edit = GadgetBoxEditor.removeSlot(empty, slot);
            assertEquals(GadgetBoxEditor.Outcome.IGNORED, edit.outcome(), "槽 " + slot + " 应被忽略");
            assertEquals(-1, edit.slot());
            assertTrue(edit.bar().isEmpty(), "被忽略的移除不得改动快照");
        }
    }

    @Test
    void 没有来源物品时复制被拒绝且快照不变() {
        VirtualGadgetBar empty = VirtualGadgetBar.empty();

        GadgetBoxEditor.Edit edit = GadgetBoxEditor.copyInto(empty, null);

        assertEquals(GadgetBoxEditor.Outcome.NO_SOURCE, edit.outcome());
        assertEquals(-1, edit.slot());
        assertTrue(edit.bar().isEmpty());
    }

    @Test
    void 语音槽与可编辑槽边界() {
        assertTrue(GadgetBoxEditor.isVoiceSlot(VirtualGadgetBar.BUBBLE_SLOT));
        assertFalse(GadgetBoxEditor.isVoiceSlot(7));
        assertTrue(GadgetBoxEditor.isEditableSlot(0));
        assertTrue(GadgetBoxEditor.isEditableSlot(7));
        assertFalse(GadgetBoxEditor.isEditableSlot(8));
        assertFalse(GadgetBoxEditor.isEditableSlot(-1));
    }

    @Test
    void 编辑结果必须携带快照() {
        assertThrows(IllegalArgumentException.class,
            () -> new GadgetBoxEditor.Edit(GadgetBoxEditor.Outcome.COPIED, null, 0));
    }

    /**
     * 「复制不消耗背包」这条产品要求只能靠源码契约守住：真正把来源物品写进背包的 API
     * 一旦出现在这里，玩家的真实物品就会被这套虚拟快照吃掉。
     */
    @Test
    void 复制路径只克隆来源且绝不触碰真实背包() throws Exception {
        String source = Files.readString(EDITOR_SOURCE);

        assertTrue(source.contains("source.clone()"), "入库必须复制来源物品");
        assertTrue(source.contains("copy.setAmount(1)"), "虚拟栏只保存单件快照");
        for (String forbidden : List.of(
            "getInventory()", "removeItem", "setItemInMainHand", "setItemInOffHand",
            "getItemInMainHand()", "setAmount(0)", "clear(" )) {
            assertFalse(source.contains(forbidden), "纯逻辑层不得出现背包写入/消耗语义：" + forbidden);
        }
    }

    /** 纯逻辑层不得引入服务端、调度或文件 IO，否则它就再也无法在测试 JVM 里被真实驱动。 */
    @Test
    void 纯逻辑不依赖服务端与文件IO() throws Exception {
        String source = Files.readString(EDITOR_SOURCE);

        for (String forbidden : List.of("Bukkit.", "MuzYamlConfig", "java.nio", "java.util.logging", "Scheduler")) {
            assertFalse(source.contains(forbidden), "纯逻辑层禁止依赖：" + forbidden);
        }
    }
}
