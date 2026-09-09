package linmumua.doudizhu.compat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyHotbarSpriteCleanupTest {
    private static final Path LEGACY_SPRITE_DIRECTORY =
        Path.of("resourcepack/assets/minecraft/textures/gui/sprites/hud");

    @TempDir
    Path temporaryDirectory;

    @Test
    void removesBothLegacyGlobalHotbarSprites() throws IOException {
        Path spriteDirectory = temporaryDirectory.resolve(LEGACY_SPRITE_DIRECTORY);
        Files.createDirectories(spriteDirectory);
        Path hotbar = Files.writeString(spriteDirectory.resolve("hotbar.png"), "legacy-hotbar");
        Path selection = Files.writeString(spriteDirectory.resolve("hotbar_selection.png"), "legacy-selection");

        CraftEngineBundleExporter.cleanupLegacyGlobalHotbarSprites(temporaryDirectory);

        assertFalse(Files.exists(hotbar));
        assertFalse(Files.exists(selection));
    }

    @Test
    void missingSpriteDirectoryIsSafe() {
        assertDoesNotThrow(
            () -> CraftEngineBundleExporter.cleanupLegacyGlobalHotbarSprites(temporaryDirectory));
    }

    @Test
    void preservesUnrelatedFiles() throws IOException {
        Path spriteDirectory = temporaryDirectory.resolve(LEGACY_SPRITE_DIRECTORY);
        Files.createDirectories(spriteDirectory);
        Path unrelated = Files.writeString(spriteDirectory.resolve("other.png"), "keep");
        Path sibling = Files.writeString(spriteDirectory.getParent().resolve("unrelated.png"), "keep");

        CraftEngineBundleExporter.cleanupLegacyGlobalHotbarSprites(temporaryDirectory);

        assertTrue(Files.isRegularFile(unrelated));
        assertTrue(Files.isRegularFile(sibling));
    }
}
