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
    void resetDeletesExplicitPathSoNextLoadUsesDefaultBranch() throws Exception {
        Path file = tempDir.resolve("settings.yml");
        UUID playerId = UUID.randomUUID();
        VirtualGadgetBarStore store = new VirtualGadgetBarStore(file);
        store.save(playerId, VirtualGadgetBar.empty());
        store.reset(playerId);

        String yaml = Files.readString(file);
        assertFalse(yaml.contains("gadget-bar:"), "reset 必须删除显式路径，而不是继续保存空列表");
        String source = Files.readString(Path.of("src/main/java/linmumua/doudizhu/ui/VirtualGadgetBarStore.java"));
        assertTrue(source.contains("if (!config.contains(path))"));
        assertTrue(source.contains("return VirtualGadgetBar.defaults()"));
    }
}
