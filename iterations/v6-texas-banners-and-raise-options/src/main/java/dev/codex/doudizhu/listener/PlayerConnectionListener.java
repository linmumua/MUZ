package dev.codex.doudizhu.listener;

import dev.codex.doudizhu.DoudizhuPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

public final class PlayerConnectionListener implements Listener {
    private final DoudizhuPlugin plugin;

    public PlayerConnectionListener(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getPhysicalTableManager().hidePrivateEntitiesFrom(event.getPlayer());
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            event.getPlayer().sendMessage(
                Component.text("✦ MUMU ✦ 资源加载正常成功 | 作者 linmumua | QQ 356013496", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
            );
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.getTableManager().removePlayerSilently(event.getPlayer(), event.getPlayer().getName() + " 离线，当前对局已重置。");
        plugin.getZjhManager().removePlayerSilently(event.getPlayer(), event.getPlayer().getName() + " 离线，当前炸金花对局已重置。");
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        plugin.getTableManager().removePlayerSilently(event.getPlayer(), event.getPlayer().getName() + " 被移出服务器，当前对局已重置。");
        plugin.getZjhManager().removePlayerSilently(event.getPlayer(), event.getPlayer().getName() + " 被移出服务器，当前炸金花对局已重置。");
    }
}
