package linmumua.doudizhu.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** CE 行为执行必须把玩家 API 投递到 player lane，控制台命令仍走 global/console。 */
class CeActionExecutorContractTest {
    private static final Path EXECUTOR =
        Path.of("src/main/java/linmumua/doudizhu/action/CeActionExecutor.java");

    @Test
    void 执行路径按玩家与控制台分流() throws IOException {
        String source = Files.readString(EXECUTOR);
        int execute = source.indexOf("public static void executePlayProfile(");
        int preview = source.indexOf("public static void previewPlayProfile(", execute);
        assertTrue(execute >= 0 && preview > execute, "必须能定位 CE 执行方法");
        String body = source.substring(execute, preview);

        assertTrue(body.contains("PlayerOutputDispatcher output = outputDispatcher(plugin)"),
            "执行路径必须取得统一玩家输出门面");
        assertTrue(body.contains("output.runPlayer(playerId"),
            "message/actionbar/title/play_sound/player-command 必须进入 player lane");
        assertTrue(body.contains("plugin.scheduler().runGlobal("),
            "控制台命令必须保持 global 调度语义");
        assertTrue(body.contains("Bukkit.getConsoleSender()"),
            "控制台命令必须使用 console sender");
        assertTrue(body.contains("current.performCommand"),
            "玩家命令必须由 player lane 内的当前玩家执行");
        assertFalse(body.contains("player.sendMessage("), "不能直接对旧 Player 发聊天消息");
        assertFalse(body.contains("player.sendActionBar("), "不能直接对旧 Player 发 ActionBar");
        assertFalse(body.contains("player.showTitle("), "不能直接对旧 Player 发标题");
        assertFalse(body.contains("player.performCommand("), "不能直接对旧 Player 执行命令");
        assertFalse(body.contains("player.playSound("), "不能直接对旧 Player 播放声音");
    }

    @Test
    void 预览路径只调用玩家输出门面() throws IOException {
        String source = Files.readString(EXECUTOR);
        int preview = source.indexOf("public static void previewPlayProfile(");
        int parse = source.indexOf("private static PlayerOutputDispatcher outputDispatcher(", preview);
        assertTrue(preview >= 0 && parse > preview, "必须能定位 CE 预览方法");
        String body = source.substring(preview, parse);

        assertTrue(body.contains("output.sendMessage("), "聊天预览必须走玩家输出门面");
        assertTrue(body.contains("output.sendActionBar("), "ActionBar 预览必须走玩家输出门面");
        assertTrue(body.contains("output.showTitle("), "标题预览必须走玩家输出门面");
        assertTrue(body.contains("output.playSound("), "声音预览必须走玩家输出门面");
        assertFalse(body.contains("player.sendMessage("), "预览不能直接对旧 Player 发聊天消息");
        assertFalse(body.contains("player.sendActionBar("), "预览不能直接对旧 Player 发 ActionBar");
        assertFalse(body.contains("player.showTitle("), "预览不能直接对旧 Player 发标题");
        assertFalse(body.contains("player.playSound("), "预览不能直接对旧 Player 播放声音");
    }
}
