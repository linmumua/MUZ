package linmumua.doudizhu.debug;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.HudOverlayLayout;
import linmumua.doudizhu.assets.HudResourceRequest;
import org.bukkit.plugin.Plugin;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * 三层 Trick HUD 连续覆盖层的统一事务写入器。
 *
 * <p>布局、码位、字体、原图和透明补行元数据全部由 {@link HudOverlayLayout} 提供；本类只负责
 * 将它们安全落到 CraftEngine 的同一个 {@code resources/muz} 根目录。无补行的字形直接引用
 * bundle 原图，只有 ascent 超过原生 height 时才逐像素复制原图并在底部追加透明行，绝不重绘
 * 牌面、头像或道具图标。
 */
public final class HudOverlayWriter {
    private static final String CRAFT_ENGINE_PLUGIN = "CraftEngine";
    private static final String OVERLAY_NAMESPACE = "muz";
    private static final String PACK_FILE = "pack.yml";
    private static final String TRICK_IMAGES_FILE = "configuration/images/trick_hud_continuous.yml";
    private static final String CONTINUOUS_TEXTURE_ROOT = "resourcepack/assets/muz/textures/font/continuous";

    private final DoudizhuPlugin plugin;

    public HudOverlayWriter(DoudizhuPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /**
     * 解析 CraftEngine 的统一 overlay 根目录；必须在主线程调用。
     *
     * @return {@code plugins/CraftEngine/resources/muz}，依赖不可用时返回 null
     */
    public Path resolveOverlayRoot() {
        Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin(CRAFT_ENGINE_PLUGIN);
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return null;
        }
        return craftEngine.getDataFolder().toPath().resolve("resources").resolve(OVERLAY_NAMESPACE)
            .toAbsolutePath().normalize();
    }

    /**
     * 异步写出牌行、头像、记牌器三层 overlay。路径必须由主线程预先解析，异步阶段不访问 Bukkit。
     *
     * @return 成功完成且仍处于 active 状态时为 true；任何失败或失活均为 false
     */
    public CompletableFuture<Boolean> writeAsync(Path root, HudResourceRequest request, Executor executor,
                                                   BooleanSupplier active) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(active, "active");
        return CompletableFuture.supplyAsync(() -> active.getAsBoolean() && writeNow(root, request, active), executor);
    }

    /** 同步写入，供测试与旧调用方使用；不会访问 Bukkit。 */
    boolean writeNow(Path root, HudResourceRequest request, BooleanSupplier active) {
        if (root == null || !active.getAsBoolean()) {
            return false;
        }
        OverlayState before = null;
        try {
            before = capture(root);
            if (!active.getAsBoolean()) {
                return false;
            }
            writeTransaction(root, request, active);
            if (!active.getAsBoolean()) {
                restore(root, before);
                return false;
            }
            return true;
        } catch (Exception failure) {
            if (before != null) {
                try {
                    restore(root, before);
                } catch (Exception rollbackFailure) {
                    warn("连续 HUD 覆盖层失败后回滚也失败：" + rollbackFailure.getMessage());
                }
            }
            warn("写出连续 HUD 覆盖层失败：" + failure.getMessage());
            return false;
        }
    }

    /**
     * 捕获本类拥有的 pack、两份 YAML 及 continuous 动态 PNG。
     *
     * @throws IOException 读取目录或文件失败
     */
    public OverlayState capture(Path root) throws IOException {
        Path safeRoot = requireRoot(root);
        Map<String, byte[]> files = new LinkedHashMap<>();
        captureFile(safeRoot, PACK_FILE, files);
        captureFile(safeRoot, TRICK_IMAGES_FILE, files);
        Path dynamicRoot = safeRoot.resolve(CONTINUOUS_TEXTURE_ROOT).normalize();
        ensureWithin(safeRoot, dynamicRoot);
        if (Files.isDirectory(dynamicRoot)) {
            try (var stream = Files.walk(dynamicRoot)) {
                stream.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(path -> {
                        String relative = safeRoot.relativize(path).toString().replace('\\', '/');
                        if (!relative.endsWith(".png")) {
                            throw new OverlayIOException("动态覆盖层包含非 PNG 文件：" + relative);
                        }
                        try {
                            files.put(relative, Files.readAllBytes(path));
                        } catch (IOException exception) {
                            throw new OverlayIOException(exception);
                        }
                    });
            } catch (OverlayIOException exception) {
                throw exception.unwrap();
            }
        }
        return new OverlayState(files);
    }

    /** 原子恢复本类拥有的文件，且不触碰 bundle 之外的资源。 */
    public void restore(Path root, OverlayState state) throws IOException {
        Objects.requireNonNull(state, "state");
        Path safeRoot = requireRoot(root);
        Set<String> owned = new LinkedHashSet<>(List.of(PACK_FILE, TRICK_IMAGES_FILE));
        Path dynamicRoot = safeRoot.resolve(CONTINUOUS_TEXTURE_ROOT).normalize();
        ensureWithin(safeRoot, dynamicRoot);
        if (Files.isDirectory(dynamicRoot)) {
            try (var stream = Files.walk(dynamicRoot)) {
                stream.filter(Files::isRegularFile).forEach(path -> {
                    String relative = safeRoot.relativize(path).toString().replace('\\', '/');
                    if (relative.endsWith(".png")) {
                        owned.add(relative);
                    }
                });
            }
        }
        for (String relative : owned) {
            if (!state.files().containsKey(relative)) {
                Files.deleteIfExists(resolveOwned(safeRoot, relative));
            }
        }
        for (Map.Entry<String, byte[]> entry : state.files().entrySet()) {
            writeBytesAtomically(safeRoot, entry.getKey(), entry.getValue());
        }
    }

    /** 生成统一 overlay pack.yml；使用 SnakeYAML 保持结构化、UTF-8 与稳定顺序。 */
    public static String buildPackYaml() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("author", "linmumua");
        root.put("description", "MUZ HUD 连续偏移覆盖层（运行期生成）");
        root.put("namespace", OVERLAY_NAMESPACE);
        return dump(root);
    }

    public static String buildImagesYaml(List<HudOverlayLayout.Glyph> glyphs) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> images = new LinkedHashMap<>();
        for (HudOverlayLayout.Glyph glyph : glyphs) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("height", glyph.height());
            entry.put("ascent", glyph.ascent());
            entry.put("font", glyph.font());
            entry.put("file", glyph.texture());
            entry.put("char", String.format("\\u%04x", glyph.codepoint()));
            images.put(continuousImageId(glyph), entry);
        }
        root.put("images", images);
        return dump(root);
    }

    private static String continuousImageId(HudOverlayLayout.Glyph glyph) {
        String id = glyph.id().startsWith(OVERLAY_NAMESPACE + ":")
            ? glyph.id().substring((OVERLAY_NAMESPACE + ":").length())
            : glyph.id();
        if (id.equals("bot_avatar")) {
            id = "trick_hud_continuous_bot_avatar";
        } else if (id.equals("bot_avatar_landlord")) {
            id = "trick_hud_continuous_bot_avatar_landlord";
        } else if (id.equals("bot_avatar_farmer")) {
            id = "trick_hud_continuous_bot_avatar_farmer";
        }
        return OVERLAY_NAMESPACE + ":" + id;
    }

    private void writeTransaction(Path root, HudResourceRequest request, BooleanSupplier active) throws IOException {
        Path safeRoot = requireRoot(root);
        List<HudOverlayLayout.Glyph> trick = HudOverlayLayout.trickGlyphs(request);
        List<HudOverlayLayout.Glyph> all = trick;
        Map<String, byte[]> dynamic = new LinkedHashMap<>();
        for (HudOverlayLayout.Glyph glyph : all) {
            if (!active.getAsBoolean()) {
                throw new IOException("连续 HUD 覆盖层写入任务已失活");
            }
            if (glyph.paddingRasterRows() <= 0) {
                if (!glyph.texture().equals(glyph.baseTexture())) {
                    throw new IOException("无补行字形不得切换纹理：" + glyph.id());
                }
                continue;
            }
            if (glyph.originalWidth() <= 0 || glyph.originalHeight() <= 0 || glyph.height() <= 0
                || glyph.paddingRasterRows() > 256 || glyph.originalHeight() + glyph.paddingRasterRows() > 256) {
                throw new IOException("连续 HUD 字形 PNG 尺寸非法：" + glyph.id());
            }
            byte[] source = Files.readAllBytes(resolveTexture(safeRoot, glyph.baseTexture()));
            byte[] padded = appendTransparentRows(source, glyph.originalWidth(), glyph.originalHeight(),
                glyph.paddingRasterRows(), glyph.id());
            dynamic.put(relativeTexturePath(glyph.texture()), padded);
        }
        writeBytesAtomically(safeRoot, PACK_FILE, buildPackYaml().getBytes(StandardCharsets.UTF_8));
        writeBytesAtomically(safeRoot, TRICK_IMAGES_FILE, buildImagesYaml(trick).getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> entry : dynamic.entrySet()) {
            writeBytesAtomically(safeRoot, entry.getKey(), entry.getValue());
        }
        removeStaleDynamicPngs(safeRoot, dynamic.keySet());
    }

    private static byte[] appendTransparentRows(byte[] source, int width, int height, int paddingRows, String id)
        throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            throw new IOException("无法按原始尺寸读取 HUD PNG：" + id);
        }
        BufferedImage padded = new BufferedImage(width, height + paddingRows, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                padded.setRGB(x, y, image.getRGB(x, y));
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(padded, "png", output)) {
            throw new IOException("JDK 不支持 PNG 编码：" + id);
        }
        return output.toByteArray();
    }

    private static void removeStaleDynamicPngs(Path root, Set<String> desired) throws IOException {
        Path dynamicRoot = root.resolve(CONTINUOUS_TEXTURE_ROOT).normalize();
        ensureWithin(root, dynamicRoot);
        if (!Files.isDirectory(dynamicRoot)) {
            return;
        }
        List<Path> stale;
        try (var stream = Files.walk(dynamicRoot)) {
            stale = stream.filter(Files::isRegularFile)
                .filter(path -> path.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png"))
                .filter(path -> !desired.contains(root.relativize(path).toString().replace('\\', '/')))
                .sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : stale) {
            Files.deleteIfExists(path);
        }
    }

    private static void captureFile(Path root, String relative, Map<String, byte[]> files) throws IOException {
        Path file = resolveOwned(root, relative);
        if (Files.isRegularFile(file)) {
            files.put(relative, Files.readAllBytes(file));
        }
    }

    private static Path resolveTexture(Path root, String textureKey) throws IOException {
        if (textureKey == null || !textureKey.startsWith("muz:")) {
            throw new IOException("HUD 原图必须位于 muz 命名空间：" + textureKey);
        }
        String path = textureKey.substring("muz:".length());
        requireSafeTexturePath(path, "HUD 原图");
        Path file = root.resolve("resourcepack/assets/muz/textures").resolve(path).normalize();
        ensureWithin(root, file);
        return file;
    }

    private static String relativeTexturePath(String textureKey) throws IOException {
        if (textureKey == null || !textureKey.startsWith("muz:font/continuous/") || !textureKey.endsWith(".png")) {
            throw new IOException("补行纹理必须写入 continuous 字体目录：" + textureKey);
        }
        String path = textureKey.substring("muz:".length());
        requireSafeTexturePath(path, "补行纹理");
        return "resourcepack/assets/muz/textures/" + path;
    }

    private static void requireSafeTexturePath(String path, String label) throws IOException {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
            || path.contains("..") || !path.startsWith("font/") || !path.endsWith(".png")) {
            throw new IOException(label + "路径不安全：" + path);
        }
    }

    private static Path requireRoot(Path root) throws IOException {
        if (root == null) {
            throw new IOException("HUD 覆盖层根目录不能为空");
        }
        Path normalized = root.toAbsolutePath().normalize();
        if (normalized.getNameCount() < 2) {
            throw new IOException("HUD 覆盖层根目录过短，拒绝写入：" + normalized);
        }
        return normalized;
    }

    private static Path resolveOwned(Path root, String relative) throws IOException {
        Path file = root.resolve(relative).normalize();
        ensureWithin(root, file);
        return file;
    }

    private static void ensureWithin(Path root, Path path) throws IOException {
        if (!path.startsWith(root)) {
            throw new IOException("HUD 覆盖层路径越界：" + path);
        }
    }

    private static void writeBytesAtomically(Path root, String relative, byte[] bytes) throws IOException {
        Path target = resolveOwned(root, relative);
        Path parent = Objects.requireNonNull(target.getParent());
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, target.getFileName() + ".", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String dump(Map<String, Object> value) {
        LoaderOptions loader = new LoaderOptions();
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setAllowUnicode(true);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setIndicatorIndent(0);
        options.setWidth(120);
        options.setSplitLines(false);
        return new Yaml(loader, options).dump(value);
    }

    private void warn(String message) {
        try {
            plugin.getLogger().warning(message);
        } catch (Throwable loggingFailure) {
            java.util.logging.Logger.getLogger(HudOverlayWriter.class.getName())
                .warning(message + "（日志通道不可用：" + loggingFailure.getMessage() + "）");
        }
    }

    /** 捕获结果只暴露不可变副本，避免回滚期间被调用方修改。 */
    public record OverlayState(Map<String, byte[]> files) {
        public OverlayState {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().clone());
            }
            files = Map.copyOf(copy);
        }

        @Override
        public Map<String, byte[]> files() {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                copy.put(entry.getKey(), entry.getValue().clone());
            }
            return Map.copyOf(copy);
        }
    }

    private static final class OverlayIOException extends RuntimeException {
        private OverlayIOException(Throwable cause) {
            super(cause);
        }

        private OverlayIOException(String message) {
            super(message);
        }

        private IOException unwrap() {
            return getCause() instanceof IOException exception ? exception : new IOException(getMessage(), this);
        }
    }
}
