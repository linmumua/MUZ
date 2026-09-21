package linmumua.doudizhu.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import linmumua.doudizhu.assets.HudOverlayLayout;
import linmumua.doudizhu.assets.HudResourceRequest;
import linmumua.doudizhu.assets.PackAssets;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 验证 CraftEngine 生成的 MUZ HUD 资源包。
 *
 * <p>验证器只在调用方的异步资源任务中使用：它从 ZIP 中央目录读取条目，限制条目及总解压
 * 长度，校验真实解压长度和 CRC32，不把资源解包到磁盘。内置 YAML 使用 SnakeYAML
 * {@link SafeConstructor}，并关闭重复键、限制 alias、代码点及嵌套深度。
 */
public final class HudResourcePackVerifier {
    private static final String COUNTER_YAML = "craftengine/muz/configuration/images/counter.yml";
    private static final String BUNDLE_RESOURCE_ROOT = "craftengine/muz/resourcepack/";

    private static final String PACK_META = "pack.mcmeta";
    private static final String FONT_ROOT = "assets/minecraft/font/";
    private static final String COUNTER_FONT = "assets/minecraft/font/muz_counter.json";
    private static final String COUNTER_TEXTURE_ROOT = "assets/muz/textures/font/counter/";
    // 桌内道具采用现代 item definition + models/item 两段资源链，必须和材质一起进 ZIP。
    private static final List<String> GADGET_ITEM_IDS = List.of(
        "table_gadget_tomato", "table_gadget_water_sheet", "table_gadget_speech_bubble"
    );

    private static final String LEGACY_HOTBAR = "assets/minecraft/textures/gui/sprites/hud/hotbar.png";
    private static final String LEGACY_HOTBAR_SELECTION =
        "assets/minecraft/textures/gui/sprites/hud/hotbar_selection.png";

    private static final int MAX_ENTRY_UNCOMPRESSED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_ENTRY_COMPRESSED_BYTES = 16 * 1024 * 1024;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final int MAX_YAML_ALIASES = 50;
    private static final int MAX_YAML_DEPTH = 64;
    private static final int MAX_YAML_CODE_POINTS = 16 * 1024 * 1024;
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PNG_BYTES = 1 * 1024 * 1024;

    private static final List<String> COUNTER_FILES = List.of(
        "label_3.png", "label_4.png", "label_5.png", "label_6.png", "label_7.png",
        "label_8.png", "label_9.png", "label_10.png", "label_j.png", "label_q.png",
        "label_k.png", "label_a.png", "label_2.png", "label_small.png", "label_big.png",
        "digit_0.png", "digit_1.png", "digit_2.png", "digit_3.png", "digit_4.png",
        "frame_normal.png", "frame_exhausted.png"
    );
    private static final Set<String> COUNTER_FILE_SET = Set.copyOf(COUNTER_FILES);
    private static final Set<String> COUNTER_LABEL_FILES = Set.copyOf(COUNTER_FILES.subList(0, PackAssets.COUNTER_LABEL_COUNT));
    private static final Set<String> COUNTER_DIGIT_FILES = Set.copyOf(
        COUNTER_FILES.subList(PackAssets.COUNTER_DIGIT_START_INDEX, PackAssets.COUNTER_FRAME_START_INDEX));

    private final Function<String, InputStream> resourceLoader;

    /**
     * 创建验证器。构造阶段只保存资源加载器，不读取任何文件。
     *
     * @param resourceLoader 插件 JAR 内资源加载器，接收资源相对路径
     */
    public HudResourcePackVerifier(Function<String, InputStream> resourceLoader) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
    }

    /**
     * 在异步线程验证一个已生成的 CraftEngine resource_pack.zip。
     *
     * <p>旧调用只验证 bundle 以及包内已有的全部 overlay，保留给离线兼容测试；Hotbar
     * 字体和图标已退役，不参与资源验证或 ready 判定。
     *
     * @param packPath 资源包路径；验证器不会修改或解包它
     * @param offsetY  本次 hotbar 调试覆盖层采用的垂直偏移
     * @throws IOException 资源缺失、映射错配、ZIP 受损、保护形式不支持或超出限制
     */
    public void verify(Path packPath, int offsetY) throws IOException {
        verifyInternal(packPath, offsetY, null, false);
    }

    /**
     * 验证当前 hotbar scale 的真实 overlay。该入口用于保存/重载同步链路，overlay 缺失、
     * 档位不一致或旧声明残留都会失败。
     */
    public void verify(Path packPath, int offsetY, int hotbarScale) throws IOException {
        // 旧三参数签名仅为源码兼容保留；Hotbar 不再参与资源验证或就绪判定。
        verifyInternal(packPath, offsetY, null, false);
    }

    /**
     * 验证四层连续 HUD 资源。该入口只接受一次性请求，实际期望值由
     * {@link HudOverlayLayout} 计算，随后直接读取 CraftEngine 生成的 ZIP 中央目录与文件。
     * 不把 writer 生成的 YAML 当成客户端资源验证证据。
     */
    public void verify(Path packPath, HudResourceRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(packPath, "packPath");
        if (!Files.isRegularFile(packPath)) {
            throw new IOException("资源包不存在或不是普通文件：" + packPath);
        }
        List<HudOverlayLayout.Glyph> glyphs;
        try {
            // 资源请求已退役 Hotbar 层；只验证牌面、头像、王冠、bot、记牌器三层。
            glyphs = HudOverlayLayout.trickGlyphs(request);
        } catch (RuntimeException exception) {
            throw new IOException("连续 HUD 请求无效：" + exception.getMessage(), exception);
        }
        if (glyphs.isEmpty()) {
            throw new IOException("连续 HUD 没有预期字形");
        }
        try {
            verifyContinuousZip(packPath, request, glyphs);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("连续 HUD 资源结构无效：" + exception.getMessage(), exception);
        }
    }

    private void verifyInternal(Path packPath, int offsetY, Integer overlayScale,
                                boolean requireOverlay) throws IOException {
        Objects.requireNonNull(packPath, "packPath");
        if (!Files.isRegularFile(packPath)) {
            throw new IOException("资源包不存在或不是普通文件：" + packPath);
        }

        ExpectedDeclarations expected = loadExpectedDeclarations(offsetY, overlayScale);
        ZipIndex zip = readZipIndex(packPath, expected, requireOverlay);
        verifyFontJson(zip, expected.bundle, expected.overlay);
        verifyCounterPngs(zip);
        verifyGadgetModelsAndItems(zip);
        verifyPackMetadata(zip);
    }

    private ZipIndex readZipIndex(Path packPath, ExpectedDeclarations expected,
                                  boolean requireOverlay) throws IOException {
        Map<String, EntryMetadata> entries = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            if (zip.size() > MAX_ZIP_ENTRIES) {
                throw new IOException("资源包条目数量超过限制，无法验证：" + zip.size());
            }
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                validateEntryName(name);
                if (!names.add(name)) {
                    throw new IOException("资源包存在重复条目，无法验证：" + name);
                }
                if (LEGACY_HOTBAR.equals(name) || LEGACY_HOTBAR_SELECTION.equals(name)) {
                    throw new IOException("资源包仍覆盖原版全局 hotbar 贴图，拒绝验证：" + name);
                }
                if (!entry.isDirectory()) {
                    // 这里只索引中央目录；无关资源不读取、不解压，也不参与大小预算。
                    entries.put(name, new EntryMetadata(entry.getSize(), entry.getCompressedSize(),
                        entry.getCrc(), entry.getMethod()));
                }
            }

            ZipIndex index = new ZipIndex(packPath, entries, names);
            for (String required : requiredEntries(expected.bundle)) {
                if (!index.names.contains(required)) {
                    throw new IOException("资源包缺少必需条目：" + required);
                }
            }

            Budget budget = new Budget();
            byte[] packMeta = readEntry(zip, index, PACK_META, MAX_JSON_BYTES, budget);
            index.selected.put(PACK_META, packMeta);
            index.overlayPrefixes.addAll(overlayPrefixes(packMeta));

            Set<String> selectedNames = new LinkedHashSet<>();
            for (String name : names) {
                if (isSelected(name) || isRelevantOverlayEntry(name, index.overlayPrefixes)) {
                    selectedNames.add(name);
                }
            }
            for (String name : selectedNames) {
                if (!PACK_META.equals(name)) {
                    index.selected.put(name, readEntry(zip, index, name, MAX_ENTRY_UNCOMPRESSED_BYTES, budget));
                }
            }
            verifyOverlayEntries(index, expected.overlay, requireOverlay);
            return index;
        } catch (ZipException exception) {
            throw new IOException("资源包 ZIP 受损或使用了不支持的保护形式，无法验证："
                + exception.getMessage(), exception);
        }
    }

    private Set<String> overlayPrefixes(byte[] packMeta) throws IOException {
        Set<String> prefixes = new LinkedHashSet<>();
        JsonObject root = parseJsonObject(packMeta, PACK_META);
        JsonElement overlays = root.get("overlays");
        if (overlays == null) {
            return prefixes;
        }
        if (!overlays.isJsonObject()) {
            throw new IOException("pack.mcmeta overlays 不是对象");
        }
        JsonObject object = overlays.getAsJsonObject();
        for (String key : List.of("entries", "formats")) {
            JsonElement value = object.get(key);
            if (value == null) {
                continue;
            }
            if (!value.isJsonArray()) {
                throw new IOException("pack.mcmeta overlays." + key + " 不是数组");
            }
            for (JsonElement item : value.getAsJsonArray()) {
                if (!item.isJsonObject()) {
                    throw new IOException("pack.mcmeta overlays." + key + " 含非对象");
                }
                JsonElement directoryValue = item.getAsJsonObject().get("directory");
                if (directoryValue == null || !directoryValue.isJsonPrimitive()
                    || !directoryValue.getAsJsonPrimitive().isString()) {
                    throw new IOException("pack.mcmeta overlay 缺少 directory");
                }
                String directory = directoryValue.getAsString();
                if (directory.isBlank() || directory.startsWith("/") || directory.contains("..")
                    || directory.contains("\\")) {
                    throw new IOException("pack.mcmeta overlay directory 不安全：" + directory);
                }
                String clean = directory.endsWith("/")
                    ? directory.substring(0, directory.length() - 1) : directory;
                prefixes.add(clean + "/");
            }
        }
        return prefixes;
    }

    private boolean isRelevantOverlayEntry(String name, Set<String> prefixes) {
        String relative = overlayRelative(name, prefixes);
        if (relative == null || relative.isEmpty() || relative.endsWith("/")) {
            return false;
        }
        if (relative.equals(LEGACY_HOTBAR) || relative.equals(LEGACY_HOTBAR_SELECTION)
            || isCounterTexturePath(relative)) {
            return true;
        }
        if (!relative.startsWith(FONT_ROOT) || !relative.endsWith(".json")) {
            return false;
        }
        String fontPath = relative.substring(FONT_ROOT.length(), relative.length() - ".json".length());
        return "muz_counter".equals(fontPath) || fontPath.startsWith("muz_counter_");
    }

    private boolean isCounterTexturePath(String path) {
        if (!path.startsWith(COUNTER_TEXTURE_ROOT) || !path.endsWith(".png")) {
            return false;
        }
        String relative = path.substring(COUNTER_TEXTURE_ROOT.length());
        int slash = relative.indexOf('/');
        if (slash < 0) {
            return true;
        }
        return slash > 0 && relative.startsWith("scale_")
            && relative.indexOf('/', slash + 1) < 0
            && relative.substring(slash + 1).endsWith(".png");
    }

    private byte[] readEntry(ZipFile zip, ZipIndex index, String name, long limit, Budget budget) throws IOException {
        EntryMetadata metadata = index.entries.get(name);
        if (metadata == null || metadata.uncompressedSize < 0 || metadata.compressedSize < 0 || metadata.crc < 0) {
            throw new IOException("资源包条目缺少有效中央目录长度或 CRC32，无法验证：" + name);
        }
        if (metadata.method != ZipEntry.STORED && metadata.method != ZipEntry.DEFLATED) {
            throw new IOException("资源包使用不支持的 ZIP 压缩方式，无法验证：" + name);
        }
        if (metadata.uncompressedSize > limit || metadata.compressedSize > MAX_ENTRY_COMPRESSED_BYTES) {
            throw new IOException("资源包条目长度超限，无法验证：" + name + " = " + metadata.uncompressedSize);
        }
        budget.add(metadata.uncompressedSize, name);
        try {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                throw new IOException("资源包中央目录条目读取失败：" + name);
            }
            try (InputStream stream = zip.getInputStream(entry)) {
                byte[] bytes = readBounded(stream, metadata.uncompressedSize, limit, name);
                CRC32 crc = new CRC32();
                crc.update(bytes);
                if (crc.getValue() != metadata.crc) {
                    throw new IOException("资源包条目 CRC32 校验失败：" + name);
                }
                return bytes;
            }
        } catch (ZipException exception) {
            throw new IOException("资源包条目受保护、混淆或无法安全解压，无法验证："
                + name + " | " + exception.getMessage(), exception);
        }
    }

    private void verifyContinuousZip(Path packPath, HudResourceRequest request,
                                      List<HudOverlayLayout.Glyph> glyphs) throws IOException {
        Map<String, HudOverlayLayout.Glyph> expected = new LinkedHashMap<>();
        Map<String, HudOverlayLayout.Glyph> expectedTextures = new LinkedHashMap<>();
        for (HudOverlayLayout.Glyph glyph : glyphs) {
            if (glyph == null || glyph.font() == null || glyph.texture() == null
                || glyph.baseTexture() == null) {
                throw new IOException("连续 HUD 字形元数据不完整");
            }
            String key = mappingKey(glyph.font(), glyph.codepoint());
            if (expected.putIfAbsent(key, glyph) != null) {
                throw new IOException("连续 HUD 存在重复 font/char：" + key);
            }
            // 无 padding 时 overlay provider 直接复用 bundle 根目录 PNG，不会在 overlay
            // 目录再次写出贴图；只有连续目录中的动态 PNG 才属于 overlay 文件集合。
            if (!glyph.texture().equals(glyph.baseTexture())) {
                String texturePath = textureZipPath(glyph.texture(), glyph.id());
                HudOverlayLayout.Glyph previous = expectedTextures.putIfAbsent(texturePath, glyph);
                if (previous != null && !sameGlyphGeometry(previous, glyph)) {
                    throw new IOException("连续 HUD 贴图路径对应多个几何声明：" + texturePath);
                }
            }
        }

        try (ZipFile zip = new ZipFile(packPath.toFile())) {
            if (zip.size() > MAX_ZIP_ENTRIES) {
                throw new IOException("资源包条目数量超过限制，无法验证：" + zip.size());
            }
            Map<String, EntryMetadata> entries = new LinkedHashMap<>();
            Set<String> names = new LinkedHashSet<>();
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                String name = entry.getName();
                validateEntryName(name);
                if (!names.add(name)) {
                    throw new IOException("资源包存在重复条目，无法验证：" + name);
                }
                if (LEGACY_HOTBAR.equals(name) || LEGACY_HOTBAR_SELECTION.equals(name)) {
                    throw new IOException("资源包仍覆盖原版全局 hotbar 贴图，拒绝验证：" + name);
                }
                if (!entry.isDirectory()) {
                    entries.put(name, new EntryMetadata(entry.getSize(), entry.getCompressedSize(),
                        entry.getCrc(), entry.getMethod()));
                }
            }
            ZipIndex index = new ZipIndex(packPath, entries, names);
            Budget budget = new Budget();
            byte[] metadata = readEntry(zip, index, PACK_META, MAX_JSON_BYTES, budget);
            index.selected.put(PACK_META, metadata);
            index.overlayPrefixes.addAll(overlayPrefixes(metadata));
            verifyPackMetadata(index);

            // 连续 overlay 不能取代既有 bundle 契约：先独立校验根字体、真实 bundle PNG、
            // 道具模型与资源包格式，再校验本次请求的四层 overlay。
            ExpectedDeclarations baseline = loadExpectedDeclarations(0, null);
            for (String required : requiredEntries(baseline.bundle)) {
                if (PACK_META.equals(required)) {
                    continue;
                }
                index.selected.put(required,
                    readEntry(zip, index, required,
                        required.endsWith(".json") ? MAX_JSON_BYTES : MAX_PNG_BYTES, budget));
            }
            Set<String> baselineFontPaths = new HashSet<>(rootFontJsonPaths(baseline.bundle).values());
            Set<String> expectedFontPaths = new LinkedHashSet<>();
            Set<String> expectedRootFontPaths = new LinkedHashSet<>();
            for (HudOverlayLayout.Glyph glyph : glyphs) {
                String path = fontJsonPath(glyph.font(), glyph.id());
                expectedFontPaths.add(path);
                expectedRootFontPaths.add(path);
                String basePath = textureZipPath(glyph.baseTexture(), glyph.id());
                byte[] base = readEntry(zip, index, basePath, MAX_PNG_BYTES, budget);
                verifyBinaryAgainstBundle(base, basePath);
                verifyPngLength(base, basePath);
                BufferedImage baseImage = readPng(base, basePath);
                if (baseImage.getWidth() != glyph.originalWidth()
                    || baseImage.getHeight() != glyph.originalHeight()) {
                    throw new IOException("连续 HUD 基准 PNG 尺寸不一致：" + basePath);
                }
            }

            Set<String> expectedTexturePaths = expectedTextures.keySet();
            rejectUndeclaredContinuousResources(names, index.overlayPrefixes,
                expectedFontPaths, expectedTexturePaths);

            Map<String, String> actualPaths = new LinkedHashMap<>();
            Map<String, ImageDeclaration> rootContinuous = new LinkedHashMap<>();
            for (String logical : expectedFontPaths) {
                String actual = selectContinuousPath(logical, names, index.overlayPrefixes,
                    actualPaths, baselineFontPaths.contains(logical));
                if (actual.equals(logical) && baselineFontPaths.contains(logical)) {
                    // CE 可将基础与 Debug 码位合并进根 Hotbar 字体；仅加入本次布局的精确声明。
                    // 与 HudOverlayLayout / 构建期码位契约对齐，不能放行整个字体或任意额外码位。
                    for (HudOverlayLayout.Glyph glyph : glyphs) {
                        if (fontJsonPath(glyph.font(), glyph.id()).equals(logical)) {
                            rootContinuous.put(glyph.id(), new ImageDeclaration(glyph.id(), glyph.font(),
                                glyph.texture(), glyph.height(), glyph.ascent(), glyph.codepoint(), actual));
                        }
                    }
                }
            }
            // 根 bundle 校验只允许跳过本次请求明确生成的独立连续字体，不能泛化放行旧分页。
            verifyFontJson(index, baseline.bundle, rootContinuous, expectedRootFontPaths);
            verifyCounterPngs(index);
            verifyGadgetModelsAndItems(index);

            Set<String> actualKeys = new HashSet<>();
            Set<String> actualFontPaths = new LinkedHashSet<>();
            Set<String> actualTexturePaths = new LinkedHashSet<>();
            for (String logical : expectedFontPaths) {
                String actual = selectContinuousPath(logical, names, index.overlayPrefixes,
                    actualPaths, baselineFontPaths.contains(logical));
                actualFontPaths.add(logical);
                byte[] bytes = readEntry(zip, index, actual, MAX_JSON_BYTES, budget);
                verifyContinuousFontJson(bytes, logical, actual, expected, actualKeys,
                    actual.equals(logical) ? baseline.bundle : Map.of());
            }
            for (String logical : expectedTexturePaths) {
                String actual = selectContinuousPath(logical, names, index.overlayPrefixes, actualPaths, false);
                actualTexturePaths.add(logical);
                byte[] bytes = readEntry(zip, index, actual, MAX_PNG_BYTES, budget);
                verifyContinuousTexture(bytes, logical, actual, expectedTextures);
            }

            // 声明前缀中的其它 MUZ HUD 文件不能悄悄混入；本次期望文件已在上面按逻辑路径唯一读取。
            for (String prefix : index.overlayPrefixes) {
                for (String name : names) {
                    if (!name.startsWith(prefix) || name.endsWith("/")) {
                        continue;
                    }
                    String relative = name.substring(prefix.length());
                    if (isContinuousFontPath(relative) || isContinuousTexturePath(relative)) {
                        if (!expectedFontPaths.contains(relative) && !expectedTexturePaths.contains(relative)) {
                            throw new IOException("连续 HUD 出现当前请求未声明的资源：" + name);
                        }
                    }
                }
            }
            if (!actualFontPaths.equals(expectedFontPaths)) {
                throw new IOException("连续 HUD 字体文件集合不一致，缺失=" + expectedFontPaths);
            }
            Set<String> expectedKeys = expected.keySet();
            if (!actualKeys.equals(expectedKeys)) {
                Set<String> missing = new LinkedHashSet<>(expectedKeys);
                missing.removeAll(actualKeys);
                Set<String> extra = new LinkedHashSet<>(actualKeys);
                extra.removeAll(expectedKeys);
                throw new IOException("连续 HUD provider 集合不一致，缺失=" + missing + "，多余=" + extra);
            }
            if (!actualTexturePaths.equals(expectedTexturePaths)) {
                throw new IOException("连续 HUD PNG 集合不一致，缺失=" + expectedTexturePaths);
            }
        } catch (ContinuousVerificationException exception) {
            throw (IOException) exception.getCause();
        } catch (ZipException exception) {
            throw new IOException("资源包 ZIP 受损或使用了不支持的保护形式，无法验证："
                + exception.getMessage(), exception);
        }
    }

    private byte[] readEntryUnchecked(ZipFile zip, ZipIndex index, String name, long limit, Budget budget) {
        try {
            return readEntry(zip, index, name, limit, budget);
        } catch (IOException exception) {
            throw new ContinuousVerificationException(exception);
        }
    }

    private boolean sameGlyphGeometry(HudOverlayLayout.Glyph first, HudOverlayLayout.Glyph second) {
        return first.originalWidth() == second.originalWidth()
            && first.originalHeight() == second.originalHeight()
            && first.height() == second.height()
            && first.ascent() == second.ascent()
            && first.paddingRasterRows() == second.paddingRasterRows()
            && first.advance() == second.advance();
    }

    private boolean isContinuousFontPath(String path) {
        if (!path.startsWith(FONT_ROOT) || !path.endsWith(".json")) {
            return false;
        }
        String name = path.substring(FONT_ROOT.length(), path.length() - ".json".length());
        // 根目录/声明目录中的 MUZ 字体均纳入集合边界，避免未知分页或旧字体绕过校验。
        return name.startsWith("muz_");
    }

    private boolean isContinuousTexturePath(String path) {
        return path.startsWith("assets/muz/textures/font/continuous/");
    }

    private void rejectUndeclaredContinuousResources(Set<String> names, Set<String> prefixes,
                                                       Set<String> expectedFontPaths,
                                                       Set<String> expectedTexturePaths) throws IOException {
        for (String name : names) {
            if (name.endsWith("/") || prefixes.stream().anyMatch(name::startsWith)) {
                continue;
            }
            String assetPath = pathAfterAssets(name);
            if (assetPath == null) {
                continue;
            }
            if (isContinuousFontPath(assetPath) && assetPath.contains("_continuous")) {
                if (name.startsWith("assets/") && expectedFontPaths.contains(assetPath)) {
                    continue;
                }
                throw new IOException(name.startsWith("assets/")
                    ? "连续 HUD 出现当前请求未声明的根目录资源：" + name
                    : "连续 HUD 资源位于未声明目录：" + name);
            }
            if (isContinuousTexturePath(assetPath)) {
                if (name.startsWith("assets/") && expectedTexturePaths.contains(assetPath)) {
                    continue;
                }
                throw new IOException(name.startsWith("assets/")
                    ? "连续 HUD 出现当前请求未声明的根目录资源：" + name
                    : "连续 HUD 资源位于未声明目录：" + name);
            }
        }
    }

    private String pathAfterAssets(String name) {
        if (name.startsWith("assets/")) {
            return name;
        }
        int index = name.indexOf("/assets/");
        return index >= 0 ? name.substring(index + 1) : null;
    }

    private void verifyContinuousFontJson(byte[] bytes, String relative, String source,
                                          Map<String, HudOverlayLayout.Glyph> expected,
                                          Set<String> actualKeys,
                                          Map<String, ImageDeclaration> verifiedBundle) throws IOException {
        Set<String> bundleKeys = new HashSet<>();
        for (ImageDeclaration declaration : verifiedBundle.values()) {
            bundleKeys.add(mappingKey(declaration.font, declaration.codepoint));
        }
        JsonObject root = parseJsonObject(bytes, source);
        JsonArray providers = root.getAsJsonArray("providers");
        if (providers == null || providers.isEmpty()) {
            throw new IOException("连续 HUD 字体 JSON 缺少 providers：" + source);
        }
        String font = fontIdFromAssetPath(relative, source);
        for (JsonElement element : providers) {
            if (!element.isJsonObject()) {
                throw new IOException("连续 HUD provider 不是对象：" + source);
            }
            JsonObject provider = element.getAsJsonObject();
            if (!"bitmap".equals(stringJson(provider, "type", source))) {
                throw new IOException("连续 HUD 含不支持的 provider 类型：" + source);
            }
            String file = stringJson(provider, "file", source);
            int height = intJson(provider, "height", source);
            int ascent = intJson(provider, "ascent", source);
            JsonArray chars = provider.getAsJsonArray("chars");
            if (chars == null || chars.isEmpty()) {
                throw new IOException("连续 HUD provider 缺少 chars：" + source);
            }
            for (JsonElement charElement : chars) {
                if (!charElement.isJsonPrimitive() || !charElement.getAsJsonPrimitive().isString()) {
                    throw new IOException("连续 HUD chars 含非字符串：" + source);
                }
                String text = charElement.getAsString();
                for (int index = 0; index < text.length();) {
                    int codepoint = text.codePointAt(index);
                    index += Character.charCount(codepoint);
                    String key = mappingKey(font, codepoint);
                    // 合并根字体中的基础码位已经完整核验，不计入连续 provider 集合。
                    if (bundleKeys.contains(key)) {
                        continue;
                    }
                    HudOverlayLayout.Glyph glyph = expected.get(key);
                    if (glyph == null || !glyph.texture().equals(file)
                        || glyph.height() != height || glyph.ascent() != ascent) {
                        throw new IOException("连续 HUD provider 与布局不一致：" + source);
                    }
                    if (!actualKeys.add(key)) {
                        throw new IOException("连续 HUD 存在重复 font/char：" + key);
                    }
                }
            }
        }
    }

    private void verifyContinuousTexture(byte[] bytes, String relative, String source,
                                         Map<String, HudOverlayLayout.Glyph> expected) throws IOException {
        HudOverlayLayout.Glyph glyph = null;
        String zipPath = relative;
        for (Map.Entry<String, HudOverlayLayout.Glyph> entry : expected.entrySet()) {
            if (entry.getKey().equals(zipPath)) {
                glyph = entry.getValue();
                break;
            }
        }
        if (glyph == null) {
            throw new IOException("连续 HUD 出现未知 PNG：" + source);
        }
        verifyPngLength(bytes, source);
        BufferedImage image = readPng(bytes, source);
        // rasterWidth() 是客户端按 provider height 推导的显示宽度，不是 PNG 栅格宽度；
        // writer 只在底部补行，PNG 宽度必须保持真实 bundle 原图宽度。
        if (image.getWidth() != glyph.originalWidth() || image.getHeight() != glyph.rasterHeight()
            || image.getWidth() > 256 || image.getHeight() > 256) {
            throw new IOException("连续 HUD PNG 几何不一致：" + source);
        }
        byte[] original = readBundleResource(glyph.baseTexture(), glyph.id());
        BufferedImage base = readPng(original, glyph.id());
        if (glyph.paddingRasterRows() == 0) {
            if (!Arrays.equals(bytes, original)) {
                throw new IOException("无 padding 的连续 HUD PNG 必须与 bundle 原图完全一致：" + source);
            }
            return;
        }
        if (base.getWidth() != glyph.originalWidth() || base.getHeight() != glyph.originalHeight()
            || image.getWidth() != base.getWidth()
            || image.getHeight() <= base.getHeight()) {
            throw new IOException("连续 HUD padding PNG 原图比例不一致：" + source);
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int actual = image.getRGB(x, y);
                if (y < base.getHeight()) {
                    if (actual != base.getRGB(x, y)) {
                        throw new IOException("连续 HUD padding PNG 原图像素被改写：" + source);
                    }
                } else if (((actual >>> 24) & 0xFF) != 0) {
                    throw new IOException("连续 HUD padding PNG 新增区域必须全透明：" + source);
                }
            }
        }
    }

    private byte[] readBundleResource(String texture, String source) throws IOException {
        String bundlePath = BUNDLE_RESOURCE_ROOT + textureZipPath(texture, source);
        InputStream stream;
        try {
            stream = resourceLoader.apply(bundlePath);
        } catch (RuntimeException exception) {
            throw new IOException("加载内置资源失败：" + bundlePath, exception);
        }
        if (stream == null) {
            throw new IOException("内置资源缺失：" + bundlePath);
        }
        try (stream) {
            return readBounded(stream, -1, MAX_PNG_BYTES, bundlePath);
        }
    }

    private static final class ContinuousVerificationException extends RuntimeException {
        private ContinuousVerificationException(IOException cause) {
            super(cause);
        }
    }

    private void verifyOverlayEntries(ZipIndex zip, Map<String, ImageDeclaration> expected,
                                      boolean requireOverlay) throws IOException {
        if (zip.overlayPrefixes.isEmpty()) {
            if (requireOverlay) {
                throw new IOException("资源包缺少当前 hotbar scale 的 overlay 声明");
            }
            return;
        }
        Map<String, ImageDeclaration> expectedByKey = new HashMap<>();
        for (ImageDeclaration declaration : expected.values()) {
            String key = mappingKey(declaration.font, declaration.codepoint);
            if (expectedByKey.putIfAbsent(key, declaration) != null) {
                throw new IOException("overlay 内置 HUD YAML 存在重复 font/char：" + key);
            }
        }
        Set<String> actualKeys = new HashSet<>();
        boolean sawRelevantEntry = false;
        for (String prefix : zip.overlayPrefixes) {
            for (String name : zip.names) {
                if (!name.startsWith(prefix) || name.endsWith("/")
                    || !isRelevantOverlayEntry(name, zip.overlayPrefixes)) {
                    continue;
                }
                sawRelevantEntry = true;
                byte[] bytes = zip.selected.get(name);
                if (bytes == null) {
                    throw new IOException("资源包 overlay 相关条目未读取：" + name);
                }
                String relative = overlayRelative(name, zip.overlayPrefixes);
                if (relative.equals(LEGACY_HOTBAR) || relative.equals(LEGACY_HOTBAR_SELECTION)) {
                    throw new IOException("资源包 overlay 覆盖原版全局 hotbar 贴图，拒绝验证：" + name);
                }
                if (relative.endsWith(".png")) {
                    String assetPath = relative;
                    verifyBinaryAgainstBundle(bytes, assetPath);
                    verifyPngLength(bytes, name);
                    BufferedImage image = readPng(bytes, name);
                    if (isCounterTexturePath(relative)) {
                        verifyCounterPngShape(image, relative);
                    }
                } else if (relative.startsWith(FONT_ROOT) && relative.endsWith(".json")) {
                    Set<String> mappings = verifyOverlayFontJson(bytes, relative, name, expectedByKey);
                    for (String key : mappings) {
                        if (!actualKeys.add(key)) {
                            throw new IOException("资源包 overlay 存在重复 font/char：" + key);
                        }
                    }
                }
            }
        }
        if (requireOverlay && !sawRelevantEntry) {
            throw new IOException("资源包缺少当前 hotbar scale 的 overlay 资源");
        }
        if (sawRelevantEntry && !actualKeys.equals(expectedByKey.keySet())) {
            Set<String> missing = new HashSet<>(expectedByKey.keySet());
            missing.removeAll(actualKeys);
            Set<String> extra = new HashSet<>(actualKeys);
            extra.removeAll(expectedByKey.keySet());
            throw new IOException("overlay 字体映射集合不一致，缺失=" + missing + "，多余=" + extra);
        }
    }

    private String overlayRelative(String name, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }
        return null;
    }

    private String selectContinuousPath(String logicalPath, Set<String> names, Set<String> prefixes,
                                        Map<String, String> selected, boolean baselineRootPath) throws IOException {
        String previous = selected.get(logicalPath);
        if (previous != null) {
            return previous;
        }
        List<String> overlayCandidates = new ArrayList<>();
        for (String prefix : prefixes) {
            String candidate = prefix + logicalPath;
            if (names.contains(candidate)) {
                overlayCandidates.add(candidate);
            }
        }
        if (baselineRootPath && !overlayCandidates.isEmpty()) {
            if (overlayCandidates.size() != 1) {
                throw new IOException("连续 HUD 缺少唯一 overlay 字体资源："
                    + logicalPath + " -> " + overlayCandidates);
            }
            selected.put(logicalPath, overlayCandidates.get(0));
            return overlayCandidates.get(0);
        }
        List<String> candidates = new ArrayList<>(overlayCandidates);
        if (names.contains(logicalPath)) {
            candidates.add(logicalPath);
        }
        if (candidates.isEmpty()) {
            throw new IOException("连续 HUD 缺少资源：" + logicalPath);
        }
        if (candidates.size() != 1) {
            throw new IOException("连续 HUD 资源同时存在于根目录和 overlay，拒绝验证："
                + logicalPath + " -> " + candidates);
        }
        selected.put(logicalPath, candidates.get(0));
        return candidates.get(0);
    }

    private Set<String> verifyOverlayFontJson(byte[] bytes, String relative, String source,
                                              Map<String, ImageDeclaration> expectedByKey) throws IOException {
        JsonObject root = parseJsonObject(bytes, source);
        JsonArray providers = root.getAsJsonArray("providers");
        if (providers == null || providers.isEmpty()) {
            throw new IOException("overlay 字体 JSON 缺少 providers：" + source);
        }
        String font = fontIdFromAssetPath(relative, source);
        Set<String> actual = new HashSet<>();
        for (JsonElement element : providers) {
            if (!element.isJsonObject()) {
                throw new IOException("overlay 字体 provider 不是对象：" + source);
            }
            JsonObject provider = element.getAsJsonObject();
            if (!"bitmap".equals(stringJson(provider, "type", source))) {
                throw new IOException("overlay 字体含不支持的 provider 类型：" + source);
            }
            String file = stringJson(provider, "file", source);
            int height = intJson(provider, "height", source);
            int ascent = intJson(provider, "ascent", source);
            JsonArray chars = provider.getAsJsonArray("chars");
            if (chars == null || chars.isEmpty()) {
                throw new IOException("overlay 位图 provider 缺少 chars：" + source);
            }
            for (JsonElement charElement : chars) {
                if (!charElement.isJsonPrimitive() || !charElement.getAsJsonPrimitive().isString()) {
                    throw new IOException("overlay 字体 chars 含非字符串：" + source);
                }
                String text = charElement.getAsString();
                if (text.isEmpty()) {
                    throw new IOException("overlay 字体 chars 含空字符串：" + source);
                }
                for (int offset = 0; offset < text.length();) {
                    int codepoint = text.codePointAt(offset);
                    offset += Character.charCount(codepoint);
                    String key = mappingKey(font, codepoint);
                    ImageDeclaration declaration = expectedByKey.get(key);
                    if (declaration == null || !declaration.file.equals(file)
                        || declaration.height != height || declaration.ascent != ascent) {
                        throw new IOException("overlay 字体映射与 YAML 不一致：" + source);
                    }
                    if (!actual.add(key)) {
                        throw new IOException("overlay 字体存在重复 font/char：" + key);
                    }
                }
            }
        }

        Set<String> expectedKeys = new HashSet<>();
        for (Map.Entry<String, ImageDeclaration> entry : expectedByKey.entrySet()) {
            ImageDeclaration declaration = entry.getValue();
            if (declaration.font.equals(font)) {
                expectedKeys.add(entry.getKey());
            }
        }
        if (expectedKeys.isEmpty() || !actual.equals(expectedKeys)) {
            Set<String> missing = new HashSet<>(expectedKeys);
            missing.removeAll(actual);
            Set<String> extra = new HashSet<>(actual);
            extra.removeAll(expectedKeys);
            throw new IOException("overlay 字体映射集合不一致：" + source
                + "，缺失=" + missing + "，多余=" + extra);
        }
        return actual;
    }

    private String fontIdFromAssetPath(String relative, String source) throws IOException {
        if (!relative.startsWith("assets/") || !relative.endsWith(".json")) {
            throw new IOException("overlay 字体路径无效：" + source);
        }
        int namespaceStart = "assets/".length();
        int namespaceEnd = relative.indexOf('/', namespaceStart);
        String fontRoot = "/font/";
        int fontStart = relative.indexOf(fontRoot, namespaceEnd);
        if (namespaceEnd < 0 || fontStart < 0) {
            throw new IOException("overlay 字体路径无效：" + source);
        }
        String namespace = relative.substring(namespaceStart, namespaceEnd);
        String path = relative.substring(fontStart + fontRoot.length(), relative.length() - ".json".length());
        if (namespace.isBlank() || path.isBlank()) {
            throw new IOException("overlay 字体路径无效：" + source);
        }
        return namespace + ":" + path;
    }

    private void validateEntryName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0
            || name.startsWith("/") || name.indexOf('\\') >= 0) {
            throw new IOException("资源包含无效 ZIP 条目名称，无法验证：" + name);
        }
        String[] segments = name.split("/");
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("资源包含不安全 ZIP 条目路径，无法验证：" + name);
            }
        }
    }

    private boolean isSelected(String name) {
        return PACK_META.equals(name)
            || (name.startsWith(FONT_ROOT + "muz_counter") && name.endsWith(".json"))
            || isCounterTexturePath(name)
            || isGadgetResourcePath(name);
    }

    private boolean isGadgetResourcePath(String name) {
        for (String id : GADGET_ITEM_IDS) {
            if (name.equals("assets/muz/items/" + id + ".json")
                || name.equals("assets/muz/models/item/" + id + ".json")
                || name.equals("assets/muz/textures/item/" + id + ".png")) {
                return true;
            }
        }
        return false;
    }

    private Set<String> requiredEntries(Map<String, ImageDeclaration> bundle) throws IOException {
        Set<String> required = new LinkedHashSet<>();
        required.add(PACK_META);
        required.addAll(rootFontJsonPaths(bundle).values());
        required.addAll(bundleTexturePaths(bundle));
        for (String id : GADGET_ITEM_IDS) {
            required.add("assets/muz/items/" + id + ".json");
            required.add("assets/muz/models/item/" + id + ".json");
            required.add("assets/muz/textures/item/" + id + ".png");
        }
        return required;
    }

    private Map<String, String> rootFontJsonPaths(Map<String, ImageDeclaration> declarations) throws IOException {
        Map<String, String> paths = new LinkedHashMap<>();
        for (ImageDeclaration declaration : declarations.values()) {
            String path = fontJsonPath(declaration.font, declaration.source);
            String previous = paths.putIfAbsent(declaration.font, path);
            if (previous != null && !previous.equals(path)) {
                throw new IOException("字体 JSON 路径重复定义：" + declaration.font);
            }
        }
        return paths;
    }

    private Set<String> bundleTexturePaths(Map<String, ImageDeclaration> declarations) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        for (ImageDeclaration declaration : declarations.values()) {
            paths.add(textureZipPath(declaration.file, declaration.source));
        }
        return paths;
    }

    private String fontJsonPath(String font, String source) throws IOException {
        int separator = font.indexOf(':');
        if (separator <= 0 || separator != font.lastIndexOf(':')) {
            throw new IOException("字体 ID 无效：" + font + "（" + source + "）");
        }
        String namespace = font.substring(0, separator);
        String path = font.substring(separator + 1);
        if (namespace.isBlank() || path.isBlank() || path.startsWith("/") || path.contains("..")
            || path.contains("\\")) {
            throw new IOException("字体 ID 路径不安全：" + font + "（" + source + "）");
        }
        return "assets/" + namespace + "/font/" + path + ".json";
    }

    private String textureZipPath(String file, String source) throws IOException {
        int separator = file.indexOf(':');
        if (separator <= 0 || separator != file.lastIndexOf(':')) {
            throw new IOException("贴图资源 ID 无效：" + file + "（" + source + "）");
        }
        String namespace = file.substring(0, separator);
        String path = file.substring(separator + 1);
        if (namespace.isBlank() || path.isBlank() || path.startsWith("/") || !path.startsWith("font/")
            || path.contains("..") || path.contains("\\")) {
            throw new IOException("贴图资源路径不安全：" + file + "（" + source + "）");
        }
        return "assets/" + namespace + "/textures/" + path;
    }

    private ExpectedDeclarations loadExpectedDeclarations(int offsetY, Integer overlayScale) throws IOException {
        validateGeneratedScaleSet(PackAssets.COUNTER_SCALE_TIERS, "counter");
        Map<String, ImageDeclaration> bundle = new LinkedHashMap<>();
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            String resourcePath = scale == PackAssets.COUNTER_DEFAULT_SCALE
                ? COUNTER_YAML : "craftengine/muz/configuration/images/counter_s" + scale + ".yml";
            collectImageDeclarations(loadYaml(resourcePath), bundle, resourcePath);
        }
        Map<String, ImageDeclaration> overlay = new LinkedHashMap<>();
        if (bundle.isEmpty()) {
            throw new IOException("内置 HUD YAML 没有字形声明");
        }
        return new ExpectedDeclarations(Map.copyOf(bundle), Map.copyOf(overlay));
    }

    /** 资源校验必须以当前 profile 生成的集合为边界，不能把旧版全量档位当成合法输入。 */
    private void validateGeneratedScaleSet(int[] scales, String family) throws IOException {
        if (scales.length == 0) {
            throw new IOException(family + " 当前 profile 没有生成任何 scale");
        }
        Set<Integer> unique = new HashSet<>();
        for (int scale : scales) {
            if (scale <= 0 || !unique.add(scale)) {
                throw new IOException(family + " 当前 profile 的 scale 集合无效：" + Arrays.toString(scales));
            }
        }
    }

    private Map<String, Object> loadYaml(String resourcePath) throws IOException {
        InputStream stream;
        try {
            stream = resourceLoader.apply(resourcePath);
        } catch (RuntimeException exception) {
            throw new IOException("加载内置 YAML 失败：" + resourcePath, exception);
        }
        if (stream == null) {
            throw new IOException("内置资源缺失：" + resourcePath);
        }
        try (stream) {
            byte[] bytes = readBounded(stream, -1, MAX_ENTRY_UNCOMPRESSED_BYTES, resourcePath);
            return parseYaml(new String(bytes, StandardCharsets.UTF_8), resourcePath);
        }
    }

    private Map<String, Object> parseYaml(String text, String source) throws IOException {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setAllowRecursiveKeys(false);
            options.setMaxAliasesForCollections(MAX_YAML_ALIASES);
            options.setNestingDepthLimit(MAX_YAML_DEPTH);
            options.setCodePointLimit(MAX_YAML_CODE_POINTS);
            Object value = new Yaml(new SafeConstructor(options)).load(text);
            if (!(value instanceof Map<?, ?> root)) {
                throw new IOException("YAML 根节点不是映射：" + source);
            }
            Set<Object> visiting = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            return normalizeMap(root, source, visiting);
        } catch (YAMLException exception) {
            throw new IOException("YAML 无法安全解析：" + source + " | " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> normalizeMap(
        Map<?, ?> map,
        String source,
        Set<Object> visiting
    ) throws IOException {
        if (!visiting.add(map)) {
            throw new IOException("YAML 含递归 alias，拒绝解析：" + source);
        }
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IOException("YAML 键不是字符串：" + source);
                }
                if (result.containsKey(key)) {
                    throw new IOException("YAML 存在重复键：" + source + " / " + key);
                }
                result.put(key, normalizeYamlValue(entry.getValue(), source, visiting));
            }
            return result;
        } finally {
            visiting.remove(map);
        }
    }

    private Object normalizeYamlValue(Object value, String source, Set<Object> visiting) throws IOException {
        if (value instanceof Map<?, ?> map) {
            return normalizeMap(map, source, visiting);
        }
        if (value instanceof List<?> list) {
            if (!visiting.add(list)) {
                throw new IOException("YAML 含递归 alias，拒绝解析：" + source);
            }
            try {
                List<Object> result = new ArrayList<>(list.size());
                for (Object item : list) {
                    result.add(normalizeYamlValue(item, source, visiting));
                }
                return result;
            } finally {
                visiting.remove(list);
            }
        }
        return value;
    }

    private void collectImageDeclarations(
        Map<String, Object> root,
        Map<String, ImageDeclaration> target,
        String source
    ) throws IOException {
        Object value = root.get("images");
        if (!(value instanceof Map<?, ?> images)) {
            throw new IOException("YAML 缺少 images 映射：" + source);
        }
        for (Map.Entry<?, ?> entry : images.entrySet()) {
            if (!(entry.getKey() instanceof String id) || !(entry.getValue() instanceof Map<?, ?> fields)) {
                throw new IOException("YAML images 条目格式无效：" + source);
            }
            requireNamespacedImageId(id, source);
            ImageDeclaration declaration = imageDeclaration(id, fields, source);
            if (target.putIfAbsent(id, declaration) != null) {
                throw new IOException("HUD 字形条目重复定义：" + id);
            }
        }
    }

    private void requireNamespacedImageId(String id, String source) throws IOException {
        int separator = id.indexOf(':');
        if (separator <= 0 || separator != id.lastIndexOf(':') || separator == id.length() - 1
            || id.startsWith("/") || id.endsWith("/") || id.contains("..") || id.contains("\\\\")) {
            throw new IOException("YAML image id 必须是安全的 namespace:path 形式：" + id + "（" + source + "）");
        }
    }

    private ImageDeclaration imageDeclaration(String id, Map<?, ?> fields, String source) throws IOException {
        String font = stringField(fields, "font", id, source);
        String file = stringField(fields, "file", id, source);
        int height = intField(fields, "height", id, source);
        int ascent = intField(fields, "ascent", id, source);
        int codepoint = parseCharacter(stringField(fields, "char", id, source), id, source);
        if (height < 0 || height > MAX_ENTRY_UNCOMPRESSED_BYTES || ascent > height) {
            throw new IOException("字形几何字段无效：" + id + "，height=" + height + "，ascent=" + ascent);
        }
        return new ImageDeclaration(id, font, file, height, ascent, codepoint, source);
    }

    private String stringField(Map<?, ?> fields, String name, String id, String source) throws IOException {
        Object value = fields.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IOException("字形缺少字符串字段 " + name + "：" + id + "（" + source + "）");
        }
        return string;
    }

    private int intField(Map<?, ?> fields, String name, String id, String source) throws IOException {
        Object value = fields.get(name);
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()) {
            throw new IOException("字形缺少整数字段 " + name + "：" + id + "（" + source + "）");
        }
        try {
            return Math.toIntExact(number.longValue());
        } catch (ArithmeticException exception) {
            throw new IOException("字形整数字段越界 " + name + "：" + id, exception);
        }
    }

    private int parseCharacter(String value, String id, String source) throws IOException {
        String text = value.trim();
        int codepoint;
        if (text.startsWith("\\u") && text.length() == 6) {
            try {
                codepoint = Integer.parseInt(text.substring(2), 16);
            } catch (NumberFormatException exception) {
                throw new IOException("字形 char 不是有效 \\uXXXX：" + id + "（" + source + "）", exception);
            }
        } else {
            if (text.codePointCount(0, text.length()) != 1) {
                throw new IOException("字形 char 必须只有一个码点：" + id + "（" + source + "）");
            }
            codepoint = text.codePointAt(0);
        }
        if (!Character.isValidCodePoint(codepoint)
            || (codepoint >= Character.MIN_SURROGATE && codepoint <= Character.MAX_SURROGATE)) {
            throw new IOException("字形 char 码位无效：" + id + "（" + source + "）");
        }
        return codepoint;
    }

    private Map<String, ImageDeclaration> mergeDeclarations(Map<String, ImageDeclaration> bundle,
                                                              Map<String, ImageDeclaration> overlay) {
        Map<String, ImageDeclaration> merged = new LinkedHashMap<>(bundle);
        merged.putAll(overlay);
        return merged;
    }

    private void verifyFontJson(ZipIndex zip, Map<String, ImageDeclaration> bundle,
                                Map<String, ImageDeclaration> overlay) throws IOException {
        verifyFontJson(zip, bundle, overlay, Set.of());
    }

    private void verifyFontJson(ZipIndex zip, Map<String, ImageDeclaration> bundle,
                                Map<String, ImageDeclaration> overlay,
                                Set<String> continuousFontPathsToIgnore) throws IOException {
        Map<String, ImageDeclaration> expectedByKey = new HashMap<>();
        Map<String, ImageDeclaration> allowed = mergeDeclarations(bundle, overlay);
        for (ImageDeclaration declaration : allowed.values()) {
            String key = mappingKey(declaration.font, declaration.codepoint);
            if (expectedByKey.putIfAbsent(key, declaration) != null) {
                throw new IOException("内置 HUD YAML 存在重复 font/char：" + key);
            }
        }

        Map<String, String> fontPaths = rootFontJsonPaths(allowed);
        Set<String> expectedPaths = new HashSet<>(fontPaths.values());
        for (String name : zip.names) {
            if (name.startsWith(FONT_ROOT + "muz_counter") && name.endsWith(".json")
                && !expectedPaths.contains(name) && !continuousFontPathsToIgnore.contains(name)) {
                throw new IOException("资源包包含当前 profile 未生成的记牌器字体分页，可能覆盖新码位：" + name);
            }
        }

        Set<String> actualKeys = new HashSet<>();
        for (Map.Entry<String, String> entry : fontPaths.entrySet()) {
            verifyOneFontJson(zip, entry.getValue(), entry.getKey(), expectedByKey, actualKeys);
        }
        Set<String> requiredKeys = new HashSet<>();
        for (ImageDeclaration declaration : bundle.values()) {
            requiredKeys.add(mappingKey(declaration.font, declaration.codepoint));
        }
        Set<String> missing = new HashSet<>(requiredKeys);
        missing.removeAll(actualKeys);
        Set<String> extra = new HashSet<>(actualKeys);
        extra.removeAll(expectedByKey.keySet());
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw new IOException("字体映射集合不一致：缺失=" + missing + "，多余=" + extra);
        }

        verifyCounterGeometry(bundle);
    }

    private void verifyOneFontJson(
        ZipIndex zip,
        String path,
        String font,
        Map<String, ImageDeclaration> expected,
        Set<String> actualKeys
    ) throws IOException {
        try {
            byte[] bytes = zip.selected.get(path);
            if (bytes == null) {
                throw new IOException("资源包缺少字体 JSON：" + path);
            }
            verifyJsonLength(bytes, path);
            JsonObject root = parseJsonObject(bytes, path);
            JsonArray providers = root.getAsJsonArray("providers");
            if (providers == null || providers.isEmpty()) {
                throw new IOException("字体 JSON 缺少 providers：" + path);
            }
            for (JsonElement providerElement : providers) {
                if (!providerElement.isJsonObject()) {
                    throw new IOException("字体 provider 不是对象：" + path);
                }
                JsonObject provider = providerElement.getAsJsonObject();
                String type = stringJson(provider, "type", path);
                if (!"bitmap".equals(type)) {
                    throw new IOException("HUD 字体含不支持的 provider 类型：" + path + " / " + type);
                }
                String file = stringJson(provider, "file", path);
                int height = intJson(provider, "height", path);
                int ascent = intJson(provider, "ascent", path);
                JsonArray chars = provider.getAsJsonArray("chars");
                if (chars == null || chars.isEmpty()) {
                    throw new IOException("位图 provider 缺少 chars：" + path);
                }
                for (JsonElement charElement : chars) {
                    if (!charElement.isJsonPrimitive() || !charElement.getAsJsonPrimitive().isString()) {
                        throw new IOException("字体 chars 含非字符串：" + path);
                    }
                    String charsText = charElement.getAsString();
                    if (charsText.isEmpty()) {
                        throw new IOException("字体 chars 含空字符串：" + path);
                    }
                    for (int index = 0; index < charsText.length();) {
                        int codepoint = charsText.codePointAt(index);
                        index += Character.charCount(codepoint);
                        if (!Character.isValidCodePoint(codepoint)
                            || (codepoint >= Character.MIN_SURROGATE && codepoint <= Character.MAX_SURROGATE)) {
                            throw new IOException("字体 chars 含无效码位：" + path);
                        }
                        String key = mappingKey(font, codepoint);
                        if (!actualKeys.add(key)) {
                            throw new IOException("资源包字体存在重复 font/char：" + key);
                        }
                        ImageDeclaration declaration = expected.get(key);
                        if (declaration == null) {
                            throw new IOException("资源包出现内置 YAML 未声明的字体映射：" + key);
                        }
                        if (!declaration.file.equals(file)
                            || declaration.height != height
                            || declaration.ascent != ascent) {
                            throw new IOException("字体映射与 YAML 不一致：" + declaration.id
                                + "，实际 file=" + file + ", height=" + height + ", ascent=" + ascent);
                        }
                    }
                }
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("字体 JSON 结构无效：" + path, exception);
        }
    }

    private String mappingKey(String font, int codepoint) {
        return font + "\u0000" + codepoint;
    }

    private void verifyCounterGeometry(Map<String, ImageDeclaration> declarations) throws IOException {
        int expectedCount = PackAssets.COUNTER_SCALE_TIERS.length
            * PackAssets.counterDownOffsetTierCount() * PackAssets.COUNTER_GLYPHS_PER_TIER;
        int actualCount = 0;
        for (ImageDeclaration declaration : declarations.values()) {
            if (isCounterFont(declaration.font)) {
                actualCount++;
            }
        }
        if (actualCount != expectedCount) {
            throw new IOException("记牌器字形数量不一致：预期 " + expectedCount + "，实际 " + actualCount);
        }

        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            for (int downTier = 0; downTier < PackAssets.counterDownOffsetTierCount(); downTier++) {
                PackAssets.CounterTier geometry = PackAssets.counterGeometry(scale, downTier);
                for (int index = 0; index < PackAssets.COUNTER_GLYPHS_PER_TIER; index++) {
                    int codepoint = geometry.codepointStart()
                        + downTier * PackAssets.COUNTER_GLYPHS_PER_TIER + index;
                    ImageDeclaration declaration = findDeclaration(declarations, geometry.font(), codepoint);
                    if (declaration == null) {
                        throw new IOException("记牌器缺少码位：" + geometry.font() + " U+" + hex(codepoint));
                    }
                    String file = COUNTER_FILES.get(index);
                    String expectedFile = PackAssets.counterTexturePath(
                        scale, file.substring(0, file.length() - ".png".length()));
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
                    if (!expectedFile.equals(declaration.file)
                        || declaration.height != height || declaration.ascent != ascent) {
                        throw new IOException("记牌器几何或贴图不一致：" + declaration.id
                            + "，预期 file=" + expectedFile + ", height=" + height
                            + ", ascent=" + ascent + "，实际 file=" + declaration.file
                            + ", height=" + declaration.height + ", ascent=" + declaration.ascent);
                    }
                }
            }
        }
    }

    private boolean isCounterFont(String font) {
        if (PackAssets.COUNTER_GLYPH_FONT.equals(font)) {
            return true;
        }
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            if (PackAssets.counterGlyphFont(scale, 0).equals(font)) {
                return true;
            }
        }
        return false;
    }

    private ImageDeclaration findDeclaration(Map<String, ImageDeclaration> declarations, String font, int codepoint) {
        for (ImageDeclaration declaration : declarations.values()) {
            if (font.equals(declaration.font) && codepoint == declaration.codepoint) {
                return declaration;
            }
        }
        return null;
    }

    private void verifyCounterPngs(ZipIndex zip) throws IOException {
        Set<String> expectedPaths = new LinkedHashSet<>();
        for (int scale : PackAssets.COUNTER_SCALE_TIERS) {
            for (String file : COUNTER_FILES) {
                expectedPaths.add(counterTextureZipPath(scale, file));
            }
        }
        for (String name : zip.names) {
            if (isCounterTexturePath(name) && !expectedPaths.contains(name)) {
                throw new IOException("资源包残留旧版或未知记牌器资源：" + name);
            }
        }
        for (String zipPath : expectedPaths) {
            byte[] bytes = requireSelected(zip, zipPath);
            verifyBinaryAgainstBundle(bytes, zipPath);
            verifyPngLength(bytes, zipPath);
            BufferedImage image = readPng(bytes, zipPath);
            verifyCounterPngShape(image, zipPath);
        }
    }

    private String counterTextureZipPath(int scale, String file) throws IOException {
        String stem = file.substring(0, file.length() - ".png".length());
        return textureZipPath(PackAssets.counterTexturePath(scale, stem), "PackAssets counter");
    }

    private void verifyCounterPngShape(BufferedImage image, String source) throws IOException {
        String file = source.substring(source.lastIndexOf('/') + 1);
        int scale = counterScaleFromTexturePath(source);
        PackAssets.CounterTier geometry = PackAssets.counterGeometry(scale, 0);
        int expectedWidth;
        int expectedHeight;
        if (COUNTER_LABEL_FILES.contains(file)) {
            expectedWidth = geometry.labelWidth();
            expectedHeight = geometry.labelHeight();
        } else if (COUNTER_DIGIT_FILES.contains(file)) {
            expectedWidth = geometry.digitWidth();
            expectedHeight = geometry.digitHeight();
        } else if ("frame_normal.png".equals(file) || "frame_exhausted.png".equals(file)) {
            expectedWidth = geometry.frameWidth();
            expectedHeight = geometry.frameHeight();
        } else {
            throw new IOException("未知记牌器 PNG：" + source);
        }
        if (image.getWidth() != expectedWidth || image.getHeight() != expectedHeight) {
            throw new IOException("记牌器 PNG 尺寸不一致：" + source + "，实际 "
                + image.getWidth() + "x" + image.getHeight() + "，预期 "
                + expectedWidth + "x" + expectedHeight);
        }
        if (COUNTER_LABEL_FILES.contains(file) || COUNTER_DIGIT_FILES.contains(file)) {
            int anchor = (image.getRGB(image.getWidth() - 1, image.getHeight() - 1) >>> 24) & 0xFF;
            if (anchor != 1) {
                throw new IOException("记牌器 PNG 缺少右下角 alpha=1 锚点：" + source);
            }
            if (rightmostOpaqueColumn(image) != image.getWidth() - 1) {
                throw new IOException("记牌器 PNG 有效宽度不是声明宽度：" + source);
            }
        }
    }

    private int counterScaleFromTexturePath(String source) throws IOException {
        String relative = source.substring(COUNTER_TEXTURE_ROOT.length());
        if (COUNTER_FILE_SET.contains(relative)) {
            return PackAssets.COUNTER_DEFAULT_SCALE;
        }
        int slash = relative.indexOf('/');
        if (slash <= "scale_".length() || !relative.startsWith("scale_")) {
            throw new IOException("记牌器 PNG 路径无效：" + source);
        }
        try {
            int scale = Integer.parseInt(relative.substring("scale_".length(), slash));
            if (PackAssets.counterScaleTierOf(scale) < 0) {
                throw new IOException("记牌器 PNG scale 不受支持：" + source);
            }
            return scale;
        } catch (NumberFormatException exception) {
            throw new IOException("记牌器 PNG scale 无效：" + source, exception);
        }
    }

    private void verifyGadgetModelsAndItems(ZipIndex zip) throws IOException {
        for (String id : GADGET_ITEM_IDS) {
            String itemPath = "assets/muz/items/" + id + ".json";
            JsonObject item = parseJsonObject(requireSelected(zip, itemPath), itemPath);
            JsonObject modelRef = item.getAsJsonObject("model");
            if (modelRef == null || !"minecraft:model".equals(stringValue(modelRef, "type"))
                || !("muz:item/" + id).equals(stringValue(modelRef, "model"))) {
                throw new IOException("桌内道具 item definition 模型引用不一致：" + itemPath);
            }

            String modelPath = "assets/muz/models/item/" + id + ".json";
            JsonObject model = parseJsonObject(requireSelected(zip, modelPath), modelPath);
            String expectedTexture = "muz:item/" + id;
            JsonObject textures = model.getAsJsonObject("textures");
            if ("table_gadget_speech_bubble".equals(id)) {
                if (!"minecraft:item/generated".equals(stringValue(model, "parent")) || textures == null
                    || !expectedTexture.equals(stringValue(textures, "layer0"))) {
                    throw new IOException("语音气泡 item model 必须使用 minecraft:item/generated + layer0：" + modelPath);
                }
            } else {
                if (textures == null || !expectedTexture.equals(stringValue(textures, "0"))
                    || !expectedTexture.equals(stringValue(textures, "particle"))) {
                    throw new IOException("桌内道具模型材质命名不一致：" + modelPath);
                }
            }

            String texturePath = "assets/muz/textures/item/" + id + ".png";
            byte[] textureBytes = requireSelected(zip, texturePath);
            verifyBinaryAgainstBundle(textureBytes, texturePath);
            verifyPngLength(textureBytes, texturePath);
            BufferedImage texture = readPng(textureBytes, texturePath);
            if (texture.getWidth() != 16 || texture.getHeight() != 16) {
                throw new IOException("桌内道具材质必须为 16x16：" + texturePath);
            }
            if ("table_gadget_water_sheet".equals(id)) {
                boolean translucent = false;
                boolean transparent = false;
                for (int y = 0; y < texture.getHeight(); y++) {
                    for (int x = 0; x < texture.getWidth(); x++) {
                        int alpha = (texture.getRGB(x, y) >>> 24) & 0xFF;
                        translucent |= alpha > 0 && alpha < 255;
                        transparent |= alpha == 0;
                    }
                }
                if (!translucent || transparent) {
                    throw new IOException("水幕道具材质必须是连续半透明薄片：" + texturePath);
                }
            }
        }
    }

    private List<Double> numericFormats(JsonElement value, String key) throws IOException {
        List<Double> formats = new ArrayList<>();
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            formats.add(value.getAsDouble());
            return formats;
        }
        if (!value.isJsonArray() || value.getAsJsonArray().isEmpty()) {
            throw new IOException("pack.mcmeta 缺少有效 pack." + key);
        }
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
                throw new IOException("pack.mcmeta 缺少有效 pack." + key);
            }
            formats.add(item.getAsDouble());
        }
        return formats;
    }

    private List<Integer> formatTuple(JsonElement value, String key) throws IOException {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 2) {
            throw new IOException("pack.mcmeta 缺少有效 pack." + key);
        }
        List<Integer> tuple = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()
                || item.getAsInt() < 0 || item.getAsDouble() != item.getAsInt()) {
                throw new IOException("pack.mcmeta 缺少有效 pack." + key);
            }
            tuple.add(item.getAsInt());
        }
        while (tuple.size() < 2) {
            tuple.add(0);
        }
        return tuple;
    }

    private int compareFormat(List<Integer> left, List<Integer> right) {
        int major = Integer.compare(left.get(0), right.get(0));
        return major != 0 ? major : Integer.compare(left.get(1), right.get(1));
    }

    private boolean isSupportedPackFormat(double format) {
        for (int allowed : PackAssets.SUPPORTED_RESOURCE_PACK_FORMATS) {
            if (format == allowed) {
                return true;
            }
        }
        return false;
    }

    private String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    private void verifyPackMetadata(ZipIndex zip) throws IOException {
        try {
            JsonObject root = parseJsonObject(requireSelected(zip, PACK_META), PACK_META);
            JsonElement packValue = root.get("pack");
            if (packValue == null || !packValue.isJsonObject()) {
                throw new IOException("pack.mcmeta 缺少有效 pack 对象");
            }
            JsonObject pack = packValue.getAsJsonObject();
            boolean supported;
            JsonElement packFormatValue = pack.get("pack_format");
            if (packFormatValue != null) {
                // 旧格式：pack_format 可以是单个数字，也可以是兼容多个客户端的数字数组。
                List<Double> packFormats = numericFormats(packFormatValue, "pack_format");
                supported = packFormats.stream().anyMatch(this::isSupportedPackFormat);
                if (!supported) {
                    throw new IOException("pack.mcmeta pack_format 不受支持：" + packFormats);
                }
            } else {
                // 新格式：Minecraft/CraftEngine 可能只写 min_format/max_format，不能再强制要求旧字段。
                List<Integer> minFormat = formatTuple(pack.get("min_format"), "min_format");
                List<Integer> maxFormat = formatTuple(pack.get("max_format"), "max_format");
                if (minFormat.isEmpty() || maxFormat.isEmpty() || compareFormat(minFormat, maxFormat) > 0) {
                    throw new IOException("pack.mcmeta 缺少有效 pack.pack_format 或 min_format/max_format");
                }
                supported = false;
                for (int allowed : PackAssets.SUPPORTED_RESOURCE_PACK_FORMATS) {
                    if (allowed >= minFormat.get(0) && allowed <= maxFormat.get(0)) {
                        supported = true;
                        break;
                    }
                }
                if (!supported) {
                    throw new IOException("pack.mcmeta min_format/max_format 不受支持："
                        + minFormat + ".." + maxFormat);
                }
            }
            if (!root.has("overlays")) {
                return;
            }
            JsonElement overlays = root.get("overlays");
            if (!overlays.isJsonObject()) {
                throw new IOException("pack.mcmeta overlays 不是对象");
            }
            JsonObject object = overlays.getAsJsonObject();
            for (String key : List.of("entries", "formats")) {
                if (!object.has(key)) {
                    continue;
                }
                JsonElement value = object.get(key);
                if (!value.isJsonArray()) {
                    throw new IOException("pack.mcmeta overlays." + key + " 不是数组");
                }
                for (JsonElement item : value.getAsJsonArray()) {
                    if (!item.isJsonObject()) {
                        throw new IOException("pack.mcmeta overlays." + key + " 含非对象");
                    }
                    JsonObject overlay = item.getAsJsonObject();
                    JsonElement directoryValue = overlay.get("directory");
                    if (directoryValue == null || !directoryValue.isJsonPrimitive()
                        || !directoryValue.getAsJsonPrimitive().isString()) {
                        throw new IOException("pack.mcmeta overlay 缺少 directory");
                    }
                    String directory = directoryValue.getAsString();
                    if (directory.isBlank() || directory.startsWith("/") || directory.contains("..")) {
                        throw new IOException("pack.mcmeta overlay directory 不安全：" + directory);
                    }
                }
            }
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("pack.mcmeta 结构无效", exception);
        }
    }

    private byte[] requireSelected(ZipIndex zip, String path) throws IOException {
        byte[] bytes = zip.selected.get(path);
        if (bytes == null) {
            throw new IOException("资源包缺少必需条目：" + path);
        }
        return bytes;
    }

    private void verifyBinaryAgainstBundle(byte[] bytes, String zipPath) throws IOException {
        String bundlePath = BUNDLE_RESOURCE_ROOT + zipPath;
        InputStream stream;
        try {
            stream = resourceLoader.apply(bundlePath);
        } catch (RuntimeException exception) {
            throw new IOException("加载内置资源失败：" + bundlePath, exception);
        }
        if (stream == null) {
            throw new IOException("内置资源缺失，无法比较哈希：" + bundlePath);
        }
        byte[] expected;
        try (stream) {
            expected = readBounded(stream, -1, MAX_PNG_BYTES, bundlePath);
        }
        String actualHash = sha256(bytes);
        String expectedHash = sha256(expected);
        if (!actualHash.equals(expectedHash) || bytes.length != expected.length) {
            throw new IOException("资源哈希不一致：" + zipPath + "，实际 SHA-256=" + actualHash
                + "，预期 SHA-256=" + expectedHash);
        }
    }

    private BufferedImage readPng(byte[] bytes, String source) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(stream);
            if (image == null) {
                throw new IOException("PNG 无法解码：" + source);
            }
            return image;
        }
    }

    private int rightmostOpaqueColumn(BufferedImage image) {
        int rightmost = -1;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    rightmost = x;
                }
            }
        }
        return rightmost;
    }

    private void verifyPngLength(byte[] bytes, String source) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_PNG_BYTES) {
            throw new IOException("PNG 条目长度无效：" + source + " = " + bytes.length);
        }
    }

    private void verifyJsonLength(byte[] bytes, String source) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_JSON_BYTES) {
            throw new IOException("字体 JSON 条目长度无效：" + source + " = " + bytes.length);
        }
    }

    private JsonObject parseJsonObject(byte[] bytes, String source) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_JSON_BYTES) {
            throw new IOException("JSON 条目长度无效：" + source + " = " + bytes.length);
        }
        try {
            JsonElement element = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw new IOException("JSON 根节点不是对象：" + source);
            }
            return element.getAsJsonObject();
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("JSON 无法解析：" + source, exception);
        }
    }

    private String stringJson(JsonObject object, String field, String source) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("JSON 缺少字符串字段 " + field + "：" + source);
        }
        return value.getAsString();
    }

    private int intJson(JsonObject object, String field, String source) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("JSON 缺少数字字段 " + field + "：" + source);
        }
        try {
            int result = value.getAsInt();
            if (value.getAsDouble() != result) {
                throw new IOException("JSON 数字字段不是整数 " + field + "：" + source);
            }
            return result;
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("JSON 数字字段无效 " + field + "：" + source, exception);
        }
    }

    private byte[] readBounded(InputStream stream, long expectedSize, long limit, String source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
            expectedSize > 0 && expectedSize <= Integer.MAX_VALUE ? (int) expectedSize : 8192);
        byte[] buffer = new byte[8192];
        long total = 0L;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > limit || total > Integer.MAX_VALUE) {
                throw new IOException("条目读取超限：" + source);
            }
            output.write(buffer, 0, read);
        }
        if (expectedSize >= 0 && total != expectedSize) {
            throw new IOException("条目实际长度与中央目录不一致：" + source);
        }
        return output.toByteArray();
    }

    private String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                text.append(String.format("%02x", value & 0xFF));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("JDK 缺少 SHA-256 实现", exception);
        }
    }

    private String hex(int codepoint) {
        return String.format("%04X", codepoint);
    }

    private record ExpectedDeclarations(
        Map<String, ImageDeclaration> bundle,
        Map<String, ImageDeclaration> overlay
    ) {
    }

    private static final class ZipIndex {
        private final Path path;
        private final Map<String, EntryMetadata> entries;
        private final Set<String> names;
        private final Map<String, byte[]> selected = new LinkedHashMap<>();
        private final Set<String> overlayPrefixes = new LinkedHashSet<>();

        private ZipIndex(Path path, Map<String, EntryMetadata> entries, Set<String> names) {
            this.path = path;
            this.entries = Map.copyOf(entries);
            this.names = Set.copyOf(names);
        }
    }

    private record EntryMetadata(long uncompressedSize, long compressedSize, long crc, int method) {
    }

    private final class Budget {
        private long total;

        private void add(long amount, String source) throws IOException {
            if (amount < 0) {
                throw new IOException("条目长度无效：" + source);
            }
            try {
                total = Math.addExact(total, amount);
            } catch (ArithmeticException exception) {
                throw new IOException("HUD 条目累计解压长度溢出，无法验证", exception);
            }
            if (total > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw new IOException("HUD 相关条目累计解压长度超限，无法验证：" + total);
            }
        }
    }

    private record ImageDeclaration(
        String id,
        String font,
        String file,
        int height,
        int ascent,
        int codepoint,
        String source
    ) {
    }
}
