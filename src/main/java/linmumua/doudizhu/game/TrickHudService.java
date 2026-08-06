package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.assets.PlayerHeadRenderer;
import linmumua.doudizhu.compat.CraftEngineOffsetService;
import linmumua.doudizhu.config.MuzYamlConfig;
import linmumua.doudizhu.model.DoudizhuCard;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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
    private static final int DEFAULT_AVATAR_SCALE = 6;

    /** 左右两个小头像的放大倍数。比中间小一档，一眼就能看出「中间那个才是该出牌的人」。 */
    private static final int SIDE_AVATAR_SCALE = 4;

    /** 相邻两个头像槽的间距。6 是沿用原先「头像到牌」的实测调优值，观感上三头像不粘连。 */
    private static final int DEFAULT_AVATAR_GAP = 6;

    /**
     * 整条 HUD 相对 BossBar 默认位置往下推的像素数。
     *
     * <p>50 是测试服实测调优值，把 HUD 从紧贴屏幕顶部推到不挡准星的位置。
     * 必须是 {@link PackAssets#cardGlyphDownOffsetTierList()} 里预生成的档位，
     * 否则默认配置本身就会触发回退警告。
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
     * <p>算出来必须落在 {@link PackAssets#avatarDownOffsetTierList()} 里，否则默认配置
     * 自己就会触发回退警告 —— 有测试守这一点。
     */
    private static final int DEFAULT_AVATAR_OFFSET_DOWN =
        PackAssets.avatarRowDownOffset(DEFAULT_OFFSET_DOWN, DEFAULT_AVATAR_SCALE);

    /**
     * 相邻两张牌左缘的间距。牌宽 35，这里取 22 是让牌像手牌那样叠放：
     * 一手最多能有 20 张（比如四个三带的飞机），全展开要 700 像素以上会超出屏幕。
     */
    private static final int DEFAULT_CARD_STEP = 22;

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
     * @param offsetX        整体水平偏移像素，正右负左
     */
    record Settings(
        boolean enabled,
        int avatarScale,
        int avatarGap,
        int cardStep,
        int heightTier,
        int downOffsetTier,
        int avatarDownOffsetTier,
        int offsetX
    ) {
    }

    /**
     * 解析 config 里的 trick-hud 段。纯函数：不碰 Bukkit，警告往哪去由调用方决定，
     * 这样测试可以直接把警告收进列表来断言「越界值确实被拒了」。
     *
     * <p>越界值一律回退而不是照用：avatarScale 超出资源包预生成范围会让头像整片
     * 变成豆腐块，cardStep 非正会让牌倒着排或全叠成一张，两种都是纯粹的配置笔误。
     */
    static Settings readSettings(MuzYamlConfig config, Consumer<String> warn) {
        boolean enabled = config.getBoolean("trick-hud.enabled", true);

        int avatarScale = config.getInt("trick-hud.avatar-scale", DEFAULT_AVATAR_SCALE);
        if (avatarScale < PackAssets.AVATAR_PIXEL_MIN_SCALE || avatarScale > PackAssets.AVATAR_PIXEL_MAX_SCALE) {
            // 必须留日志：否则玩家只会看到头像莫名变方块，没人能联想到是这一行配置写错了。
            warn.accept("trick-hud.avatar-scale=" + avatarScale + " 超出资源包预生成范围（"
                + PackAssets.AVATAR_PIXEL_MIN_SCALE + ".." + PackAssets.AVATAR_PIXEL_MAX_SCALE
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

        // 缩放与向下偏移都只能取【构建期预生成的档位】：height/ascent 固化在资源包的
        // images.yml 里，运行时改不了，写一个没生成过的值会直接变豆腐块。
        int cardHeight = config.getInt("trick-hud.card-height", PackAssets.cardGlyphHeightAt(0));
        int heightTier = PackAssets.cardGlyphHeightTierOf(cardHeight);
        if (heightTier < 0) {
            warn.accept("trick-hud.card-height=" + cardHeight + " 不是预生成的缩放档（可选 "
                + PackAssets.cardGlyphHeightTierList() + "），已回退为 " + PackAssets.cardGlyphHeightAt(0));
            heightTier = 0;
        }

        int offsetDown = config.getInt("trick-hud.offset-down", DEFAULT_OFFSET_DOWN);
        int downOffsetTier = PackAssets.cardGlyphDownOffsetTierOf(offsetDown);
        if (downOffsetTier < 0) {
            warn.accept("trick-hud.offset-down=" + offsetDown + " 不是预生成的偏移档（可选 "
                + PackAssets.cardGlyphDownOffsetTierList() + "），已回退为 "
                + PackAssets.cardGlyphDownOffsetAt(0));
            downOffsetTier = 0;
        }

        // 头像行的偏移【独立于牌行】，查的是头像自己那张档位表。两行能各自随便调是刻意的，
        // 代价是配歪了两行会重叠 —— 那由下面的 warnIfRowsOverlap 出警告，不在这里拦。
        int avatarOffsetDown = config.getInt("trick-hud.avatar-offset-down", DEFAULT_AVATAR_OFFSET_DOWN);
        int avatarDownOffsetTier = PackAssets.avatarDownOffsetTierOf(avatarOffsetDown);
        if (avatarDownOffsetTier < 0) {
            warn.accept("trick-hud.avatar-offset-down=" + avatarOffsetDown + " 不是预生成的头像偏移档（可选 "
                + PackAssets.avatarDownOffsetTierList() + "），已回退为 " + DEFAULT_AVATAR_OFFSET_DOWN);
            avatarDownOffsetTier = PackAssets.avatarDownOffsetTierOf(DEFAULT_AVATAR_OFFSET_DOWN);
        }
        warnIfRowsOverlap(downOffsetTier, avatarDownOffsetTier, avatarScale, warn);

        // offset-x 不校验：任意整数都合法（正右负左），靠负空格实现，不依赖预生成字形。
        int offsetX = config.getInt("trick-hud.offset-x", 0);

        return new Settings(
            enabled, avatarScale, avatarGap, cardStep, heightTier, downOffsetTier, avatarDownOffsetTier, offsetX);
    }

    /**
     * 两行配歪了会重叠，重叠就留警告。
     *
     * <p>【这是「两行位置完全自由」方案的已知代价】：牌行与头像行各有独立档位表、各自随便调，
     * 结构上不再保证不重叠，只能靠警告拦。所以这条警告必须真的有 —— 静默重叠的表现是
     * 「头像糊在牌上」，服主完全没法把这个现象和自己改的那行配置联系起来。
     *
     * <p>几何依据：位图字形占基线上方 {@code [ascent - height, ascent]}，两族都取
     * {@code ascent = height - d}，于是字形盒是「基线下方 d 到基线上方 height - d」。
     * 头像顶边在基线下方 {@code d_头像 - 10 * scale}，不重叠要求它不高于牌底（基线下方
     * {@code d_牌}），即 {@code d_头像 - 10 * scale >= d_牌}。
     *
     * <p>盒高必须按 {@link PackAssets#AVATAR_OUTLINED_PIXELS}(10) 算，不是 8：描边那两行
     * 永远参与字形度量，运行期关 avatar-outline 只是不画。按 8 算会漏报 2*scale 像素的重叠。
     *
     * <p>纯函数，警告去向由调用方决定（同 {@link #readSettings}），测试可以直接断言
     * 「重叠组合确实留了警告」。
     *
     * @param cardDownOffsetTier   牌行的向下偏移档序号（牌那张表）
     * @param avatarDownOffsetTier 头像行的向下偏移档序号（头像那张表）
     * @param avatarScale          大头像倍数，决定头像盒高
     */
    static void warnIfRowsOverlap(
        int cardDownOffsetTier, int avatarDownOffsetTier, int avatarScale, Consumer<String> warn) {
        int cardDown = PackAssets.cardGlyphDownOffsetAt(cardDownOffsetTier);
        int avatarDown = PackAssets.avatarDownOffsetAt(avatarDownOffsetTier);
        int boxHeight = PackAssets.AVATAR_OUTLINED_PIXELS * avatarScale;
        int required = PackAssets.avatarRowDownOffset(cardDown, avatarScale);
        if (avatarDown >= required) {
            return;
        }
        warn.accept("trick-hud.avatar-offset-down=" + avatarDown + " 比牌行低太少，头像会压进牌里 "
            + (required - avatarDown) + " 像素（牌行 offset-down=" + cardDown + " + 头像字形盒高 "
            + boxHeight + " = 至少要 " + required + "，注意盒高按 10*avatar-scale 算、"
            + "与 avatar-outline 开关无关）；想要两行精确相接请把 avatar-offset-down 设为 "
            + required + "，它必须是预生成档位之一（" + PackAssets.avatarDownOffsetTierList() + "）");
    }

    private final DoudizhuPlugin plugin;
    private final CraftEngineOffsetService offsetService;
    private final PlayerHeadRenderer headRenderer;

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

    TrickHudService(
        DoudizhuPlugin plugin,
        CraftEngineOffsetService offsetService,
        PlayerHeadRenderer headRenderer
    ) {
        this.plugin = plugin;
        this.offsetService = offsetService;
        this.headRenderer = headRenderer;
        this.snapshot = buildSnapshot();
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
        // 【整帧只读一次快照】：reload 可能在渲染中途换掉它，读两次就可能前半帧用旧 scale、
        // 后半帧用新槽宽，画出错位的一帧。存成局部变量后这一帧一定是自洽的。
        Snapshot current0 = snapshot;
        Settings settings = current0.settings();
        if (!settings.enabled() || !offsetService.isAvailable()) {
            // 没有负空格就没法叠牌也没法拼头像，整条 HUD 不显示，避免画出一条横到屏幕外的牌。
            hide(viewer);
            return;
        }
        // 名单要在三个槽【之前】算好并整份传下去：皮肤分配必须看到同桌全部 bot 才能保证不重脸，
        // 逐槽各算一次只能看到自己，两个 bot 就可能撞到同一张皮肤。
        List<UUID> tableBotIds = botIdsOf(previous, current, next);
        int avatarRowDownTier = current0.avatarRowDownTier();
        String line = TrickHudView.buildMiniMessage(
            avatarSlot(previous, SIDE_AVATAR_SCALE, tableBotIds, avatarRowDownTier),
            avatarSlot(current, settings.avatarScale(), tableBotIds, avatarRowDownTier),
            avatarSlot(next, SIDE_AVATAR_SCALE, tableBotIds, avatarRowDownTier),
            current0.avatarSlotWidth(),
            settings.avatarGap(),
            cards,
            settings.cardStep(),
            offsetService::offset,
            settings.heightTier(),
            settings.downOffsetTier(),
            settings.offsetX()
        );
        apply(viewer, line);
    }

    /** 收起该观看者的 HUD（离桌、游戏结束、或这一轮被重置）。 */
    void hide(Player viewer) {
        UUID viewerId = viewer.getUniqueId();
        BossBar bar = bars.remove(viewerId);
        lastLines.remove(viewerId);
        if (bar != null) {
            viewer.hideBossBar(bar);
        }
    }

    /** 桌子销毁时把所有还挂着的 HUD 收掉，避免玩家屏幕上留一条永久的空轨道。 */
    void hideAll() {
        for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer != null) {
                viewer.hideBossBar(entry.getValue());
            }
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
            viewer.showBossBar(bar);
            return;
        }
        bar.name(name);
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
        Seat seat, int scale, List<UUID> tableBotIds, int avatarRowDownTier) {
        if (seat == null || seat.playerId() == null) {
            return TrickHudView.Avatar.EMPTY;
        }
        String rendered;
        if (seat.isBot()) {
            rendered = headRenderer.miniMessageForBot(tableBotIds, seat.playerId(), scale, avatarRowDownTier);
        } else {
            Player player = Bukkit.getPlayer(seat.playerId());
            rendered = player == null ? null : headRenderer.miniMessageFor(player, scale, avatarRowDownTier);
        }
        return avatarSlotOf(seat, scale, isOutlined(), rendered, avatarRowDownTier);
    }

    /**
     * 把「已经取到（或没取到）的头像文本」包成一个槽位。
     *
     * <p>从 {@link #avatarSlot} 里拆出来是为了能测：取皮肤那一步要碰 Bukkit 和网络，
     * 而真正容易出错的是【拿不到皮肤时怎么兜底】和【报出的宽度对不对】。
     * 拆开后这部分是纯函数，同 {@link #readSettings} 与 {@link #warnIfRowsOverlap} 的做法。
     *
     * @param rendered 像素头像文本；{@code null} 表示这次没取到 —— 皮肤还在异步下载
     *                 （刚开局那一帧）、皮肤站连不通或返回错误、玩家没有自定义皮肤。
     *                 这几种都必须兜住，否则那个槽位会空着。
     * @param outlined 描边是否开着，决定像素头像宽度按 10 行还是 8 行算
     */
    static TrickHudView.Avatar avatarSlotOf(
        Seat seat, int scale, boolean outlined, String rendered, int avatarRowDownTier) {
        if (seat == null || seat.playerId() == null) {
            return TrickHudView.Avatar.EMPTY;
        }
        if (rendered != null) {
            // 【宽度必须和渲染那边同源】：advanceWidth 就是 renderMiniMessage 的净前进量，
            // 两者脱钩会让槽内居中整体偏，而且真人和机器人一起偏，看不出是哪边错。
            return new TrickHudView.Avatar(rendered, PlayerHeadRenderer.advanceWidth(scale, outlined));
        }
        // 图标挂在 PackAssets.BOT_AVATAR_FONT 上，不套 <font:...> 会是豆腐块。
        // 这张图标是构建期固定尺寸的（10/11 像素），不随 avatar-scale 缩放，
        // 所以兜底时那一槽会明显比真人头像小 —— 兜底本来就是「宁可小也别空着」。
        return new TrickHudView.Avatar(
            "<white><font:" + PackAssets.BOT_AVATAR_FONT + ">"
                + PackAssets.botAvatarChar(seat.role(), avatarRowDownTier)
                + "</font></white>",
            PackAssets.botAvatarAdvanceWidth(seat.role())
        );
    }
}
