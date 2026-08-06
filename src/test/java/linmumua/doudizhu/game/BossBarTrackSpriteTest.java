package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import net.kyori.adventure.bossbar.BossBar;
import org.junit.jupiter.api.Test;

/**
 * 守护「BossBar 血条轨道隐形」这条链路。
 *
 * <p>出牌 HUD 是借 BossBar 的标题来显示头像和牌的，但 BossBar 自带的血条轨道会在
 * 屏幕顶部留下一条空槽。原版协议里没有「只要标题不要血条」这种字段，唯一的办法是
 * 用资源包把轨道贴图换成全透明。
 *
 * <p>这条链路的麻烦之处在于：它由「代码里选的颜色」和「资源包里哪个文件被透明化」
 * 两半拼起来，而两边都看不见对方。改个 BossBar 颜色、图省事换成 NOTCHED 样式、
 * 把贴图放到老的 bars.png 路径、或者哪天有人把透明图换回原图——任何一处都会让
 * 空槽重新出现，且这些改动单看都很无害，编译和运行都不会报错。所以这里把两半焊死。
 */
class BossBarTrackSpriteTest {
    /** 原版血条轨道贴图的尺寸。 */
    private static final int VANILLA_TRACK_WIDTH = 182;

    private static final int VANILLA_TRACK_HEIGHT = 5;

    /**
     * 核心因果链：代码里实际使用的那个颜色，在资源包里必须有对应的全透明贴图。
     *
     * <p>文件名是从 {@link TrickHudService#BAR_COLOR} 反推出来的，没有把 "white" 写死。
     * 所以把颜色改成 BLUE，这个测试就会去找一个资源包里根本不存在的
     * blue_background.png 并失败，而不是默默放过一条重新出现的空血条。
     */
    @Test
    void hudBarColorHasAMatchingTransparentSpriteInThePack() throws IOException {
        String color = TrickHudService.BAR_COLOR.name().toLowerCase(Locale.ROOT);
        for (String suffix : List.of("background", "progress")) {
            String path = spritePath(color + "_" + suffix + ".png");
            assertFullyTransparent(readImage(path), path);
        }
    }

    /**
     * 样式必须是 PROGRESS。
     *
     * <p>NOTCHED_* 的分段刻度贴图（notched_6_background.png 之类）是按样式索引、
     * 所有颜色共用的，不走按颜色隔离那套逻辑。用了 NOTCHED，刻度就会画在透明轨道上，
     * 而想擦掉它只能去覆盖全服共用的贴图，会连带影响别的插件和原版血条。
     */
    @Test
    void hudBarOverlayIsNotNotchedBecauseNotchedSpritesAreSharedAcrossColors() {
        assertEquals(
                BossBar.Overlay.PROGRESS,
                TrickHudService.BAR_OVERLAY,
                "NOTCHED_* 的刻度贴图所有颜色共用，把 white_* 透明化挡不住它");
    }

    /**
     * 贴图必须落在 1.20.2 之后的 GUI sprite 路径上，而且要真的进了下发给客户端的包。
     *
     * <p>1.20.2 起原版把 GUI 图集拆成了 textures/gui/sprites 下的独立文件。放回老的
     * textures/gui/bars.png 既不会报错也不会生效，是最典型的静默失效。这里读的是
     * 构建产物的清单而不是源码目录，顺带保证文件确实被打进了 CraftEngine bundle。
     */
    @Test
    void transparentSpritesAreBundledAtTheVanillaGuiSpritePath() throws IOException {
        String index = read("craftengine/muz/_bundle_index.txt");
        for (String fileName : List.of("white_background.png", "white_progress.png")) {
            String entry = "resourcepack/assets/minecraft/textures/gui/sprites/boss_bar/" + fileName;
            assertTrue(index.contains(entry), "bundle 里缺少 " + entry + "，轨道不会隐形");
        }
    }

    /** 保持和原版轨道一样的尺寸，免得客户端按别的尺寸去拉伸或算错图集布局。 */
    @Test
    void transparentSpritesKeepTheVanillaTrackSize() throws IOException {
        for (String fileName : List.of("white_background.png", "white_progress.png")) {
            String path = spritePath(fileName);
            BufferedImage image = readImage(path);
            assertEquals(VANILLA_TRACK_WIDTH, image.getWidth(), "宽度和原版轨道不一致：" + path);
            assertEquals(VANILLA_TRACK_HEIGHT, image.getHeight(), "高度和原版轨道不一致：" + path);
        }
    }

    /**
     * 只能透明化我们自己用的那一档颜色，不许顺手把别的颜色也抹掉。
     *
     * <p>末影龙用 PINK、凋灵用 PURPLE。把它们的贴图一起覆盖成透明，等于把原版 boss
     * 血条从整个服务器上删掉——这是资源包最容易造成的越界破坏，而且出事时没人会
     * 想到是斗地主插件干的。notched_* 跨颜色共用，同理不能碰。
     */
    @Test
    void onlyTheHudBarColorIsTransparentSoVanillaBossBarsSurvive() throws IOException {
        String index = read("craftengine/muz/_bundle_index.txt");
        for (BossBar.Color other : BossBar.Color.values()) {
            if (other == TrickHudService.BAR_COLOR) {
                continue;
            }
            String name = other.name().toLowerCase(Locale.ROOT);
            assertFalse(
                    index.contains("boss_bar/" + name + "_"),
                    "不该覆盖 " + name + " 色血条贴图，那会弄坏原版和其他插件的 BossBar");
        }
        assertFalse(
                index.contains("boss_bar/notched_"),
                "notched_* 是所有颜色共用的刻度贴图，覆盖它会影响全服 BossBar");
    }

    /**
     * pack.mcmeta 的格式号必须和当前构建目标匹配。
     *
     * <p>这是上面所有断言都盖不住的一环：贴图路径全对、内容全透明、清单里也在，
     * 但只要 pack.mcmeta 宣称的资源包格式号和客户端不匹配，整个包就可能被拒绝或部分忽略，
     * 轨道照旧露着。而这个数字之前是**硬编码 94**（那是 1.21.11 的数据包格式号，
     * 不是任何版本的资源包格式号），三个目标版本还共用它。
     *
     * <p>三个目标的正确值：1.21.11 → 75、26.1.2 → 84、26.2 → 88。
     * 期望值由构建脚本按 muzTarget 注入，所以这条断言会跟着目标版本自动切换，
     * 不会再出现"改了目标版本忘了改格式号"。
     */
    @Test
    void packFormatMatchesTheBuildTarget() throws IOException {
        String expected = System.getProperty("muz.expectedResourcePackFormat");
        assertNotNull(expected, "构建脚本必须注入 muz.expectedResourcePackFormat");

        String mcmeta = read("craftengine/muz/resourcepack/pack.mcmeta");
        assertTrue(
                mcmeta.contains("\"pack_format\": " + expected),
                "pack.mcmeta 的 pack_format 必须是 " + expected + "（当前构建目标的资源包格式号），实际内容：" + mcmeta);
        assertTrue(
                mcmeta.contains("\"min_format\": [" + expected + ", 0]"),
                "min_format 也要跟着目标版本走，实际内容：" + mcmeta);
        // 1.21.9 起 supported_formats 已被 min_format/max_format 取代，留着它反而可能被新客户端判为过时声明
        assertFalse(
                mcmeta.contains("supported_formats"),
                "1.21.9 起应改用 min_format/max_format，不该再写 supported_formats");
    }

    private static String spritePath(String fileName) {
        return "craftengine/muz/resourcepack/assets/minecraft/textures/gui/sprites/boss_bar/" + fileName;
    }

    private static void assertFullyTransparent(BufferedImage image, String path) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xFF;
                assertEquals(0, alpha, "像素 (" + x + "," + y + ") 不透明，轨道会露出来：" + path);
            }
        }
    }

    private static BufferedImage readImage(String path) throws IOException {
        InputStream stream = BossBarTrackSpriteTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing resource " + path);
        try (stream) {
            BufferedImage image = ImageIO.read(stream);
            assertNotNull(image, "读不出图片 " + path);
            return image;
        }
    }

    private static String read(String path) throws IOException {
        InputStream stream = BossBarTrackSpriteTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing resource " + path);
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
