package linmumua.doudizhu.assets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import linmumua.doudizhu.game.PlayerRole;
import org.junit.jupiter.api.Test;

/**
 * 像素头像渲染器的守护测试。
 *
 * <p>这套方案最容易出错的地方是【几何】：头像是靠「画完一行就用负空格把光标拉回行首」
 * 拼出来的，负空格只要和一行的实际前进宽度差一个像素，8 行就会叠成阶梯状；
 * 而这种错位在编译和单元覆盖率上都看不出来，只有玩家进游戏才会发现。
 * 所以这里不只测「有没有输出」，而是把输出当成绘图指令逐个执行、追踪光标坐标。
 */
class PlayerHeadRendererTest {
    /** 测试用的假偏移实现：把偏移量原样写成 {N}，方便解析出来验算。 */
    private static final IntFunction<String> FAKE_OFFSET = pixels -> "{" + pixels + "}";

    /** 匹配一次偏移，或一个「带颜色的方块字形」。 */
    private static final Pattern TOKEN =
        Pattern.compile("\\{(-?\\d+)}|<color:#([0-9a-f]{6})>(.)</color>");

    @Test
    void outlineWrapsHeadWithoutOverwritingAnySkinPixel() {
        int[][] head = opaqueHead();
        int outline = 0xFF123456;
        int[][] outlined = PlayerHeadRenderer.withOutline(head, outline);

        assertEquals(PackAssets.AVATAR_HEAD_PIXELS + 2, outlined.length,
            "描边向外扩一圈，边长应当 +2");
        assertEquals(PackAssets.AVATAR_OUTLINED_PIXELS, outlined.length,
            "扩后的边长必须等于资源包预生成的行数，否则会取不到字形");

        // 原像素必须原样落在中心，一个都不许被描边盖掉——向内吃会让脸缺一圈。
        for (int row = 0; row < head.length; row++) {
            for (int col = 0; col < head[row].length; col++) {
                assertEquals(head[row][col], outlined[row + 1][col + 1],
                    "原像素 (" + row + "," + col + ") 必须原样保留");
            }
        }

        // 四条边的正中都应当是描边色：头像本体不透明，边框贴着它。
        int last = outlined.length - 1;
        int mid = outlined.length / 2;
        assertEquals(outline, outlined[0][mid], "上边框应为描边色");
        assertEquals(outline, outlined[last][mid], "下边框应为描边色");
        assertEquals(outline, outlined[mid][0], "左边框应为描边色");
        assertEquals(outline, outlined[mid][last], "右边框应为描边色");

        // 四个角只斜向挨着头像，按四邻规则不该被填，否则边框会鼓出四个角。
        assertEquals(0, outlined[0][0], "左上角不该填色：它只是斜向挨着头像");
        assertEquals(0, outlined[0][last], "右上角不该填色");
        assertEquals(0, outlined[last][0], "左下角不该填色");
        assertEquals(0, outlined[last][last], "右下角不该填色");
    }

    @Test
    void outlineLeavesFullyTransparentHeadUntouched() {
        // 全透明皮肤（理论上不该出现，但别让它算出一片实心色块）
        int[][] blank = new int[PackAssets.AVATAR_HEAD_PIXELS][PackAssets.AVATAR_HEAD_PIXELS];
        int[][] outlined = PlayerHeadRenderer.withOutline(blank, 0xFF123456);

        for (int[] row : outlined) {
            for (int argb : row) {
                assertEquals(0, argb, "没有任何不透明像素时不该描出任何边");
            }
        }
    }

    /**
     * 把渲染结果当绘图指令执行一遍，算出每个方块【墨水】落在哪个像素区间。
     *
     * <p>这是本文件几条几何断言共用的底座：位图字形的前进量是「墨水宽 + 1 像素字间距」，
     * 所以光标前进 {@code scale + 1} 而墨水只占 {@code [起点, 起点 + scale)}。
     * 把两者分开记录，才能验出「相邻两列的墨水之间有没有缝」。
     */
    private static List<Ink> layout(String rendered, int scale) {
        List<Ink> inks = new ArrayList<>();
        int cursor = 0;
        for (Token token : parse(rendered)) {
            if (token.shift != null) {
                cursor += token.shift;
            } else {
                inks.add(new Ink(cursor, cursor + scale, token.glyph, token.colour));
                cursor += scale + 1;
            }
        }
        return inks;
    }

    /** 一个方块的墨水区间：{@code [left, right)}。 */
    private record Ink(int left, int right, String glyph, String colour) {
    }

    /** 执行完整段之后光标停在哪 —— 也就是这段的净前进量。 */
    private static int netAdvance(String rendered, int scale) {
        int cursor = 0;
        for (Token token : parse(rendered)) {
            cursor += token.shift != null ? token.shift : scale + 1;
        }
        return cursor;
    }

    /**
     * 同一行里相邻两列的方块必须【紧邻无缝】。
     *
     * <p><b>守的是哪个 bug。</b>Minecraft 位图字形的前进量恒等于「墨水宽 + 1 像素」，那个 +1
     * 写在引擎里、资源包改不掉。方块贴图是 scale 像素宽，所以每画一个方块光标就多走 1 像素，
     * 列间露出一道 1 像素透明缝 —— 整张脸被切成竖条纹（玩家截图里就是这个现象）。
     * 必须逐个用负偏移把字间距抵掉。
     *
     * <p><b>为什么直接断言几何而不是断言字符串。</b>「输出里含某个偏移标签」这类断言对本 bug
     * 免疫：旧实现同样输出偏移标签，只是量不对。这里把输出当绘图指令执行、算出每个方块的墨水
     * 区间，再要求 {@code 第 c 列的左缘 == 第 c-1 列的右缘}。差一个像素就红。
     */
    @Test
    void 同一行相邻两列的方块必须紧邻无缝() {
        for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE;
             scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
            int rows = PackAssets.AVATAR_HEAD_PIXELS;
            List<Ink> inks = layout(
                PlayerHeadRenderer.renderMiniMessage(opaqueHead(), scale, FAKE_OFFSET), scale);
            assertEquals(rows * rows, inks.size(), "倍数 " + scale + "：全不透明头像应当画满每一格");

            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < rows; col++) {
                    Ink ink = inks.get(row * rows + col);
                    assertEquals(col * scale, ink.left(),
                        "倍数 " + scale + " 第 " + row + " 行第 " + col + " 列的左缘不在 col*scale 上："
                            + "列距不等于 scale，头像会被竖向条纹切开（或反过来挤在一起）");
                    if (col > 0) {
                        Ink left = inks.get(row * rows + col - 1);
                        assertEquals(left.right(), ink.left(),
                            "倍数 " + scale + " 第 " + row + " 行第 " + col
                                + " 列与左邻之间有 " + (ink.left() - left.right())
                                + " 像素缝隙：位图字形那 1 像素字间距没被抵掉");
                    }
                }
            }
        }
    }

    @Test
    void everyRowStartsAtColumnZeroAndAvatarEndsAtItsRightEdge() {
        int scale = 6;
        int rows = PackAssets.AVATAR_HEAD_PIXELS;
        String rendered = PlayerHeadRenderer.renderMiniMessage(opaqueHead(), scale, FAKE_OFFSET);
        List<Ink> inks = layout(rendered, scale);

        // 每一行的第一个方块都必须从 x=0 开始：换行回退量只要差一个像素，这里就会露出来。
        for (int row = 0; row < rows; row++) {
            assertEquals(0, inks.get(row * rows).left(),
                "第 " + row + " 行没有回到行首，头像会画成阶梯状");
        }
        // 最后一行不回退，光标停在头像右边缘，后面接着拼牌面才不会压在头像上。
        // 【右边缘是 rows * scale】：列距是 scale，不是 scale + 1。
        assertEquals(rows * scale, netAdvance(rendered, scale),
            "渲染结束后光标必须停在头像右边缘");
    }

    /**
     * {@code advanceWidth} 必须等于渲染实际的净前进量。
     *
     * <p><b>守的是哪个 bug。</b>排版那边用 {@code advanceWidth} 把头像在槽里居中
     * （{@code lead = (slot - advanceWidth) / 2}），渲染那边自己走另一个量。这两个数一旦脱钩，
     * 三个槽的居中全错，而且错得很隐蔽 —— 每个头像只偏几像素，没人会想到是这两处算式不一致。
     * 本次把列距从 {@code scale + 1} 改成 {@code scale}，两处都得改；只改一处就是这个 bug。
     *
     * <p>全倍数 x 开关描边都验：描边把矩阵撑到 10x10，两条路径必须同步跟着变。
     */
    @Test
    void advanceWidth必须等于渲染的净前进量() {
        for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE;
             scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
            String plain = PlayerHeadRenderer.renderMiniMessage(opaqueHead(), scale, FAKE_OFFSET);
            assertEquals(PlayerHeadRenderer.advanceWidth(scale, false), netAdvance(plain, scale),
                "倍数 " + scale + "（无描边）：advanceWidth 与实际净前进量脱钩，槽内居中会整体偏");

            int[][] outlined = PlayerHeadRenderer.withOutline(opaqueHead(), 0xFF123456);
            String withOutline = PlayerHeadRenderer.renderMiniMessage(outlined, scale, FAKE_OFFSET);
            assertEquals(PlayerHeadRenderer.advanceWidth(scale, true), netAdvance(withOutline, scale),
                "倍数 " + scale + "（带描边）：advanceWidth 与实际净前进量脱钩");
        }
    }

    /**
     * 净前进量必须等于【视觉宽度】，不能把字形的字间距算进去。
     *
     * <p><b>守的是哪个 bug。</b>这一条和上一条不同：上一条只要求两处算式一致，
     * 如果两处【同时】写成 {@code rows * (scale + 1)}，上一条照样绿，但头像右边会挂一条
     * 1 像素宽 x rows 的空白 —— 视觉宽度是 {@code rows * scale}，槽内居中会按偏大的宽度算，
     * 头像整体左移 {@code rows / 2} 像素。这里直接拿最右一列墨水的右缘当真值。
     */
    @Test
    void 净前进量必须等于最右一列墨水的右缘() {
        for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE;
             scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
            String rendered = PlayerHeadRenderer.renderMiniMessage(opaqueHead(), scale, FAKE_OFFSET);
            int rightmost = layout(rendered, scale).stream().mapToInt(Ink::right).max().orElseThrow();
            assertEquals(rightmost, netAdvance(rendered, scale),
                "倍数 " + scale + "：净前进量不等于最右墨水的右缘，头像右侧多挂/少挂了空白，"
                    + "槽内居中会整体偏移");
            assertEquals(PackAssets.AVATAR_HEAD_PIXELS * scale, rightmost,
                "倍数 " + scale + "：8 列 x scale 的视觉宽度不对");
        }
    }

    @Test
    void eachRowUsesItsOwnAscentGlyphSoTheAvatarIsNotFlattened() {
        int scale = 6;
        String rendered = PlayerHeadRenderer.renderMiniMessage(opaqueHead(), scale, FAKE_OFFSET);

        List<Token> drawn = new ArrayList<>();
        for (Token token : parse(rendered)) {
            if (token.shift == null) {
                drawn.add(token);
            }
        }
        assertEquals(64, drawn.size(), "8x8 全不透明的头像应当画 64 个方块");

        // 行号是烧进字形里的（靠 ascent 分档抬高），用错字形整行就会掉到别的高度上。
        for (int row = 0; row < PackAssets.AVATAR_HEAD_PIXELS; row++) {
            String expected = PackAssets.avatarPixelChar(scale, row);
            for (int col = 0; col < PackAssets.AVATAR_HEAD_PIXELS; col++) {
                assertEquals(expected, drawn.get(row * PackAssets.AVATAR_HEAD_PIXELS + col).glyph,
                    "第 " + row + " 行第 " + col + " 列用错了行字形");
            }
        }
        // 顺带确认 8 行确实是 8 个不同字形，而不是同一个字符重复。
        assertEquals(8, new java.util.HashSet<>(List.of(
            PackAssets.avatarPixelChar(scale, 0), PackAssets.avatarPixelChar(scale, 1),
            PackAssets.avatarPixelChar(scale, 2), PackAssets.avatarPixelChar(scale, 3),
            PackAssets.avatarPixelChar(scale, 4), PackAssets.avatarPixelChar(scale, 5),
            PackAssets.avatarPixelChar(scale, 6), PackAssets.avatarPixelChar(scale, 7))).size());
    }

    @Test
    void pixelColoursArePreservedExactly() {
        int[][] head = new int[8][8];
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                // 每个像素给一个互不相同的颜色，任何串色都会被抓到。
                head[row][col] = 0xFF000000 | (row * 8 + col) * 0x010203;
            }
        }
        String rendered = PlayerHeadRenderer.renderMiniMessage(head, 6, FAKE_OFFSET);

        List<Token> drawn = new ArrayList<>();
        for (Token token : parse(rendered)) {
            if (token.shift == null) {
                drawn.add(token);
            }
        }
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                String expected = String.format("%06x", (row * 8 + col) * 0x010203);
                assertEquals(expected, drawn.get(row * 8 + col).colour,
                    "第 " + row + " 行第 " + col + " 列颜色不是该像素本身的颜色");
            }
        }
    }

    /**
     * 透明像素不画方块，但必须【原地占满一整个列距】，右边的列不许左移。
     *
     * <p><b>守的是哪个 bug。</b>透明像素靠一段正偏移空过去。这个偏移量以前是 {@code scale + 1}
     * （跟着旧的列距），改成 {@code scale} 后如果漏改，透明像素右边的所有列会整体右移 1 像素、
     * 逐个累积；反过来漏掉这段偏移，右边的列会整体左移一整格，脸直接歪掉。
     *
     * <p>断言方式换成【按列号验墨水位置】而不是数偏移标签的个数：本次实现把「字间距抵消」
     * 与「透明占位」合并成一段输出了，数标签会把两者混在一起，而列位置是不受实现方式影响的真值。
     */
    @Test
    void 透明像素不画方块但必须占满列距() {
        int scale = 6;
        int rows = PackAssets.AVATAR_HEAD_PIXELS;
        int[][] head = opaqueHead();
        head[3][2] = 0x00FF0000; // 全透明的红：alpha 为 0
        head[3][5] = 0x40FF0000; // 半透明，低于阈值也算透明
        // 整行透明：偏移合并后这一行不该产出任何方块，但下一行仍要从行首开始。
        for (int col = 0; col < rows; col++) {
            head[6][col] = 0;
        }

        String rendered = PlayerHeadRenderer.renderMiniMessage(head, scale, FAKE_OFFSET);
        List<Ink> inks = layout(rendered, scale);
        assertEquals(rows * rows - 2 - rows, inks.size(),
            "透明像素不该画成方块，否则透明处会出现色块");

        // 逐个方块核对它落在第几列：透明像素跳过后，同行右边的列号必须一个不差。
        int index = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < rows; col++) {
                if (((head[row][col] >>> 24) & 0xFF) < 128) {
                    continue;
                }
                Ink ink = inks.get(index++);
                assertEquals(col * scale, ink.left(),
                    "第 " + row + " 行第 " + col + " 列的墨水没落在 col*scale 上："
                        + "透明像素的占位偏移不等于一个列距，同行右边的像素整体错位");
                assertEquals(PackAssets.avatarPixelChar(scale, row), ink.glyph(),
                    "第 " + row + " 行第 " + col + " 列用错了行字形");
            }
        }
        // 整段净前进量不受透明像素影响：它只决定画不画，不改宽度。
        assertEquals(PlayerHeadRenderer.advanceWidth(scale, false), netAdvance(rendered, scale),
            "有透明像素时净前进量变了，槽内居中会跟着错");
    }

    /**
     * 列距必须跟着倍数走，不能写死。
     *
     * <p>倍数是 config 可调的（4..10）。这里对每个倍数都验「行首归零 + 净前进量 = rows * scale」，
     * 写死某个倍数的值会在服主改 avatar-scale 之后错位。
     */
    @Test
    void 列距与换行回退都跟着倍数走() {
        int rows = PackAssets.AVATAR_HEAD_PIXELS;
        for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE;
             scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
            String rendered = PlayerHeadRenderer.renderMiniMessage(opaqueHead(), scale, FAKE_OFFSET);
            List<Ink> inks = layout(rendered, scale);
            for (int row = 0; row < rows; row++) {
                assertEquals(0, inks.get(row * rows).left(),
                    "倍数 " + scale + " 第 " + row + " 行没回到行首：换行回退量没跟着倍数变");
            }
            assertEquals(rows * scale, netAdvance(rendered, scale),
                "倍数 " + scale + " 的净前进量没跟着倍数变");
        }
    }

    @Test
    void hatLayerIsCompositedOverTheBaseHeadSoHatsAndHairShowUp() {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        // base 头部涂红，hat 层涂不透明的蓝 —— 叠加后应当看到蓝色。
        fill(skin, 8, 8, 0xFFFF0000);
        fill(skin, 40, 8, 0xFF0000FF);

        int[][] head = PlayerHeadRenderer.extractHead(skin);
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                assertEquals(0xFF0000FF, head[row][col],
                    "hat 层没有叠上去，戴帽子的皮肤会显示成光头");
            }
        }
    }

    @Test
    void fullyTransparentHatLeavesTheBaseHeadVisible() {
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(skin, 8, 8, 0xFFFF0000);
        fill(skin, 40, 8, 0x00000000); // hat 层全透明，绝大多数皮肤都是这样

        int[][] head = PlayerHeadRenderer.extractHead(skin);
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                assertEquals(0xFFFF0000, head[row][col],
                    "hat 全透明时不该把脸盖成透明");
            }
        }
    }

    @Test
    void skinsTooNarrowForAHatLayerStillRender() {
        // 少数畸形皮肤宽度不足 48，读 hat 区会越界；此时应当只用 base 层而不是抛异常。
        BufferedImage skin = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        fill(skin, 8, 8, 0xFF00FF00);

        int[][] head = PlayerHeadRenderer.extractHead(skin);
        assertEquals(0xFF00FF00, head[0][0]);
        assertEquals(0xFF00FF00, head[7][7]);
    }

    @Test
    void scalesOutsideThePregeneratedRangeAreRejected() {
        // 资源包只预生成了 4..10 倍的方块字形，越界会渲染成豆腐块，必须当场报错。
        assertThrows(IllegalArgumentException.class,
            () -> PackAssets.avatarPixelChar(PackAssets.AVATAR_PIXEL_MIN_SCALE - 1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> PackAssets.avatarPixelChar(PackAssets.AVATAR_PIXEL_MAX_SCALE + 1, 0));
        // 行号上界是描边后的 10 行，不是 8 行：描边多出的两行也预生成了字形。
        assertThrows(IllegalArgumentException.class,
            () -> PackAssets.avatarPixelChar(6, PackAssets.AVATAR_OUTLINED_PIXELS));
        assertThrows(IllegalArgumentException.class, () -> PackAssets.avatarPixelChar(6, -1));
    }

    @Test
    void avatarGlyphsDoNotCollideWithCardGlyphs() {
        // 三类字形各挂一个独立字体，跨类撞码位【结构上不可能】——只要字体名不同。
        // 这一条守的就是「字体名别写重」：一旦有人把两个常量写成同一个值，
        // 两张码位表会合成一张，扩档时又会互相盖掉。
        assertNotEquals(PackAssets.CARD_GLYPH_FONT, PackAssets.AVATAR_PIXEL_FONT,
            "牌面与头像的字体名重了，等于又回到共用一张码位表");
        assertNotEquals(PackAssets.CARD_GLYPH_FONT, PackAssets.BOT_AVATAR_FONT,
            "牌面与机器人头像的字体名重了");
        assertNotEquals(PackAssets.AVATAR_PIXEL_FONT, PackAssets.BOT_AVATAR_FONT,
            "头像与机器人头像的字体名重了");
        assertNotEquals("minecraft:default", PackAssets.CARD_GLYPH_FONT,
            "不能挂回 default —— 那是全服共享的码位表");
        assertNotEquals("minecraft:default", PackAssets.AVATAR_PIXEL_FONT,
            "不能挂回 default");
        assertNotEquals("minecraft:default", PackAssets.BOT_AVATAR_FONT,
            "不能挂回 default");

        assertNotEquals(PackAssets.avatarPixelChar(6, 0), PackAssets.avatarPixelChar(7, 0),
            "不同倍数必须是不同字形");
        assertFalse(PackAssets.avatarPixelChar(6, 0).equals(PackAssets.avatarPixelChar(6, 1)),
            "不同行必须是不同字形");
    }

    /**
     * 头像字形【在自己那张码位表里】必须逐个唯一，所有倍数 x 所有行 x 所有偏移档全枚举。
     *
     * <p>为什么要全枚举：上一版这条测试只拿 {@code 起点 + 55} 估了一下牌面的占用长度，
     * 没算「档位倍数」，于是牌面偏移档从 6 扩到 15、实际占用涨到 4125 个码位、
     * 盖穿头像起点时，测试照样是绿的，问题一路跑到 CE 启动才炸出一千多条警告。
     * 估算换成全枚举后，任何一次扩档只要产生重复码位，这里立刻红。
     */
    @Test
    void avatarGlyphCodepointsAreUniqueWithinTheirOwnFont() {
        Map<String, String> seen = new HashMap<>();
        // 遍历【头像自己那张档位表】。拆表后头像的码位公式用的是 avatarDownOffsetTierCount()，
        // 拿牌表的档数来遍历会漏掉或多出档位，唯一性就验不全。
        for (int tier = 0; tier < PackAssets.avatarDownOffsetTierCount(); tier++) {
            for (int scale = PackAssets.AVATAR_PIXEL_MIN_SCALE;
                 scale <= PackAssets.AVATAR_PIXEL_MAX_SCALE; scale++) {
                for (int row = 0; row < PackAssets.AVATAR_OUTLINED_PIXELS; row++) {
                    String glyph = PackAssets.avatarPixelChar(scale, row, tier);
                    String who = "scale=" + scale + " row=" + row + " downTier=" + tier;
                    String previous = seen.put(glyph, who);
                    assertNull(previous,
                        "头像字形码位 U+" + Integer.toHexString(glyph.codePointAt(0))
                            + " 被两个组合共用：" + previous + " 与 " + who);
                }
            }
        }
    }

    /**
     * 头像与 bot 图标的越界校验必须查【头像】档位表，不许借牌表。
     *
     * <p>为什么这条测得出而别的测不出：两张表在下标 0..12 上都合法，所以「借牌表校验」
     * 在所有现有用例里都是无害的等价实现 —— 码位公式没变、条目名没变、字形都取得到。
     * 唯一的可观察差异在【表尾】：头像表 13 档、牌表 15 档，借牌表会让下标 13/14 被放过。
     *
     * <p>守的风险是「哪天头像表变得比牌表长」。届时借牌表的校验会把头像自己的合法档位判成
     * 越界、当场抛异常，而这个错误的根因（校验查错表）在拆表当时就已经埋下、只是恰好被
     * 「牌表更长」掩盖着。把边界直接钉在头像表的档数上，掩盖就没了。
     */
    @Test
    void 头像与bot的越界校验查的是头像表而不是牌表() {
        int firstInvalid = PackAssets.avatarDownOffsetTierCount();

        assertThrows(
            IllegalArgumentException.class,
            () -> PackAssets.avatarPixelChar(PackAssets.AVATAR_PIXEL_MIN_SCALE, 0, firstInvalid),
            "头像档位表只有 " + firstInvalid + " 档，下标 " + firstInvalid + " 必须被拒。"
                + "没被拒说明越界校验借的是牌表（牌表更长，恰好放过了这个下标）"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PackAssets.botAvatarChar(PlayerRole.LANDLORD, firstInvalid),
            "bot 图标画在头像行，越界校验必须查头像表；借牌表会放过头像表的越界下标"
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> PackAssets.avatarPixelAssetName(PackAssets.AVATAR_PIXEL_MIN_SCALE, 0, firstInvalid),
            "头像条目名里的偏移值必须查头像表；借牌表会算出另一档的偏移、拼出资源包里不存在的名字"
        );

        // 表尾那一档必须是合法的 —— 否则上面三条会因为「校验过严」而假绿。
        int lastValid = firstInvalid - 1;
        assertDoesNotThrow(
            () -> {
                PackAssets.avatarPixelChar(PackAssets.AVATAR_PIXEL_MIN_SCALE, 0, lastValid);
                PackAssets.botAvatarChar(PlayerRole.LANDLORD, lastValid);
                PackAssets.avatarPixelAssetName(PackAssets.AVATAR_PIXEL_MIN_SCALE, 0, lastValid);
            },
            "头像表最深那一档（下标 " + lastValid + "）被判成越界了，头像行调到最低时会整片消失"
        );
    }

    /**
     * 同一个机器人每次都必须取到同一张皮肤。
     *
     * <p><b>守的是哪个 bug。</b>HUD 每秒重算一次。分配逻辑只要掺进任何「每次调用都可能变」的量
     * （随机数、{@code System.currentTimeMillis}、{@code HashSet} 的迭代顺序、
     * 甚至传进来的集合顺序），机器人就会每秒换一张脸。
     *
     * <p>顺带钉住「与传入集合的顺序无关」：调用方给的是三个槽位拼出来的 List，
     * 而槽位顺序【每一轮都在转】（上一位/当前/下一位随 currentTurn 轮换）。
     * 实现里那句 {@code sorted()} 就是为这件事存在的，去掉它这条会红。
     */
    @Test
    void 同一个机器人每次取到同一张皮肤() {
        UUID a = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID b = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID c = UUID.fromString("00000000-0000-4000-8000-000000000003");
        List<UUID> roster = List.of(a, b, c);

        int first = PlayerHeadRenderer.botSkinVariant(roster, b);
        for (int i = 0; i < 50; i++) {
            assertEquals(first, PlayerHeadRenderer.botSkinVariant(roster, b),
                "同一份名单反复查，同一个机器人算出了不同皮肤：机器人会每秒换脸");
        }
        // 含 null / 重复也不能影响结果：调用方是从槽位拼名单的，空槽和重复都可能出现。
        assertEquals(first, PlayerHeadRenderer.botSkinVariant(Arrays.asList(b, null, a, b, c, c), b),
            "名单里有 null 或重复项就换脸");
    }

    /**
     * 分配结果必须与【传入名单的顺序】无关。
     *
     * <p><b>守的是哪个 bug。</b>调用方的名单是「上一位 / 当前 / 下一位」三个槽拼出来的，
     * 而这三个槽【每一轮都在轮换】（随 currentTurn 转）。落座顺序如果就取传入顺序，
     * 同一桌在不同轮次会算出不同分配 —— 机器人每轮换一张脸。实现里那句 {@code sorted()}
     * 就是为这件事存在的。
     *
     * <p><b>为什么必须随机跑很多桌，不能手挑三个 UUID。</b>只有在【两个 bot 的首选下标撞车】时
     * 顺位探测才会介入，也只有那时候顺序才可观察 —— 手挑的 UUID 大概率首选各不相同，
     * 去掉 {@code sorted()} 照样全绿（实测：本条的初版就是这样假绿的）。
     * 这里随机 400 桌 x 每桌 3 个 bot，撞车概率约 44%，必然覆盖到。
     */
    @Test
    void 分配结果与传入名单的顺序无关() {
        java.util.Random random = new java.util.Random(31337L);
        int collisionCases = 0;
        for (int table = 0; table < 400; table++) {
            List<UUID> roster = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                roster.add(new UUID(random.nextLong(), random.nextLong()));
            }
            // 统计「首选撞车」的桌数：这类桌才真正考验顺序无关性，一个都没有就说明本条没测到东西。
            java.util.Set<Integer> preferred = new java.util.HashSet<>();
            for (UUID bot : roster) {
                preferred.add(PlayerHeadRenderer.botSkinVariant(List.of(bot), bot));
            }
            if (preferred.size() < roster.size()) {
                collisionCases++;
            }

            for (UUID bot : roster) {
                int expected = PlayerHeadRenderer.botSkinVariant(roster, bot);
                List<UUID> shuffled = new ArrayList<>(roster);
                for (int attempt = 0; attempt < 6; attempt++) {
                    java.util.Collections.shuffle(shuffled, random);
                    assertEquals(expected, PlayerHeadRenderer.botSkinVariant(shuffled, bot),
                        "第 " + table + " 桌：换了名单顺序就换了皮肤。座位每轮轮换，"
                            + "机器人会每轮换一张脸");
                }
            }
        }
        assertTrue(collisionCases > 20,
            "随机出的 400 桌里只有 " + collisionCases + " 桌首选撞车，本条测不到顺位探测的顺序敏感性，"
                + "等于假绿 —— 换个随机种子或加大样本");
    }

    /**
     * 同桌的机器人必须长得不一样。
     *
     * <p><b>守的是哪个 bug。</b>用户原话是「同桌两个 bot 都用同一张皮肤会让人以为是同一个人」。
     * 单纯 {@code hash % n} 满足不了这条 —— 两个随机 UUID 有 1/n 概率撞到同一张。
     * 实现里的「顺位探测」就是为这条存在的，去掉探测这条会红。
     *
     * <p>不是只验一两个手挑的 UUID：随机跑 2000 桌，每桌 2~3 个 bot，
     * 要求【每一桌】内部的皮肤下标互不相同。手挑 UUID 的话，恰好不撞的组合会让测试假绿。
     */
    @Test
    void 同桌的机器人皮肤互不相同() {
        java.util.Random random = new java.util.Random(20240607L);
        for (int table = 0; table < 2000; table++) {
            int botCount = 2 + random.nextInt(2); // 2 或 3 个 bot
            List<UUID> roster = new ArrayList<>();
            for (int i = 0; i < botCount; i++) {
                roster.add(new UUID(random.nextLong(), random.nextLong()));
            }
            java.util.Set<Integer> variants = new java.util.HashSet<>();
            for (UUID bot : roster) {
                int variant = PlayerHeadRenderer.botSkinVariant(roster, bot);
                assertTrue(variant >= 0 && variant < PlayerHeadRenderer.botSkinCount(),
                    "皮肤下标越界：" + variant);
                assertTrue(variants.add(variant),
                    "第 " + table + " 桌的 " + botCount + " 个机器人里有两个撞到了皮肤 " + variant
                        + "：玩家会以为是同一个人");
            }
        }
    }

    /**
     * 备的皮肤必须【真的够用】，而且每一张都得有机会出现。
     *
     * <p><b>守的是哪个 bug。</b>斗地主一桌最多 3 个 bot，皮肤池至少要有 3 张才谈得上不重脸；
     * 池子缩到 2 张时上面那条「同桌不重脸」会直接变成不可能满足。
     *
     * <p>后半段守的是另一个风险：如果分配退化成「按名单排名取第 rank 张」，
     * 那每桌都只会用到前 3 张，池子里剩下的永远见不到 —— 加皮肤等于白加。
     */
    @Test
    void 皮肤池够三个机器人用且每张都会被用到() {
        assertTrue(PlayerHeadRenderer.botSkinCount() >= 3,
            "皮肤池不足 3 张，一桌 3 个 bot 时必然重脸");

        java.util.Set<Integer> everSeen = new java.util.HashSet<>();
        java.util.Random random = new java.util.Random(777L);
        for (int table = 0; table < 3000; table++) {
            List<UUID> roster = List.of(
                new UUID(random.nextLong(), random.nextLong()),
                new UUID(random.nextLong(), random.nextLong()));
            for (UUID bot : roster) {
                everSeen.add(PlayerHeadRenderer.botSkinVariant(roster, bot));
            }
        }
        assertEquals(PlayerHeadRenderer.botSkinCount(), everSeen.size(),
            "有皮肤永远轮不到（只见到 " + everSeen + "）：分配退化成了「按排名取前几张」，"
                + "池子里后面那些白加了");
    }

    /**
     * 不在名单里的 UUID 必须被判成「没有皮肤」，让调用方走位图兜底。
     *
     * <p><b>守的是哪个 bug。</b>真人玩家也会被传进 {@code avatarSlot}，只是走另一条分支；
     * 空座位则是 null。这里返回一个「看起来合法」的下标会让真人被画上机器人的皮肤。
     */
    @Test
    void 不在名单里的UUID没有皮肤() {
        UUID bot = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
        UUID stranger = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
        assertEquals(-1, PlayerHeadRenderer.botSkinVariant(List.of(bot), stranger),
            "名单外的 UUID 拿到了皮肤下标，真人会被画上机器人的脸");
        assertEquals(-1, PlayerHeadRenderer.botSkinVariant(List.of(bot), null),
            "null 应当没有皮肤（空座位）");
        assertEquals(-1, PlayerHeadRenderer.botSkinVariant(null, bot),
            "名单为 null 时应当没有皮肤");
        assertEquals(-1, PlayerHeadRenderer.botSkinVariant(List.of(), bot),
            "空名单里不该查到皮肤");
    }

    /**
     * 机器人比皮肤还多时必须【接受重脸而不是死循环】。
     *
     * <p><b>守的是哪个 bug。</b>顺位探测如果没有上界，池子被占满后会一直找不到空位、
     * 无限循环下去 —— 那是在主线程上刷 HUD，直接卡服。重脸只是观感问题，死循环是宕机。
     * 斗地主用不到这么多 bot，但这个上界是「最要命的失败模式」的唯一防线。
     */
    @Test
    void 机器人多于皮肤数时接受重脸而不是死循环() {
        int skins = PlayerHeadRenderer.botSkinCount();
        List<UUID> roster = new ArrayList<>();
        for (int i = 0; i < skins + 4; i++) {
            roster.add(new UUID(0x5EEDL, i));
        }
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            for (UUID bot : roster) {
                int variant = PlayerHeadRenderer.botSkinVariant(roster, bot);
                assertTrue(variant >= 0 && variant < skins,
                    "池子占满后算出了越界下标 " + variant + "，取皮肤时会 IndexOutOfBounds");
            }
        }, "顺位探测没有上界：池子占满后死循环，主线程刷 HUD 时会直接卡服");
    }

    private static int[][] opaqueHead() {
        int[][] head = new int[8][8];
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                head[row][col] = 0xFF808080;
            }
        }
        return head;
    }

    private static void fill(BufferedImage image, int x, int y, int argb) {
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                image.setRGB(x + col, y + row, argb);
            }
        }
    }

    private static List<Token> parse(String rendered) {
        // 整个头像必须被包在头像自己的字体里 —— 方块字形注册在那张字体上，
        // 少了这层包装客户端找不到字形，头像会整片变成豆腐块。
        String open = "<font:" + PackAssets.AVATAR_PIXEL_FONT + ">";
        assertTrue(rendered.startsWith(open),
            "头像没有包在 " + PackAssets.AVATAR_PIXEL_FONT + " 字体里，客户端会显示成豆腐块");
        assertTrue(rendered.endsWith("</font>"), "头像的字体标签没有闭合，后面的文本会跟着变字体");
        // 剥掉这层包装再按原有规则逐个 token 校验，下面的坐标与颜色断言不受影响。
        String body = rendered.substring(open.length(), rendered.length() - "</font>".length());

        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(body);
        int consumed = 0;
        while (matcher.find()) {
            // 确认整个字符串都被识别掉了，防止渲染器悄悄输出了别的东西。
            assertEquals(consumed, matcher.start(),
                "渲染结果里有无法识别的内容：" + body.substring(consumed, matcher.start()));
            consumed = matcher.end();
            if (matcher.group(1) != null) {
                tokens.add(Token.shift(Integer.parseInt(matcher.group(1))));
            } else {
                tokens.add(Token.glyph(matcher.group(2), matcher.group(3)));
            }
        }
        assertEquals(body.length(), consumed, "渲染结果尾部有无法识别的内容");
        return tokens;
    }

    private record Token(Integer shift, String colour, String glyph) {
        static Token shift(int pixels) {
            return new Token(pixels, null, null);
        }

        static Token glyph(String colour, String glyph) {
            return new Token(null, colour, glyph);
        }
    }
}
