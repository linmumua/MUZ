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

    @Test
    void removesRetiredHotbarYamlFontsAndOverlayButPreservesTableGadgets() throws IOException {
        Path targetRoot = temporaryDirectory.resolve("muz");
        Path images = targetRoot.resolve("configuration/images");
        Path fonts = targetRoot.resolve("resourcepack/assets/muz/textures/font/scale_100");
        Path items = targetRoot.resolve("resourcepack/assets/muz/textures/item");
        Path legacyOverlay = temporaryDirectory.resolve("muz_hotbar_debug/nested");
        Files.createDirectories(images);
        Files.createDirectories(fonts);
        Files.createDirectories(items);
        Files.createDirectories(legacyOverlay);

        Path hotbarBase = Files.writeString(images.resolve("hotbar_hud.yml"), "legacy");
        Path hotbarRuntime = Files.writeString(images.resolve("hotbar_debug.yml"), "legacy");
        Path hotbarIcon = Files.writeString(fonts.resolve("hotbar_egg.png"), "legacy");
        Path hotbarSelect = Files.writeString(fonts.resolve("hotbar_select.png"), "legacy");
        Path gadget = Files.writeString(items.resolve("table_gadget_tomato.png"), "keep");
        Path trick = Files.writeString(images.resolve("trick_hud_continuous.yml"), "keep");
        Files.writeString(legacyOverlay.resolve("pack.yml"), "legacy");

        CraftEngineBundleExporter.cleanupLegacyHotbarResources(targetRoot, temporaryDirectory);

        assertFalse(Files.exists(hotbarBase));
        assertFalse(Files.exists(hotbarRuntime));
        assertFalse(Files.exists(hotbarIcon));
        assertFalse(Files.exists(hotbarSelect));
        assertFalse(Files.exists(temporaryDirectory.resolve("muz_hotbar_debug")));
        assertTrue(Files.isRegularFile(gadget));
        assertTrue(Files.isRegularFile(trick));
    }
}
