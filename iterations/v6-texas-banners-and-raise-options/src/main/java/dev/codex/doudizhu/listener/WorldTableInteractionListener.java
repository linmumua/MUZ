package dev.codex.doudizhu.listener;

import dev.codex.doudizhu.DoudizhuPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public final class WorldTableInteractionListener implements Listener {
    private final DoudizhuPlugin plugin;

    public WorldTableInteractionListener(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (plugin.getPhysicalTableManager().handleInteraction(event.getPlayer(), event.getRightClicked())
            || plugin.getZjhPhysicalTableManager().handleInteraction(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (plugin.getPhysicalTableManager().handleAttack(player, event.getEntity())
            || plugin.getZjhPhysicalTableManager().handleAttack(player, event.getEntity())) {
            event.setCancelled(true);
        }
    }
}
