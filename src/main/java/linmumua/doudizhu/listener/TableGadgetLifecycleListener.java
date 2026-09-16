package linmumua.doudizhu.listener;

import java.util.UUID;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.TableGadgetService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** 牌桌道具的玩家级生命周期清理；由 TableGadgetService 注册且只注册一次。 */
public final class TableGadgetLifecycleListener implements Listener {
    private final DoudizhuPlugin plugin;

    public TableGadgetLifecycleListener(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        clear(event.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        clear(event.getPlayer());
    }

    private void clear(Player player) {
        if (player == null) {
            return;
        }
        TableGadgetService service = plugin.tableGadgets();
        if (service != null) {
            UUID playerId = player.getUniqueId();
            service.clearPlayer(playerId);
        }
    }
}
