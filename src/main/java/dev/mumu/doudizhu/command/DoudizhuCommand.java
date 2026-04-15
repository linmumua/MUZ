package dev.mumu.doudizhu.command;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.ai.AiChatGateway;
import dev.mumu.doudizhu.game.GameTable;
import dev.mumu.doudizhu.room.TableLevel;
import dev.mumu.doudizhu.ui.MuzTheme;
import dev.mumu.doudizhu.zhajinhua.ZjhTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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
                case "create" -> {
                    Player player = requirePlayer(sender);
                    CreateRequest request = parseCreateRequest(args);
                    if (request.texas()) {
                        plugin.getZjhPhysicalTableManager().placeNewTable(player, request.id(), 10, request.level());
                        sender.sendMessage(message("德州牌桌 " + request.id() + " 已经摆好了，场次是 " + plugin.roomDisplayTag(request.level()) + "。", NamedTextColor.GREEN));
                    } else {
                        plugin.getPhysicalTableManager().placeNewTable(player, request.id(), request.level());
                        sender.sendMessage(message("斗地主牌桌 " + request.id() + " 已经摆好了，场次是 " + plugin.roomDisplayTag(request.level()) + "。", NamedTextColor.GREEN));
                    }
                }
                case "set" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("这个命令需要管理员权限。");
                    }
                    requireArgs(args, 3, "/muz set <牌桌id> <high|mid|low|fun>");
                    TableLevel level = TableLevel.parse(args[2]);
                    if (level == null) {
                        throw new IllegalArgumentException("场次只能是 high / mid / low / fun。");
                    }
                    GameTable ddzTable = plugin.getTableManager().getTable(args[1]);
                    if (ddzTable != null) {
                        ddzTable.setRoomLevel(level);
                        plugin.getPhysicalTableManager().refresh(ddzTable);
                        sender.sendMessage(message("斗地主牌桌 " + ddzTable.getName() + " 现在已经切到 " + plugin.roomDisplayTag(level) + "。", NamedTextColor.GREEN));
                        return true;
                    }
                    ZjhTable zjhTable = plugin.getZjhManager().getTable(args[1]);
                    if (zjhTable != null) {
                        zjhTable.setRoomLevel(level);
                        plugin.getZjhPhysicalTableManager().refresh(zjhTable);
                        sender.sendMessage(message("德州牌桌 " + zjhTable.getName() + " 现在已经切到 " + plugin.roomDisplayTag(level) + "。", NamedTextColor.GREEN));
                        return true;
                    }
                    throw new IllegalArgumentException("找不到这个牌桌 id: " + args[1]);
                }
                case "chip" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("这个命令需要管理员权限。");
                    }
                    requireArgs(args, 2, "/muz chip <mode|setitem|resetitem|balance> ...");
                    switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "mode" -> {
                            requireArgs(args, 3, "/muz chip mode <gold|chip>");
                            if (args[2].equalsIgnoreCase("gold")) {
                                plugin.setChipPaymentEnabled(false);
                                sender.sendMessage(message("支付模式已经切到金币。", NamedTextColor.GREEN));
                            } else if (args[2].equalsIgnoreCase("chip")) {
                                plugin.setChipPaymentEnabled(true);
                                sender.sendMessage(message("支付模式已经切到全局筹码。", NamedTextColor.GREEN));
                            } else {
                                throw new IllegalArgumentException("支付模式只能是 gold 或 chip。");
                            }
                        }
                        case "setitem" -> {
                            Player player = requirePlayer(sender);
                            if (player.getInventory().getItemInMainHand().getType().isAir()) {
                                throw new IllegalStateException("先把你想当作筹码的物品拿到主手。");
                            }
                            plugin.setChipPaymentItem(player.getInventory().getItemInMainHand());
                            sender.sendMessage(message("主手物品已经设成全局筹码外观。", NamedTextColor.GREEN));
                        }
                        case "resetitem" -> {
                            plugin.setChipPaymentItem(null);
                            sender.sendMessage(message("筹码外观已经恢复默认。", NamedTextColor.YELLOW));
                        }
                        case "balance" -> {
                            requireArgs(args, 3, "/muz chip balance <玩家> [数量]");
                            Player target = Bukkit.getPlayerExact(args[2]);
                            if (target == null) {
                                throw new IllegalArgumentException("目标玩家必须在线。");
                            }
                            if (args.length >= 4) {
                                int amount = Integer.parseInt(args[3]);
                                plugin.setChipBalance(target.getUniqueId(), amount);
                                sender.sendMessage(message(target.getName() + " 的筹码现在是 " + amount + "。", NamedTextColor.GREEN));
                            } else {
                                sender.sendMessage(message(target.getName() + " 当前有 " + plugin.getChipBalance(target.getUniqueId()) + " 筹码。", NamedTextColor.GOLD));
                            }
                        }
                        default -> throw new IllegalArgumentException("用法: /muz chip <mode|setitem|resetitem|balance> ...");
                    }
                }
                case "give" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("这个命令需要管理员权限。");
                    }
                    requireArgs(args, 3, "/muz give <玩家> <doudizhu|texas> [high|mid|low|fun] [数字id]");
                    Player target = Bukkit.getPlayerExact(args[1]);
                    if (target == null) {
                        throw new IllegalArgumentException("目标玩家必须在线。");
                    }
                    if (!isCreateTypeToken(args[2])) {
                        throw new IllegalArgumentException("请先写玩法：doudizhu 或 texas。");
                    }
                    boolean texas = isTexasType(args[2]);
                    TableLevel level = TableLevel.FUN;
                    String tableId = defaultId();
                    if (args.length >= 4) {
                        TableLevel parsedLevel = TableLevel.parse(args[3]);
                        if (parsedLevel != null) {
                            level = parsedLevel;
                            if (args.length >= 5) {
                                tableId = validateNumericId(args[4]);
                            }
                        } else {
                            tableId = validateNumericId(args[3]);
                        }
                    }
                    ItemStack placer = texas
                        ? plugin.createTexasTablePlacerItem(tableId, level)
                        : plugin.createDoudizhuTablePlacerItem(tableId, level);
                    java.util.HashMap<Integer, ItemStack> rejected = target.getInventory().addItem(placer);
                    if (!rejected.isEmpty()) {
                        rejected.values().forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
                    }
                    sender.sendMessage(message("已经给 " + target.getName() + " 一张" + (texas ? "德州" : "斗地主") + "放桌器，桌号是 " + tableId + "。", NamedTextColor.GREEN));
                    target.sendMessage(message("你收到了一张 MUZ " + (texas ? "德州" : "斗地主") + "放桌器，右键一次预览，再右键放置。", NamedTextColor.GOLD));
                }
                case "history" -> {
                    UUID targetId;
                    String targetName;
                    int page = 1;
                    if (args.length >= 2) {
                        Player online = Bukkit.getPlayerExact(args[1]);
                        if (online != null) {
                            targetId = online.getUniqueId();
                            targetName = online.getName();
                        } else {
                            org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
                            targetId = offline.getUniqueId();
                            targetName = offline.getName() == null ? args[1] : offline.getName();
                        }
                        if (args.length >= 3) {
                            page = Math.max(1, Integer.parseInt(args[2]));
                        }
                    } else {
                        Player player = requirePlayer(sender);
                        targetId = player.getUniqueId();
                        targetName = player.getName();
                    }
                    if (sender instanceof Player player) {
                        plugin.getHandGuiService().openHistory(player, targetId, targetName, page);
                    } else {
                        plugin.buildHistoryComponents(targetId, targetName, page, 5).forEach(sender::sendMessage);
                    }
                }
                case "bot" -> {
                    requireArgs(args, 2, "/muz bot <add|remove> [名字|数字id]");
                    if (args[1].equalsIgnoreCase("add")) {
                        Player player = requirePlayer(sender);
                        GameTable ddzTable = plugin.getTableManager().getTableOf(player);
                        ZjhTable zjhTable = plugin.getZjhManager().getTableOf(player);
                        String botName = args.length >= 3 ? args[2] : null;
                        if (ddzTable != null) {
                            java.util.UUID botId = ddzTable.addBot(botName);
                            sender.sendMessage(message("已经往这张牌桌里加了一个机器人，bot id 是 " + plugin.getBotNumericId(botId) + "。", NamedTextColor.GREEN));
                        } else if (zjhTable != null) {
                            java.util.UUID botId = zjhTable.addBot(botName);
                            sender.sendMessage(message("已经往这张牌桌里加了一个机器人，bot id 是 " + plugin.getBotNumericId(botId) + "。", NamedTextColor.GREEN));
                        } else {
                            throw new IllegalStateException("你现在不在任何牌桌里。");
                        }
                    } else if (args[1].equalsIgnoreCase("remove")) {
                        Integer numericId = args.length >= 3 ? parseBotNumericId(args[2]) : null;
                        int removedId = removeBotGlobal(numericId);
                        sender.sendMessage(message("机器人 " + removedId + " 已经移除了。", NamedTextColor.YELLOW));
                    } else {
                        throw new IllegalArgumentException("用法: /muz bot <add|remove> [名字|数字id]");
                    }
                }
                case "list" -> {
                    List<String> lines = new ArrayList<>();
                    plugin.getTableManager().getTables().stream()
                        .sorted(Comparator.comparing(GameTable::getName))
                        .forEach(table -> lines.add("- " + table.getName() + " [斗地主|" + plugin.roomDisplayTag(table.getRoomLevel()) + "] " + table.getSeats().size() + "/3"));
                    plugin.getZjhManager().getTables().stream()
                        .sorted(Comparator.comparing(ZjhTable::getName))
                        .forEach(table -> lines.add("- " + table.getName() + " [德州|" + plugin.roomDisplayTag(table.getRoomLevel()) + "] " + table.getSeats().size() + "/" + table.getMaxPlayers()));
                    if (lines.isEmpty()) {
                        sender.sendMessage(message("现在还没有任何牌桌。", NamedTextColor.YELLOW));
                    } else {
                        sender.sendMessage(message("当前牌桌一览：", NamedTextColor.GOLD));
                        lines.forEach(line -> sender.sendMessage(message(line, NamedTextColor.GRAY)));
                    }
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
                case "hand", "look", "check", "call", "follow", "raise", "fold", "allin" ->
                    throw new IllegalStateException("德州现在改成图形界面操作了，直接用桌边按钮就行。");
                case "forceend" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("这个命令需要管理员权限。");
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
                        throw new IllegalStateException("这个命令需要管理员权限。");
                    }
                    requireArgs(args, 2, "/muz remove <牌桌名>");
                    if (plugin.getPhysicalTableManager().isPlaced(args[1])) {
                        GameTable table = plugin.getTableManager().getTable(args[1]);
                        if (table != null) {
                            table.forceClose("管理员 " + sender.getName() + " 移除了斗地主牌桌 " + args[1] + "。");
                        }
                        plugin.getPhysicalTableManager().removeTable(args[1]);
                    } else if (plugin.getZjhPhysicalTableManager().isPlaced(args[1])) {
                        ZjhTable table = plugin.getZjhManager().getTable(args[1]);
                        if (table != null) {
                            table.forceClose("管理员 " + sender.getName() + " 移除了德州扑克牌桌 " + args[1] + "。");
                        }
                        plugin.getZjhPhysicalTableManager().removeTable(args[1]);
                    } else {
                        throw new IllegalArgumentException("这个牌桌没有实体桌面。");
                    }
                    sender.sendMessage(message("实体牌桌 " + args[1] + " 已经移除了。", NamedTextColor.YELLOW));
                }
                case "reload" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("这个命令需要管理员权限。");
                    }
                    plugin.reloadPluginState(sender);
                }
                case "admin" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("这个命令需要管理员权限。");
                    }
                    if (args.length >= 2 && args[1].equalsIgnoreCase("remove")) {
                        requireArgs(args, 3, "/muz admin remove <牌桌id>");
                        forceRemoveAnyTable(args[2], sender.getName());
                        sender.sendMessage(message("牌桌 " + args[2] + " 已经强制移除了。", NamedTextColor.YELLOW));
                        return true;
                    }
                    Player player = requirePlayer(sender);
                    plugin.getHandGuiService().openAdminModels(player);
                }
                case "debug" -> {
                    if (!sender.hasPermission("muz.admin")) {
                        throw new IllegalStateException("这个命令需要管理员权限。");
                    }
                    requireArgs(args, 2, "/muz debug <add|remove|bot> ...");
                    if (args[1].equalsIgnoreCase("bot")) {
                        handleDebugBot(sender, args);
                        return true;
                    }
                    Player player = requirePlayer(sender);
                    if (args[1].equalsIgnoreCase("add")) {
                        int count = 1;
                        if (args.length >= 3) {
                            count = Math.max(1, Integer.parseInt(args[2]));
                        }
                        long batchId = System.currentTimeMillis();
                        Location center = plugin.defaultTableAnchor(player);
                        float yaw = Math.round(player.getLocation().getYaw() / 90.0f) * 90.0f;
                        for (int index = 1; index <= count; index++) {
                            String tableName = "debug-" + batchId + "-" + index;
                            Location anchor = center.clone().add(debugGridOffset(index - 1, count));
                            GameTable table = plugin.getPhysicalTableManager().placeNewTableAt(player, tableName, TableLevel.FUN, anchor, yaw);
                            table.enableDebugAutoLoop();
                            table.addBot("DbgA-" + index);
                            table.addBot("DbgB-" + index);
                            table.addBot("DbgC-" + index);
                            table.startRound(player);
                        }
                        sender.sendMessage(message("已经在你附近摆好 " + count + " 张会自己打牌的观察桌。", NamedTextColor.GREEN));
                    } else if (args[1].equalsIgnoreCase("remove")) {
                        int removed = removeDebugTables(player, args.length >= 3 ? args[2] : "1");
                        sender.sendMessage(message("已经移除了 " + removed + " 张观察桌。", NamedTextColor.YELLOW));
                    } else {
                        throw new IllegalArgumentException("用法: /muz debug <add|remove|bot> ...");
                    }
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
            List<String> options = new ArrayList<>(List.of("help", "create", "set", "give", "history", "chip", "remove", "bot", "list", "settings", "labels", "status"));
            if (sender.hasPermission("muz.admin")) {
                options.addAll(List.of("reload", "admin", "forceend", "debug"));
            }
            return filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(List.of("doudizhu", "texas"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give") && isCreateTypeToken(args[2])) {
            return filter(List.of("fun", "low", "mid", "high"), args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bot")) {
            return filter(List.of("add", "remove"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("bot") && args[1].equalsIgnoreCase("remove")) {
            List<String> ids = plugin.getBotHandles().stream()
                .map(handle -> String.valueOf(handle.numericId()))
                .toList();
            return filter(ids, args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return filter(List.of("remove"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            return filter(List.of("add", "remove", "bot"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("remove")) {
            return filter(List.of("1", "5", "10", "20", "50", "all"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("bot")) {
            return filter(List.of("信息", "info"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("debug") && args[1].equalsIgnoreCase("bot")
            && (args[2].equalsIgnoreCase("info") || args[2].equalsIgnoreCase("信息"))) {
            List<String> ids = plugin.getBotHandles().stream()
                .map(handle -> String.valueOf(handle.numericId()))
                .toList();
            return filter(ids, args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("history")) {
            return filter(List.of("1", "2", "3"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("chip")) {
            return filter(List.of("mode", "setitem", "resetitem", "balance"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("chip") && args[1].equalsIgnoreCase("mode")) {
            return filter(List.of("gold", "chip"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("chip") && args[1].equalsIgnoreCase("balance")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            List<String> names = new ArrayList<>();
            names.addAll(plugin.getTableManager().getTables().stream().map(GameTable::getName).toList());
            names.addAll(plugin.getZjhManager().getTables().stream().map(ZjhTable::getName).toList());
            return filter(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return filter(List.of("low", "mid", "high", "fun"), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return filter(List.of("doudizhu", "texas", "holdem"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            if (isCreateTypeToken(args[1])) {
                List<String> options = new ArrayList<>(List.of("low", "mid", "high", "fun"));
                options.add(defaultId());
                return filter(options, args[2]);
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("create") && isCreateTypeToken(args[1])) {
            List<String> names = new ArrayList<>();
            names.add(defaultId());
            return filter(names, args[3]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("remove")) {
            List<String> names = new ArrayList<>();
            names.addAll(plugin.getTableManager().getTables().stream().map(GameTable::getName).toList());
            names.addAll(plugin.getZjhManager().getTables().stream().map(ZjhTable::getName).toList());
            return filter(names, args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            List<String> names = new ArrayList<>();
            names.addAll(plugin.getTableManager().getTables().stream().map(GameTable::getName).toList());
            names.addAll(plugin.getZjhManager().getTables().stream().map(ZjhTable::getName).toList());
            return filter(names, args[1]);
        }
        return List.of();
    }

    private void help(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        lines.add("/muz create <doudizhu|texas> [high|mid|low|fun] [数字id] - 创建牌桌；不写场次时用默认场次");
        lines.add("/muz set <牌桌id> <high|mid|low|fun> - 修改牌桌场次倍率");
        lines.add("/muz give <玩家> <doudizhu|texas> [high|mid|low|fun] [数字id] - 发对应玩法的放桌器，不写场次默认 fun");
        lines.add("/muz history [玩家] [页码] - 查看玩家历史战绩");
        lines.add("/muz chip mode <gold|chip> - 切换全局金币/筹码支付");
        lines.add("/muz chip setitem - 把主手物品设为全局筹码");
        lines.add("/muz chip balance <玩家> [数量] - 查看或设置玩家筹码");
        lines.add("/muz remove <牌桌名> - 移除实体牌桌");
        lines.add("/muz bot <add|remove> [名字|数字id] - 添加机器人，或按数字 id 移除");
        lines.add("/muz list - 查看当前牌桌");
        lines.add("/muz settings - 随时打开你的个人偏移微调菜单");
        lines.add("/muz labels - 兼容旧命令，效果同 /muz settings");
        lines.add("/muz status - 查看当前牌桌状态");
        lines.add("德州现已改为桌边按钮操作");
        if (sender.hasPermission("muz.admin")) {
            lines.add("/muz reload - 动态重载配置、资源导出与已放置牌桌");
            lines.add("/muz admin - 打开管理员全局配置菜单");
            lines.add("/muz admin remove <牌桌id> - 忽略占用状态强制删表");
            lines.add("/muz forceend - 强制结束当前对局");
            lines.add("/muz debug add [数量] - 在你附近生成会自己打牌的观察桌");
            lines.add("/muz debug remove [1-50|all] - 移除最近的观察桌，或全部移除");
            lines.add("/muz debug bot 信息 [bot数字id] [消息] - 查看 bot 信息，或直接和 bot 聊天测试 DeepSeek");
        }
        sender.sendMessage(message("MUZ 常用命令", NamedTextColor.GOLD));
        lines.forEach(line -> sender.sendMessage(message(line, NamedTextColor.GRAY)));
    }

    private void handleDebugBot(CommandSender sender, String[] args) {
        if (args.length < 3 || (!args[2].equalsIgnoreCase("info") && !args[2].equalsIgnoreCase("信息"))) {
            throw new IllegalArgumentException("用法: /muz debug bot 信息 [bot数字id] [消息]");
        }
        if (args.length == 3) {
            sendBotDebugOverview(sender);
            return;
        }
        int numericId = Integer.parseInt(args[3]);
        DoudizhuPlugin.BotHandle handle = requireBotHandle(numericId);
        if (args.length == 4) {
            sendBotDebugInfo(sender, handle);
            return;
        }
        String prompt = String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)).trim();
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("消息不能为空。");
        }
        sendBotDebugInfo(sender, handle);
        sender.sendMessage(message("已把消息发给 " + resolveBotDisplayName(handle) + "，正在等 DeepSeek 回应。", NamedTextColor.YELLOW));
        AiChatGateway gateway = plugin.getAiChatGateway();
        if (gateway == null) {
            throw new IllegalStateException("DeepSeek 网关还没初始化。");
        }
        List<AiChatGateway.Message> messages = List.of(
            AiChatGateway.Message.system(botPersonaPrompt(handle)),
            AiChatGateway.Message.user(prompt)
        );
        gateway.chatAsync(new AiChatGateway.ChatRequest(messages, plugin.aiModelName(), null, null))
            .whenComplete((response, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    String detail = error.getCause() == null ? error.getMessage() : error.getCause().getMessage();
                    sender.sendMessage(message("DeepSeek 调试失败: " + safeText(detail), NamedTextColor.RED));
                    return;
                }
                String content = response == null ? "" : safeText(response.content());
                if (content.isBlank() && response != null) {
                    content = safeText(response.reasoningContent());
                }
                sender.sendMessage(message(resolveBotDisplayName(handle) + " 的回复：", NamedTextColor.GOLD));
                if (content.isBlank()) {
                    sender.sendMessage(message("这次接口返回了空内容。", NamedTextColor.RED));
                    return;
                }
                for (String line : content.split("\\R")) {
                    if (!line.isBlank()) {
                        sender.sendMessage(message(line, NamedTextColor.WHITE));
                    }
                }
            }));
    }

    private void sendBotDebugOverview(CommandSender sender) {
        sender.sendMessage(message("DeepSeek 状态: " + plugin.aiStatusSummary(), NamedTextColor.GOLD));
        List<DoudizhuPlugin.BotHandle> handles = plugin.getBotHandles();
        if (handles.isEmpty()) {
            sender.sendMessage(message("当前没有已注册的 bot。", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(message("当前 bot 列表：", NamedTextColor.GOLD));
        for (DoudizhuPlugin.BotHandle handle : handles) {
            sender.sendMessage(message(describeBotLine(handle), NamedTextColor.GRAY));
        }
        sender.sendMessage(message("直接测试: /muz debug bot 信息 <id> 你好", NamedTextColor.YELLOW));
    }

    private void sendBotDebugInfo(CommandSender sender, DoudizhuPlugin.BotHandle handle) {
        sender.sendMessage(message("Bot " + handle.numericId() + " · " + resolveBotDisplayName(handle), NamedTextColor.GOLD));
        sender.sendMessage(message("牌桌: " + handle.tableName() + " · 类型: " + botGameLabel(handle), NamedTextColor.GRAY));
        sender.sendMessage(message("DeepSeek: " + plugin.aiModelName() + " @ " + plugin.aiBaseUrl(), NamedTextColor.GRAY));
        sender.sendMessage(message("密钥: " + plugin.aiApiKeyMasked(), NamedTextColor.GRAY));
        sender.sendMessage(message("人设: " + botPersonaTitle(handle), NamedTextColor.GRAY));
    }

    private DoudizhuPlugin.BotHandle requireBotHandle(int numericId) {
        DoudizhuPlugin.BotHandle handle = plugin.getBotHandle(numericId);
        if (handle == null) {
            throw new IllegalArgumentException("找不到 bot id: " + numericId);
        }
        return handle;
    }

    private String describeBotLine(DoudizhuPlugin.BotHandle handle) {
        return handle.numericId()
            + " · " + resolveBotDisplayName(handle)
            + " · " + handle.tableName()
            + " · " + botGameLabel(handle)
            + " · " + botPersonaTitle(handle);
    }

    private String resolveBotDisplayName(DoudizhuPlugin.BotHandle handle) {
        for (GameTable table : plugin.getTableManager().getTables()) {
            if (table.isBot(handle.botId())) {
                return table.displayName(handle.botId());
            }
        }
        for (ZjhTable table : plugin.getZjhManager().getTables()) {
            if (table.isBot(handle.botId())) {
                return table.displayName(handle.botId());
            }
        }
        return "Bot-" + handle.numericId();
    }

    private String botGameLabel(DoudizhuPlugin.BotHandle handle) {
        return handle.gameType() == DoudizhuPlugin.BotGameType.DOUDIZHU ? "斗地主" : "德州";
    }

    private String botPersonaTitle(DoudizhuPlugin.BotHandle handle) {
        return switch (Math.floorMod(handle.numericId(), 4)) {
            case 0 -> "稳牌老手";
            case 1 -> "气氛担当";
            case 2 -> "冷静数据派";
            default -> "碎嘴教练";
        };
    }

    private String botPersonaPrompt(DoudizhuPlugin.BotHandle handle) {
        String displayName = resolveBotDisplayName(handle);
        String persona = switch (Math.floorMod(handle.numericId(), 4)) {
            case 0 -> "你走稳健老手风格，说话克制，判断清楚，偶尔给一两句牌桌建议。";
            case 1 -> "你走轻松气氛组风格，口吻活一点，但不要油腻，也不要刷屏。";
            case 2 -> "你走数据分析派风格，喜欢用简短逻辑解释判断。";
            default -> "你走碎嘴教练风格，会提醒节奏和失误点，但语气要友好。";
        };
        String gamePrompt = handle.gameType() == DoudizhuPlugin.BotGameType.DOUDIZHU
            ? "你是 MUZ 斗地主牌桌里的虚拟牌友 `" + displayName + "`，熟悉叫分、地主、炸弹、春天和倍率。"
            : "你是 MUZ 德州牌桌里的虚拟牌友 `" + displayName + "`，熟悉位置、底池赔率、跟注、加注和弃牌。";
        return plugin.aiSystemPrompt()
            + "\n"
            + gamePrompt
            + persona
            + "你现在处于聊天模式，不需要按实战出牌格式输出。"
            + "你只用简体中文回复，控制在 1 到 4 句。";
    }


    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        throw new IllegalStateException("这个命令需要玩家本人来执行。");
    }

    private GameTable requireTable(Player player) {
        GameTable table = plugin.getTableManager().getTableOf(player);
        if (table == null) {
            throw new IllegalStateException("你现在不在任何牌桌里。");
        }
        return table;
    }

    private ZjhTable requireZjhTable(Player player) {
        ZjhTable table = plugin.getZjhManager().getTableOf(player);
        if (table == null) {
            throw new IllegalStateException("你现在不在任何德州牌桌里。");
        }
        return table;
    }

    private void requireArgs(String[] args, int expected, String usage) {
        if (args.length < expected) {
            throw new IllegalArgumentException("用法: " + usage);
        }
    }

    private Component message(String text, NamedTextColor color) {
        return MuzTheme.named(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private List<String> filter(List<String> values, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return values.stream()
            .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
            .sorted()
            .collect(Collectors.toList());
    }

    private String defaultId() {
        List<Integer> used = new ArrayList<>();
        used.addAll(plugin.getTableManager().getTables().stream().map(GameTable::getName).map(this::parseNumericIdOrMinusOne).filter(value -> value > 0).toList());
        used.addAll(plugin.getZjhManager().getTables().stream().map(ZjhTable::getName).map(this::parseNumericIdOrMinusOne).filter(value -> value > 0).toList());
        int index = 1;
        while (used.contains(index)) {
            index++;
        }
        return String.valueOf(index);
    }

    private boolean existingNameExists(List<String> existing, String candidate) {
        return existing.stream().anyMatch(name -> name.equalsIgnoreCase(candidate));
    }

    private CreateRequest parseCreateRequest(String[] args) {
        requireArgs(args, 2, "/muz create <doudizhu|texas> [high|mid|low|fun] [数字id]");
        if (!isCreateTypeToken(args[1])) {
            throw new IllegalArgumentException("创建牌桌时第二个参数必须是玩法: doudizhu 或 texas。");
        }
        boolean texas = isTexasType(args[1]);
        TableLevel level = plugin.defaultCreateRoomLevel();
        String id = defaultId();
        if (args.length >= 3) {
            TableLevel parsedLevel = TableLevel.parse(args[2]);
            if (parsedLevel != null) {
                level = parsedLevel;
                if (args.length >= 4) {
                    id = validateNumericId(args[3]);
                }
            } else {
                id = validateNumericId(args[2]);
            }
        }
        return new CreateRequest(texas, level, id);
    }

    private String validateNumericId(String token) {
        String trimmed = token == null ? "" : token.trim();
        if (!trimmed.matches("\\d+")) {
            throw new IllegalArgumentException("牌桌 id 必须是纯数字。");
        }
        return trimmed;
    }

    private int parseNumericIdOrMinusOne(String token) {
        if (token == null || !token.trim().matches("\\d+")) {
            return -1;
        }
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean isCreateTypeToken(String token) {
        String normalized = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return normalized.equals("doudizhu") || normalized.equals("ddz") || isTexasType(normalized);
    }

    private boolean isTexasType(String token) {
        String normalized = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return normalized.equals("texas") || normalized.equals("holdem") || normalized.equals("zjh") || normalized.equals("zhajinhua");
    }

    private org.bukkit.util.Vector debugGridOffset(int index, int count) {
        if (count <= 1) {
            return new org.bukkit.util.Vector(0.0, 0.0, 0.0);
        }
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        int row = index / columns;
        int col = index % columns;
        double spacing = plugin.getDebugTableSpacing();
        double originX = -((columns - 1) * spacing) / 2.0;
        double rows = Math.ceil((double) count / columns);
        double originZ = -((rows - 1) * spacing) / 2.0;
        return new org.bukkit.util.Vector(originX + col * spacing, 0.0, originZ + row * spacing);
    }

    private int removeDebugTables(Player player, String token) {
        List<String> debugTableNames = new ArrayList<>();
        debugTableNames.addAll(plugin.getTableManager().getTables().stream()
            .map(GameTable::getName)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("debug-"))
            .toList());
        for (String placedName : plugin.getPhysicalTableManager().placedTableNames()) {
            if (placedName.toLowerCase(Locale.ROOT).startsWith("debug-") && debugTableNames.stream().noneMatch(name -> name.equalsIgnoreCase(placedName))) {
                debugTableNames.add(placedName);
            }
        }
        debugTableNames = debugTableNames.stream()
            .sorted(Comparator.comparingDouble(name -> {
                Location anchor = plugin.getPhysicalTableManager().tableAnchor(name);
                return anchor == null ? Double.MAX_VALUE : anchor.distanceSquared(player.getLocation());
            }))
            .toList();
        if (debugTableNames.isEmpty()) {
            throw new IllegalStateException("当前没有可移除的观察桌。");
        }
        int removeCount;
        if ("all".equalsIgnoreCase(token)) {
            removeCount = debugTableNames.size();
        } else {
            try {
                removeCount = Integer.parseInt(token);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("移除数量只能是 1-50，或者输入 all。");
            }
            if (removeCount < 1 || removeCount > 50) {
                throw new IllegalArgumentException("移除数量只能是 1-50，或者输入 all。");
            }
        }
        int actual = Math.min(removeCount, debugTableNames.size());
        for (int index = 0; index < actual; index++) {
            forceRemoveAnyTable(debugTableNames.get(index), player.getName());
        }
        return actual;
    }

    private void forceRemoveAnyTable(String tableId, String actorName) {
        GameTable ddzTable = plugin.getTableManager().getTable(tableId);
        if (ddzTable != null) {
            ddzTable.disableDebugAutoLoop();
            ddzTable.forceClose("管理员 " + actorName + " 强制移除了牌桌 " + tableId + "。");
            plugin.getPhysicalTableManager().forceRemoveTable(tableId);
            return;
        }
        ZjhTable zjhTable = plugin.getZjhManager().getTable(tableId);
        if (zjhTable != null) {
            zjhTable.disableDebugAutoLoop();
            zjhTable.forceClose("管理员 " + actorName + " 强制移除了牌桌 " + tableId + "。");
            plugin.getZjhPhysicalTableManager().forceRemoveTable(tableId);
            return;
        }
        if (plugin.getPhysicalTableManager().isPlaced(tableId)) {
            plugin.getPhysicalTableManager().forceRemoveTable(tableId);
            return;
        }
        if (plugin.getZjhPhysicalTableManager().isPlaced(tableId)) {
            plugin.getZjhPhysicalTableManager().forceRemoveTable(tableId);
            return;
        }
        throw new IllegalArgumentException("找不到这个牌桌 id: " + tableId);
    }

    private int removeBotGlobal(Integer numericId) {
        DoudizhuPlugin.BotHandle handle = numericId == null ? plugin.latestBotHandle() : plugin.getBotHandle(numericId);
        if (handle == null) {
            throw new IllegalStateException("当前没有机器人可移除。");
        }
        return switch (handle.gameType()) {
            case DOUDIZHU -> {
                GameTable table = plugin.getTableManager().getTable(handle.tableName());
                if (table == null) {
                    plugin.unregisterBot(handle.botId());
                    throw new IllegalStateException("bot 所在牌桌已不存在。");
                }
                table.removeBot(String.valueOf(handle.numericId()));
                yield handle.numericId();
            }
            case TEXAS -> {
                ZjhTable table = plugin.getZjhManager().getTable(handle.tableName());
                if (table == null) {
                    plugin.unregisterBot(handle.botId());
                    throw new IllegalStateException("bot 所在牌桌已不存在。");
                }
                table.removeBot(String.valueOf(handle.numericId()));
                yield handle.numericId();
            }
        };
    }

    private Integer parseBotNumericId(String token) {
        try {
            return Integer.parseInt(token.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("bot id 必须是数字。");
        }
    }

    private record CreateRequest(boolean texas, TableLevel level, String id) {
    }
}

