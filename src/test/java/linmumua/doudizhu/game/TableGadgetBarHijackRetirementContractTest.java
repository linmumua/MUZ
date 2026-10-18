package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 「桌内道具栏不再劫持原版快捷栏，选择改由 MuMenu 面板命令接通」的源码契约。
 *
 * <p>背景：旧 {@link TableGadgetBarHudService} 监听 {@code PlayerItemHeldEvent}（滚轮/数字键）并取消原版
 * 快捷栏切换来选槽，而 MUZ 事件优先级是 LOWEST、MuMenu 是 HIGHEST，取消会和面板输入抢事件。
 * 本轮退役这条劫持，选择改由 {@code /muz gadget select <1..8>} → {@link GadgetPanelService} →
 * {@code select(UUID,int)} 走真实槽。
 *
 * <p>【为什么用源码契约】快捷栏事件要真实 Bukkit 事件与玩家；这里钉住的是「劫持确实被移除」「新接缝
 * 确实存在」「既有只读符号未被顺手删掉」，与行为测试互补。
 */
class TableGadgetBarHijackRetirementContractTest {
    private static final Path BAR_SERVICE =
        Path.of("src/main/java/linmumua/doudizhu/game/TableGadgetBarHudService.java");

    /**
     * 快捷栏劫持必须彻底移除（监听、import、处理函数都不允许再接线）。
     *
     * <p>【为什么不直接断言不含 "PlayerItemHeldEvent" 字样】退役说明里会以历史注记提到旧机制（项目要求
     * 保留「为什么这么改」的注释），所以这里钉的是**代码引用**：import 行、处理函数声明、以及只有那处
     * 事件才会用的 {@code event.getNewSlot()}。
     *
     * <p>失败条件：任一残留都会让 MUZ 重新和 MuMenu 面板抢原版快捷栏事件。
     */
    @Test
    void 不再接线原版快捷栏劫持() throws IOException {
        String source = Files.readString(BAR_SERVICE);
        assertFalse(source.contains("import org.bukkit.event.player.PlayerItemHeldEvent"),
            "不得再 import PlayerItemHeldEvent：这正是要与 MuMenu 面板争抢的那条劫持");
        assertFalse(source.contains("public void onHeldChange("),
            "旧 onHeldChange 处理函数必须退役，避免被重新接线");
        assertFalse(source.contains("PlayerItemHeldEvent event"),
            "不得再有 PlayerItemHeldEvent 形参，劫持必须彻底移除");
        assertFalse(source.contains("event.getNewSlot()"),
            "getNewSlot() 只属于被退役的快捷栏劫持，不得残留");
    }

    /**
     * 新增的窄接缝必须存在：命令/面板与 Dialog 都靠它们接通。
     *
     * <p>失败条件：签名被改名或删掉，会让 {@link GadgetPanelService} 与 Dialog 接线编译不过或运行时失效。
     */
    @Test
    void 公开接缝存在且签名稳定() throws IOException {
        String source = Files.readString(BAR_SERVICE);
        assertTrue(source.contains("public boolean openVoicePanel(Player player)"),
            "缺 openVoicePanel(Player)：命令与 Dialog 的语音入口无法在不选中语音格时打开面板");
        assertTrue(source.contains("public VirtualGadgetBar visibleBar(UUID playerId)"),
            "缺 visibleBar(UUID)：Dialog 编辑屏幕没有只读八槽快照入口");
        assertTrue(source.contains("public GadgetSlotSnapshot gadgetSlotSnapshot(UUID playerId)"),
            "缺 gadgetSlotSnapshot(UUID)：PAPI 动态标签没有不可变快照来源");
        assertTrue(source.contains("public void select(UUID playerId, int slot)"),
            "缺 select(UUID,int)：命令与 Dialog 共用的选择接缝");
    }

    /**
     * 既有只读符号必须保留（HUD 退役只移除第四行的实际接线，不删这些）。
     *
     * <p>失败条件：顺手删掉这些会让契约测试与其他调用方失效——它们是「保留八槽数据与虚拟选择」的载体。
     */
    @Test
    void 保留既有只读快照与选择映射符号() throws IOException {
        String source = Files.readString(BAR_SERVICE);
        assertTrue(source.contains("public HudBarSnapshot hudBarSnapshot(UUID playerId)"));
        assertTrue(source.contains("static boolean barRowVisible("));
        assertTrue(source.contains("static int slotForHotbarIndex("));
        assertTrue(source.contains("static int normalizeSelectedSlot("));
        assertTrue(source.contains("static List<Integer> displayedSlots(VirtualGadgetBar bar)"));
        assertTrue(source.contains("record GadgetSlotSnapshot("));
    }

    /**
     * 选择必须重建 PAPI 快照，否则面板的选中态/空槽动态标签不会跟着更新。
     *
     * <p>失败条件：{@code select} 不再调用 {@code publishSnapshot(...)}。
     */
    @Test
    void 选择后重建不可变快照() throws IOException {
        String body = methodBody(BAR_SERVICE, "public void select(UUID playerId, int slot)");
        assertTrue(body.contains("publishSnapshot(playerId, slot);"),
            "select 必须重建 PAPI 快照，否则选中态标签会停在旧值");
    }

    /**
     * 不可变快照必须是纯数据（String/Boolean），才允许 PAPI 在任意线程读。
     *
     * <p>失败条件：快照里塞进 ItemStack/Location 等 Bukkit 对象，跨线程读取会违反 lane 约束。
     */
    @Test
    void 快照是纯数据类型() throws IOException {
        String source = Files.readString(BAR_SERVICE);
        int record = source.indexOf("record GadgetSlotSnapshot(");
        assertTrue(record > 0, "找不到 GadgetSlotSnapshot 记录");
        String header = source.substring(record, source.indexOf(')', record) + 1);
        assertTrue(header.contains("List<String>") && header.contains("List<Boolean>"),
            "快照只能是 String/Boolean 纯数据：header=" + header);
        assertFalse(header.contains("ItemStack") || header.contains("Location"),
            "快照不得包含 Bukkit 世界对象");
    }

    private static String methodBody(Path file, String signature) throws IOException {
        String source = Files.readString(file);
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
