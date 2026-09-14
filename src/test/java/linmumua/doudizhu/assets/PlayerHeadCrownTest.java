package linmumua.doudizhu.assets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 地主王冠的守护测试。
 *
 * <p>王冠是【地主身份标识】，不是装饰：玩家靠它一眼认出谁是地主。
 *
 * <p><b>这一批测试整体重写过一次</b>，因为王冠的实现换了载体：原先是把王冠像素【盖进 8x8 脸矩阵】
 * （`withCrown(int[][]) -> int[][]`），现在是【独立字形家族】画在脸上方
 * （{@link PlayerHeadRenderer#crownMiniMessage}，返回 MiniMessage 文本）。
 * 守的风险没变，只是断言对象从矩阵变成了文本：
 * <ol>
 *   <li>王冠必须【净位移为零】—— 差一个像素，戴冠的地主整槽横向错开，三头像不对齐；</li>
 *   <li>脸的矩阵【一个像素都不能动】—— 这是换独立家族的全部意义（先前两版一个挤低脸、一个遮住头发）；</li>
 *   <li>王冠确实画出来了、是金色、且用的是王冠字体 —— 空实现也能让上面两条过；</li>
 *   <li>王冠与描边【互不干扰】—— 两者曾经互斥，现在必须能同时开。</li>
 * </ol>
 */
class PlayerHeadCrownTest {

    /** 假的偏移提供者：把偏移量原样写成可断言的标记，便于核算净位移。 */
    private static final java.util.function.IntFunction<String> OFFSETS = px -> "[" + px + "]";

    private static final Pattern OFFSET = Pattern.compile("\\[(-?\\d+)]");

    /** 造一张纯色的假脸，每个像素都不透明。 */
    private static int[][] solidFace(int argb) {
        int size = PackAssets.AVATAR_HEAD_PIXELS;
        int[][] face = new int[size][size];
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                face[row][col] = argb;
            }
        }
        return face;
    }

    /**
     * 数一段 MiniMessage 的净水平位移：所有偏移标记之和，加上每个字形自带的前进量。
     *
     * <p>字形前进量是 {@code scale + 1}（{@code GLYPH_TRAILING_SPACING} 那 1 像素字间距）。
     */
    private static int netAdvance(String miniMessage, int scale) {
        int total = 0;
        Matcher matcher = OFFSET.matcher(miniMessage);
        while (matcher.find()) {
            total += Integer.parseInt(matcher.group(1));
        }
        // 每个 <color:#......> 恰好包一个字形方块。
        int glyphs = countOccurrences(miniMessage, "<color:#");
        return total + glyphs * (scale + 1);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /**
     * 王冠画完【净位移必须为零】—— 这是三个头像能对齐的前提。
     *
     * <p><b>守的是哪个 bug。</b>第一版王冠往矩阵上加两行，戴冠的地主脸被挤低两像素、那一槽也宽一截，
     * 三头像并排时一眼看出没对齐（服主截图报的就是这个）。换成独立家族后，几何风险从「行数变了」
     * 转移到「光标没回到原位」：王冠画在脸【之前】，如果画完光标没退回行首，脸就会整体右移。
     *
     * <p>净位移为零还连带保证 {@code advanceWidth} 不用为地主分叉 —— 它只看描边。
     */
    @Test
    void 王冠画完净位移为零() {
        int scale = 6;
        for (int faceWidth : new int[] {
            PackAssets.AVATAR_HEAD_PIXELS, PackAssets.AVATAR_OUTLINED_PIXELS}) {
            String crown = PlayerHeadRenderer.crownMiniMessage(faceWidth, scale, 0, OFFSETS);
            assertEquals(0, netAdvance(crown, scale),
                "王冠净位移必须为零（脸宽 " + faceWidth + " 列），否则戴冠的头像整槽横向错开");
        }
    }

    /**
     * 换独立家族的【全部意义】：脸的矩阵一个像素都不动。
     *
     * <p><b>守的是哪两个 bug。</b>第一版往矩阵加两行 → 脸被挤低；第二版盖在头顶两行 → 头发被遮掉。
     * 现在王冠在自己的字形家族里，脸矩阵根本不参与王冠渲染 —— 这条测试钉住这一点：
     * 戴冠与不戴冠，脸那部分的输出必须【逐字符相同】。
     */
    @Test
    void 戴冠不改动脸的任何像素() {
        int scale = 6;
        int[][] face = solidFace(0xFF808080);
        String plain = PlayerHeadRenderer.renderMiniMessage(face, scale, OFFSETS, 0, false);
        String crowned = PlayerHeadRenderer.renderMiniMessage(face, scale, OFFSETS, 0, true);

        assertTrue(crowned.endsWith(plain),
            "戴冠版必须是「王冠片段 + 完全相同的脸」；脸那部分只要差一个字符，"
                + "就说明王冠又动了脸矩阵：\n不戴冠=" + plain + "\n戴冠=" + crowned);
        String crownPart = crowned.substring(0, crowned.length() - plain.length());
        assertNotEquals("", crownPart, "戴冠必须真的多画了王冠片段");
    }

    /**
     * 王冠必须真的画出来，而且是金色。
     *
     * <p><b>守的是哪个 bug。</b>上面两条测试对「空实现」全都通过 —— 什么都不画，净位移当然是零、
     * 脸当然没被动。地主标识失效是【静默】的：玩家只会觉得「怎么看不出谁是地主」，
     * 没人会想到是渲染问题。所以必须断言真的有金色方块。
     */
    @Test
    void 王冠必须画出金色方块() {
        String crown = PlayerHeadRenderer.crownMiniMessage(PackAssets.AVATAR_HEAD_PIXELS, 6, 0, OFFSETS);
        int painted = countOccurrences(crown, "<color:#");
        assertTrue(painted > 0, "王冠一个方块都没画，地主标识会静默失效：" + crown);
        assertEquals(painted, countOccurrences(crown, "ffd24a"),
            "王冠的每个方块都必须是金色 0xFFD24A（与既有地主金边同色）：" + crown);
    }

    /**
     * 王冠必须挂在【王冠自己的字体】上。
     *
     * <p><b>守的是哪个 bug。</b>王冠字形注册在 {@code muz_avatar_crown} 上，套错字体（比如沿用
     * 头像那张）会让王冠整片变豆腐块 —— 而且因为码位在头像族里也是合法的，画出来是【别的东西】，
     * 比空白更难排查。
     */
    @Test
    void 王冠套的是王冠字体() {
        String crown = PlayerHeadRenderer.crownMiniMessage(PackAssets.AVATAR_HEAD_PIXELS, 6, 0, OFFSETS);
        assertTrue(crown.contains("<font:" + PackAssets.AVATAR_CROWN_FONT + ">"),
            "王冠必须套 " + PackAssets.AVATAR_CROWN_FONT + "，套错会画成别的图案：" + crown);
    }

    /**
     * 王冠与描边【互不干扰】，必须能同时开。
     *
     * <p><b>守的是哪个 bug。</b>「王冠加两行」那版里两者是互斥的（描边把 8x8 撑到 10x10，
     * 王冠再加两行就超出预生成行数）。现在描边只作用于脸矩阵、王冠在独立家族，两者正交。
     * 这条测试同时守住居中：8 列的王冠画在 10 列的脸上时必须右移一格，否则王冠偏左。
     */
    @Test
    void 王冠与描边可以同时开且王冠居中() {
        int scale = 6;
        String onFace = PlayerHeadRenderer.crownMiniMessage(
            PackAssets.AVATAR_HEAD_PIXELS, scale, 0, OFFSETS);
        String onOutlined = PlayerHeadRenderer.crownMiniMessage(
            PackAssets.AVATAR_OUTLINED_PIXELS, scale, 0, OFFSETS);

        assertEquals(countOccurrences(onFace, "<color:#"), countOccurrences(onOutlined, "<color:#"),
            "描边不该改变王冠画多少个方块 —— 两者是正交的");
        assertNotEquals(onFace, onOutlined,
            "10 列的脸上王冠必须右移一格才居中，输出理应与 8 列时不同");
        assertEquals(0, netAdvance(onOutlined, scale),
            "居中留白必须在行末退干净，否则描边+戴冠的地主整槽错开");
    }

    /**
     * 偏移档必须真的传到王冠字形上。
     *
     * <p><b>守的是哪个 bug。</b>王冠字形每一档偏移是不同码位。如果渲染时把档位丢了（比如恒传 0），
     * 王冠会固定画在【屏幕顶部】而脸跟着 HUD 沉下去 —— 王冠和脸分家，飘在半空。
     */
    @Test
    void 偏移档变了王冠码位跟着变() {
        int scale = 6;
        int tier0 = 0;
        int alternateTier = PackAssets.avatarDownOffsetTierCount() - 1;
        assertTrue(alternateTier > tier0,
            "当前 profile 至少要生成一个非基准头像偏移档，才能验证王冠码位随档位变化");
        String tier0Message = PlayerHeadRenderer.crownMiniMessage(
            PackAssets.AVATAR_HEAD_PIXELS, scale, tier0, OFFSETS);
        String alternateMessage = PlayerHeadRenderer.crownMiniMessage(
            PackAssets.AVATAR_HEAD_PIXELS, scale, alternateTier, OFFSETS);
        assertNotEquals(tier0Message, alternateMessage,
            "不同偏移档必须用不同码位的王冠字形，否则王冠不会跟着 HUD 一起下沉");
    }

    /**
     * 缩放倍数必须真的传到王冠字形上。
     *
     * <p><b>守的是哪个 bug。</b>王冠字形按 scale 预生成。丢了 scale（比如恒传默认值）会让王冠
     * 与脸【尺寸不匹配】：脸放大到 16 倍而王冠还是 6 倍，看着像顶小帽子。
     */
    @Test
    void 缩放倍数变了王冠字形跟着变() {
        String small = PlayerHeadRenderer.crownMiniMessage(
            PackAssets.AVATAR_HEAD_PIXELS, PackAssets.AVATAR_PIXEL_MIN_SCALE, 0, OFFSETS);
        String large = PlayerHeadRenderer.crownMiniMessage(
            PackAssets.AVATAR_HEAD_PIXELS, PackAssets.AVATAR_PIXEL_MAX_SCALE, 0, OFFSETS);
        assertNotEquals(small, large, "不同 scale 必须用不同的王冠字形，否则王冠与脸尺寸不匹配");
    }
}
