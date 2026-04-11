package dev.mumu.doudizhu.listener;

import dev.mumu.doudizhu.DoudizhuPlugin;
import java.util.Locale;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerCommandEvent;

public final class CraftEngineLifecycleListener implements Listener {
    private final DoudizhuPlugin plugin;

    public CraftEngineLifecycleListener(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (isCraftEngineReload(event.getMessage())) {
            plugin.getCraftEngineBundleExporter().ensureBundleReady("player-ce-reload", false);
            plugin.scheduleVisualWarmupRebuilds("player-ce-reload", 40L, 140L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (isCraftEngineReload(event.getCommand())) {
            plugin.getCraftEngineBundleExporter().ensureBundleReady("console-ce-reload", false);
            plugin.scheduleVisualWarmupRebuilds("console-ce-reload", 40L, 140L);
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("CraftEngine")) {
            plugin.ensureCraftEngineProtectionListenerRegistered();
            plugin.getCraftEngineBundleExporter().ensureBundleReady("craftengine-enable", false);
            plugin.attemptPersistedTableRestore();
            plugin.scheduleVisualWarmupRebuilds("craftengine-enable", 60L, 180L);
            return;
        }
        if (event.getPlugin().getName().equalsIgnoreCase("PlaceholderAPI")) {
            plugin.ensurePlaceholderHookReady();
        }
    }

    private boolean isCraftEngineReload(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return false;
        }
        String[] parts = normalized.split("\\s+");
        if (parts.length < 2) {
            return false;
        }
        String base = parts[0];
        String sub = parts[1];
        boolean craftEngineBase =
            base.equals("ce")
                || base.equals("craftengine")
                || base.endsWith(":ce")
                || base.endsWith(":craftengine");
        boolean reloadSub = sub.equals("reload") || sub.equals("rl");
        return craftEngineBase && reloadSub;
    }
}

