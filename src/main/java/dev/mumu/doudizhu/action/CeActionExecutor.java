package dev.mumu.doudizhu.action;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.game.GameTable;
import dev.mumu.doudizhu.model.CardPattern;
import dev.mumu.doudizhu.model.DoudizhuCard;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class CeActionExecutor {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String ACCENT = "#8FD4FF";
    private static final String WARM = "#F1D398";
    private static final String MUTED = "#9AA8B6";
    private static final String SUCCESS = "#8ED8A7";
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
        if (profile == null || profile.spec().isBlank()) {
            return;
        }
        Map<String, String> args = parse(profile.spec());
        String type = args.getOrDefault("type", "none").toLowerCase(Locale.ROOT);
        if (type.equals("none")) {
            return;
        }

        try {
            switch (type) {
                case "message" -> {
                    String message = replace(args.getOrDefault("message", ""), player, table, pattern, cards);
                    if (!message.isBlank()) {
                        player.sendMessage(MINI_MESSAGE.deserialize(message));
                    }
                }
                case "actionbar" -> {
                    String actionbar = replace(args.getOrDefault("actionbar", args.getOrDefault("message", "")), player, table, pattern, cards);
                    if (!actionbar.isBlank()) {
                        player.sendActionBar(MINI_MESSAGE.deserialize(actionbar));
                    }
                }
                case "title" -> {
                    String title = replace(args.getOrDefault("title", ""), player, table, pattern, cards);
                    String subtitle = replace(args.getOrDefault("subtitle", ""), player, table, pattern, cards);
                    int fadeIn = intValue(args.get("fade-in"), 5);
                    int stay = intValue(args.get("stay"), 30);
                    int fadeOut = intValue(args.get("fade-out"), 10);
                    player.showTitle(net.kyori.adventure.title.Title.title(
                        MINI_MESSAGE.deserialize(title.isBlank() ? "<gradient:" + ACCENT + ":" + WARM + "><bold>出牌已确认</bold></gradient>" : title),
                        MINI_MESSAGE.deserialize(subtitle),
                        net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(fadeIn * 50L),
                            java.time.Duration.ofMillis(stay * 50L),
                            java.time.Duration.ofMillis(fadeOut * 50L)
                        )
                    ));
                }
                case "command" -> {
                    String command = replace(args.getOrDefault("command", ""), player, table, pattern, cards);
                    if (!command.isBlank()) {
                        if (booleanValue(args.get("as-player"))) {
                            String normalized = command.startsWith("/") ? command.substring(1) : command;
                            player.performCommand(normalized);
                        } else {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.startsWith("/") ? command.substring(1) : command);
                        }
                    }
                }
                case "play_sound" -> {
                    String sound = replace(args.getOrDefault("sound", ""), player, table, pattern, cards);
                    float volume = floatValue(args.get("volume"), 1.0f);
                    float pitch = floatValue(args.get("pitch"), 1.0f);
                    if (!sound.isBlank() && volume > 0.0f) {
                        player.playSound(player.getLocation(), sound, volume, pitch);
                    }
                }
                default -> plugin.getLogger().warning("Unsupported CE action type in play profile: " + type);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Failed to execute play action profile: " + exception.getMessage());
        }
    }

    public static void previewPlayProfile(DoudizhuPlugin plugin, Player player, DoudizhuPlugin.OptionProfile profile) {
        if (profile == null || profile.spec().isBlank()) {
            player.sendActionBar(MINI_MESSAGE.deserialize("<" + MUTED + ">当前方案没有可预览内容</" + MUTED + ">"));
            return;
        }
        Map<String, String> args = parse(profile.spec());
        String type = args.getOrDefault("type", "none").toLowerCase(Locale.ROOT);
        try {
            switch (type) {
                case "none" -> player.sendActionBar(MINI_MESSAGE.deserialize("<" + MUTED + ">当前行为方案不会额外执行操作</" + MUTED + ">"));
                case "message" -> player.sendMessage(MINI_MESSAGE.deserialize("<" + ACCENT + ">行为预览</" + ACCENT + "><dark_gray> · </dark_gray><" + WARM + ">聊天提示</" + WARM + ">"));
                case "actionbar" -> player.sendActionBar(MINI_MESSAGE.deserialize("<" + ACCENT + ">行为预览</" + ACCENT + "><dark_gray> · </dark_gray><" + WARM + ">动作栏提示</" + WARM + ">"));
                case "title" -> player.showTitle(net.kyori.adventure.title.Title.title(
                    MINI_MESSAGE.deserialize("<gradient:" + ACCENT + ":" + WARM + "><bold>行为预览</bold></gradient>"),
                    MINI_MESSAGE.deserialize("<" + MUTED + ">" + profile.label() + "</" + MUTED + ">")
                ));
                case "play_sound" -> {
                    String sound = args.getOrDefault("sound", "");
                    float volume = floatValue(args.get("volume"), 1.0f);
                    float pitch = floatValue(args.get("pitch"), 1.0f);
                    if (!sound.isBlank() && volume > 0.0f) {
                        player.playSound(player.getLocation(), sound, volume, pitch);
                    } else {
                        player.sendActionBar(MINI_MESSAGE.deserialize("<" + WARM + ">这个行为方案没有可播放的声音</" + WARM + ">"));
                    }
                }
                case "command" -> player.sendActionBar(MINI_MESSAGE.deserialize("<" + WARM + ">命令行为不能直接预览</" + WARM + ">"));
                default -> player.sendActionBar(MINI_MESSAGE.deserialize("<" + DANGER + ">暂不支持预览这个行为类型</" + DANGER + ">"));
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Failed to preview play action profile: " + exception.getMessage());
            player.sendActionBar(MINI_MESSAGE.deserialize("<" + DANGER + ">行为预览失败</" + DANGER + ">"));
        }
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

    private static String replace(String raw, Player player, GameTable table, CardPattern pattern, List<DoudizhuCard> cards) {
        String cardsText = cards.stream().map(DoudizhuCard::displayLabel).collect(Collectors.joining(" "));
        String patternText = table.describePlayedCards(pattern, cards);
        return raw
            .replace("<arg:player.name>", player.getName())
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

