package linmumua.doudizhu.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * @author linmumua
 * @Desc 牌桌区块/世界生命周期事件接缝的最小源码契约
 * @date 2026-09-22
 */
class TableWorldLifecycleListenerContractTest {
    private static final Path LISTENER = Path.of(
        "src/main/java/linmumua/doudizhu/listener/TableWorldLifecycleListener.java");
    private static final Path MANAGER = Path.of(
        "src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    private static final Path PLUGIN = Path.of(
        "src/main/java/linmumua/doudizhu/DoudizhuPlugin.java");

    @Test
    void 事件注册与投递只读取世界区块标识并进入global() throws IOException {
        String listener = Files.readString(LISTENER);
        String plugin = Files.readString(PLUGIN);

        assertTrue(listener.contains("ChunkLoadEvent"));
        assertTrue(listener.contains("ChunkUnloadEvent"));
        assertTrue(listener.contains("WorldUnloadEvent"));
        assertTrue(listener.contains("event.getWorld().getUID()"));
        assertTrue(listener.contains("event.getChunk().getX()"));
        assertTrue(listener.contains("event.getChunk().getZ()"));
        assertTrue(listener.contains("plugin.scheduler().runGlobal("));
        assertFalse(listener.contains("org.bukkit.entity"), "事件层不得导入实体 API");
        assertFalse(listener.contains("Bukkit.getEntity"), "事件层不得查询 Bukkit 实体");
        assertFalse(listener.contains("placedTables"), "事件层不得遍历 placedTables");
        assertTrue(plugin.contains(
            "registerEvents(new TableWorldLifecycleListener(this), this)"),
            "插件启用时必须注册世界生命周期监听器");
    }

    @Test
    void manager通过footprint和world索引提供不可变桌名快照() throws IOException {
        String source = Files.readString(MANAGER);

        assertTrue(source.contains("tableNamesForFootprintChunk(UUID worldId, int chunkX, int chunkZ)"));
        assertTrue(source.contains("tableNamesInWorld(UUID worldId)"));
        assertTrue(source.contains("new ChunkOwnerKey(worldId, packedChunkKey(chunkX, chunkZ))"));
        assertTrue(source.contains(".sorted(String.CASE_INSENSITIVE_ORDER)"));
        assertTrue(source.contains(".toList()"), "查询结果必须是不可变快照");
    }

    @Test
    void unload只切换global不清理实体或删除索引() throws IOException {
        String source = Files.readString(MANAGER);
        String chunkUnload = methodBody(source, "public void onChunkUnload(UUID worldId, int chunkX, int chunkZ)");
        String worldUnload = methodBody(source, "public void onWorldUnload(UUID worldId)");

        assertTrue(chunkUnload.contains("markTableUnplaced(tableName)"));
        assertTrue(worldUnload.contains("markTableUnplaced(tableName)"));
        assertFalse(chunkUnload.contains("cleanupPlacedTable"));
        assertFalse(chunkUnload.contains("removePlacedTable"));
        assertFalse(worldUnload.contains("cleanupPlacedTable"));
        assertFalse(worldUnload.contains("removePlacedTable"));
        assertFalse(chunkUnload.contains("clearPlacedTableIndexes"));
        assertFalse(worldUnload.contains("clearPlacedTableIndexes"));
    }

    @Test
    void chunkLoad按桌名去重并把实体检查放进owner回调() throws IOException {
        String source = Files.readString(MANAGER);

        assertTrue(source.contains("chunkLoadRepairQueued"), "必须有同桌加载任务去重集合");
        assertTrue(source.contains("if (!chunkLoadRepairQueued.add(key))"), "重复 ChunkLoad 不得重复排队");
        assertTrue(source.contains("runTableLater(table, 1L, () ->"),
            "重建/刷新必须延迟投递到牌桌 owner lane");
        assertTrue(source.contains("repairTableAfterChunkLoad(table)"));
        assertTrue(source.contains("isIncomplete(placed)"));
        assertTrue(source.contains("rebuildSingleTable(table.getName())"));
        assertTrue(source.contains("notifyTableAnchorBinding(table)"));
        assertTrue(source.contains("refresh(table)"));
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
