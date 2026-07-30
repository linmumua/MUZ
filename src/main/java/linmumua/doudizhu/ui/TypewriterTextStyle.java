package linmumua.doudizhu.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class TypewriterTextStyle {
    private TypewriterTextStyle() {
    }

    public static void apply(TextDisplay display, Display.Billboard billboard, boolean panel, float scale) {
        display.setBillboard(billboard);
        display.setDefaultBackground(panel);
        display.setShadowed(false);
        display.setSeeThrough(false);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setTextOpacity((byte) 255);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        if (panel) {
            display.setBackgroundColor(Color.fromARGB(92, 16, 20, 24));
        } else {
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        }
        Vector3f translation = baseTranslation(billboard, panel);
        display.setTransformation(new Transformation(
            translation,
            new AxisAngle4f(),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f()
        ));
        display.setViewRange(Math.max(display.getViewRange(), 32.0f));
    }

    private static Vector3f baseTranslation(Display.Billboard billboard, boolean panel) {
        return billboard == Display.Billboard.CENTER
            ? new Vector3f(0.0f, panel ? 0.05f : 0.03f, 0.0f)
            : new Vector3f(0.0f, panel ? 0.05f : 0.03f, 0.0f);
    }

    public static Component title(String text) {
        return MuzTheme.accent(text).decoration(TextDecoration.BOLD, true);
    }

    public static Component focus(String text) {
        return MuzTheme.body(text).decoration(TextDecoration.BOLD, true);
    }

    public static Component meta(String text) {
        return MuzTheme.muted(text);
    }

    public static Component warm(String text) {
        return MuzTheme.warm(text);
    }

    public static Component accent(String text) {
        return MuzTheme.accent(text);
    }

    public static Component success(String text) {
        return MuzTheme.success(text);
    }

    public static Component danger(String text) {
        return MuzTheme.danger(text);
    }

    public static Component warning(String text) {
        return MuzTheme.warning(text);
    }

    public static Component joinLines(Component... lines) {
        Component result = Component.empty();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                result = result.append(Component.newline());
            }
            if (lines[index] != null) {
                result = result.append(lines[index]);
            }
        }
        return MuzTheme.plain(result);
    }

    public static Component line(String label, String value) {
        return joinInline(meta(label), value == null || value.isBlank() ? null : focus(value));
    }

    public static Component line(String label, Component value) {
        return joinInline(meta(label), value);
    }

    public static Component joinInline(Component... parts) {
        Component result = Component.empty();
        boolean first = true;
        for (Component part : parts) {
            if (part == null) {
                continue;
            }
            if (!first) {
                result = result.append(MuzTheme.divider(" · "));
            }
            result = result.append(MuzTheme.plain(part));
            first = false;
        }
        return MuzTheme.plain(result);
    }
}
