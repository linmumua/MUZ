package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import linmumua.doudizhu.assets.PackAssets;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/** 牌桌额外道具栏与语音入口的源码契约。 */
class TableGadgetBarHudServiceTest {
    private static final Path HUD = Path.of(
        "src/main/java/linmumua/doudizhu/game/TableGadgetBarHudService.java");
    private static final Path PLUGIN = Path.of(
        "src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");
    private static final Path TABLE = Path.of(
        "src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path SPEECH = Path.of(
        "src/main/java/linmumua/doudizhu/game/TableSpeechPanelService.java");
    private static final Path CE_LISTENER = Path.of(
        "src/main/java/linmumua/doudizhu/listener/CraftEngineProtectionListener.java");

    @Test
    void 对局额外栏复用八格数据并保留第九格语音入口() throws IOException {
        String source = Files.readString(HUD);
        assertTrue(source.contains("VirtualGadgetBar.SLOT_COUNT + 1"));
        assertTrue(source.contains("store.loadRaw(playerId)"));
        assertTrue(source.contains("store.decodeRaw(raw)"));
        assertTrue(source.contains("VirtualGadgetBar.BUBBLE_SLOT"));
        assertTrue(source.contains("Consumer<Player> voiceOpener"));
        assertTrue(source.contains("actionBarOverlay.outputDispatcher()"));
        assertTrue(source.contains("output.runPlayer(playerId"));
        assertTrue(source.contains("refreshResultAllowed(stopped, state, generation)"));
        assertFalse(source.contains("player.sendActionBar"), "生产额外栏不得绕过共享玩家输出门面");
        assertTrue(source.contains("PlayerItemHeldEvent"));
        assertTrue(source.contains("event.setCancelled(true)"));
        assertTrue(source.contains("PackAssets.gadgetBarBaseGlyphText"), "额外栏必须发送 MUZ 九格底图字形");
        assertTrue(source.contains("PackAssets.gadgetBarIconGlyphText"), "道具槽必须叠加客户端真实图标字形");
        assertTrue(source.contains("PackAssets.gadgetBarSelectGlyphText"), "选中槽必须叠加独立选框字形");
        assertTrue(source.contains("PackAssets.gadgetBarKind"), "道具槽必须按 ItemStack 选择客户端真实图标");
        assertTrue(source.contains("PackAssets.GADGET_BAR_SPEECH"), "第九槽必须使用固定语音图标");
        assertTrue(source.contains("offsetService.offset(-PackAssets.GADGET_BAR_CELL_ADVANCE)"),
            "图标和选框必须回退到同一槽位，而不能改变整条栏的净宽度");
        assertFalse(source.contains("Component.text(\"道具 \")"), "额外栏不得继续输出文字标题");
        assertFalse(source.contains("itemLabel("), "额外栏不得继续使用文字道具标签");
        assertFalse(source.contains("openInventory"), "额外栏不得打开 Inventory GUI");
    }

    /**
     * 九格栏已并入出牌 HUD（BossBar 第四行），不再走 ActionBar。
     *
     * <p>用户要求：九格栏与对局状态提示抢同一个 ActionBar 槽位来回覆盖闪烁，必须独立出来并入出牌 HUD。
     * 所以本服务只保留数据源职责，暴露 {@code hudBarGlyphText} 给 TrickHudService，并【彻底】不再合成/发送
     * ActionBar（旧机制下的 {@code compose}/{@code HotbarActionBarLayout} 固定宽度算式与聊天兜底都已删除）。
     */
    @Test
    void 九格栏并入出牌HUD后不再占用ActionBar() throws IOException {
        String source = Files.readString(HUD);
        assertTrue(source.contains("public String hudBarGlyphText(UUID playerId)"),
            "九格栏必须暴露只读字形快照供出牌 HUD 拼第四行");
        assertTrue(source.contains("PackAssets.gadgetBarRowAdvance()") || source.contains("gadgetBarRowAdvance"),
            "九格栏宽度必须与 PackAssets/TrickHudView 同源");
        // 旧机制断言改为【不得再出现】：这些只为 ActionBar 合成服务。
        assertFalse(source.contains("output.sendActionBar(playerId"),
            "九格栏不得再通过 ActionBar 发送（会与状态提示互相覆盖闪烁）");
        assertFalse(source.contains("HotbarActionBarLayout"), "固定宽度 ActionBar 布局已随并入 BossBar 退役");
        assertFalse(source.contains("chatFallback"), "聊天兜底只为 ActionBar 正文服务，已随机制删除");
        assertFalse(source.contains("MINI.deserialize"), "本服务不再自行拼 MiniMessage，改由 View 消费字形片段");
    }

    /**
     * 用户要求：开局后九格栏位置固定。并入 BossBar 后栏不再自己居中，位置由字形自带的固定下移档
     * {@link PackAssets#GADGET_BAR_ROW_DOWN_OFFSET} 决定；本测试钉住两侧常量同源，防止改一处漏一处。
     */
    @Test
    void 九格栏竖直位置由固定下移档决定且两侧同源() throws IOException {
        String build = Files.readString(Path.of("build.gradle.kts"));
        assertTrue(build.contains("val gadgetBarRowDownOffset ="),
            "构建期必须显式声明九格栏下移档，不能让它散落在 ascent 算式里");
        assertTrue(build.contains("ascent: ${gadgetBarCellHeight - gadgetBarRowDownOffset}"),
            "九格栏 provider 的 ascent 必须是格高减去固定下移档");
        // 断言由「下移档大于记牌器偏移」升级为按字形盒边比较：旧断言只比偏移量，
        // 漏掉了 152 时九格栏顶（基线下 130）压进记牌器 frame 底（基线下 137）7 像素的重叠。
        // 盒模型：字形占 [ascent-height, ascent]；记牌器 frame ascent = COUNTER_FRAME_ASCENT - offset。
        int defaultCounterOffsetDown = 122;
        int counterBottomBelowBaseline = defaultCounterOffsetDown - PackAssets.COUNTER_FRAME_ASCENT
            + PackAssets.COUNTER_FRAME_HEIGHT;
        int gadgetTopBelowBaseline = PackAssets.GADGET_BAR_ROW_DOWN_OFFSET - PackAssets.GADGET_BAR_CELL_HEIGHT;
        assertTrue(gadgetTopBelowBaseline >= counterBottomBelowBaseline,
            "九格栏顶(" + gadgetTopBelowBaseline + ")必须不高于默认记牌器底(" + counterBottomBelowBaseline
                + ")，否则第四行会压在记牌器上");
        // 九格栏宽度必须与 TrickHudView 的容器宽算式一致。
        assertEquals(9 * PackAssets.GADGET_BAR_CELL_ADVANCE, PackAssets.gadgetBarRowAdvance());
    }

    @Test
    void 牌桌按钮不再打开九格InventoryGUI() throws IOException {
        String plugin = Files.readString(PLUGIN);
        String table = Files.readString(TABLE);
        assertTrue(plugin.contains("getTableGadgetBarHudService()"));
        assertFalse(plugin.contains("registerEvents(tableGadgetGuiService"),
            "退役九格 GUI 不得注册为运行期监听器");
        assertFalse(table.contains("getTableGadgetGuiService().open(player)"),
            "GADGET 按钮只能刷新屏幕额外栏");
    }

    @Test
    void Bukkit家具路由也复用第九格语音入口() throws IOException {
        String source = Files.readString(CE_LISTENER);
        assertTrue(source.contains("getTableGadgetBarHudService().tryOpenVoice(player)"));
        assertTrue(source.contains("getTableSpeechPanelService().tryHandleRightClick(player)"));
    }

    @Test
    void 语音面板文字按行高等比缩放而非被宽度横向拉伸() throws IOException {
        String source = Files.readString(SPEECH);
        assertTrue(source.contains("float textScale = (float) (panel.height() / PANEL_BASE_HEIGHT)"));
        assertTrue(source.contains("new Vector3f(textScale, textScale, 1.0f)"));
        assertFalse(source.contains("panel.width() / PANEL_BASE_WIDTH"),
            "面板宽度只应作为命中几何，不能把文字横向拉伸");
    }

    @Test
    void 异步刷新代次在退出或停止后拒绝迟到结果() {
        TableGadgetBarHudService.RefreshState state = new TableGadgetBarHudService.RefreshState();
        long initialGeneration = state.currentGeneration();

        assertTrue(TableGadgetBarHudService.refreshResultAllowed(false, state, initialGeneration));
        state.invalidate();
        assertFalse(TableGadgetBarHudService.refreshResultAllowed(false, state, initialGeneration));
        assertTrue(TableGadgetBarHudService.refreshResultAllowed(
            false, state, state.currentGeneration()));
        assertFalse(TableGadgetBarHudService.refreshResultAllowed(
            true, state, state.currentGeneration()));
    }
}
