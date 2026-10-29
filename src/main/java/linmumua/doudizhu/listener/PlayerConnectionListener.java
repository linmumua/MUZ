package linmumua.doudizhu.listener;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.GameTable;
import linmumua.doudizhu.game.PlayerOutputDispatcher;
import linmumua.doudizhu.game.TableGadgetService;
import linmumua.doudizhu.ui.MuzTheme;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class PlayerConnectionListener implements Listener {
    private final DoudizhuPlugin plugin;
    private final PlayerOutputDispatcher output;

    public PlayerConnectionListener(DoudizhuPlugin plugin) {
        this.plugin = plugin;
        this.output = plugin.getActionBarOverlayService().outputDispatcher();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        java.util.UUID playerId = event.getPlayer().getUniqueId();
        plugin.getPhysicalTableManager().markPlayerConnected(playerId);
        scheduleViewerWarmup(event.getPlayer(), "join");
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        java.util.UUID playerId = event.getPlayer().getUniqueId();
        switch (event.getStatus()) {
            case ACCEPTED -> output.sendActionBar(playerId, progressMessage("资源包已接受，开始下载", 0.20, NamedTextColor.AQUA));
            case DOWNLOADED -> output.sendActionBar(playerId, progressMessage("资源包已下载，正在应用", 0.72, NamedTextColor.GOLD));
            case SUCCESSFULLY_LOADED -> {
                // 【仅 op 可见】CraftEngine 每次重载资源包，**全体在线玩家**都会重新下载一次，
                // 于是这条「加载完成 | 作者 … | QQ …」会逐个弹给所有人——对普通玩家来说这是一条
                // 与自己无关的播报（重载通常还是管理员操作的）。用户明确要求仅对 op 显示，
                // 因此普通玩家只在客户端静默应用资源包，不再收到这条提示。
                // 其余状态（拒绝/下载失败/地址无效等）是玩家自己那次下载的结果，仍然逐人提示。
                if (showsLoadedNotice(event.getPlayer().isOp())) {
                    output.sendMessage(playerId, progressMessage("资源包加载完成 | 作者 linmumua | QQ 356013496", 1.0, NamedTextColor.GREEN));
                }
                scheduleViewerWarmup(event.getPlayer(), "resource-pack");
            }
            case DECLINED -> output.sendMessage(playerId, progressMessage("你拒绝了服务器资源包。", 0.0, NamedTextColor.RED));
            case FAILED_DOWNLOAD -> output.sendMessage(playerId, progressMessage("资源包下载失败，请检查链接或网络。", 0.35, NamedTextColor.RED));
            case INVALID_URL -> output.sendMessage(playerId, progressMessage("资源包地址无效，服务器资源包配置有误。", 0.10, NamedTextColor.RED));
            case FAILED_RELOAD -> output.sendMessage(playerId, progressMessage("资源包已下载，但重新加载失败。", 0.85, NamedTextColor.RED));
            case DISCARDED -> output.sendActionBar(playerId, progressMessage("资源包任务已被中止。", 0.0, NamedTextColor.YELLOW));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        java.util.UUID playerId = event.getPlayer().getUniqueId();
        output.cancel(playerId);
        plugin.getPhysicalTableManager().markPlayerDisconnected(playerId);
        clearTableGadget(event.getPlayer());
        plugin.getTableManager().removePlayerSilently(event.getPlayer(), event.getPlayer().getName() + " 离线，当前对局已重置。");
        // hover/选中/调试面板那几张按玩家分组的 map 同理：tick() 只遍历在线玩家，
        // 离线的 key 永远轮不到清理，不在这里显式清就会无上限累积。
        plugin.getPhysicalTableManager().clearPlayerCaches(playerId);
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        java.util.UUID playerId = event.getPlayer().getUniqueId();
        output.cancel(playerId);
        plugin.getPhysicalTableManager().markPlayerDisconnected(playerId);
        clearTableGadget(event.getPlayer());
        plugin.getTableManager().removePlayerSilently(event.getPlayer(), event.getPlayer().getName() + " 被移出服务器，当前对局已重置。");
        // 被踢和自己退出是同一种离线，缓存清理不能只做一边。
        plugin.getPhysicalTableManager().clearPlayerCaches(playerId);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        clearTableGadget(event.getPlayer());
        scheduleViewerWarmup(event.getPlayer(), "respawn");
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        clearTableGadget(event.getPlayer());
        scheduleViewerWarmup(event.getPlayer(), "world-change");
    }

    private void clearTableGadget(org.bukkit.entity.Player player) {
        TableGadgetService service = plugin.tableGadgets();
        if (service != null) {
            service.clearPlayer(player.getUniqueId());
        }
    }

    /**
     * 「资源包加载完成」提示是否要发给该玩家。
     *
     * <p>为什么只有 op 看：这条提示在 CraftEngine 重载资源包时会对**每个在线玩家**各触发一次，
     * 内容却是给管理员看的构建/作者信息。做成只接收布尔值的纯函数，便于在无 Bukkit 服务端的
     * 单测里逐条验算判据，而不是只能读源码猜。
     */
    static boolean showsLoadedNotice(boolean operator) {
        return operator;
    }

    private Component progressMessage(String text, double progress, NamedTextColor color) {
        return MuzTheme.named(buildProgressBar(progress) + " " + text, color);
    }

    private String buildProgressBar(double progress) {
        int width = 16;
        double clamped = Math.clamp(progress, 0.0, 1.0);
        int filled = (int) Math.round(clamped * width);
        StringBuilder builder = new StringBuilder();
        builder.append('[');
        for (int index = 0; index < width; index++) {
            builder.append(index < filled ? '#' : '-');
        }
        builder.append("] ");
        builder.append((int) Math.round(clamped * 100.0));
        builder.append('%');
        return builder.toString();
    }

    private void scheduleViewerWarmup(org.bukkit.entity.Player player, String reason) {
        java.util.UUID playerId = player.getUniqueId();
        long[] delays = {5L, 30L, 80L, 160L, 320L};
        for (long delay : delays) {
            output.runPlayer(playerId, delay, current -> {
                if (plugin.isShuttingDown() || !current.isOnline()) {
                    return;
                }
                // HARD-CODED VIEWER RESYNC:
                // Rejoining players can still miss existing TextDisplay/table visuals after startup even when the table exists server-side.
                // Player lane 只确认 UUID/在线；跨桌修复交给 global coordinator，再按桌 owner 投递。
                scheduleIncompleteTableRepair("viewer-" + reason + "-ddz-" + delay);
                // syncViewer 自身只拍 UUID 快照，并把每张桌的实体同步投递到对应 owner lane。
                plugin.getPhysicalTableManager().syncViewer(current);
            });
        }
    }

    private void scheduleIncompleteTableRepair(String reason) {
        plugin.scheduler().runGlobal(() -> {
            if (plugin.isShuttingDown()) {
                return;
            }
            for (GameTable table : plugin.getTableManager().getTables()) {
                String tableName = table.getName();
                plugin.getTableManager().runTableNow(table, () ->
                    // 机制变更：按桌修复已改为异步 stage 流水线，必须在这里挂失败回调；
                    // 否则失败只留在没人观察的 stage 上，等于被静默吞掉。
                    plugin.getPhysicalTableManager().repairIncompleteTables(reason + "-table-" + tableName)
                        .exceptionally(failure -> {
                            plugin.getLogger().warning("按桌修复失败: table=" + tableName
                                + "，原因=" + failure.getMessage());
                            return null;
                        })
                );
            }
        });
    }
}

