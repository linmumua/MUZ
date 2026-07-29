package linmumua.doudizhu.listener;

import linmumua.doudizhu.DoudizhuPlugin;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class CraftEngineProtectionListener implements Listener {
    private final DoudizhuPlugin plugin;

    public CraftEngineProtectionListener(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        Entity entity = event.furniture().bukkitEntity();
        if (entity == null) {
            return;
        }
        if (plugin.getPhysicalTableManager().isProtectedEntity(entity.getUniqueId())) {
            event.setDropItems(false);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        if (plugin.getPhysicalTableManager().isProtectedPlacedBlock(event.bukkitBlock())) {
            event.setDropItems(false);
            event.setCancelled(true);
        }
    }
}
