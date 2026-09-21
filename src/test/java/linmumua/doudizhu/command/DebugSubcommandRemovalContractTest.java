package linmumua.doudizhu.command;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 已删除的 HUD 调试子命令不得重新成为正式命令入口。 */
class DebugSubcommandRemovalContractTest {
    private static final Path COMMAND =
        Path.of("src/main/java/linmumua/doudizhu/command/DoudizhuCommand.java");

    @Test
    void trace执行补全及用法提示均已移除() throws IOException {
        String source = Files.readString(COMMAND);
        assertFalse(source.contains("\"trace\""), "不得恢复 trace 执行分支或补全项");
        assertFalse(source.contains("|trace"), "用法提示不得保留 trace");
        assertFalse(source.contains("toggleHandCardTrace"), "命令不能再启用手牌追踪");
    }

    @Test
    void 旧debugShowStickHud入口均不存在() throws IOException {
        String source = Files.readString(COMMAND);

        assertFalse(source.contains("debug show"), "不得恢复 /muz debug show");
        assertFalse(source.contains("debug stick"), "不得恢复 /muz debug stick");
        assertFalse(source.contains("debug hud"), "不得恢复 /muz debug hud");
    }
}
