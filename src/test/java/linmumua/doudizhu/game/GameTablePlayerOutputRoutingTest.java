package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.lang.reflect.Field;
import sun.misc.Unsafe;
import org.junit.jupiter.api.Test;

/**
 * GameTable 玩家输出路由契约：牌桌 owner 只保存 UUID，实际 Bukkit Player API 由 player lane 执行。
 */
class GameTablePlayerOutputRoutingTest {
    private static final Path SOURCE = Path.of("src/main/java/linmumua/doudizhu/game/GameTable.java");

    @Test
    void directPlayerOutputMustUseUuidDispatcher() throws IOException {
        String source = stripComments(Files.readString(SOURCE, StandardCharsets.UTF_8));

        assertTrue(source.contains("private final PlayerOutputDispatcher outputDispatcher"),
            "GameTable 必须持有共享玩家输出门面");
        assertTrue(source.contains("this.outputDispatcher = this.actionBarOverlay.outputDispatcher()"),
            "玩家输出门面必须复用牌桌 ActionBar 服务的 player lane");
        for (String directCall : new String[] {
            "player.sendMessage(",
            "player.showTitle(",
            "player.sendActionBar(",
            "player.playSound(",
            "player.stopSound("
        }) {
            assertFalse(source.contains(directCall),
                "GameTable 不得直接调用 " + directCall + "，必须改为 UUID + PlayerOutputDispatcher");
        }

        assertTrue(source.contains("outputDispatcher.sendMessage(seats, MuzTheme.danger(message))"),
            "结算失败通知必须按座位 UUID 投递");
        assertTrue(source.contains("outputDispatcher.showTitle(seat, title)"),
            "未准备标题必须按座位 UUID 投递");
        assertTrue(source.contains("outputDispatcher.sendMessage(\n                seat,\n                roundChatMessageForSeat"),
            "结算聊天必须按座位 UUID 投递");
        assertFalse(source.contains("import org.bukkit.Bukkit;"), "GameTable 不得直接依赖 Bukkit 玩家解析");
        assertFalse(source.contains("Bukkit.getPlayer("), "GameTable 不得直接调用 Bukkit.getPlayer");
        assertFalse(source.contains("Bukkit.getOfflinePlayer("), "GameTable 不得直接调用 Bukkit.getOfflinePlayer");
        assertFalse(source.contains(".isOnline()"), "GameTable owner 逻辑不得直接调用 Player.isOnline");
    }

    @Test
    void uuidCoreAdaptersAndNameSnapshotRemainExplicit() throws Exception {
        String source = stripComments(Files.readString(SOURCE, StandardCharsets.UTF_8));
        assertTrue(source.contains("private final Map<UUID, String> playerNames"), "必须保留桌内 UUID→名称快照");
        assertTrue(source.contains("public void addPlayer(UUID playerId, String playerName)"), "入桌核心必须走 UUID/name");
        for (String method : new String[] {
            "public void toggleReady(UUID playerId)",
            "public void bid(UUID playerId, int points)",
            "public void chooseDouble(UUID playerId, boolean doubled)",
            "public void playSelected(UUID playerId)",
            "public void pass(UUID playerId)"
        }) {
            assertTrue(source.contains(method), "缺少 UUID 核心入口: " + method);
        }
        assertTrue(source.contains("return playerId.toString().substring(0, 8)"), "名称缺失时必须使用 UUID fallback");

        GameTable table = unsafeTable();
        UUID human = UUID.randomUUID();
        UUID bot = UUID.randomUUID();
        setField(table, "playerNames", new LinkedHashMap<>(java.util.Map.of(human, "快照玩家")));
        setField(table, "botNames", new LinkedHashMap<>(java.util.Map.of(bot, "Bot-1")));
        assertTrue("快照玩家".equals(table.displayName(human)), "真人名称必须来自入桌快照");
        assertTrue("Bot-1".equals(table.displayName(bot)), "机器人名称必须优先来自 bot 名称");
        assertTrue(table.displayName(UUID.randomUUID()).length() == 8, "未知玩家必须回退 UUID 前缀");
    }

    @Test
    void tableOwnerDelayedActionbarMustNotCapturePlayer() throws IOException {
        String source = stripComments(Files.readString(SOURCE, StandardCharsets.UTF_8));
        String body = methodBody(source,
            "private void broadcastStickyOutcomeActionBar(List<UUID> winners)",
            "    private void resetRound()");

        assertTrue(body.contains("manager.runTableLater(this"),
            "牌桌 owner 的延迟 ActionBar 必须保留在牌桌 owner lane");
        assertTrue(body.contains("dispatchActionBar(seat, bar, 25)"),
            "延迟结果必须通过 UUID ActionBar 路由");
        assertFalse(body.contains("Player "),
            "异步闭包内不得捕获或保存旧 Player");
        assertFalse(body.contains("onlinePlayer("),
            "延迟 ActionBar 不应在闭包内读取旧 Player，只交给 player lane 解析当前玩家");
    }

    @Test
    void persistentActionBarUsesUuidHudEntryPoint() throws IOException {
        String source = stripComments(Files.readString(SOURCE, StandardCharsets.UTF_8));
        String body = methodBody(source,
            "private void broadcastPersistentActionBar(int remainingSeconds)",
            "    private void dispatchTrickHud(UUID viewerId)");
        assertTrue(body.contains("dispatchTrickHud(playerId)"), "持久 ActionBar 必须通过 UUID HUD 入口");
        assertFalse(body.contains("onlinePlayer("), "owner lane 不得先解析在线 Player");
        assertFalse(body.contains("Player viewer"), "owner lane 不得持有 HUD viewer Player");
        assertTrue(source.contains("dispatcher.runPlayer(viewerId, this::sendTrickHud)"),
            "TrickHud 的旧 Player 输入必须由 UUID 输出门面在 player lane 适配");
    }

    @Test
    void outputMethodsKeepTextAndRouteSemantics() throws IOException {
        String source = stripComments(Files.readString(SOURCE, StandardCharsets.UTF_8));
        String broadcast = methodBody(source,
            "private void broadcast(Component message)",
            "    private void broadcastOpeningActionBar()");
        String actionBar = methodBody(source,
            "private void broadcastActionBar(Component message)",
            "    private void dispatchActionBar(UUID playerId, Component message, int durationTicks)");
        String title = methodBody(source,
            "private void playUnreadyWarning(List<UUID> unreadySeats)",
            "    private void sendRoundChatBundles(");
        String chat = methodBody(source,
            "private void sendRoundChatBundles(",
            "    private Component roundChatMessageForSeat(");

        assertTrue(broadcast.contains("outputDispatcher.sendMessage(seat, full)"),
            "普通广播文本必须保留原消息并改走 UUID 输出");
        assertTrue(broadcast.contains("dispatchActionBar(seat, actionBar, 60)"),
            "普通广播 ActionBar 必须保留原时长");
        assertTrue(actionBar.contains("dispatchActionBar(seat, actionBar, 60)"),
            "阶段 ActionBar 必须保留原时长");
        assertTrue(title.contains("effectCoordinator.playConfiguredSound(seat, sound)"),
            "未准备提示音必须保留原音效协调器路径");
        assertTrue(title.contains("outputDispatcher.showTitle(seat, title)"),
            "未准备标题必须改走 player lane");
        assertTrue(chat.contains("roundChatMessageForSeat(seat, settlement, settlementView, summary)"),
            "结算聊天内容必须继续按座位生成");
        assertTrue(chat.contains("outputDispatcher.sendMessage("),
            "结算聊天必须改走 UUID 输出");
    }

    private static GameTable unsafeTable() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (GameTable) ((Unsafe) field.get(null)).allocateInstance(GameTable.class);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = GameTable.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static String methodBody(String source, String signature, String nextSignature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到方法 " + signature);
        int end = source.indexOf(nextSignature, start);
        assertTrue(end > start, "找不到方法结束锚点 " + nextSignature);
        return source.substring(start, end);
    }
}
