package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * @author linmumua
 * @Desc 牌桌世界 owner tick 的最小源码契约
 * @date 2026-09-21
 */
class PhysicalTableOwnerTickContractTest {
    private static final Path PHYSICAL_MANAGER = Path.of(
        "src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path PLAYER_OUTPUT_DISPATCHER = Path.of(
        "src/main/java/linmumua/doudizhu/game/PlayerOutputDispatcher.java");
    private static final Path TABLE_MANAGER = Path.of(
        "src/main/java/linmumua/doudizhu/game/TableManager.java");
    private static final Path PERIODIC_REGISTRY = Path.of(
        "src/main/java/linmumua/doudizhu/game/TablePeriodicTaskRegistry.java");
    private static final Path GAME_TABLE = Path.of(
        "src/main/java/linmumua/doudizhu/game/GameTable.java");
    private static final Path PLUGIN = Path.of(
        "src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");

    @Test
    void 世界刷新入口只接受单桌且不再扫描全服玩家或全部已放置桌() throws IOException {
        String source = Files.readString(PHYSICAL_MANAGER);
        int start = source.indexOf("public void tickTable(GameTable table)");
        assertTrue(start >= 0, "PhysicalTableManager 必须提供单桌 owner tick 入口");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "单桌 owner tick 入口必须有完整方法体");
        String tick = source.substring(start, end);
        assertFalse(tick.contains("Bukkit.getOnlinePlayers()"), "单桌 tick 不得扫描全服在线玩家");
        assertFalse(tick.contains("placedTables.values()"), "单桌 tick 不得遍历全部已放置桌");
        assertFalse(source.contains("public void tick()"), "不得保留全局 PhysicalTableManager.tick 入口");
    }

    @Test
    void 已放置桌世界tick绑定锚点region且未放置桌不注册() throws IOException {
        String source = Files.readString(TABLE_MANAGER);
        assertTrue(source.contains("private final TablePeriodicTaskRegistry worldPeriodicTasks;"));
        assertTrue(source.contains("worldPeriodicTasks.register("));
        assertTrue(source.contains("plugin.getPhysicalTableManager().tickTable(table);"));
        assertTrue(source.contains("new TablePeriodicTaskRegistry.PeriodicDescription(1L, 1L)"));
        String registry = Files.readString(PERIODIC_REGISTRY);
        assertTrue(registry.contains("scheduler.runRegionTimer"), "周期注册表必须按锚点选择 region lane");
        assertTrue(source.contains("if (anchor == null) {\n            worldPeriodicTasks.cancelOwner(table);"),
            "未放置桌必须取消世界 owner tick");
    }

    @Test
    void 插件入口不再从global任务驱动PhysicalTableManager且主动离桌显式清缓存() throws IOException {
        String source = Files.readString(PLUGIN);
        assertFalse(source.contains("physicalTableManager.tick();"));
        assertTrue(source.contains("tableSpeechPanelService.tick();"), "语音面板独立生命周期仍需保留");
        String gameTable = Files.readString(GAME_TABLE);
        assertTrue(gameTable.contains("plugin.getPhysicalTableManager().clearPlayerCaches(playerId);"),
            "拆掉全局 tick 后，主动离桌仍必须清理玩家 hover/调试缓存");
    }

    @Test
    void 跨桌viewer同步先拍UUID再逐桌投递owner且不在global路径改实体() throws IOException {
        String source = Files.readString(PHYSICAL_MANAGER);
        int start = source.indexOf("private void syncViewer(UUID viewerId)");
        assertTrue(start >= 0, "跨桌同步必须提供 UUID 入口");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "UUID viewer 同步入口必须有完整方法体");
        String sync = source.substring(start, end);
        assertTrue(sync.contains("List<String> tableNames"), "跨桌同步应先固定桌名快照，避免遍历期间修改集合");
        assertTrue(sync.contains("runTableNow(table, () -> syncViewerOnOwner(table, viewerId))"),
            "每张桌必须由其 owner lane 执行同步");
        assertFalse(sync.contains("showEntity("), "global 跨桌入口不得直接调用玩家实体 API");
        assertFalse(sync.contains("hideEntity("), "global 跨桌入口不得直接调用玩家实体 API");
    }

    @Test
    void PhysicalTableManager的玩家输出只经UUID快照和PlayerOutputDispatcher() throws IOException {
        String source = Files.readString(PHYSICAL_MANAGER);
        assertTrue(source.contains("private final PlayerOutputDispatcher playerOutput;"),
            "牌桌管理器必须持有玩家输出门面");
        assertTrue(source.contains("private final PlayerPresenceRegistry playerPresence;"),
            "在线玩家来源必须是连接生命周期维护的 UUID registry");
        int snapshotStart = source.indexOf("private List<UUID> onlinePlayerIdsSnapshot()");
        assertTrue(snapshotStart >= 0, "在线玩家遍历只能读取 UUID 快照");
        int snapshotEnd = source.indexOf("\n    }", snapshotStart);
        assertTrue(snapshotEnd > snapshotStart, "UUID 快照入口必须有完整方法体");
        String snapshotMethod = source.substring(snapshotStart, snapshotEnd);
        assertFalse(snapshotMethod.contains("Bukkit.getOnlinePlayers()"),
            "PhysicalTableManager owner lane 不得枚举 Bukkit Player");
        assertTrue(source.contains("markPlayerConnected(viewerId);"),
            "viewer 连接生命周期必须能登记 UUID");
        assertTrue(source.contains("public void markPlayerDisconnected(UUID playerId)"),
            "连接监听器必须有 UUID 离线注入点");
        assertTrue(source.contains("playerOutput.showEntity("), "显示实体必须进入 player lane");
        assertTrue(source.contains("playerOutput.hideEntity("), "隐藏实体必须进入 player lane");
        assertTrue(source.contains("playerOutput.sendMessage("), "追踪消息必须进入 player lane");
        assertFalse(source.matches("(?s).*\\b(?:player|viewer|other)\\.(?:showEntity|hideEntity|sendMessage|sendActionBar|playSound|stopSound)\\(.*"),
            "PhysicalTableManager 不得直接调用玩家输出 API");
    }

    @Test
    void 玩家实体可见性必须按UUID在player_lane重新解析且旧Entity重载不得捕获实体() throws IOException {
        // 统一换行为 LF 再匹配跨行片段：本断言针对的是「旧重载的实现形状」，不是文件的换行符，
        // 而 Files.readString 不做换行翻译（Windows 工作区源码为 CRLF），不归一化会把
        // 「实现没退化」误报成「旧重载不得创建捕获旧 Entity 的闭包」。
        String dispatcher = Files.readString(PLAYER_OUTPUT_DISPATCHER).replace("\r\n", "\n");
        assertTrue(dispatcher.contains(
            "public void showEntity(UUID viewerId, Plugin plugin, UUID entityId)"),
            "PlayerOutputDispatcher 必须提供 UUID-first showEntity 入口");
        assertTrue(dispatcher.contains(
            "public void hideEntity(UUID viewerId, Plugin plugin, UUID entityId)"),
            "PlayerOutputDispatcher 必须提供 UUID-first hideEntity 入口");
        assertTrue(dispatcher.contains("Entity entity = Bukkit.getEntity(entityId);"),
            "实体可见性必须在 player lane 通过 UUID 重新解析实体");
        assertTrue(dispatcher.contains("entity != null && entity.isValid()"),
            "重新解析的实体必须经过有效性校验");

        int showLegacyStart = dispatcher.indexOf(
            "public void showEntity(UUID viewerId, Plugin plugin, Entity entity)");
        int showLegacyEnd = dispatcher.indexOf(
            "public void hideEntity(UUID viewerId, Plugin plugin, UUID entityId)", showLegacyStart);
        assertTrue(showLegacyStart >= 0 && showLegacyEnd > showLegacyStart,
            "showEntity 旧重载必须保留兼容入口");
        String showLegacy = dispatcher.substring(showLegacyStart, showLegacyEnd);
        assertTrue(showLegacy.contains("showEntity(viewerId, plugin, entity.getUniqueId());"),
            "showEntity 旧重载必须立即转换为 UUID");
        assertFalse(showLegacy.contains("runPlayer("),
            "showEntity 旧重载不得把旧 Entity 捕获进 player lane");
        assertFalse(showLegacy.contains("->"),
            "showEntity 旧重载不得创建捕获旧 Entity 的闭包");

        int hideLegacyStart = dispatcher.indexOf(
            "public void hideEntity(UUID viewerId, Plugin plugin, Entity entity)");
        // 结束边界必须落在旧重载**自己的方法体结尾**。原先用「下一个无关成员的 javadoc」当边界，一旦在两者
        // 之间新增任何成员（例如 reportCrossRegionSkip），该区间就会把它整段吞进来，于是那段合法代码里的
        // lambda 会让「旧重载不得创建闭包」这条断言假失败——断言本身没变，是真边界变了。
        // 这里改用它自身的末行（UUID 转换调用）定位，把方法体收在自己的边界内，新增成员不再影响该区间。
        String conversion = "hideEntity(viewerId, plugin, entity.getUniqueId());";
        int hideLegacyConversion = dispatcher.indexOf(conversion, hideLegacyStart);
        assertTrue(hideLegacyStart >= 0 && hideLegacyConversion > hideLegacyStart,
            "hideEntity 旧重载必须保留兼容入口");
        // 边界必须覆盖旧重载**整个方法体**：取转换调用之后第一个方法级闭合行（8 空格缩进的 `}`），
        // 这样「转换之后又追加闭包 / runPlayer」这类回归仍会被下面的断言抓住。
        // 注意不能取到下一个成员的 javadoc——那会把 reportCrossRegionSkip 整段吞进来，
        // 让其中合法的 lambda 造成假失败（这正是修正前的失败原因）；也不能只截到转换那一行，
        // 那样区间看不见方法体后半段的回归，等于把断言架空。
        int hideLegacyEnd = dispatcher.indexOf("\n    }", hideLegacyConversion);
        assertTrue(hideLegacyEnd > hideLegacyConversion,
            "hideEntity 旧重载必须能在方法体闭合处界定");
        String hideLegacy = dispatcher.substring(hideLegacyStart, hideLegacyEnd);
        assertTrue(hideLegacy.contains("entity == null"), "hideEntity 旧重载必须保留空值保护");
        assertTrue(hideLegacy.contains(conversion), "hideEntity 旧重载必须立即转换为 UUID");
        assertFalse(hideLegacy.contains("runPlayer("),
            "hideEntity 旧重载不得把旧 Entity 捕获进 player lane");
        assertFalse(hideLegacy.contains("->"),
            "hideEntity 旧重载不得创建捕获旧 Entity 的闭包");
    }

    @Test
    void UUID_presence_registry只发布不可变快照并保持连接生命周期顺序() {
        PhysicalTableManager.PlayerPresenceRegistry registry = new PhysicalTableManager.PlayerPresenceRegistry();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        registry.markConnected(alice);
        registry.markConnected(bob);
        List<UUID> snapshot = registry.snapshot();
        assertEquals(List.of(alice, bob), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(UUID.randomUUID()));

        registry.markDisconnected(alice);
        assertEquals(List.of(bob), registry.snapshot());
        registry.markDisconnected(alice);
        assertEquals(List.of(bob), registry.snapshot());
    }
}
