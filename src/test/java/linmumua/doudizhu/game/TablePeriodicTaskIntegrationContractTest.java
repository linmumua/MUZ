package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * @author linmumua
 * @Desc 牌桌周期任务与实体放置生命周期的最小集成契约
 * @date 2026-09-21
 */
class TablePeriodicTaskIntegrationContractTest {
    private static final Path TABLE_MANAGER = Path.of(
        "src/main/java/linmumua/doudizhu/game/TableManager.java");
    private static final Path PHYSICAL_MANAGER = Path.of(
        "src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path GAME_TABLE = Path.of(
        "src/main/java/linmumua/doudizhu/game/GameTable.java");
    private static final Path ROUND_OPENING = Path.of(
        "src/main/java/linmumua/doudizhu/game/RoundOpeningCoordinator.java");
    private static final Path BOT_AI = Path.of(
        "src/main/java/linmumua/doudizhu/game/BotAiCoordinator.java");
    private static final Path TIMED_OUT = Path.of(
        "src/main/java/linmumua/doudizhu/game/TimedOutPlayCoordinator.java");

    @Test
    void TableManager创建失败会清理本次新建桌而非留下global周期任务() throws IOException {
        String source = Files.readString(TABLE_MANAGER);
        assertTrue(source.contains("tables.remove(key, table);"));
        assertTrue(source.contains("table.shutdown();"));
        assertTrue(source.contains("registerTablePeriodicTask(table, 1L, 10L, table::tickActionBar)"));
    }

    @Test
    void 关键牌桌回调必须经过owner投递门面而不是直接回到global() throws IOException {
        String gameTable = Files.readString(GAME_TABLE);
        String opening = Files.readString(ROUND_OPENING);
        String botAi = Files.readString(BOT_AI);
        String timedOut = Files.readString(TIMED_OUT);
        assertTrue(gameTable.contains("manager.runTableLater(this"));
        assertTrue(gameTable.contains("manager.runTableTimer(GameTable.this"));
        assertTrue(opening.contains("support.runTableTimer(0L, 1L"));
        assertTrue(botAi.contains("support.runTableNow(() ->"));
        assertTrue(timedOut.contains("support.runTableNow(() ->"));
    }

    @Test
    void 一次性任务lane只读取TableManager私有锚点副本并在卸载后回到global() throws IOException {
        String source = Files.readString(TABLE_MANAGER);
        assertTrue(source.contains("tableOwnerAnchors"));
        assertTrue(source.contains("tableOwnerAnchors.remove(key);"), "创建/卸载/注销必须能清除旧锚点");
        assertTrue(source.contains("org.bukkit.Location anchor = ownerAnchor(ownerKey);"),
            "周期与一次性任务必须从 TableManager owner 状态取锚点");
        assertTrue(source.contains("setOwnerAnchor(ownerKey, safeAnchor);"),
            "成功重绑定后才提交新的 owner 锚点");
        assertTrue(source.contains("tableOwnerAnchors.remove(key);"),
            "markTableUnplaced 后必须让后续一次性任务回到 global");
        assertFalse(source.contains("plugin.getPhysicalTableManager().tableAnchor(table.getName())"),
            "TableManager 不得把 PhysicalTableManager.tableAnchor 作为唯一 lane 判断");
        assertTrue(source.contains("return anchor == null ? null : anchor.clone();"),
            "owner 锚点读取必须返回副本，不能暴露可变 Location");
    }

    @Test
    void 每个放置写入后必须先通知锚点绑定再刷新牌桌() throws IOException {
        String source = Files.readString(PHYSICAL_MANAGER);
        assertTrue(source.contains("putPlacedTable("), "所有放置路径必须通过统一索引入口写入牌桌");
        assertTrue(source.contains("notifyTableAnchorBinding(table);"), "放置后必须通知周期绑定");
        assertTrue(source.contains("cancelOwnerPeriodicTasks(placed.tableName());"));
        // 机制变更（非弱化）：重建原先靠 markTableUnplaced(previous.tableName()) 把桌先切回未放置，会开出一个
        // "这张桌不存在"的窗口——窗口内 cleanupIfEmpty 会把空桌注销（永久丢桌），重建失败也会连旧桌一起丢。
        // 现在旧 owner 绑定与放置快照都保留到收口提交，lane 切换改由收口处的 notifyTableAnchorBinding 完成。
        // 断言方向随之反转：锁的是"重建不得提前切回未放置 / 提前摘除旧桌"。
        String capture = between(source,
            "private RebuildRequest captureSingleRebuild(String tableKey, double deltaY)",
            "private RebuildRequest freezeRebuildRequest(");
        assertFalse(capture.contains("markTableUnplaced("),
            "重建不得提前把桌切回未放置：那会放开 cleanupIfEmpty 把空桌注销（永久丢桌）");
        assertFalse(capture.contains("placedTables.remove("), "重建不得提前摘除旧桌");
        assertTrue(capture.contains("rebuildingTableKeys.add(tableKey)"),
            "重建互斥必须由显式在飞标记承担（旧实现靠先摘除旧桌）");
    }

    private static String between(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "缺少源码入口: " + startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(end > start, "缺少源码结束边界: " + endMarker);
        return source.substring(start, end);
    }
}
