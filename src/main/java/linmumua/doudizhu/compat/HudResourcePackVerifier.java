package linmumua.doudizhu.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.debug.HotbarDebugOverlayWriter;
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
    private static final String HOTBAR_YAML = "craftengine/muz/configuration/images/hotbar_hud.yml";
    private static final String BUNDLE_RESOURCE_ROOT = "craftengine/muz/resourcepack/";

    private static final String PACK_META = "pack.mcmeta";
    private static final String FONT_ROOT = "assets/minecraft/font/";
    private static final String COUNTER_FONT = "assets/minecraft/font/muz_counter.json";
    private static final String HOTBAR_FONT = "assets/minecraft/font/muz_hotbar.json";
    private static final String COUNTER_TEXTURE_ROOT = "assets/muz/textures/font/counter/";
    private static final String HOTBAR_TEXTURE = "assets/muz/textures/font/hotbar_slots.png";

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
     * @param packPath 资源包路径；验证器不会修改或解包它
     * @param offsetY  本次 hotbar 调试覆盖层采用的垂直偏移
     * @throws IOException 资源缺失、映射错配、ZIP 受损、保护形式不支持或超出限制
     */
    public void verify(Path packPath, int offsetY) throws IOException {
        Objects.requireNonNull(packPath, "packPath");
        if (!Files.isRegularFile(packPath)) {
            throw new IOException("资源包不存在或不是普通文件：" + packPath);
        }

        Map<String, ImageDeclaration> expected = loadExpectedDeclarations(offsetY);
        ZipIndex zip = readZipIndex(packPath, expected);
        verifyFontJson(zip, expected);
        verifyCounterPngs(zip);
        verifyHotbarPng(zip);
        verifyPackMetadata(zip);
    }

    private ZipIndex readZipIndex(Path packPath, Map<String, ImageDeclaration> expected) throws IOException {
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
            for (String required : requiredEntries()) {
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
            verifyOverlayEntries(index, expected);
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
            || relative.equals(HOTBAR_TEXTURE)) {
            return true;
        }
        if (relative.startsWith(COUNTER_TEXTURE_ROOT) && relative.endsWith(".png")) {
            String file = relative.substring(COUNTER_TEXTURE_ROOT.length());
            return COUNTER_FILE_SET.contains(file);
        }
        if (!relative.startsWith(FONT_ROOT) || !relative.endsWith(".json")) {
            return false;
        }
        String fontPath = relative.substring(FONT_ROOT.length(), relative.length() - ".json".length());
        return "muz_counter".equals(fontPath) || "muz_hotbar".equals(fontPath)
            || fontPath.startsWith("muz_counter_") || fontPath.startsWith("muz_hotbar_");
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

    private void verifyOverlayEntries(ZipIndex zip, Map<String, ImageDeclaration> expected) throws IOException {
        Map<String, ImageDeclaration> expectedByKey = new HashMap<>();
        for (ImageDeclaration declaration : expected.values()) {
            expectedByKey.put(mappingKey(declaration.font, declaration.codepoint), declaration);
        }
        Set<String> actualKeys = new HashSet<>();
        for (String prefix : zip.overlayPrefixes) {
            for (String name : zip.names) {
                if (!name.startsWith(prefix) || name.endsWith("/")
                    || !isRelevantOverlayEntry(name, zip.overlayPrefixes)) {
                    continue;
                }
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
                    if (relative.startsWith(COUNTER_TEXTURE_ROOT)) {
                        verifyCounterPngShape(image, relative);
                    } else if (relative.equals(HOTBAR_TEXTURE)) {
                        verifyHotbarPngShape(image, name);
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
    }

    private String overlayRelative(String name, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }
        return null;
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
            if (declaration.font.equals(font) && declaration.id.startsWith("muz_hotbar_debug:")) {
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
            || COUNTER_FONT.equals(name)
            || HOTBAR_FONT.equals(name)
            || (name.startsWith(FONT_ROOT + "muz_counter") && name.endsWith(".json"))
            || (name.startsWith(COUNTER_TEXTURE_ROOT) && name.endsWith(".png"))
            || HOTBAR_TEXTURE.equals(name);
    }

    private Set<String> requiredEntries() {
        Set<String> required = new LinkedHashSet<>();
        required.add(PACK_META);
        required.add(COUNTER_FONT);
        required.add(HOTBAR_FONT);
        required.add(HOTBAR_TEXTURE);
        for (String file : COUNTER_FILES) {
            required.add(COUNTER_TEXTURE_ROOT + file);
        }
        return required;
    }

    private Map<String, ImageDeclaration> loadExpectedDeclarations(int offsetY) throws IOException {
        Map<String, ImageDeclaration> expected = new LinkedHashMap<>();
        collectImageDeclarations(loadYaml(COUNTER_YAML), expected, COUNTER_YAML);
        collectImageDeclarations(loadYaml(HOTBAR_YAML), expected, HOTBAR_YAML);
        String overlayText = HotbarDebugOverlayWriter.buildImagesYaml(offsetY);
        collectImageDeclarations(parseYaml(overlayText, "hotbar 调试覆盖层"), expected, "hotbar 调试覆盖层");
        if (expected.isEmpty()) {
            throw new IOException("内置 HUD YAML 没有字形声明");
        }
        return expected;
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
            ImageDeclaration declaration = imageDeclaration(id, fields, source);
            if (target.putIfAbsent(id, declaration) != null) {
                throw new IOException("HUD 字形条目重复定义：" + id);
            }
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

    private void verifyFontJson(ZipIndex zip, Map<String, ImageDeclaration> expected) throws IOException {
        Map<String, ImageDeclaration> expectedByKey = new HashMap<>();
        for (ImageDeclaration declaration : expected.values()) {
            String key = mappingKey(declaration.font, declaration.codepoint);
            if (expectedByKey.putIfAbsent(key, declaration) != null) {
                throw new IOException("内置 HUD YAML 存在重复 font/char：" + key);
            }
        }

        for (String name : zip.names) {
            if (name.startsWith(FONT_ROOT + "muz_counter_") && name.endsWith(".json")) {
                throw new IOException("资源包残留旧版记牌器字体分页，可能覆盖新码位：" + name);
            }
            if (name.startsWith(FONT_ROOT + "muz_hotbar_") && name.endsWith(".json")) {
                throw new IOException("资源包残留旧版 hotbar 字体分页：" + name);
            }
        }

        Set<String> actualKeys = new HashSet<>();
        verifyOneFontJson(zip, COUNTER_FONT, "minecraft:muz_counter", expectedByKey, actualKeys);
        verifyOneFontJson(zip, HOTBAR_FONT, "minecraft:muz_hotbar", expectedByKey, actualKeys);
        if (!actualKeys.equals(expectedByKey.keySet())) {
            Set<String> missing = new HashSet<>(expectedByKey.keySet());
            missing.removeAll(actualKeys);
            Set<String> extra = new HashSet<>(actualKeys);
            extra.removeAll(expectedByKey.keySet());
            throw new IOException("字体映射集合不一致：缺失=" + missing + "，多余=" + extra);
        }

        verifyCounterGeometry(expected);
        verifyHotbarGeometry(expected);
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
        int expectedCount = PackAssets.avatarDownOffsetTierCount() * PackAssets.COUNTER_GLYPHS_PER_TIER;
        int actualCount = 0;
        for (ImageDeclaration declaration : declarations.values()) {
            if (PackAssets.COUNTER_GLYPH_FONT.equals(declaration.font)) {
                actualCount++;
            }
        }
        if (actualCount != expectedCount) {
            throw new IOException("记牌器字形数量不一致：预期 " + expectedCount + "，实际 " + actualCount);
        }

        for (int tier = 0; tier < PackAssets.avatarDownOffsetTierCount(); tier++) {
            int offset = PackAssets.avatarDownOffsetAt(tier);
            for (int index = 0; index < PackAssets.COUNTER_GLYPHS_PER_TIER; index++) {
                int codepoint = PackAssets.COUNTER_GLYPH_CODEPOINT_START
                    + tier * PackAssets.COUNTER_GLYPHS_PER_TIER + index;
                ImageDeclaration declaration = findDeclaration(declarations, PackAssets.COUNTER_GLYPH_FONT, codepoint);
                if (declaration == null) {
                    throw new IOException("记牌器缺少码位：U+" + hex(codepoint));
                }
                String file = COUNTER_FILES.get(index);
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
                if (!("muz:font/counter/" + file).equals(declaration.file)
                    || declaration.height != height || declaration.ascent != ascent) {
                    throw new IOException("记牌器几何或贴图不一致：" + declaration.id
                        + "，预期 file=muz:font/counter/" + file + ", height=" + height
                        + ", ascent=" + ascent + "，实际 file=" + declaration.file
                        + ", height=" + declaration.height + ", ascent=" + declaration.ascent);
                }
            }
        }
    }

    private ImageDeclaration findDeclaration(Map<String, ImageDeclaration> declarations, String font, int codepoint) {
        for (ImageDeclaration declaration : declarations.values()) {
            if (font.equals(declaration.font) && codepoint == declaration.codepoint) {
                return declaration;
            }
        }
        return null;
    }

    private void verifyHotbarGeometry(Map<String, ImageDeclaration> declarations) throws IOException {
        ImageDeclaration base = declarations.get("muz:hotbar_slots");
        ImageDeclaration debug = declarations.get("muz_hotbar_debug:hotbar_slots_debug");
        if (base == null || debug == null) {
            throw new IOException("缺少 hotbar 基础或调试覆盖层字形声明");
        }
        if (!PackAssets.HOTBAR_HUD_FONT.equals(base.font)
            || base.codepoint != PackAssets.HOTBAR_HUD_CODEPOINT
            || base.height != PackAssets.HOTBAR_HUD_GLYPH_HEIGHT
            || base.ascent != HotbarDebugOverlayWriter.BASE_ASCENT
            || !"muz:font/hotbar_slots.png".equals(base.file)) {
            throw new IOException("hotbar 基础字形与 PackAssets 不一致");
        }
        if (!PackAssets.HOTBAR_HUD_FONT.equals(debug.font)
            || debug.codepoint != PackAssets.HOTBAR_HUD_DEBUG_CODEPOINT
            || debug.height != HotbarDebugOverlayWriter.GLYPH_HEIGHT
            || !"muz:font/hotbar_slots.png".equals(debug.file)) {
            throw new IOException("hotbar 调试覆盖层字形与 PackAssets 不一致");
        }
    }

    private void verifyCounterPngs(ZipIndex zip) throws IOException {
        for (String name : zip.names) {
            if (!name.startsWith(COUNTER_TEXTURE_ROOT) || name.endsWith("/")) {
                continue;
            }
            String file = name.substring(COUNTER_TEXTURE_ROOT.length());
            if (!COUNTER_FILE_SET.contains(file)) {
                throw new IOException("资源包残留旧版或未知记牌器资源：" + name);
            }
        }
        for (String file : COUNTER_FILES) {
            String zipPath = COUNTER_TEXTURE_ROOT + file;
            byte[] bytes = requireSelected(zip, zipPath);
            verifyBinaryAgainstBundle(bytes, zipPath);
            verifyPngLength(bytes, zipPath);
            BufferedImage image = readPng(bytes, zipPath);
            verifyCounterPngShape(image, zipPath);
        }
    }

    private void verifyCounterPngShape(BufferedImage image, String source) throws IOException {
        String file = source.substring(source.lastIndexOf('/') + 1);
        boolean digit = COUNTER_DIGIT_FILES.contains(file);
        int expectedHeight = digit ? PackAssets.COUNTER_DIGIT_HEIGHT : PackAssets.COUNTER_LABEL_HEIGHT;
        if (image.getWidth() != PackAssets.COUNTER_LABEL_WIDTH || image.getHeight() != expectedHeight) {
            throw new IOException("记牌器 PNG 尺寸不一致：" + source + "，实际 "
                + image.getWidth() + "x" + image.getHeight() + "，预期 "
                + PackAssets.COUNTER_LABEL_WIDTH + "x" + expectedHeight);
        }
        if (COUNTER_LABEL_FILES.contains(file) || COUNTER_DIGIT_FILES.contains(file)) {
            int anchor = (image.getRGB(image.getWidth() - 1, image.getHeight() - 1) >>> 24) & 0xFF;
            if (anchor != 1) {
                throw new IOException("记牌器 PNG 缺少右下角 alpha=1 锚点：" + source);
            }
            if (rightmostOpaqueColumn(image) != 32) {
                throw new IOException("记牌器 PNG 有效宽度不是 33px：" + source);
            }
        }
    }

    private void verifyHotbarPngShape(BufferedImage image, String source) throws IOException {
        if (image.getWidth() != PackAssets.HOTBAR_HUD_GLYPH_WIDTH
            || image.getHeight() != PackAssets.HOTBAR_HUD_GLYPH_HEIGHT) {
            throw new IOException("hotbar PNG 尺寸不一致：实际 " + image.getWidth() + "x" + image.getHeight()
                + "，预期 " + PackAssets.HOTBAR_HUD_GLYPH_WIDTH + "x" + PackAssets.HOTBAR_HUD_GLYPH_HEIGHT);
        }
    }

    private void verifyHotbarPng(ZipIndex zip) throws IOException {
        byte[] bytes = requireSelected(zip, HOTBAR_TEXTURE);
        verifyBinaryAgainstBundle(bytes, HOTBAR_TEXTURE);
        verifyPngLength(bytes, HOTBAR_TEXTURE);
        BufferedImage image = readPng(bytes, HOTBAR_TEXTURE);
        if (image.getWidth() != PackAssets.HOTBAR_HUD_GLYPH_WIDTH
            || image.getHeight() != PackAssets.HOTBAR_HUD_GLYPH_HEIGHT) {
            throw new IOException("hotbar PNG 尺寸不一致：实际 " + image.getWidth() + "x" + image.getHeight()
                + "，预期 " + PackAssets.HOTBAR_HUD_GLYPH_WIDTH + "x" + PackAssets.HOTBAR_HUD_GLYPH_HEIGHT);
        }
        int background = 0xFF121216;
        int[] colors = {
            0xFFE03A3A, 0xFFE06A2A, 0xFFE08A2A, 0xFFD8D030, 0xFF3CC050,
            0xFF30C0A8, 0xFF3888E0, 0xFF7050D8, 0xFFC04AA0
        };
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int expected = background;
                boolean slot = y >= 1 && y < 21 && x >= 2 && x < 180 && (x - 2) % 20 < 18;
                if (slot) {
                    expected = colors[(x - 2) / 20];
                }
                if (image.getRGB(x, y) != expected) {
                    throw new IOException("hotbar PNG 像素不一致：(" + x + "," + y + ")");
                }
            }
        }
    }

    private void verifyPackMetadata(ZipIndex zip) throws IOException {
        try {
            JsonObject root = parseJsonObject(requireSelected(zip, PACK_META), PACK_META);
            JsonElement packValue = root.get("pack");
            if (packValue == null || !packValue.isJsonObject()) {
                throw new IOException("pack.mcmeta 缺少有效 pack 对象");
            }
            JsonObject pack = packValue.getAsJsonObject();
            if (!pack.has("pack_format") || !pack.get("pack_format").isJsonPrimitive()
                || !pack.getAsJsonPrimitive("pack_format").isNumber()) {
                throw new IOException("pack.mcmeta 缺少有效 pack.pack_format");
            }
            double packFormat = pack.getAsJsonPrimitive("pack_format").getAsDouble();
            if (packFormat != 84 && packFormat != 88) {
                throw new IOException("pack.mcmeta pack_format 不受支持：" + packFormat);
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
