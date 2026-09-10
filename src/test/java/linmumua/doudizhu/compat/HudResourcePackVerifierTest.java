package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.debug.HotbarDebugOverlayWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HudResourcePackVerifier 的资源契约测试。
 *
 * <p>fixture 使用当前构建资源中的 22 张记牌器 PNG，并在测试内按 PackAssets 的同一公式生成
 * 字体 JSON。这样「正确包」不是只放几个空文件，而是会真实经过 4422 个记牌器映射、hotbar
 * 覆盖层、PNG 哈希和中央目录校验；各个损坏用例只改动一个契约点，确保断言能锁住失败原因。
 */
class HudResourcePackVerifierTest {
    private static final int OFFSET_Y = 50;
    private static final String PACK_META = "pack.mcmeta";
    private static final String COUNTER_FONT = "assets/minecraft/font/muz_counter.json";
    private static final String HOTBAR_FONT = "assets/minecraft/font/muz_hotbar.json";
    private static final String HOTBAR_TEXTURE = "assets/muz/textures/font/hotbar_slots.png";
    private static final String COUNTER_TEXTURE_ROOT = "assets/muz/textures/font/counter/";
    private static final List<String> COUNTER_FILES = List.of(
        "label_3.png", "label_4.png", "label_5.png", "label_6.png", "label_7.png",
        "label_8.png", "label_9.png", "label_10.png", "label_j.png", "label_q.png",
        "label_k.png", "label_a.png", "label_2.png", "label_small.png", "label_big.png",
        "digit_0.png", "digit_1.png", "digit_2.png", "digit_3.png", "digit_4.png",
        "frame_normal.png", "frame_exhausted.png"
    );

    @TempDir
    Path temporaryDirectory;

    @Test
    void 正确生成包通过完整校验() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("valid.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y), Map.of());

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void packFormat84与88都通过() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("pack-format-84.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y), Map.of(),
            "{\"pack\":{\"pack_format\":84}}");

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void 构造器不读取资源且只在verify阶段调用loader() throws IOException {
        int[] calls = {0};
        HudResourcePackVerifier verifier = new HudResourcePackVerifier(path -> {
            calls[0]++;
            return resources().apply(path);
        });
        assertEquals(0, calls[0], "构造器不得做资源 I/O");

        Path pack = writePack(temporaryDirectory.resolve("lazy.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y), Map.of());
        verifier.verify(pack, OFFSET_Y);
        assertTrue(calls[0] > 0, "verify 应读取内置 YAML 与 PNG");
    }

    @Test
    void 旧48像素记牌器映射被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("legacy-48.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.OLD_48_PIXEL_MAPPING), hotbarFontJson(OFFSET_Y), Map.of());

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("字体映射与 YAML 不一致"), failure.getMessage());
    }

    @Test
    void ascent与码位错配都被拒绝() throws IOException {
        Path ascentPack = writePack(temporaryDirectory.resolve("wrong-ascent.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.WRONG_ASCENT), hotbarFontJson(OFFSET_Y), Map.of());
        Path codepointPack = writePack(temporaryDirectory.resolve("wrong-codepoint.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.WRONG_CODEPOINT), hotbarFontJson(OFFSET_Y), Map.of());

        IOException ascentFailure = assertThrows(IOException.class, () -> verifier().verify(ascentPack, OFFSET_Y));
        IOException codepointFailure = assertThrows(IOException.class, () -> verifier().verify(codepointPack, OFFSET_Y));
        assertTrue(ascentFailure.getMessage().contains("字体映射与 YAML 不一致"), ascentFailure.getMessage());
        assertTrue(codepointFailure.getMessage().contains("字体映射与 YAML 不一致")
            || codepointFailure.getMessage().contains("字体映射集合不一致")
            || codepointFailure.getMessage().contains("重复 font/char")
            || codepointFailure.getMessage().contains("未声明的字体映射"), codepointFailure.getMessage());
    }

    @Test
    void PNG哈希错配被拒绝() throws IOException {
        byte[] original = readResource("craftengine/muz/resourcepack/" + HOTBAR_TEXTURE);
        byte[] damaged = original.clone();
        damaged[damaged.length - 1] ^= 0x01;
        Path pack = writePack(temporaryDirectory.resolve("wrong-png.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y),
            Map.of(HOTBAR_TEXTURE, damaged));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("资源哈希不一致"), failure.getMessage());
    }

    @Test
    void 旧overlay的ascent被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("old-overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(0), Map.of());

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("字体映射与 YAML 不一致"), failure.getMessage());
    }

    @Test
    void overlay覆盖旧字体或贴图被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y),
            Map.of("legacy/assets/minecraft/font/muz_counter.json",
                "{\"providers\":[]}".getBytes(StandardCharsets.UTF_8)),
            overlayMetadata("legacy"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("overlay"), failure.getMessage());
    }

    @Test
    void CRC损坏被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("crc.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y), Map.of());
        corruptStoredEntry(pack, HOTBAR_TEXTURE);

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("CRC32") || failure.getMessage().contains("无法安全解压"),
            failure.getMessage());
    }

    @Test
    void 中央目录重复关键条目被拒绝() throws IOException {
        Path pack = temporaryDirectory.resolve("duplicate.zip");
        writeRawStoredZip(pack, List.of(
            new RawEntry(PACK_META, "one".getBytes(StandardCharsets.UTF_8)),
            new RawEntry(PACK_META, "two".getBytes(StandardCharsets.UTF_8))
        ));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("重复条目"), failure.getMessage());
    }

    @Test
    void 超量条目在解包前被拒绝() throws IOException {
        Path pack = temporaryDirectory.resolve("too-large.zip");
        writeRawValidPack(pack, "{\"pack\":{\"pack_format\":88}}", List.of(
            new RawEntry("assets/minecraft/font/muz_counter_legacy.json", new byte[0],
                16L * 1024L * 1024L + 1L)));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("超限"), failure.getMessage());
    }

    @Test
    void 无关大条目不读取且可通过() throws IOException {
        Path pack = temporaryDirectory.resolve("irrelevant-huge.zip");
        writeRawValidPack(pack, "{\"pack\":{\"pack_format\":88}}",
            List.of(new RawEntry("unrelated.bin", new byte[0], 16L * 1024L * 1024L + 1L)));

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void 相关超量overlay条目被拒绝() throws IOException {
        Path pack = temporaryDirectory.resolve("relevant-huge-overlay.zip");
        writeRawValidPack(pack, overlayMetadata("muz_hotbar_debug"), List.of(
            new RawEntry("muz_hotbar_debug/assets/minecraft/font/muz_hotbar.json",
                new byte[0], 16L * 1024L * 1024L + 1L)));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("超限"), failure.getMessage());
    }

    @Test
    void 中央目录与本地头名称不同仍可验证() throws IOException {
        Path pack = temporaryDirectory.resolve("local-name-mismatch.zip");
        writePack(pack, OFFSET_Y, counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y), Map.of());
        rewriteLocalHeaderName(pack, PACK_META, "pack.mcteta");

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void 正确overlay覆盖可通过() throws IOException {
        String path = "muz_hotbar_debug/assets/minecraft/font/muz_hotbar.json";
        Path pack = writePack(temporaryDirectory.resolve("valid-overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y),
            Map.of(path, overlayHotbarFontJson(OFFSET_Y, OverlayMutation.NONE).getBytes(StandardCharsets.UTF_8)),
            overlayMetadata("muz_hotbar_debug"));

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void 仅无关overlay可通过() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("irrelevant-overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y),
            Map.of("muz_hotbar_debug/unrelated.txt", "not HUD".getBytes(StandardCharsets.UTF_8)),
            overlayMetadata("muz_hotbar_debug"));

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void 任意声明overlay目录中的旧映射被拒绝() throws IOException {
        String path = "declared_overlay/assets/minecraft/font/muz_hotbar.json";
        Path pack = writePack(temporaryDirectory.resolve("declared-old-overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y),
            Map.of(path, overlayHotbarFontJson(OFFSET_Y, OverlayMutation.OLD_48_PIXEL_MAPPING)
                .getBytes(StandardCharsets.UTF_8)),
            overlayMetadata("declared_overlay"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("overlay") || failure.getMessage().contains("字体映射"),
            failure.getMessage());
    }

    @Test
    void overlay错ascent被拒绝() throws IOException {
        String path = "muz_hotbar_debug/assets/minecraft/font/muz_hotbar.json";
        Path pack = writePack(temporaryDirectory.resolve("wrong-overlay-ascent.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y),
            Map.of(path, overlayHotbarFontJson(OFFSET_Y, OverlayMutation.WRONG_ASCENT)
                .getBytes(StandardCharsets.UTF_8)),
            overlayMetadata("muz_hotbar_debug"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("字体映射"), failure.getMessage());
    }

    @Test
    void overlay重复char被拒绝() throws IOException {
        String path = "muz_hotbar_debug/assets/minecraft/font/muz_hotbar.json";
        Path pack = writePack(temporaryDirectory.resolve("duplicate-overlay-char.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y),
            Map.of(path, overlayHotbarFontJson(OFFSET_Y, OverlayMutation.DUPLICATE_CHAR)
                .getBytes(StandardCharsets.UTF_8)),
            overlayMetadata("muz_hotbar_debug"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("重复"), failure.getMessage());
    }

    @Test
    void overlay缺失char被拒绝() throws IOException {
        String path = "muz_hotbar_debug/assets/minecraft/font/muz_hotbar.json";
        Path pack = writePack(temporaryDirectory.resolve("missing-overlay-char.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(OFFSET_Y),
            Map.of(path, overlayHotbarFontJson(OFFSET_Y, OverlayMutation.MISSING_CHAR)
                .getBytes(StandardCharsets.UTF_8)),
            overlayMetadata("muz_hotbar_debug"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("集合不一致"), failure.getMessage());
    }

    private HudResourcePackVerifier verifier() {
        return new HudResourcePackVerifier(resources());
    }

    private Function<String, InputStream> resources() {
        // 测试只使用当前 Gradle target 注入的 test runtime classpath，不能静默回退到其他目标的生成物。
        return path -> HudResourcePackVerifierTest.class.getClassLoader().getResourceAsStream(path);
    }

    private byte[] readResource(String path) throws IOException {
        try (InputStream stream = resources().apply(path)) {
            if (stream == null) {
                throw new IOException("测试资源缺失：" + path);
            }
            return stream.readAllBytes();
        }
    }

    private Path writePack(
        Path target,
        int offsetY,
        String counterJson,
        String hotbarJson,
        Map<String, byte[]> overrides
    ) throws IOException {
        return writePack(target, offsetY, counterJson, hotbarJson, overrides,
            "{\"pack\":{\"pack_format\":88}}");
    }

    private Path writePack(
        Path target,
        int offsetY,
        String counterJson,
        String hotbarJson,
        Map<String, byte[]> overrides,
        String packMetadata
    ) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            addStored(zip, PACK_META, packMetadata.getBytes(StandardCharsets.UTF_8));
            addStored(zip, COUNTER_FONT, counterJson.getBytes(StandardCharsets.UTF_8));
            addStored(zip, HOTBAR_FONT, hotbarJson.getBytes(StandardCharsets.UTF_8));
            addResource(zip, HOTBAR_TEXTURE, overrides);
            for (String file : COUNTER_FILES) {
                addResource(zip, COUNTER_TEXTURE_ROOT + file, overrides);
            }
            for (Map.Entry<String, byte[]> extra : overrides.entrySet()) {
                if (!extra.getKey().equals(HOTBAR_TEXTURE)
                    && !extra.getKey().startsWith(COUNTER_TEXTURE_ROOT)) {
                    addStored(zip, extra.getKey(), extra.getValue());
                }
            }
        }
        return target;
    }

    private void writeRawValidPack(Path target, String packMetadata, List<RawEntry> extras) throws IOException {
        List<RawEntry> entries = new ArrayList<>();
        entries.add(new RawEntry(PACK_META, packMetadata.getBytes(StandardCharsets.UTF_8)));
        entries.add(new RawEntry(COUNTER_FONT, counterFontJson(OFFSET_Y, Mutation.NONE)
            .getBytes(StandardCharsets.UTF_8)));
        entries.add(new RawEntry(HOTBAR_FONT, hotbarFontJson(OFFSET_Y).getBytes(StandardCharsets.UTF_8)));
        entries.add(new RawEntry(HOTBAR_TEXTURE, readResource("craftengine/muz/resourcepack/" + HOTBAR_TEXTURE)));
        for (String file : COUNTER_FILES) {
            String path = COUNTER_TEXTURE_ROOT + file;
            entries.add(new RawEntry(path, readResource("craftengine/muz/resourcepack/" + path)));
        }
        entries.addAll(extras);
        writeRawStoredZip(target, entries);
    }

    private String overlayMetadata(String directory) {
        return "{\"pack\":{\"pack_format\":88},\"overlays\":{\"entries\":[{\"directory\":\""
            + directory + "\"}]}}";
    }

    private void addResource(ZipOutputStream zip, String path, Map<String, byte[]> overrides) throws IOException {
        byte[] bytes = overrides.get(path);
        if (bytes == null) {
            bytes = readResource("craftengine/muz/resourcepack/" + path);
        }
        addStored(zip, path, bytes);
    }

    private void addStored(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        ZipEntry entry = new ZipEntry(path);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private String counterFontJson(int offsetY, Mutation mutation) {
        StringBuilder json = new StringBuilder("{\"providers\":[");
        boolean first = true;
        for (int tier = 0; tier < PackAssets.avatarDownOffsetTierCount(); tier++) {
            int offset = PackAssets.avatarDownOffsetAt(tier);
            for (int index = 0; index < PackAssets.COUNTER_GLYPHS_PER_TIER; index++) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                int codepoint = PackAssets.COUNTER_GLYPH_CODEPOINT_START
                    + tier * PackAssets.COUNTER_GLYPHS_PER_TIER + index;
                int height;
                int ascent;
                if (index < PackAssets.COUNTER_LABEL_COUNT) {
                    height = PackAssets.COUNTER_LABEL_HEIGHT;
                    ascent = PackAssets.COUNTER_LABEL_ASCENT - offset;
                } else if (index < PackAssets.COUNTER_FRAME_START_INDEX) {
                    height = PackAssets.COUNTER_DIGIT_HEIGHT;
                    ascent = PackAssets.COUNTER_DIGIT_ASCENT - offset;
                } else {
                    height = PackAssets.COUNTER_FRAME_HEIGHT;
                    ascent = PackAssets.COUNTER_FRAME_ASCENT - offset;
                }
                String file = COUNTER_FILES.get(index);
                if (mutation == Mutation.OLD_48_PIXEL_MAPPING && offset == OFFSET_Y && index == 15) {
                    file = "rank_3_dim.png";
                    height = 48;
                    ascent = 26;
                } else if (mutation == Mutation.WRONG_ASCENT && offset == OFFSET_Y && index == 15) {
                    ascent++;
                } else if (mutation == Mutation.WRONG_CODEPOINT && offset == OFFSET_Y && index == 15) {
                    codepoint += 0x100;
                }
                appendBitmapProvider(json, height, ascent, "muz:font/counter/" + file, codepoint);
            }
        }
        return json.append("]}").toString();
    }

    private String hotbarFontJson(int offsetY) {
        StringBuilder json = new StringBuilder("{\"providers\":[");
        appendBitmapProvider(json, PackAssets.HOTBAR_HUD_GLYPH_HEIGHT, HotbarDebugOverlayWriter.BASE_ASCENT,
            "muz:font/hotbar_slots.png", PackAssets.HOTBAR_HUD_CODEPOINT);
        json.append(',');
        appendBitmapProvider(json, HotbarDebugOverlayWriter.GLYPH_HEIGHT,
            HotbarDebugOverlayWriter.ascentFor(offsetY), "muz:font/hotbar_slots.png",
            PackAssets.HOTBAR_HUD_DEBUG_CODEPOINT);
        return json.append("]}").toString();
    }

    private String overlayHotbarFontJson(int offsetY, OverlayMutation mutation) {
        int height = HotbarDebugOverlayWriter.GLYPH_HEIGHT;
        int ascent = HotbarDebugOverlayWriter.ascentFor(offsetY);
        String file = "muz:font/hotbar_slots.png";
        if (mutation == OverlayMutation.OLD_48_PIXEL_MAPPING) {
            height = 48;
            ascent = 26;
            file = "muz:font/counter/rank_3_dim.png";
        } else if (mutation == OverlayMutation.WRONG_ASCENT) {
            ascent++;
        }
        StringBuilder json = new StringBuilder("{\"providers\":[");
        if (mutation == OverlayMutation.MISSING_CHAR) {
            appendBitmapProvider(json, PackAssets.HOTBAR_HUD_GLYPH_HEIGHT, HotbarDebugOverlayWriter.BASE_ASCENT,
                file, PackAssets.HOTBAR_HUD_CODEPOINT);
        } else {
            appendBitmapProvider(json, height, ascent, file, PackAssets.HOTBAR_HUD_DEBUG_CODEPOINT);
            if (mutation == OverlayMutation.DUPLICATE_CHAR) {
                json.append(',');
                appendBitmapProvider(json, height, ascent, file, PackAssets.HOTBAR_HUD_DEBUG_CODEPOINT);
            }
        }
        return json.append("]}").toString();
    }

    private void appendBitmapProvider(StringBuilder json, int height, int ascent, String file, int codepoint) {
        json.append("{\"type\":\"bitmap\",\"height\":").append(height)
            .append(",\"ascent\":").append(ascent)
            .append(",\"file\":\"").append(file)
            .append("\",\"chars\":[\"").append(String.format("\\u%04x", codepoint))
            .append("\"]}");
    }

    private void corruptStoredEntry(Path pack, String target) throws IOException {
        byte[] bytes = Files.readAllBytes(pack);
        for (int offset = 0; offset + 30 <= bytes.length; offset++) {
            if (readInt(bytes, offset) != 0x04034B50) {
                continue;
            }
            int nameLength = readUnsignedShort(bytes, offset + 26);
            int extraLength = readUnsignedShort(bytes, offset + 28);
            int dataLength = readInt(bytes, offset + 18);
            int nameStart = offset + 30;
            String name = new String(bytes, nameStart, nameLength, StandardCharsets.UTF_8);
            if (target.equals(name)) {
                int dataStart = nameStart + nameLength + extraLength;
                bytes[dataStart] ^= 0x01;
                Files.write(pack, bytes);
                return;
            }
            offset = nameStart + nameLength + extraLength + Math.max(0, dataLength) - 1;
        }
        throw new IOException("找不到要损坏的 ZIP 条目：" + target);
    }

    private void rewriteLocalHeaderName(Path pack, String target, String replacement) throws IOException {
        byte[] bytes = Files.readAllBytes(pack);
        byte[] targetBytes = target.getBytes(StandardCharsets.UTF_8);
        byte[] replacementBytes = replacement.getBytes(StandardCharsets.UTF_8);
        if (targetBytes.length != replacementBytes.length) {
            throw new IOException("本地头名称替换必须保持长度不变");
        }
        for (int offset = 0; offset + 30 <= bytes.length; offset++) {
            if (readInt(bytes, offset) != 0x04034B50) {
                continue;
            }
            int nameLength = readUnsignedShort(bytes, offset + 26);
            int nameStart = offset + 30;
            if (nameLength == targetBytes.length && matches(bytes, nameStart, targetBytes)) {
                System.arraycopy(replacementBytes, 0, bytes, nameStart, replacementBytes.length);
                Files.write(pack, bytes);
                return;
            }
        }
        throw new IOException("找不到要修改的 ZIP 本地头条目：" + target);
    }

    private boolean matches(byte[] bytes, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > bytes.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[offset + index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private void writeRawStoredZip(Path target, List<RawEntry> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<Integer> localOffsets = new ArrayList<>();
        for (RawEntry entry : entries) {
            localOffsets.add(output.size());
            writeInt(output, 0x04034B50);
            writeShort(output, 20);
            writeShort(output, 0);
            writeShort(output, ZipEntry.STORED);
            writeShort(output, 0);
            writeShort(output, 0);
            writeInt(output, crc(entry.bytes));
            writeInt(output, entry.compressedSize());
            writeInt(output, entry.uncompressedSize());
            writeShort(output, entry.name.getBytes(StandardCharsets.UTF_8).length);
            writeShort(output, 0);
            output.write(entry.name.getBytes(StandardCharsets.UTF_8));
            output.write(entry.bytes);
        }
        int centralOffset = output.size();
        for (int index = 0; index < entries.size(); index++) {
            RawEntry entry = entries.get(index);
            byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
            writeInt(output, 0x02014B50);
            writeShort(output, 20);
            writeShort(output, 20);
            writeShort(output, 0);
            writeShort(output, ZipEntry.STORED);
            writeShort(output, 0);
            writeShort(output, 0);
            writeInt(output, crc(entry.bytes));
            writeInt(output, entry.compressedSize());
            writeInt(output, entry.uncompressedSize());
            writeShort(output, name.length);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeShort(output, 0);
            writeInt(output, 0);
            writeInt(output, localOffsets.get(index));
            output.write(name);
        }
        int centralSize = output.size() - centralOffset;
        writeInt(output, 0x06054B50);
        writeShort(output, 0);
        writeShort(output, 0);
        writeShort(output, entries.size());
        writeShort(output, entries.size());
        writeInt(output, centralSize);
        writeInt(output, centralOffset);
        writeShort(output, 0);
        Files.write(target, output.toByteArray());
    }

    private long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private int readUnsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
            | ((bytes[offset + 1] & 0xFF) << 8)
            | ((bytes[offset + 2] & 0xFF) << 16)
            | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private void writeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xFF);
        output.write((value >>> 8) & 0xFF);
    }

    private void writeInt(ByteArrayOutputStream output, long value) {
        output.write((int) value & 0xFF);
        output.write((int) (value >>> 8) & 0xFF);
        output.write((int) (value >>> 16) & 0xFF);
        output.write((int) (value >>> 24) & 0xFF);
    }

    private enum Mutation {
        NONE,
        OLD_48_PIXEL_MAPPING,
        WRONG_ASCENT,
        WRONG_CODEPOINT
    }

    private enum OverlayMutation {
        NONE,
        OLD_48_PIXEL_MAPPING,
        WRONG_ASCENT,
        DUPLICATE_CHAR,
        MISSING_CHAR
    }

    private record RawEntry(String name, byte[] bytes, long uncompressedSize, long compressedSize) {
        private RawEntry(String name, byte[] bytes) {
            this(name, bytes, bytes.length, bytes.length);
        }

        private RawEntry(String name, byte[] bytes, long declaredSize) {
            this(name, bytes, declaredSize, declaredSize);
        }
    }
}
