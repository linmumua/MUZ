package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import linmumua.doudizhu.assets.HudOverlayLayout;
import linmumua.doudizhu.assets.HudResourceRequest;
import java.util.zip.ZipOutputStream;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.debug.HotbarDebugOverlayWriter;
import linmumua.doudizhu.debug.HudOverlayWriter;
import org.yaml.snakeyaml.Yaml;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HudResourcePackVerifier 的资源契约测试。
 *
 * <p>fixture 使用当前构建资源中的各 scale 记牌器 PNG，并在测试内按 PackAssets 的同一公式生成
 * 根 bundle 与 overlay 字体 JSON。这样「正确包」不是只放几个空文件，而是会真实经过三档记牌器映射、
 * hotbar 根/覆盖层、PNG 哈希和中央目录校验；各个损坏用例只改动一个契约点，确保断言能锁住失败原因。
 */
class HudResourcePackVerifierTest {
    private static final int OFFSET_Y = 50;
    private static final String PACK_META = "pack.mcmeta";
    private static final String COUNTER_FONT = "assets/minecraft/font/muz_counter.json";
    private static final String HOTBAR_FONT = "assets/minecraft/font/muz_hotbar.json";
    private static final List<String> HOTBAR_ICON_FILES = List.of(
        "hotbar_egg.png", "hotbar_water.png", "hotbar_tomato.png"
    );
    private static final String HOTBAR_SELECT_TEXTURE = "assets/muz/textures/font/hotbar_select.png";
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
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(), Map.of());

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void profile未生成的档位被拒绝() throws IOException {
        int generatedCounterScale = PackAssets.COUNTER_SCALE_TIERS[0];
        int ungeneratedCounterScale = generatedCounterScale + 1;
        while (contains(PackAssets.COUNTER_SCALE_TIERS, ungeneratedCounterScale)) {
            ungeneratedCounterScale++;
        }
        String staleCounterPath = "assets/minecraft/font/muz_counter_s" + ungeneratedCounterScale + ".json";
        Path counterPack = writePack(temporaryDirectory.resolve("stale-counter-scale.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            Map.of(staleCounterPath, counterFontJson(OFFSET_Y, Mutation.NONE)
                .getBytes(StandardCharsets.UTF_8)));
        IOException counterFailure = assertThrows(IOException.class,
            () -> verifier().verify(counterPack, OFFSET_Y));
        assertTrue(counterFailure.getMessage().contains("未生成")
            || counterFailure.getMessage().contains("不受支持")
            || counterFailure.getMessage().contains("字体映射")
            || counterFailure.getMessage().contains("残留旧版"), counterFailure.getMessage());

        int generatedHotbarScale = PackAssets.HOTBAR_SCALE_TIERS[0];
        int ungeneratedHotbarScale = generatedHotbarScale + 1;
        while (contains(PackAssets.HOTBAR_SCALE_TIERS, ungeneratedHotbarScale)) {
            ungeneratedHotbarScale++;
        }
        String staleHotbarPath = "assets/minecraft/font/muz_hotbar_s" + ungeneratedHotbarScale + ".json";
        Path hotbarPack = writePack(temporaryDirectory.resolve("stale-hotbar-scale.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            Map.of(staleHotbarPath, hotbarFontJson().getBytes(StandardCharsets.UTF_8)));
        IOException hotbarFailure = assertThrows(IOException.class,
            () -> verifier().verify(hotbarPack, OFFSET_Y));
        assertTrue(hotbarFailure.getMessage().contains("未生成")
            || hotbarFailure.getMessage().contains("不受支持")
            || hotbarFailure.getMessage().contains("字体映射")
            || hotbarFailure.getMessage().contains("残留旧版"), hotbarFailure.getMessage());
    }

    @Test
    void 带scale入口强制要求当前hotbarOverlay() throws IOException {
        Path bundleOnly = writePack(temporaryDirectory.resolve("bundle-only-scale.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(), Map.of());
        IOException missing = assertThrows(IOException.class,
            () -> verifier().verify(bundleOnly, OFFSET_Y, PackAssets.HOTBAR_DEFAULT_SCALE));
        assertTrue(missing.getMessage().contains("overlay"), missing.getMessage());

        Path withOverlay = writePack(temporaryDirectory.resolve("current-scale-overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            overlayFontOverrides("muz", OFFSET_Y, OverlayMutation.NONE),
            overlayMetadata("muz"));
        assertDoesNotThrow(() -> verifier().verify(withOverlay, OFFSET_Y, PackAssets.HOTBAR_DEFAULT_SCALE));
    }

    @Test
    void 选错hotbarScale的overlay被拒绝() throws IOException {
        int unsupported = PackAssets.HOTBAR_DEFAULT_SCALE + 1;
        while (contains(PackAssets.HOTBAR_SCALE_TIERS, unsupported)) {
            unsupported++;
        }
        int invalidScale = unsupported;
        IOException failure = assertThrows(IOException.class,
            () -> verifier().verify(temporaryDirectory.resolve("missing.zip"), OFFSET_Y, invalidScale));
        assertTrue(failure.getMessage().contains("不是当前资源包已生成的档位"), failure.getMessage());
    }

    @Test
    void packFormat75_84_88都通过() throws IOException {
        // 三个目标格式（paper-1.21.11=75、paper-26.1.2=84、paper-26.2=88）都必须被接受。
        // 曾经硬编码只认 84/88，会把 1.21.11 的合法资源包误判为不受支持。
        for (int format : new int[]{75, 84, 88}) {
            Path pack = writePack(temporaryDirectory.resolve("pack-format-" + format + ".zip"), OFFSET_Y,
                counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(), Map.of(),
                "{\"pack\":{\"pack_format\":" + format + "}}");

            assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y), "pack_format=" + format + " 应通过");
        }
    }

    @Test
    void 现代minMaxFormat声明通过() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("modern-pack-format.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(), Map.of(),
            "{\"pack\":{\"min_format\":[88,0],\"max_format\":[88,0]}}");

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void 缺少完整现代Format范围被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("invalid-modern-pack-format.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(), Map.of(),
            "{\"pack\":{\"min_format\":[89,0],\"max_format\":[90,0]}}");

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("min_format/max_format 不受支持"), failure.getMessage());
    }

    @Test
    void 不受支持的packFormat被拒绝() throws IOException {
        // 项目构建表之外的格式（如 100）不得放行，避免掩盖真实的版本错配。
        Path pack = writePack(temporaryDirectory.resolve("pack-format-100.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(), Map.of(),
            "{\"pack\":{\"pack_format\":100}}");

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("pack_format 不受支持"), failure.getMessage());
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
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(), Map.of());
        verifier.verify(pack, OFFSET_Y);
        assertTrue(calls[0] > 0, "verify 应读取内置 YAML 与 PNG");
    }

    @Test
    void 旧48像素记牌器映射被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("legacy-48.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.OLD_48_PIXEL_MAPPING), hotbarFontJson(), Map.of());

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("字体映射与 YAML 不一致"), failure.getMessage());
    }

    @Test
    void ascent与码位错配都被拒绝() throws IOException {
        Path ascentPack = writePack(temporaryDirectory.resolve("wrong-ascent.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.WRONG_ASCENT), hotbarFontJson(), Map.of());
        Path codepointPack = writePack(temporaryDirectory.resolve("wrong-codepoint.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.WRONG_CODEPOINT), hotbarFontJson(), Map.of());

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
        String hotbarTexture = "assets/muz/textures/font/" + HOTBAR_ICON_FILES.get(0);
        byte[] original = readResource("craftengine/muz/resourcepack/" + hotbarTexture);
        byte[] damaged = original.clone();
        damaged[damaged.length - 1] ^= 0x01;
        Path pack = writePack(temporaryDirectory.resolve("wrong-png.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            Map.of(hotbarTexture, damaged));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("资源哈希不一致"), failure.getMessage());
    }

    @Test
    void 旧overlay的ascent被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("old-overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            overlayFontOverrides("muz", 0, OverlayMutation.NONE),
            overlayMetadata("muz"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("字体映射与 YAML 不一致"), failure.getMessage());
    }

    @Test
    void overlay覆盖旧字体或贴图被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            Map.of("legacy/assets/minecraft/font/muz_counter.json",
                "{\"providers\":[]}".getBytes(StandardCharsets.UTF_8)),
            overlayMetadata("legacy"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("overlay"), failure.getMessage());
    }

    @Test
    void CRC损坏被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("crc.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(), Map.of());
        corruptStoredEntry(pack, "assets/muz/textures/font/" + HOTBAR_ICON_FILES.get(0));

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
        writeRawValidPack(pack, overlayMetadata("muz"), List.of(
            new RawEntry("muz/assets/minecraft/font/muz_hotbar.json",
                new byte[0], 16L * 1024L * 1024L + 1L)));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("超限"), failure.getMessage());
    }

    @Test
    void 中央目录与本地头名称不同仍可验证() throws IOException {
        Path pack = temporaryDirectory.resolve("local-name-mismatch.zip");
        writePack(pack, OFFSET_Y, counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(), Map.of());
        rewriteLocalHeaderName(pack, PACK_META, "pack.mcteta");

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void 正确overlay覆盖可通过() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("valid-overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            overlayFontOverrides("muz", OFFSET_Y, OverlayMutation.NONE),
            overlayMetadata("muz"));

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void 仅无关overlay可通过() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("irrelevant-overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            Map.of("muz/unrelated.txt", "not HUD".getBytes(StandardCharsets.UTF_8)),
            overlayMetadata("muz"));

        assertDoesNotThrow(() -> verifier().verify(pack, OFFSET_Y));
    }

    @Test
    void 任意声明overlay目录中的旧映射被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("declared-old-overlay.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            overlayFontOverrides("declared_overlay", OFFSET_Y, OverlayMutation.OLD_48_PIXEL_MAPPING),
            overlayMetadata("declared_overlay"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("overlay") || failure.getMessage().contains("字体映射"),
            failure.getMessage());
    }

    @Test
    void overlay错ascent被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("wrong-overlay-ascent.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            overlayFontOverrides("muz", OFFSET_Y, OverlayMutation.WRONG_ASCENT),
            overlayMetadata("muz"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("字体映射"), failure.getMessage());
    }

    @Test
    void overlay重复char被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("duplicate-overlay-char.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            overlayFontOverrides("muz", OFFSET_Y, OverlayMutation.DUPLICATE_CHAR),
            overlayMetadata("muz"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("重复"), failure.getMessage());
    }

    @Test
    void overlay缺失char被拒绝() throws IOException {
        Path pack = writePack(temporaryDirectory.resolve("missing-overlay-char.zip"), OFFSET_Y,
            counterFontJson(OFFSET_Y, Mutation.NONE), hotbarFontJson(),
            overlayFontOverrides("muz", OFFSET_Y, OverlayMutation.MISSING_CHAR),
            overlayMetadata("muz"));

        IOException failure = assertThrows(IOException.class, () -> verifier().verify(pack, OFFSET_Y));
        assertTrue(failure.getMessage().contains("集合不一致"), failure.getMessage());
    }

    @Test
    void 连续请求正偏移37_83_157通过() throws IOException {
        for (int offset : new int[]{37, 83, 157}) {
            HudResourceRequest request = request(offset, offset, offset, offset, PackAssets.HOTBAR_DEFAULT_SCALE);
            Path pack = writeContinuousPack(temporaryDirectory.resolve("continuous-" + offset + ".zip"), request,
                ContinuousMutation.NONE);
            assertDoesNotThrow(() -> verifier().verify(pack, request));
        }
    }

    @Test
    void 根目录连续字体可通过且重复路径被拒绝() throws IOException {
        HudResourceRequest request = request(37, 37, 37, -200, PackAssets.HOTBAR_DEFAULT_SCALE);
        Path source = writeContinuousPack(temporaryDirectory.resolve("continuous-root-source.zip"), request,
            ContinuousMutation.NONE);
        String overlayPath = "continuous/assets/minecraft/font/muz_counter_continuous.json";
        String rootPath = "assets/minecraft/font/muz_counter_continuous.json";
        Path root = rewriteZip(temporaryDirectory.resolve("continuous-root.zip"), source,
            Map.of(overlayPath, rootPath), Map.of());
        assertDoesNotThrow(() -> verifier().verify(root, request));

        Path duplicate = rewriteZip(temporaryDirectory.resolve("continuous-root-duplicate.zip"), source,
            Map.of(), Map.of(rootPath, readZipEntry(source, overlayPath)));
        IOException failure = assertThrows(IOException.class, () -> verifier().verify(duplicate, request));
        assertTrue(failure.getMessage().contains("同时存在") || failure.getMessage().contains("重复"),
            failure.getMessage());
    }

    @Test
    void 根目录合并Hotbar基础与连续字形通过() throws IOException {
        // 现场 61191 = EF07，是鸡蛋连续字形，不是基础 YAML 中的 EF04。
        assertEquals(61191, PackAssets.hotbarTier(100).debugCodepoint());
        for (int offset : new int[]{37, -200, HudOverlayLayout.minHotbarOffsetY(100)}) {
            HudResourceRequest request = request(37, 83, 157, offset, 100);
            Path source = writeContinuousPack(temporaryDirectory.resolve("merged-source-" + offset + ".zip"),
                request, ContinuousMutation.NONE);
            Path merged = mergeRootHotbar(source, "valid-" + offset, "none");
            assertDoesNotThrow(() -> verifier().verify(merged, request));
        }
    }

    @Test
    void 根目录合并Hotbar不能放行篡改缺失重复及未知映射() throws IOException {
        HudResourceRequest request = request(37, 83, 157, -200, 100);
        Path source = writeContinuousPack(temporaryDirectory.resolve("merged-invalid-source.zip"),
            request, ContinuousMutation.NONE);
        for (String mutation : List.of("base-ascent", "debug-ascent", "debug-height", "debug-file",
            "unknown", "duplicate", "missing-base", "missing-debug", "both")) {
            Path merged = mergeRootHotbar(source, mutation, mutation);
            assertThrows(IOException.class, () -> verifier().verify(merged, request), mutation);
        }
    }

    private Path mergeRootHotbar(Path source, String name, String mutation) throws IOException {
        var base = com.google.gson.JsonParser.parseString(new String(readZipEntry(source, HOTBAR_FONT),
            StandardCharsets.UTF_8)).getAsJsonObject();
        var debug = com.google.gson.JsonParser.parseString(new String(readZipEntry(source, "continuous/" + HOTBAR_FONT),
            StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("providers");
        var providers = base.getAsJsonArray("providers");
        if (mutation.equals("base-ascent")) providers.get(0).getAsJsonObject().addProperty("ascent", 123);
        if (mutation.equals("missing-base")) providers.remove(0);
        if (mutation.equals("debug-ascent")) debug.get(0).getAsJsonObject().addProperty("ascent", 123);
        if (mutation.equals("debug-height")) debug.get(0).getAsJsonObject().addProperty("height", 123);
        if (mutation.equals("debug-file")) debug.get(0).getAsJsonObject().addProperty("file", "muz:font/wrong.png");
        if (mutation.equals("unknown")) {
            var chars = new com.google.gson.JsonArray();
            chars.add(String.valueOf((char) 0xEF00));
            debug.get(0).getAsJsonObject().add("chars", chars);
        }
        if (mutation.equals("missing-debug")) debug.remove(0);
        providers.addAll(debug);
        if (mutation.equals("duplicate")) providers.add(debug.get(0).deepCopy());
        Path target = temporaryDirectory.resolve("merged-hotbar-" + name + ".zip");
        try (ZipFile input = new ZipFile(source.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("continuous/" + HOTBAR_FONT) && !mutation.equals("both")) continue;
                byte[] bytes = entry.getName().equals(HOTBAR_FONT)
                    ? base.toString().getBytes(StandardCharsets.UTF_8)
                    : input.getInputStream(entry).readAllBytes();
                addStored(output, entry.getName(), bytes);
            }
        }
        return target;
    }

    @Test
    void 连续请求负偏移与hotbar边界通过() throws IOException {
        for (int offset : new int[]{-1, -128}) {
            HudResourceRequest request = request(offset, offset, offset, -200, PackAssets.HOTBAR_DEFAULT_SCALE);
            Path pack = writeContinuousPack(temporaryDirectory.resolve("continuous-negative-" + offset + ".zip"), request,
                ContinuousMutation.NONE);
            assertDoesNotThrow(() -> verifier().verify(pack, request));
        }
        HudResourceRequest hotbar = request(0, 0, 0,
            HudOverlayLayout.minHotbarOffsetY(PackAssets.HOTBAR_DEFAULT_SCALE), PackAssets.HOTBAR_DEFAULT_SCALE);
        Path hotbarPack = writeContinuousPack(temporaryDirectory.resolve("continuous-hotbar-negative.zip"), hotbar,
            ContinuousMutation.NONE);
        assertDoesNotThrow(() -> verifier().verify(hotbarPack, hotbar));
    }

    @Test
    void 连续请求错代与provider篡改被拒绝() throws IOException {
        HudResourceRequest first = request(37, 37, 37, -200, PackAssets.HOTBAR_DEFAULT_SCALE);
        HudResourceRequest second = request(83, 83, 83, -200, PackAssets.HOTBAR_DEFAULT_SCALE);
        Path stale = writeContinuousPack(temporaryDirectory.resolve("continuous-stale.zip"), first,
            ContinuousMutation.NONE);
        assertThrows(IOException.class, () -> verifier().verify(stale, second));
        HudResourceRequest mutationRequest = request(-128, -128, -128,
            HudOverlayLayout.minHotbarOffsetY(PackAssets.HOTBAR_DEFAULT_SCALE), PackAssets.HOTBAR_DEFAULT_SCALE);
        for (ContinuousMutation mutation : new ContinuousMutation[]{ContinuousMutation.ASCENT,
            ContinuousMutation.HEIGHT, ContinuousMutation.PADDING, ContinuousMutation.PNG,
            ContinuousMutation.DROP_LAYER}) {
            Path pack = writeContinuousPack(temporaryDirectory.resolve("continuous-" + mutation + ".zip"),
                mutationRequest, mutation);
            assertThrows(IOException.class, () -> verifier().verify(pack, mutationRequest), mutation.name());
        }
    }

    private HudResourceRequest request(int card, int avatar, int counter, int hotbar, int scale) {
        return new HudResourceRequest(card, avatar, counter, hotbar, scale);
    }

    private Path writeContinuousPack(Path target, HudResourceRequest request, ContinuousMutation mutation)
        throws IOException {
        List<HudOverlayLayout.Glyph> glyphs = HudOverlayLayout.glyphs(request);
        Map<String, HudOverlayLayout.Glyph> glyphById = new LinkedHashMap<>();
        for (HudOverlayLayout.Glyph glyph : glyphs) {
            glyphById.put(glyph.id(), glyph);
        }
        Map<String, List<WriterImage>> overlayImages = new LinkedHashMap<>();
        collectWriterImages(HudOverlayWriter.buildImagesYaml(HudOverlayLayout.trickGlyphs(request)), overlayImages);
        collectWriterImages(HudOverlayWriter.buildImagesYaml(HudOverlayLayout.hotbarGlyphs(request)), overlayImages);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            Set<String> added = new java.util.HashSet<>();
            addStored(zip, PACK_META,
                "{\"pack\":{\"pack_format\":88},\"overlays\":{\"entries\":[{\"directory\":\"continuous\"}]}}"
                    .getBytes(StandardCharsets.UTF_8));
            added.add(PACK_META);
            addBundleBaselineResources(zip, added);
            for (HudOverlayLayout.Glyph glyph : glyphs) {
                String basePath = texturePath(glyph.baseTexture());
                if (added.add(basePath)) {
                    addStored(zip, basePath, readResource("craftengine/muz/resourcepack/" + basePath));
                }
            }
            for (Map.Entry<String, List<WriterImage>> entry : overlayImages.entrySet()) {
                StringBuilder json = new StringBuilder("{\"providers\":[");
                boolean first = true;
                for (WriterImage image : entry.getValue()) {
                    if (mutation == ContinuousMutation.DROP_LAYER
                        && image.id().equals("muz:" + glyphs.get(0).id())) {
                        continue;
                    }
                    if (!first) {
                        json.append(',');
                    }
                    first = false;
                    int height = image.height();
                    int ascent = image.ascent();
                    if (mutation == ContinuousMutation.HEIGHT
                        && image.id().equals("muz:" + glyphs.get(0).id())) {
                        height++;
                    }
                    if (mutation == ContinuousMutation.ASCENT
                        && image.id().equals("muz:" + glyphs.get(0).id())) {
                        ascent++;
                    }
                    appendProvider(json, height, ascent, image.file(), image.codepoint());
                }
                addStored(zip, "continuous/" + fontPath(entry.getKey()), json.append("]}").toString()
                    .getBytes(StandardCharsets.UTF_8));
            }
            for (WriterImage image : flatten(overlayImages)) {
                HudOverlayLayout.Glyph glyph = glyphForWriterImage(glyphById, image.id());
                if (glyph == null || glyph.texture().equals(glyph.baseTexture())) {
                    continue;
                }
                String path = texturePath(glyph.texture());
                byte[] png = readResource("craftengine/muz/resourcepack/" + texturePath(glyph.baseTexture()));
                if (glyph.paddingRasterRows() > 0) {
                    png = paddedPng(png, glyph.paddingRasterRows());
                }
                if (mutation == ContinuousMutation.PADDING
                    && glyph.id().equals(glyphs.get(0).id())) {
                    png = readResource("craftengine/muz/resourcepack/" + texturePath(glyph.baseTexture()));
                } else if (mutation == ContinuousMutation.PNG
                    && glyph.id().equals(glyphs.get(0).id())) {
                    png = mutatePngPixel(png);
                }
                addStored(zip, "continuous/" + path, png);
            }
        }
        return target;
    }

    private void addBundleBaselineResources(ZipOutputStream zip, Set<String> added) throws IOException {
        Map<String, List<WriterImage>> baseline = new LinkedHashMap<>();
        for (String path : bundleImageYamlPaths()) {
            collectWriterImages(new String(readResource(path), StandardCharsets.UTF_8), baseline);
        }
        for (String font : baseline.keySet()) {
            String path = fontPath(font);
            if (added.add(path)) {
                addStored(zip, path, buildFontJson(baseline.get(font)).getBytes(StandardCharsets.UTF_8));
            }
        }
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            for (String file : COUNTER_FILES) {
                String path = counterTexturePath(scale, file);
                if (added.add(path)) {
                    addStored(zip, path, readResource("craftengine/muz/resourcepack/" + path));
                }
            }
        }
        for (int scale : PackAssets.HOTBAR_SCALE_TIERS) {
            for (int index = 0; index < PackAssets.HOTBAR_ICON_COUNT; index++) {
                String path = hotbarTexturePath(scale, index);
                if (added.add(path)) {
                    addStored(zip, path, readResource("craftengine/muz/resourcepack/" + path));
                }
            }
            String path = hotbarSelectTexturePath(scale);
            if (added.add(path)) {
                addStored(zip, path, readResource("craftengine/muz/resourcepack/" + path));
            }
        }
        for (String path : gadgetResourcePaths()) {
            if (added.add(path)) {
                addStored(zip, path, readResource("craftengine/muz/resourcepack/" + path));
            }
        }
    }

    private List<String> bundleImageYamlPaths() {
        List<String> paths = new ArrayList<>();
        for (int tier = 0; tier < PackAssets.cardGlyphHeightTierCount(); tier++) {
            paths.add("craftengine/muz/configuration/images/card_h"
                + PackAssets.cardGlyphHeightAt(tier) + ".yml");
        }
        for (int scale : PackAssets.AVATAR_PIXEL_SCALE_TIERS) {
            paths.add("craftengine/muz/configuration/images/avatar_px_s" + scale + ".yml");
        }
        paths.add("craftengine/muz/configuration/images/avatar_crown.yml");
        paths.add("craftengine/muz/configuration/images/bot_avatar.yml");
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            paths.add("craftengine/muz/configuration/images/"
                + (scale == PackAssets.COUNTER_DEFAULT_SCALE ? "counter" : "counter_s" + scale) + ".yml");
        }
        for (int scale : PackAssets.HOTBAR_SCALE_TIERS) {
            paths.add("craftengine/muz/configuration/images/"
                + (scale == PackAssets.HOTBAR_DEFAULT_SCALE ? "hotbar_hud" : "hotbar_hud_s" + scale) + ".yml");
        }
        return paths;
    }

    private void collectWriterImages(String yaml, Map<String, List<WriterImage>> target) throws IOException {
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map<?, ?> root) || !(root.get("images") instanceof Map<?, ?> images)) {
            throw new IOException("测试 YAML 缺少 images 映射");
        }
        for (Map.Entry<?, ?> item : images.entrySet()) {
            if (!(item.getKey() instanceof String id) || !(item.getValue() instanceof Map<?, ?> fields)) {
                throw new IOException("测试 YAML image 条目格式无效");
            }
            String font = stringValue(fields, "font");
            String file = stringValue(fields, "file");
            int height = numberValue(fields, "height");
            int ascent = numberValue(fields, "ascent");
            int codepoint = parseFixtureCodepoint(stringValue(fields, "char"));
            WriterImage image = new WriterImage(id, font, file, height, ascent, codepoint);
            target.computeIfAbsent(font, ignored -> new ArrayList<>()).add(image);
        }
    }

    private String buildFontJson(List<WriterImage> images) {
        StringBuilder json = new StringBuilder("{\"providers\":[");
        for (int index = 0; index < images.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            WriterImage image = images.get(index);
            appendProvider(json, image.height(), image.ascent(), image.file(), image.codepoint());
        }
        return json.append("]}").toString();
    }

    private HudOverlayLayout.Glyph glyphForWriterImage(Map<String, HudOverlayLayout.Glyph> glyphById,
                                                        String namespacedId) {
        String id = stripNamespace(namespacedId);
        if (id.equals("trick_hud_continuous_bot_avatar")) {
            id = "bot_avatar";
        } else if (id.equals("trick_hud_continuous_bot_avatar_landlord")) {
            id = "bot_avatar_landlord";
        } else if (id.equals("trick_hud_continuous_bot_avatar_farmer")) {
            id = "bot_avatar_farmer";
        }
        return glyphById.get(id);
    }

    private List<WriterImage> flatten(Map<String, List<WriterImage>> grouped) {
        List<WriterImage> result = new ArrayList<>();
        for (List<WriterImage> images : grouped.values()) {
            result.addAll(images);
        }
        return result;
    }

    private String stringValue(Map<?, ?> fields, String key) throws IOException {
        Object value = fields.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IOException("测试 YAML 缺少字符串字段：" + key);
        }
        return string;
    }

    private int numberValue(Map<?, ?> fields, String key) throws IOException {
        Object value = fields.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
            throw new IOException("测试 YAML 缺少整数字段：" + key);
        }
        return number.intValue();
    }

    private int parseFixtureCodepoint(String value) throws IOException {
        String text = value.trim();
        try {
            if (text.startsWith("\\u") && text.length() == 6) {
                return Integer.parseInt(text.substring(2), 16);
            }
            if (text.codePointCount(0, text.length()) == 1) {
                return text.codePointAt(0);
            }
        } catch (RuntimeException exception) {
            throw new IOException("测试 YAML char 无效：" + value, exception);
        }
        throw new IOException("测试 YAML char 必须是单一码点：" + value);
    }

    private String stripNamespace(String id) {
        int separator = id.indexOf(':');
        return separator >= 0 ? id.substring(separator + 1) : id;
    }

    private String texturePath(String resource) {
        int separator = resource.indexOf(':');
        return "assets/" + resource.substring(0, separator) + "/textures/" + resource.substring(separator + 1);
    }

    private String fontPath(String resource) {
        int separator = resource.indexOf(':');
        return "assets/" + resource.substring(0, separator) + "/font/" + resource.substring(separator + 1) + ".json";
    }

    private void appendProvider(StringBuilder json, int height, int ascent, String file, int codepoint) {
        json.append("{\"type\":\"bitmap\",\"height\":").append(height)
            .append(",\"ascent\":").append(ascent).append(",\"file\":\"").append(file)
            .append("\",\"chars\":[\"").append(String.format("\\u%04x", codepoint)).append("\"]}");
    }

    private byte[] paddedPng(byte[] original, int rows) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
        BufferedImage padded = new BufferedImage(source.getWidth(), source.getHeight() + rows,
            BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                padded.setRGB(x, y, source.getRGB(x, y));
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(padded, "png", output)) {
            throw new IOException("测试环境不支持 PNG 编码");
        }
        return output.toByteArray();
    }

    private byte[] mutatePngPixel(byte[] original) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
        if (image == null || image.getWidth() == 0 || image.getHeight() == 0) {
            throw new IOException("测试 PNG 无法解码或尺寸为空");
        }
        int x = image.getWidth() / 2;
        int y = image.getHeight() / 2;
        boolean changed = false;
        for (int scanY = 0; scanY < image.getHeight() && !changed; scanY++) {
            for (int scanX = 0; scanX < image.getWidth(); scanX++) {
                if (((image.getRGB(scanX, scanY) >>> 24) & 0xFF) != 0) {
                    x = scanX;
                    y = scanY;
                    changed = true;
                    break;
                }
            }
        }
        image.setRGB(x, y, image.getRGB(x, y) ^ (changed ? 0x00010101 : 0x01000000));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("测试环境不支持 PNG 编码");
        }
        return output.toByteArray();
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
            List<String> generated = new ArrayList<>();
            addStored(zip, PACK_META, packMetadata.getBytes(StandardCharsets.UTF_8));
            addStored(zip, COUNTER_FONT, counterJson.getBytes(StandardCharsets.UTF_8));
            generated.add(COUNTER_FONT);
            addStored(zip, HOTBAR_FONT, hotbarJson.getBytes(StandardCharsets.UTF_8));
            generated.add(HOTBAR_FONT);
            for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
                if (scale == PackAssets.COUNTER_DEFAULT_SCALE) {
                    continue;
                }
                String path = counterFontPath(scale);
                addStored(zip, path, counterFontJson(scale, offsetY, Mutation.NONE)
                    .getBytes(StandardCharsets.UTF_8));
                generated.add(path);
            }
            for (int scale : PackAssets.HOTBAR_SCALE_TIERS) {
                if (scale == PackAssets.HOTBAR_DEFAULT_SCALE) {
                    continue;
                }
                String path = hotbarFontPath(scale);
                addStored(zip, path, hotbarFontJson(scale).getBytes(StandardCharsets.UTF_8));
                generated.add(path);
            }
            for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
                for (String file : COUNTER_FILES) {
                    String path = counterTexturePath(scale, file);
                    addResource(zip, path, overrides);
                    generated.add(path);
                }
            }
            for (int scale : PackAssets.HOTBAR_SCALE_TIERS) {
                for (int index = 0; index < PackAssets.HOTBAR_ICON_COUNT; index++) {
                    String path = hotbarTexturePath(scale, index);
                    addResource(zip, path, overrides);
                    generated.add(path);
                }
                String path = hotbarSelectTexturePath(scale);
                addResource(zip, path, overrides);
                generated.add(path);
            }
            for (String path : gadgetResourcePaths()) {
                addResource(zip, path, overrides);
                generated.add(path);
            }
            for (Map.Entry<String, byte[]> extra : overrides.entrySet()) {
                if (!generated.contains(extra.getKey())) {
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
        entries.add(new RawEntry(HOTBAR_FONT, hotbarFontJson().getBytes(StandardCharsets.UTF_8)));
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            if (scale != PackAssets.COUNTER_DEFAULT_SCALE) {
                String path = counterFontPath(scale);
                entries.add(new RawEntry(path, counterFontJson(scale, OFFSET_Y, Mutation.NONE)
                    .getBytes(StandardCharsets.UTF_8)));
            }
        }
        for (int scale : PackAssets.HOTBAR_SCALE_TIERS) {
            if (scale != PackAssets.HOTBAR_DEFAULT_SCALE) {
                String path = hotbarFontPath(scale);
                entries.add(new RawEntry(path, hotbarFontJson(scale).getBytes(StandardCharsets.UTF_8)));
            }
        }
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            for (String file : COUNTER_FILES) {
                String path = counterTexturePath(scale, file);
                entries.add(new RawEntry(path, readResource("craftengine/muz/resourcepack/" + path)));
            }
        }
        for (int scale : PackAssets.HOTBAR_SCALE_TIERS) {
            for (int index = 0; index < PackAssets.HOTBAR_ICON_COUNT; index++) {
                String path = hotbarTexturePath(scale, index);
                entries.add(new RawEntry(path, readResource("craftengine/muz/resourcepack/" + path)));
            }
            String path = hotbarSelectTexturePath(scale);
            entries.add(new RawEntry(path, readResource("craftengine/muz/resourcepack/" + path)));
        }
        for (String path : gadgetResourcePaths()) {
            entries.add(new RawEntry(path, readResource("craftengine/muz/resourcepack/" + path)));
        }
        entries.addAll(extras);
        writeRawStoredZip(target, entries);
    }

    private String overlayMetadata(String directory) {
        return "{\"pack\":{\"pack_format\":88},\"overlays\":{\"entries\":[{\"directory\":\""
            + directory + "\"}]}}";
    }

    private String counterFontPath(int scale) {
        return scale == PackAssets.COUNTER_DEFAULT_SCALE
            ? COUNTER_FONT : "assets/minecraft/font/muz_counter_s" + scale + ".json";
    }

    private String hotbarFontPath(int scale) {
        return scale == PackAssets.HOTBAR_DEFAULT_SCALE
            ? HOTBAR_FONT : "assets/minecraft/font/muz_hotbar_s" + scale + ".json";
    }

    private String counterTexturePath(int scale, String file) {
        return scale == PackAssets.COUNTER_DEFAULT_SCALE
            ? COUNTER_TEXTURE_ROOT + file
            : COUNTER_TEXTURE_ROOT + "scale_" + scale + "/" + file;
    }

    private String hotbarTexturePath(int scale, int index) {
        String file = HOTBAR_ICON_FILES.get(index);
        return scale == PackAssets.HOTBAR_DEFAULT_SCALE
            ? "assets/muz/textures/font/" + file
            : "assets/muz/textures/font/scale_" + scale + "/" + file;
    }

    private String hotbarSelectTexturePath(int scale) {
        return scale == PackAssets.HOTBAR_DEFAULT_SCALE
            ? HOTBAR_SELECT_TEXTURE
            : "assets/muz/textures/font/scale_" + scale + "/hotbar_select.png";
    }

    private List<String> gadgetResourcePaths() {
        List<String> paths = new ArrayList<>();
        for (String id : List.of("table_gadget_tomato", "table_gadget_water_sheet")) {
            paths.add("assets/muz/items/" + id + ".json");
            paths.add("assets/muz/models/item/" + id + ".json");
        }
        paths.add("assets/muz/textures/item/table_gadget_water_sheet.png");
        return paths;
    }

    private Map<String, byte[]> overlayFontOverrides(
        String directory,
        int offsetY,
        OverlayMutation mutation
    ) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (int scale : PackAssets.HOTBAR_SCALE_TIERS) {
            OverlayMutation scaleMutation = scale == PackAssets.HOTBAR_DEFAULT_SCALE
                ? mutation : OverlayMutation.NONE;
            String path = directory + "/" + hotbarFontPath(scale);
            result.put(path, overlayHotbarFontJson(offsetY, scale, scaleMutation)
                .getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }

    private void addResource(ZipOutputStream zip, String path, Map<String, byte[]> overrides) throws IOException {
        byte[] bytes = overrides.get(path);
        if (bytes == null) {
            bytes = readResource("craftengine/muz/resourcepack/" + path);
        }
        addStored(zip, path, bytes);
    }

    private Path rewriteZip(Path target, Path source, Map<String, String> renames,
                            Map<String, byte[]> additions) throws IOException {
        Set<String> added = new HashSet<>();
        try (ZipFile input = new ZipFile(source.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String destination = renames.getOrDefault(entry.getName(), entry.getName());
                if (!added.add(destination)) {
                    throw new IOException("测试 ZIP 目标条目重复：" + destination);
                }
                addStored(output, destination, input.getInputStream(entry).readAllBytes());
            }
            for (Map.Entry<String, byte[]> addition : additions.entrySet()) {
                if (!added.add(addition.getKey())) {
                    throw new IOException("测试 ZIP 新增条目重复：" + addition.getKey());
                }
                addStored(output, addition.getKey(), addition.getValue());
            }
        }
        return target;
    }

    private byte[] readZipEntry(Path source, String path) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                throw new IOException("测试 ZIP 缺少条目：" + path);
            }
            return zip.getInputStream(entry).readAllBytes();
        }
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

    private static boolean contains(int[] values, int target) {
        for (int value : values) {
            if (value == target) {
                return true;
            }
        }
        return false;
    }

    private String counterFontJson(int offsetY, Mutation mutation) {
        return counterFontJson(PackAssets.COUNTER_DEFAULT_SCALE, offsetY, mutation);
    }

    private String counterFontJson(int scale, int offsetY, Mutation mutation) {
        StringBuilder json = new StringBuilder("{\"providers\":[");
        boolean first = true;
        int mutationOffset = PackAssets.counterDownOffsetTierOf(offsetY) >= 0
            ? offsetY : PackAssets.counterDownOffsetAt(0);
        for (int tier = 0; tier < PackAssets.counterDownOffsetTierCount(); tier++) {
            int offset = PackAssets.counterDownOffsetAt(tier);
            PackAssets.CounterTier geometry = PackAssets.counterGeometry(scale, tier);
            for (int index = 0; index < PackAssets.COUNTER_GLYPHS_PER_TIER; index++) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                int codepoint = geometry.codepointStart()
                    + tier * PackAssets.COUNTER_GLYPHS_PER_TIER + index;
                int height;
                int ascent;
                if (index < PackAssets.COUNTER_LABEL_COUNT) {
                    height = geometry.labelHeight();
                    ascent = geometry.labelAscent();
                } else if (index < PackAssets.COUNTER_FRAME_START_INDEX) {
                    height = geometry.digitHeight();
                    ascent = geometry.digitAscent();
                } else {
                    height = geometry.frameHeight();
                    ascent = geometry.frameAscent();
                }
                String file = COUNTER_FILES.get(index);
                String resourceFile = "muz:font/" + (scale == PackAssets.COUNTER_DEFAULT_SCALE
                    ? "counter/" + file : "counter/scale_" + scale + "/" + file);
                if (mutation == Mutation.OLD_48_PIXEL_MAPPING && scale == PackAssets.COUNTER_DEFAULT_SCALE
                    && offset == mutationOffset && index == 15) {
                    resourceFile = "muz:font/counter/rank_3_dim.png";
                    height = 48;
                    ascent = 26;
                } else if (mutation == Mutation.WRONG_ASCENT && scale == PackAssets.COUNTER_DEFAULT_SCALE
                    && offset == mutationOffset && index == 15) {
                    ascent++;
                } else if (mutation == Mutation.WRONG_CODEPOINT && scale == PackAssets.COUNTER_DEFAULT_SCALE
                    && offset == mutationOffset && index == 15) {
                    codepoint += 0x100;
                }
                appendBitmapProvider(json, height, ascent, resourceFile, codepoint);
            }
        }
        return json.append("]}").toString();
    }

    private String hotbarFontJson() {
        return hotbarFontJson(PackAssets.HOTBAR_DEFAULT_SCALE);
    }

    private String hotbarFontJson(int scale) {
        PackAssets.HotbarTier tier = PackAssets.hotbarTier(scale);
        StringBuilder json = new StringBuilder("{\"providers\":[");
        for (int index = 0; index < PackAssets.HOTBAR_ICON_COUNT; index++) {
            if (index > 0) {
                json.append(',');
            }
            appendBitmapProvider(json, tier.height(), tier.baseAscent(),
                PackAssets.hotbarIconTexture(index, scale), tier.baseCodepoint() + index);
        }
        json.append(',');
        // 选中框字形：根 bundle 只声明 base/select，overlay 的 debug/select-debug 单独生成。
        appendBitmapProvider(json, tier.selectHeight(), tier.baseAscent(), tier.selectTexture(), tier.selectCodepoint());
        return json.append("]}").toString();
    }

    private String overlayHotbarFontJson(int offsetY, OverlayMutation mutation) {
        return overlayHotbarFontJson(offsetY, PackAssets.HOTBAR_DEFAULT_SCALE, mutation);
    }

    private String overlayHotbarFontJson(int offsetY, int scale, OverlayMutation mutation) {
        PackAssets.HotbarTier tier = PackAssets.hotbarTier(scale);
        int height = tier.height();
        int ascent = HotbarDebugOverlayWriter.ascentFor(offsetY, scale);
        StringBuilder json = new StringBuilder("{\"providers\":[");
        if (mutation == OverlayMutation.OLD_48_PIXEL_MAPPING) {
            appendBitmapProvider(json, 48, 26, "muz:font/counter/rank_3_dim.png", tier.debugCodepoint());
        } else {
            if (mutation == OverlayMutation.WRONG_ASCENT) {
                ascent++;
            }
            for (int index = 0; index < PackAssets.HOTBAR_ICON_COUNT; index++) {
                if (index > 0) {
                    json.append(',');
                }
                appendBitmapProvider(json, height, ascent,
                    PackAssets.hotbarIconTexture(index, scale), tier.debugCodepoint() + index);
            }
            if (mutation == OverlayMutation.DUPLICATE_CHAR) {
                json.append(',');
                appendBitmapProvider(json, height, ascent,
                    PackAssets.hotbarIconTexture(0, scale), tier.debugCodepoint());
            }
            if (mutation != OverlayMutation.MISSING_CHAR) {
                json.append(',');
                appendBitmapProvider(json, tier.selectHeight(),
                    HotbarDebugOverlayWriter.ascentFor(offsetY, scale), tier.selectTexture(), tier.selectDebugCodepoint());
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

    private enum ContinuousMutation {
        NONE,
        ASCENT,
        HEIGHT,
        PADDING,
        PNG,
        DROP_LAYER
    }

    private record WriterImage(String id, String font, String file, int height, int ascent, int codepoint) {
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
