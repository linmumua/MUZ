package dev.codex.doudizhu.command;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.game.GameTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class DoudizhuCommand implements TabExecutor {
    private final DoudizhuPlugin plugin;

    public DoudizhuCommand(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                help(sender);
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "place" -> {
                    Player player = requirePlayer(sender);
                    requireArgs(args, 2, "/muz place <牌桌名>");
                    plugin.getPhysicalTableManager().placeNewTable(player, args[1]);
                    sender.sendMessage(message("已在你面前创建并放置实体牌桌 " + args[1] + "。", NamedTextColor.GREEN));
                }
                case "bot" -> {
                    Player player = requirePlayer(sender);
                    GameTable table = requireTable(player);
                    requireArgs(args, 2, "/muz bot <add|remove> [名字]");
                    if (args[1].equalsIgnoreCase("add")) {
                        String botName = args.length >= 3 ? args[2] : null;
                        table.addBot(botName);
                        sender.sendMessage(message("已向牌桌添加机器人。", NamedTextColor.GREEN));
                    } else if (args[1].equalsIgnoreCase("remove")) {
                        table.removeBot();
                        sender.sendMessage(message("已移除一个机器人。", NamedTextColor.YELLOW));
                    } else {
                        throw new IllegalArgumentException("用法: /muz bot <add|remove> [名字]");
                    }
                }
                case "list" -> {
                    List<GameTable> tables = plugin.getTableManager().getTables().stream()
                        .sorted(Comparator.comparing(GameTable::getName))
                        .toList();
                    if (tables.isEmpty()) {
                        sender.sendMessage(message("当前没有任何牌桌。", NamedTextColor.YELLOW));
                    } else {
                        sender.sendMessage(message("当前牌桌：", NamedTextColor.GOLD));
                        for (GameTable table : tables) {
                            sender.sendMessage(message(
                                "- " + table.getName() + " [" + table.getPhase().name() + "] " + table.getSeats().size() + "/3",
                                NamedTextColor.GRAY
                            ));
                        }
                    }
                }
                case "join" -> {
                    Player player = requirePlayer(sender);
                    requireArgs(args, 2, "/muz join <牌桌名>");
                    plugin.getTableManager().joinTable(player, args[1]);
                    sender.sendMessage(message("已加入牌桌 " + args[1] + "。", NamedTextColor.GREEN));
                }
                case "leave" -> {
                    Player player = requirePlayer(sender);
                    plugin.getTableManager().leaveTable(player);
                    sender.sendMessage(message("你已离开牌桌。", NamedTextColor.YELLOW));
                }
                case "labels", "settings" -> {
                    Player player = requirePlayer(sender);
                    plugin.getHandGuiService().openSettings(player);
                }
                case "status" -> {
                    Player player = requirePlayer(sender);
                    requireTable(player).buildStatusLines().forEach(sender::sendMessage);
                }
                case "forceend" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("你没有权限使用这个命令。");
                    }
                    Player player = requirePlayer(sender);
                    requireTable(player).forceEnd(sender);
                }
                case "remove" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("你没有权限使用这个命令。");
                    }
                    requireArgs(args, 2, "/muz remove <牌桌名>");
                    plugin.getPhysicalTableManager().removeTable(args[1]);
                    sender.sendMessage(message("已移除实体牌桌 " + args[1] + "。", NamedTextColor.YELLOW));
                }
                case "reload" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("你没有权限使用这个命令。");
                    }
                    plugin.reloadPluginState();
                    sender.sendMessage(message("斗地主配置、资源导出与已放置牌桌已动态重载。", NamedTextColor.GREEN));
                }
                case "admin" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("你没有权限使用这个命令。");
                    }
                    Player player = requirePlayer(sender);
                    plugin.getHandGuiService().openAdminModels(player);
                }
                case "debug" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("你没有权限使用这个命令。");
                    }
                    requireArgs(args, 2, "/muz debug add [数量]");
                    if (!args[1].equalsIgnoreCase("add")) {
                        throw new IllegalArgumentException("用法: /muz debug add [数量]");
                    }
                    int count = 1;
                    if (args.length >= 3) {
                        count = Math.max(1, Integer.parseInt(args[2]));
                    }
                    long batchId = System.currentTimeMillis();
                    for (int index = 1; index <= count; index++) {
                        String tableName = "debug-" + batchId + "-" + index;
                        GameTable table = plugin.getTableManager().createTable(tableName);
                        table.enableDebugAutoLoop();
                        table.addBot("DbgA-" + index);
                        table.addBot("DbgB-" + index);
                        table.addBot("DbgC-" + index);
                        table.startRound(sender);
                    }
                    sender.sendMessage(message("已创建 " + count + " 张 debug bot 自循环牌桌。", NamedTextColor.GREEN));
                }
                default -> help(sender);
            }
        } catch (RuntimeException exception) {
            sender.sendMessage(message(exception.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("help", "place", "remove", "bot", "list", "join", "leave", "settings", "labels", "status"));
            if (sender.hasPermission("muz.admin")) {
                options.addAll(List.of("reload", "admin", "forceend", "debug"));
            }
            return filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bot")) {
            return filter(List.of("add", "remove"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return filter(List.of("add"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("remove"))) {
            return filter(plugin.getTableManager().getTables().stream().map(GameTable::getName).toList(), args[1]);
        }
        return List.of();
    }

    private void help(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        lines.add("/muz place <牌桌名> - 在你面前创建并放置实体牌桌");
        lines.add("/muz remove <牌桌名> - 移除实体牌桌");
        lines.add("/muz bot <add|remove> [名字] - 添加或移除机器人");
        lines.add("/muz list - 查看当前牌桌");
        lines.add("/muz join <牌桌名> - 加入牌桌");
        lines.add("/muz leave - 离开当前牌桌");
        lines.add("/muz settings - 随时打开你的个人偏移微调菜单");
        lines.add("/muz labels - 兼容旧命令，效果同 /muz settings");
        lines.add("/muz status - 查看当前牌桌状态");
        if (sender.hasPermission("muz.admin")) {
            lines.add("/muz reload - 动态重载配置、资源导出与已放置牌桌");
            lines.add("/muz admin - 打开管理员全局配置菜单");
            lines.add("/muz forceend - 强制结束当前对局");
            lines.add("/muz debug add [数量] - 创建自循环 bot 压测牌桌");
        }
        sender.sendMessage(message("MUZ 命令帮助", NamedTextColor.GOLD));
        lines.forEach(line -> sender.sendMessage(message(line, NamedTextColor.GRAY)));
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw new IllegalStateException("这个命令只能由玩家执行。");
    }

    private GameTable requireTable(Player player) {
        GameTable table = plugin.getTableManager().getTableOf(player);
        if (table == null) {
            throw new IllegalStateException("你当前不在任何牌桌里。");
        }
        return table;
    }

    private void requireArgs(String[] args, int expected, String usage) {
        if (args.length < expected) {
            throw new IllegalArgumentException("用法: " + usage);
        }
    }

    private Component message(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private List<String> filter(List<String> values, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return values.stream()
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
            .sorted()
            .collect(Collectors.toList());
    }
}
