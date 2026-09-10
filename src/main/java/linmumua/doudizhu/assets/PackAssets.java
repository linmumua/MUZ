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
    public static final String AVATAR_CROWN_FONT = "minecraft:muz_avatar_crown";

    /**
     * 记牌器分层字形族的基础字体名。
     *
     * <p>每档固定 22 个码位：15 个点数标签、0..4 五个数字、普通框、耗尽框。
     * 码位从 {@link #COUNTER_GLYPH_CODEPOINT_START}（{@code 0xE900}）起，按
     * {@code tier * 22 + layerIndex} 连续分配；构建期与运行期使用同一算式。
     */
    public static final String COUNTER_GLYPH_FONT = "minecraft:muz_counter";

    /** 记牌器分层字形族的码位起点，必须与 build.gradle.kts 的 counterGlyphCodepointStart 一致。 */
    public static final int COUNTER_GLYPH_CODEPOINT_START = 0xE900;

    /** 记牌器每档的标签层数量；顺序必须保持 CardRank 枚举序。 */
    public static final int COUNTER_LABEL_COUNT = PackTiers.COUNTER_RANK_GLYPHS;

    /** 记牌器数字层只生成累计已出张数 0..4。 */
    public static final int COUNTER_DIGIT_COUNT = PackTiers.COUNTER_DIGIT_GLYPHS;

    /** 标签层视觉尺寸（像素），必须与构建期 PNG 和 images.yml 同源。 */
    public static final int COUNTER_LABEL_WIDTH = PackTiers.COUNTER_LABEL_WIDTH;
    public static final int COUNTER_LABEL_HEIGHT = PackTiers.COUNTER_LABEL_HEIGHT;

    /** 数字层视觉尺寸（像素），必须与构建期 PNG 和 images.yml 同源。 */
    public static final int COUNTER_DIGIT_WIDTH = PackTiers.COUNTER_DIGIT_WIDTH;
    public static final int COUNTER_DIGIT_HEIGHT = PackTiers.COUNTER_DIGIT_GLYPH_HEIGHT;

    /** 普通框/耗尽框视觉尺寸（像素），必须与构建期 PNG 和 images.yml 同源。 */
    public static final int COUNTER_FRAME_WIDTH = PackTiers.COUNTER_FRAME_WIDTH;
    public static final int COUNTER_FRAME_HEIGHT = PackTiers.COUNTER_FRAME_HEIGHT;

    /** 分层记牌器 ascent 基准：label=16、frame=-4、digit=-7，均再减头像下移档。 */
    public static final int COUNTER_LABEL_ASCENT = PackTiers.COUNTER_LABEL_ASCENT;
    public static final int COUNTER_FRAME_ASCENT = PackTiers.COUNTER_FRAME_ASCENT;
    public static final int COUNTER_DIGIT_ASCENT = PackTiers.COUNTER_DIGIT_ASCENT;

    /** 记牌器三层字形统一的水平前进量（像素）。 */
    public static final int COUNTER_GLYPH_ADVANCE = PackTiers.COUNTER_GLYPH_ADVANCE;

    /** 记牌器每档字形数量：标签 15 + 普通/耗尽框 2 + 数字 5。 */
    public static final int COUNTER_GLYPHS_PER_TIER = PackTiers.COUNTER_GLYPHS_PER_TIER;

    /** 记牌器框层数量：普通框与耗尽框，必须与构建期生成表同源。 */
    public static final int COUNTER_FRAME_COUNT = PackTiers.COUNTER_FRAME_GLYPHS;

    /** 数字层在每档中的下标为 15..19，与 build.gradle.kts 的码位表一致。 */
    public static final int COUNTER_DIGIT_START_INDEX = COUNTER_LABEL_COUNT;

    /** 框层下标为 20..21；码位表顺序不限制 View 按 label → frame → digit 绘制。 */
    public static final int COUNTER_FRAME_START_INDEX = COUNTER_LABEL_COUNT + COUNTER_DIGIT_COUNT;

    /** 固定 cell 几何供 View 与 Debug Web 共用，全部由生成的层尺寸及 ascent 推导。 */
    public static final int COUNTER_CELL_WIDTH = COUNTER_LABEL_WIDTH;
    public static final int COUNTER_CELL_ADVANCE = COUNTER_GLYPH_ADVANCE;
    public static final int COUNTER_FRAME_TOP_DELTA = COUNTER_LABEL_ASCENT - COUNTER_FRAME_ASCENT;
    public static final int COUNTER_DIGIT_INSET = COUNTER_FRAME_ASCENT - COUNTER_DIGIT_ASCENT;
    public static final int COUNTER_CELL_HEIGHT = COUNTER_FRAME_TOP_DELTA + COUNTER_FRAME_HEIGHT;

    /**
     * 底部物品栏 HUD 字形族的字体名与码位。
     *
     * <p>单张静态字形（无偏移档），用于在 ActionBar 中渲染 9 个物品槽背景，
     * 通过负 ascent 把贴图压到屏幕底部物品栏区域显示。
     *
     * <p>必须与 build.gradle.kts 里的 {@code hotbarHudFont} 和 {@code hotbarHudCodepoint}
     * 保持一致。选 {@code 0xEF00} 是因为 PUA 区 0xE000-0xF8FF 里前段被牌面、头像、
     * 记牌器占据，后段预留给 CraftEngine 内置配置，{@code 0xEF00} 处于空隙且不与
     * 任何已知字形冲突。
     */
    public static final String HOTBAR_HUD_FONT = "minecraft:muz_hotbar";

    /** 底部物品栏字形码位（对应贴图文件 {@code muz:font/hotbar_slots.png}）。 */
    public static final int HOTBAR_HUD_CODEPOINT = 0xEF00;

    /** PLAYING 阶段显示的九个彩色调试槽；必须与构建期绘图常量一致。 */
    public static final int HOTBAR_HUD_SLOT_COUNT = 9;

    /**
     * 底部物品栏遮罩字形的贴图宽度，固定为原版 9 槽 hotbar 的 182px。
     *
     * <p>九个槽块各为 18×20px，位于 x=2,22,...,162；底色填满 182×22 遮罩。
     * 该遮罩只由 PLAYING 阶段 ActionBar 推送，不修改 minecraft 原版 hotbar sprite。
     */
    public static final int HOTBAR_HUD_GLYPH_WIDTH = 182;

    /** 底部物品栏遮罩字形的原生高度；CraftEngine {@code height} 必须恒等于此值以保持 1:1。 */
    public static final int HOTBAR_HUD_GLYPH_HEIGHT = 22;

    /**
     * 底部物品栏字形的光标前进量（183px）。
     *
     * <p>Minecraft 位图字形在 182px 渲染宽度之外额外加 1 像素字间距（与牌面字形同理），
     * 所以前进量 = 贴图宽 + 1。{@link HotbarHudService} 用 CraftEngine 负空格把这个
     * 前进量抵消，使文本有效宽度 = 叠加消息宽度，客户端按叠加消息居中。
     */
    public static final int HOTBAR_HUD_GLYPH_ADVANCE = HOTBAR_HUD_GLYPH_WIDTH + 1;

    /**
     * 调试覆盖层的底部物品栏字形码位。
     *
     * <p>与 {@link #HOTBAR_HUD_CODEPOINT}（0xEF00）【同字体、同贴图】，唯一差别是
     * ascent 的来源：
     * <ul>
     *   <li>0xEF00 的 ascent 由 build.gradle.kts 烘焙成字面量 -128，进了 jar 就固定；</li>
     *   <li>0xEF01 的 ascent 由 {@code HotbarDebugOverlayWriter} 在运行期写出，
     *       等于 {@code -128 - hotbar-hud.offset-y}，所以可以连续调整。</li>
     * </ul>
     *
     * <p>【为什么两个码位都要保留】：生产环境不能依赖运行期写出的文件（本类的契约是
     * 「插件侧靠枚举复算，不读资源包」），所以必须有一个纯 bundle 的兜底码位；
     * 而连续可拖的 ascent 又只能来自运行期生成的 YAML。两者职责不重叠，
     * 共用同一张 {@code muz:font/hotbar_slots.png} 与同一个字体族，已是最小形态。
     *
     * <p>【必须与 {@code HotbarDebugOverlayWriter} 生成侧严格对齐】：那边写
     * {@code char: \uef01}、{@code font: minecraft:muz_hotbar}、{@code height: 22}，
     * 与这里的码位、{@link #HOTBAR_HUD_FONT}、贴图原生高一一对应。
     * 任意一侧改了另一侧不改，游戏内就是豆腐块或位置突变。
     */
    public static final int HOTBAR_HUD_DEBUG_CODEPOINT = 0xEF01;

    /**
     * 取底部物品栏字形的 MiniMessage 片段（已包含字体标签）。
     *
     * <p>返回 {@code <font:minecraft:muz_hotbar>\uef00</font>}，可直接拼入
     * MiniMessage 字符串中，由 {@link HotbarHudService} 负责后续合成。
     */
    public static String hotbarHudGlyphText() {
        return "<font:" + HOTBAR_HUD_FONT + ">"
            + new String(Character.toChars(HOTBAR_HUD_CODEPOINT))
            + "</font>";
    }

    /**
     * 取调试覆盖层字形的 MiniMessage 片段（已包含字体标签）。
     *
     * <p>返回 {@code <font:minecraft:muz_hotbar>\uef01</font>}。只有在 Debug Web
     * 生成过覆盖层资源、且 CraftEngine 重新打包下发之后，客户端才认得这个码位；
     * 覆盖层缺失时客户端会显示豆腐块，所以调用方必须先确认覆盖层就绪
     * （{@link HotbarHudService} 用 Debug Web 的接管状态做这个判断）。
     */
    public static String hotbarHudDebugGlyphText() {
        return "<font:" + HOTBAR_HUD_FONT + ">"
            + new String(Character.toChars(HOTBAR_HUD_DEBUG_CODEPOINT))
            + "</font>";
    }

    /**
     * 王冠族的码位起点。
     *
     * <p>用 {@code 0xE000}（PUA 最起头）不怕和牌族的 {@code 0xE100} 撞：各族有独立字体，
     * 码位空间互不相干。起点越低单张字体能装的档越多 —— 王冠一档只占 30 个码位，
     * 从 0xE000 起一张字体就能装下全部 201 档，不用切分。
     */
    public static final int AVATAR_CROWN_CODEPOINT_START = 0xE000;

    /**
     * 一张字体能装几档。
     *
     * <p>【容量按各族自己的起点算】：每张切出来的字体都从该族的 {@code codepointStart}
     * 重新起算，能装的档数是 {@code (MAX_GLYPH_CODEPOINT - codepointStart + 1) / 每档码位数}。
     * 牌族从 0xE100 起是 144 档/张，头像族从 0xE800 起是 40 档/张。
     * 若按 PUA 起点 0xE000 的容量算，装满一张就会冲出 BMP，4 位 unicode 转义会错位。
     *
     * <p>【切分单位是「档」不是「条」】：同一档内的 55 张牌必须落在同一张字体里，
     * 否则一行 HUD 里的牌会分散在两张字体上，得套两层 {@code <font>} 标签才画得完。
     */
    private static int tiersPerFont(int glyphsPerTier, int codepointStart) {
        return (PackTiers.MAX_GLYPH_CODEPOINT - codepointStart + 1) / glyphsPerTier;
    }

    /** 该档在其所属字体内的起始码位。与构建期的 {@code tierFontSlot} 是同一个算式。 */
    private static int tierCodepointBase(int tier, int glyphsPerTier, int codepointStart) {
        int tierInFont = tier % tiersPerFont(glyphsPerTier, codepointStart);
        return codepointStart + tierInFont * glyphsPerTier;
    }

    /**
     * 该档落在第几张字体上。字体名规则：第 0 张沿用原名，之后带 {@code _2} / {@code _3} 后缀。
     *
     * <p>与构建期的 {@code fontNameOf} 必须逐字一致，否则 {@code <font>} 标签指向一张
     * 不存在的字体，整段变豆腐块。
     */
    private static String fontNameOf(String baseFont, int tier, int glyphsPerTier, int codepointStart) {
        int fontIndex = tier / tiersPerFont(glyphsPerTier, codepointStart);
        return fontIndex == 0 ? baseFont : baseFont + "_" + (fontIndex + 1);
    }

    /**
     * 牌面字形在这一档该用哪张字体。
     *
     * <p>档位放开后牌族有 1025 档、56375 条，一张字体装不下（144 档/张），切成 8 张。
     * 调用方【必须用这个方法取字体名】，不能再写死 {@link #CARD_GLYPH_FONT} ——
     * 那只是第 0 张的名字，深档的牌在别的字体上。
     */
    public static String cardGlyphFont(int heightTier, int downOffsetTier) {
        int tier = heightTier * cardGlyphDownOffsetTierCount() + downOffsetTier;
        return fontNameOf(CARD_GLYPH_FONT, tier, CARD_GLYPH_INDEX.size(), CARD_GLYPH_CODEPOINT_START);
    }

    /** 头像字形在这一档该用哪张字体。头像族 201 档、40 档/张，切成 6 张。 */
    public static String avatarPixelFont(int downOffsetTier) {
        return fontNameOf(AVATAR_PIXEL_FONT, downOffsetTier, avatarGlyphsPerTier(), AVATAR_PIXEL_CODEPOINT_START);
    }

    /** 王冠字形的字体。一档只占 30 个码位，201 档单张字体装得下，恒定返回基名。 */
    public static String avatarCrownFont(int downOffsetTier) {
        return fontNameOf(AVATAR_CROWN_FONT, downOffsetTier, crownGlyphsPerTier(), AVATAR_CROWN_CODEPOINT_START);
    }

    /**
     * 记牌行字形使用的字体。
     *
     * <p>记牌器按 {@code 0xE900 + tier * 22 + layerIndex} 直接分配码位，当前资源档位
     * 全部位于同一张 {@code muz_counter} 字体中；字体名必须与构建期 YAML 同源。
     */
    public static String counterGlyphFont(int downOffsetTier) {
        avatarDownOffsetAt(downOffsetTier);
        return COUNTER_GLYPH_FONT;
    }

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
     * <p>【表的顺序不承载语义】：默认牌面高是显式常量 {@link #DEFAULT_CARD_HEIGHT}，
     * 不再取「索引 0」。早先索引 0 兼任默认值，档位改成按范围生成后索引 0 会变成区间端点
     * （降序时是 56），默认牌面高会静默改掉 —— 所以把默认值从表里解耦了。
     *
     * <p>表由构建期按 {@code 32..56} 生成（降序），见 {@link PackTiers#CARD_HEIGHT_TIERS}。
     * 上限给到 56 而不是停在贴图原生的 53：放大确有插值模糊，但服主要不要放大是他的事，
     * 不该由这里替他决定。
     */
    private static final int[] CARD_GLYPH_HEIGHT_TIERS = PackTiers.CARD_HEIGHT_TIERS;

    /**
     * 牌面高的默认值，{@code config.yml} 读不到 {@code card-height} 时用它。
     *
     * <p>53 是牌贴图正面的原生像素高（35x53），1:1 不插值。这个值【必须落在
     * {@link #CARD_GLYPH_HEIGHT_TIERS} 里】，否则默认配置一启动就要被吸附成别的高度。
     * 当前构建参数（32..56 步长 1）覆盖了它；若把 {@code muzCardHeightStep} 调成偶数步长，
     * 53 就会落到网格外 —— 那时这个常量也要跟着改。
     */
    public static final int DEFAULT_CARD_HEIGHT = 53;

    /**
     * 牌面字形的向下偏移档：把牌从 BossBar 那一行往屏幕下方推多少像素。
     *
     * <p>只有向下没有向上：BossBar 固定在屏幕顶部，往上推会直接出屏；而且向下只要把
     * ascent 减小就行，能复用同一张贴图，向上则要求 height 跟着涨（Minecraft 限制
     * ascent 不得大于 height），那等于把牌拉伸，不是纯位移。
     *
     * <p>索引 0 必须是 0：不带档位的那些重载走的就是档 0（桌边座位牌、Title），
     * 它们不能跟着 HUD 往下沉。构建期按 {@code 0..80} 步长 2 生成，首项天然是 0。
     */
    private static final int[] CARD_GLYPH_DOWN_OFFSET_TIERS = PackTiers.CARD_DOWN_OFFSET_TIERS;

    /**
     * 头像行（与跟着头像走的 bot 兜底图标）自己的向下偏移档，与牌那张表【完全独立】。
     *
     * <p>为什么必须是两张表而不是共用一张：两行 HUD 里头像行永远比牌行深一整个头像字形盒
     * （见 {@link #avatarRowDownOffset}），牌行区间是 0..80、头像行要到 400，
     * 两个区间差得远。共用一张表时每一档都要无差别生成四族字形，牌永远用不到深档，
     * 约一半条目是纯废条目。拆开之后各族只生成自己够用的档。
     *
     * <p>索引 0 必须是 0，和牌表同理：{@link #avatarPixelChar(int, int)} 与
     * {@link #botAvatarChar(PlayerRole)} 这两个不带档位的重载走的就是档 0，
     * 桌边座位牌和 Title 用的是它们 —— 那些地方不能跟着 HUD 一起往下沉。
     *
     * <p>构建期按 {@code 0..400} 步长 2 生成（见 {@link PackTiers#AVATAR_DOWN_OFFSET_TIERS}）。
     * 上限 400 覆盖最坏组合：牌行最深 80 加最大头像盒高 192（{@code 12 * 16}）是 272，留了余量。
     * 步长 2 而不是 1 是条目数的折中 —— 吸附误差最多 1 像素，肉眼看不出；步长 1 会让
     * 头像族条目数翻倍。想要精确到 1 像素就用 {@code -PmuzAvatarOffsetStep=1} 重新构建。
     *
     * <p>浅档里有一部分【任何组合都必然与牌行重叠】：不重叠下限是
     * {@code offset-down + 12 * avatar-scale}，scale 最小是 2，所以 {@code 0..22}
     * 那 12 档配上去一定会报重叠警告。仍然生成它们是因为「不该由这里替服主决定」，
     * 代价只有 2196 条（占 2.4%）。
     */
    private static final int[] AVATAR_DOWN_OFFSET_TIERS = PackTiers.AVATAR_DOWN_OFFSET_TIERS;

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
     * <p>【必须用 {@link #AVATAR_ROW_TOTAL_PIXELS}(12) 而不是 8 或 10】：
     * 字形盒高由构建期的 {@code (行数 - row) * scale} 决定，描边那两行永远参与字形度量
     * （运行期关掉 avatar-outline 只是不画描边像素，盒子照样占位），而王冠还要再往上
     * 凸出 2 行。按 10 算会漏掉王冠那 {@code 2 * scale} 像素 —— 地主的王冠会压进牌行；
     * 按 8 算连描边都漏，两行直接压在一起。
     *
     * <p>返回的是【需要的像素量】，不是档位。头像行档位现在由 config 的
     * {@code trick-hud.avatar-offset-down} 直接给（查 {@link #avatarDownOffsetTierOf}），
     * 这个算式的用途变成两件事：给那个键推默认值，以及判断服主配出来的两行会不会重叠。
     */
    public static int avatarRowDownOffset(int cardDownOffset, int avatarScale) {
        return cardDownOffset + AVATAR_ROW_TOTAL_PIXELS * avatarScale;
    }

    /**
     * 把任意整数吸附到最近的档位，返回那一档的【档序号】。
     *
     * <p>为什么需要吸附而不是拒绝：偏移量做不到运行期任意取值（每个值都得有预生成字形），
     * 但服主没有义务背下几百个合法值。放开范围后档位很密（步长 2），就近吸附的误差最多
     * 1 像素，肉眼看不出来 —— 与其为 111 报一条「合法值是 0,2,4,...」的天书警告，
     * 不如静默用 110 或 112。
     *
     * <p>越界的处理【不同于范围内】：范围内静默吸附，越界要警告 —— 服主配 500 想要的
     * 显然不是 400，钳到边界必须让他知道。越界判定由调用方做（比较 {@code value} 与
     * 表的首末项），这个方法只负责找最近的。
     *
     * <p>【并列时取档位值较小的那一档】，例如步长 2 下的 111 取 110。这里显式比较档位值
     * 而不是靠遍历顺序：{@link #CARD_GLYPH_HEIGHT_TIERS} 是降序、两张偏移表是升序，
     * 靠「先遇到的赢」会让同一条规则在降序表上变成「取较大」。步长为 1 时并列不可能发生，
     * 但 {@code muzCardHeightStep} 是对外可调的构建参数，步长变 2 后 55 就会并列。
     */
    private static int nearestTier(int[] tiers, int value) {
        int best = 0;
        int bestDistance = Math.abs(tiers[0] - value);
        for (int index = 1; index < tiers.length; index++) {
            int distance = Math.abs(tiers[index] - value);
            if (distance < bestDistance || (distance == bestDistance && tiers[index] < tiers[best])) {
                best = index;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 把 {@code card-height} 吸附到最近的牌面高档，返回档序号。 */
    public static int nearestCardGlyphHeightTier(int height) {
        return nearestTier(CARD_GLYPH_HEIGHT_TIERS, height);
    }

    /** 把 {@code offset-down} 吸附到最近的牌行偏移档，返回档序号。 */
    public static int nearestCardGlyphDownOffsetTier(int downOffset) {
        return nearestTier(CARD_GLYPH_DOWN_OFFSET_TIERS, downOffset);
    }

    /** 把 {@code avatar-offset-down} 吸附到最近的头像行偏移档，返回档序号。 */
    public static int nearestAvatarDownOffsetTier(int downOffset) {
        return nearestTier(AVATAR_DOWN_OFFSET_TIERS, downOffset);
    }

    /** 牌面高的合法区间，警告文案用。 */
    public static int cardGlyphHeightMin() {
        return min(CARD_GLYPH_HEIGHT_TIERS);
    }

    public static int cardGlyphHeightMax() {
        return max(CARD_GLYPH_HEIGHT_TIERS);
    }

    /** 牌行偏移的合法区间。 */
    public static int cardGlyphDownOffsetMin() {
        return min(CARD_GLYPH_DOWN_OFFSET_TIERS);
    }

    public static int cardGlyphDownOffsetMax() {
        return max(CARD_GLYPH_DOWN_OFFSET_TIERS);
    }

    /** 头像行偏移的合法区间。 */
    public static int avatarDownOffsetMin() {
        return min(AVATAR_DOWN_OFFSET_TIERS);
    }

    public static int avatarDownOffsetMax() {
        return max(AVATAR_DOWN_OFFSET_TIERS);
    }

    private static int min(int[] values) {
        int result = values[0];
        for (int value : values) {
            result = Math.min(result, value);
        }
        return result;
    }

    private static int max(int[] values) {
        int result = values[0];
        for (int value : values) {
            result = Math.max(result, value);
        }
        return result;
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
            tierCodepointBase(tier, CARD_GLYPH_INDEX.size(), CARD_GLYPH_CODEPOINT_START) + index));
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

    /**
     * 头像放大倍数的可选范围，资源包只预生成了这个区间内的方块字形。
     *
     * <p>与偏移档不同，scale 是【连续整数】不需要吸附：2..16 每个值都有字形。
     * 所以校验它只是范围检查（越界钳到边界），不存在「就近吸附」。
     */
    public static final int AVATAR_PIXEL_MIN_SCALE = PackTiers.AVATAR_MIN_SCALE;
    public static final int AVATAR_PIXEL_MAX_SCALE = PackTiers.AVATAR_MAX_SCALE;

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
     * 王冠占几行，画在头像盒【上方】。
     *
     * <p>王冠不再盖住头像顶部两行，而是向上凸出：底边锚点与头像完全相同，主体落在
     * 头像 row 0 之上。这样地主的脸不会被王冠遮掉，而且王冠与描边不再互斥
     * （先戴冠再描边，描边会连王冠一起勾出轮廓）。
     */
    public static final int AVATAR_CROWN_PIXELS = 2;

    /**
     * 头像行整体占多高（含凸出的王冠），单位是「像素格」。
     *
     * <p>【不重叠判定必须用这个，不能用 {@link #AVATAR_OUTLINED_PIXELS}】：王冠比头像盒
     * 顶还高 {@code 2 * scale}，按 10 算会漏报，地主的王冠会压进牌行且不报警。
     * {@link #AVATAR_OUTLINED_PIXELS} 的语义不变，仍是「描边后头像本身多少行」，
     * 生成字形与校验行号还用它。
     */
    public static final int AVATAR_ROW_TOTAL_PIXELS = AVATAR_OUTLINED_PIXELS + AVATAR_CROWN_PIXELS;

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
        int index = (scale - AVATAR_PIXEL_MIN_SCALE) * AVATAR_OUTLINED_PIXELS + row;
        return new String(Character.toChars(
            tierCodepointBase(downOffsetTier, avatarGlyphsPerTier(), AVATAR_PIXEL_CODEPOINT_START) + index));
    }

    /** 头像方块字形在 images.yml 里的条目名，构建侧与插件侧必须算出同一个。 */
    public static String avatarPixelAssetName(int scale, int row, int downOffsetTier) {
        return "avatar_px_" + scale + "_" + row + "_d" + avatarDownOffsetAt(downOffsetTier);
    }

    /** 头像族一档占几个码位：每个 scale 一整列描边行。 */
    private static int avatarGlyphsPerTier() {
        return (AVATAR_PIXEL_MAX_SCALE - AVATAR_PIXEL_MIN_SCALE + 1) * AVATAR_OUTLINED_PIXELS;
    }

    /** 王冠族一档占几个码位。 */
    private static int crownGlyphsPerTier() {
        return (AVATAR_PIXEL_MAX_SCALE - AVATAR_PIXEL_MIN_SCALE + 1) * AVATAR_CROWN_PIXELS;
    }

    /**
     * 取王冠第 {@code row} 行的字形字符（row 0 是最上面那行）。
     *
     * <p>王冠是独立字形家族，底边锚点与头像相同但主体落在头像盒【上方】：
     * 资源包里王冠第 row 行的贴图高是 {@code (12 - row) * scale}，取
     * {@code ascent = height - offset} 后白块落在锚点上方 {@code (11-row)*scale ..
     * (12-row)*scale}，正好接在头像 row 0（{@code 9*scale .. 10*scale}）之上。
     *
     * <p>贴图纯白，颜色由调用方套 {@code <color>} 给 —— 金色王冠和黑色描边共用这些字形。
     */
    public static String avatarCrownChar(int scale, int row, int downOffsetTier) {
        if (scale < AVATAR_PIXEL_MIN_SCALE || scale > AVATAR_PIXEL_MAX_SCALE) {
            throw new IllegalArgumentException(
                "王冠倍数超出资源包预生成范围（" + AVATAR_PIXEL_MIN_SCALE + ".."
                    + AVATAR_PIXEL_MAX_SCALE + "）：" + scale);
        }
        if (row < 0 || row >= AVATAR_CROWN_PIXELS) {
            throw new IllegalArgumentException("王冠行号越界（0.." + (AVATAR_CROWN_PIXELS - 1) + "）：" + row);
        }
        avatarDownOffsetAt(downOffsetTier);
        int index = (scale - AVATAR_PIXEL_MIN_SCALE) * AVATAR_CROWN_PIXELS + row;
        return new String(Character.toChars(
            tierCodepointBase(downOffsetTier, crownGlyphsPerTier(), AVATAR_CROWN_CODEPOINT_START) + index));
    }

    /** 王冠字形在 images.yml 里的条目名。 */
    public static String avatarCrownAssetName(int scale, int row, int downOffsetTier) {
        return "avatar_crown_" + scale + "_" + row + "_d" + avatarDownOffsetAt(downOffsetTier);
    }

    /** 记牌器标签层字形字符（默认头像下移档）。 */
    public static String counterRankChar(CardRank rank) {
        return counterRankChar(rank, 0);
    }

    /**
     * 记牌器标签层字形字符。
     *
     * <p>每档标签层只占 15 个码位，顺序严格沿用 {@link CardRank#ordinal()}；耗尽状态
     * 不再复制一套暗色标签，而是由调用方叠加 {@link #counterFrameChar(boolean, int)}。
     */
    public static String counterRankChar(CardRank rank, int downOffsetTier) {
        if (rank == null) {
            throw new IllegalArgumentException("记牌器点数不能为空");
        }
        avatarDownOffsetAt(downOffsetTier);
        return counterGlyphChar(rank.ordinal(), downOffsetTier);
    }

    /** 记牌器数字层字形字符（仅支持累计已出张数 0..4）。 */
    public static String counterDigitChar(int digit) {
        return counterDigitChar(digit, 0);
    }

    /** 记牌器数字层字形字符（指定头像下移档）。 */
    public static String counterDigitChar(int digit, int downOffsetTier) {
        if (digit < 0 || digit >= COUNTER_DIGIT_COUNT) {
            throw new IllegalArgumentException("记牌器数字下标越界（0.." + (COUNTER_DIGIT_COUNT - 1) + "）：" + digit);
        }
        avatarDownOffsetAt(downOffsetTier);
        return counterGlyphChar(COUNTER_DIGIT_START_INDEX + digit, downOffsetTier);
    }

    /** 记牌器框层字形字符；exhausted=true 时使用耗尽框。 */
    public static String counterFrameChar(boolean exhausted) {
        return counterFrameChar(exhausted, 0);
    }

    /** 记牌器框层字形字符（指定头像下移档）。 */
    public static String counterFrameChar(boolean exhausted, int downOffsetTier) {
        avatarDownOffsetAt(downOffsetTier);
        return counterGlyphChar(COUNTER_FRAME_START_INDEX + (exhausted ? 1 : 0), downOffsetTier);
    }

    /** 分层记牌器字形在 images.yml 中的条目名。 */
    public static String counterRankAssetName(CardRank rank, int downOffsetTier) {
        return "counter_label_" + counterRankSlug(rank) + "_d" + avatarDownOffsetAt(downOffsetTier);
    }

    /** 分层记牌器数字字形在 images.yml 中的条目名。 */
    public static String counterDigitAssetName(int digit, int downOffsetTier) {
        if (digit < 0 || digit >= COUNTER_DIGIT_COUNT) {
            throw new IllegalArgumentException("记牌器数字下标越界（0.." + (COUNTER_DIGIT_COUNT - 1) + "）：" + digit);
        }
        return "counter_digit_" + digit + "_d" + avatarDownOffsetAt(downOffsetTier);
    }

    /** 分层记牌器框字形在 images.yml 中的条目名。 */
    public static String counterFrameAssetName(boolean exhausted, int downOffsetTier) {
        return "counter_frame_" + (exhausted ? "exhausted" : "normal")
            + "_d" + avatarDownOffsetAt(downOffsetTier);
    }

    private static String counterGlyphChar(int index, int downOffsetTier) {
        return new String(Character.toChars(
            tierCodepointBase(downOffsetTier, COUNTER_GLYPHS_PER_TIER, COUNTER_GLYPH_CODEPOINT_START) + index));
    }

    private static String counterRankSlug(CardRank rank) {
        return switch (rank) {
            case THREE -> "3";
            case FOUR -> "4";
            case FIVE -> "5";
            case SIX -> "6";
            case SEVEN -> "7";
            case EIGHT -> "8";
            case NINE -> "9";
            case TEN -> "10";
            case JACK -> "j";
            case QUEEN -> "q";
            case KING -> "k";
            case ACE -> "a";
            case TWO -> "2";
            case SMALL_JOKER -> "small";
            case BIG_JOKER -> "big";
        };
    }

    /**
     * 兼容旧调用方式：分层资源不再生成亮/暗两套组合图，dim 仅保留参数兼容并由框层表达耗尽状态。
     */
    @Deprecated
    public static String counterRankChar(CardRank rank, boolean dim) {
        return counterRankChar(rank, 0);
    }

    /** 兼容旧调用方式；数字层仅有 0..4，dim 参数不再参与码位。 */
    @Deprecated
    public static String counterDigitChar(int digit, boolean dim) {
        return counterDigitChar(digit, 0);
    }

    /** 兼容旧调用方式；数字层仅有 0..4，dim 参数不再参与码位。 */
    @Deprecated
    public static String counterDigitChar(int digit, boolean dim, int downOffsetTier) {
        return counterDigitChar(digit, downOffsetTier);
    }

    /** 兼容旧调用方式；dim 参数不再参与码位。 */
    @Deprecated
    public static String counterRankChar(CardRank rank, boolean dim, int downOffsetTier) {
        return counterRankChar(rank, downOffsetTier);
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

