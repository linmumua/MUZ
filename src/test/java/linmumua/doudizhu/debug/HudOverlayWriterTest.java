package linmumua.doudizhu.debug;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.HudOverlayLayout;
import linmumua.doudizhu.assets.HudResourceRequest;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import javax.imageio.ImageIO;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连续 HUD 覆盖层的布局边界、结构化 YAML 及可恢复事务回归。
 *
 * <p>测试关注的是资源契约而不是具体牌面绘图：布局层负责 Glyph 元数据，本类负责确认
 * writer 不改码位、不重绘无补行资源，并能在失败后恢复自己拥有的文件。
 */
class HudOverlayWriterTest {
    @Test
    void Trick正负边界均由统一请求接受() {
        HudResourceRequest upward = new HudResourceRequest(-128, -128, -128, 0, 100);
        HudResourceRequest downward = new HudResourceRequest(512, 512, 512, 512, 100);
        assertEquals(-128, upward.cardOffsetDown());
        assertEquals(512, downward.counterOffsetDown());
    }

    @Test
    void bot原图按真实16与18像素栅格补行并保持GCD比例() {
        HudResourceRequest request = new HudResourceRequest(-128, -128, -128, 0, 100);
        for (String id : new String[] {"bot_avatar", "bot_avatar_landlord", "bot_avatar_farmer"}) {
            HudOverlayLayout.Glyph bot = HudOverlayLayout.trickGlyphs(request).stream()
                .filter(glyph -> glyph.id().equals(id))
                .findFirst()
                .orElseThrow();
            int original = id.equals("bot_avatar") ? 16 : 18;
            int baseHeight = id.equals("bot_avatar") ? 10 : 11;
            int baseAscent = 8;
            int ascent = baseAscent - request.avatarOffsetDown();
            int gcd = gcd(baseHeight, original);
            int displayStep = baseHeight / gcd;
            int rasterStep = original / gcd;
            int units = (ascent - baseHeight + displayStep - 1) / displayStep;

            assertEquals(original, bot.originalWidth(), id);
            assertEquals(original, bot.originalHeight(), id);
            assertEquals(baseHeight, bot.baseHeight(), id);
            assertEquals(baseAscent, bot.baseAscent(), id);
            assertTrue(bot.paddingRasterRows() > 0, id);
            assertEquals(units * rasterStep, bot.paddingRasterRows(), id);
            assertEquals(baseHeight + units * displayStep, bot.height(), id);
            assertEquals(original + units * rasterStep, bot.rasterHeight(), id);
            assertEquals(Math.max(1, Math.round((float) original * bot.height() / bot.rasterHeight())),
                bot.rasterWidth(), id);
            assertEquals(bot.rasterWidth() + 1, bot.advance(), id);
        }
    }

    private static int gcd(int left, int right) {
        int a = Math.abs(left);
        int b = Math.abs(right);
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    @Test
    void YAML使用SnakeYAML结构化根并包含完整字形元数据() {
        HudResourceRequest request = new HudResourceRequest(-128, -128, -128, 0, 100);
        String trick = HudOverlayWriter.buildImagesYaml(HudOverlayLayout.trickGlyphs(request));
        assertTrue(trick.startsWith("images:"));
        assertTrue(trick.contains("\n"), "SnakeYAML 必须输出真实换行");
        assertFalse(trick.contains("\\n"), "不得把换行写成字面量 \\n");
        assertTrue(trick.contains("font:"));
        assertTrue(trick.contains("char:"));
    }

    @Test
    void pack声明只包含项目作者和统一命名空间() {
        Object loaded = new org.yaml.snakeyaml.Yaml().load(HudOverlayWriter.buildPackYaml());
        assertTrue(loaded instanceof Map<?, ?>);
        Map<?, ?> map = (Map<?, ?>) loaded;
        assertEquals("linmumua", map.get("author"));
        assertEquals("muz", map.get("namespace"));
    }

    @Test
    void image条目键带有CraftEngine资源命名空间且连续bot不覆盖基础ID() {
        HudResourceRequest request = new HudResourceRequest(0, 0, 0, 0, 100);
        Object loaded = new org.yaml.snakeyaml.Yaml().load(
            HudOverlayWriter.buildImagesYaml(HudOverlayLayout.trickGlyphs(request)));
        assertTrue(loaded instanceof Map<?, ?>);
        Map<?, ?> images = (Map<?, ?>) ((Map<?, ?>) loaded).get("images");
        assertTrue(images.keySet().stream().allMatch(key -> key instanceof String
            && ((String) key).startsWith("muz:")), "CraftEngine image key 必须带 muz 命名空间");
        assertTrue(images.containsKey("muz:trick_hud_continuous_bot_avatar"));
        assertTrue(images.containsKey("muz:trick_hud_continuous_bot_avatar_landlord"));
        assertTrue(images.containsKey("muz:trick_hud_continuous_bot_avatar_farmer"));
    }

    @Test
    void captureRestore覆盖pack两份YAML和动态PNG且不擦无关文件() throws Exception {
        Path root = Files.createTempDirectory("muz-overlay-test");
        Path unrelated = root.resolve("resourcepack/other/keep.txt");
        Files.createDirectories(unrelated.getParent());
        Files.writeString(unrelated, "keep", StandardCharsets.UTF_8);

        HudOverlayWriter writer = new HudOverlayWriter(unsafePlugin());
        HudResourceRequest request = new HudResourceRequest(-128, -128, -128,
            0, 100);
        writeBaseTextures(root, request);
        assertTrue(writer.writeNow(root, request, () -> true), "真实完整 request 必须先成功写入 fixture");
        HudOverlayWriter.OverlayState state = writer.capture(root);
        assertTrue(state.files().containsKey("pack.yml"));
        assertTrue(state.files().containsKey("configuration/images/trick_hud_continuous.yml"));
        assertTrue(state.files().keySet().stream().anyMatch(path -> path.contains("font/continuous/")),
            "fixture 必须包含动态 PNG，才能验证完整回滚");

        Path pack = root.resolve("pack.yml");
        Path trick = root.resolve("configuration/images/trick_hud_continuous.yml");
        Path dynamic = root.resolve(state.files().keySet().stream()
            .filter(path -> path.startsWith("resourcepack/assets/muz/textures/font/continuous/"))
            .findFirst().orElseThrow());
        Files.writeString(pack, "new-pack", StandardCharsets.UTF_8);
        Files.deleteIfExists(trick);
        Files.write(dynamic, new byte[] {9});
        writer.restore(root, state);

        for (Map.Entry<String, byte[]> entry : state.files().entrySet()) {
            assertTrue(Files.exists(root.resolve(entry.getKey())), "恢复后缺少文件：" + entry.getKey());
            assertEquals(java.util.Arrays.toString(entry.getValue()),
                java.util.Arrays.toString(Files.readAllBytes(root.resolve(entry.getKey()))),
                "恢复后文件内容不一致：" + entry.getKey());
        }
        assertEquals("keep", Files.readString(unrelated));
    }

    private static void writeBaseTextures(Path root, HudResourceRequest request) throws Exception {
        for (HudOverlayLayout.Glyph glyph : HudOverlayLayout.glyphs(request)) {
            String relative = glyph.baseTexture().substring("muz:".length());
            Path target = root.resolve("resourcepack/assets/muz/textures").resolve(relative);
            Files.createDirectories(target.getParent());
            BufferedImage image = new BufferedImage(glyph.originalWidth(), glyph.originalHeight(),
                BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(image, "png", bytes), "测试夹具必须能写出 PNG：" + glyph.id());
            Files.write(target, bytes.toByteArray());
        }
    }

    @Test
    void 临时文件写入失败返回false且不声称成功() throws Exception {
        Path rootFile = Files.createTempFile("muz-overlay-not-directory", ".tmp");
        HudOverlayWriter writer = new HudOverlayWriter(unsafePlugin());
        HudResourceRequest request = new HudResourceRequest(0, 0, 0, 0, 100);
        assertFalse(writer.writeNow(rootFile, request, () -> true));
    }

    @Test
    void 资源读取失败后保留旧状态并允许再次恢复() throws Exception {
        Path root = Files.createTempDirectory("muz-overlay-failure");
        Path pack = root.resolve("pack.yml");
        Path trick = root.resolve("configuration/images/trick_hud_continuous.yml");
        Files.createDirectories(trick.getParent());
        Files.writeString(pack, "old-pack");
        Files.writeString(trick, "old-trick");
        HudOverlayWriter writer = new HudOverlayWriter(unsafePlugin());
        HudOverlayWriter.OverlayState before = writer.capture(root);
        HudResourceRequest request = new HudResourceRequest(-128, 0, 0, 0, 100);
        assertFalse(writer.writeNow(root, request, () -> true));
        assertEquals("old-pack", Files.readString(pack));
        assertEquals("old-trick", Files.readString(trick));
        Files.writeString(pack, "changed-again");
        writer.restore(root, before);
        writer.restore(root, before);
        assertEquals("old-pack", Files.readString(pack));
    }

    @Test
    void OverlayState复制输入避免回滚快照被外部数组修改() {
        Map<String, byte[]> values = new LinkedHashMap<>();
        byte[] bytes = {1, 2, 3};
        values.put("pack.yml", bytes);
        HudOverlayWriter.OverlayState state = new HudOverlayWriter.OverlayState(values);
        bytes[0] = 9;
        assertEquals(1, state.files().get("pack.yml")[0]);
        byte[] returned = state.files().get("pack.yml");
        returned[1] = 8;
        assertEquals(2, state.files().get("pack.yml")[1]);
    }

    private static DoudizhuPlugin unsafePlugin() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (DoudizhuPlugin) ((Unsafe) field.get(null)).allocateInstance(DoudizhuPlugin.class);
    }
}
