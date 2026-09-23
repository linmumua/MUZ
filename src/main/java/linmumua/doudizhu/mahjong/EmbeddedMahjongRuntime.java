package linmumua.doudizhu.mahjong;

import linmumua.doudizhu.DoudizhuPlugin;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public final class EmbeddedMahjongRuntime {
    private final DoudizhuPlugin plugin;
    private final MahjongTableManager tableManager;
    private final AtomicBoolean shutdown = new AtomicBoolean();

    public EmbeddedMahjongRuntime(DoudizhuPlugin plugin) {
        this.plugin = plugin;
        this.tableManager = new MahjongTableManager(plugin, MahjongLayoutConfig.from(plugin));
    }

    public void reloadConfig() {
        if (shutdown.get()) {
            return;
        }
        this.tableManager.reloadLayout(MahjongLayoutConfig.from(plugin));
    }

    public boolean isEnabled() {
        return !shutdown.get() && plugin.isMahjongIntegrationEnabled();
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    public MahjongTableManager tableManager() {
        return tableManager;
    }

    public String statusSummary() {
        if (!isEnabled()) {
            return "内嵌已关闭";
        }
        return "内嵌已启用 · 桌数 " + tableManager.tableCount() + " · " + MahjongLayoutConfig.from(plugin).summary();
    }

    public void open(Player player) {
        send(player, tableManager.openLobbyLines(player));
    }

    public Map<String, String> statusMap() {
        return tableManager.statusMap();
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        tableManager.shutdown();
    }

    private void send(Player player, List<Component> lines) {
        if (player == null) {
            return;
        }
        tableManager.sendToPlayer(player, lines);
    }
}
