package linmumua.doudizhu.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 旧安装的明牌文案迁移：只替换「没人改过的旧默认」，绝不覆盖自定义文案。 */
class RevealMessageMigrationTest {
    @TempDir
    Path tempDir;

    private MuzYamlConfig config(String yaml) throws IOException {
        Path file = tempDir.resolve("config.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return new MuzYamlConfig(file);
    }

    /** 收集 info 日志，用来证明"自定义文案只提示、不修改"。 */
    private static final class RecordingHandler extends Handler {
        private final List<String> messages = new ArrayList<>();

        @Override
        public void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static Logger recordingLogger(RecordingHandler handler) {
        Logger logger = Logger.getLogger("RevealMessageMigrationTest-" + System.nanoTime());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        return logger;
    }

    @Test
    void replacesTheExactLegacyDefaultsWithPlaceholderTemplates() throws IOException {
        // 老服升级后这三键都已存在，mergeMissingFrom 补不进来；
        // 不迁移的话玩家会一直看到「整局最多 ×2」，与 3→2→1 的实际结算矛盾。
        MuzYamlConfig config = config("""
            round-opening:
              messages:
                revealing: "明牌窗口：还剩 %seconds% 秒，可自愿明牌（整局最多 ×2）。"
                revealed: "%player% 已明牌，本局公共倍率变为 ×2。"
                reveal-button: "明牌 ×2"
            """);

        assertTrue(RevealMessageMigration.migrate(config, null), "命中旧默认就必须报告已改写");

        assertEquals(RevealMessageMigration.newTemplate(RevealMessageMigration.REVEALING),
            config.getString(RevealMessageMigration.REVEALING));
        assertEquals(RevealMessageMigration.newTemplate(RevealMessageMigration.REVEALED),
            config.getString(RevealMessageMigration.REVEALED));
        assertEquals(RevealMessageMigration.newTemplate(RevealMessageMigration.REVEAL_BUTTON),
            config.getString(RevealMessageMigration.REVEAL_BUTTON));
        assertTrue(config.getString(RevealMessageMigration.REVEALING).contains("%multiplier%"));
    }

    @Test
    void keepsCustomTextByteForByte() throws IOException {
        // 管理员自己写过的文案属于用户数据，升级不能冲掉。
        String customRevealing = "明牌还来得及，剩 %seconds% 秒，自己看着办。";
        String customButton = "亮牌!";
        MuzYamlConfig config = config("""
            round-opening:
              messages:
                revealing: "%s"
                revealed: "（我们服不提示倍率）"
                reveal-button: "%s"
            """.formatted(customRevealing, customButton));

        assertFalse(RevealMessageMigration.migrate(config, null), "自定义文案不应触发改写");

        assertEquals(customRevealing, config.getString(RevealMessageMigration.REVEALING));
        assertEquals("（我们服不提示倍率）", config.getString(RevealMessageMigration.REVEALED));
        assertEquals(customButton, config.getString(RevealMessageMigration.REVEAL_BUTTON));
    }

    @Test
    void customTextWithoutPlaceholderIsOnlyReportedNeverRewritten() throws IOException {
        // 「不悄改」的具体含义：只留一条提示告诉管理员该怎么加占位符，文本一个字符都不动。
        MuzYamlConfig config = config("""
            round-opening:
              messages:
                reveal-button: "明牌（固定）"
            """);
        RecordingHandler handler = new RecordingHandler();
        Logger logger = recordingLogger(handler);

        assertFalse(RevealMessageMigration.migrate(config, logger));

        assertEquals("明牌（固定）", config.getString(RevealMessageMigration.REVEAL_BUTTON));
        assertTrue(handler.messages.stream().anyMatch(message -> message.contains("%multiplier%")),
            "必须提示占位符的用法，否则管理员不知道文案为什么不随倍率变化：" + handler.messages);
    }

    @Test
    void alreadyMigratedValuesAreLeftAloneAndReportNoChange() throws IOException {
        // 幂等：每次 /muz reload 都会跑一遍，不能每次都报告 changed（那会触发无意义的写盘）。
        MuzYamlConfig config = config("""
            round-opening:
              messages:
                revealing: "%s"
                revealed: "%s"
                reveal-button: "%s"
            """.formatted(
                RevealMessageMigration.newTemplate(RevealMessageMigration.REVEALING),
                RevealMessageMigration.newTemplate(RevealMessageMigration.REVEALED),
                RevealMessageMigration.newTemplate(RevealMessageMigration.REVEAL_BUTTON)));

        assertFalse(RevealMessageMigration.migrate(config, null));
    }

    @Test
    void missingKeysAreLeftToTheBackfillStep() throws IOException {
        // 缺键不归本迁移管：mergeMissingFrom 会补，迁移不该顺手写一份"新默认"造成两个来源。
        MuzYamlConfig config = config("round-opening:\n  messages:\n    dealing: \"正在发牌\"\n");

        assertFalse(RevealMessageMigration.migrate(config, null));
        assertEquals(null, config.get(RevealMessageMigration.REVEALING));
    }

    @Test
    void partiallyLegacyConfigurationMigratesOnlyTheLegacyKeys() throws IOException {
        // 逐键判定：一条是旧默认、一条是自定义，只能改前者。
        MuzYamlConfig config = config("""
            round-opening:
              messages:
                revealing: "明牌窗口：还剩 %seconds% 秒，可自愿明牌（整局最多 ×2）。"
                revealed: "我服自己写的明牌提示"
                reveal-button: "明牌 ×2"
            """);

        assertTrue(RevealMessageMigration.migrate(config, null));

        assertEquals(RevealMessageMigration.newTemplate(RevealMessageMigration.REVEALING),
            config.getString(RevealMessageMigration.REVEALING));
        assertEquals("我服自己写的明牌提示", config.getString(RevealMessageMigration.REVEALED),
            "自定义的那条必须原样保留");
        assertEquals(RevealMessageMigration.newTemplate(RevealMessageMigration.REVEAL_BUTTON),
            config.getString(RevealMessageMigration.REVEAL_BUTTON));
    }

    @Test
    void migrationNeverTouchesOtherTextThatHappensToContainTwo() throws IOException {
        // 「别泛字符串替换所有 2」的具体反例：其它段里含 ×2 的文案与倍率无关，绝不能被动。
        MuzYamlConfig config = config("""
            table-gadgets:
              gui:
                title: "桌内道具 ×2"
            round-opening:
              messages:
                reveal-button: "明牌 ×2"
            """);

        assertTrue(RevealMessageMigration.migrate(config, null));

        assertEquals("桌内道具 ×2", config.getString("table-gadgets.gui.title"),
            "迁移只认三条明牌键，不得波及其它含 ×2 的文本");
    }

    @Test
    void newTemplatesMatchTheRuntimeCodeDefaults() {
        // 配置侧模板必须与 RoundOpeningSettings.Messages 的代码默认值逐字一致，
        // 否则「缺键时的显示」与「迁移后的显示」会不一样。
        assertEquals("明牌窗口：还剩 %seconds% 秒，可自愿明牌（当前 ×%multiplier%）。",
            RevealMessageMigration.newTemplate(RevealMessageMigration.REVEALING));
        assertEquals("%player% 已明牌，本局公共倍率变为 ×%multiplier%。",
            RevealMessageMigration.newTemplate(RevealMessageMigration.REVEALED));
        assertEquals("明牌 ×%multiplier%", RevealMessageMigration.newTemplate(RevealMessageMigration.REVEAL_BUTTON));
    }

    @Test
    void shippedConfigCarriesTheSameNewTemplates() throws IOException {
        // 三处必须一致：打包 config.yml 的值、本迁移写回的值、RoundOpeningSettings 的代码默认。
        // 任何一处单独改动都会让"老服迁移完的文案"与"新装服的文案"不同。
        String template = Files.readString(Path.of("src/main/resources/config.yml"), StandardCharsets.UTF_8);
        Path file = tempDir.resolve("shipped-reveal.yml");
        Files.writeString(file, template, StandardCharsets.UTF_8);
        MuzYamlConfig shipped = new MuzYamlConfig(file);

        for (String path : List.of(RevealMessageMigration.REVEALING,
            RevealMessageMigration.REVEALED, RevealMessageMigration.REVEAL_BUTTON)) {
            assertEquals(RevealMessageMigration.newTemplate(path), shipped.getString(path),
                path + " 在打包 config.yml 里必须已改写成带 %multiplier% 的新模板");
        }
        // 打包模板里也不该再留着旧默认值（否则新装服与迁移路径的判定会互相干扰）。
        for (String path : List.of(RevealMessageMigration.REVEALING,
            RevealMessageMigration.REVEALED, RevealMessageMigration.REVEAL_BUTTON)) {
            assertFalse(RevealMessageMigration.legacyDefault(path).equals(shipped.getString(path)),
                path + " 不得仍是旧默认文案");
        }
    }
}
