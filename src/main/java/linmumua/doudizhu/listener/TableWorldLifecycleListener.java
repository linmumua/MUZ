package linmumua.doudizhu.listener;

import java.util.UUID;
import linmumua.doudizhu.DoudizhuPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * 牌桌世界与区块生命周期事件接缝。
 *
 * @author linmumua
 * @Desc 只把 Bukkit 生命周期事件转换为 world/chunk 标识并投递到 global lane
 * @date 2026-09-22
 */
public final class TableWorldLifecycleListener implements Listener {
    private final DoudizhuPlugin plugin;

    public TableWorldLifecycleListener(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        UUID worldId = event.getWorld().getUID();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        plugin.scheduler().runGlobal(() -> plugin.getPhysicalTableManager()
            .onChunkLoad(worldId, chunkX, chunkZ));
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        plugin.scheduler().runGlobal(() -> plugin.getPhysicalTableManager()
            .onChunkUnload(worldId, chunkX, chunkZ));
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID worldId = event.getWorld().getUID();
        plugin.scheduler().runGlobal(() -> plugin.getPhysicalTableManager()
            .onWorldUnload(worldId));
    }
}
