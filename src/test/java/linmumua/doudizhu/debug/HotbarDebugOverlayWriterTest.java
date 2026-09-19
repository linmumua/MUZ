package linmumua.doudizhu.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

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
        // offset-y = 0 必须等于 bundle 里烘焙的基础 ascent，否则拖动没有可预期的原点：
        // 一开面板底图就会自己跳一下。
        assertEquals(-43, PackAssets.HOTBAR_BASE_ASCENT);
        assertEquals(PackAssets.HOTBAR_BASE_ASCENT, HotbarDebugOverlayWriter.BASE_ASCENT);
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
    void 连续补行范围允许ascent超过原始height但拒绝越界() {
        // 连续布局会在 ascent 超过原始 height 时补底部透明行，不能再把 offset 静默钳回旧下界。
        int minOffset = HotbarDebugOverlayWriter.minOffsetY();
        assertEquals(256, HotbarDebugOverlayWriter.ascentFor(minOffset));
        assertThrows(IllegalArgumentException.class,
            () -> HotbarDebugOverlayWriter.ascentFor(minOffset - 1));
        assertTrue(HotbarDebugOverlayWriter.ascentFor(minOffset)
            > HotbarDebugOverlayWriter.GLYPH_HEIGHT);
    }

    @Test
    void 生成的YAML与PackAssets的码位和字体严格对齐() {
        String yaml = HotbarDebugOverlayWriter.buildImagesYaml(24);
        Object parsed = new org.yaml.snakeyaml.Yaml().load(yaml);
        assertTrue(parsed instanceof java.util.Map<?, ?>, "overlay YAML 必须能按结构化根节点解析");
        assertTrue(((java.util.Map<?, ?>) parsed).containsKey("images"),
            "overlay YAML 根节点必须包含 images");
        assertFalse(yaml.contains("\\n"), "overlay YAML 不得把换行写成字面量 \\n");

        // 三张 overlay 图标的码位必须来自 PackAssets 复算侧，不能随手写字面量。
        for (int index = 0; index < PackAssets.HOTBAR_ICON_COUNT; index++) {
            String expectedChar = String.format("\\u%04x", PackAssets.hotbarIconChar(index,
                PackAssets.HOTBAR_DEFAULT_SCALE, true).codePointAt(0));
            assertTrue(yaml.contains("char: " + expectedChar),
                "覆盖层图标码位必须与 PackAssets 对齐，否则游戏内是豆腐块：index=" + index);
        }

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

        // 三张 overlay 图标都复用 bundle PNG，覆盖层只新增 YAML/provider，不自带 PNG。
        assertTrue(yaml.contains("file: muz:font/hotbar_egg.png"), "overlay 应复用鸡蛋 PNG");
        assertTrue(yaml.contains("file: muz:font/hotbar_water.png"), "overlay 应复用水桶 PNG");
        assertTrue(yaml.contains("file: muz:font/hotbar_tomato.png"), "overlay 应复用番茄 PNG");
    }

    @Test
    void pack声明作者只有linmumua() {
        String packYaml = HotbarDebugOverlayWriter.buildPackYaml();
        assertTrue(packYaml.contains("author: linmumua"));
        assertTrue(packYaml.contains("namespace: muz"),
            "覆盖层必须用独立命名空间，避免落进 CraftEngineBundleExporter 的清理范围");
    }

    @Test
    void 覆盖层写出不再分发旧的重载命令() throws Exception {
        // 旧命令语义已过时：覆盖层写出只负责原子落盘，CraftEngine 真实 reload Future、
        // generateResourcePack() 与实际 ZIP 校验统一由 HudResourcePackBridge 流程负责。
        String source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/java/linmumua/doudizhu/debug/HotbarDebugOverlayWriter.java"));
        assertTrue(!source.contains("CRAFT_ENGINE_RELOAD_COMMAND"),
            "Writer 不得恢复旧的命令分发入口");
        assertTrue(!source.contains("dispatchCommand"),
            "Writer 不得在异步写盘后自行分发 CraftEngine 命令");
    }

    @Test
    void hotbarOnly写入口明确拒绝避免覆盖其它三层() throws Exception {
        HotbarDebugOverlayWriter writer = new HotbarDebugOverlayWriter(unsafePlugin());
        assertThrows(UnsupportedOperationException.class, () -> writer.writeNow(0, 100));
        Executor direct = Runnable::run;
        CompletionException failure = assertThrows(CompletionException.class,
            () -> writer.writeAsync(Path.of("unused"), 0, 100, direct, () -> true).join());
        assertTrue(failure.getCause() instanceof UnsupportedOperationException);
    }

    private static DoudizhuPlugin unsafePlugin() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (DoudizhuPlugin) ((Unsafe) field.get(null)).allocateInstance(DoudizhuPlugin.class);
    }
}
