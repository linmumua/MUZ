package linmumua.doudizhu.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

class HotbarFontMetricsTest {
    @Test
    void bitmap按有效alpha宽度与客户端round加一并支持bold继承() throws Exception {
        byte[] vanilla = zip(Map.of(
            "assets/minecraft/font/default.json", "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"minecraft:font/base.png\",\"height\":4,\"chars\":[\"A\"]}]}".getBytes(StandardCharsets.UTF_8),
            "assets/minecraft/textures/font/base.png", bitmap(4, 4, new int[] {0, 2})
        ));
        Path actual = writeZip(Map.of());
        try {
            HotbarFontMetrics metrics = HotbarFontMetrics.load(new ByteArrayInputStream(vanilla), actual, 88);
            assertEquals(OptionalInt.of(4), metrics.measure(Component.text("A")));
            assertEquals(OptionalInt.of(5), metrics.measure(Component.text("A").decorate(TextDecoration.BOLD)));
            Component inherited = Component.text("").decorate(TextDecoration.BOLD).append(Component.text("A"));
            assertEquals(OptionalInt.of(5), metrics.measure(inherited));
        } finally {
            Files.deleteIfExists(actual);
        }
    }

    @Test
    void space与reference可测且小数advance保守拒绝() throws Exception {
        byte[] vanilla = zip(Map.of(
            "assets/minecraft/font/default.json", "{\"providers\":[{\"type\":\"space\",\"advances\":{\" \":2,\"x\":1.5}}]}".getBytes(StandardCharsets.UTF_8),
            "assets/minecraft/font/ref.json", "{\"providers\":[{\"type\":\"reference\",\"id\":\"minecraft:default\"}]}".getBytes(StandardCharsets.UTF_8)
        ));
        Path actual = writeZip(Map.of());
        try {
            HotbarFontMetrics metrics = HotbarFontMetrics.load(new ByteArrayInputStream(vanilla), actual, 88);
            assertEquals(OptionalInt.of(2), metrics.measure(Component.text(" ")));
            assertTrue(metrics.measure(Component.text("x")).isEmpty());
            assertEquals(OptionalInt.of(2), metrics.measure(Component.text(" ").font(Key.key("minecraft", "ref"))));
        } finally {
            Files.deleteIfExists(actual);
        }
    }

    @Test
    void unihex中文sizeOverride使用整数半像素advance() throws Exception {
        String json = "{\"providers\":[{\"type\":\"unihex\",\"hex_file\":\"minecraft:font/test.hex\",\"size_overrides\":[{\"from\":\"4e2d\",\"to\":\"4e2d\",\"left\":2,\"right\":11}]}]}";
        byte[] vanilla = zip(Map.of(
            "assets/minecraft/font/default.json", json.getBytes(StandardCharsets.UTF_8),
            "assets/minecraft/font/test.hex", "4E2D:0000000000000000000000000000F000\n".getBytes(StandardCharsets.US_ASCII)
        ));
        Path actual = writeZip(Map.of());
        try {
            HotbarFontMetrics metrics = HotbarFontMetrics.load(new ByteArrayInputStream(vanilla), actual, 88);
            assertEquals(OptionalInt.of(6), metrics.measure(Component.text("中")));
            assertTrue(metrics.measure(Component.text("中").decorate(TextDecoration.BOLD)).isEmpty());
        } finally {
            Files.deleteIfExists(actual);
        }
    }

    @Test
    void 高包PNG与provider覆盖且activeOverlay目录晚者优先() throws Exception {
        Map<String, byte[]> vanillaEntries = new LinkedHashMap<>();
        vanillaEntries.put("assets/minecraft/font/default.json", bitmapFont("minecraft:font/shared.png", 'A'));
        vanillaEntries.put("assets/minecraft/textures/font/shared.png", bitmap(2, 4, new int[] {0}));
        byte[] o1Json = bitmapFont("minecraft:font/o1.png", 'A');
        byte[] o2Json = bitmapFont("minecraft:font/o2.png", 'A');
        Map<String, byte[]> actualEntries = new LinkedHashMap<>();
        actualEntries.put("pack.mcmeta", "{\"pack\":{\"pack_format\":88},\"overlays\":{\"entries\":[{\"directory\":\"o1\",\"formats\":{\"min_inclusive\":88,\"max_inclusive\":88}},{\"directory\":\"o2\",\"formats\":{\"min_inclusive\":88,\"max_inclusive\":88}}]}}".getBytes(StandardCharsets.UTF_8));
        actualEntries.put("assets/minecraft/font/default.json", bitmapFont("minecraft:font/shared.png", 'A'));
        actualEntries.put("assets/minecraft/textures/font/shared.png", bitmap(4, 4, new int[] {0, 1, 2}));
        actualEntries.put("overlays/o1/assets/minecraft/font/default.json", o1Json);
        actualEntries.put("overlays/o1/assets/minecraft/textures/font/o1.png", bitmap(4, 4, new int[] {0}));
        actualEntries.put("overlays/o2/assets/minecraft/font/default.json", o2Json);
        actualEntries.put("overlays/o2/assets/minecraft/textures/font/o2.png", bitmap(4, 4, new int[] {0, 1, 2, 3}));
        Path actual = writeZip(actualEntries);
        try {
            HotbarFontMetrics metrics = HotbarFontMetrics.load(new ByteArrayInputStream(zip(vanillaEntries)), actual, 88);
            assertEquals(OptionalInt.of(5), metrics.measure(Component.text("A")));
        } finally {
            Files.deleteIfExists(actual);
        }
    }

    @Test
    void filter分歧与未知provider不猜测() throws Exception {
        byte[] vanilla = zip(Map.of(
            "assets/minecraft/font/default.json", "{\"providers\":[{\"type\":\"space\",\"advances\":{\"F\":2},\"filter\":{\"uniform\":true}},{\"type\":\"space\",\"advances\":{\"F\":3},\"filter\":{\"jp\":true}},{\"type\":\"translate\",\"key\":\"x\"}]}".getBytes(StandardCharsets.UTF_8)
        ));
        Path actual = writeZip(Map.of());
        try {
            HotbarFontMetrics metrics = HotbarFontMetrics.load(new ByteArrayInputStream(vanilla), actual, 88);
            assertTrue(metrics.measure(Component.text("F")).isEmpty());
            assertTrue(metrics.measure(Component.translatable("chat.type.text")).isEmpty());
        } finally {
            Files.deleteIfExists(actual);
        }
    }

    @Test
    void 任一客户端字体选项组合不可测时不得采用其它组合的宽度() throws Exception {
        byte[] vanilla = zip(Map.of(
            "assets/minecraft/font/default.json",
            "{\"providers\":[{\"type\":\"space\",\"advances\":{\"A\":2},\"filter\":{\"uniform\":true}}]}".getBytes(StandardCharsets.UTF_8)
        ));
        Path actual = writeZip(Map.of());
        try {
            var metrics = HotbarFontMetrics.load(new ByteArrayInputStream(vanilla), actual, 84);
            assertTrue(metrics.measure(Component.text("A")).isEmpty(),
                "Unicode 开关关闭时不能测宽，不能采用开启时的 2px 做固定位置补偿");
        } finally {
            Files.deleteIfExists(actual);
        }
    }

    @Test
    void bitmap省略height时使用客户端默认8且不能落入后置provider() throws Exception {
        byte[] vanilla = zip(Map.of(
            "assets/minecraft/font/default.json",
            "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"minecraft:font/default-height.png\",\"chars\":[\"A\"]},{\"type\":\"space\",\"advances\":{\"A\":3}}]}".getBytes(StandardCharsets.UTF_8),
            "assets/minecraft/textures/font/default-height.png", bitmap(8, 8, new int[] {0, 1, 2, 3})
        ));
        Path actual = writeZip(Map.of());
        try {
            var metrics = HotbarFontMetrics.load(new ByteArrayInputStream(vanilla), actual, 84);
            assertEquals(OptionalInt.of(5), metrics.measure(Component.text("A")),
                "客户端先命中默认 height=8 的 bitmap，其 advance 是 5，不是后置 space 的 3");
        } finally {
            Files.deleteIfExists(actual);
        }
    }

    @Test
    void 未支持provider或未知filter不能被后置可测provider掩盖() throws Exception {
        for (String first : new String[] {
            "{\"type\":\"ttf\",\"file\":\"minecraft:font/custom.ttf\"}",
            "{\"type\":\"space\",\"advances\":{\"A\":9},\"filter\":{\"future_option\":true}}",
            "{\"type\":\"reference\",\"id\":\"minecraft:unknown\"}"
        }) {
            byte[] vanilla = zip(Map.of(
                "assets/minecraft/font/default.json",
                ("{\"providers\":[" + first + ",{\"type\":\"space\",\"advances\":{\"A\":3}}]}").getBytes(StandardCharsets.UTF_8),
                "assets/minecraft/font/unknown.json",
                "{\"providers\":[{\"type\":\"ttf\",\"file\":\"minecraft:font/custom.ttf\"}]}".getBytes(StandardCharsets.UTF_8)
            ));
            Path actual = writeZip(Map.of());
            try {
                var metrics = HotbarFontMetrics.load(new ByteArrayInputStream(vanilla), actual, 84);
                assertTrue(metrics.measure(Component.text("A")).isEmpty(),
                    "未实现的高优先级字体能力必须降级，不能猜测它缺少 A 而选后置 3px");
            } finally {
                Files.deleteIfExists(actual);
            }
        }
    }

    @Test
    void packFilter存在时保守返回empty() throws Exception {
        byte[] vanilla = zip(Map.of(
            "assets/minecraft/font/default.json", "{\"providers\":[{\"type\":\"space\",\"advances\":{\"A\":2}}]}".getBytes(StandardCharsets.UTF_8)
        ));
        Path actual = writeZip(Map.of(
            "pack.mcmeta", "{\"filter\":{\"block\":{\"pattern\":\".*\"}}}".getBytes(StandardCharsets.UTF_8)
        ));
        try {
            HotbarFontMetrics metrics = HotbarFontMetrics.load(new ByteArrayInputStream(vanilla), actual, 88);
            assertTrue(metrics.measure(Component.text("A")).isEmpty());
        } finally {
            Files.deleteIfExists(actual);
        }
    }

    @Test
    void 真实2612Vanilla字体ZIP中文宽度一致而混合文本字体选项分歧时拒绝() throws Exception {
        var vanilla = HotbarFontMetricsTest.class.getClassLoader()
            .getResourceAsStream("hotbar-font/vanilla-26.1.2.zip");
        assertTrue(vanilla != null, "缺少 hotbar-font/vanilla-26.1.2.zip 测试资源");
        Path actual = writeZip(Map.of());
        try (var input = vanilla) {
            HotbarFontMetrics metrics = HotbarFontMetrics.load(input, actual, 84);
            assertEquals(OptionalInt.of(36), metrics.measure(Component.text("玩家测试")),
                "四个中文字符在官方普通与 Unicode 字体选项下均为 9px advance");
            assertTrue(metrics.measure(Component.text("玩家测试 Hello 123")).isEmpty(),
                "官方普通字体为 86px、Unicode 字体为 73px，不能把任一值当客户端确定宽度");
        } finally {
            Files.deleteIfExists(actual);
        }
    }

    private static byte[] bitmapFont(String file, char character) {
        return ("{\"providers\":[{\"type\":\"bitmap\",\"file\":\"" + file
            + "\",\"height\":4,\"chars\":[\"" + character + "\"]}]}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bitmap(int width, int height, int[] opaqueColumns) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int column : opaqueColumns) {
            for (int y = 0; y < height; y++) {
                image.setRGB(column, y, 0xFF000000);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static Path writeZip(Map<String, byte[]> entries) throws IOException {
        Path path = Files.createTempFile("muz-font-test-", ".zip");
        Files.write(path, zip(entries));
        return path;
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
