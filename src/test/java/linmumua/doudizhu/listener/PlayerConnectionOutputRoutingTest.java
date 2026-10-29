package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 资源包反馈与重连 warmup 必须先进入 UUID 玩家输出门面。 */
class PlayerConnectionOutputRoutingTest {
    private static final Path LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/PlayerConnectionListener.java");

    @Test
    void resourcePackMessagesUseDispatcherInsteadOfDirectPlayerOutput() throws IOException {
        String source = Files.readString(LISTENER);
        String statusBody = methodBody(source, "public void onResourcePackStatus(PlayerResourcePackStatusEvent event)");

        assertTrue(statusBody.contains("event.getPlayer().getUniqueId()"));
        assertTrue(statusBody.contains("output.sendActionBar(playerId"));
        assertTrue(statusBody.contains("output.sendMessage(playerId"));
        assertFalse(statusBody.contains("event.getPlayer().sendActionBar"));
        assertFalse(statusBody.contains("event.getPlayer().sendMessage"));
    }

    @Test
    void viewerWarmupUsesPlayerLaneAndOfflinePathsCancelUuidTasks() throws IOException {
        String source = Files.readString(LISTENER);
        String warmupBody = methodBody(source, "private void scheduleViewerWarmup(org.bukkit.entity.Player player, String reason)");

        assertTrue(warmupBody.contains("output.runPlayer(playerId, delay"));
        assertTrue(source.contains("output.cancel(playerId)"));
    }

    @Test
    void connectionPresenceHooksPreserveWarmupAndCleanupOrder() throws IOException {
        String source = Files.readString(LISTENER);
        String joinBody = methodBody(source, "public void onJoin(PlayerJoinEvent event)");
        assertBefore(joinBody, "markPlayerConnected(playerId)", "scheduleViewerWarmup(event.getPlayer(), \"join\")");

        for (String signature : new String[] {
            "public void onQuit(PlayerQuitEvent event)",
            "public void onKick(PlayerKickEvent event)",
        }) {
            String body = methodBody(source, signature);
            assertBefore(body, "output.cancel(playerId)", "markPlayerDisconnected(playerId)");
            assertBefore(body, "markPlayerDisconnected(playerId)", "clearTableGadget(event.getPlayer())");
            assertBefore(body, "clearTableGadget(event.getPlayer())", "removePlayerSilently(");
            assertBefore(body, "removePlayerSilently(", "clearPlayerCaches(playerId)");
        }
    }

    @Test
    void warmupRepairsUseGlobalCoordinatorAndTableOwnerLane() throws IOException {
        String source = Files.readString(LISTENER);
        String warmupBody = methodBody(source, "private void scheduleViewerWarmup(org.bukkit.entity.Player player, String reason)");
        String coordinatorBody = methodBody(source, "private void scheduleIncompleteTableRepair(String reason)");

        assertTrue(warmupBody.contains("!current.isOnline()"));
        assertTrue(warmupBody.contains("scheduleIncompleteTableRepair("));
        assertTrue(warmupBody.contains("syncViewer(current)"));
        assertFalse(warmupBody.contains("repairIncompleteTables("));

        assertTrue(coordinatorBody.contains("plugin.scheduler().runGlobal("));
        assertTrue(coordinatorBody.contains("getTableManager().getTables()"));
        assertTrue(coordinatorBody.contains("runTableNow(table"));
        assertTrue(coordinatorBody.contains("table.getName()"));
        assertTrue(coordinatorBody.contains("repairIncompleteTables("));
    }

    @Test
    void loadedNoticeIsOnlyForOperators() {
        // 资源包加载完成提示在 CE 重载资源包时对每个在线玩家各触发一次，
        // 普通玩家不该收到这条管理员向的播报（用户明确要求仅 op 显示）。
        assertTrue(PlayerConnectionListener.showsLoadedNotice(true), "op 必须收到加载完成提示");
        assertFalse(PlayerConnectionListener.showsLoadedNotice(false), "普通玩家不得收到加载完成提示");
    }

    @Test
    void loadedNoticeBranchIsGatedButWarmupStillRuns() throws IOException {
        String source = Files.readString(LISTENER);
        String statusBody = methodBody(source, "public void onResourcePackStatus(PlayerResourcePackStatusEvent event)");

        assertTrue(statusBody.contains("showsLoadedNotice(event.getPlayer().isOp())"),
            "加载完成提示必须以 isOp() 门禁");
        assertBefore(statusBody, "if (showsLoadedNotice(", "output.sendMessage(playerId, progressMessage(\"资源包加载完成");
        // 反证：不得退回「无条件发给所有人」。
        assertFalse(statusBody.contains("case SUCCESSFULLY_LOADED -> {\n                output.sendMessage("),
            "加载完成提示不得再无条件发给所有玩家");
        // 资源包应用后仍要为该玩家补一次视图重同步，门禁只挡提示、不挡功能。
        assertTrue(statusBody.contains("scheduleViewerWarmup(event.getPlayer(), \"resource-pack\")"),
            "门禁只应影响提示，warmup 必须照旧执行");
        // 其余状态是玩家自己那次下载的结果，仍应逐人提示。
        assertTrue(statusBody.contains("你拒绝了服务器资源包。"), "拒绝提示仍需逐人发送");
        assertTrue(statusBody.contains("资源包下载失败"), "下载失败提示仍需逐人发送");
    }

    private static void assertBefore(String body, String first, String second) {
        int firstIndex = body.indexOf(first);
        int secondIndex = body.indexOf(second);
        assertTrue(firstIndex >= 0, "找不到顺序锚点 " + first);
        assertTrue(secondIndex >= 0, "找不到顺序锚点 " + second);
        assertTrue(firstIndex < secondIndex, first + " 必须在 " + second + " 之前");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature);
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
