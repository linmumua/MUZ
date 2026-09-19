package linmumua.doudizhu.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Minecraft 字体 advance 的离线快照。
 *
 * <p>load 只做 ZIP/JSON/PNG/unihex 解析；measure 不触碰文件系统、ZIP 或 Bukkit。
 * 同一字体中前面的 provider 优先，高包 provider 放在低包 provider 前面；无法安全
 * 确定宽度时返回 empty，而不是猜一个字体宽度。
 */
public final class HotbarFontMetrics {
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_FONT_DEPTH = 32;
    private static final int MAX_PROVIDER_COUNT = 16_384;

    private final Map<String, List<Provider>> fonts;
    private final boolean metadataFilterUnknown;

    private HotbarFontMetrics(Map<String, List<Provider>> fonts, boolean metadataFilterUnknown) {
        this.fonts = deepUnmodifiable(fonts);
        this.metadataFilterUnknown = metadataFilterUnknown;
    }

    public static HotbarFontMetrics load(InputStream vanillaArchive, Path actualPack, int packFormat)
        throws IOException {
        if (vanillaArchive == null || actualPack == null) {
            throw new NullPointerException("字体归档和实际资源包不能为空");
        }
        Archive vanilla = readVanillaArchive(vanillaArchive);
        Archive actual = readActualArchive(actualPack);

        Map<String, byte[]> resources = new HashMap<>(vanilla.entries);
        List<Layer> actualLayers = actual.layers(packFormat);
        for (Layer layer : actualLayers) {
            resources.putAll(layer.entries);
            resources.putAll(layer.resourceAliases);
        }

        Map<String, List<Provider>> merged = new HashMap<>();
        mergeFonts(merged, vanilla.entries, resources);
        for (Layer layer : actualLayers) {
            mergeFonts(merged, layer.entries, resources);
        }
        return new HotbarFontMetrics(merged, vanilla.metadataFilterUnknown || actual.metadataFilterUnknown);
    }

    /**
     * 测量 Minecraft 最终字体宽度。返回值对应 Font.width 的整数容器契约：
     * 内部保留 provider 的小数 advance，只有整段总和为非负有限整数时才返回。
     */
    public OptionalInt measure(Component component) {
        if (component == null || metadataFilterUnknown || !(component instanceof TextComponent)) {
            return OptionalInt.empty();
        }
        Set<Integer> widths = new HashSet<>();
        for (boolean uniform : new boolean[] {false, true}) {
            for (boolean jp : new boolean[] {false, true}) {
                OptionalDouble width = measureComponent(component, "minecraft:default", false,
                    uniform, jp, new HashSet<>(), 0);
                if (width.isEmpty() || !Double.isFinite(width.getAsDouble()) || width.getAsDouble() < 0) {
                    return OptionalInt.empty();
                }
                double value = width.getAsDouble();
                if (value != Math.rint(value) || value > Integer.MAX_VALUE) {
                    return OptionalInt.empty();
                }
                widths.add((int) value);
            }
        }
        return widths.size() == 1 ? OptionalInt.of(widths.iterator().next()) : OptionalInt.empty();
    }

    private OptionalDouble measureComponent(Component component, String inheritedFont, boolean inheritedBold,
                                            boolean uniform, boolean jp, Set<String> references, int depth) {
        if (depth > MAX_FONT_DEPTH || !(component instanceof TextComponent text)) {
            return OptionalDouble.empty();
        }
        String font = inheritedFont;
        Key styleFont = component.style().font();
        if (styleFont != null) {
            font = normalizeKey(styleFont.asString());
        }
        boolean bold = inheritedBold;
        TextDecoration.State boldState = component.style().decoration(TextDecoration.BOLD);
        if (boldState == TextDecoration.State.TRUE) {
            bold = true;
        } else if (boldState == TextDecoration.State.FALSE) {
            bold = false;
        }

        double total = 0.0;
        for (int index = 0; index < text.content().length();) {
            int codePoint = text.content().codePointAt(index);
            OptionalDouble width = measureGlyph(font, codePoint, bold, uniform, jp, references, depth);
            if (width.isEmpty()) {
                return OptionalDouble.empty();
            }
            total += width.getAsDouble();
            if (!Double.isFinite(total) || total < 0.0) {
                return OptionalDouble.empty();
            }
            index += Character.charCount(codePoint);
        }
        for (Component child : component.children()) {
            OptionalDouble width = measureComponent(child, font, bold, uniform, jp, references, depth + 1);
            if (width.isEmpty()) {
                return OptionalDouble.empty();
            }
            total += width.getAsDouble();
            if (!Double.isFinite(total) || total < 0.0) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.of(total);
    }

    private OptionalDouble measureGlyph(String font, int codePoint, boolean bold, boolean uniform, boolean jp,
                                        Set<String> references, int depth) {
        List<Provider> providers = fonts.get(font);
        if (providers == null) {
            return OptionalDouble.empty();
        }
        for (Provider provider : providers) {
            // empty 仅表示已知 provider 没有此字符；NaN 表示能力未知，必须穿透 reference，
            // 不能把“无法判断是否含有字符”当作缺字而采用后置 provider 的宽度。
            if (provider.filter().unknown) {
                return OptionalDouble.of(Double.NaN);
            }
            if (!provider.filter().matches(uniform, jp)) {
                continue;
            }
            OptionalDouble width = provider.measure(this, codePoint, bold, uniform, jp, references, depth + 1);
            if (width.isPresent()) {
                double value = width.getAsDouble();
                return Double.isFinite(value) && value >= 0.0
                    ? width : OptionalDouble.of(Double.NaN);
            }
        }
        return OptionalDouble.empty();
    }

    private OptionalDouble measureReference(String target, int codePoint, boolean bold, boolean uniform, boolean jp,
                                            Set<String> references, int depth) {
        if (depth > MAX_FONT_DEPTH || !references.add(target)) {
            return OptionalDouble.empty();
        }
        try {
            return measureGlyph(target, codePoint, bold, uniform, jp, references, depth + 1);
        } finally {
            references.remove(target);
        }
    }

    private interface Provider {
        Filter filter();

        OptionalDouble measure(HotbarFontMetrics owner, int codePoint, boolean bold, boolean uniform, boolean jp,
                               Set<String> references, int depth);
    }

    private static final class BitmapProvider implements Provider {
        private final Filter filter;
        private final Map<Integer, Integer> advances;

        private BitmapProvider(Filter filter, Map<Integer, Integer> advances) {
            this.filter = filter;
            this.advances = advances;
        }

        @Override
        public Filter filter() {
            return filter;
        }

        @Override
        public OptionalDouble measure(HotbarFontMetrics owner, int codePoint, boolean bold, boolean uniform,
                                      boolean jp, Set<String> references, int depth) {
            Integer advance = advances.get(codePoint);
            return advance == null ? OptionalDouble.empty()
                : OptionalDouble.of(advance + (bold ? 1.0 : 0.0));
        }
    }

    private static final class SpaceProvider implements Provider {
        private final Filter filter;
        private final Map<Integer, Double> advances;

        private SpaceProvider(Filter filter, Map<Integer, Double> advances) {
            this.filter = filter;
            this.advances = advances;
        }

        @Override
        public Filter filter() {
            return filter;
        }

        @Override
        public OptionalDouble measure(HotbarFontMetrics owner, int codePoint, boolean bold, boolean uniform,
                                      boolean jp, Set<String> references, int depth) {
            Double advance = advances.get(codePoint);
            if (advance == null || !Double.isFinite(advance) || advance < 0.0) {
                return OptionalDouble.empty();
            }
            double result = advance + (bold ? 1.0 : 0.0);
            return Double.isFinite(result) && result >= 0.0 ? OptionalDouble.of(result) : OptionalDouble.empty();
        }
    }

    private static final class ReferenceProvider implements Provider {
        private final Filter filter;
        private final String target;

        private ReferenceProvider(Filter filter, String target) {
            this.filter = filter;
            this.target = target;
        }

        @Override
        public Filter filter() {
            return filter;
        }

        @Override
        public OptionalDouble measure(HotbarFontMetrics owner, int codePoint, boolean bold, boolean uniform,
                                      boolean jp, Set<String> references, int depth) {
            return owner.measureReference(target, codePoint, bold, uniform, jp, references, depth);
        }
    }

    private static final class UnihexProvider implements Provider {
        private final Filter filter;
        private final Map<Integer, Double> advances;

        private UnihexProvider(Filter filter, Map<Integer, Double> advances) {
            this.filter = filter;
            this.advances = advances;
        }

        @Override
        public Filter filter() {
            return filter;
        }

        @Override
        public OptionalDouble measure(HotbarFontMetrics owner, int codePoint, boolean bold, boolean uniform,
                                      boolean jp, Set<String> references, int depth) {
            Double advance = advances.get(codePoint);
            if (advance == null || !Double.isFinite(advance) || advance < 0.0) {
                return OptionalDouble.empty();
            }
            double result = advance + (bold ? 0.5 : 0.0);
            return Double.isFinite(result) && result >= 0.0 ? OptionalDouble.of(result) : OptionalDouble.empty();
        }
    }

    private static final class UnsupportedProvider implements Provider {
        private final Filter filter;

        private UnsupportedProvider(Filter filter) {
            this.filter = filter;
        }

        @Override
        public Filter filter() {
            return filter;
        }

        @Override
        public OptionalDouble measure(HotbarFontMetrics owner, int codePoint, boolean bold, boolean uniform,
                                      boolean jp, Set<String> references, int depth) {
            return OptionalDouble.of(Double.NaN);
        }
    }

    private static final class Filter {
        private static final Filter NONE = new Filter(null, null, false);
        private static final Filter UNKNOWN = new Filter(null, null, true);
        private final Boolean uniform;
        private final Boolean jp;
        private final boolean unknown;

        private Filter(Boolean uniform, Boolean jp, boolean unknown) {
            this.uniform = uniform;
            this.jp = jp;
            this.unknown = unknown;
        }

        private boolean matches(boolean uniformValue, boolean jpValue) {
            return !unknown
                && (uniform == null || uniform == uniformValue)
                && (jp == null || jp == jpValue);
        }
    }

    private static void mergeFonts(Map<String, List<Provider>> merged, Map<String, byte[]> layer,
                                   Map<String, byte[]> resources) throws IOException {
        List<String> names = new ArrayList<>();
        for (String name : layer.keySet()) {
            if (name.startsWith("assets/") && name.contains("/font/") && name.endsWith(".json")) {
                names.add(name);
            }
        }
        Collections.sort(names);
        int providerCount = 0;
        for (String name : names) {
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(new String(layer.get(name), StandardCharsets.UTF_8));
            } catch (RuntimeException ex) {
                throw new IOException("字体 JSON 无法解析: " + name, ex);
            }
            if (!parsed.isJsonObject()) {
                throw new IOException("字体 JSON 根节点不是对象: " + name);
            }
            JsonElement providersElement = parsed.getAsJsonObject().get("providers");
            if (providersElement == null || !providersElement.isJsonArray()) {
                continue;
            }
            List<Provider> providers = new ArrayList<>();
            for (JsonElement element : providersElement.getAsJsonArray()) {
                providers.add(parseProvider(element, resources));
                if (++providerCount > MAX_PROVIDER_COUNT) {
                    throw new IOException("字体 provider 数量超过限制");
                }
            }
            String key = fontKey(name);
            List<Provider> old = merged.getOrDefault(key, List.of());
            List<Provider> combined = new ArrayList<>(providers.size() + old.size());
            combined.addAll(providers);
            combined.addAll(old);
            merged.put(key, combined);
        }
    }

    private static Provider parseProvider(JsonElement element, Map<String, byte[]> resources) {
        if (!element.isJsonObject()) {
            return new UnsupportedProvider(Filter.NONE);
        }
        JsonObject object = element.getAsJsonObject();
        Filter filter = parseFilter(object.get("filter"));
        String type = string(object, "type");
        try {
            Provider provider = switch (type == null ? "" : type) {
                case "bitmap" -> parseBitmap(object, filter, resources);
                case "space" -> parseSpace(object, filter);
                case "reference" -> {
                    String id = string(object, "id");
                    yield id == null ? null : new ReferenceProvider(filter, normalizeKey(id));
                }
                case "unihex" -> parseUnihex(object, filter, resources);
                default -> null;
            };
            return provider == null ? new UnsupportedProvider(filter) : provider;
        } catch (RuntimeException ignored) {
            return new UnsupportedProvider(filter);
        }
    }

    private static BitmapProvider parseBitmap(JsonObject object, Filter filter,
                                              Map<String, byte[]> resources) {
        String file = string(object, "file");
        int declaredHeight = intValue(object.get("height"), 8);
        JsonArray chars = object.getAsJsonArray("chars");
        if (file == null || declaredHeight <= 0 || chars == null || chars.isEmpty()) {
            return null;
        }
        byte[] imageBytes = resources.get(normalizeBitmapResource(file));
        if (imageBytes == null) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return null;
            }
            List<String> rows = new ArrayList<>();
            int columns = 0;
            for (JsonElement row : chars) {
                if (!row.isJsonPrimitive() || !row.getAsJsonPrimitive().isString()) {
                    return null;
                }
                String value = row.getAsString();
                rows.add(value);
                columns = Math.max(columns, value.codePointCount(0, value.length()));
            }
            if (columns == 0 || image.getWidth() % columns != 0 || image.getHeight() % rows.size() != 0) {
                return null;
            }
            int tileWidth = image.getWidth() / columns;
            int tileHeight = image.getHeight() / rows.size();
            Map<Integer, Integer> advances = new HashMap<>();
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                String row = rows.get(rowIndex);
                int columnIndex = 0;
                for (int index = 0; index < row.length();) {
                    int codePoint = row.codePointAt(index);
                    int actualWidth = effectiveWidth(image, columnIndex * tileWidth, rowIndex * tileHeight,
                        tileWidth, tileHeight);
                    int advance = (int) (actualWidth * ((double) declaredHeight / tileHeight) + 0.5D) + 1;
                    if (advance < 0) {
                        return null;
                    }
                    advances.put(codePoint, advance);
                    columnIndex++;
                    index += Character.charCount(codePoint);
                }
            }
            return new BitmapProvider(filter, advances);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static int effectiveWidth(BufferedImage image, int left, int top, int width, int height) {
        boolean hasAlpha = image.getColorModel().hasAlpha();
        int right = -1;
        for (int y = top; y < top + height; y++) {
            for (int x = left + width - 1; x >= left; x--) {
                if (!hasAlpha || ((image.getRGB(x, y) >>> 24) & 0xFF) != 0) {
                    right = Math.max(right, x - left);
                    break;
                }
            }
        }
        return right + 1;
    }

    private static SpaceProvider parseSpace(JsonObject object, Filter filter) {
        JsonObject advancesObject = object.getAsJsonObject("advances");
        if (advancesObject == null) {
            return null;
        }
        Map<Integer, Double> advances = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : advancesObject.entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                continue;
            }
            advances.put(entry.getKey().codePointAt(0), value.getAsDouble());
        }
        return new SpaceProvider(filter, advances);
    }

    private static UnihexProvider parseUnihex(JsonObject object, Filter filter,
                                              Map<String, byte[]> resources) {
        String file = string(object, "hex_file");
        if (file == null) {
            file = string(object, "file");
        }
        if (file == null || (string(object, "hex_size") != null
            && !string(object, "hex_size").startsWith("16"))) {
            return null;
        }
        byte[] data = resources.get(normalizeHexResource(file));
        if (data == null) {
            return null;
        }
        Map<Integer, int[]> bounds = parseUnihexData(data, file);
        JsonArray overrides = object.getAsJsonArray("size_overrides");
        if (overrides != null) {
            for (JsonElement element : overrides) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject override = element.getAsJsonObject();
                int from = codePoint(override.get("from"));
                int to = codePoint(override.get("to"));
                int left = intValue(override.get("left"), -1);
                int right = intValue(override.get("right"), -1);
                if (from < 0 || to < from || left < 0 || right < left) {
                    continue;
                }
                for (int codePoint = from; codePoint <= to && codePoint <= Character.MAX_CODE_POINT; codePoint++) {
                    if (bounds.containsKey(codePoint)) {
                        bounds.put(codePoint, new int[] {left, right});
                    }
                }
            }
        }
        Map<Integer, Double> advances = new HashMap<>();
        for (Map.Entry<Integer, int[]> entry : bounds.entrySet()) {
            int width = entry.getValue()[1] - entry.getValue()[0] + 1;
            int baseAdvance = width / 2 + 1;
            advances.put(entry.getKey(), (double) baseAdvance);
        }
        return new UnihexProvider(filter, advances);
    }

    private static Map<Integer, int[]> parseUnihexData(byte[] data, String file) {
        if (file.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return parseUnihexZip(data);
        }
        return parseUnihexText(data);
    }

    private static Map<Integer, int[]> parseUnihexZip(byte[] data) {
        Map<Integer, int[]> result = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase(Locale.ROOT).endsWith(".hex")) {
                    continue;
                }
                byte[] hex = readLimited(zip, MAX_ENTRY_BYTES, MAX_ARCHIVE_BYTES - total);
                total += hex.length;
                result.putAll(parseUnihexText(hex));
            }
        } catch (IOException ignored) {
            return Collections.emptyMap();
        }
        return result;
    }

    private static Map<Integer, int[]> parseUnihexText(byte[] data) {
        Map<Integer, int[]> result = new HashMap<>();
        String text = new String(data, StandardCharsets.US_ASCII);
        for (String line : text.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                int codePoint = Integer.parseInt(line.substring(0, colon), 16);
                String hex = line.substring(colon + 1).trim();
                int rowWidth = switch (hex.length()) {
                    case 32 -> 8;
                    case 64 -> 16;
                    case 96 -> 24;
                    case 128 -> 32;
                    default -> 0;
                };
                if (rowWidth == 0) {
                    continue;
                }
                int digitsPerRow = rowWidth / 4;
                int left = rowWidth;
                int right = -1;
                for (int row = 0; row < 16; row++) {
                    int rowStart = row * digitsPerRow;
                    long bits = Long.parseLong(hex.substring(rowStart, rowStart + digitsPerRow), 16);
                    for (int x = 0; x < rowWidth; x++) {
                        if ((bits & (1L << (rowWidth - 1 - x))) != 0) {
                            left = Math.min(left, x);
                            right = Math.max(right, x);
                        }
                    }
                }
                if (right >= left) {
                    result.put(codePoint, new int[] {left, right});
                }
            } catch (RuntimeException ignored) {
                // 单个坏 glyph 无法安全测量时按缺失处理。
            }
        }
        return result;
    }

    private static Filter parseFilter(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Filter.NONE;
        }
        JsonObject object = element.getAsJsonObject();
        for (String key : object.keySet()) {
            if (!key.equals("uniform") && !key.equals("jp")) {
                return Filter.UNKNOWN;
            }
        }
        Boolean uniform = bool(object.get("uniform"));
        Boolean jp = bool(object.get("jp"));
        boolean unknown = (object.has("uniform") && uniform == null) || (object.has("jp") && jp == null);
        return new Filter(uniform, jp, unknown);
    }

    private static Archive readVanillaArchive(InputStream input) throws IOException {
        Path temporary = Files.createTempFile("muz-font-metrics-", ".zip");
        try {
            try (InputStream source = input; var output = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = source.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_ARCHIVE_BYTES) {
                        throw new IOException("字体归档压缩数据超过大小限制");
                    }
                    output.write(buffer, 0, count);
                }
            }
            return readSelectiveZip(temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Archive readActualArchive(Path path) throws IOException {
        return readSelectiveZip(path);
    }

    private static Archive readSelectiveZip(Path path) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var iterator = zip.entries();
            long total = 0;
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                String name = normalizeEntry(entry.getName());
                if (name == null || entry.isDirectory() || !isFontJsonOrMetadata(name)) {
                    continue;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] data = readLimited(input, MAX_ENTRY_BYTES, MAX_ARCHIVE_BYTES - total);
                    total += data.length;
                    entries.put(name, data);
                }
            }
            Set<String> wanted = referencedResourcePaths(entries);
            iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                String name = normalizeEntry(entry.getName());
                if (name == null || entry.isDirectory() || !wanted.contains(name)) {
                    continue;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    byte[] data = readLimited(input, MAX_ENTRY_BYTES, MAX_ARCHIVE_BYTES - total);
                    total += data.length;
                    entries.put(name, data);
                }
            }
        }
        return new Archive(entries, hasPackFilter(entries.get("pack.mcmeta")));
    }

    private static boolean isFontJsonOrMetadata(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("pack.mcmeta") || lower.contains("/font/") && lower.endsWith(".json");
    }

    private static Set<String> referencedResourcePaths(Map<String, byte[]> entries) {
        Set<String> wanted = new HashSet<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("pack.mcmeta")) {
                continue;
            }
            JsonObject root = jsonObject(entry.getValue());
            if (root == null) {
                continue;
            }
            String prefix = "";
            int overlaySlash = name.indexOf("/assets/");
            if (overlaySlash >= 0) {
                prefix = name.substring(0, overlaySlash + 1);
            }
            collectResourceReferences(root, prefix, wanted);
        }
        return wanted;
    }

    private static void collectResourceReferences(JsonElement element, String prefix, Set<String> wanted) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()
                    && (entry.getKey().equals("file") || entry.getKey().equals("hex_file"))) {
                    String path = entry.getKey().equals("file")
                        ? normalizeBitmapResource(entry.getValue().getAsString())
                        : normalizeHexResource(entry.getValue().getAsString());
                    if (!path.startsWith("/")) {
                        wanted.add(prefix + path);
                    }
                }
                collectResourceReferences(entry.getValue(), prefix, wanted);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectResourceReferences(child, prefix, wanted);
            }
        }
    }

    private static byte[] readLimited(InputStream input, int entryLimit, long remaining) throws IOException {
        if (remaining <= 0) {
            throw new IOException("字体 ZIP 解压后超过大小限制");
        }
        int limit = (int) Math.min(entryLimit, remaining);
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) {
                throw new IOException("字体 ZIP 条目超过大小限制");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private record Archive(Map<String, byte[]> entries, boolean metadataFilterUnknown) {
        private List<Layer> layers(int packFormat) {
            List<Layer> result = new ArrayList<>();
            Map<String, byte[]> base = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (!entry.getKey().startsWith("overlays/")) {
                    base.put(entry.getKey(), entry.getValue());
                }
            }
            result.add(new Layer(base, Map.of()));
            JsonObject meta = jsonObject(entries.get("pack.mcmeta"));
            JsonObject overlaysObject = meta == null ? null : object(meta, "overlays");
            JsonArray overlays = overlaysObject == null ? null : overlaysObject.getAsJsonArray("entries");
            if (overlays == null) {
                return result;
            }
            for (JsonElement element : overlays) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject overlay = element.getAsJsonObject();
                String directory = string(overlay, "directory");
                String normalized = directory == null ? null : normalizeEntry(directory);
                if (normalized == null || !overlayActive(overlay.get("formats"), packFormat)) {
                    continue;
                }
                String prefix = "overlays/" + normalized + "/";
                Map<String, byte[]> layer = new LinkedHashMap<>();
                Map<String, byte[]> aliases = new LinkedHashMap<>();
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    if (entry.getKey().startsWith(prefix)) {
                        String relative = entry.getKey().substring(prefix.length());
                        layer.put(relative, entry.getValue());
                        if (relative.startsWith("assets/")) {
                            aliases.put(relative, entry.getValue());
                        }
                    }
                }
                result.add(new Layer(layer, aliases));
            }
            return result;
        }
    }

    private record Layer(Map<String, byte[]> entries, Map<String, byte[]> resourceAliases) {
    }

    private static boolean overlayActive(JsonElement formats, int packFormat) {
        if (formats == null || !formats.isJsonObject()) {
            return true;
        }
        JsonObject object = formats.getAsJsonObject();
        int min = intValue(object.get("min_inclusive"), Integer.MIN_VALUE);
        int max = intValue(object.get("max_inclusive"), Integer.MAX_VALUE);
        return packFormat >= min && packFormat <= max;
    }

    private static boolean hasPackFilter(byte[] bytes) {
        JsonObject meta = jsonObject(bytes);
        return meta != null && meta.has("filter");
    }

    private static JsonObject jsonObject(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static Map<String, List<Provider>> deepUnmodifiable(Map<String, List<Provider>> source) {
        Map<String, List<Provider>> copy = new HashMap<>();
        for (Map.Entry<String, List<Provider>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String fontKey(String path) {
        String value = path.substring("assets/".length(), path.length() - ".json".length());
        int slash = value.indexOf("/font/");
        return value.substring(0, slash).toLowerCase(Locale.ROOT) + ":" + value.substring(slash + 6);
    }

    private static String normalizeKey(String key) {
        String value = key.trim();
        return value.contains(":") ? value.toLowerCase(Locale.ROOT) : "minecraft:" + value.toLowerCase(Locale.ROOT);
    }

    private static String normalizeBitmapResource(String value) {
        String path = normalizeResourcePath(value);
        if (path.startsWith("/") || path.contains("..")) {
            return path;
        }
        int assetsEnd = path.indexOf('/', "assets/".length());
        if (assetsEnd < 0) {
            return path;
        }
        String namespacePrefix = path.substring(0, assetsEnd + 1);
        String relative = path.substring(assetsEnd + 1);
        return relative.startsWith("textures/") ? path : namespacePrefix + "textures/" + relative;
    }

    private static String normalizeHexResource(String value) {
        return normalizeResourcePath(value);
    }

    private static String normalizeResourcePath(String value) {
        if (value.startsWith("/") || value.contains("..")) {
            return value;
        }
        int colon = value.indexOf(':');
        return colon < 0 ? "assets/minecraft/" + value : "assets/" + value.substring(0, colon) + "/" + value.substring(colon + 1);
    }

    private static String normalizeEntry(String value) {
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("../") || normalized.equals("..")) {
            return null;
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
            ? element.getAsString() : null;
    }

    private static int intValue(JsonElement element, int fallback) {
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int codePoint(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return -1;
        }
        try {
            String value = element.getAsString();
            if (value.codePointCount(0, value.length()) == 1) {
                return value.codePointAt(0);
            }
            return value.matches("[0-9a-fA-F]+") ? Integer.parseInt(value, 16) : element.getAsInt();
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static Boolean bool(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return element.getAsBoolean();
    }
}
