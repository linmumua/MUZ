package linmumua.doudizhu.assets;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.PlayerRole;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.CardSuit;
import linmumua.doudizhu.model.DoudizhuCard;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PackAssets {
    /**
     * 机器人头像图标占用的私有区码位。
     * <p>
     * 机器人没有皮肤，无法取到 PLAYER_HEAD 原生头像，
     * 用位图字形图标代替，拼进名字 Component 即可当图标显示，不需要额外实体。
     * <p>
     * 必须与 build.gradle.kts 里的 {@code BOT_AVATAR_CHAR} 保持一致，
     * 否则运行时拼出来的字符找不到字形，桌边会显示成豆腐块。
     * <p>
     * 选 \uf900 是因为 CraftEngine 内置配置已占用 \uf800-\uf814 与 \uf830-\uf844，
     * 往后留出间隔避免以后 CE 升级撞码位。
     */
    public static final String BOT_AVATAR_CHAR = "\uf900";

    /**
     * 地主机器人头像（金色描边）与农民机器人头像（黑色描边）的码位。
     * <p>
     * 描边必须画在贴图里：{@code PlayerHeadObjectContents} 那套原生头像渲染器
     * 只暴露 name/id/profileProperties/hat/texture，没有任何描边参数
     * （4.26.1 与 5.2.0 字节码均已核实），服务端给不了描边指令。
     * 所以一个描边色 = 一张独立贴图 = 一个独立字形 = 一个独立码位。
     * <p>
     * 两张贴图由 build.gradle.kts 从 bot_avatar.png 自动派生，不手工维护。
     * 同样必须与 build.gradle.kts 里的对应常量保持一致。
     */
    public static final String BOT_AVATAR_LANDLORD_CHAR = "\uf901";
    public static final String BOT_AVATAR_FARMER_CHAR = "\uf902";

    /**
     * 机器人图标「向下偏移档」的码位起点，对应偏移档 1 及以后（档 0 用上面那三个原码位）。
     *
     * <p>出牌 HUD 里图标和牌排在同一行文本里。牌按偏移档往下沉、图标却不动的话，
     * 机器人打出的那手牌会和图标错开十几像素，看着像图标浮在牌上方。
     * 但 {@code \uf900-\uf902} 那三个码位还给桌边座位牌用着，
     * 不能跟着 HUD 一起沉，所以偏移档另开一段码位、复用同一批贴图。
     *
     * <p>每档 3 个（无描边、地主、农民，顺序即 {@link #botAvatarChar(PlayerRole, int)} 里的
     * roleIndex），必须与 build.gradle.kts 里的 {@code botAvatarDownCodepointStart} 保持一致。
     */
    /**
     * 机器人图标字形高度：无角色那份 10 像素，带角色描边那两份 11 像素。
     *
     * <p>必须与 build.gradle.kts 里 {@code botAvatarGlyphs} 的高度逐字一致
     * （那边是 {@code "bot_avatar" to 10, "bot_avatar_landlord" to 11, "bot_avatar_farmer" to 11}）。
     * 描边把图标撑大了 1 像素，算窄了 HUD 里这一槽的居中就会偏。
     */
    public static final int BOT_AVATAR_HEIGHT = 10;

    /** 带角色描边的机器人图标高度，比无角色那份多 1 像素（描边）。 */
    public static final int BOT_AVATAR_OUTLINED_HEIGHT = 11;

    public static final int BOT_AVATAR_DOWN_CODEPOINT_START = 0xF910;

    /** 每个向下偏移档要占的机器人图标码位数：无描边、地主描边、农民描边各一个。 */
    public static final int BOT_AVATAR_VARIANTS = 3;

    /**
     * 牌面字形码位起点，落在 Unicode 私有区（U+E000–U+F8FF）。
     *
     * <p>必须与 build.gradle.kts 里的 {@code cardGlyphCodepointStart} 保持一致。
     * 头像那几个用的是 U+F900 起的码位，那其实是「CJK 兼容汉字」区（U+F900=豈），
     * 已经上线就不动了；牌面这 55 个新码位改用真正的私有区，不会顶掉汉字。
     *
     * <p>某张牌的码位 = 起点 + 该牌贴图文件名在【全部牌贴图文件名字母序】里的下标。
     * 构建侧按扫目录得到的 cardIds 顺序算，这边按 {@link #buildCardGlyphIndex()}
     * 自行枚举同一套名字算，两边独立实现；错位不会变豆腐块而是【显示成另一张牌】，
     * 比豆腐块更难发现，所以由 CraftEngineBundleResourcesTest 逐张比对守护。
     */
    /**
     * 三类位图字形各自的字体 ID，必须与 build.gradle.kts 里的
     * {@code cardGlyphFont / avatarPixelFont / botAvatarFont} 逐字一致。
     *
     * <p>为什么不共用 minecraft:default：default 是全服共享的一张码位表，牌面、头像、
     * 机器人头像三家都往里塞，任何一家扩档都可能盖掉另一家的码位 —— 牌面偏移档扩到
     * 15 个之后占了 4125 个码位，直接盖穿了头像的起点 0xE800，CE 启动时报了一千多条
     * 「字符已被另一张图片占用」。拆成三个字体后每家独占一张表，档位随便扩都撞不到别人。
     *
     * <p>拆字体不会让同一行的中文丢字形：字体只作用于被 {@code <font:...>} 包住的那一段，
     * 三处消费点都只把字形字符本身包进去，中文仍走 default。这一点由
     * TrickHudViewTest 用真的 MiniMessage 解析器逐段核对，不是靠推理。
     *
     * <p>名字放在 minecraft 命名空间下（而不是 {@code muz:cards}）是刻意的：CE 自带配置
     * 只出现过 minecraft:default 与 minecraft:gui，没有非 minecraft 命名空间的先例，
     * 用没实证过的写法一旦 CE 不产出对应 font JSON 就是整片豆腐块。
     */
    public static final String CARD_GLYPH_FONT = "minecraft:muz_cards";
    public static final String AVATAR_PIXEL_FONT = "minecraft:muz_avatar";
    public static final String BOT_AVATAR_FONT = "minecraft:muz_bot_avatar";

    public static final int CARD_GLYPH_CODEPOINT_START = 0xE100;

    /**
     * 牌面字形的渲染宽度，单位像素，必须与 build.gradle.kts 裁出的贴图宽度一致。
     *
     * <p>牌贴图是 79x63 的 UV 展开图，正面那半是 35x53；字形按 1:1 渲染（height 等于贴图高），
     * 所以渲染宽度就是 35。
     */
    public static final int CARD_GLYPH_WIDTH = 35;

    /**
     * 牌面字形的前进宽度：画完一张牌后光标往右走多少像素。
     *
     * <p>Minecraft 的位图字形在渲染宽度之外还会加 1 像素的字间距，所以是宽度加一。
     * 出牌 HUD 想让牌叠放就得靠负偏移把这个前进量抵掉一部分，算错就会叠歪，
     * 因此这个值不能在别处写成魔数。
     */
    public static final int CARD_GLYPH_ADVANCE = CARD_GLYPH_WIDTH + 1;

    /**
     * 牌面字形的缩放档：每一档就是一个渲染高度（像素）。
     *
     * <p>为什么只能是【离散档位】而不是任意倍数：缩放靠位图字形的 height 实现，而 height
     * 写在资源包的 images.yml 里，是构建期固化的整数，运行时改不了。所以每一档都得在
     * 构建期预生成一整套 55 个字形，config 只能在这些档里挑一个。
     *
     * <p>索引 0 必须是 53（即 {@link #CARD_GLYPH_WIDTH} 对应的 1:1 原始像素）：默认档的
     * 码位由此落在 {@link #CARD_GLYPH_CODEPOINT_START} 起的第一段，和「没有档位」的旧版
     * 完全一致，老资源包与现有断言都不用跟着挪。
     *
     * <p>全部不超过 53 是有意的：牌贴图本身就是 35x53，放大只会得到插值模糊的牌，
     * 而且满手 20 张放大后会横出屏幕。
     */
    private static final int[] CARD_GLYPH_HEIGHT_TIERS = {53, 48, 42, 37, 32};

    /**
     * 牌面字形的向下偏移档：把牌从 BossBar 那一行往屏幕下方推多少像素。
     *
     * <p>只有向下没有向上：BossBar 固定在屏幕顶部，往上推会直接出屏；而且向下只要把
     * ascent 减小就行，能复用同一张贴图，向上则要求 height 跟着涨（Minecraft 限制
     * ascent 不得大于 height），那等于把牌拉伸，不是纯位移。
     *
     * <p>索引 0 必须是 0，理由同缩放档：默认档码位保持不变。
     */
    private static final int[] CARD_GLYPH_DOWN_OFFSET_TIERS = {
        0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 50, 52
    };

    /**
     * 头像行（与跟着头像走的 bot 兜底图标）自己的向下偏移档，与牌那张表【完全独立】。
     *
     * <p>为什么必须是两张表而不是共用一张：两行 HUD 里头像行永远比牌行深一整个头像字形盒
     * （见 {@link #avatarRowDownOffset}），牌行的取值区间是 0..52、头像行是 40..300，
     * 两个区间几乎不重叠。共用一张表时每一档都要无差别生成三族字形（牌 275 + 头像 70 +
     * bot 3），于是牌永远用不到深档、头像永远用不到浅档，约一半条目是纯废条目。
     * 拆开之后各族只生成自己够用的档，images.yml 反而变小。
     *
     * <p>索引 0 必须是 0，和牌表同理：{@link #avatarPixelChar(int, int)} 与
     * {@link #botAvatarChar(PlayerRole)} 这两个不带档位的重载走的就是档 0，
     * 桌边座位牌和 Title 用的是它们 —— 那些地方不能跟着 HUD 一起往下沉。
     *
     * <p>为什么步长取 10：头像盒高恒等于 {@code 10 * avatar-scale}，scale 每加一档盒高就多 10。
     * 步长取 10 时，同一个头像位置能被多组 (offset-down, avatar-scale) 命中，
     * 覆盖率比等分成别的步长高得多。
     *
     * <p>下限 40 来自「不重叠」：牌行合法区间 0..52、scale 合法区间 4..10（盒高 40..100），
     * 两者相加的最小可用值就是 40，比它更浅的档任何组合都用不到。
     *
     * <p>上限 300 是【纯观感取值，不是技术极限】：字形几何是 {@code ascent = height - offset}，
     * Minecraft 只要求 {@code ascent <= height}，而 ascent 允许为负，所以往下理论上没有硬上限。
     * 这个上限被抬过两次 —— 先是 150（只覆盖到「两行紧贴」），再是 200，服主两次都很快顶到头。
     * 300 能在默认 scale=6 下把头像行压到牌行下方约 190 像素，留了足够余量；
     * 每档的代价是 70 条 images.yml 条目（{@code (10-4+1) * 10}）加 3 条 bot 图标条目。
     *
     * <p>真正的天花板是【码位空间】而不是几何：头像族从 {@code 0xE800} 起、每档 70 个码位，
     * 28 档占到 {@code 0xEFA7}，PUA 到 {@code 0xF8FF} 为止还剩两千多个位置。
     * 三族各有独立字体（{@code muz_avatar} / {@code muz_cards} / {@code muz_bot_avatar}），
     * 所以头像族的码位区间和牌族重叠也无害 —— 各自一套空间。
     *
     * <p>这张表必须与 build.gradle.kts 的 {@code avatarDownOffsetTiers} 逐项一致，
     * 否则头像与 bot 图标的码位会整体平移，玩家看到的是错位或豆腐块。
     */
    private static final int[] AVATAR_DOWN_OFFSET_TIERS = {
        0, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150,
        160, 170, 180, 190, 200, 210, 220, 230, 240, 250, 260, 270, 280, 290, 300
    };

    /** 牌贴图文件名 -> 字形下标。键包含 54 张牌加牌背，共 55 个。 */
    private static final Map<String, Integer> CARD_GLYPH_INDEX = buildCardGlyphIndex();

    /** 缩放档的档数。config 校验和构建期循环都从这里取，不许各写一个字面量。 */
    public static int cardGlyphHeightTierCount() {
        return CARD_GLYPH_HEIGHT_TIERS.length;
    }

    /**
     * 牌行向下偏移档的档数。
     *
     * <p>【这个值是牌面码位公式里的乘数】（见 {@link #cardGlyphChar(DoudizhuCard, int, int)}），
     * 改一档就会让所有缩放档 &gt;=1 的牌面码位整体平移。头像与 bot 不许用它，
     * 它们有 {@link #avatarDownOffsetTierCount()}。
     */
    public static int cardGlyphDownOffsetTierCount() {
        return CARD_GLYPH_DOWN_OFFSET_TIERS.length;
    }

    /** 头像行向下偏移档的档数。头像与 bot 兜底图标的码位公式用它，不许用牌那张表的档数。 */
    public static int avatarDownOffsetTierCount() {
        return AVATAR_DOWN_OFFSET_TIERS.length;
    }

    /** 第 {@code tier} 档的渲染高度，单位像素。 */
    public static int cardGlyphHeightAt(int tier) {
        if (tier < 0 || tier >= CARD_GLYPH_HEIGHT_TIERS.length) {
            throw new IllegalArgumentException("牌面缩放档越界（0.." + (CARD_GLYPH_HEIGHT_TIERS.length - 1) + "）：" + tier);
        }
        return CARD_GLYPH_HEIGHT_TIERS[tier];
    }

    /** 第 {@code tier} 档的向下偏移，单位像素。 */
    public static int cardGlyphDownOffsetAt(int tier) {
        if (tier < 0 || tier >= CARD_GLYPH_DOWN_OFFSET_TIERS.length) {
            throw new IllegalArgumentException(
                "牌面向下偏移档越界（0.." + (CARD_GLYPH_DOWN_OFFSET_TIERS.length - 1) + "）：" + tier);
        }
        return CARD_GLYPH_DOWN_OFFSET_TIERS[tier];
    }

    /** 第 {@code tier} 档【头像行】的向下偏移，单位像素。 */
    public static int avatarDownOffsetAt(int tier) {
        if (tier < 0 || tier >= AVATAR_DOWN_OFFSET_TIERS.length) {
            throw new IllegalArgumentException(
                "头像行向下偏移档越界（0.." + (AVATAR_DOWN_OFFSET_TIERS.length - 1) + "）：" + tier);
        }
        return AVATAR_DOWN_OFFSET_TIERS[tier];
    }

    /**
     * 把 config 里写的像素高度换成档位下标；没有这一档返回 -1。
     *
     * <p>返回 -1 而不是抛异常，是因为调用方（config 解析）要的是「回退并留警告」，
     * 不是让服务器起不来。
     */
    public static int cardGlyphHeightTierOf(int height) {
        return indexOf(CARD_GLYPH_HEIGHT_TIERS, height);
    }

    /** 把 config 里写的向下偏移像素换成档位下标；没有这一档返回 -1。 */
    public static int cardGlyphDownOffsetTierOf(int downOffset) {
        return indexOf(CARD_GLYPH_DOWN_OFFSET_TIERS, downOffset);
    }

    /** 同上，但查【头像行】那张表；config 的 avatar-offset-down 走这里。 */
    public static int avatarDownOffsetTierOf(int downOffset) {
        return indexOf(AVATAR_DOWN_OFFSET_TIERS, downOffset);
    }

    /**
     * 两行 HUD 里头像行需要的向下偏移量 = 牌行偏移 + 头像字形盒高。
     *
     * <p>「两行」不是真的换行，是靠字形 ascent 把整个字形盒沉到基线下方（BossBar 标题只有一行）。
     * 头像行要正好落在牌行下方，就得比牌行再深一整个头像字形盒的高度。
     *
     * <p>【必须用 {@link #AVATAR_OUTLINED_PIXELS}(10) 而不是 {@link #AVATAR_HEAD_PIXELS}(8)】：
     * 字形盒高由构建期的 {@code (avatarOutlinedPixels - row) * scale} 决定，描边那两行永远参与
     * 字形度量。运行期关掉 avatar-outline 只是不画描边像素，盒子照样占 10 行的位置。
     * 按 8 算会少让 2*scale 像素，两行直接压在一起。
     *
     * <p>返回的是【需要的像素量】，不是档位。头像行档位现在由 config 的
     * {@code trick-hud.avatar-offset-down} 直接给（查 {@link #avatarDownOffsetTierOf}），
     * 这个算式的用途变成两件事：给那个键推默认值，以及判断服主配出来的两行会不会重叠。
     */
    public static int avatarRowDownOffset(int cardDownOffset, int avatarScale) {
        return cardDownOffset + AVATAR_OUTLINED_PIXELS * avatarScale;
    }

    /** 档位取值列表，供 config 越界时把「合法值有哪些」直接写进警告里。 */
    public static String cardGlyphHeightTierList() {
        return join(CARD_GLYPH_HEIGHT_TIERS);
    }

    /** 同上，牌行向下偏移档。 */
    public static String cardGlyphDownOffsetTierList() {
        return join(CARD_GLYPH_DOWN_OFFSET_TIERS);
    }

    /** 同上，头像行向下偏移档。 */
    public static String avatarDownOffsetTierList() {
        return join(AVATAR_DOWN_OFFSET_TIERS);
    }

    private static int indexOf(int[] values, int value) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == value) {
                return index;
            }
        }
        return -1;
    }

    private static String join(int[] values) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append('/');
            }
            builder.append(values[index]);
        }
        return builder.toString();
    }

    /**
     * 第 {@code heightTier} 档的牌面渲染宽度，单位像素。
     *
     * <p>位图字形是等比缩放的：只指定 height，宽度由客户端按贴图宽高比自己算。所以缩放档
     * 一变，宽度就跟着变，{@link #CARD_GLYPH_WIDTH} 只是 1:1 那一档的值，不能当通用宽度用，
     * 否则缩放后 HUD 的叠牌间距会整排算错。
     *
     * <p>舍入方式按 Minecraft 位图字形的做法取四舍五入。这一点没法在单元测试里证伪
     * （客户端才是真正的渲染方），所以缩放档下的叠放可能与实际差 1 像素，需要实机确认。
     */
    public static int cardGlyphWidth(int heightTier) {
        int height = cardGlyphHeightAt(heightTier);
        return Math.round((float) CARD_GLYPH_WIDTH * height / CARD_GLYPH_HEIGHT_TIERS[0]);
    }

    /** 第 {@code heightTier} 档画完一张牌后光标往右走多少像素（渲染宽度加 1 像素字间距）。 */
    public static int cardGlyphAdvance(int heightTier) {
        return cardGlyphWidth(heightTier) + 1;
    }

    private PackAssets() {
    }

    /**
     * 机器人头像图标组件，桌边座位牌与出牌 HUD 共用这一个。
     * <p>
     * 图标是位图字形，本质上仍是一个文本字符，会被外层 Component 的颜色染色 ——
     * 不显式指定颜色就会继承父节点（例如机器人名字的 AQUA），整个图标被染成
     * 单色，原图配色全部丢失。这里显式设 WHITE 而不是 reset：WHITE 是明确的
     * 白色染色，位图字形按白色渲染即等于保留贴图原色；reset 只清样式，
     * 某些客户端上仍可能落回父节点颜色。
     * <p>
     * 同时关掉粗体和斜体：座位名字带 BOLD，图标若跟着变粗，客户端会把字形
     * 横向拉伸一像素，图标看起来会糊。
     *
     * @param role          地主取金边、农民取黑边；{@code null}（角色未定，例如叫分阶段）
     *                      取无描边的基础图标
     * @param trailingSpace 是否在图标后补一个空格。拼在名字前面要补（座位牌就是这么用的）；
     *                      单独显示图标时不补，否则右边会多出一段空隙
     */
    public static Component botAvatarIcon(PlayerRole role, boolean trailingSpace) {
        String glyph = botAvatarChar(role);
        return Component.text(trailingSpace ? glyph + " " : glyph)
            .color(NamedTextColor.WHITE)
            .decoration(TextDecoration.BOLD, false)
            .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 机器人头像字形字符。出牌 HUD 在真人皮肤还没下载好、或玩家根本没有自定义皮肤时
     * 也要拿这个字符兜底，所以不能是私有的。
     */
    /**
     * 机器人图标画完之后光标前进多少像素。
     *
     * <p>位图字形的前进量是【字形宽 + 1】：Minecraft 在字形右侧留一像素字间距。
     * 这个值用来在 HUD 的头像槽里居中，漏掉那 1 像素整槽就会左偏半像素。
     *
     * @param role 角色决定用哪份贴图；null 是还没定角色的那份（10 像素，没描边）
     */
    public static int botAvatarAdvanceWidth(PlayerRole role) {
        int height = role == null ? BOT_AVATAR_HEIGHT : BOT_AVATAR_OUTLINED_HEIGHT;
        return height + 1;
    }

    public static String botAvatarChar(PlayerRole role) {
        if (role == PlayerRole.LANDLORD) {
            return BOT_AVATAR_LANDLORD_CHAR;
        }
        if (role == PlayerRole.FARMER) {
            return BOT_AVATAR_FARMER_CHAR;
        }
        return BOT_AVATAR_CHAR;
    }

    /**
     * 机器人头像字形字符，取指定向下偏移档的那一份。
     *
     * <p>档 0 直接返回上面三个原码位，桌边座位牌用的就是它们；
     * 只有出牌 HUD 会用到 &gt;0 的档，让图标跟着牌一起往下沉。
     *
     * <p>【档位走头像那张表，不是牌那张】：这个图标画在 HUD 的【头像行】，是真人皮肤取不到
     * 时的兜底。跟着牌表走会让 bot 玩家的图标和真人头像上下错开一整行。
     *
     * @param downOffsetTier 向下偏移档下标，取值范围同 {@link #avatarDownOffsetAt(int)}
     */
    public static String botAvatarChar(PlayerRole role, int downOffsetTier) {
        // 先校验再分支：越界的档位不能因为「刚好是 0」就悄悄放过去。
        avatarDownOffsetAt(downOffsetTier);
        if (downOffsetTier == 0) {
            return botAvatarChar(role);
        }
        int roleIndex;
        if (role == PlayerRole.LANDLORD) {
            roleIndex = 1;
        } else if (role == PlayerRole.FARMER) {
            roleIndex = 2;
        } else {
            roleIndex = 0;
        }
        int codepoint = BOT_AVATAR_DOWN_CODEPOINT_START
            + (downOffsetTier - 1) * BOT_AVATAR_VARIANTS + roleIndex;
        return new String(Character.toChars(codepoint));
    }

    public static NamespacedKey cardModel(DoudizhuPlugin plugin, DoudizhuCard card) {
        return new NamespacedKey(plugin, "cards/" + cardAssetName(card));
    }

    public static NamespacedKey backModel(DoudizhuPlugin plugin) {
        return new NamespacedKey(plugin, "cards/card_back");
    }

    public static NamespacedKey uiModel(DoudizhuPlugin plugin, String id) {
        return new NamespacedKey(plugin, "ui/" + id);
    }

    public static NamespacedKey configuredUiModel(DoudizhuPlugin plugin, String configured, String fallbackId) {
        if (configured != null && configured.contains(":")) {
            NamespacedKey parsed = NamespacedKey.fromString(configured);
            if (parsed != null) {
                return parsed;
            }
        }
        return uiModel(plugin, fallbackId);
    }

    public static NamespacedKey furnitureModel(DoudizhuPlugin plugin, String id) {
        return new NamespacedKey(plugin, "furniture/" + id);
    }

    public static NamespacedKey roleModel(DoudizhuPlugin plugin, PlayerRole role) {
        return uiModel(plugin, role == PlayerRole.LANDLORD ? "landlord" : "farmer");
    }

    public static String cardAssetName(DoudizhuCard card) {
        if (card.rank() == CardRank.SMALL_JOKER) {
            return "small_joker";
        }
        if (card.rank() == CardRank.BIG_JOKER) {
            return "big_joker";
        }
        return suitName(card.suit()) + "_" + rankName(card.rank());
    }

    /**
     * 这张牌在默认档（1:1、不下移）的牌面字形字符。
     *
     * <p>返回的是【裸字符】，调用方必须把它包进 {@code <font:} {@link #CARD_GLYPH_FONT}
     * {@code >...</font>} 才能显示 —— 字形注册在那张自定义字体上，不套标签会是豆腐块。
     * 这里不直接返回带标签的片段，是因为一行里有多张牌，包一次比每张各包一次省得多。
     */
    public static String cardGlyphChar(DoudizhuCard card) {
        return cardGlyphChar(card, 0, 0);
    }

    /**
     * 这张牌在指定缩放档与向下偏移档的牌面字形字符。
     *
     * <p>码位排布：{@code 起点 + 档序号 * 55 + 牌下标}，档序号 =
     * {@code 缩放档 * 偏移档数 + 偏移档}。两个档都取 0 时档序号为 0，码位退化成
     * 「起点 + 牌下标」，和没有档位的旧版一模一样。
     *
     * <p>这个公式在 build.gradle.kts 里被独立实现了一遍（两边各算一次是既有约定）。
     * 错位的后果不是豆腐块而是【把 3 显示成 4】这种静默串牌，所以由
     * CraftEngineBundleResourcesTest 逐档逐张比对守护。
     */
    public static String cardGlyphChar(DoudizhuCard card, int heightTier, int downOffsetTier) {
        String assetName = cardAssetName(card);
        Integer index = CARD_GLYPH_INDEX.get(assetName);
        if (index == null) {
            // 走到这里说明 cardAssetName 产出了字形表里没有的名字，
            // 继续跑只会显示成豆腐块，不如当场报出来。
            throw new IllegalStateException("牌面字形码位表里没有这张牌：" + assetName);
        }
        // 这两个调用同时承担越界校验，别改成直接读数组。
        cardGlyphHeightAt(heightTier);
        cardGlyphDownOffsetAt(downOffsetTier);
        int tier = heightTier * cardGlyphDownOffsetTierCount() + downOffsetTier;
        return new String(Character.toChars(
            CARD_GLYPH_CODEPOINT_START + tier * CARD_GLYPH_INDEX.size() + index));
    }

    /**
     * 牌面字形在 images.yml 里的条目名。
     *
     * <p>每一档都带 {@code _h<高度>_d<下移>} 后缀，默认档也不例外 —— 宁可让名字长一点，
     * 也不要为「默认档不带后缀」多一条分支，那正是最容易让构建侧和插件侧算歪的地方。
     */
    public static String cardGlyphAssetName(String cardAssetName, int heightTier, int downOffsetTier) {
        return "card_" + cardAssetName
            + "_h" + cardGlyphHeightAt(heightTier)
            + "_d" + cardGlyphDownOffsetAt(downOffsetTier);
    }

    /**
     * 头像像素方块字形的码位起点，落在私有区。
     *
     * <p>头像挂在 {@link #AVATAR_PIXEL_FONT} 这张独立码位表上，所以这个起点只需要
     * 在头像自己的字形之间自洽，与牌面占多少档、起点在哪都无关。以前两家挤
     * minecraft:default 时，牌面一扩档就会盖穿这个起点（那正是 CE 报一千多条
     * 「字符已被占用」的原因），拆字体之后这类连锁调整不会再发生。
     */
    public static final int AVATAR_PIXEL_CODEPOINT_START = 0xE800;

    /** 头像放大倍数的可选范围，资源包只预生成了这个区间内的方块字形。 */
    public static final int AVATAR_PIXEL_MIN_SCALE = 4;
    public static final int AVATAR_PIXEL_MAX_SCALE = 10;

    /** 皮肤头部是 8x8 像素，头像就是 8 行 x 8 列个方块。 */
    public static final int AVATAR_HEAD_PIXELS = 8;

    /**
     * 加描边后的头像行数：8x8 向外扩一圈变 10x10。
     *
     * <p>字形按行预生成（每行贴图高度不同），所以描边多出来的两行也必须有自己的字形，
     * 这个值就是生成与校验的上界。
     */
    public static final int AVATAR_OUTLINED_PIXELS = AVATAR_HEAD_PIXELS + 2;

    /**
     * 取头像第 {@code row} 行用的方块字形字符。
     *
     * <p>为什么每行是不同的字符：Minecraft 限制 height &gt;= ascent，单靠 ascent
     * 抬不到基线上方几十像素。资源包里第 row 行的贴图高 (8-row)*scale、方块画在
     * 顶部、下方是透明 padding，取 height = ascent = 贴图高，方块才落在基线上方
     * [(7-row)*scale, (8-row)*scale]。所以「第几行」是烧进字形里的，不能共用一个字符。
     *
     * <p>贴图是纯白的，调用方要自己套颜色标签 —— Minecraft 对字形是乘算着色，
     * 白底乘上皮肤像素色就得到该像素本身的颜色。
     *
     * @param scale 放大倍数，必须在 {@link #AVATAR_PIXEL_MIN_SCALE} 到
     *              {@link #AVATAR_PIXEL_MAX_SCALE} 之间（资源包只生成了这些）
     * @param row   行号，0 是头像最上面那行
     */
    public static String avatarPixelChar(int scale, int row) {
        return avatarPixelChar(scale, row, 0);
    }

    /**
     * 取头像第 {@code row} 行、指定向下偏移档的方块字形字符。
     *
     * <p>偏移档查【头像自己那张表】（{@link #avatarDownOffsetAt}），与牌表相互独立：
     * 头像行永远比牌行深一整个头像盒，两者的取值区间几乎不重叠，共用一张表会让
     * 每一档都白生成另一族用不到的字形。
     *
     * <p>偏移不需要新贴图：同一档 scale 的贴图照用，只把 ascent 减掉偏移量。
     */
    public static String avatarPixelChar(int scale, int row, int downOffsetTier) {
        if (scale < AVATAR_PIXEL_MIN_SCALE || scale > AVATAR_PIXEL_MAX_SCALE) {
            // 资源包里没有这个倍数的贴图，硬拼出来只会显示豆腐块，当场报出来。
            throw new IllegalArgumentException(
                "头像倍数超出资源包预生成范围（" + AVATAR_PIXEL_MIN_SCALE + ".."
                    + AVATAR_PIXEL_MAX_SCALE + "）：" + scale);
        }
        if (row < 0 || row >= AVATAR_OUTLINED_PIXELS) {
            throw new IllegalArgumentException("头像行号越界（0.." + (AVATAR_OUTLINED_PIXELS - 1) + "）：" + row);
        }
        // 同时承担偏移档的越界校验，查的是头像自己那张表。
        avatarDownOffsetAt(downOffsetTier);
        int perTier = (AVATAR_PIXEL_MAX_SCALE - AVATAR_PIXEL_MIN_SCALE + 1) * AVATAR_OUTLINED_PIXELS;
        int index = (scale - AVATAR_PIXEL_MIN_SCALE) * AVATAR_OUTLINED_PIXELS + row;
        return new String(Character.toChars(
            AVATAR_PIXEL_CODEPOINT_START + downOffsetTier * perTier + index));
    }

    /** 头像方块字形在 images.yml 里的条目名，构建侧与插件侧必须算出同一个。 */
    public static String avatarPixelAssetName(int scale, int row, int downOffsetTier) {
        return "avatar_px_" + scale + "_" + row + "_d" + avatarDownOffsetAt(downOffsetTier);
    }

    /**
     * 按【贴图文件名字母序】给 55 张牌面贴图编下标，与构建脚本扫目录后 sorted() 的
     * 顺序对齐。这里不去读资源包文件，纯靠枚举复算，保持插件侧零 IO。
     */
    private static Map<String, Integer> buildCardGlyphIndex() {
        List<String> assetNames = new ArrayList<>();
        // 牌背也占一个字形（贴图目录里有 card_back.png，构建侧一视同仁地编了号），
        // 漏掉它后面所有牌的下标都会前移一位。
        assetNames.add("card_back");
        for (CardSuit suit : CardSuit.values()) {
            if (suit == CardSuit.JOKER) {
                continue;
            }
            for (CardRank rank : CardRank.values()) {
                if (rank == CardRank.SMALL_JOKER || rank == CardRank.BIG_JOKER) {
                    continue;
                }
                assetNames.add(suitName(suit) + "_" + rankName(rank));
            }
        }
        assetNames.add("small_joker");
        assetNames.add("big_joker");
        // Kotlin 的 List<String>.sorted() 和这里都是 String.compareTo（UTF-16 序），
        // 两侧排序规则必须是同一个，不能换成带 Locale 的比较器。
        assetNames.sort(null);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < assetNames.size(); i++) {
            index.put(assetNames.get(i), i);
        }
        return Map.copyOf(index);
    }

    private static String suitName(CardSuit suit) {
        return switch (suit) {
            case CLUBS -> "clubs";
            case DIAMONDS -> "diamonds";
            case HEARTS -> "hearts";
            case SPADES -> "spades";
            case JOKER -> "joker";
        };
    }

    private static String rankName(CardRank rank) {
        return switch (rank) {
            case THREE -> "3";
            case FOUR -> "4";
            case FIVE -> "5";
            case SIX -> "6";
            case SEVEN -> "7";
            case EIGHT -> "8";
            case NINE -> "9";
            case TEN -> "10";
            case JACK -> "jack";
            case QUEEN -> "queen";
            case KING -> "king";
            case ACE -> "ace";
            case TWO -> "2";
            case SMALL_JOKER -> "small_joker";
            case BIG_JOKER -> "big_joker";
        };
    }
}

