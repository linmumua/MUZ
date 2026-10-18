package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.assets.PlayerHeadRenderer;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import org.junit.jupiter.api.Test;

/**
 * 出牌 HUD「BossBar 第四行道具栏」退役的专属测试。
 *
 * <p>【为什么单独一个文件】：这次改动是「移除实际接线」而不是「删类」。带道具栏的旧重载与
 * {@code GadgetBarRow} 类型被保留以兼容签名，因此仅靠 TrickHudViewTest 里改几条几何断言，
 * 很容易在将来被「顺手恢复」——只要有人让该重载重新消费 {@code gadgetBar} 参数，旧的几何断言
 * 也能照样过（它们只看净前进量是否闭合）。这里从两个角度把退役锁死：
 * <ol>
 *   <li><b>行为</b>：把道具栏输入喂进旧重载，输出必须与三行版【逐字节相同】，容器宽也必须与
 *       不带道具栏的七参版相同 —— 即任何道具输入都不产出第四行、也不影响居中闭合；</li>
 *   <li><b>源码契约</b>：{@code TrickHudService} 不得再引用数据源 / 快照 / 道具栏类型，
 *       {@code TrickHudView} 不得再留下第四行的渲染痕迹（行宽参与、字形产出）。</li>
 * </ol>
 *
 * <p>这是机制升级而非弱化：断言方向由「第四行必须被计入并产出」反转为「必须被忽略且不产出」，
 * 同时把「退役说明仍留档」也钉进契约，避免注释被静默抹掉。
 */
class TrickHudGadgetRowRetiredTest {
    private static final Path SERVICE =
        Path.of("src/main/java/linmumua/doudizhu/game/TrickHudService.java");
    private static final Path VIEW =
        Path.of("src/main/java/linmumua/doudizhu/game/TrickHudView.java");

    private static final IntFunction<String> OFFSETS = pixels -> "<off:" + pixels + ">";

    private static final int BIG_SCALE = 6;
    private static final int SMALL_SCALE = 4;
    private static final int BIG_WIDTH = PlayerHeadRenderer.advanceWidth(BIG_SCALE, true);
    private static final int SMALL_WIDTH = PlayerHeadRenderer.advanceWidth(SMALL_SCALE, true);
    private static final int SLOT = BIG_WIDTH;

    private static final TrickHudView.Avatar BIG = avatar(BIG_WIDTH);
    private static final TrickHudView.Avatar SMALL = avatar(SMALL_WIDTH);

    private static final int GAP = 4;
    private static final int STEP = 22;

    private static TrickHudView.Avatar avatar(int width) {
        return new TrickHudView.Avatar("<avatar:" + width + ">", width);
    }

    /** 一段宽度确定的假道具栏，内容写成 {@code <gadget:宽>}；它会被旧重载忽略。 */
    private static TrickHudView.GadgetBarRow gadgetBar(int width) {
        return new TrickHudView.GadgetBarRow("<gadget:" + width + ">", width);
    }

    private static List<DoudizhuCard> hand() {
        return List.of(
            new DoudizhuCard(CardRank.THREE.ordinal(), CardRank.THREE, CardSuit.SPADES),
            new DoudizhuCard(CardRank.FOUR.ordinal(), CardRank.FOUR, CardSuit.HEARTS));
    }

    /** 真实实现（三行版）的输出。 */
    private static String threeRow(List<DoudizhuCard> cards) {
        return TrickHudView.buildMiniMessage(
            SMALL, BIG, SMALL, SLOT, GAP, cards, STEP, OFFSETS, 0, 0, 0,
            TrickHudView.RowXOffsets.NONE, List.of(), 0, false);
    }

    /**
     * 带道具栏的旧重载输出。{@code avatarCells} 为 false 时三行全空，用来证明「只有道具栏输入」
     * 不会自己撑出一行。
     */
    private static String withGadget(List<DoudizhuCard> cards, boolean avatars, TrickHudView.GadgetBarRow bar) {
        return TrickHudView.buildMiniMessage(
            avatars ? SMALL : TrickHudView.Avatar.EMPTY,
            avatars ? BIG : TrickHudView.Avatar.EMPTY,
            avatars ? SMALL : TrickHudView.Avatar.EMPTY,
            avatars ? SLOT : 0,
            GAP, cards, STEP, OFFSETS, 0, 0, 0,
            TrickHudView.RowXOffsets.NONE, List.of(), 0, false, bar, 0);
    }

    /**
     * 行为：无论喂多宽的道具栏，旧重载都必须逐字节等于三行版。
     *
     * <p>覆盖「满格 198 / 变长 88 / 空 0」三种宽度，以及「头像+牌」「只有牌」「三行全空」三种输入，
     * 把「道具栏参数被彻底忽略」钉死。
     */
    @Test
    void 带道具栏重载逐字节等价三行版() {
        for (int width : new int[] {0, 88, 198}) {
            TrickHudView.GadgetBarRow bar = width == 0
                ? TrickHudView.GadgetBarRow.EMPTY
                : gadgetBar(width);
            String expected = threeRow(hand());
            String actual = withGadget(hand(), true, bar);
            assertEquals(expected, actual,
                "栏宽 " + width + "：带道具栏重载必须与三行版逐字节一致，第四行不得产出");
            assertFalse(actual.contains("<gadget"),
                "栏宽 " + width + "：已退役的九格栏字形不得出现在出牌 HUD 输出里");
        }

        // 只给道具栏、其余三行全空：退役前会画出第四行，现在必须是空串。
        assertEquals("", withGadget(List.of(), false, gadgetBar(198)),
            "只有道具栏输入而没有其它三行时，不得产出任何 HUD 文本");
        // 没有任何输入：与三行版同为受控空表（保持既有「三行全空即收 HUD」语义）。
        assertEquals("", withGadget(List.of(), false, TrickHudView.GadgetBarRow.EMPTY));
    }

    /**
     * 行为：带道具栏的容器宽重载必须忽略道具栏宽度，恒等于不带道具栏的七参版。
     *
     * <p>并直接与「三行各自宽度取最大」的第一性原理对照，确保忽略之后算出的仍是原三行结果。
     */
    @Test
    void 容器宽重载忽略道具栏宽度且等于原三行结果() {
        int slot = SLOT;
        List<DoudizhuCard> hand = hand();
        int cardRow = (hand.size() - 1) * STEP + PackAssets.cardGlyphAdvance(0);
        int avatarRow = 3 * slot + 2 * GAP;
        int expectedThreeRow = Math.max(cardRow, avatarRow);

        assertEquals(expectedThreeRow, TrickHudView.containerAdvance(slot, GAP, hand.size(), STEP, 0),
            "七参版（无道具栏）应当就是三行各自宽度取最大");
        for (int width : new int[] {0, 88, 198, 400}) {
            assertEquals(expectedThreeRow,
                TrickHudView.containerAdvance(slot, GAP, hand.size(), STEP, 0, List.of(), 0, width),
                "栏宽 " + width + " 必须被忽略：带道具栏的容器宽重载要恒等于原三行结果");
        }
    }

    /**
     * 源码契约：TrickHudService 不再接线第四行。
     *
     * <p>断言针对【代码形状】而不是裸字符串——退役说明注释里会保留
     * {@code TableGadgetBarHudService} / {@code hudBarSnapshot} / {@code GadgetBarRow} 等历史名词，
     * 那正是我们要求留档的内容，不能用「整文件不含某名字」把它误伤掉。这里钉死的是调用/构造形状。
     */
    @Test
    void TrickHudService不再接线第四行() throws IOException {
        String source = Files.readString(SERVICE);

        assertFalse(source.contains("getTableGadgetBarHudService"),
            "TrickHudService 不得再获取桌内道具栏数据源");
        assertFalse(source.contains("hudBarSnapshot("),
            "TrickHudService 不得再读取道具栏只读快照");
        assertFalse(source.contains("gadgetBarRow"),
            "第四行快照变量与取快照的私有方法应已删除");
        assertFalse(source.contains("new TrickHudView.GadgetBarRow("),
            "TrickHudService 不得再构造道具栏片段");
        // 保留退役说明，避免注释被静默抹掉。
        assertTrue(source.contains("第四行道具栏已退役"),
            "必须留下有效的退役/历史兼容说明");
    }

    /** 源码契约：TrickHudView 已无第四行渲染路径，且旧重载确实是「委托」而非「就地忽略参数」。 */
    @Test
    void TrickHudView带道具栏重载只委托不渲染() throws IOException {
        String source = Files.readString(VIEW);

        // 第四行渲染痕迹必须消失。
        assertFalse(source.contains("hasGadgetBarRow"),
            "不得再保留第四行的存在性判定");
        assertFalse(source.contains("gadgetBar.glyphText()"),
            "不得再从道具栏取出字形产出第四行");
        assertFalse(source.contains("gadgetBar.advancePixels()"),
            "不得再把道具栏宽度计入任何算式");
        assertFalse(source.contains("Math.max(0, gadgetBarAdvance)"),
            "容器宽不得再取道具栏宽度");

        // 旧重载必须委托三行版：方法体内只应有一次 return，且调用的是不带道具栏的重载。
        int signature = source.indexOf(
            "List<CounterCell> counterCells,\n        int counterGapPixels,\n"
                + "        boolean continuousFont,\n        GadgetBarRow gadgetBar,\n"
                + "        int gadgetBarXOffset\n    ) {");
        assertTrue(signature >= 0, "带道具栏的旧重载签名必须保留以兼容调用方");
        int bodyEnd = source.indexOf("\n    }", signature);
        assertTrue(bodyEnd > signature, "找不到旧重载方法体结束位置");
        String body = source.substring(signature, bodyEnd);
        assertTrue(body.contains("return buildMiniMessage("),
            "旧重载必须直接委托三行版");
        assertTrue(body.contains("counterCells, counterGapPixels, continuousFont);"),
            "旧重载必须调用不带道具栏的三行版（参数到此为止）");
        assertFalse(body.contains("hasAvatarRow"),
            "旧重载体内不得再有任何第四行绘制逻辑（三行排布由被委托的方法负责）");

        // 容器宽重载的八参版（带 gadgetBarAdvance）必须委托七参版。
        assertTrue(source.contains(
                "counterCells, counterGapPixels);"),
            "容器宽八参重载必须委托不带道具栏的七参版");
    }
}
