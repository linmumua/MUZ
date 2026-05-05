package dev.mumu.doudizhu.mahjong;

import dev.mumu.doudizhu.DoudizhuPlugin;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class MahjongEngineBridge {
    private final DoudizhuPlugin plugin;
    private final EmbeddedMahjongRuntime embeddedRuntime;

    public MahjongEngineBridge(DoudizhuPlugin plugin, EmbeddedMahjongRuntime embeddedRuntime) {
        this.plugin = plugin;
        this.embeddedRuntime = embeddedRuntime;
    }

    public EmbeddedMahjongRuntime embeddedRuntime() {
        return embeddedRuntime;
    }

    public void open(Player player) {
        if (embeddedRuntime != null && embeddedRuntime.isEnabled()) {
            embeddedRuntime.open(player);
            return;
        }
        plugin.openExternalMahjongEntry(player);
    }

    public void handleCommand(CommandSender sender, String[] args) {
        Player player = sender instanceof Player p ? p : null;
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(java.util.Locale.ROOT);
        if ("open".equals(sub)) {
            if (player == null) {
                throw new IllegalStateException("这个命令需要玩家执行。");
            }
            open(player);
            return;
        }
        if (embeddedRuntime == null || !embeddedRuntime.isEnabled()) {
            if (player == null) {
                throw new IllegalStateException("内嵌麻将当前已关闭，且控制台不能走外部 GUI 入口。");
            }
            plugin.openExternalMahjongEntry(player);
            return;
        }
        MahjongTableManager manager = embeddedRuntime.tableManager();
        switch (sub) {
            case "help" -> requirePlayer(player).sendMessage(Component.text("/muz mahjong <help|open|create|list|state|remove>"));
            case "create" -> {
                MahjongTableSession table = manager.createTable(requirePlayer(player), args.length >= 2 ? args[1] : null);
                sender.sendMessage("已创建内嵌麻将桌 " + table.id());
            }
            case "list" -> send(sender, manager.listLines());
            case "state" -> {
                MahjongTableSession table = args.length >= 2 ? manager.table(args[1]) : manager.nearestTable(player == null ? null : player.getLocation());
                send(sender, manager.stateLines(table));
            }
            case "remove" -> {
                if (args.length < 2) {
                    throw new IllegalArgumentException("用法: /muz mahjong remove <桌号>");
                }
                MahjongTableSession removed = manager.removeTable(args[1]);
                if (removed == null) {
                    throw new IllegalArgumentException("没找到要删除的麻将桌: " + args[1]);
                }
                sender.sendMessage("已删除内嵌麻将桌 " + removed.id());
            }
            default -> throw new IllegalArgumentException("用法: /muz mahjong <help|open|create|list|state|remove>");
        }
    }

    private Player requirePlayer(Player player) {
        if (player == null) {
            throw new IllegalStateException("这个命令需要玩家执行。");
        }
        return player;
    }

    private void send(CommandSender sender, List<Component> lines) {
        lines.forEach(sender::sendMessage);
    }
}
