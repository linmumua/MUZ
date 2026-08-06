package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 机器人头像图标的渲染约束。
 *
 * 背景：真人玩家的桌边名字不带图标，机器人没有皮肤，
 * 在名字前面拼一个位图字体图标当头像。
 *
 * 用源码扫描而不是构造 Component：seatName 依赖
 * GameTable 与 Bukkit，这个项目的测试跑不起 Bukkit；而这里要守的决策
 * （必须显式白色、必须用共享常量）恰好能在源码层面判定。
 * 写法沿用 SeatLabelParityTest。
 */
class BotAvatarIconTest {
    private static final Path MANAGER = Path.of("src/main/java/linmumua/doudizhu/world/PhysicalTableManager.java");
    /**
     * 图标的实际构造已收进 PackAssets，桌边座位牌与出牌 HUD 共用它，
     * 所以"必须白色、不许粗斜体、码位来自常量"这三条约束要在这里守。
     */
    private static final Path ASSETS = Path.of("src/main/java/linmumua/doudizhu/assets/PackAssets.java");

    /**
     * 图标必须显式染成白色。
     *
     * 这是本次改动最容易回退的一点：位图字形本质上还是文本字符，
     * 会继承外层 Component 的颜色。机器人名字用的是 AQUA，
     * 如果图标不显式设色，整个图标会被染成青色，原图配色全部丢失
     * （用户原话：「遇到头像先把 image 去掉颜色，否则沿用当前文本色」）。
     *
     * 用 WHITE 而不是 reset：WHITE 是明确的白色染色，位图字形按白色渲染
     * 即等于保留贴图原色；reset 只清样式，某些客户端仍会落回父节点颜色。
     */
    @Test
    void iconIsExplicitlyWhiteSoItDoesNotInheritTheNameColor() throws IOException {
        String body = methodBody(ASSETS, "public static Component botAvatarIcon(");

        assertTrue(
            body.contains("NamedTextColor.WHITE"),
            "图标没有显式设白色，会被机器人名字的 AQUA 染色"
        );
        assertTrue(
            !body.contains("NamedTextColor.AQUA"),
            "图标被显式染成了青色，贴图原本的配色会丢失"
        );
    }

    /**
     * 图标不能带粗体或斜体。
     *
     * 座位名字带 BOLD，图标若跟着变粗，客户端会把字形横向拉伸一像素，
     * 16x16 的图标看起来会糊掉。斜体同理会把图标切歪。
     */
    @Test
    void iconDisablesBoldAndItalicSoTheGlyphIsNotDistorted() throws IOException {
        String body = methodBody(ASSETS, "public static Component botAvatarIcon(");

        List<String> missing = new ArrayList<>();
        for (String guard : List.of("TextDecoration.BOLD, false", "TextDecoration.ITALIC, false")) {
            if (!body.contains(guard)) {
                missing.add(guard);
            }
        }
        assertTrue(missing.isEmpty(), "图标没有关掉这些装饰，字形会被拉伸变形：" + missing);
    }

    /**
     * 码位只能来自共享常量，不许在渲染代码里写死。
     *
     * images.yml 的 char 与这些常量必须严格一致，否则客户端找不到字形、
     * 桌边出现豆腐块，而服务端不报任何错。把码位收在 PackAssets 的常量里，
     * 才能让资源侧的测试（CraftEngineBundleResourcesTest）真正校验得住一致性。
     *
     * 三个码位（无描边 / 地主金边 / 农民黑边）都要守：漏掉任意一个，
     * 那个角色的图标就会变成豆腐块。
     */
    @Test
    void iconCharComesFromTheSharedConstantNotAHardcodedLiteral() throws IOException {
        // 按角色选码位的逻辑在 botAvatarChar 里，图标构造只负责套样式。
        // 锚点故意不含访问修饰符：这条测试守护的是「码位来自共享常量」，
        // 方法是 private 还是 public 与该意图无关（出牌 HUD 兜底后它已转为 public）。
        String body = methodBody(ASSETS, "static String botAvatarChar(");

        List<String> missing = new ArrayList<>();
        for (String constant : List.of("BOT_AVATAR_LANDLORD_CHAR", "BOT_AVATAR_FARMER_CHAR", "BOT_AVATAR_CHAR")) {
            if (!body.contains(constant)) {
                missing.add(constant);
            }
        }
        assertTrue(missing.isEmpty(), "这些码位常量没有被用到，对应角色的图标会显示成豆腐块：" + missing);
        assertTrue(
            !body.contains("\\uf9"),
            "图标码位被写死成了字面量，与 images.yml 会各自漂移"
        );
    }

    /**
     * 桌边座位必须用机器人图标，并且要带上角色。
     *
     * statusAvatarName 已被移除（与桌名标题视觉重叠），这里同时守护它确实不存在。
     */
    @Test
    void bothSeatLabelAndStatusBannerShowTheIconForBots() throws IOException {
        // statusAvatarName 已被删除，桌名上方不再有重复的头像名称 TextDisplay
        String source = Files.readString(MANAGER);
        assertTrue(
            !source.contains("private Component statusAvatarName("),
            "statusAvatarName 方法应该已被删除，它与桌名标题视觉重叠"
        );

        // seatName 仍须显示机器人图标并传角色
        String seatNameBody = methodBody("private Component seatName(");
        assertTrue(
            seatNameBody.contains("botAvatarIcon("),
            "seatName 没有给机器人显示头像图标"
        );
        assertTrue(
            seatNameBody.contains("botAvatarIcon(table.getRole("),
            "seatName 没传角色，描边会退回无描边基础图标"
        );
    }

    /**
     * 图标只给机器人，真人玩家不能带。
     *
     * 真人有自己的 PLAYER_HEAD 头像，再加一个机器人图标等于说他是机器人。
     */
    @Test
    void iconIsGatedOnTheBotCheck() throws IOException {
        String seatName = methodBody("private Component seatName(");

        int botCheck = seatName.indexOf("isBot(");
        int iconUse = seatName.indexOf("botAvatarIcon(");
        assertTrue(botCheck >= 0, "seatName 里找不到机器人判定，图标可能加给了所有玩家");
        assertTrue(iconUse > botCheck, "图标没有被机器人判定挡住，真人玩家也会带上机器人图标");
    }

    private static String methodBody(String signature) throws IOException {
        return methodBody(MANAGER, signature);
    }

    private static String methodBody(Path file, String signature) throws IOException {
        String source = Files.readString(file);
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到 " + signature + "，这条测试的锚点已失效");
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "找不到 " + signature + " 的结束锚点");
        return source.substring(start, end);
    }
}
