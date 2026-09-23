package linmumua.doudizhu.action;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.GameTable;
import linmumua.doudizhu.game.PlayerOutputDispatcher;
import linmumua.doudizhu.model.CardPattern;
import linmumua.doudizhu.model.DoudizhuCard;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class CeActionExecutor {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String ACCENT = "#8FD4FF";
    private static final String WARM = "#F1D398";
    private static final String MUTED = "#9AA8B6";
    private static final String DANGER = "#F2A1A8";

    private CeActionExecutor() {
    }

    public static void executePlayProfile(
        DoudizhuPlugin plugin,
        Player player,
        GameTable table,
        CardPattern pattern,
        List<DoudizhuCard> cards,
        DoudizhuPlugin.OptionProfile profile
    ) {
        if (plugin == null || player == null || profile == null || profile.spec().isBlank()) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PlayerOutputDispatcher output = outputDispatcher(plugin);
        Map<String, String> args = parse(profile.spec());
        String type = args.getOrDefault("type", "none").toLowerCase(Locale.ROOT);
        if (type.equals("none")) {
            return;
        }

        try {
            switch (type) {
                case "message" -> output.runPlayer(playerId, current -> {
                    String message = replace(args.getOrDefault("message", ""), current.getName(), table, pattern, cards);
                    if (!message.isBlank()) {
                        current.sendMessage(MINI_MESSAGE.deserialize(message));
                    }
                });
                case "actionbar" -> output.runPlayer(playerId, current -> {
                    String actionbar = replace(
                        args.getOrDefault("actionbar", args.getOrDefault("message", "")),
                        current.getName(), table, pattern, cards
                    );
                    if (!actionbar.isBlank()) {
                        current.sendActionBar(MINI_MESSAGE.deserialize(actionbar));
                    }
                });
                case "title" -> output.runPlayer(playerId, current -> {
                    String title = replace(args.getOrDefault("title", ""), current.getName(), table, pattern, cards);
                    String subtitle = replace(args.getOrDefault("subtitle", ""), current.getName(), table, pattern, cards);
                    int fadeIn = intValue(args.get("fade-in"), 5);
                    int stay = intValue(args.get("stay"), 30);
                    int fadeOut = intValue(args.get("fade-out"), 10);
                    current.showTitle(Title.title(
                        MINI_MESSAGE.deserialize(title.isBlank()
                            ? "<gradient:" + ACCENT + ":" + WARM + "><bold>出牌已确认</bold></gradient>"
                            : title),
                        MINI_MESSAGE.deserialize(subtitle),
                        Title.Times.times(
                            java.time.Duration.ofMillis(fadeIn * 50L),
                            java.time.Duration.ofMillis(stay * 50L),
                            java.time.Duration.ofMillis(fadeOut * 50L)
                        )
                    ));
                });
                case "command" -> {
                    if (booleanValue(args.get("as-player"))) {
                        output.runPlayer(playerId, current -> {
                            String command = replace(args.getOrDefault("command", ""), current.getName(), table, pattern, cards);
                            if (!command.isBlank()) {
                                current.performCommand(normalizeCommand(command));
                            }
                        });
                    } else {
                        String playerName = player.getName();
                        String command = replace(args.getOrDefault("command", ""), playerName, table, pattern, cards);
                        if (!command.isBlank()) {
                            plugin.scheduler().runGlobal(() -> Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(), normalizeCommand(command)
                            ));
                        }
                    }
                }
                case "play_sound" -> output.runPlayer(playerId, current -> {
                    String sound = replace(args.getOrDefault("sound", ""), current.getName(), table, pattern, cards);
                    float volume = floatValue(args.get("volume"), 1.0f);
                    float pitch = floatValue(args.get("pitch"), 1.0f);
                    if (!sound.isBlank() && volume > 0.0f) {
                        current.playSound(current.getLocation(), sound, volume, pitch);
                    }
                });
                default -> plugin.getLogger().warning("Unsupported CE action type in play profile: " + type);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Failed to execute play action profile: " + exception.getMessage());
        }
    }

    public static void previewPlayProfile(DoudizhuPlugin plugin, Player player, DoudizhuPlugin.OptionProfile profile) {
        if (plugin == null || player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        PlayerOutputDispatcher output = outputDispatcher(plugin);
        if (profile == null || profile.spec().isBlank()) {
            output.sendActionBar(playerId, MINI_MESSAGE.deserialize("<" + MUTED + ">当前方案没有可预览内容</" + MUTED + ">"));
            return;
        }
        Map<String, String> args = parse(profile.spec());
        String type = args.getOrDefault("type", "none").toLowerCase(Locale.ROOT);
        try {
            switch (type) {
                case "none" -> output.sendActionBar(
                    playerId,
                    MINI_MESSAGE.deserialize("<" + MUTED + ">当前行为方案不会额外执行操作</" + MUTED + ">")
                );
                case "message" -> output.sendMessage(
                    playerId,
                    MINI_MESSAGE.deserialize("<" + ACCENT + ">行为预览</" + ACCENT + "><dark_gray> · </dark_gray><" + WARM + ">聊天提示</" + WARM + ">")
                );
                case "actionbar" -> output.sendActionBar(
                    playerId,
                    MINI_MESSAGE.deserialize("<" + ACCENT + ">行为预览</" + ACCENT + "><dark_gray> · </dark_gray><" + WARM + ">动作栏提示</" + WARM + ">")
                );
                case "title" -> output.showTitle(playerId, Title.title(
                    MINI_MESSAGE.deserialize("<gradient:" + ACCENT + ":" + WARM + "><bold>行为预览</bold></gradient>"),
                    MINI_MESSAGE.deserialize("<" + MUTED + ">" + profile.label() + "</" + MUTED + ">")
                ));
                case "play_sound" -> {
                    String sound = args.getOrDefault("sound", "");
                    float volume = floatValue(args.get("volume"), 1.0f);
                    float pitch = floatValue(args.get("pitch"), 1.0f);
                    if (!sound.isBlank() && volume > 0.0f) {
                        output.playSound(playerId, sound, volume, pitch);
                    } else {
                        output.sendActionBar(
                            playerId,
                            MINI_MESSAGE.deserialize("<" + WARM + ">这个行为方案没有可播放的声音</" + WARM + ">")
                        );
                    }
                }
                case "command" -> output.sendActionBar(
                    playerId,
                    MINI_MESSAGE.deserialize("<" + WARM + ">命令行为不能直接预览</" + WARM + ">")
                );
                default -> output.sendActionBar(
                    playerId,
                    MINI_MESSAGE.deserialize("<" + DANGER + ">暂不支持预览这个行为类型</" + DANGER + ">")
                );
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Failed to preview play action profile: " + exception.getMessage());
            output.sendActionBar(playerId, MINI_MESSAGE.deserialize("<" + DANGER + ">行为预览失败</" + DANGER + ">"));
        }
    }

    private static PlayerOutputDispatcher outputDispatcher(DoudizhuPlugin plugin) {
        if (plugin.getActionBarOverlayService() != null) {
            return plugin.getActionBarOverlayService().outputDispatcher();
        }
        return new PlayerOutputDispatcher(plugin);
    }

    private static String normalizeCommand(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(separator + 1).trim();
            values.put(key, value);
        }
        return values;
    }

    private static String replace(String raw, String playerName, GameTable table, CardPattern pattern, List<DoudizhuCard> cards) {
        String cardsText = cards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" "));
        String patternText = table.describePlayedCards(pattern, cards);
        return raw
            .replace("<arg:player.name>", playerName)
            .replace("<arg:table.name>", table.getName())
            .replace("<arg:pattern>", patternText)
            .replace("<arg:cards>", cardsText);
    }

    private static boolean booleanValue(String raw) {
        return raw != null && (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("yes"));
    }

    private static int intValue(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static float floatValue(String raw, float fallback) {
        try {
            return raw == null ? fallback : Float.parseFloat(raw.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}

