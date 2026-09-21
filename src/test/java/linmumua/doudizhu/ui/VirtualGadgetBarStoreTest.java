package linmumua.doudizhu.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import linmumua.doudizhu.config.MuzYamlConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VirtualGadgetBarStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void explicitEmptyListRoundTripsWithoutPaperRegistry() throws Exception {
        Path file = tempDir.resolve("player-settings.yml");
        UUID playerId = UUID.randomUUID();
        VirtualGadgetBarStore store = new VirtualGadgetBarStore(file);

        store.save(playerId, VirtualGadgetBar.empty());
        assertTrue(store.load(playerId).isEmpty());
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("gadget-bar:"));
        MuzYamlConfig persisted = new MuzYamlConfig(file);
        String path = "players." + playerId + ".gadget-bar";
        assertTrue(persisted.contains(path), "明确空栏必须保留配置路径，不能退化成缺失路径");
        assertTrue(persisted.get(path) instanceof List<?> list && list.isEmpty(),
            "明确空栏的结构化值必须为空 List");
    }

    @Test
    void rawReadDistinguishesMissingEmptyAndEightSlotsWithoutWriting() throws Exception {
        Path file = tempDir.resolve("raw.yml");
        UUID playerId = UUID.randomUUID();
        VirtualGadgetBarStore store = new VirtualGadgetBarStore(file);
        org.junit.jupiter.api.Assertions.assertFalse(store.loadRaw(playerId).present());
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(file));
        String text = "players:\n  " + playerId + ":\n    gadget-bar: [null, egg, egg, bucket, null, egg, egg, egg, voice]\n";
        Files.writeString(file, text);
        var raw = store.loadRaw(playerId);
        org.junit.jupiter.api.Assertions.assertTrue(raw.present());
        org.junit.jupiter.api.Assertions.assertEquals(8, raw.values().size());
        org.junit.jupiter.api.Assertions.assertNull(raw.values().get(0));
        org.junit.jupiter.api.Assertions.assertEquals(raw.values().get(1), raw.values().get(2));
        org.junit.jupiter.api.Assertions.assertEquals(text, Files.readString(file));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> raw.values().clear());
    }

    @Test
    void corruptPreviewReadFailsWithoutQuarantiningPlayerFile() throws Exception {
        Path file = tempDir.resolve("broken.yml");
        String text = "players: [\n";
        Files.writeString(file, text);
        VirtualGadgetBarStore store = new VirtualGadgetBarStore(file);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> store.loadRaw(UUID.randomUUID()));
        org.junit.jupiter.api.Assertions.assertEquals(text, Files.readString(file));
        try (var files = Files.list(tempDir)) {
            org.junit.jupiter.api.Assertions.assertEquals(1, files.count(), "只读失败不得产生隔离文件");
        }
    }

    @Test
    void resetDeletesExplicitPathSoNextLoadUsesDefaultBranch() throws Exception {
        Path file = tempDir.resolve("settings.yml");
        UUID playerId = UUID.randomUUID();
        VirtualGadgetBarStore store = new VirtualGadgetBarStore(file);
        store.save(playerId, VirtualGadgetBar.empty());
        store.reset(playerId);

        String yaml = Files.readString(file);
        assertFalse(yaml.contains("gadget-bar:"), "reset 必须删除显式路径，而不是继续保存空列表");
        String source = Files.readString(Path.of("src/main/java/linmumua/doudizhu/ui/VirtualGadgetBarStore.java"));
        assertTrue(source.contains("if (!raw.present())"));
        assertTrue(source.contains("return VirtualGadgetBar.defaults()"));
    }
}
