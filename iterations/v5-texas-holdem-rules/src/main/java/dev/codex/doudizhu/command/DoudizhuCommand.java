package dev.codex.doudizhu.command;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.game.GameTable;
import dev.codex.doudizhu.zhajinhua.ZjhTable;
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
                    requireArgs(args, 2, "/muz place <doudizhu|texas> [id]");
                    String type = args[1].toLowerCase(Locale.ROOT);
                    String id = args.length >= 3 ? args[2] : defaultId(type);
                    if (type.equals("texas") || type.equals("holdem") || type.equals("zjh") || type.equals("zhajinhua")) {
                        plugin.getZjhPhysicalTableManager().placeNewTable(player, id, 10);
                        sender.sendMessage(message("已创建并放置德州扑克牌桌 " + id + "。", NamedTextColor.GREEN));
                    } else if (type.equals("doudizhu") || type.equals("ddz")) {
                        plugin.getPhysicalTableManager().placeNewTable(player, id);
                        sender.sendMessage(message("已创建并放置斗地主牌桌 " + id + "。", NamedTextColor.GREEN));
                    } else {
                        throw new IllegalArgumentException("牌桌类型只能是 doudizhu 或 texas。");
                    }
                }
                case "bot" -> {
                    Player player = requirePlayer(sender);
                    requireArgs(args, 2, "/muz bot <add|remove> [名字]");
                    GameTable ddzTable = plugin.getTableManager().getTableOf(player);
                    ZjhTable zjhTable = plugin.getZjhManager().getTableOf(player);
                    if (args[1].equalsIgnoreCase("add")) {
                        String botName = args.length >= 3 ? args[2] : null;
                        if (ddzTable != null) {
                            ddzTable.addBot(botName);
                        } else if (zjhTable != null) {
                            zjhTable.addBot(botName);
                        } else {
                            throw new IllegalStateException("你当前不在任何牌桌里。");
                        }
                        sender.sendMessage(message("已向牌桌添加机器人。", NamedTextColor.GREEN));
                    } else if (args[1].equalsIgnoreCase("remove")) {
                        if (ddzTable != null) {
                            ddzTable.removeBot();
                        } else if (zjhTable != null) {
                            zjhTable.removeBot();
                        } else {
                            throw new IllegalStateException("你当前不在任何牌桌里。");
                        }
                        sender.sendMessage(message("已移除一个机器人。", NamedTextColor.YELLOW));
                    } else {
                        throw new IllegalArgumentException("用法: /muz bot <add|remove> [名字]");
                    }
                }
                case "list" -> {
                    List<String> lines = new ArrayList<>();
                    plugin.getTableManager().getTables().stream()
                        .sorted(Comparator.comparing(GameTable::getName))
                        .forEach(table -> lines.add("- " + table.getName() + " [斗地主] " + table.getSeats().size() + "/3"));
                    plugin.getZjhManager().getTables().stream()
                        .sorted(Comparator.comparing(ZjhTable::getName))
                        .forEach(table -> lines.add("- " + table.getName() + " [德州] " + table.getSeats().size() + "/" + table.getMaxPlayers()));
                    if (lines.isEmpty()) {
                        sender.sendMessage(message("当前没有任何牌桌。", NamedTextColor.YELLOW));
                    } else {
                        sender.sendMessage(message("当前牌桌：", NamedTextColor.GOLD));
                        lines.forEach(line -> sender.sendMessage(message(line, NamedTextColor.GRAY)));
                    }
                }
                case "join" -> {
                    Player player = requirePlayer(sender);
                    requireArgs(args, 2, "/muz join <牌桌名>");
                    if (plugin.getTableManager().getTable(args[1]) != null) {
                        plugin.getTableManager().joinTable(player, args[1]);
                    } else if (plugin.getZjhManager().getTable(args[1]) != null) {
                        plugin.getZjhManager().joinTable(player, args[1]);
                    } else {
                        throw new IllegalArgumentException("找不到这个牌桌。");
                    }
                    sender.sendMessage(message("已加入牌桌 " + args[1] + "。", NamedTextColor.GREEN));
                }
                case "leave" -> {
                    Player player = requirePlayer(sender);
                    if (plugin.getTableManager().getTableOf(player) != null) {
                        plugin.getTableManager().leaveTable(player);
                    } else if (plugin.getZjhManager().getTableOf(player) != null) {
                        plugin.getZjhManager().leaveTable(player);
                    } else {
                        throw new IllegalArgumentException("你当前不在任何牌桌里。");
                    }
                    sender.sendMessage(message("你已离开牌桌。", NamedTextColor.YELLOW));
                }
                case "labels", "settings" -> {
                    Player player = requirePlayer(sender);
                    plugin.getHandGuiService().openSettings(player);
                }
                case "status" -> {
                    Player player = requirePlayer(sender);
                    GameTable ddzTable = plugin.getTableManager().getTableOf(player);
                    if (ddzTable != null) {
                        ddzTable.buildStatusLines().forEach(sender::sendMessage);
                    } else {
                        ZjhTable zjhTable = requireZjhTable(player);
                        zjhTable.buildStatusLines().forEach(sender::sendMessage);
                    }
                }
                case "hand", "look" -> requireZjhTable(requirePlayer(sender)).showHand(requirePlayer(sender));
                case "check" -> requireZjhTable(requirePlayer(sender)).check(requirePlayer(sender));
                case "call", "follow" -> requireZjhTable(requirePlayer(sender)).call(requirePlayer(sender));
                case "raise" -> {
                    Player player = requirePlayer(sender);
                    requireArgs(args, 2, "/muz raise <总下注>");
                    requireZjhTable(player).raise(player, Integer.parseInt(args[1]));
                }
                case "fold" -> requireZjhTable(requirePlayer(sender)).fold(requirePlayer(sender));
                case "allin" -> requireZjhTable(requirePlayer(sender)).allIn(requirePlayer(sender));
                case "forceend" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("你没有权限使用这个命令。");
                    }
                    Player player = requirePlayer(sender);
                    if (plugin.getTableManager().getTableOf(player) != null) {
                        requireTable(player).forceEnd(sender);
                    } else {
                        requireZjhTable(player).forceEnd(sender);
                    }
                }
                case "remove" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("你没有权限使用这个命令。");
                    }
                    requireArgs(args, 2, "/muz remove <牌桌名>");
                    if (plugin.getPhysicalTableManager().isPlaced(args[1])) {
                        plugin.getPhysicalTableManager().removeTable(args[1]);
                    } else if (plugin.getZjhPhysicalTableManager().isPlaced(args[1])) {
                        plugin.getZjhPhysicalTableManager().removeTable(args[1]);
                    } else {
                        throw new IllegalArgumentException("这个牌桌没有实体桌面。");
                    }
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
            List<String> options = new ArrayList<>(List.of("help", "place", "remove", "bot", "list", "join", "leave", "settings", "labels", "status", "hand", "check", "call", "raise", "fold", "allin"));
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
        if (args.length == 2 && args[0].equalsIgnoreCase("place")) {
            return filter(List.of("doudizhu", "texas", "holdem"), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("remove"))) {
            List<String> names = new ArrayList<>();
            names.addAll(plugin.getTableManager().getTables().stream().map(GameTable::getName).toList());
            names.addAll(plugin.getZjhManager().getTables().stream().map(ZjhTable::getName).toList());
            return filter(names, args[1]);
        }
        return List.of();
    }

    private void help(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        lines.add("/muz place <doudizhu|texas> [id] - 创建对应玩法牌桌");
        lines.add("/muz remove <牌桌名> - 移除实体牌桌");
        lines.add("/muz bot <add|remove> [名字] - 添加或移除机器人");
        lines.add("/muz list - 查看当前牌桌");
        lines.add("/muz join <牌桌名> - 加入牌桌");
        lines.add("/muz leave - 离开当前牌桌");
        lines.add("/muz settings - 随时打开你的个人偏移微调菜单");
        lines.add("/muz labels - 兼容旧命令，效果同 /muz settings");
        lines.add("/muz status - 查看当前牌桌状态");
        lines.add("/muz hand / check / call / raise / fold / allin - 德州扑克操作命令");
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

    private ZjhTable requireZjhTable(Player player) {
        ZjhTable table = plugin.getZjhManager().getTableOf(player);
        if (table == null) {
            throw new IllegalStateException("你当前不在任何炸金花牌桌里。");
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

    private String defaultId(String type) {
        long suffix = System.currentTimeMillis() % 100000;
        return (type.equals("texas") || type.equals("holdem") || type.equals("zjh") || type.equals("zhajinhua") ? "texas-" : "ddz-") + suffix;
    }
}
