package linmumua.doudizhu.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class HudOverlayLayoutTest {
    private static final Path BUNDLE_ROOT = Path.of(
        "build", "paper-26.2", "generated", "resources", "main", "craftengine", "muz");
    private static final Path IMAGES_DIR = BUNDLE_ROOT.resolve("configuration/images");
    private static final Path TEXTURE_ROOT = BUNDLE_ROOT.resolve("resourcepack/assets");
    private static final Pattern NAME = Pattern.compile("^ {2}muz:(\\S+):$");
    private static final Pattern FIELD = Pattern.compile("^\\s+(height|ascent|font|file|char):\\s+(.+)$");

    @Test
    void 请求只校验三层Trick范围并忽略兼容Hotbar字段() {
        assertEquals(-128, HudOverlayLayout.MIN_TRICK_OFFSET);
        assertEquals(512, HudOverlayLayout.MAX_TRICK_OFFSET);
        assertThrows(IllegalArgumentException.class,
            () -> new HudResourceRequest(-129, 0, 0, Integer.MIN_VALUE, -1));
        assertThrows(IllegalArgumentException.class,
            () -> new HudResourceRequest(0, 513, 0, Integer.MAX_VALUE, 101));
        HudResourceRequest request = new HudResourceRequest(0, 0, 0, Integer.MIN_VALUE, -1);
        assertEquals(0, request.cardOffsetDown());
    }

    @Test
    void 三层列表数量顺序与fontchar契约稳定() {
        HudResourceRequest request = new HudResourceRequest(37, 83, 157, -200, 100);
        List<HudOverlayLayout.Glyph> trick = HudOverlayLayout.trickGlyphs(request);
        List<HudOverlayLayout.Glyph> all = HudOverlayLayout.glyphs(request);
        assertEquals(trick, all);
        assertEquals(55 + 2 * 10 + 2 * 2 + 3 + PackAssets.COUNTER_SCALE_TIERS.length * 22, trick.size());
        assertEquals("minecraft:muz_cards_continuous", trick.get(0).font());
        assertEquals(55, trick.stream().filter(g -> g.id().startsWith("card_")).count());
        assertEquals(20, trick.stream().filter(g -> g.id().startsWith("avatar_px_")).count());
        assertEquals(4, trick.stream().filter(g -> g.id().startsWith("avatar_crown_")).count());
        assertEquals(3, trick.stream().filter(g -> g.id().startsWith("bot_avatar")).count());
        assertEquals(22, trick.stream().filter(g -> g.id().startsWith("counter_")).count());
        Set<String> ids = new HashSet<>();
        Set<String> fontChars = new HashSet<>();
        for (HudOverlayLayout.Glyph glyph : all) {
            assertTrue(ids.add(glyph.id()), "Glyph id 重复：" + glyph.id());
            assertTrue(fontChars.add(glyph.font() + "#" + glyph.codepoint()),
                "font/char 重复：" + glyph.font() + "#" + glyph.codepoint());
            assertEquals(glyph.advance() - 1, glyph.rasterWidth(),
                "BitmapProvider 显示宽度必须与 advance-1 一致：" + glyph.id());
        }
        assertEquals(all, List.copyOf(all));
        assertThrows(UnsupportedOperationException.class, () -> all.add(all.get(0)));
    }

    @Test
    void 负偏移按gcd补行且保持比例并拒绝超限() {
        HudResourceRequest request = new HudResourceRequest(-128, -128, -128, Integer.MIN_VALUE, -1);
        HudOverlayLayout.Glyph card = HudOverlayLayout.trickGlyphs(request).stream()
            .filter(g -> g.id().equals("card_card_back_h53")).findFirst().orElseThrow();
        assertEquals(181, card.height());
        assertEquals(128, card.paddingRasterRows());
        assertEquals(181, card.rasterHeight());
        assertEquals(35, card.rasterWidth());
        assertTrue(card.texture().startsWith("muz:font/continuous/"));

        HudOverlayLayout.Glyph bot = HudOverlayLayout.trickGlyphs(request).stream()
            .filter(g -> g.id().equals("bot_avatar")).findFirst().orElseThrow();
        assertEquals(16, bot.originalWidth());
        assertEquals(16, bot.originalHeight());
        assertEquals(208, bot.paddingRasterRows());
        assertEquals(224, bot.rasterHeight());
        assertEquals(140, bot.height());
        assertEquals(10, bot.rasterWidth());
        assertTrue(bot.rasterWidth() <= 256);

        HudOverlayLayout.Glyph outlinedBot = HudOverlayLayout.trickGlyphs(request).stream()
            .filter(g -> g.id().equals("bot_avatar_farmer")).findFirst().orElseThrow();
        assertEquals(18, outlinedBot.originalWidth());
        assertEquals(18, outlinedBot.originalHeight());
        assertEquals(216, outlinedBot.paddingRasterRows());
        assertEquals(234, outlinedBot.rasterHeight());
        assertEquals(143, outlinedBot.height());
        assertEquals(11, outlinedBot.rasterWidth());

        assertTrue(HudOverlayLayout.glyphs(request).stream()
            .noneMatch(glyph -> glyph.id().startsWith("hotbar_")),
            "三层资源请求不得再生成 Hotbar glyph");
    }

    @Test
    void 正偏移不生成动态PNG且几何不变() {
        HudResourceRequest request = new HudResourceRequest(37, 83, 157, -200, 100);
        for (HudOverlayLayout.Glyph glyph : HudOverlayLayout.glyphs(request)) {
            assertFalse(glyph.texture().startsWith("muz:font/continuous/"), glyph.id());
            assertEquals(0, glyph.paddingRasterRows(), glyph.id());
            assertEquals(glyph.baseHeight(), glyph.height(), glyph.id());
            assertEquals(glyph.baseAscent() - glyph.offset(), glyph.ascent(), glyph.id());
        }
    }

    @Test
    void 与真实bundlePNG尺寸独立对比() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(IMAGES_DIR), "缺少真实 CraftEngine images 输出");
        Assumptions.assumeTrue(Files.isDirectory(TEXTURE_ROOT), "缺少真实 resourcepack 输出");
        HudResourceRequest request = new HudResourceRequest(37, 83, 157, -200, 100);
        Map<String, Entry> entries = loadEntries();
        int checked = 0;
        for (HudOverlayLayout.Glyph glyph : HudOverlayLayout.trickGlyphs(request)) {
            if (glyph.paddingRasterRows() != 0) {
                continue;
            }
            Path png = texturePath(glyph.baseTexture());
            if (!Files.isRegularFile(png)) {
                continue;
            }
            BufferedImage image = ImageIO.read(png.toFile());
            assertEquals(glyph.originalWidth(), image.getWidth(), glyph.id());
            assertEquals(glyph.originalHeight(), image.getHeight(), glyph.id());
            Entry entry = entries.values().stream()
                .filter(candidate -> candidate.file().equals(glyph.baseTexture())
                    && candidate.font().equals(baseFont(glyph.font()))
                    && candidate.height() == glyph.baseHeight()
                    && candidate.ascent() == glyph.baseAscent())
                .findFirst().orElse(null);
            assertTrue(entry != null, "真实 bundle 缺少基础 glyph：" + glyph.baseTexture());
            assertEquals(glyph.codepoint(), entry.codepoint(), glyph.id());
            checked++;
        }
        assertTrue(checked > 0, "没有检查到真实 bundle PNG");
    }

    private static String baseFont(String continuousFont) {
        return continuousFont.endsWith("_continuous")
            ? continuousFont.substring(0, continuousFont.length() - "_continuous".length())
            : continuousFont;
    }

    private static Path texturePath(String resource) {
        int colon = resource.indexOf(':');
        String path = colon >= 0 ? resource.substring(colon + 1) : resource;
        return TEXTURE_ROOT.resolve("muz").resolve("textures").resolve(path);
    }

    private static Map<String, Entry> loadEntries() throws IOException {
        Map<String, Entry> result = new HashMap<>();
        try (var stream = Files.list(IMAGES_DIR)) {
            for (Path part : stream.filter(path -> path.toString().endsWith(".yml")).toList()) {
                String id = null;
                String font = null;
                String file = null;
                int height = 0;
                int ascent = 0;
                int codepoint = 0;
                for (String line : Files.readAllLines(part, StandardCharsets.UTF_8)) {
                    Matcher name = NAME.matcher(line);
                    if (name.matches()) {
                        id = name.group(1);
                        font = null;
                        file = null;
                        continue;
                    }
                    Matcher field = FIELD.matcher(line);
                    if (!field.matches() || id == null) {
                        continue;
                    }
                    switch (field.group(1)) {
                        case "font" -> font = field.group(2);
                        case "file" -> file = field.group(2);
                        case "height" -> height = Integer.parseInt(field.group(2));
                        case "ascent" -> ascent = Integer.parseInt(field.group(2));
                        case "char" -> codepoint = Integer.parseInt(field.group(2).substring(2), 16);
                        default -> { }
                    }
                    if (font != null && file != null && codepoint != 0) {
                        result.put(id, new Entry(font, file, height, ascent, codepoint));
                    }
                }
            }
        }
        return result;
    }

    private record Entry(String font, String file, int height, int ascent, int codepoint) {
    }
}
