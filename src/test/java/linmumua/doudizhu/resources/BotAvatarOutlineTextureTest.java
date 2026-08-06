package linmumua.doudizhu.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * 描边版机器人头像贴图的几何约束。
 *
 * <p>为什么要验贴图本身：描边是画进 PNG 的（原生头像渲染器不开放描边参数），
 * 所以"描边好不好看"这件事完全由构建期的派生算法决定。这里守两条最容易
 * 悄悄坏掉的性质：
 * <ul>
 *   <li>画布必须比原图大。原图 16x16 已经画满，若不放大，描边会盖掉图标自身的边缘像素。</li>
 *   <li>描边必须沿轮廓走，不能整块填充。填充会把圆角图标描成一个方块，
 *       那就不是描边而是背景色块了。</li>
 * </ul>
 * 这两条都是肉眼进游戏才能发现、而且服务端不报错的问题。
 */
class BotAvatarOutlineTextureTest {
    private static final int BASE_SIZE = 16;
    private static final int OUTLINED_SIZE = 18;
    private static final int GOLD = 0xFFFFD24A;
    private static final int BLACK = 0xFF141414;

    private static BufferedImage read(String path) throws IOException {
        InputStream stream = BotAvatarOutlineTextureTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "资源包里找不到 " + path);
        try (stream) {
            BufferedImage image = ImageIO.read(stream);
            assertNotNull(image, path + " 不是可读的 PNG");
            return image;
        }
    }

    private static BufferedImage outlined(String role) throws IOException {
        return read("craftengine/muz/resourcepack/assets/muz/textures/font/bot_avatar_" + role + ".png");
    }

    /**
     * 画布必须放大，否则描边会吃掉图标自己的边缘。
     */
    @Test
    void outlinedCanvasIsLargerThanTheBaseIconSoTheOutlineHasRoom() throws IOException {
        BufferedImage base = read("craftengine/muz/resourcepack/assets/muz/textures/font/bot_avatar.png");
        assertEquals(BASE_SIZE, base.getWidth(), "基础图标尺寸变了，描边派生的假设需要同步更新");

        for (String role : new String[] {"landlord", "farmer"}) {
            BufferedImage image = outlined(role);
            assertEquals(OUTLINED_SIZE, image.getWidth(), role + " 描边版宽度不对，描边会盖住图标边缘");
            assertEquals(OUTLINED_SIZE, image.getHeight(), role + " 描边版高度不对，描边会盖住图标边缘");
        }
    }

    /**
     * 地主金边、农民黑边，颜色不能串。
     */
    @Test
    void eachRoleUsesItsOwnOutlineColour() throws IOException {
        assertTrue(countColour(outlined("landlord"), GOLD) > 0, "地主版没有金色描边像素");
        assertTrue(countColour(outlined("farmer"), BLACK) > 0, "农民版没有黑色描边像素");
        assertEquals(0, countColour(outlined("landlord"), BLACK), "地主版混进了黑色描边");
        assertEquals(0, countColour(outlined("farmer"), GOLD), "农民版混进了金色描边");
    }

    /**
     * 描边必须沿轮廓，不能填满四角。
     *
     * 判据：四个角必须是透明的。图标是圆角造型，描边若沿轮廓走，
     * 角上不会有像素；一旦整块填充，四角立刻变成描边色。
     */
    @Test
    void outlineFollowsTheContourInsteadOfFillingTheWholeCanvas() throws IOException {
        for (String role : new String[] {"landlord", "farmer"}) {
            BufferedImage image = outlined(role);
            int max = OUTLINED_SIZE - 1;
            int[][] corners = {{0, 0}, {max, 0}, {0, max}, {max, max}};
            for (int[] corner : corners) {
                int alpha = image.getRGB(corner[0], corner[1]) >>> 24;
                assertEquals(
                    0,
                    alpha,
                    role + " 描边版的角 (" + corner[0] + "," + corner[1] + ") 不透明，"
                        + "说明描边是整块填充而不是沿轮廓，圆角图标会变成方块"
                );
            }
        }
    }

    /**
     * 描边不能把图标本身的像素覆盖掉。
     *
     * 原图的不透明像素数应当在描边版里完整保留 —— 描边只是在外圈补像素。
     */
    @Test
    void outlinePreservesEveryOpaquePixelOfTheBaseIcon() throws IOException {
        BufferedImage base = read("craftengine/muz/resourcepack/assets/muz/textures/font/bot_avatar.png");
        int baseOpaque = 0;
        for (int y = 0; y < base.getHeight(); y++) {
            for (int x = 0; x < base.getWidth(); x++) {
                if ((base.getRGB(x, y) >>> 24) != 0) {
                    baseOpaque++;
                }
            }
        }

        for (String role : new String[] {"landlord", "farmer"}) {
            BufferedImage image = outlined(role);
            int outlineColour = role.equals("landlord") ? GOLD : BLACK;
            int preserved = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getRGB(x, y);
                    if ((argb >>> 24) != 0 && argb != outlineColour) {
                        preserved++;
                    }
                }
            }
            assertEquals(baseOpaque, preserved, role + " 描边版丢了图标本体的像素，描边盖到图上了");
        }
    }

    private static int countColour(BufferedImage image, int argb) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == argb) {
                    count++;
                }
            }
        }
        return count;
    }
}
