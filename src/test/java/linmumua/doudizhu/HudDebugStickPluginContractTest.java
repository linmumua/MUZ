package linmumua.doudizhu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** HUD 调试棒插件契约与 TrickHudPreview 恢复契约。 */
class HudDebugStickPluginContractTest {
    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");
    private static final List<Path> PREVIEW_CANDIDATES = List.of(
        Path.of("src/main/java/linmumua/doudizhu/game/TrickHudPreview.java"),
        Path.of("src/main/java/linmumua/doudizhu/TrickHudPreview.java")
    );

    @Test
    void 插件提供hudDebugStick的PdcBLAZEROD创建契约() throws IOException {
        String source = Files.readString(PLUGIN);

        assertTrue(source.contains("new NamespacedKey(this, \"hud-debug-stick\")"),
            "HUD 调试棒必须使用 hud-debug-stick PDC 键");
        assertTrue(source.contains("Material.BLAZE_ROD"),
            "HUD 调试棒必须使用 BLAZE_ROD");

        int start = source.indexOf("createHudDebugStickItem");
        assertTrue(start >= 0, "插件必须提供 createHudDebugStickItem API");
        int end = nextMethod(source, start + 1);
        String region = source.substring(start, end);
        assertTrue(region.contains("set(hudDebugStickKey, PersistentDataType.STRING"),
            "创建 HUD 调试棒时必须写入 hud-debug-stick PDC 标记");
    }

    @Test
    void 插件提供基于Pdc的isHudDebugStick识别API() throws IOException {
        String source = Files.readString(PLUGIN);
        int start = source.indexOf("public boolean isHudDebugStick");
        assertTrue(start >= 0, "插件必须提供 isHudDebugStick(ItemStack) API");

        int end = nextMethod(source, start + 1);
        String region = source.substring(start, end);
        assertTrue(region.contains("hasStringMarker(itemStack, hudDebugStickKey, \"hud\")"),
            "isHudDebugStick 必须复用 HUD 字符串标记识别逻辑");
        assertFalse(region.contains("displayName") || region.contains("getLore"),
            "isHudDebugStick 不得依赖显示名或 lore");

        int markerStart = source.indexOf("private boolean hasStringMarker");
        assertTrue(markerStart >= 0, "插件必须提供共享的 hasStringMarker 方法");
        String markerRegion = source.substring(markerStart, nextMethod(source, markerStart + 1));
        assertTrue(markerRegion.contains("PersistentDataType.STRING"),
            "hasStringMarker 必须读取 PDC 字符串类型");
    }

    @Test
    void 若TrickHudPreview存在则保留关键可使用API() throws IOException {
        Path preview = PREVIEW_CANDIDATES.stream().filter(Files::exists).findFirst().orElse(null);
        if (preview == null) {
            return;
        }

        String source = Files.readString(preview);
        assertTrue(source.contains("class TrickHudPreview"),
            "TrickHudPreview 文件存在时必须声明对应类");
        for (String method : List.of("show(", "hide(", "reloadSettings(", "hideAll(")) {
            assertTrue(source.contains(method),
                "TrickHudPreview 必须保留关键 API: " + method);
        }

        String pluginSource = Files.readString(PLUGIN);
        assertTrue(pluginSource.contains("public TrickHudPreview trickHudPreview()"),
            "插件存在 TrickHudPreview 时必须提供可恢复/使用的访问入口");
    }

    private static int nextMethod(String source, int from) {
        Matcher matcher = Pattern.compile("\\n    (?:public|private|protected) ").matcher(source);
        return matcher.find(from) ? matcher.start() : source.length();
    }
}
