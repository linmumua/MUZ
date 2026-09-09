package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.model.DoudizhuCard;

/**
 * 出牌 HUD 的排版：把「桌上最后打出的那手牌」和「上一位/当前/下一位三连头像」
 * 拼成【两行】MiniMessage。
 *
 * <pre>
 *         [ 牌 牌 牌 ]          上排：桌上最后打出的那手牌
 *    (小)    ( 大 )    (小)     中排：上一位 / 当前该出牌的人 / 下一位
 *   3⁴4⁴5⁴…K⁴A⁴2⁴ 小¹大¹        下排：记牌器，每个点数还剩几张
 * </pre>
 *
 * <p>「两行」是靠字形自带的 ascent 实现的，不是真的换行：BossBar 标题只有一行文本，
 * 但位图字形可以指定 ascent，把整个字形盒沉到基线下方任意深度。上排用牌的偏移档，
 * 下排用一个更深的偏移档（差值恰好等于头像字形盒高，见
 * {@link linmumua.doudizhu.assets.PackAssets#avatarRowDownOffset}），两者就上下相接。
 * 水平方向两行各自从行首开始画，中间靠负偏移把光标拉回来。
 *
 * <p>这里是纯函数，不碰 Bukkit 也不碰网络，方便测试把输出当绘图指令逐个执行、验算坐标。
 *
 * <h2>居中：各行宽度不同，较窄那行必须自己补偿</h2>
 * BossBar 标题由客户端按【文本总宽】自动居中，负空格计入总宽，所以只要偏移量和被抵掉的
 * 前进量严格相等，客户端算出的总宽就等于实际视觉宽度，居中自然正确。
 *
 * <p>但客户端只按总宽居中【一次】：各行宽度不同（上排随张数变，中排三槽固定，
 * 下排随剩余张数的位数变），直接各自从行首画的话，窄的行会靠左。所以这里取
 * {@code W = max(各行宽)} 当容器宽，每行前面垫 {@code (W - 本行宽) / 2}、后面把光标补到 W，
 * 各行就都居中于同一条中线。首尾仍严格配对（净前进量恒等于 W），否则总宽漂移、居中跟着错位。
 */
final class TrickHudView {
    private TrickHudView() {
    }

    /**
     * 头像行里的一个槽位。
     *
     * @param text          头像的 MiniMessage 片段，必须自带颜色与字体标签；
     *                      空串表示这个槽位没人（人数不足、玩家离线），槽位宽度仍要保留
     * @param advancePixels 画完这段之后光标前进了多少像素；用来在槽位里居中
     */
    record Avatar(String text, int advancePixels) {
        static final Avatar EMPTY = new Avatar("", 0);

        boolean isEmpty() {
            return text == null || text.isEmpty();
        }
    }

    /**
     * 记牌器行里的一格：一个点数图标 + 它的剩余张数。
     *
     * <p>和 {@link Avatar} 一样是【注入式】的：调用方把画好的 MiniMessage 片段和它的
     * 前进量一起交进来，这个类只做几何。字形长什么样、剩几张要不要置灰、数字用哪套图，
     * 全在调用方决定 —— 排版不必知道，也就不会被字形资源的进度卡住。
     *
     * <p>【宽度必须逐格自报，不能假定等宽】：剩 4 张时数字是一位数，剩 10 张以上是两位数
     * （双王各只有 1 张，但普通点数 4 张、理论上调用方也可能传别的计数口径）。
     * 一旦按固定宽度乘格数去算行宽，两位数的格子就会互相压字，而且行宽算错会连累居中。
     *
     * @param text          这一格的 MiniMessage 片段，必须自带颜色与字体标签；
     *                      空串表示这一格不显示（比如该点数已出完且配置了出完就隐藏），
     *                      但它的宽度仍然计入行宽，避免后面的格子整体左移
     * @param advancePixels 画完这一格之后光标前进了多少像素；【按实际位数算好再传进来】
     */
    record CounterCell(String text, int advancePixels) {
        boolean isEmpty() {
            return text == null || text.isEmpty();
        }
    }

    /**
     * 拼出 HUD 的三行。
     *
     * <p>【常显】：桌上没牌时只有上排空着，下排三连头像照旧输出 —— 整条 HUD 在 PLAYING
     * 阶段一直在，不会一轮打完就闪一下消失。只有连头像都没有（三个槽位全空）才返回空串。
     *
     * @param previous       上一位玩家的头像槽
     * @param current        当前该出牌的人的头像槽（画在正中间、用大倍数）
     * @param next           下一位玩家的头像槽
     * @param slotPixels     每个头像槽的宽度；三槽等宽是「中间那个必然居中」的前提，
     *                       所以取三者里最大的那个宽度，槽内各自居中
     * @param avatarGapPixels 相邻两个头像槽的间距
     * @param cards          桌上最后打出的那手牌，会按牌力从小到大重排，不修改传入的列表；
     *                       null 或空表示这一轮还没人出牌，上排留空
     * @param cardStepPixels 相邻两张牌左缘的间距；小于牌宽就是叠放
     * @param offsetProvider 给定像素数返回一段水平偏移文本
     * @param heightTier     牌面缩放档，0 是 1:1
     * @param cardDownTier   牌行用的向下偏移档
     * @param xOffsetPixels  整体水平偏移，正数右移、负数左移、0 保持居中
     * @param rowXOffsets    三行【各自】的水平偏移，叠加在 {@code xOffsetPixels} 之上；
     *                       正右负左。见 {@link RowXOffsets}
     * @param counterCells   记牌器行的各格，按显示顺序排列；null 或空表示不显示记牌器行。
     *                       行宽是各格前进量【逐个累加】而来，所以每格可以不等宽
     * @param counterGapPixels 相邻两格的间距
     */
    static String buildMiniMessage(
        Avatar previous,
        Avatar current,
        Avatar next,
        int slotPixels,
        int avatarGapPixels,
        List<DoudizhuCard> cards,
        int cardStepPixels,
        IntFunction<String> offsetProvider,
        int heightTier,
        int cardDownTier,
        int xOffsetPixels,
        RowXOffsets rowXOffsets,
        List<CounterCell> counterCells,
        int counterGapPixels
    ) {
        RowXOffsets rowX = rowXOffsets == null ? RowXOffsets.NONE : rowXOffsets;
        List<Avatar> slots = List.of(
            previous == null ? Avatar.EMPTY : previous,
            current == null ? Avatar.EMPTY : current,
            next == null ? Avatar.EMPTY : next
        );
        boolean hasAvatarRow = slotPixels > 0 && slots.stream().anyMatch(slot -> !slot.isEmpty());
        List<DoudizhuCard> ordered = cards == null ? List.of() : new ArrayList<>(cards);
        boolean hasCardRow = !ordered.isEmpty();
        List<CounterCell> counters = counterCells == null ? List.of() : List.copyOf(counterCells);
        boolean hasCounterRow = !counters.isEmpty();
        if (!hasAvatarRow && !hasCardRow && !hasCounterRow) {
            // 三行都没有：不在 PLAYING、或者三个座位都取不到人。留一条空 BossBar 没有意义。
            return "";
        }

        // 各行各自的净前进量。行内一切偏移都算进来，这样「补到 W」的算式才闭合。
        int cardRowAdvance = hasCardRow
            ? (ordered.size() - 1) * cardStepPixels + PackAssets.cardGlyphAdvance(heightTier)
            : 0;
        int avatarRowAdvance = hasAvatarRow ? 3 * slotPixels + 2 * avatarGapPixels : 0;
        int counterRowAdvance = counterRowAdvance(counters, counterGapPixels);
        int containerAdvance = Math.max(Math.max(cardRowAdvance, avatarRowAdvance), counterRowAdvance);

        StringBuilder builder = new StringBuilder();
        // 水平偏移【必须首尾配对】：行首 +x、行尾 -x，两者相加为 0，客户端算出的总宽不变，
        // 于是居中基准不动，而行首那一段把所有可见字形整体推走 x 像素 —— 净效果就是精确位移 x。
        // 只在行首加 +x 是错的：总宽会跟着涨 x，客户端居中时又把起点左移 x/2，实际只移动一半。
        appendOffset(builder, offsetProvider, xOffsetPixels);

        // 【每行的 x 必须和 pad 一起首尾配对】：行首多推 rowX，行尾就要多退 rowX，
        // 否则「退回行首 / 补到容器宽」这两个算式不再闭合，后面的行会被前一行的 x 带着跑，
        // 而且容器总宽会变，客户端的居中基准跟着动 —— 表现为「调 A 行的 x，B 行也在动」。
        if (hasCardRow) {
            int pad = (containerAdvance - cardRowAdvance) / 2 + rowX.card();
            appendOffset(builder, offsetProvider, pad);
            appendCardRow(builder, ordered, cardStepPixels, offsetProvider, heightTier, cardDownTier);
            // 后面还有行要画就退回行首（每行都从同一个原点开始）；否则直接把光标补到容器宽。
            appendOffset(builder, offsetProvider, hasAvatarRow || hasCounterRow
                ? -(pad + cardRowAdvance)
                : containerAdvance - pad - cardRowAdvance);
        }
        if (hasAvatarRow) {
            int pad = (containerAdvance - avatarRowAdvance) / 2 + rowX.avatar();
            appendOffset(builder, offsetProvider, pad);
            appendAvatarRow(builder, slots, slotPixels, avatarGapPixels, offsetProvider);
            appendOffset(builder, offsetProvider, hasCounterRow
                ? -(pad + avatarRowAdvance)
                : containerAdvance - pad - avatarRowAdvance);
        }
        if (hasCounterRow) {
            int pad = (containerAdvance - counterRowAdvance) / 2 + rowX.counter();
            appendOffset(builder, offsetProvider, pad);
            appendCounterRow(builder, counters, counterGapPixels, offsetProvider);
            // 最后一行负责把光标补到容器宽：净前进量恒等于 W，客户端才会把各行一起居中。
            appendOffset(builder, offsetProvider, containerAdvance - pad - counterRowAdvance);
        }

        appendOffset(builder, offsetProvider, -xOffsetPixels);
        return builder.toString();
    }

    /**
     * 三行各自的水平偏移，正右负左。
     *
     * <p>【为什么每行要独立而不是只有一个整体 x】：三行的宽度天生不等（牌行随出牌张数变，
     * 头像行固定三槽，记牌行是 15 格），一个整体 x 只能让它们一起平移，做不到「牌行靠左、
     * 记牌行再往左一点」这类排布。而横向偏移靠负空格实现，任意整数都合法、不需要预生成字形，
     * 所以拆成三个的代价只是三个配置键。
     *
     * <p>它是【叠加在整体 x 之上】的增量，不是替代：整体 x 仍然管「整条 HUD 往哪偏」，
     * 这三个管「某一行相对其他行往哪偏」。都为 0 时行为与改动前完全一致。
     */
    record RowXOffsets(int card, int avatar, int counter) {
        static final RowXOffsets NONE = new RowXOffsets(0, 0, 0);
    }

    /** 不带记牌器行的旧入口，等价于传空的记牌器行。 */
    static String buildMiniMessage(
        Avatar previous,
        Avatar current,
        Avatar next,
        int slotPixels,
        int avatarGapPixels,
        List<DoudizhuCard> cards,
        int cardStepPixels,
        IntFunction<String> offsetProvider,
        int heightTier,
        int cardDownTier,
        int xOffsetPixels
    ) {
        return buildMiniMessage(
            previous, current, next, slotPixels, avatarGapPixels, cards, cardStepPixels,
            offsetProvider, heightTier, cardDownTier, xOffsetPixels, RowXOffsets.NONE, List.of(), 0);
    }

    /**
     * 记牌器行宽：各格前进量【逐个累加】，再加上格间距。
     *
     * <p>不用「格数 × 固定宽」是因为各格位数不同（剩 4 张是一位数、剩 10 张以上是两位数），
     * 乘法算出来的行宽会和实际画出来的不一致，居中和首尾配对会同时出错。
     */
    private static int counterRowAdvance(List<CounterCell> counters, int counterGapPixels) {
        if (counters.isEmpty()) {
            return 0;
        }
        int total = (counters.size() - 1) * counterGapPixels;
        for (CounterCell cell : counters) {
            total += cell.advancePixels();
        }
        return total;
    }

    /** 上排：桌上最后打出的那手牌，从当前光标位置开始画，净前进量 = cardRowAdvance。 */
    private static void appendCardRow(
        StringBuilder builder,
        List<DoudizhuCard> ordered,
        int cardStepPixels,
        IntFunction<String> offsetProvider,
        int heightTier,
        int cardDownTier
    ) {
        // 和桌面中央的已出牌区共用同一份牌序，否则同一手牌两处顺序不一样，看着像出错了。
        ordered.sort(DoudizhuCard.DISPLAY_ORDER);

        // 牌面贴图本身是彩色的，位图字形会被文本颜色乘算，所以必须显式染白才是原色；
        // 万一继承到外面的颜色（比如红色），整手牌会整体偏色。
        builder.append("<white>");
        // 牌面字形挂在自己的字体上，必须套标签才有字形。字体名【随档位变】——
        // 牌族有 1025 档、一张字体只装 144 档，深档在 muz_cards_2..8 上，
        // 写死基名会让深档整手牌变豆腐块（而浅档正常，本地默认配置测不出来）。
        // 整段包一次而不是每张各包一次：一手最多 20 张，逐张包会让这行文本长出一倍。
        // 中间夹的负空格是 CE 的偏移字形，它自带 <font:...> 会临时切走再切回来，
        // 关闭标签只弹回上一层（也就是这里的牌面字体），不会掉回 default。
        //
        // 【标签必须开在 <white> 之内】：字体只能盖住牌面字形本身，绝不能把整行连同
        // 后面拼接的玩家名字一起套进去，否则中文会因为这张字体里没有汉字字形而变豆腐块。
        // 下面那句 </font> 在当前结构下其实是冗余的（</white> 会隐式闭合内层 font，
        // 实测确认过），但照样写出来 —— 一旦哪天 <white> 被去掉，没有它字体就会漏给后文。
        builder.append("<font:").append(PackAssets.cardGlyphFont(heightTier, cardDownTier)).append('>');
        int advance = PackAssets.cardGlyphAdvance(heightTier);
        for (int index = 0; index < ordered.size(); index++) {
            builder.append(PackAssets.cardGlyphChar(ordered.get(index), heightTier, cardDownTier));
            if (index < ordered.size() - 1) {
                // 字形自带这一档的前进量，想让下一张只前进 cardStep，
                // 就要补上两者之差（叠放时是负数）。
                builder.append(offsetProvider.apply(cardStepPixels - advance));
            }
        }
        builder.append("</font>");
        builder.append("</white>");
    }

    /**
     * 下排：三个等宽槽位，每个头像在自己槽位里居中，净前进量 = avatarRowAdvance。
     *
     * <p>槽内居中是必须的：三个头像宽度并不相同（大小倍数不同，机器人兜底图标又只有 11 像素），
     * 直接左对齐会让中间那个大头像偏离中线，而「中间头像正对屏幕中心」是这个布局的全部意义。
     * 空槽位也照样前进一整槽，人数不足时布局才不会塌。
     */
    private static void appendAvatarRow(
        StringBuilder builder,
        List<Avatar> slots,
        int slotPixels,
        int avatarGapPixels,
        IntFunction<String> offsetProvider
    ) {
        for (int index = 0; index < slots.size(); index++) {
            Avatar slot = slots.get(index);
            int used = slot.isEmpty() ? 0 : slot.advancePixels();
            int lead = (slotPixels - used) / 2;
            appendOffset(builder, offsetProvider, lead);
            if (!slot.isEmpty()) {
                builder.append(slot.text());
            }
            // 槽位右侧补齐，再加槽间距。整数除法把奇数余量留给右侧，误差最多 1 像素。
            int trail = slotPixels - lead - used;
            appendOffset(builder, offsetProvider, index < slots.size() - 1 ? trail + avatarGapPixels : trail);
        }
    }

    /**
     * 下排：记牌器，每格一个点数图标 + 剩余张数，净前进量 = counterRowAdvance。
     *
     * <p>【逐格累加而不是等宽铺开】：每格宽度由调用方按实际位数算好传进来，这里只负责
     * 依次画出并在格间补间距。空格子照样前进它自报的宽度，这样「某个点数出完就隐藏」
     * 时后面的格子不会整体左移、行宽也不会变。
     */
    private static void appendCounterRow(
        StringBuilder builder,
        List<CounterCell> counters,
        int counterGapPixels,
        IntFunction<String> offsetProvider
    ) {
        for (int index = 0; index < counters.size(); index++) {
            CounterCell cell = counters.get(index);
            if (cell.isEmpty()) {
                // 不画内容，但要把这一格的宽度走完，否则后面所有格子左移、行宽也对不上。
                appendOffset(builder, offsetProvider, cell.advancePixels());
            } else {
                builder.append(cell.text());
            }
            if (index < counters.size() - 1) {
                appendOffset(builder, offsetProvider, counterGapPixels);
            }
        }
    }

    /** 偏移为 0 时不产出标签：省文本长度，也让「有没有偏移」在测试里一目了然。 */    private static void appendOffset(StringBuilder builder, IntFunction<String> offsetProvider, int pixels) {
        if (pixels != 0) {
            builder.append(offsetProvider.apply(pixels));
        }
    }

    /**
     * 各行 HUD 的净前进量（也就是客户端用来居中的那个总宽），单位像素。
     *
     * <p>不含水平偏移：偏移只挪位置、不改宽度（首尾配对相加为 0）。
     * 用来在测试里核对排版，也方便判断会不会超屏。
     */
    static int containerAdvance(
        int slotPixels, int avatarGapPixels, int cardCount, int cardStepPixels, int heightTier) {
        return containerAdvance(
            slotPixels, avatarGapPixels, cardCount, cardStepPixels, heightTier, List.of(), 0);
    }

    /** 带记牌器行的版本；记牌器行宽由各格前进量逐个累加而来。 */
    static int containerAdvance(
        int slotPixels,
        int avatarGapPixels,
        int cardCount,
        int cardStepPixels,
        int heightTier,
        List<CounterCell> counterCells,
        int counterGapPixels
    ) {
        int cardRow = cardCount <= 0
            ? 0
            : (cardCount - 1) * cardStepPixels + PackAssets.cardGlyphAdvance(heightTier);
        int counterRow = counterRowAdvance(
            counterCells == null ? List.of() : counterCells, counterGapPixels);
        return Math.max(Math.max(cardRow, 3 * slotPixels + 2 * avatarGapPixels), counterRow);
    }
}
