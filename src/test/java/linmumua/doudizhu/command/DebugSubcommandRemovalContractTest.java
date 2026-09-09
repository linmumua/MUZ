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
    void 旧debugShowStickHud入口均不存在() throws IOException {
        String source = Files.readString(COMMAND);

        assertFalse(source.contains("debug show"), "不得恢复 /muz debug show");
        assertFalse(source.contains("debug stick"), "不得恢复 /muz debug stick");
        assertFalse(source.contains("debug hud"), "不得恢复 /muz debug hud");
    }
}
