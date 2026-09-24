package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 交互处理 catch 必须"既提示、又留痕"的源码契约。
 *
 * <p>背景：{@code handleInteraction} 及同源的点击 catch（手牌选牌/出牌）以前只给玩家一条 ActionBar 提示，
 * 不写服务端日志。真正的失败（例如调度器拒绝 0 初始延迟抛出的 {@code IllegalArgumentException}）因此被静默吞掉，
 * 实服里只看到玩家一句提示、控制台没有任何堆栈。修复后新增了限频留痕助手
 * {@code reportInteractionFailure}，本测试钉住三处 catch 的挂载点不会回退。
 *
 * <p>用源码扫描而不是调用方法：这条链路要 Bukkit 的 Player / Entity / 事件，行为级验证放在
 * {@code InteractionFailureLoggingBehaviorTest}，这里只负责"三处 catch 都还在提示 + 留痕"。
 */
class InteractionFailureLoggingContractTest {
    private static final Path MANAGER =
        Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");

    /**
     * 三处"只提示"catch 都必须改成"先留痕、再照旧提示玩家"。
     *
     * <p>失败条件：任一 catch 删掉 {@code reportInteractionFailure(...)}（回到静默）或删掉
     * {@code hint(...)}（玩家失去反馈）。两者都是回归。
     */
    @Test
    void everyHintOnlyInteractionCatchAlsoLogsAndStillHints() throws IOException {
        assertCatchLogsAndHints(
            "public boolean handleInteraction(Player player, Entity entity)",
            "按钮点击");
        assertCatchLogsAndHints(
            "private void toggleHandCardSelection(GameTable table, PlacedTable placed, Player player, int cardId)",
            "手牌选牌");
        assertCatchLogsAndHints(
            "private void playSelectedHandCard(GameTable table, Player player, int cardId)",
            "手牌出牌");
    }

    /**
     * 留痕助手必须真的把异常本体交给 logger，并按 WARNING 记录、按「动作 + 异常摘要」限频。
     *
     * <p>失败条件：只记 {@code getMessage()} 字符串（丢掉堆栈，实服无法定位）、降级到非 WARNING
     * （淹没在 INFO 里）、或去掉限频（高频点击刷屏）。
     */
    @Test
    void reportingHelperLogsTheThrowableAtWarningAndRateLimits() throws IOException {
        String helper = methodBody(MANAGER,
            "private void reportInteractionFailure(String action, Player player, RuntimeException exception)");

        assertTrue(helper.contains("plugin.getLogger().log("),
            "留痕助手必须走 plugin.getLogger() 记录，不能在 catch 里另造一处日志出口");
        assertTrue(helper.contains("java.util.logging.Level.WARNING"),
            "交互失败必须按 WARNING 记录，降级到 INFO 会让真实失败淹没在常规日志里");
        // 异常本体作为第三个参数交给 logger（JUL 的 log(Level, String, Throwable)）；
        // 这里取结束片段而不是整行，因为它被排在日志文本之后另起一行。
        assertTrue(helper.contains("exception);"),
            "必须把异常本体（含堆栈）交给 logger，只记 getMessage() 会让实服看不到堆栈");
        assertTrue(helper.contains("interactionFailureLogMillis") && helper.contains("INTERACTION_FAILURE_LOG_INTERVAL_MILLIS"),
            "留痕必须限频：同一动作+同一异常在窗口内只记一条，否则高频点击会刷屏");
    }

    private static void assertCatchLogsAndHints(String signature, String what) throws IOException {
        String body = methodBody(MANAGER, signature);
        assertTrue(body.contains("reportInteractionFailure("),
            what + " 的 catch 只提示不记日志：真正的失败会在服务端完全不可见");
        assertTrue(body.contains("hint(player.getUniqueId(), exception.getMessage(), NamedTextColor.RED)"),
            what + " 的 catch 丢失了玩家提示：玩家点了没反应会连提示都没有");
    }

    private static String methodBody(Path file, String signature) throws IOException {
        String source = Files.readString(file);
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
