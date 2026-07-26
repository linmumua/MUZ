package dev.mumu.doudizhu.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;

class MuzYamlConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void savesAndReloadsUtf8BlockStyleYaml() throws IOException {
        Path file = tempDir.resolve("config.yml");
        MuzYamlConfig config = MuzYamlConfig.empty(file);
        config.set("table.name", "测试牌桌");
        config.set("table.seats", List.of("north", "south"));
        config.save();

        String serialized = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(serialized.contains("table:"));
        assertTrue(serialized.contains("测试牌桌"));

        MuzYamlConfig reloaded = new MuzYamlConfig(file);
        assertEquals("测试牌桌", reloaded.getString("table.name"));
        assertEquals(List.of("north", "south"), reloaded.getStringList("table.seats"));
    }

    @Test
    void rejectsDuplicateKeys() throws IOException {
        Path file = tempDir.resolve("duplicate.yml");
        Files.writeString(file, "table: first\ntable: second\n", StandardCharsets.UTF_8);

        assertThrows(DuplicateKeyException.class, () -> new MuzYamlConfig(file));
    }
}
