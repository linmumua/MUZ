package dev.codex.doudizhu.action;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.game.GameTable;
import dev.codex.doudizhu.model.CardPattern;
import dev.codex.doudizhu.model.DoudizhuCard;
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
                        MINI_MESSAGE.deserialize(title.isBlank() ? "<gray>出牌成功</gray>" : title),
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
