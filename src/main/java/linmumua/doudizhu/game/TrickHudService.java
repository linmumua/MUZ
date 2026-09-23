package linmumua.doudizhu.game;

import java.net.URL;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.HudOverlayLayout;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.assets.PlayerHeadRenderer;
import linmumua.doudizhu.compat.CraftEngineOffsetService;
import linmumua.doudizhu.config.MuzYamlConfig;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.ui.MuzTheme;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

/**
 * 把出牌 HUD 挂到 BossBar 上，并管理每个观看者自己的那一条。
 *
 * <p>为什么用 BossBar：这个 HUD 要求头像和牌【并排在同一行】，而 ActionBar 已经被
 * 常驻状态行占用，Title 只有正中两槽、位置不可控。BossBar 是唯一还空着、
 * 又能承载任意长文本的槽位。
 *
 * <p>代价要说清楚：BossBar 的血条轨道本身没法藏（原版协议不支持），所以屏幕顶部会
 * 多出一条空轨道；这里把进度设成 0 让它尽量不显眼。当初一并顾虑的
 * 「位移量随 GUI 缩放漂移」在这个用法下不成立：
 * 我们只做头像与牌的【相对】排布，GUI 缩放会整体放大，相对位置不变。
 */
final class TrickHudService {
    /**
     * BossBar 的颜色。这个值和资源包里的贴图文件名是硬绑定的，改它要先改资源包。
     *
     * <p>客户端按颜色索引取血条贴图（{@code boss_bar/<颜色>_background.png}），
     * 我们只把 {@code white_background.png} 和 {@code white_progress.png} 换成了全透明，
     * 所以只有 WHITE 这一档的轨道是隐形的。换成别的颜色，屏幕顶部会立刻多出一条空血条槽。
     * 按颜色隔离也是有意的：原版末影龙和凋灵用的是 PINK/PURPLE，不受我们影响。
     */
    static final BossBar.Color BAR_COLOR = BossBar.Color.WHITE;

    /**
     * 必须是 PROGRESS，不能用 NOTCHED_*。
     *
     * <p>分段刻度贴图（{@code notched_6_background.png} 等）是按样式索引、所有颜色共用的，
     * 不走上面那套按颜色隔离的逻辑。用了 NOTCHED 就会在透明轨道上画出刻度，
     * 而想擦掉它就得覆盖全服共用的贴图，会连带影响别的插件和原版血条。
     */
    static final BossBar.Overlay BAR_OVERLAY = BossBar.Overlay.PROGRESS;

    /**
     * 中间那个大头像的放大倍数。
     *
     * <p>6 倍的【字形盒】是 60 像素高，不是 48 —— 字形按 {@code AVATAR_OUTLINED_PIXELS}(=10)
     * 行预生成（{@code writeAvatarPixelGlyph} 恒按 10 行走，与运行期 avatar-outline
     * 开关无关，关掉描边只是不画最外那圈像素、不改字形度量）。48 是关掉描边后
     * 【可见的 8x8 脸】的高度，两行布局的垂直间距必须按 60 算，按 48 算会让头像顶边
     * 压进牌里 12 像素。
     */
    private static final int DEFAULT_AVATAR_SCALE = defaultAvatarScale();

    /** 左右两个小头像的放大倍数。优先使用构建期约定的 4 倍，缺档时退到实际生成集合的最小档。 */
    private static final int SIDE_AVATAR_SCALE = sideAvatarScale();

    private static int defaultAvatarScale() {
        return PackAssets.avatarPixelScaleTierOf(6) >= 0
            ? 6
            : PackAssets.avatarPixelScaleAt(0);
    }

    private static int sideAvatarScale() {
        return PackAssets.avatarPixelScaleTierOf(4) >= 0
            ? 4
            : PackAssets.avatarPixelScaleAt(0);
    }

    /** 相邻两个头像槽的间距。6 是沿用原先「头像到牌」的实测调优值，观感上三头像不粘连。 */
    private static final int DEFAULT_AVATAR_GAP = 6;

    /**
     * 整条 HUD 相对 BossBar 默认位置往下推的像素数。
     *
     * <p>50 是测试服实测调优值，把 HUD 从紧贴屏幕顶部推到不挡准星的位置。
     * 落在预生成档位上（0..80 步长 2），所以默认配置不会被吸附。
     *
     * <p>这一项只管牌行；头像行由 {@link #DEFAULT_AVATAR_OFFSET_DOWN} 单独管。
     */
    private static final int DEFAULT_OFFSET_DOWN = 50;

    /**
     * 头像行默认的向下偏移，单位像素。
     *
     * <p>不写字面量 110 而是从默认组合算出来：这个值的含义是「牌行默认偏移 + 默认倍数的
     * 头像盒高」，也就是两行【精确相接、零重叠】的那个点。谁改了 {@link #DEFAULT_OFFSET_DOWN}
     * 或 {@link #DEFAULT_AVATAR_SCALE}，默认值会自己跟着走，不会退化成一个和牌行错开的数。
     *
     * <p>算出来是 {@code 50 + 12 * 6 = 122}，落在预生成档位上（0..400 步长 2），
     * 所以默认配置既不会被吸附也不会触发重叠警告 —— 有测试守这一点。
     */
    private static final int DEFAULT_AVATAR_OFFSET_DOWN =
        PackAssets.avatarRowDownOffset(DEFAULT_OFFSET_DOWN, DEFAULT_AVATAR_SCALE);

    /**
     * 相邻两张牌左缘的间距。牌宽 35，这里取 22 是让牌像手牌那样叠放：
     * 一手最多能有 20 张（比如四个三带的飞机），全展开要 700 像素以上会超出屏幕。
     */
    private static final int DEFAULT_CARD_STEP = 22;

    /**
     * 记牌器相邻两格的默认间距。
     *
     * <p>15 个点数（13 个普通 + 双王）一字排开，间距每加 1 像素整行就宽 14 像素，
     * 所以这里取得很小：2 像素已经够把相邻两格分开，再大就有超出屏宽的风险。
     */
    private static final int DEFAULT_COUNTER_GAP = 2;

    /** counter 资源的默认缩放百分比；运行期几何由当前生成 profile 的 CounterTier 提供。 */
    private static final int DEFAULT_COUNTER_SCALE = 100;

    /** counter 独立 Y 的源码默认值；旧配置缺键时先继承 avatar-offset-down。 */
    private static final int DEFAULT_COUNTER_OFFSET_DOWN = 122;

    /**
     * 从 config 读出来的那几个可调量。
     *
     * @param enabled        总开关
     * @param avatarScale    中间那个大头像的放大倍数，同时决定用哪一档 ascent 字形
     * @param avatarGap      相邻两个头像槽的间距（改成两行布局后不再是「头像到牌」的间距）
     * @param cardStep       相邻两张牌左缘的间距
     * @param heightTier     牌面缩放【档序号】（不是像素高）；config 写的是像素高，这里已换算过
     * @param downOffsetTier 牌行的向下偏移【档序号】，查的是牌那张档位表
     * @param avatarDownOffsetTier 头像行的向下偏移【档序号】，查的是头像那张【独立】档位表。
     *                       两行位置可以各自随便调，代价是配歪了会重叠，只靠警告拦（见
     *                       {@link #warnIfRowsOverlap}）
     * @param offsetX        整体水平偏移像素，正右负左；三行一起动
     * @param rowXOffsets    三行【各自】的水平偏移，叠加在 {@link #offsetX} 之上。
     *                       独立出来是因为三行宽度天生不等，一个整体 x 做不到「牌行靠左、
     *                       记牌行再往左一点」这类排布；而横向靠负空格实现，任意整数都合法、
     *                       不需要预生成字形，拆开的代价只是多三个配置键
     * @param counterEnabled 记牌器行（第三行）的开关。【与 {@link #enabled} 分开】：
     *                       有人只想要「谁出了什么」而嫌记牌器占地方或觉得降低难度，
     *                       关它不该连整条 HUD 一起关掉
     * @param counterScale   记牌器资源缩放百分比，只允许当前 profile/PackTiers 已生成的档位
     * @param counterDownOffsetTier 记牌器独立的向下偏移档，不再复用头像行位置
     * @param counterGap     记牌器相邻两格的间距。各格自身宽度由对应 scale 的资源几何决定，
     *                       不随累计已出数量变化；固定宽度才能让 15 格位置始终稳定
     * @param counterHideExhausted 某个点数出完（剩 0 张）时是否隐藏那一格。
     *                       【隐藏的只是内容，不是位置】：那一格照样占住它的宽度，
     *                       否则后面所有格子会左移、玩家靠位置扫读的习惯就废了
     */
    record Settings(
        boolean enabled,
        int avatarScale,
        int avatarGap,
        int cardStep,
        int heightTier,
        int downOffsetTier,
        int avatarDownOffsetTier,
        int offsetX,
        TrickHudView.RowXOffsets rowXOffsets,
        boolean counterEnabled,
        int counterScale,
        int counterDownOffsetTier,
        int counterGap,
        boolean counterHideExhausted,
        int cardOffsetDown,
        int avatarOffsetDown,
        int counterOffsetDown
    ) {
        /** 原始像素值必须保留；tier 只用于旧 bundle 兼容路径。 */
    }

    /**
     * 解析 config 里的 trick-hud 段。纯函数：不碰 Bukkit，警告往哪去由调用方决定，
     * 这样测试可以直接把警告收进列表来断言「越界值确实被拒了」。
     *
     * <p>越界值一律回退而不是照用：avatarScale 超出资源包预生成范围会让头像整片
     * 变成豆腐块，cardStep 非正会让牌倒着排或全叠成一张，两种都是纯粹的配置笔误。
     * 头像与牌行重叠属于布局诊断，只在 {@code debug.enabled=true} 时提示，不改变任何
     * 偏移值，也不影响正式 HUD 或资源/非法配置错误的告警。
     */
    static Settings readSettings(MuzYamlConfig config, Consumer<String> warn) {
        boolean enabled = config.getBoolean("trick-hud.enabled", true);
        boolean debugEnabled = config.getBoolean("debug.enabled", false);

        int avatarScale = config.getInt("trick-hud.avatar-scale", DEFAULT_AVATAR_SCALE);
        if (PackAssets.avatarPixelScaleTierOf(avatarScale) < 0) {
            // 必须留日志：否则玩家只会看到头像莫名变方块，没人能联想到是这一行配置写错了。
            warn.accept("trick-hud.avatar-scale=" + avatarScale + " 不是资源包已生成档位（"
                + java.util.Arrays.toString(PackAssets.AVATAR_PIXEL_SCALE_TIERS)
                + "），已回退为 " + DEFAULT_AVATAR_SCALE);
            avatarScale = DEFAULT_AVATAR_SCALE;
        }

        int cardStep = config.getInt("trick-hud.card-step", DEFAULT_CARD_STEP);
        if (cardStep <= 0) {
            warn.accept("trick-hud.card-step=" + cardStep + " 必须为正数，已回退为 " + DEFAULT_CARD_STEP);
            cardStep = DEFAULT_CARD_STEP;
        }

        // avatarGap 不校验：负值是有意义的用法（让第一张牌压在头像上做紧凑排版）。
        int avatarGap = config.getInt("trick-hud.avatar-gap", DEFAULT_AVATAR_GAP);

        // 牌高仍是构建期离散资源，height/ascent 固化在资源包 images.yml 里，运行时只能
        // 选择已生成档位；三项 Y 则在下面按连续 raw 范围读取，旧 tier 只给 legacy fallback。
        int cardHeight = config.getInt("trick-hud.card-height", PackAssets.DEFAULT_CARD_HEIGHT);
        int heightTier = snapTier(
            cardHeight, PackAssets.cardGlyphHeightMin(), PackAssets.cardGlyphHeightMax(),
            PackAssets.nearestCardGlyphHeightTier(cardHeight),
            "trick-hud.card-height", warn, PackAssets::cardGlyphHeightAt);

        int offsetDown = config.getInt("trick-hud.offset-down", DEFAULT_OFFSET_DOWN);
        int downOffsetTier = rawOffsetTier(
            offsetDown, PackAssets.nearestCardGlyphDownOffsetTier(offsetDown),
            "trick-hud.offset-down", warn, PackAssets::cardGlyphDownOffsetAt);

        // 头像行的偏移【独立于牌行】，查的是头像自己那张档位表。两行能各自随便调是刻意的，
        // 代价是配歪了两行会重叠 —— 仅在 debug.enabled=true 时由下面的诊断提示，不在这里拦。
        int avatarOffsetDown = config.getInt("trick-hud.avatar-offset-down", DEFAULT_AVATAR_OFFSET_DOWN);
        int avatarDownOffsetTier = rawOffsetTier(
            avatarOffsetDown, PackAssets.nearestAvatarDownOffsetTier(avatarOffsetDown),
            "trick-hud.avatar-offset-down", warn, PackAssets::avatarDownOffsetAt);
        if (debugEnabled) {
            warnIfRowsOverlap(offsetDown, avatarOffsetDown, avatarScale, warn);
        }

        // offset-x 不校验：任意整数都合法（正右负左），靠负空格实现，不依赖预生成字形。
        int offsetX = config.getInt("trick-hud.offset-x", 0);

        // 三行各自的 x，同样不校验。它们是【叠加在 offset-x 之上的增量】：
        // offset-x 管整条 HUD 往哪偏，这三个管某一行相对其他行往哪偏。
        // 全为 0（默认）时行为与只有 offset-x 时完全一致。
        TrickHudView.RowXOffsets rowXOffsets = new TrickHudView.RowXOffsets(
            config.getInt("trick-hud.card-offset-x", 0),
            config.getInt("trick-hud.avatar-offset-x", 0),
            config.getInt("trick-hud.counter.offset-x", 0));

        // 记牌器行【独立开关】：关掉只少画第三行，牌行与头像行照旧。
        boolean counterEnabled = config.getBoolean("trick-hud.counter.enabled", true);

        int counterScale = config.getInt("trick-hud.counter.scale", DEFAULT_COUNTER_SCALE);
        if (PackAssets.counterScaleTierOf(counterScale) < 0) {
            warn.accept("trick-hud.counter.scale=" + counterScale
                + " 不是当前资源包已生成的缩放档，已回退为 " + DEFAULT_COUNTER_SCALE);
            counterScale = DEFAULT_COUNTER_SCALE;
        }

        // counter Y 与头像行完全独立。旧配置缺键时先继承 avatar-offset-down，
        // 再由默认值 122 收底；ensureConfigIntegrity 会把迁移后的结果写回磁盘，
        // 这里保留同一 fallback 供纯函数测试和未经过启动迁移的旧配置使用。
        String counterOffsetKey = "trick-hud.counter.offset-down";
        int counterOffsetDown = config.contains(counterOffsetKey)
            ? config.getInt(counterOffsetKey, DEFAULT_COUNTER_OFFSET_DOWN)
            : config.getInt("trick-hud.avatar-offset-down", DEFAULT_COUNTER_OFFSET_DOWN);
        int counterDownOffsetTier = rawOffsetTier(
            counterOffsetDown, PackAssets.nearestCounterDownOffsetTier(counterOffsetDown),
            counterOffsetKey, warn, PackAssets::counterDownOffsetAt);

        // 和 avatarGap 不同，这里【必须拦负值】：头像槽的负间距是有意义的紧凑排版，
        // 而记牌器 15 格一字排开，负间距会让点数图标和邻格的数字直接叠在一起糊成一团，
        // 没有任何一种看法能读出剩几张。这属于纯粹的配置笔误，回退而不是照用。
        int counterGap = config.getInt("trick-hud.counter.gap", DEFAULT_COUNTER_GAP);
        if (counterGap < 0) {
            warn.accept("trick-hud.counter.gap=" + counterGap
                + " 不能为负（会让相邻两格压字），已回退为 " + DEFAULT_COUNTER_GAP);
            counterGap = DEFAULT_COUNTER_GAP;
        }

        boolean counterHideExhausted = config.getBoolean("trick-hud.counter.hide-exhausted", false);

        return new Settings(
            enabled, avatarScale, avatarGap, cardStep, heightTier, downOffsetTier, avatarDownOffsetTier, offsetX,
            rowXOffsets, counterEnabled, counterScale, counterDownOffsetTier, counterGap, counterHideExhausted,
            offsetDown, avatarOffsetDown, counterOffsetDown);
    }

    /**
     * 把配置值吸附到最近的预生成档，越界时额外留一条警告。
     *
     * <p>【范围内静默、越界才警告】是刻意的分工：档位步长只有 1~2 像素，范围内吸附的误差
     * 服主根本看不出来，为此刷一条警告只会让他以为配错了。而越界是真的没被满足 ——
     * 配 500 却只能给 400，不说他会一直以为是配置没生效。
     *
     * @param value    config 里写的原始值
     * @param min      该档位表的最小值
     * @param max      最大值
     * @param tier     已经算好的最近档序号
     * @param key      配置键名，用于警告文案
     * @param resolve  档序号 → 实际值，用于把「吸附到了多少」写进警告
     * @return 最终采用的档序号（与传入的 {@code tier} 相同，这里只负责警告）
     */
    private static int rawOffsetTier(
        int value, int tier, String key, Consumer<String> warn,
        java.util.function.IntUnaryOperator resolve) {
        int resolved = resolve.applyAsInt(tier);
        if (value < PackAssets.MIN_TRICK_OFFSET || value > PackAssets.MAX_TRICK_OFFSET) {
            warn.accept(key + "=" + value + " 超出连续覆盖层范围（"
                + PackAssets.MIN_TRICK_OFFSET + ".." + PackAssets.MAX_TRICK_OFFSET
                + "），旧 bundle 兼容档为 " + resolved + "；HUD 将隐藏");
        }
        return tier;
    }

    private static int snapTier(
        int value, int min, int max, int tier, String key,
        Consumer<String> warn, java.util.function.IntUnaryOperator resolve) {
        int resolved = resolve.applyAsInt(tier);
        if (value < min || value > max) {
            warn.accept(key + "=" + value + " 超出资源包预生成范围（" + min + ".." + max
                + "），旧 bundle 兼容档为 " + resolved + "；连续覆盖层未就绪时将隐藏 HUD");
        }
        return tier;
    }

    /**
     * 两行配歪了会重叠，重叠就留警告。
     *
     * <p>【这是「两行位置完全自由」方案的已知代价】：牌行与头像行各有独立档位表、各自随便调，
     * 结构上不再保证不重叠；开启 {@code debug.enabled} 后才通过这条警告提示。正式 HUD 仍按
     * 用户配置渲染，不自动改成建议值，也不因这条诊断关闭。
     *
     * <p>几何依据：位图字形占基线上方 {@code [ascent - height, ascent]}，两族都取
     * {@code ascent = height - d}，于是字形盒是「基线下方 d 到基线上方 height - d」。
     * 头像行顶边在基线下方 {@code d_头像 - 12 * scale}，不重叠要求它不高于牌底（基线下方
     * {@code d_牌}），即 {@code d_头像 - 12 * scale >= d_牌}。
     *
     * <p>盒高必须按 {@link PackAssets#AVATAR_ROW_TOTAL_PIXELS}(12) 算，不是 8 也不是 10：
     * 描边那两行永远参与字形度量（运行期关 avatar-outline 只是不画），而王冠还要再往上凸出
     * 2 行。按 10 算会漏报王冠那 {@code 2 * scale} 像素 —— 地主的王冠压进牌行却不报警。
     *
     * <p>纯函数，警告去向由调用方决定（同 {@link #readSettings}），测试可以直接断言
     * 「重叠组合确实留了警告」。
     *
     * @param cardDownOffsetTier   牌行的向下偏移档序号（牌那张表）
     * @param avatarDownOffsetTier 头像行的向下偏移档序号（头像那张表）
     * @param avatarScale          大头像倍数，决定头像盒高
     */
    static void warnIfRowsOverlap(
        int cardDown, int avatarDown, int avatarScale, Consumer<String> warn) {
        int boxHeight = PackAssets.AVATAR_ROW_TOTAL_PIXELS * avatarScale;
        int required = PackAssets.avatarRowDownOffset(cardDown, avatarScale);
        if (avatarDown >= required) {
            return;
        }
        // 【不枚举合法值】：连续 raw Y 不需要知道旧档网格，给一个可直接抄的建议值就够；
        // 旧 tier 只用于未就绪时的 legacy fallback，不参与 raw 改写。
        warn.accept("trick-hud.avatar-offset-down=" + avatarDown + " 比牌行低太少，头像会压进牌里 "
            + (required - avatarDown) + " 像素（牌行 offset-down=" + cardDown + " + 头像行整体高 "
            + boxHeight + " = 至少要 " + required + "，盒高按 12*avatar-scale 算：描边 10 行加"
            + "王冠凸出 2 行，与 avatar-outline 开关无关）；建议把 avatar-offset-down 设为 "
            + required + " 或更大");
    }

    private final DoudizhuPlugin plugin;
    private final CraftEngineOffsetService offsetService;
    private final PlayerHeadRenderer headRenderer;
    /** 玩家输出门面；BossBar/ActionBar 的客户端调用统一由 player lane 执行。 */
    private final PlayerOutputDispatcher output;

    /**
     * 当前生效的配置快照。
     *
     * <p>【必须整份替换，不许逐字段改】：{@code settings}、{@code avatarRowDownTier}、
     * {@code avatarSlotWidth} 三者是互相推导出来的（槽宽依赖 avatar-scale 与描边开关，
     * 头像行档位来自 settings），逐个赋值会出现「新 scale 配旧槽宽」的中间态，
     * 渲染线程刚好读到就会画出错位的一帧。装进 record 整份换是原子的。
     *
     * <p>{@code volatile}：{@link #reloadSettings()} 在主线程写，Folia 下渲染可能在
     * 区域线程读，没有 volatile 不保证可见性。
     */
    private volatile Snapshot snapshot;

    /**
     * 一份自洽的配置快照。
     *
     * @param settings          config 里 trick-hud 段的解析结果
     * @param avatarRowDownTier 头像行的向下偏移档，直接来自 avatar-offset-down（不由牌行推导）
     * @param avatarSlotWidth   三个头像槽的统一宽度：取最宽的那个（大头像），
     *                          三槽等宽是「中间必然居中」的前提
     */
    private record Snapshot(Settings settings, int avatarRowDownTier, int avatarSlotWidth) {
    }

    private final Map<UUID, BossBar> bars = new HashMap<>();

    /** 上一次发给该观看者的那一行，内容没变就不重新解析 MiniMessage，也不重发。 */
    private final Map<UUID, String> lastLines = new HashMap<>();

    /**
     * 由 player lane 采集、供 table/region owner 消费的不可变玩家输入。
     * URL 只作为皮肤下载 key 传给头像渲染器，owner lane 不再触碰 PlayerProfile 或背包。
     */
    private record PlayerInputSnapshot(boolean online, URL skinUrl, boolean hasCounterItem) {
        private static final PlayerInputSnapshot UNKNOWN = new PlayerInputSnapshot(true, null, false);
        private static final PlayerInputSnapshot OFFLINE = new PlayerInputSnapshot(false, null, false);
    }

    private final Map<UUID, PlayerInputSnapshot> playerInputs = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> pendingPlayerInputs = new ConcurrentHashMap<>();

    /** 已经收到过「偏移不可用」提示的人，避免每秒刷屏。 */
    private final java.util.Set<UUID> offsetWarnedViewers = new java.util.HashSet<>();

    TrickHudService(
        DoudizhuPlugin plugin,
        CraftEngineOffsetService offsetService,
        PlayerHeadRenderer headRenderer
    ) {
        this(
            plugin,
            offsetService,
            headRenderer,
            outputDispatcherOf(plugin)
        );
    }

    /**
     * 测试与受控装配入口：表内状态仍由调用方所在的 table/region owner 驱动，只有玩家输出
     * 借由注入的门面切到 player lane。
     */
    TrickHudService(
        DoudizhuPlugin plugin,
        CraftEngineOffsetService offsetService,
        PlayerHeadRenderer headRenderer,
        PlayerOutputDispatcher output
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.offsetService = Objects.requireNonNull(offsetService, "offsetService");
        this.headRenderer = Objects.requireNonNull(headRenderer, "headRenderer");
        this.output = Objects.requireNonNull(output, "output");
        this.snapshot = buildSnapshot();
    }

    private static PlayerOutputDispatcher outputDispatcherOf(DoudizhuPlugin plugin) {
        ActionBarOverlayService actionBar = plugin.getActionBarOverlayService();
        return actionBar == null
            ? new PlayerOutputDispatcher(plugin)
            : actionBar.outputDispatcher();
    }

    /**
     * 重新读 config 并整份换掉快照，让 {@code /muz reload} 能改动 trick-hud 的尺寸与偏移。
     *
     * <p>【为什么需要这个方法】：这些值原本在构造期固化成 final，而 {@link TrickHudService}
     * 由 {@link GameTable} 在自己的构造期 new 出来、同样存成 final，两层固化叠加的结果是
     * reload 完全碰不到 HUD —— 服主改完 avatar-offset-down 执行 reload 看不到任何变化，
     * 只能重启，而且没有任何提示说明为什么。
     *
     * <p>不清 {@code bars}：BossBar 本身与配置无关，换了快照后下一次 render 会用新尺寸重画。
     * 但必须清 {@code lastLines}，否则内容比对会认为「这一行没变」而跳过重发，
     * 新尺寸要等到玩家下一次出牌才生效。
     */
    void reloadSettings() {
        this.snapshot = buildSnapshot();
        lastLines.clear();
        // 清掉提示去重：reload 常常就是为了修偏移不可用这个问题，
        // 不清的话修好之前那批人再也收不到提示，修没修好也看不出来。
        offsetWarnedViewers.clear();
    }

    private Snapshot buildSnapshot() {
        Settings loaded = readSettings(plugin.yamlConfig(), message -> plugin.getLogger().warning(message));
        return new Snapshot(
            loaded,
            loaded.avatarDownOffsetTier(),
            PlayerHeadRenderer.advanceWidth(loaded.avatarScale(), isOutlined())
        );
    }

    /**
     * 在 UUID 对应的 player lane 采集一次不可变输入；owner lane 只读取完成后的快照。
     *
     * <p>不能在这里用 {@code Bukkit.getPlayer}：这条路径由牌桌 owner 调用，玩家实体、profile
     * 和背包都必须在 player lane 内读取。玩家离线时 runPlayer 返回 null，显式写入离线快照，
     * 避免把上一次在线状态继续当成当前输入。
     */
    private void requestPlayerInput(UUID playerId) {
        if (playerId == null || pendingPlayerInputs.putIfAbsent(playerId, Boolean.TRUE) != null) {
            return;
        }
        try {
            if (output.runPlayer(playerId, player -> {
                PlayerInputSnapshot input = new PlayerInputSnapshot(
                    player.isOnline(),
                    PlayerHeadRenderer.skinUrlOf(player),
                    plugin.hasCounterItem(player));
                playerInputs.put(playerId, input);
                pendingPlayerInputs.remove(playerId);
            }) == null) {
                playerInputs.put(playerId, PlayerInputSnapshot.OFFLINE);
                pendingPlayerInputs.remove(playerId);
            }
        } catch (RuntimeException exception) {
            pendingPlayerInputs.remove(playerId);
            throw exception;
        }
    }

    private PlayerInputSnapshot playerInputOf(UUID playerId) {
        return playerInputs.getOrDefault(playerId, PlayerInputSnapshot.UNKNOWN);
    }

    private void requestSeatInput(Seat seat) {
        if (seat != null && !seat.isBot()) {
            requestPlayerInput(seat.playerId());
        }
    }

    /**
     * 提前把这一桌三个人的皮肤请求出去，避免 HUD 第一帧闪一下兜底图标。
     *
     * <p>【要解决的现象】：皮肤是异步下载的，{@link PlayerHeadRenderer#miniMessageFor} 第一次
     * 调用必然返回 null（见那边的注释），于是 {@link #avatarSlotOf} 会走位图图标兜底。
     * 那张图标是构建期固定 10/11 像素的，不随 avatar-scale 缩放，所以它闪出来时比真人头像
     * 小一圈、风格也不一致 —— 表现就是「出牌那一瞬间有个小图标跳一下」。
     *
     * <p>【为什么必须用与渲染完全相同的参数】：缓存 key 是 {@code scale|tier|outline|url}，
     * 预热时若用了别的 scale 或档位，渲染那一刻照样 miss，等于白热一趟。所以这里读的是
     * 同一份快照，三个槽的 scale 也和 {@link #render} 里一一对应（两侧小、中间大）。
     *
     * <p>返回值忽略是有意的：这一趟就是要它 miss 一次好触发下载，拿到 null 才是正常路径。
     *
     * @param seats 这一桌的三个座位，顺序无关紧要 —— 每个位置都可能轮到当中间那个大头像，
     *              所以每人都要按大小两种 scale 各热一次
     */
    void prewarmAvatars(List<Seat> seats) {
        Snapshot current0 = snapshot;
        if (!current0.settings().enabled() || !offsetService.isAvailable()) {
            return;
        }
        List<UUID> tableBotIds = botIdsOf(seats.toArray(new Seat[0]));
        int tier = current0.avatarRowDownTier();
        boolean continuousFont = !legacyBundleExact(current0.settings());
        int bigScale = current0.settings().avatarScale();
        for (Seat seat : seats) {
            if (seat == null || seat.playerId() == null) {
                continue;
            }
            // 每人热两种 scale：这一桌轮一圈后，每个人都会当过一次中间的大头像。
            // 只热当前那个 scale 的话，轮到他坐中间时照样闪一下。
            for (int scale : new int[] {SIDE_AVATAR_SCALE, bigScale}) {
                // 【只热不戴冠那版】：这里是发牌时机，地主还没叫出来。
                // 地主定下来后由 prewarmLandlordCrown 单独补他那一版。
                if (seat.isBot()) {
                    headRenderer.miniMessageForBot(
                        tableBotIds, seat.playerId(), scale, tier, false, continuousFont);
                } else {
                    requestPlayerInput(seat.playerId());
                    PlayerInputSnapshot input = playerInputOf(seat.playerId());
                    if (input.online()) {
                        headRenderer.miniMessageFor(
                            input.skinUrl(), scale, tier, false, continuousFont);
                    }
                }
            }
        }
    }

    /**
     * 地主一确定就把他【戴王冠】那版头像预热出来。
     *
     * <p>【为什么不能在 {@link #prewarmAvatars} 里一起热】：斗地主是先发牌、后叫地主，
     * 发牌那一刻还不知道谁是地主。而王冠进了缓存 key（见
     * {@link PlayerHeadRenderer#miniMessageFor}），戴冠版是一条独立缓存 —— 发牌时热的
     * 全是不戴冠那版，地主一定下来照样 miss，王冠会晚几十毫秒才出现。
     *
     * <p>【为什么不干脆两种冠态都热】：每个 miss 的 key 都是一次完整的皮肤 HTTP 下载
     * （下载层没有图片级缓存），全热就是每局 6 次变 12 次。地主只有一个，
     * 定下来之后只补他一个人、两种 scale，代价是 2 次。
     *
     * @param seats    这一桌的三个座位，用来算机器人皮肤变体（同桌不重脸）
     * @param landlord 刚确定的地主；{@code null} 时直接返回
     */
    void prewarmLandlordCrown(List<Seat> seats, UUID landlord) {
        Snapshot current0 = snapshot;
        if (landlord == null || !current0.settings().enabled() || !offsetService.isAvailable()) {
            return;
        }
        Seat seat = seats.stream()
            .filter(candidate -> candidate != null && landlord.equals(candidate.playerId()))
            .findFirst()
            .orElse(null);
        if (seat == null) {
            return;
        }
        List<UUID> tableBotIds = botIdsOf(seats.toArray(new Seat[0]));
        int tier = current0.avatarRowDownTier();
        boolean continuousFont = !legacyBundleExact(current0.settings());
        // 两种 scale 都要：地主也会轮到坐中间那个大头像的位置。
        for (int scale : new int[] {SIDE_AVATAR_SCALE, current0.settings().avatarScale()}) {
            if (seat.isBot()) {
                headRenderer.miniMessageForBot(
                    tableBotIds, seat.playerId(), scale, tier, true, continuousFont);
            } else {
                requestPlayerInput(seat.playerId());
                PlayerInputSnapshot input = playerInputOf(seat.playerId());
                if (input.online()) {
                    headRenderer.miniMessageFor(
                        input.skinUrl(), scale, tier, true, continuousFont);
                }
            }
        }
    }

    /**
     * HUD 上三个头像槽各是谁。
     *
     * @param playerId 该槽位的玩家；null 表示这个槽位没人（人数不足、空座），槽宽仍保留
     * @param isBot    是不是机器人（机器人没有皮肤，走位图图标兜底）
     * @param role     角色，机器人兜底图标要按角色选地主还是农民那张
     */
    record Seat(UUID playerId, boolean isBot, PlayerRole role) {
        static final Seat EMPTY = new Seat(null, false, null);
    }

    /**
     * 刷新某个观看者的 HUD。每秒会被调用，所以内部靠内容比对避免重复发包。
     *
     * @param previous 上一位玩家
     * @param current  当前该出牌的人，画在正中间、用大倍数
     * @param next     下一位玩家
     * @param cards    桌上最后打出的那手牌；空表示这一轮还没人出牌，上排留空但头像照旧显示
     */
    void render(Player viewer, Seat previous, Seat current, Seat next, List<DoudizhuCard> cards) {
        render(viewer, previous, current, next, cards, Map.of());
    }

    /**
     * 带记牌器读数的兼容渲染入口。
     *
     * <p>调用方只给剩余张数时，累计已出数量必须由「初始牌数 - 剩余张数」即时推导，
     * 不能把剩余张数复用成显示数字；这样既不引入第二份可变状态，也和 GameTable 的正式路由一致。
     *
     * @param remainingCounts 每个点数还剩几张；空表示不画记牌行
     */
    void render(
        Player viewer,
        Seat previous,
        Seat current,
        Seat next,
        List<DoudizhuCard> cards,
        Map<CardRank, Integer> remainingCounts
    ) {
        render(viewer, previous, current, next, cards, playedCountsFromRemaining(remainingCounts), remainingCounts,
            plugin.hudRowOverride(viewer.getUniqueId()), false);
    }

    /** 正式牌桌路由：计牌器显示累计已出数量，剩余数量仅用于耗尽/隐藏判断。 */
    void render(
        Player viewer,
        Seat previous,
        Seat current,
        Seat next,
        List<DoudizhuCard> cards,
        Map<CardRank, Integer> playedCounts,
        Map<CardRank, Integer> remainingCounts
    ) {
        render(viewer, previous, current, next, cards, playedCounts, remainingCounts,
            plugin.hudRowOverride(viewer.getUniqueId()), false);
    }

    /**
     * 调试预览专用入口：允许只显示指定行，并在不要求玩家携带记牌器时显示样例记牌数据。
     * 正式牌桌调用上面的旧入口，默认三行全开且仍要求玩家持有记牌器。
     *
     * <p>这里同样只接收剩余张数，显示用的累计已出数量现场推导，避免调试棒和 Web fixture
     * 因各自维护一份 played 数据而漂移。
     */
    void render(
        Player viewer,
        Seat previous,
        Seat current,
        Seat next,
        List<DoudizhuCard> cards,
        Map<CardRank, Integer> remainingCounts,
        int visibleRows,
        boolean forceCounterWithoutItem
    ) {
        render(viewer, previous, current, next, cards, playedCountsFromRemaining(remainingCounts), remainingCounts,
            visibleRows, forceCounterWithoutItem);
    }

    void render(
        Player viewer,
        Seat previous,
        Seat current,
        Seat next,
        List<DoudizhuCard> cards,
        Map<CardRank, Integer> playedCounts,
        Map<CardRank, Integer> remainingCounts,
        int visibleRows,
        boolean forceCounterWithoutItem
    ) {
        Snapshot current0 = snapshot;
        Settings settings = current0.settings();
        UUID viewerId = viewer.getUniqueId();
        requestPlayerInput(viewerId);
        requestSeatInput(previous);
        requestSeatInput(current);
        requestSeatInput(next);
        PlayerInputSnapshot viewerInput = playerInputOf(viewerId);
        if (!settings.enabled()) {
            hide(viewer);
            return;
        }
        if (!offsetService.isAvailable()) {
            warnOffsetsUnavailableOnce(viewer);
            hide(viewer);
            return;
        }
        if (visibleRows == 0) {
            hide(viewer);
            return;
        }
        boolean legacyBundle = legacyBundleExact(settings);
        if (!overlayReadyFor(settings) && !legacyBundle) {
            warnOverlayNotReadyOnce(viewer, settings);
            hide(viewer);
            return;
        }
        boolean continuousFont = !legacyBundle;
        boolean showCards = (visibleRows & 1) != 0;
        boolean showAvatars = (visibleRows & 2) != 0;
        boolean showCounter = settings.counterEnabled()
            && (forceCounterWithoutItem || viewerInput.hasCounterItem())
            && (visibleRows & 4) != 0;
        List<UUID> tableBotIds = botIdsOf(previous, current, next);
        int avatarRowDownTier = current0.avatarRowDownTier();
        String line = TrickHudView.buildMiniMessage(
            showAvatars ? avatarSlot(previous, SIDE_AVATAR_SCALE, tableBotIds, avatarRowDownTier, continuousFont) : TrickHudView.Avatar.EMPTY,
            showAvatars ? avatarSlot(current, settings.avatarScale(), tableBotIds, avatarRowDownTier, continuousFont) : TrickHudView.Avatar.EMPTY,
            showAvatars ? avatarSlot(next, SIDE_AVATAR_SCALE, tableBotIds, avatarRowDownTier, continuousFont) : TrickHudView.Avatar.EMPTY,
            showAvatars ? current0.avatarSlotWidth() : 0,
            settings.avatarGap(),
            showCards ? cards : List.of(),
            settings.cardStep(),
            offsetService::offset,
            settings.heightTier(),
            settings.downOffsetTier(),
            settings.offsetX(),
            settings.rowXOffsets(),
            showCounter ? counterCells(playedCounts, remainingCounts, settings.counterHideExhausted(),
                settings.counterScale(), settings.counterDownOffsetTier(), continuousFont) : List.of(),
            settings.counterGap(),
            continuousFont
        );
        apply(viewer, line);
    }

    /**
     * 把累计已出数量摊成固定 15 格分层字形。
     *
     * <p>每格按「点数标签、框、已出数字」三层生成；View 使用当前生成 geometry 携带的
     * advance 把后两层叠回同一格，最后一层保留该格的净前进量。剩余数量只负责判断耗尽
     * 与是否隐藏，正式 HUD 不再把剩余数当作显示数字。
     *
     * @param playedCounts       每个点数累计已出数量
     * @param remainingCounts    每个点数剩余数量，仅用于耗尽/隐藏判断
     * @param hideWhenExhausted  出完的点数是否输出空占位
     * @param scale              记牌器资源缩放百分比
     * @param downOffsetTier     记牌器自己的向下偏移档
     */
    private List<TrickHudView.CounterCell> counterCells(
        Map<CardRank, Integer> playedCounts,
        Map<CardRank, Integer> remainingCounts,
        boolean hideWhenExhausted,
        int scale,
        int downOffsetTier,
        boolean continuousFont
    ) {
        if ((playedCounts == null || playedCounts.isEmpty())
            && (remainingCounts == null || remainingCounts.isEmpty())) {
            return List.of();
        }
        Map<CardRank, Integer> played = playedCounts == null ? Map.of() : playedCounts;
        Map<CardRank, Integer> remaining = remainingCounts == null ? Map.of() : remainingCounts;
        int glyphDownTier = continuousFont ? 0 : downOffsetTier;
        String baseFont = PackAssets.counterGlyphFont(scale, glyphDownTier);
        String font = continuousFont ? HudOverlayLayout.continuousFont(baseFont) : baseFont;
        PackAssets.CounterTier geometry = PackAssets.counterGeometry(scale, glyphDownTier);
        List<TrickHudView.CounterCell> cells = new ArrayList<>(CardRank.values().length);
        for (CardRank rank : CardRank.values()) {
            int initial = initialCount(rank);
            int shown = clampCount(played.getOrDefault(rank, 0), initial);
            int left = clampCount(remaining.getOrDefault(rank, initial), initial);
            boolean exhausted = left == 0;
            if (hideWhenExhausted && exhausted) {
                cells.add(new TrickHudView.CounterCell(List.of(), geometry.advance()));
                continue;
            }
            // 框 PNG 已按 normal/exhausted 生成两种颜色；用白色保留贴图颜色，避免再次乘色变脏。
            String frameColor = "white";
            String labelColor = exhausted ? "dark_gray" : "white";
            String digitColor = exhausted ? "dark_gray" : "gray";
            String frame = layer(font, PackAssets.counterFrameChar(exhausted, scale, glyphDownTier), frameColor);
            String label = layer(font, PackAssets.counterRankChar(rank, scale, glyphDownTier), labelColor);
            String digit = layer(font, PackAssets.counterDigitChar(shown, scale, glyphDownTier), digitColor);
            // 层顺序固定为 label → frame → digit；View 按当前生成 geometry 的 advance 拉回后层。
            cells.add(new TrickHudView.CounterCell(List.of(label, frame, digit), geometry.advance()));
        }
        return cells;
    }

    /**
     * 兼容旧测试与旧内部调用：默认使用 100% 记牌器资源和基准向下偏移档。
     *
     * <p>分层记牌器新增 scale 与独立 downTier 后，完整入口携带五个参数；保留这个四参数委托，
     * 避免旧调用方在资源档升级时失去原有的默认行为。
     */
    private List<TrickHudView.CounterCell> counterCells(
        Map<CardRank, Integer> playedCounts,
        Map<CardRank, Integer> remainingCounts,
        boolean hideWhenExhausted,
        int downOffsetTier
    ) {
        return counterCells(
            playedCounts, remainingCounts, hideWhenExhausted,
            PackAssets.DEFAULT_HUD_SCALE, downOffsetTier, false);
    }

    static Map<CardRank, Integer> playedCountsFromRemaining(Map<CardRank, Integer> remainingCounts) {
        if (remainingCounts == null || remainingCounts.isEmpty()) {
            return Map.of();
        }
        EnumMap<CardRank, Integer> played = new EnumMap<>(CardRank.class);
        for (CardRank rank : CardRank.values()) {
            int initial = initialCount(rank);
            int left = clampCount(remainingCounts.getOrDefault(rank, initial), initial);
            played.put(rank, initial - left);
        }
        return Map.copyOf(played);
    }

    private static int initialCount(CardRank rank) {
        return rank.isJoker() ? 1 : 4;
    }

    private static int clampCount(int value, int initial) {
        return Math.max(0, Math.min(initial, value));
    }

    private static String layer(String font, String glyph, String color) {
        return "<" + color + "><font:" + font + ">" + glyph + "</font></" + color + ">";
    }

    private boolean overlayReadyFor(Settings settings) {
        HudOverlayRuntimeState state = plugin.getHudOverlayRuntimeState();
        return state != null && state.matchesTrick(
            settings.cardOffsetDown(), settings.avatarOffsetDown(), settings.counterOffsetDown());
    }

    private static boolean legacyBundleExact(Settings settings) {
        return settings.cardOffsetDown() == PackAssets.cardGlyphDownOffsetAt(settings.downOffsetTier())
            && settings.avatarOffsetDown() == PackAssets.avatarDownOffsetAt(settings.avatarDownOffsetTier())
            && settings.counterOffsetDown() == PackAssets.counterDownOffsetAt(settings.counterDownOffsetTier());
    }

    private void warnOverlayNotReadyOnce(Player viewer, Settings settings) {
        if (!offsetWarnedViewers.add(viewer.getUniqueId())) {
            return;
        }
        output.sendActionBar(viewer.getUniqueId(), MuzTheme.danger(
            "出牌 HUD 不可用：连续字体覆盖层未校验，请重新生成并加载资源包"));
        plugin.getLogger().warning("出牌 HUD 因连续字体覆盖层未就绪而隐藏：card="
            + settings.cardOffsetDown() + ", avatar=" + settings.avatarOffsetDown()
            + ", counter=" + settings.counterOffsetDown());
    }

    /**
     * 偏移服务不可用时，给这名玩家提示一次。
     *
     * <p>每人只发一次：render 挂在每秒的倒计时广播上，不去重会变成刷屏。
     * 用 ActionBar 而不是聊天栏，理由同上——每秒一条聊天记录会把屏幕冲满。
     */
    private void warnOffsetsUnavailableOnce(Player viewer) {
        if (!offsetWarnedViewers.add(viewer.getUniqueId())) {
            return;
        }
        output.sendActionBar(viewer.getUniqueId(), MuzTheme.danger(
            "出牌 HUD 不可用：CraftEngine 字体偏移没就绪，请检查资源包是否加载成功"));
        plugin.getLogger().warning("出牌 HUD 因 CraftEngine 字体偏移不可用而未显示，"
            + "常见原因是资源包配置解析失败（例如 configuration 下某个 yml 超过 SnakeYAML "
            + "单文档上限）。修好后执行 /muz reload 即可重试，不必重启。");
    }

    /** 收起该观看者的 HUD（离桌、游戏结束、或这一轮被重置）。 */
    void hide(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        BossBar bar = bars.remove(viewerId);
        lastLines.remove(viewerId);
        if (bar != null) {
            output.hideBossBar(viewerId, bar);
        }
    }

    /** 桌子销毁时把所有还挂着的 HUD 收掉，避免玩家屏幕上留一条永久的空轨道。 */
    void hideAll() {
        for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            output.hideBossBar(entry.getKey(), entry.getValue());
        }
        bars.clear();
        lastLines.clear();
    }

    private void apply(Player viewer, String line) {
        UUID viewerId = viewer.getUniqueId();
        if (line.isEmpty()) {
            hide(viewer);
            return;
        }
        if (line.equals(lastLines.get(viewerId))) {
            // 内容没变（同一手牌还摆在桌上），不必每秒重发。
            return;
        }
        Component name;
        try {
            name = MiniMessage.miniMessage().deserialize(line);
        } catch (Exception exception) {
            // 偏移片段是 CraftEngine 生成的，理论上合法；真解析失败就别把 HUD 变成崩溃源。
            plugin.getLogger().warning("Failed to parse trick HUD line: " + exception.getMessage());
            hide(viewer);
            return;
        }
        lastLines.put(viewerId, line);
        BossBar bar = bars.get(viewerId);
        if (bar == null) {
            // 进度固定 0：轨道贴图已被资源包透明化，这里再让前景宽度为 0，
            // 万一贴图没加载成功也只会露出空槽而不是一条满血条。
            bar = BossBar.bossBar(name, 0.0f, BAR_COLOR, BAR_OVERLAY);
            bars.put(viewerId, bar);
            output.showBossBar(viewerId, bar);
            return;
        }
        BossBar currentBar = bar;
        output.runPlayer(viewerId, ignored -> currentBar.name(name));
    }

    /** 描边开着的话头像矩阵是 10x10，宽度跟着涨两列，槽宽算式要用同一个判断。 */
    private boolean isOutlined() {
        return plugin.getTrickHudAvatarOutlineArgb() != 0;
    }

    /**
     * 这三个 HUD 槽位里的机器人 UUID。
     *
     * <p>斗地主固定三人，而 HUD 的三个槽正好是「上一位 / 当前 / 下一位」，所以这三个槽
     * 就是整桌名单 —— 不必再从 {@link GameTable} 多传一份，也就不会有两份名单不一致的问题。
     *
     * <p>这份名单交给 {@link PlayerHeadRenderer#botSkinVariant} 决定谁用哪张皮肤。
     * 名单内容在一局内不变（机器人只在 LOBBY 阶段增减），所以每帧算出的分配结果相同，
     * 机器人不会每次刷新就换一张脸。
     */
    static List<UUID> botIdsOf(Seat... seats) {
        List<UUID> ids = new ArrayList<>();
        for (Seat seat : seats) {
            if (seat != null && seat.isBot() && seat.playerId() != null) {
                ids.add(seat.playerId());
            }
        }
        return ids;
    }

    /**
     * 拼一个头像槽：优先用皮肤渲染的像素头像，拿不到就退回位图字形图标。
     *
     * <p>真人和机器人现在走【同一条】像素头像路径，只是皮肤 URL 来源不同（真人查
     * {@code PlayerProfile}，机器人查内置常量池）。这样机器人那一槽的尺寸、描边、偏移档
     * 都和真人一致 —— 之前机器人用的是构建期固定 10/11 像素的手绘图标，比真人头像小一圈。
     *
     * <p>位图图标兜底【仍然必须留着】，这几种情况都会走到它，否则那个槽位会空着：
     * 皮肤还在异步下载（刚开局那一帧）、皮肤站连不通或返回错误、玩家没有自定义皮肤。
     *
     * <p>座位没人（{@code playerId == null}）是另一回事：那是人数不足或空座，
     * 该真的留空 —— 给不存在的人画一个机器人图标反而误导。
     *
     * <p>槽位宽度由调用方统一给（三槽等宽），这里只需要报出这段实际占多宽，
     * 让排版把它在槽里居中。
     */
    private TrickHudView.Avatar avatarSlot(
        Seat seat, int scale, List<UUID> tableBotIds, int avatarRowDownTier, boolean continuousFont) {
        if (seat == null || seat.playerId() == null) {
            return TrickHudView.Avatar.EMPTY;
        }
        String rendered;
        // 【offline 与「皮肤还没下载好」必须分开】：两者都让 rendered 变成 null，但含义相反。
        // 掉线是持续状态，那一槽会一直没有头像，必须画图标占住，否则 HUD 上留一个长期的洞；
        // 皮肤没下载好只是几十毫秒的暂态，下一帧就被真头像替掉，画图标反而闪一下。
        boolean offline = false;
        // 地主戴王冠。角色来自座位本身，所以叫完地主刷新一次 HUD 王冠就会出现。
        boolean crowned = seat.role() == PlayerRole.LANDLORD;
        if (seat.isBot()) {
            // 机器人不会掉线：它的皮肤来自内置常量池，null 只可能是下载中或下载失败。
            rendered = headRenderer.miniMessageForBot(
                tableBotIds, seat.playerId(), scale, avatarRowDownTier, crowned, continuousFont);
        } else {
            requestPlayerInput(seat.playerId());
            PlayerInputSnapshot input = playerInputOf(seat.playerId());
            offline = !input.online();
            rendered = offline
                ? null
                : headRenderer.miniMessageFor(
                    input.skinUrl(), scale, avatarRowDownTier, crowned, continuousFont);
        }
        return avatarSlotOf(
            seat, scale, isOutlined(), rendered, avatarRowDownTier, offline, continuousFont);
    }

    /**
     * 把「已经取到（或没取到）的头像文本」包成一个槽位。
     *
     * <p>从 {@link #avatarSlot} 里拆出来是为了能测：取皮肤那一步要碰 Bukkit 和网络，
     * 而真正容易出错的是【拿不到皮肤时怎么兜底】和【报出的宽度对不对】。
     * 拆开后这部分是纯函数，同 {@link #readSettings} 与 {@link #warnIfRowsOverlap} 的做法。
     *
     * @param rendered 像素头像文本；{@code null} 表示这次没取到 —— 皮肤还在异步下载
     *                 （{@link #prewarmAvatars} 没赶上）、皮肤站连不通或返回错误，或玩家掉线。
     * @param outlined 描边是否开着，决定像素头像宽度按 10 行还是 8 行算
     * @param offline  该座位的真人玩家当前不在线。这是【持续状态】，与「皮肤还没下载好」
     *                 这种暂态区别对待：见方法内注释
     */
    static TrickHudView.Avatar avatarSlotOf(
        Seat seat, int scale, boolean outlined, String rendered, int avatarRowDownTier,
        boolean offline) {
        return avatarSlotOf(seat, scale, outlined, rendered, avatarRowDownTier, offline, false);
    }

    static TrickHudView.Avatar avatarSlotOf(
        Seat seat, int scale, boolean outlined, String rendered, int avatarRowDownTier,
        boolean offline, boolean continuousFont) {
        if (seat == null || seat.playerId() == null) {
            return TrickHudView.Avatar.EMPTY;
        }
        // 【戴不戴王冠不影响这里】：王冠走独立字形家族、画在脸上方，画完净位移为零，
        // 所以地主和农民的槽宽完全一致。之前那版王冠往上加两行，宽度就得跟着分叉，
        // 还会把地主的脸压低两像素 —— 三个头像并排时一眼看出没对齐。
        if (rendered != null) {
            // 【宽度必须和渲染那边同源】：advanceWidth 就是 renderMiniMessage 的净前进量，
            // 两者脱钩会让槽内居中整体偏，而且真人和机器人一起偏，看不出是哪边错。
            return new TrickHudView.Avatar(rendered, PlayerHeadRenderer.advanceWidth(scale, outlined));
        }
        if (offline) {
            // 掉线是【持续状态】：这一槽在玩家回来之前一直没有头像，必须画图标占住，
            // 否则 HUD 上会留一个长期的洞，看着像 HUD 坏了。
            // 连续覆盖层必须使用 base tier 0 的 bot 码位与独立 continuous 字体；
            // 旧 bundle 路径才继续使用头像行旧档码位。
            String botFont = continuousFont
                ? HudOverlayLayout.continuousFont(PackAssets.BOT_AVATAR_FONT)
                : PackAssets.BOT_AVATAR_FONT;
            String botGlyph = continuousFont
                ? PackAssets.botAvatarChar(seat.role())
                : PackAssets.botAvatarChar(seat.role(), avatarRowDownTier);
            return new TrickHudView.Avatar(
                "<white><font:" + botFont + ">" + botGlyph + "</font></white>",
                PackAssets.botAvatarAdvanceWidth(seat.role())
            );
        }
        // 【皮肤还没下载好：留空，不画那张位图图标】
        //
        // 那张图标是构建期固定 10/11 像素的，不随 avatar-scale 缩放，闪出来时比真人头像小
        // 一圈、风格也不一致，表现是「出牌瞬间有个小图标跳一下」—— 这就是服主报的现象。
        // 而这个分支全是暂态（下载中、皮肤站抽风），下一帧就会被真头像替掉，
        // 为几十毫秒画一个风格不一致的东西比那一槽空着更显眼。
        //
        // 宽度仍按真头像算：槽宽恒定，皮肤到位时不会整行左右跳动。
        return new TrickHudView.Avatar("", PlayerHeadRenderer.advanceWidth(scale, outlined));
    }
}
