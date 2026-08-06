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
    void savePreservesExistingComments() throws IOException {
        // 保存一次就把注释全冲掉是实际发生过的事故：服务器 config.yml 从 235 行注释掉到只剩
        // 手工补回的那 20 行，玩家改配置时看不到任何取值说明。
        String template = """
            # 顶部说明
            trick-hud:
              # 总开关
              enabled: true
              # 头像放大倍数，只能取 4..10
              avatar-scale: 6
              # 水平偏移
              offset-x: 0
            """;
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, template, StandardCharsets.UTF_8);

        MuzYamlConfig config = new MuzYamlConfig(file);
        // 改一个无关的值再存，模拟插件补默认项时触发的那次写盘
        config.set("trick-hud.offset-x", 4);
        config.saveWithComments(template);

        String serialized = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(serialized.contains("# 顶部说明"), "顶部注释必须留下：\n" + serialized);
        assertTrue(serialized.contains("# 总开关"), "键上方的注释必须留下：\n" + serialized);
        assertTrue(serialized.contains("# 头像放大倍数，只能取 4..10"),
            "带取值范围的注释必须留下，这类注释丢了配置就没法看：\n" + serialized);
        // 注释保住的同时，值也必须真的写进去了
        assertEquals(4, new MuzYamlConfig(file).getInt("trick-hud.offset-x", 0));
        assertEquals(6, new MuzYamlConfig(file).getInt("trick-hud.avatar-scale", 0));
    }

    @Test
    void repeatedSavesDoNotDuplicateComments() throws IOException {
        // 第一版实现把模板注释无条件插到键上方，而目标里本来就有注释，于是每保存一次
        // 同一段说明就多一份。实测服务器 config.yml 出现了成对重复的注释行。
        String template = """
            # 顶部说明
            trick-hud:
              # 总开关
              enabled: true
            """;
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, template, StandardCharsets.UTF_8);

        for (int round = 0; round < 3; round++) {
            MuzYamlConfig config = new MuzYamlConfig(file);
            config.set("trick-hud.enabled", true);
            config.saveWithComments(template);
        }

        String serialized = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(serialized, "# 总开关"),
            "反复保存后同一段注释只能有一份：\n" + serialized);
        assertEquals(1, countOccurrences(serialized, "# 顶部说明"),
            "头部注释也不能重复：\n" + serialized);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

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
