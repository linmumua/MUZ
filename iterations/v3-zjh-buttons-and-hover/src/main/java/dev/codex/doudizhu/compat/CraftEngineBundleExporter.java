package dev.codex.doudizhu.compat;

import dev.codex.doudizhu.DoudizhuPlugin;
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

    public void exportIfAvailable() {
        ensureBundleReady("startup", false);
    }

    public void ensureBundleReady(String reason, boolean force) {
        Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin(CRAFT_ENGINE_PLUGIN);
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return;
        }

        try (InputStream stream = plugin.getResource(BUNDLE_INDEX)) {
            if (stream == null) {
                plugin.getLogger().warning("CraftEngine bundle index is missing, skipping bundle export.");
                return;
            }

            Path targetRoot = craftEngine.getDataFolder().toPath().resolve("resources").resolve("muz");
            if (!force && isBundleCurrent(targetRoot)) {
                plugin.getLogger().info("CraftEngine bundle already up to date for reason: " + reason);
                return;
            }

            List<String> entries = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

            cleanupStaleFiles(targetRoot, entries);
            for (String entry : entries) {
                copyBundledFile(entry, targetRoot.resolve(entry));
            }
            plugin.getLogger().info(
                "CraftEngine detected. Exported MUZ bundle to " + targetRoot.toAbsolutePath() + " (reason: " + reason + ")"
            );
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to export CraftEngine bundle: " + exception.getMessage());
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
}
