package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.assets.PlayerHeadRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import org.junit.jupiter.api.Test;

/**
 * 出牌 HUD 两行排版的守护测试。
 *
 * <p>这里不满足于「字符串里有几张牌」，而是把输出当成绘图指令逐个执行、追踪光标坐标，
 * 因为这个功能真正会坏的地方是几何：偏移量算错一个像素，编译和覆盖率都看不出来，
 * 玩家却会看到牌叠歪、或者整行偏离屏幕中心。
 */
class TrickHudViewTest {
    /** 测试用的偏移写法，形如 {@code <off:-14>}，方便解析回像素数。 */
    private static final IntFunction<String> OFFSETS = pixels -> "<off:" + pixels + ">";

    /**
     * 模拟一个头像段：真实头像画完后光标正停在右缘，所以用一段等宽的纯偏移代替。
     *
     * <p>宽度取真实算式 {@code PlayerHeadRenderer.advanceWidth}，而不是随手写个 48 ——
     * 48 是「关掉描边后可见的 8x8 脸」的高度，拿它当宽度是历史上踩过的坑。
     */
    private static final int BIG_SCALE = 6;
    private static final int SMALL_SCALE = 4;
    private static final int BIG_WIDTH = PlayerHeadRenderer.advanceWidth(BIG_SCALE, true);
    private static final int SMALL_WIDTH = PlayerHeadRenderer.advanceWidth(SMALL_SCALE, true);
    private static final int SLOT = BIG_WIDTH;

    private static final TrickHudView.Avatar BIG = avatar(BIG_WIDTH);
    private static final TrickHudView.Avatar SMALL = avatar(SMALL_WIDTH);

    private static final int GAP = 4;
    private static final int STEP = 22;

    /**
     * 一段宽度确定的假头像。
     *
     * <p>内容写成 {@code <avatar:宽>}，绘图解释器把它当成「前进这么多像素，并记下起点」——
     * 不能直接用 {@code <off:宽>}：那样头像和居中补偿在输出里长得一模一样，
     * 测「头像行从哪开始」时就分不出来了。
     */
    private static TrickHudView.Avatar avatar(int width) {
        return new TrickHudView.Avatar("<avatar:" + width + ">", width);
    }

    /** 只画头像行、不画牌的常用入口。 */
    private static String avatarRowOnly() {
        return TrickHudView.buildMiniMessage(SMALL, BIG, SMALL, SLOT, GAP, List.of(), STEP, OFFSETS, 0, 0, 0);
    }

    /** 两行都画的常用入口。 */
    private static String bothRows(List<DoudizhuCard> cards) {
        return TrickHudView.buildMiniMessage(SMALL, BIG, SMALL, SLOT, GAP, cards, STEP, OFFSETS, 0, 0, 0);
    }

    /**
     * 用【真的 MiniMessage 解析器】跑一遍输出，逐段核对字体归属。
     *
     * <p>为什么必须真解析：前面那些测试都是拿正则扫字符串，能验几何但验不了「字体到底
     * 落在谁身上」。而这次把三类字形从 minecraft:default 拆到各自字体后，正确性完全
     * 押在两个 MiniMessage 行为上，它们都是推理得来的，没实测过：
     * <ol>
     *   <li>牌面那段的字体是 muz:cards；</li>
     *   <li>夹在牌之间的偏移片段自带 {@code <font:...>}，它的 {@code </font>} 只弹回
     *       上一层（牌面字体），不会把后面的牌掉回 default —— 一旦弹错，后面所有牌
     *       全变豆腐块。</li>
     * </ol>
     *
     * <p>顺带钉死最要紧的那条：字体【不能漏到 HUD 之后的文本】。老代码注释坚持
     * 「必须挂 default，否则同段文本里的中文会丢字形」，我判定那个理由只在整段套字体时
     * 成立才敢拆。如果判断错了，桌边玩家名字会变豆腐块，所以这里把「HUD 后面接一段中文，
     * 那段必须仍是默认字体」写成断言。
     */
    @Test
    void 每段文本的字体归属正确且不会漏给后续文本() {
        // 模拟 CraftEngine 的偏移片段：它自己就是一段带字体的文本。
        IntFunction<String> ceOffsets =
            pixels -> "<font:craftengine:offset>" + (pixels < 0 ? "L" : "R") + "</font>";
        List<DoudizhuCard> cards = List.of(card(CardRank.THREE), card(CardRank.FOUR));

        String line = TrickHudView.buildMiniMessage(
            TrickHudView.Avatar.EMPTY, TrickHudView.Avatar.EMPTY, TrickHudView.Avatar.EMPTY,
            0, GAP, cards, STEP, ceOffsets, 0, 0, 0);
        // 后面接一段中文，模拟真实场景里 HUD 之后还要拼玩家名字。
        Component parsed = MiniMessage.miniMessage().deserialize(line + "张三");

        List<String> cardFonts = new ArrayList<>();
        List<String> offsetFonts = new ArrayList<>();
        List<String> chineseFonts = new ArrayList<>();
        collectFonts(parsed, null, cardFonts, offsetFonts, chineseFonts);

        assertTrue(cardFonts.size() >= 2, "两张牌的字形应该都被解析出来，实际：" + cardFonts);
        for (String font : cardFonts) {
            assertEquals(PackAssets.CARD_GLYPH_FONT, font,
                "牌面字形没落在 " + PackAssets.CARD_GLYPH_FONT + " 上，客户端会显示豆腐块");
        }
        assertEquals(List.of("craftengine:offset"), offsetFonts,
            "偏移片段应该保持自己的字体");
        // 这条是拆字体这个决定的正确性依据：中文必须仍是默认字体。
        // 「默认」在 Adventure 里表示成 style().font() == null，也就是没有显式指定字体，
        // 所以期望值用 singletonList(null)（List.of 不接受 null 元素）。
        assertEquals(Collections.singletonList(null), chineseFonts,
            "HUD 的字体漏给了后面的中文 —— 玩家名字会变豆腐块，说明不能这样拆字体");
    }

    /** 递归收集各类字符所在的字体：牌面字形、偏移字符、中文各归一类。 */
    private static void collectFonts(
        Component component,
        String inherited,
        List<String> cardFonts,
        List<String> offsetFonts,
        List<String> chineseFonts
    ) {
        String font = component.style().font() == null
            ? inherited
            : component.style().font().asString();
        if (component instanceof TextComponent text) {
            for (int index = 0; index < text.content().length(); index++) {
                char current = text.content().charAt(index);
                if (current == 'L' || current == 'R') {
                    if (!offsetFonts.contains(font)) {
                        offsetFonts.add(font);
                    }
                } else if (current >= 0x4E00 && current <= 0x9FFF) {
                    if (!chineseFonts.contains(font)) {
                        chineseFonts.add(font);
                    }
                } else if (current >= 0xE000 && current <= 0xF8FF) {
                    cardFonts.add(font);
                }
            }
        }
        for (Component child : component.children()) {
            collectFonts(child, font, cardFonts, offsetFonts, chineseFonts);
        }
    }

    /**
     * 【常显】桌上没牌时只有上排空着，三连头像照旧输出。
     *
     * <p>守的风险：以前这里返回空串、调用方据此把整条 BossBar 收掉，于是每打完一轮
     * HUD 就闪一下。改成常显后如果有人「顺手」把空串那条恢复回来，玩家看到的还是闪烁，
     * 而所有几何测试都会照旧全绿 —— 只有这条能发现。
     */
    @Test
    void 桌上没牌时仍然输出三连头像() {
        for (List<DoudizhuCard> empty : new List[] {List.of(), null}) {
            String line = TrickHudView.buildMiniMessage(
                SMALL, BIG, SMALL, SLOT, GAP, empty, STEP, OFFSETS, 0, 0, 0);

            assertFalse(line.isEmpty(), "桌上没牌时不该收掉整条 HUD，头像轮换要一直在");
            assertEquals(0, cardLeftEdges(line).size(), "没牌时上排必须真的空着，不能留上一轮的残影");
            assertEquals(
                TrickHudView.containerAdvance(SLOT, GAP, 0, STEP, 0),
                netAdvance(line),
                "只有头像行时总宽应当就是头像行宽，多出的偏移会把居中带歪"
            );
        }
    }

    /** 连头像都没有（不在 PLAYING、或三个座位全空）才该收掉整条 HUD。 */
    @Test
    void 三个头像槽全空且没牌时才返回空串() {
        assertEquals("", TrickHudView.buildMiniMessage(
            TrickHudView.Avatar.EMPTY, TrickHudView.Avatar.EMPTY, TrickHudView.Avatar.EMPTY,
            SLOT, GAP, List.of(), STEP, OFFSETS, 0, 0, 0));
        assertEquals("", TrickHudView.buildMiniMessage(
            null, null, null, SLOT, GAP, null, STEP, OFFSETS, 0, 0, 0));
    }

    @Test
    void 每张牌的左缘间距等于配置的步长() {
        List<DoudizhuCard> cards = cards(CardRank.THREE, CardRank.FOUR, CardRank.FIVE, CardRank.SIX);
        List<Integer> lefts = cardLeftEdges(bothRows(cards));

        assertEquals(4, lefts.size(), "四张牌应该画出四个字形");
        for (int index = 1; index < lefts.size(); index++) {
            assertEquals(
                STEP,
                lefts.get(index) - lefts.get(index - 1),
                "第 " + index + " 张牌与前一张的左缘间距不等于步长，牌会叠歪"
            );
        }
    }

    /**
     * 【两行各自居中】较窄那行必须自己补 (宽-窄)/2，否则它会靠左。
     *
     * <p>这是整个两行布局最容易错的一处：客户端只按总宽居中一次，两行宽度不同时
     * 窄的那行不会自动跟着居中。这里直接比对两行的【视觉中心】——它们必须落在同一条线上。
     * 少补偿、补成整份而不是一半、或者补到错的那一行，三种写法都会被这条抓到。
     */
    @Test
    void 两行各自居中于同一条中线() {
        // 逐张数验：牌行从比头像行窄一路涨到比它宽，补偿方向会翻转。
        for (int count : new int[] {1, 2, 3, 5, 10, 20}) {
            List<DoudizhuCard> cards = repeated(count);
            String line = bothRows(cards);

            int cardRowAdvance = (count - 1) * STEP + PackAssets.cardGlyphAdvance(0);
            int avatarRowAdvance = 3 * SLOT + 2 * GAP;
            int container = Math.max(cardRowAdvance, avatarRowAdvance);

            // 牌行中心：第一张牌左缘 + 行宽的一半。
            double cardCenter = cardLeftEdges(line).getFirst() + cardRowAdvance / 2.0;
            double avatarCenter = avatarRowStart(line) + avatarRowAdvance / 2.0;

            assertEquals(
                container / 2.0, cardCenter, 0.5,
                count + " 张牌时上排没有居中于容器中线，两行会错开"
            );
            assertEquals(
                container / 2.0, avatarCenter, 0.5,
                count + " 张牌时下排头像没有居中于容器中线，中间那个大头像会偏离屏幕中心"
            );
        }
    }

    /**
     * 【首尾配对】净前进量必须恒等于容器宽。
     *
     * <p>负空格是计入客户端总宽的，所以两行的补偿、回退、水平偏移全部必须闭合成
     * 「净前进量 = 容器宽」。少配一段总宽就漂移，客户端的居中基准跟着挪，
     * 玩家看到整条 HUD 偏向一侧 —— 而且偏移量随张数变化，看着像随机抖动。
     */
    @Test
    void 净前进量恒等于容器宽() {
        for (int count : new int[] {0, 1, 2, 5, 20}) {
            String line = bothRows(repeated(count));
            assertEquals(
                TrickHudView.containerAdvance(SLOT, GAP, count, STEP, 0),
                netAdvance(line),
                count + " 张牌时净前进量不等于容器宽，首尾偏移没配对，客户端会把 HUD 居中到错误的位置"
            );
        }
    }

    @Test
    void 牌按从小到大重排且不修改传入列表() {
        List<DoudizhuCard> cards = new ArrayList<>(cards(CardRank.ACE, CardRank.THREE, CardRank.KING));
        List<DoudizhuCard> original = List.copyOf(cards);
        String line = bothRows(cards);

        assertEquals(
            List.of(
                PackAssets.cardGlyphChar(card(CardRank.THREE)),
                PackAssets.cardGlyphChar(card(CardRank.KING)),
                PackAssets.cardGlyphChar(card(CardRank.ACE))
            ),
            glyphs(line),
            "HUD 的牌序要和桌面中央已出牌区一致：从小到大"
        );
        assertEquals(original, cards, "排版不该修改调用方传进来的列表");
    }

    @Test
    void 牌面显式染白避免继承外部颜色() {
        String line = bothRows(cards(CardRank.THREE));
        assertTrue(line.contains("<white>"), "彩色牌面贴图会被文本颜色乘算，必须显式染白才是原色");
        assertTrue(line.contains("</white>"), "染白要闭合，避免影响后面的内容");
    }

    @Test
    void 单张牌不产生多余偏移() {
        // 只画牌行（三槽全空、槽宽 0）时容器宽就是牌行宽，净前进量必须正好是一张牌的前进量。
        String line = TrickHudView.buildMiniMessage(
            TrickHudView.Avatar.EMPTY, TrickHudView.Avatar.EMPTY, TrickHudView.Avatar.EMPTY,
            0, GAP, cards(CardRank.THREE), STEP, OFFSETS, 0, 0, 0);
        assertEquals(
            PackAssets.CARD_GLYPH_ADVANCE,
            netAdvance(line),
            "只有一张牌时不该补步长偏移，否则右侧会多出一段空白把居中带偏"
        );
    }

    @Test
    void 步长等于前进量时不产生偏移标签() {
        String line = TrickHudView.buildMiniMessage(
            TrickHudView.Avatar.EMPTY, TrickHudView.Avatar.EMPTY, TrickHudView.Avatar.EMPTY,
            0,
            GAP,
            cards(CardRank.THREE, CardRank.FOUR),
            PackAssets.CARD_GLYPH_ADVANCE,
            OFFSETS,
            0, 0, 0
        );
        assertTrue(line.contains("<off:0>"), "步长等于前进量时偏移应为 0，交给偏移实现自己决定是否退化成空串");
    }

    @Test
    void 水平偏移只挪位置不改总宽() {
        // 这是 offset-x 唯一容易写错的地方：只在行首加 +x 的话，客户端算出的总宽也涨 x，
        // 它居中时又把起点左移 x/2，玩家看到的位移只有设定值的一半 —— 而且看着「有反应」，
        // 于是服主会一路把数值调到两倍，换个分辨率再全错。首尾配对是唯一正确写法。
        List<DoudizhuCard> cards = cards(CardRank.THREE, CardRank.FOUR, CardRank.FIVE);
        List<Integer> centered = cardLeftEdges(bothRows(cards));
        int centeredWidth = netAdvance(bothRows(cards));

        for (int offsetX : new int[] {30, -30, 1, -120}) {
            String line = TrickHudView.buildMiniMessage(
                SMALL, BIG, SMALL, SLOT, GAP, cards, STEP, OFFSETS, 0, 0, offsetX);
            List<Integer> shifted = cardLeftEdges(line);

            assertEquals(centered.size(), shifted.size(), "偏移不该改变画出的牌数");
            for (int index = 0; index < shifted.size(); index++) {
                assertEquals(
                    centered.get(index) + offsetX,
                    shifted.get(index),
                    "offset-x=" + offsetX + " 时第 " + index + " 张牌没有整体平移这么多像素"
                );
            }
            assertEquals(
                centeredWidth,
                netAdvance(line),
                "offset-x=" + offsetX + " 改变了整行总宽，客户端会把 HUD 的居中基准一起挪走，实际位移只剩一半"
            );
        }
    }

    @Test
    void 缩放档下的叠放间距按当档牌宽算() {
        // 缩放后牌的字形前进量也跟着变小。补差时若仍用默认档的前进量，
        // 牌与牌之间会被拉开一段固定空隙（或反向重叠），而每一档错的量还不一样。
        for (int heightTier = 0; heightTier < PackAssets.cardGlyphHeightTierCount(); heightTier++) {
            String line = TrickHudView.buildMiniMessage(
                SMALL, BIG, SMALL, SLOT, GAP,
                cards(CardRank.THREE, CardRank.FOUR, CardRank.FIVE), STEP, OFFSETS, heightTier, 0, 0);
            List<Integer> lefts = cardLeftEdges(line, heightTier, 0);

            assertEquals(3, lefts.size(), "缩放档 " + heightTier + " 的牌字形没被认出来，码位算错了");
            for (int index = 1; index < lefts.size(); index++) {
                assertEquals(
                    STEP,
                    lefts.get(index) - lefts.get(index - 1),
                    "缩放档 " + heightTier + " 的第 " + index + " 张牌间距不等于步长，牌会叠歪"
                );
            }
        }
    }

    @Test
    void 向下偏移档不影响水平排版() {
        // 下移是靠 ascent 实现的，纯垂直；如果哪天有人拿负空格去凑下移，
        // 水平排版会跟着被污染，这里把「换偏移档，横向一个像素都不动」钉住。
        List<DoudizhuCard> cards = cards(CardRank.THREE, CardRank.FOUR, CardRank.FIVE);
        List<Integer> baseline = cardLeftEdges(bothRows(cards), 0, 0);

        for (int downTier = 1; downTier < PackAssets.cardGlyphDownOffsetTierCount(); downTier++) {
            List<Integer> shifted = cardLeftEdges(
                TrickHudView.buildMiniMessage(
                    SMALL, BIG, SMALL, SLOT, GAP, cards, STEP, OFFSETS, 0, downTier, 0), 0, downTier);
            assertEquals(baseline, shifted, "偏移档 " + downTier + " 改变了水平排版，下移只该动垂直方向");
        }
    }

    /**
     * 【人数不足 3】小头像取不到人时留空，但槽宽照旧保留，大头像仍在正中。
     *
     * <p>守的风险：空槽如果不占宽度，三槽就变成两槽甚至一槽，中间那个大头像会跟着
     * 往左滑 —— 而这在满座时永远看不到，只有有人中途离桌才显形，正是最容易漏测的路径。
     */
    @Test
    void 人数不足时空槽仍占宽度使大头像保持居中() {
        List<DoudizhuCard> cards = cards(CardRank.THREE, CardRank.FOUR);
        int container = TrickHudView.containerAdvance(SLOT, GAP, 2, STEP, 0);
        double expectedBigCenter = container / 2.0;

        record Case(String name, TrickHudView.Avatar left, TrickHudView.Avatar right) {
        }
        List<Case> cases = List.of(
            new Case("满座", SMALL, SMALL),
            new Case("缺上一位", TrickHudView.Avatar.EMPTY, SMALL),
            new Case("缺下一位", SMALL, TrickHudView.Avatar.EMPTY),
            new Case("只剩自己", TrickHudView.Avatar.EMPTY, TrickHudView.Avatar.EMPTY)
        );

        for (Case testCase : cases) {
            String line = TrickHudView.buildMiniMessage(
                testCase.left(), BIG, testCase.right(), SLOT, GAP, cards, STEP, OFFSETS, 0, 0, 0);
            List<Integer> avatars = avatarLeftEdges(line);

            // 中间那个大头像是列表里的哪一个：左槽有人时是第二个，否则是第一个。
            int bigIndex = testCase.left().isEmpty() ? 0 : 1;
            double bigCenter = avatars.get(bigIndex) + BIG_WIDTH / 2.0;

            assertEquals(
                expectedBigCenter, bigCenter, 0.5,
                testCase.name() + "：大头像没落在容器中线上，空槽没有保留宽度、布局塌了"
            );
            assertEquals(
                container,
                netAdvance(line),
                testCase.name() + "：总宽变了，说明空槽没按整槽前进"
            );
        }
    }

    /**
     * 【槽内居中】三个头像宽度不同，每个都必须在自己槽位里居中。
     *
     * <p>守的风险：直接左对齐时中间那个大头像看着还行（它就是最宽的、槽宽等于它），
     * 但两侧小头像会贴着左边，三连看着不对称；而机器人兜底图标只有 11 像素宽，
     * 左对齐会让它孤零零挂在槽的最左侧。
     */
    @Test
    void 每个头像在自己槽位里居中() {
        TrickHudView.Avatar tiny = avatar(11);
        String line = TrickHudView.buildMiniMessage(
            SMALL, BIG, tiny, SLOT, GAP, List.of(), STEP, OFFSETS, 0, 0, 0);
        List<Integer> avatars = avatarLeftEdges(line);
        int rowStart = avatarRowStart(line);

        assertEquals(3, avatars.size(), "三个槽都有人时应该画出三段头像");
        int[] widths = {SMALL_WIDTH, BIG_WIDTH, 11};
        for (int slot = 0; slot < 3; slot++) {
            int slotStart = rowStart + slot * (SLOT + GAP);
            assertEquals(
                slotStart + SLOT / 2.0,
                avatars.get(slot) + widths[slot] / 2.0,
                0.5,
                "第 " + slot + " 个头像没在自己槽位里居中，三连看着会歪"
            );
        }
    }

    /**
     * 【20 张不超屏】一手最多 20 张（四个三带的飞机），叠放后不能横出屏幕。
     *
     * <p>守的风险：card-step 或缩放档改动后 20 张会悄悄变宽。480 是最窄的常见可用宽度
     * （1920 分辨率 / GUI 缩放 4 = 480 GUI 像素），HUD 超过它就会被裁掉两端。
     * 两行布局下容器宽取两行较大者，所以这条也顺带守住「头像行没有反过来变成瓶颈」。
     */
    @Test
    void 二十张牌加头像行不超出最窄常见屏宽() {
        int container = TrickHudView.containerAdvance(SLOT, GAP, 20, STEP, 0);
        assertEquals(container, netAdvance(bothRows(repeated(20))), "20 张时净前进量应当就是容器宽");
        assertTrue(
            container <= 480,
            "20 张牌的两行 HUD 宽 " + container + " 像素，超过 1920/GUI4 的 480 可用宽度会被裁掉两端"
        );
    }

    // ---- 下面是把输出当绘图指令执行的小解释器 ----

    /** count 张同样的牌。用来验张数变化时的几何，牌面本身是什么无关。 */
    private static List<DoudizhuCard> repeated(int count) {
        List<DoudizhuCard> cards = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            cards.add(card(CardRank.THREE));
        }
        return cards;
    }

    /** 逐 token 执行，返回每个牌字形被画下时的光标 x 坐标。 */
    private static List<Integer> cardLeftEdges(String line) {
        return cardLeftEdges(line, 0, 0);
    }

    private static List<Integer> cardLeftEdges(String line, int heightTier, int downTier) {
        List<Integer> lefts = new ArrayList<>();
        walk(line, lefts, null, heightTier, downTier, new ArrayList<>());
        return lefts;
    }

    /** 逐 token 执行，返回整行结束时光标的位置，也就是客户端算出的净宽。 */
    private static int netAdvance(String line) {
        int[] cursor = new int[1];
        walk(line, new ArrayList<>(), cursor, 0, 0, new ArrayList<>());
        return cursor[0];
    }

    /** 每个头像被画下时的光标 x 坐标（左缘）。 */
    private static List<Integer> avatarLeftEdges(String line) {
        List<Integer> avatars = new ArrayList<>();
        walk(line, new ArrayList<>(), null, 0, 0, avatars);
        return avatars;
    }

    /** 头像行的起点：第一个槽位的左缘（含槽内居中留白），即行首补偿之后的位置。 */
    private static int avatarRowStart(String line) {
        // 第一个槽里的头像左缘 = 行首补偿 + 槽内居中留白，所以要把留白减回去。
        int firstAvatarLeft = avatarLeftEdges(line).getFirst();
        return firstAvatarLeft - (SLOT - SMALL_WIDTH) / 2;
    }

    private static List<String> glyphs(String line) {
        List<String> found = new ArrayList<>();
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '<') {
                index = line.indexOf('>', index);
                continue;
            }
            if (isCardGlyph(current, 0, 0)) {
                found.add(String.valueOf(current));
            }
        }
        return found;
    }

    private static void walk(
        String line,
        List<Integer> cardLefts,
        int[] cursorOut,
        int heightTier,
        int downTier,
        List<Integer> avatarLefts
    ) {
        int cursor = 0;
        int advance = PackAssets.cardGlyphAdvance(heightTier);
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '<') {
                int close = line.indexOf('>', index);
                String tag = line.substring(index + 1, close);
                if (tag.startsWith("off:")) {
                    cursor += Integer.parseInt(tag.substring(4));
                } else if (tag.startsWith("avatar:")) {
                    avatarLefts.add(cursor);
                    cursor += Integer.parseInt(tag.substring(7));
                }
                index = close;
                continue;
            }
            if (isCardGlyph(current, heightTier, downTier)) {
                cardLefts.add(cursor);
                cursor += advance;
            }
        }
        if (cursorOut != null) {
            cursorOut[0] = cursor;
        }
    }

    /** 该字符是不是【这一档】的牌字形。认错档会让下面的坐标全按错的前进量累加。 */
    private static boolean isCardGlyph(char value, int heightTier, int downTier) {
        int tier = heightTier * PackAssets.cardGlyphDownOffsetTierCount() + downTier;
        int start = PackAssets.CARD_GLYPH_CODEPOINT_START + tier * 55;
        return value >= start && value < start + 55;
    }

    private static List<DoudizhuCard> cards(CardRank... ranks) {
        List<DoudizhuCard> cards = new ArrayList<>();
        for (CardRank rank : ranks) {
            cards.add(card(rank));
        }
        return cards;
    }

    private static DoudizhuCard card(CardRank rank) {
        return new DoudizhuCard(rank.ordinal(), rank, CardSuit.SPADES);
    }

    /**
     * 头像前进宽度必须按【描边后的 10 行】算，且开/关描边不能算出同一个值。
     *
     * <p><b>守的是哪个 bug。</b>字形盒高由构建期的 {@code (avatarOutlinedPixels - row) * scale}
     * 决定，描边那两行【永远】参与字形度量；运行期关掉 avatar-outline 只是不画描边像素，
     * 盒子照样占 10 行。源码注释里曾写着「6 倍是 48 像素高」——那是可见的 8x8 脸，不是字形盒，
     * 照它把 10 改成 8 会让每个头像槽算窄 2*scale 像素，三槽累计偏差 36 像素，
     * 中间那个大头像就不再对准屏幕中线（而这个布局的全部意义就是它对准中线）。
     *
     * <p><b>为什么必须单独写这条。</b>本文件其余测试的槽宽都是从
     * {@code PlayerHeadRenderer.advanceWidth} 现算的，算式改错时期望值会跟着一起错 ——
     * 那些测试对这个 bug 完全免疫（实测：把 10 改成 8，本文件其余测试全绿）。
     * 所以这里必须钉死【字面量】，让期望值不再依赖被测算式。
     */
    @Test
    void 头像前进宽度按描边后的十行算而不是可见的八行() {
        // 10 行 * 6 = 60。
        //
        // 【为什么不再是 70】列距曾经是 scale + 1（位图字形自带 1 像素字间距），于是宽度算
        // 10 * 7 = 70 —— 但那 1 像素在列间露成了透明缝，头像被切成竖条纹。修法是渲染时逐个
        // 用负偏移把字间距抵掉，列距变成 scale，视觉宽度与净前进量都跟着变成 10 * 6 = 60。
        // 改的是列距语义，不是放宽断言：字面量照旧钉死，只是钉在新的正确值上。
        // 「盒高按 10 行而不是可见的 8 行」这条性质【原样保留】，也仍然是本条测试的主诉。
        assertEquals(60, PlayerHeadRenderer.advanceWidth(6, true),
            "scale-6 描边头像的前进宽度不再是 60：字形盒是 AVATAR_OUTLINED_PIXELS(10) 行，"
                + "不是可见脸的 8 行。算窄会让头像槽偏，中间大头像偏离屏幕中线。");
        assertEquals(40, PlayerHeadRenderer.advanceWidth(4, true),
            "scale-4 描边头像的前进宽度不再是 40（10 行 * 4）");

        // 按 8 行算会得到 48，与 60 不同 —— 这就是本条守的那个 bug。
        assertEquals(PackAssets.AVATAR_OUTLINED_PIXELS * 6, PlayerHeadRenderer.advanceWidth(6, true),
            "宽度算式与 AVATAR_OUTLINED_PIXELS 脱钩了");
        assertNotEquals(PackAssets.AVATAR_HEAD_PIXELS * 6, PlayerHeadRenderer.advanceWidth(6, true),
            "宽度按可见的 8 行算了：每槽会窄 2*scale，三槽累计偏差让中间大头像偏离屏幕中线");
        assertFalse(
            PlayerHeadRenderer.advanceWidth(6, true) == PlayerHeadRenderer.advanceWidth(6, false),
            "开描边与关描边算出了同样的宽度：说明 outlined 这个参数被忽略了，"
                + "两种配置下必有一种排版是错的");
    }
}
