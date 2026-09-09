package linmumua.doudizhu.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.assets.PackAssets;
import org.junit.jupiter.api.Test;

/**
 * 守护 hotbar 调试覆盖层的「垂直偏移 → 字形 ascent」换算。
 *
 * <p>这层换算是构建期与运行期的双向约定：覆盖层写出的 {@code images.yml} 必须与
 * {@link PackAssets} 的码位/字体常量、以及 build.gradle.kts 烘焙的基准 ascent 对齐。
 * 错位的表现不是报错而是【位置突变或豆腐块】，肉眼审查很难发现，所以用测试钉住。
 */
class HotbarDebugOverlayWriterTest {

    @Test
    void 偏移零时与构建期烘焙的基准ascent重合() {
        // offset-y = 0 必须等于 bundle 里烘焙的 -128，否则拖动没有可预期的原点：
        // 一开面板底图就会自己跳一下。
        assertEquals(HotbarDebugOverlayWriter.BASE_ASCENT, HotbarDebugOverlayWriter.ascentFor(0));
    }

    @Test
    void 正偏移向下负偏移向上() {
        // ascent 越小越往下，所以「向下 offsetY」对应 ascent 减小。
        // 这个方向一旦写反，拖动就会朝反方向跑，而且钳位边界也会跟着反。
        int base = HotbarDebugOverlayWriter.BASE_ASCENT;
        assertEquals(base - 10, HotbarDebugOverlayWriter.ascentFor(10));
        assertEquals(base + 10, HotbarDebugOverlayWriter.ascentFor(-10));
    }

    @Test
    void 钳位守住Minecraft的ascent不得大于height() {
        // Minecraft 要求 ascent <= height。向上偏到极限时必须刚好不越界，
        // 越界的资源包会被客户端拒绝加载，整套字形一起失效。
        int minOffset = HotbarDebugOverlayWriter.minOffsetY();
        assertEquals(HotbarDebugOverlayWriter.GLYPH_HEIGHT,
            HotbarDebugOverlayWriter.ascentFor(minOffset));
        // 超出下界要被钳回来，而不是算出一个非法 ascent
        assertEquals(HotbarDebugOverlayWriter.GLYPH_HEIGHT,
            HotbarDebugOverlayWriter.ascentFor(minOffset - 50));
        assertTrue(HotbarDebugOverlayWriter.ascentFor(minOffset - 50)
            <= HotbarDebugOverlayWriter.GLYPH_HEIGHT);
    }

    @Test
    void 生成的YAML与PackAssets的码位和字体严格对齐() {
        String yaml = HotbarDebugOverlayWriter.buildImagesYaml(24);

        // 码位：必须是 PackAssets 复算侧用的那个，不是随手写的字面量
        String expectedChar = String.format("\\u%04x", PackAssets.HOTBAR_HUD_DEBUG_CODEPOINT);
        assertTrue(yaml.contains("char: " + expectedChar),
            "覆盖层码位必须与 PackAssets.HOTBAR_HUD_DEBUG_CODEPOINT 一致，否则游戏内是豆腐块");

        // 字体：与 bundle 同族，运行期才能在两个码位间切换
        assertTrue(yaml.contains("font: " + PackAssets.HOTBAR_HUD_FONT),
            "覆盖层必须复用 bundle 的字体族");

        // height 恒等于贴图原生高 = 1:1 渲染。改 height 会缩放贴图而不是平移，
        // 那就不是「纯位移」了，这条是本方案成立的前提。
        assertTrue(yaml.contains("height: " + HotbarDebugOverlayWriter.GLYPH_HEIGHT),
            "height 必须恒等于贴图原生高，否则贴图被缩放");

        // ascent 按换算写入
        assertTrue(yaml.contains("ascent: " + HotbarDebugOverlayWriter.ascentFor(24)),
            "ascent 必须由 offset-y 换算而来");

        // 贴图引用 bundle 那张，覆盖层不自带 PNG（绘图逻辑只保留构建期一份）
        assertTrue(yaml.contains("file: muz:font/hotbar_slots.png"),
            "覆盖层应复用 bundle 的贴图，不自带 PNG");
    }

    @Test
    void pack声明作者只有linmumua() {
        String packYaml = HotbarDebugOverlayWriter.buildPackYaml();
        assertTrue(packYaml.contains("author: linmumua"));
        assertTrue(packYaml.contains("namespace: muz_hotbar_debug"),
            "覆盖层必须用独立命名空间，避免落进 CraftEngineBundleExporter 的清理范围");
    }

    @Test
    void ce重载命令必须显式重建客户端资源包() {
        // 无参数 ce reload 只重载配置，不会重建客户端 resource_pack.zip；
        // 这里必须固定到 ce reload pack，才能让覆盖层变更真正下发到客户端。
        assertEquals("ce reload pack", HotbarDebugOverlayWriter.CRAFT_ENGINE_RELOAD_COMMAND,
            "完整资源包重载命令必须是 ce reload pack，否则覆盖层不会重建并下发客户端资源包");
    }
}
