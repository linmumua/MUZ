package linmumua.doudizhu.ui.dialog;

import java.util.Locale;

/**
 * Dialog 输入文本解析的纯函数集合。
 *
 * <p>原生 Dialog 的文本输入框给到服务端的是原始字符串，玩家可能输入空串、多余空格、
 * 全角逗号、非数字、NaN/Infinity 等任何内容。所有解析都集中在这里，且**刻意不静默钳错**：
 * 非法输入一律抛 {@link IllegalArgumentException} 并带中文提示，由调用方决定提示给玩家还是忽略。
 *
 * <p>不依赖任何 Bukkit 类型，便于直接单测。
 */
public final class DialogInputParsers {
    private DialogInputParsers() {
    }

    /** 去空格；null 返回空串。 */
    public static String trimOrEmpty(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** 是否为取消输入：null/空白/"取消"/"cancel"（忽略大小写）。 */
    public static boolean isCancel(String raw) {
        String value = trimOrEmpty(raw);
        return value.isEmpty()
            || value.equals("取消")
            || value.equalsIgnoreCase("cancel");
    }

    /**
     * 解析 RGB 颜色，沿用旧箱子输入的语义：支持 {@code "255,226,92"} 三个十进制分量，
     * 也支持 {@code "F9B5B5"} 六位十六进制。
     *
     * @param raw 原始输入
     * @return 长度为 3 的数组 {r,g,b}，各分量 0..255
     * @throws IllegalArgumentException 输入非法
     */
    public static int[] parseRgbRequired(String raw) {
        String value = trimOrEmpty(raw);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("颜色不能为空，请输入 255,226,92 或 F9B5B5 格式");
        }
        // 中文全角逗号容错：玩家常从聊天里复制粘贴。
        value = value.replace('，', ',').replace(' ', ',');
        if (value.contains(",")) {
            String[] parts = value.split(",");
            java.util.List<String> tokens = new java.util.ArrayList<>();
            for (String part : parts) {
                String token = part.trim();
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
            if (tokens.size() != 3) {
                throw new IllegalArgumentException("颜色需要 3 个分量，例如 255,226,92");
            }
            int[] rgb = new int[3];
            for (int i = 0; i < 3; i++) {
                rgb[i] = parseColorComponent(tokens.get(i));
            }
            return rgb;
        }
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() != 6 || !hex.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("颜色格式非法，请输入 255,226,92 或 F9B5B5");
        }
        return new int[] {
            Integer.parseInt(hex.substring(0, 2), 16),
            Integer.parseInt(hex.substring(2, 4), 16),
            Integer.parseInt(hex.substring(4, 6), 16)
        };
    }

    private static int parseColorComponent(String token) {
        final int parsed;
        try {
            parsed = Integer.parseInt(token);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("颜色分量必须是 0-255 的整数：" + token);
        }
        if (parsed < 0 || parsed > 255) {
            throw new IllegalArgumentException("颜色分量必须在 0-255 之间：" + token);
        }
        return parsed;
    }

    /** 把 RGB 分量格式化为 {@code "RRGGBB"} 大写十六进制。 */
    public static String formatRgb(int r, int g, int b) {
        return String.format(Locale.ROOT, "%02X%02X%02X", clamp255(r), clamp255(g), clamp255(b));
    }

    private static int clamp255(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /**
     * 解析有限 double；拒绝空、非数字、NaN、正负无穷。
     *
     * @throws IllegalArgumentException 非法输入
     */
    public static double parseFiniteDoubleRequired(String raw) {
        String value = trimOrEmpty(raw);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("数值不能为空");
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("数值格式非法：" + value);
        }
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("数值必须是有限值：" + value);
        }
        return parsed;
    }

    /** 解析有限 float；语义同 {@link #parseFiniteDoubleRequired(String)}。 */
    public static float parseFiniteFloatRequired(String raw) {
        return (float) parseFiniteDoubleRequired(raw);
    }

    /**
     * 解析整数；拒绝空与非整数（含小数、"1.0" 允许被拒，强制玩家输入整数值）。
     *
     * @throws IllegalArgumentException 非法输入
     */
    public static int parseRequiredInt(String raw) {
        String value = trimOrEmpty(raw);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("数值不能为空");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("必须输入整数：" + value);
        }
    }
}
