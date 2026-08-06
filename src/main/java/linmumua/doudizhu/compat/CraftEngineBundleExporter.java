package linmumua.doudizhu.compat;

import linmumua.doudizhu.DoudizhuPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.plugin.Plugin;

public final class CraftEngineBundleExporter {
    private static final String CRAFT_ENGINE_PLUGIN = "CraftEngine";
    private static final String BUNDLE_ROOT = "craftengine/muz";
    private static final String BUNDLE_INDEX = BUNDLE_ROOT + "/_bundle_index.txt";
    private static final String BUNDLE_PACK_FILE = BUNDLE_ROOT + "/pack.yml";

    /**
     * 内容指纹存档的文件名，写在导出目标目录下。
     *
     * <p>它不在 bundle 清单里，所以 {@link #cleanupStaleFiles} 必须显式放它一马，
     * 否则每次导出都会把刚写下的指纹当成「清单外的残留」删掉，判定就永久失效。
     */
    private static final String BUNDLE_FINGERPRINT_FILE = ".muz-bundle-fingerprint";

    private final DoudizhuPlugin plugin;

    public CraftEngineBundleExporter(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public BundleExportResult exportIfAvailable() {
        return ensureBundleReady("startup", false);
    }

    public BundleExportResult ensureBundleReady(String reason, boolean force) {
        return ensureBundleReady(reason, force, BundleExportProgressListener.none());
    }

    public BundleExportResult ensureBundleReady(String reason, boolean force, BundleExportProgressListener progressListener) {
        Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin(CRAFT_ENGINE_PLUGIN);
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return BundleExportResult.skipped("CraftEngine 未检测到，已跳过");
        }

        Path targetRoot = craftEngine.getDataFolder().toPath().resolve("resources").resolve("muz");
        List<String> entries = List.of();
        int copiedEntries = 0;
        try (InputStream stream = plugin.getResource(BUNDLE_INDEX)) {
            if (stream == null) {
                plugin.getLogger().warning("CraftEngine bundle index is missing, skipping bundle export.");
                return BundleExportResult.failed("bundle 索引缺失", 0, 0);
            }

            entries = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
            String fingerprint = computeBundleFingerprint(entries);
            if (!force && isBundleCurrent(targetRoot, fingerprint)) {
                return BundleExportResult.upToDate(entries.size());
            }

            cleanupStaleFiles(targetRoot, entries);
            for (String entry : entries) {
                copyBundledFile(entry, targetRoot.resolve(entry));
                copiedEntries++;
                progressListener.onProgress(copiedEntries, entries.size(), entry);
            }
            // 指纹最后写：中途失败时它不会留下，下次启动照旧会重新导出，
            // 不会出现「文件只复制了一半却被判定为最新」。
            Files.writeString(targetRoot.resolve(BUNDLE_FINGERPRINT_FILE), fingerprint, StandardCharsets.UTF_8);
            return BundleExportResult.exported(entries.size(), copiedEntries);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to export CraftEngine bundle: " + exception.getMessage());
            return BundleExportResult.failed(exception.getMessage(), entries.size(), copiedEntries);
        }
    }

    /**
     * 判断 CraftEngine 目录里的 bundle 是否已经和 jar 内的完全一致。
     *
     * <h2>为什么不能只比 pack.yml（已取证的故障）</h2>
     *
     * <p>这里原先只比对 {@code pack.yml} 的字节。但 {@code pack.yml} 只含 author / version /
     * description / namespace，<b>不含任何文件清单或内容指纹</b>。于是只要插件版本号没变，
     * 新增或修改过的资源文件就永远不会被复制过去 —— 而版本号是手工维护的，
     * 加一个新贴图并不会让它自增。
     *
     * <p>这个 bug 的表现极具误导性：老资源（早在当前版本号定版前就存在）一直是正常的，
     * 只有<b>新加的文件</b>静默失效。BossBar 轨道透明化就是这么失效的：
     * {@code white_background.png} / {@code white_progress.png} 打进了 jar、
     * 单元测试也全过，但它们从未被导出到 CraftEngine 的 resources 目录，
     * 客户端拿到的仍是原版不透明贴图，那条深色槽一直露在屏幕顶部。
     *
     * <h2>为什么用指纹而不是逐字节比对</h2>
     *
     * <p>直觉做法是逐条读出 jar 内资源和磁盘文件、逐字节比。那样确实正确，但 bundle 有
     * 423 个文件、7.3 MB（三个 BGM 的 ogg 各占 600-800 KB），每次启动都要把两边各读一遍，
     * 等于凭空多做约 15 MB 的磁盘 I/O，而其中绝大多数文件从来不变。
     *
     * <p>所以改成：把「清单 + 每个 jar 内资源的内容哈希」压成一份摘要写在目标目录里，
     * 下次启动只算 jar 侧的摘要（只读 jar，不读磁盘上那 7.3 MB）再和存档比一次字符串。
     * 文件增、删、改、改名都会让摘要变化，检测能力和逐字节比对等价；
     * 而且摘要里带了清单本身，所以「清单没变但某个文件内容变了」也能抓到。
     *
     * @param targetRoot CraftEngine 下的 muz 资源目录
     * @param expectedFingerprint 本次 jar 内 bundle 的内容指纹
     * @return 指纹一致返回 true；存档缺失或不一致都返回 false
     * @throws IOException 读取本地文件失败
     */
    private boolean isBundleCurrent(Path targetRoot, String expectedFingerprint) throws IOException {
        Path stamp = targetRoot.resolve(BUNDLE_FINGERPRINT_FILE);
        if (!Files.isRegularFile(stamp)) {
            return false;
        }
        return expectedFingerprint.equals(Files.readString(stamp, StandardCharsets.UTF_8).trim());
    }

    /**
     * 算出 jar 内 bundle 的内容指纹。
     *
     * <p>把每个条目的路径和内容哈希依次喂进同一个摘要，所以路径变化和内容变化都会反映出来。
     * {@code pack.yml} 也一并算进去 —— 它虽然不在清单里，却是 CraftEngine 读取的入口文件。
     *
     * @param entries bundle 清单里的全部相对路径
     * @return 十六进制指纹
     * @throws IOException 读取 jar 内资源失败
     */
    private String computeBundleFingerprint(List<String> entries) throws IOException {
        // 用 LinkedHashMap 保序：摘要对喂入顺序敏感，顺序不稳会让指纹每次都变、退化成永远重导
        java.util.Map<String, java.util.function.Supplier<InputStream>> sources =
            new java.util.LinkedHashMap<>();
        sources.put("pack.yml", () -> plugin.getResource(BUNDLE_PACK_FILE));
        for (String entry : entries) {
            sources.put(entry, () -> plugin.getResource(BUNDLE_ROOT + "/" + entry));
        }
        return fingerprintOf(sources);
    }

    /**
     * 把「路径 → 内容」的集合压成一个指纹。
     *
     * <p>做成不依赖 Bukkit 的静态函数，为的是能直接单测：喂两组内容进去，
     * 断言「内容变了指纹就变」「顺序或路径变了指纹也变」。
     * 如果把它埋在需要活 plugin 实例的私有方法里，这套判定就只能靠肉眼审查。
     *
     * <p>路径和内容都参与摘要，所以文件改名（内容不变）同样会让指纹变化。
     *
     * @param sources 路径到内容流的映射，遍历顺序必须稳定
     * @return 十六进制指纹
     * @throws IOException 某个内容流缺失或读取失败
     */
    static String fingerprintOf(
        java.util.Map<String, java.util.function.Supplier<InputStream>> sources
    ) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 是 JDK 强制要求提供的算法，走不到这里
            throw new IOException("当前 JVM 不支持 SHA-256", exception);
        }
        for (java.util.Map.Entry<String, java.util.function.Supplier<InputStream>> source
            : sources.entrySet()) {
            digest.update(source.getKey().getBytes(StandardCharsets.UTF_8));
            try (InputStream stream = source.getValue().get()) {
                if (stream == null) {
                    throw new IOException("Missing bundled resource: " + source.getKey());
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private void copyBundledFile(String relativePath, Path targetPath) throws IOException {
        try (InputStream resource = plugin.getResource(BUNDLE_ROOT + "/" + relativePath)) {
            if (resource == null) {
                throw new IOException("Missing bundled resource: " + relativePath);
            }
            Files.createDirectories(Objects.requireNonNull(targetPath.getParent()));
            Files.copy(resource, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanupStaleFiles(Path targetRoot, List<String> bundledEntries) throws IOException {
        if (!Files.exists(targetRoot)) {
            return;
        }
        // 用 HashSet 而不是 Collectors.toSet()：后者不保证返回可变集合，加不进指纹存档
        Set<Path> expectedFiles = bundledEntries.stream()
            .map(targetRoot::resolve)
            .collect(Collectors.toCollection(java.util.HashSet::new));
        // 指纹存档不在清单里，但它是我们自己写的，不能当残留删掉
        expectedFiles.add(targetRoot.resolve(BUNDLE_FINGERPRINT_FILE));

        try (var walk = Files.walk(targetRoot)) {
            List<Path> existing = walk
                .filter(Files::isRegularFile)
                .toList();
            for (Path file : existing) {
                if (!expectedFiles.contains(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }

        try (var walk = Files.walk(targetRoot)) {
            List<Path> directories = walk
                .filter(Files::isDirectory)
                .sorted(Comparator.reverseOrder())
                .toList();
            for (Path directory : directories) {
                if (directory.equals(targetRoot)) {
                    continue;
                }
                try (var children = Files.list(directory)) {
                    if (children.findAny().isEmpty()) {
                        Files.deleteIfExists(directory);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    public interface BundleExportProgressListener {
        void onProgress(int copiedEntries, int totalEntries, String relativePath);

        static BundleExportProgressListener none() {
            return (copiedEntries, totalEntries, relativePath) -> {
            };
        }
    }

    public record BundleExportResult(BundleExportState state, int totalEntries, int copiedEntries, String detail) {
        public static BundleExportResult skipped(String detail) {
            return new BundleExportResult(BundleExportState.SKIPPED, 0, 0, detail);
        }

        public static BundleExportResult upToDate(int totalEntries) {
            return new BundleExportResult(BundleExportState.UP_TO_DATE, totalEntries, totalEntries, "CraftEngine bundle 已是最新");
        }

        public static BundleExportResult exported(int totalEntries, int copiedEntries) {
            return new BundleExportResult(BundleExportState.EXPORTED, totalEntries, copiedEntries, "CraftEngine bundle 已同步");
        }

        public static BundleExportResult failed(String detail, int totalEntries, int copiedEntries) {
            return new BundleExportResult(BundleExportState.FAILED, totalEntries, copiedEntries, detail);
        }
    }

    public enum BundleExportState {
        SKIPPED,
        UP_TO_DATE,
        EXPORTED,
        FAILED
    }
}

