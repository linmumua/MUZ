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
 *    (小)    ( 大 )    (小)     下排：上一位 / 当前该出牌的人 / 下一位
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
 * <h2>居中：两行宽度不同，较窄那行必须自己补偿</h2>
 * BossBar 标题由客户端按【文本总宽】自动居中，负空格计入总宽，所以只要偏移量和被抵掉的
 * 前进量严格相等，客户端算出的总宽就等于实际视觉宽度，居中自然正确。
 *
 * <p>但客户端只按总宽居中【一次】：两行宽度不同（上排随张数变，下排三槽固定），
 * 直接各自从行首画的话，窄的那行会靠左。所以这里取 {@code W = max(两行宽)} 当容器宽，
 * 每行前面垫 {@code (W - 本行宽) / 2}、后面把光标补到 W，两行就都居中于同一条中线。
 * 首尾仍严格配对（净前进量恒等于 W），否则总宽漂移、居中跟着错位。
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
     * 拼出 HUD 的两行。
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
        int xOffsetPixels
    ) {
        List<Avatar> slots = List.of(
            previous == null ? Avatar.EMPTY : previous,
            current == null ? Avatar.EMPTY : current,
            next == null ? Avatar.EMPTY : next
        );
        boolean hasAvatarRow = slotPixels > 0 && slots.stream().anyMatch(slot -> !slot.isEmpty());
        List<DoudizhuCard> ordered = cards == null ? List.of() : new ArrayList<>(cards);
        boolean hasCardRow = !ordered.isEmpty();
        if (!hasAvatarRow && !hasCardRow) {
            // 既没牌也没头像：不在 PLAYING、或者三个座位都取不到人。留一条空 BossBar 没有意义。
            return "";
        }

        // 两行各自的净前进量。行内一切偏移都算进来，这样「补到 W」的算式才闭合。
        int cardRowAdvance = hasCardRow
            ? (ordered.size() - 1) * cardStepPixels + PackAssets.cardGlyphAdvance(heightTier)
            : 0;
        int avatarRowAdvance = hasAvatarRow ? 3 * slotPixels + 2 * avatarGapPixels : 0;
        int containerAdvance = Math.max(cardRowAdvance, avatarRowAdvance);

        StringBuilder builder = new StringBuilder();
        // 水平偏移【必须首尾配对】：行首 +x、行尾 -x，两者相加为 0，客户端算出的总宽不变，
        // 于是居中基准不动，而行首那一段把所有可见字形整体推走 x 像素 —— 净效果就是精确位移 x。
        // 只在行首加 +x 是错的：总宽会跟着涨 x，客户端居中时又把起点左移 x/2，实际只移动一半。
        appendOffset(builder, offsetProvider, xOffsetPixels);

        if (hasCardRow) {
            int pad = (containerAdvance - cardRowAdvance) / 2;
            appendOffset(builder, offsetProvider, pad);
            appendCardRow(builder, ordered, cardStepPixels, offsetProvider, heightTier, cardDownTier);
            // 还有下排要画就退回行首（下排从同一个原点开始）；否则直接把光标补到容器宽。
            appendOffset(builder, offsetProvider, hasAvatarRow
                ? -(pad + cardRowAdvance)
                : containerAdvance - pad - cardRowAdvance);
        }
        if (hasAvatarRow) {
            int pad = (containerAdvance - avatarRowAdvance) / 2;
            appendOffset(builder, offsetProvider, pad);
            appendAvatarRow(builder, slots, slotPixels, avatarGapPixels, offsetProvider);
            // 补到容器宽：净前进量恒等于 W，客户端才会把两行一起居中在屏幕中线。
            appendOffset(builder, offsetProvider, containerAdvance - pad - avatarRowAdvance);
        }

        appendOffset(builder, offsetProvider, -xOffsetPixels);
        return builder.toString();
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
        // 牌面字形挂在自己的字体上（见 PackAssets.CARD_GLYPH_FONT），必须套标签才有字形。
        // 整段包一次而不是每张各包一次：一手最多 20 张，逐张包会让这行文本长出一倍。
        // 中间夹的负空格是 CE 的偏移字形，它自带 <font:...> 会临时切走再切回来，
        // 关闭标签只弹回上一层（也就是这里的牌面字体），不会掉回 default。
        //
        // 【标签必须开在 <white> 之内】：字体只能盖住牌面字形本身，绝不能把整行连同
        // 后面拼接的玩家名字一起套进去，否则中文会因为这张字体里没有汉字字形而变豆腐块。
        // 下面那句 </font> 在当前结构下其实是冗余的（</white> 会隐式闭合内层 font，
        // 实测确认过），但照样写出来 —— 一旦哪天 <white> 被去掉，没有它字体就会漏给后文。
        builder.append("<font:").append(PackAssets.CARD_GLYPH_FONT).append('>');
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

    /** 偏移为 0 时不产出标签：省文本长度，也让「有没有偏移」在测试里一目了然。 */
    private static void appendOffset(StringBuilder builder, IntFunction<String> offsetProvider, int pixels) {
        if (pixels != 0) {
            builder.append(offsetProvider.apply(pixels));
        }
    }

    /**
     * 两行 HUD 的净前进量（也就是客户端用来居中的那个总宽），单位像素。
     *
     * <p>不含水平偏移：偏移只挪位置、不改宽度（首尾配对相加为 0）。
     * 用来在测试里核对排版，也方便判断会不会超屏。
     */
    static int containerAdvance(
        int slotPixels, int avatarGapPixels, int cardCount, int cardStepPixels, int heightTier) {
        int cardRow = cardCount <= 0
            ? 0
            : (cardCount - 1) * cardStepPixels + PackAssets.cardGlyphAdvance(heightTier);
        return Math.max(cardRow, 3 * slotPixels + 2 * avatarGapPixels);
    }
}
