package linmumua.doudizhu.config;

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

    @Test
    void truncatedFileIsQuarantinedInsteadOfBreakingStartup() throws IOException {
        // 进程被杀导致 YAML 只写了一半时，插件不能因为解析失败而整体禁用。
        Path file = tempDir.resolve("truncated.yml");
        Files.writeString(file, "players: {\n  ", StandardCharsets.UTF_8);

        MuzYamlConfig config = new MuzYamlConfig(file);

        assertTrue(config.rawRoot().isEmpty(), "坏文件应当按空配置继续");
        assertTrue(
            Files.isRegularFile(tempDir.resolve("truncated.yml.broken")),
            "坏文件应当留一份 .broken 备份便于排查"
        );
    }

    @Test
    void saveLeavesNoTemporaryFileBehind() throws IOException {
        Path file = tempDir.resolve("atomic.yml");
        MuzYamlConfig config = new MuzYamlConfig(file);
        config.set("table.name", "原子写");

        config.save();

        assertTrue(Files.isRegularFile(file), "目标文件应当存在");
        assertTrue(
            Files.notExists(tempDir.resolve("atomic.yml.tmp")),
            "替换完成后不应残留 .tmp 文件"
        );
        assertEquals("原子写", new MuzYamlConfig(file).getString("table.name"));
    }
}
