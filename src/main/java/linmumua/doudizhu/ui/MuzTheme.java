package linmumua.doudizhu.ui;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class MuzTheme {
    public static final TextColor BODY_START = TextColor.color(0xFFF2CC);
    public static final TextColor BODY_END = TextColor.color(0xCFE4FF);
    public static final TextColor MUTED_START = TextColor.color(0xE5C98D);
    public static final TextColor MUTED_END = TextColor.color(0xAFC5E6);
    public static final TextColor DIVIDER_START = TextColor.color(0xA8865D);
    public static final TextColor DIVIDER_END = TextColor.color(0x8294B3);
    public static final TextColor ACCENT_START = TextColor.color(0x94F0FF);
    public static final TextColor ACCENT_END = TextColor.color(0x4AA8FF);
    public static final TextColor WARM_START = TextColor.color(0xFFD98F);
    public static final TextColor WARM_END = TextColor.color(0xFF9D2E);
    public static final TextColor SUCCESS_START = TextColor.color(0xD8FF64);
    public static final TextColor SUCCESS_END = TextColor.color(0x1ED76E);
    public static final TextColor DANGER_START = TextColor.color(0xFF9AA0);
    public static final TextColor DANGER_END = TextColor.color(0xFF3E7A);
    public static final TextColor WARNING_START = TextColor.color(0xFFE86D);
    public static final TextColor WARNING_END = TextColor.color(0xFF8C13);
    public static final TextColor HOT_START = TextColor.color(0xFF1834);
    public static final TextColor HOT_END = TextColor.color(0xFF1834);
    public static final TextColor HOT_LABEL_START = TextColor.color(0xFF7A68);
    public static final TextColor HOT_LABEL_END = TextColor.color(0xFF4E76);
    public static final TextColor HOT_VALUE_START = TextColor.color(0xFF203C);
    public static final TextColor HOT_VALUE_END = TextColor.color(0xFF0F29);
    public static final TextColor ORANGE_START = TextColor.color(0xFF9A1E);
    public static final TextColor ORANGE_END = TextColor.color(0xFF9A1E);
    public static final TextColor MULTIPLIER_WARM_START = TextColor.color(0xFFD978);
    public static final TextColor MULTIPLIER_WARM_END = TextColor.color(0xFF9728);
    public static final TextColor MULTIPLIER_HOT_START = TextColor.color(0xFF8A62);
    public static final TextColor MULTIPLIER_HOT_END = TextColor.color(0xFF243C);
    public static final TextColor MULTIPLIER_BLAZE_START = TextColor.color(0xFF684D);
    public static final TextColor MULTIPLIER_BLAZE_END = TextColor.color(0xFF0526);
    public static final TextColor CARD_LABEL = TextColor.color(0xFFF4D8);
    public static final TextColor FARMER = TextColor.color(0x3FFF68);
    public static final TextColor LANDLORD = TextColor.color(0xFF5924);

    private MuzTheme() {
    }

    public static Component plain(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 拼接多个 Component 并去除斜体装饰。null 元素被跳过。
     *
     * <p>共用于 {@link linmumua.doudizhu.DoudizhuPlugin} 和
     * {@link linmumua.doudizhu.world.PhysicalTableManager}，两者逻辑 100% 相同，
     * 因此必须共用，避免改一处漏一处。
     *
     * @param components 要拼接的组件（可含 null）
     * @return 拼接后去斜体的结果
     */
    public static Component concat(Component... components) {
        Component result = Component.empty();
        for (Component component : components) {
            if (component != null) {
                result = result.append(component);
            }
        }
        return plain(result);
    }

    public static Component text(String content, TextColor color) {
        return gradient(content, color, color, false);
    }

    public static Component body(String content) {
        return gradient(content, BODY_START, BODY_END, false);
    }

    public static Component muted(String content) {
        return gradient(content, MUTED_START, MUTED_END, false);
    }

    public static Component divider(String content) {
        return gradient(content, DIVIDER_START, DIVIDER_END, false);
    }

    public static Component accent(String content) {
        return gradient(content, ACCENT_START, ACCENT_END, true);
    }

    public static Component warm(String content) {
        return gradient(content, WARM_START, WARM_END, true);
    }

    public static Component success(String content) {
        return gradient(content, SUCCESS_START, SUCCESS_END, true);
    }

    public static Component danger(String content) {
        return gradient(content, DANGER_START, DANGER_END, true);
    }

    public static Component warning(String content) {
        return gradient(content, WARNING_START, WARNING_END, true);
    }

    public static Component hot(String content) {
        return solid(content, HOT_START, true);
    }

    public static Component hotLabel(String content) {
        return gradient(content, HOT_LABEL_START, HOT_LABEL_END, true);
    }

    public static Component hotValue(String content) {
        return gradient(content, HOT_VALUE_START, HOT_VALUE_END, true);
    }

    public static Component hotMetric(String label, String value) {
        return hotMetric(label, value, null);
    }

    public static Component hotMetric(String label, String value, String suffix) {
        return hotMetric(label, multiplierToken(value), suffix);
    }

    public static Component hotMetric(String label, Component value) {
        return hotMetric(label, value, null);
    }

    public static Component hotMetric(String label, Component value, String suffix) {
        Component component = plain(hotLabel(label));
        if (value != null) {
            component = component.append(space()).append(plain(value));
        }
        if (suffix != null && !suffix.isBlank()) {
            component = component.append(space()).append(plain(hotLabel(suffix)));
        }
        return plain(component);
    }

    public static Component multiplierToken(String token) {
        if (token == null || token.isBlank()) {
            return Component.empty();
        }
        String[] pieces = token.split("/");
        Component line = Component.empty();
        for (int index = 0; index < pieces.length; index++) {
            if (index > 0) {
                line = line.append(divider("/"));
            }
            String piece = pieces[index].trim();
            if (piece.isEmpty()) {
                continue;
            }
            if ((piece.startsWith("x") || piece.startsWith("X")) && piece.length() > 1) {
                line = line.append(multiplierWarm(piece.substring(0, 1)))
                    .append(hotValue(piece.substring(1)));
            } else {
                line = line.append(hotValue(piece));
            }
        }
        return plain(line);
    }

    public static Component orange(String content) {
        return solid(content, ORANGE_START, true);
    }

    public static Component multiplierWarm(String content) {
        return gradient(content, MULTIPLIER_WARM_START, MULTIPLIER_WARM_END, true);
    }

    public static Component multiplierHot(String content) {
        return gradient(content, MULTIPLIER_HOT_START, MULTIPLIER_HOT_END, true);
    }

    public static Component multiplierBlaze(String content) {
        return gradient(content, MULTIPLIER_BLAZE_START, MULTIPLIER_BLAZE_END, true);
    }

    public static Component solid(String content, TextColor color, boolean bold) {
        Component component = Component.text(content, color).decoration(TextDecoration.ITALIC, false);
        return bold ? component.decoration(TextDecoration.BOLD, true) : component;
    }

    public static Component solidNamed(String content, NamedTextColor color) {
        return solid(content, color, false);
    }

    public static Component cardLabel(String content) {
        return solid(content, CARD_LABEL, true);
    }

    public static Component farmer(String content) {
        return solid(content, FARMER, true);
    }

    public static Component landlord(String content) {
        return solid(content, LANDLORD, true);
    }

    public static Component named(String content, NamedTextColor color) {
        if (color == null) {
            return body(content);
        }
        if (color == NamedTextColor.GRAY) {
            return muted(content);
        }
        if (color == NamedTextColor.DARK_GRAY || color == NamedTextColor.BLACK) {
            return divider(content);
        }
        if (color == NamedTextColor.AQUA
            || color == NamedTextColor.BLUE
            || color == NamedTextColor.DARK_AQUA
            || color == NamedTextColor.DARK_BLUE
            || color == NamedTextColor.LIGHT_PURPLE
            || color == NamedTextColor.DARK_PURPLE) {
            return accent(content);
        }
        if (color == NamedTextColor.GOLD) {
            return warm(content);
        }
        if (color == NamedTextColor.YELLOW) {
            return warning(content);
        }
        if (color == NamedTextColor.GREEN || color == NamedTextColor.DARK_GREEN) {
            return success(content);
        }
        if (color == NamedTextColor.RED || color == NamedTextColor.DARK_RED) {
            return danger(content);
        }
        return body(content);
    }

    public static Component header(String gameName, String tableName, String roomTag) {
        Component line = warm("[")
            .append(accent(gameName))
            .append(divider(" · "))
            .append(body(tableName))
            .append(warm("]"));
        if (roomTag != null && !roomTag.isBlank()) {
            line = line.append(divider(" · ")).append(muted(roomTag));
        }
        return plain(line);
    }

    public static Component field(String label, String value) {
        return field(label, body(value));
    }

    public static Component field(String label, Component value) {
        return plain(muted(label).append(divider(" · ")).append(plain(value)));
    }

    public static Component banner(String gameName, String tableName, Component headline) {
        return plain(header(gameName, tableName, null).append(divider(" ")).append(plain(headline)));
    }

    public static Component row(Component head, List<Component> details) {
        Component line = plain(head);
        for (Component detail : details) {
            if (detail == null) {
                continue;
            }
            line = line.append(divider(" · ")).append(plain(detail));
        }
        return plain(line);
    }

    public static Component space() {
        return Component.text(" ").decoration(TextDecoration.ITALIC, false);
    }

    private static Component gradient(String content, TextColor start, TextColor end, boolean bold) {
        if (content == null || content.isEmpty()) {
            return Component.empty();
        }
        Component result = Component.empty();
        String[] lines = content.split("\n", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (lineIndex > 0) {
                result = result.append(Component.newline());
            }
            String line = lines[lineIndex];
            if (line.isEmpty()) {
                continue;
            }
            if (shouldUseSolid(line)) {
                Component single = solid(line, start, bold);
                result = result.append(single);
                continue;
            }
            for (int index = 0; index < line.length(); index++) {
                float progress = (float) index / (float) (line.length() - 1);
                Component part = Component.text(String.valueOf(line.charAt(index)), mix(start, end, progress))
                    .decoration(TextDecoration.ITALIC, false);
                if (bold) {
                    part = part.decoration(TextDecoration.BOLD, true);
                }
                result = result.append(part);
            }
        }
        return plain(result);
    }

    private static boolean shouldUseSolid(String content) {
        if (content == null || content.isEmpty()) {
            return true;
        }
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        return trimmed.codePointCount(0, trimmed.length()) <= 2;
    }

    private static TextColor mix(TextColor start, TextColor end, float progress) {
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        int red = Math.round(start.red() + (end.red() - start.red()) * clamped);
        int green = Math.round(start.green() + (end.green() - start.green()) * clamped);
        int blue = Math.round(start.blue() + (end.blue() - start.blue()) * clamped);
        return TextColor.color(red, green, blue);
    }
}
