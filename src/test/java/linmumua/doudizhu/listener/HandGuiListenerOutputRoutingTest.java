package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Hand GUI 的玩家输出必须走 UUID 门面，避免延迟回调继续调用旧 Player。 */
class HandGuiListenerOutputRoutingTest {
    private static final Path LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/HandGuiListener.java");

    @Test
    void playerOutputUsesDispatcherInsteadOfDirectPlayerApi() throws IOException {
        String source = Files.readString(LISTENER);

        assertTrue(source.contains("private final PlayerOutputDispatcher output;"));
        assertTrue(source.contains("this.output = plugin.getActionBarOverlayService().outputDispatcher();"));
        assertTrue(source.contains("output.sendMessage(player.getUniqueId()"));
        assertTrue(source.contains("output.sendActionBar(playerId"));
        assertTrue(source.contains("output.playSound(playerId"));
        assertTrue(source.contains("output.closeInventory(player.getUniqueId())"));
        assertFalse(source.contains("player.sendMessage("));
        assertFalse(source.contains("player.sendActionBar("));
        assertFalse(source.contains("player.playSound("));
        assertFalse(source.contains("player.closeInventory("));
    }

    @Test
    void asyncChatUsesUuidPlayerLaneWithoutCapturingEventPlayer() throws IOException {
        String source = Files.readString(LISTENER);
        String body = methodBody(source, "public void onAsyncChat(AsyncPlayerChatEvent event)");

        assertTrue(body.contains("UUID playerId = event.getPlayer().getUniqueId();"));
        assertTrue(body.contains("output.runPlayer(playerId, current ->"));
        assertTrue(body.contains("handlePendingSoundInput(current, plain)"));
        assertTrue(body.contains("handlePendingSignInput(current, plain)"));
        assertFalse(body.contains("plugin.scheduler().runSync"));
        assertFalse(body.contains("handlePendingSoundInput(event.getPlayer()"));
        assertFalse(body.contains("handlePendingSignInput(event.getPlayer()"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature);
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
