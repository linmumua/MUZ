package linmumua.doudizhu.mahjong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 锁定麻将跨区域实体操作与玩家消息的 owner lane 边界。 */
class MahjongRegionMessageContractTest {
    private static final Path SOURCE = Path.of(
        "src/main/java/linmumua/doudizhu/mahjong/MahjongTableManager.java");

    @Test
    void tableEntityWorkUsesAnchorRegionAndTableGenerationBarrier() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("public MuzScheduler.TaskHandle runOnTableRegion(")
            && source.contains("plugin.scheduler().runRegion(table.anchor(), () ->"),
            "麻将实体操作必须投递到桌锚点所属 region，而不是 global lane");
        assertTrue(source.contains("long expectedGeneration = table.generation();")
            && source.contains("table.generation() == expectedGeneration")
            && source.contains("tables.get(table.id()) == table"),
            "迟到的跨区域实体回调必须同时通过 generation 与实例身份屏障");
        assertTrue(source.contains("table.generation() != expectedGeneration")
            && source.contains("cleanupOnTableRegion(table)"),
            "重绘和清理回调也必须在 region 内做代次校验后再操作实体");
    }

    @Test
    void playerMessagesCrossRegionBoundaryThroughPlayerLane() throws IOException {
        String source = Files.readString(SOURCE);

        assertTrue(source.contains("private final PlayerOutputDispatcher playerOutput")
            && source.contains("new PlayerOutputDispatcher(plugin)"),
            "麻将玩家输出必须统一经过 PlayerOutputDispatcher");
        assertTrue(source.contains("public MuzScheduler.TaskHandle runForPlayer(UUID playerId, java.util.function.Consumer<Player> task)")
            && source.contains("playerOutput.runPlayer(playerId, task)"),
            "玩家输出必须按 UUID 进入 player lane");
        assertTrue(source.contains("playerOutput.sendMessage(playerId, message)"),
            "跨区域消息只能通过 UUID 门面投递，不能在管理器里直接调用玩家 API");
        assertTrue(source.contains("sendToPlayer(player.getUniqueId(),"),
            "事件回调必须以 UUID 调用玩家输出入口，避免把 Player 带入迟到回调");
        assertFalse(source.contains("Bukkit.getPlayer")
            || source.contains("player.sendMessage")
            || source.contains("messages.forEach(player::sendMessage"),
            "麻将管理器不得直接解析或调用玩家消息 API");
    }

    @Test
    void broadcastResolvesPlayersThenDelegatesToPlayerLane() throws IOException {
        String source = Files.readString(SOURCE);
        int start = source.indexOf("private void broadcast(MahjongTableSession table, Component message)");
        assertTrue(start >= 0, "必须保留麻将广播入口");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "广播入口必须有完整方法体");
        String body = source.substring(start, end);

        assertTrue(body.contains("for (UUID playerId : table.occupants().values())"),
            "广播必须使用桌内玩家 UUID 快照");
        assertTrue(body.contains("sendToPlayer(playerId, message)"),
            "广播必须统一经过 UUID + player lane 门面");
        assertFalse(body.contains("Bukkit.getPlayer")
            || body.contains("plugin.scheduler().runGlobal")
            || body.contains("online.sendMessage"),
            "广播不得在生产者线程解析或直接触碰玩家 API");
    }
}
