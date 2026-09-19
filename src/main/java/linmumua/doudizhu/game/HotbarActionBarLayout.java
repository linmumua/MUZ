package linmumua.doudizhu.game;

/**
 * Hotbar 固定坐标 ActionBar 的纯数值布局。
 *
 * <p>图标字形固定占用 {@code H} 像素；正文宽度 {@code M} 由客户端目标字体测量。
 * 正文以整数坐标居中，整条 Component 的净前进量始终保持为 {@code H}，因此客户端
 * 仍按固定 Hotbar 宽度居中，不会因为正文长度变化而把图标带偏。
 */
public final class HotbarActionBarLayout {
    private HotbarActionBarLayout() {
    }

    /**
     * 按当前资源档位的真实 advance 计算正文前后的负空格偏移。
     *
     * <p>{@code D=floor(H/2)-floor(M/2)}；图标后偏移为 {@code D-H}，正文后偏移为
     * {@code H-D-M}。四项相加后总宽严格为 {@code H}，不使用浮点数或隐式四舍五入。
     */
    public static Layout calculate(int hotbarWidth, int messageWidth) {
        if (hotbarWidth < 0) {
            throw new IllegalArgumentException("Hotbar 宽度不能为负数: " + hotbarWidth);
        }
        if (messageWidth < 0) {
            throw new IllegalArgumentException("正文宽度不能为负数: " + messageWidth);
        }
        int h = hotbarWidth;
        int d = Math.floorDiv(h, 2) - Math.floorDiv(messageWidth, 2);
        int afterGlyph = d - h;
        int afterText = h - d - messageWidth;
        if (h + afterGlyph + messageWidth + afterText != h) {
            throw new IllegalStateException("Hotbar 固定宽度布局计算不闭合");
        }
        return new Layout(h, messageWidth, d, afterGlyph, afterText);
    }

    /** 已完成整数校验的固定坐标布局。 */
    public record Layout(
        int hotbarWidth,
        int messageWidth,
        int messageStart,
        int afterGlyphOffset,
        int afterTextOffset
    ) {
        public Layout {
            if (hotbarWidth < 0 || messageWidth < 0) {
                throw new IllegalArgumentException("布局宽度不能为负数");
            }
        }

        /** 客户端按固定 Hotbar 宽度居中后的图标左坐标。 */
        public int iconLeft(int screenWidth) {
            if (screenWidth < 0) {
                throw new IllegalArgumentException("屏幕宽度不能为负数: " + screenWidth);
            }
            return Math.floorDiv(screenWidth, 2) - Math.floorDiv(hotbarWidth, 2);
        }

        /** 客户端按固定 Hotbar 宽度居中后的正文左坐标。 */
        public int messageLeft(int screenWidth) {
            return iconLeft(screenWidth) + messageStart;
        }

        /** 图标、正文与尾部偏移组合后的净前进量。 */
        public int totalWidth() {
            return hotbarWidth + afterGlyphOffset + messageWidth + afterTextOffset;
        }
    }
}
