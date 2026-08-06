package linmumua.doudizhu.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * bot.action-delay-ticks 的 min/max 默认值必须全局唯一：同一语义的回退值在
 * 运行时读取（reloadPluginSettings）与配置迁移（ensureBotAiConfig）里必须一致。
 *
 * <p>历史 bug：迁移处用了硬编码 20，而运行时 min 用 10、max 用 30。
 * 当旧键存在但值被清空时，迁移写入的值与运行时读到的回退值矛盾。
 * 提取常量后若有人改了一处漏了另一处，此测试会立即失败。
 */
class BotDelayDefaultsConsistencyTest {

    private static final Path PLUGIN =
        Path.of("src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");

    /**
     * 常量声明必须存在且 max > min。
     * 如果有人删除常量或把 max 改得比 min 小，此测试失败。
     */
    @Test
    void constantsDeclaredWithCorrectRelationship() throws IOException {
        String source = Files.readString(PLUGIN);
        Matcher minMatcher = Pattern.compile(
            "private\\s+static\\s+final\\s+int\\s+DEFAULT_BOT_DELAY_MIN_TICKS\\s*=\\s*(\\d+)")
            .matcher(source);
        Matcher maxMatcher = Pattern.compile(
            "private\\s+static\\s+final\\s+int\\s+DEFAULT_BOT_DELAY_MAX_TICKS\\s*=\\s*(\\d+)")
            .matcher(source);
        assertTrue(minMatcher.find(),
            "找不到 DEFAULT_BOT_DELAY_MIN_TICKS 常量声明");
        assertTrue(maxMatcher.find(),
            "找不到 DEFAULT_BOT_DELAY_MAX_TICKS 常量声明");
        int min = Integer.parseInt(minMatcher.group(1));
        int max = Integer.parseInt(maxMatcher.group(1));
        assertTrue(max > min,
            "DEFAULT_BOT_DELAY_MAX_TICKS(" + max + ") 必须大于 "
                + "DEFAULT_BOT_DELAY_MIN_TICKS(" + min + ")");
    }

    /**
     * 源码中不得出现对旧键 bot.action-delay-ticks 的 getInt 调用使用硬编码数字回退。
     * 合法形式只能是引用 DEFAULT_BOT_DELAY_MIN_TICKS 或 DEFAULT_BOT_DELAY_MAX_TICKS。
     * 如果有人绕过常量直接写数字，这条测试会失败。
     */
    @Test
    void noHardcodedFallbackForOldDelayKey() throws IOException {
        String source = Files.readString(PLUGIN);
        // 匹配 getInt("bot.action-delay-ticks", <纯数字>) 形式的硬编码
        Pattern hardcoded = Pattern.compile(
            "getInt\\(\\s*\"bot\\.action-delay-ticks\"\\s*,\\s*\\d+\\s*\\)");
        Matcher m = hardcoded.matcher(source);
        while (m.find()) {
            // 找到此匹配所在的行号，辅助定位
            int lineNum = source.substring(0, m.start()).split("\n").length;
            assertTrue(false,
                "第 " + lineNum + " 行对旧键使用了硬编码默认值而非常量，"
                    + "这会导致迁移与运行时回退值不一致：" + m.group());
        }
    }
}
