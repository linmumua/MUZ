package linmumua.doudizhu.compat;

import linmumua.doudizhu.DoudizhuPlugin;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            if (!force && isBundleCurrent(targetRoot)) {
                return BundleExportResult.upToDate(entries.size());
            }

            cleanupStaleFiles(targetRoot, entries);
            for (String entry : entries) {
                copyBundledFile(entry, targetRoot.resolve(entry));
                copiedEntries++;
                progressListener.onProgress(copiedEntries, entries.size(), entry);
            }
            return BundleExportResult.exported(entries.size(), copiedEntries);
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to export CraftEngine bundle: " + exception.getMessage());
            return BundleExportResult.failed(exception.getMessage(), entries.size(), copiedEntries);
        }
    }

    private boolean isBundleCurrent(Path targetRoot) throws IOException {
        Path targetPack = targetRoot.resolve("pack.yml");
        if (!Files.isRegularFile(targetPack)) {
            return false;
        }
        try (InputStream bundledPack = plugin.getResource(BUNDLE_PACK_FILE)) {
            if (bundledPack == null) {
                return false;
            }
            String bundled = new String(bundledPack.readAllBytes(), StandardCharsets.UTF_8);
            String existing = Files.readString(targetPack, StandardCharsets.UTF_8);
            return bundled.equals(existing);
        }
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
        Set<Path> expectedFiles = bundledEntries.stream()
            .map(targetRoot::resolve)
            .collect(Collectors.toSet());

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

