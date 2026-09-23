package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** WorldTableInteractionListener 的玩家输出必须统一经 PlayerOutputDispatcher。 */
class WorldTableInteractionListenerOutputRoutingTest {
    private static final Path LISTENER =
        Path.of("src/main/java/linmumua/doudizhu/listener/WorldTableInteractionListener.java");

    @Test
    void 玩家输出不直接调用PlayerApi() throws IOException {
        String source = Files.readString(LISTENER);

        assertTrue(source.contains("output.sendActionBar"), "ActionBar 必须经 PlayerOutputDispatcher");
        assertTrue(source.contains("output.spawnParticle"), "粒子必须经 PlayerOutputDispatcher");
        assertTrue(source.contains("output.playSound"), "声音必须经 PlayerOutputDispatcher");
        assertFalse(source.contains("player.sendActionBar("), "监听器不得直接调用 Player.sendActionBar");
        assertFalse(source.contains("player.spawnParticle("), "监听器不得直接调用 Player.spawnParticle");
        assertFalse(source.contains("player.playSound("), "监听器不得直接调用 Player.playSound");
        assertFalse(source.contains("plugin.playPlacementBlockedWarning("), "声音警告不得绕过 PlayerOutputDispatcher");
    }

    @Test
    void 延迟换手回调只按UUID重新解析玩家() throws IOException {
        String source = Files.readString(LISTENER);
        String body = methodBody(source, "public void onHudDebugStickHeldChange(PlayerItemHeldEvent event)");

        assertTrue(body.contains("UUID playerId = event.getPlayer().getUniqueId()"),
            "事件处理阶段只能把玩家身份转成 UUID 交给延迟回调");
        assertTrue(body.contains("output.runPlayer(playerId"),
            "延迟回调必须把 UUID 投递到 player lane 后再读取当前玩家");
        assertFalse(body.contains("Player player = event.getPlayer()"),
            "延迟回调不得捕获事件中的 Player 引用");
    }

    @Test
    void global预览tick只保留UUID并经玩家lane读取即时快照() throws IOException {
        String source = Files.readString(LISTENER);
        String globalBody = methodBody(source, "private void tickToolPreviews()");
        assertFalse(globalBody.contains("Bukkit.getPlayer"), "global 预览 tick 不得直接解析 Bukkit Player");
        assertFalse(globalBody.contains("getInventory()"), "global 预览 tick 不得读取玩家背包");
        assertFalse(globalBody.contains("getEyeLocation()"), "global 预览 tick 不得读取玩家位置");
        assertFalse(globalBody.contains("getWorld()"), "global 预览 tick 不得读取玩家世界");

        for (String signature : new String[] {
            "private void tickHudDebugPreviews()",
            "private void tickTablePlacerPreviews()",
            "private void tickTableRemoverPreviews()"
        }) {
            String body = methodBody(source, signature);
            assertTrue(body.contains("output.runPlayer(playerId"),
                signature + " 必须按 UUID 投递到 player lane");
            assertTrue(body.contains("List.copyOf"),
                signature + " 必须先固定 UUID/预览状态快照，不能在投递期间直接遍历可变集合");
            assertFalse(body.contains("plugin.getServer().getPlayer"),
                signature + " 不得在 global lane 直接解析 Player");
        }

        assertTrue(source.contains("spawnTablePlacerPreview(UUID playerId, World playerWorld"),
            "放桌预览实体操作只能接收 UUID 与玩家世界快照");
        assertTrue(source.contains("spawnTableRemoverPreview(UUID playerId, Location playerEye"),
            "拆桌预览实体操作只能接收 UUID 与玩家视线快照");
        assertTrue(source.contains("runTableNow(table, () -> spawnTableRemoverPreview(playerId, playerEye, target))"),
            "拆桌预览读取牌桌几何必须回到对应牌桌 owner lane");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到方法: " + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "找不到方法体: " + signature);
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(start, index);
            }
        }
        throw new AssertionError("找不到方法结束位置: " + signature);
    }
}
