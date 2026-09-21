package linmumua.doudizhu.game;

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
        assertTrue(source.contains("actionBarOverlay.currentOverlay(playerId)"));
        assertTrue(source.contains("PlayerItemHeldEvent"));
        assertTrue(source.contains("event.setCancelled(true)"));
        assertTrue(source.contains("PackAssets.gadgetBarBaseGlyphText"), "额外栏必须发送 MUZ 九格底图字形");
        assertTrue(source.contains("PackAssets.gadgetBarIconGlyphText"), "道具槽必须叠加客户端真实图标字形");
        assertTrue(source.contains("PackAssets.gadgetBarSelectGlyphText"), "选中槽必须叠加独立选框字形");
        assertTrue(source.contains("PackAssets.gadgetBarKind"), "道具槽必须按 ItemStack 选择客户端真实图标");
        assertTrue(source.contains("PackAssets.GADGET_BAR_SPEECH"), "第九槽必须使用固定语音图标");
        assertTrue(source.contains("offsetService.offset(-PackAssets.GADGET_BAR_CELL_ADVANCE)"),
            "图标和选框必须回退到同一槽位，而不能改变 ActionBar 净宽度");
        assertFalse(source.contains("Component.text(\"道具 \")"), "额外栏不得继续输出文字标题");
        assertFalse(source.contains("itemLabel("), "额外栏不得继续使用文字道具标签");
        assertFalse(source.contains("openInventory"), "额外栏不得打开 Inventory GUI");
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
}
