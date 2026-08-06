package linmumua.doudizhu.assets;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import javax.imageio.ImageIO;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.compat.CraftEngineOffsetService;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerTextures;

/**
 * 把玩家皮肤的头部渲染成一段可以排进普通聊天行的「像素头像」。
 *
 * <p>为什么要这么绕：出牌 HUD 要求头像和牌并排在同一行文本里。牌是固定的 54 张，
 * 可以在构建期把牌面裁成字形；但玩家皮肤是运行时才知道的，没法预生成字形。
 * 所以资源包只提供一个【纯白方块】字形，这里读皮肤的 8x8 头部，把每个像素输出成
 * 一个方块字形加一个颜色标签 —— Minecraft 对字形是乘算着色，白底乘上像素颜色
 * 就得到该像素本身的颜色，64 个方块拼起来就是彩色头像。
 *
 * <p>垂直方向靠字形自带的 ascent 分档（第几行是烧进字形里的，见
 * {@link PackAssets#avatarPixelChar(int, int)}）；水平方向靠 CraftEngine 的负空格
 * 把光标拉回行首（见 {@link CraftEngineOffsetService}）。全程零 shader。
 *
 * <p>类分成两层：{@link #renderMiniMessage} 与 {@link #extractHead} 是纯函数，
 * 不碰网络也不碰 Bukkit，便于测试钉死输出结构；下载与缓存留在实例方法里。
 */
public final class PlayerHeadRenderer {
    /** 皮肤里头部正面的位置：base 层在 (8,8)，hat 层在 (40,8)，都是 8x8。 */
    private static final int HEAD_BASE_X = 8;
    private static final int HEAD_BASE_Y = 8;
    private static final int HEAD_HAT_X = 40;
    private static final int HEAD_HAT_Y = 8;

    /** alpha 低于这个值就当成透明，hat 层大面积是全透明的。 */
    private static final int ALPHA_THRESHOLD = 128;

    /**
     * Minecraft 位图字形固有的右侧字间距，单位像素。
     *
     * <p>【这是列间那道缝的根因】原版加载 bitmap provider 时，前进量恒等于
     * {@code round(有效墨水宽 * 缩放) + 1}，那个 {@code +1} 写在引擎里、资源包无法配置。
     * 方块贴图是 {@code scale} 像素宽、1:1 不缩放，所以字形前进 {@code scale + 1}
     * 而墨水只有 {@code scale} 宽 —— 每两列之间就露出 1 像素透明缝，整张脸被切成百叶窗。
     *
     * <p>【为什么不能靠改贴图解决】把方块贴成 {@code scale + 1} 宽并填满白，有效墨水宽变成
     * {@code scale + 1}，前进量跟着变成 {@code scale + 2}，缝还在（而且方块变成非正方形）。
     * 这个 {@code +1} 是加在墨水宽【之后】的，无论怎么改贴图都甩不掉，只能靠负偏移抵消。
     *
     * <p>行方向没有这个问题：行距由 ascent 决定，贴图高度是连续的
     * {@code (10 - row) * scale}，不经过前进量。
     */
    private static final int GLYPH_TRAILING_SPACING = 1;

    /**
     * 给机器人用的真实玩家皮肤纹理哈希。
     *
     * <p>为什么用真实皮肤而不是构建期手绘的位图图标：手绘图标是固定尺寸（10/11 像素），
     * 不随 {@code avatar-scale} 缩放，机器人那一槽会明显比真人头像小一圈，三连头像看着像坏了。
     * 换成真实皮肤后机器人和真人走【同一条】像素头像渲染路径，尺寸、描边、偏移档全部一致。
     *
     * <p>【为什么写成常量而不是查 Mojang API】查 API 是启动期额外的网络依赖与失败面，
     * 离线服根本连不通。这些哈希都实测过 HTTP 200 且是合法 PNG。
     *
     * <p>【为什么是这 6 个、以及被剔掉的两个】选皮肤不能只看「能下载」，还要看
     * {@link #extractHead} 合成之后【脸上真的有五官】。实测剔掉了两个：
     * Notch（{@code 2920...}）的 overlay 层在头部正面是整片不透明，合成后是一整块纯黑；
     * Dream（{@code ca93...}）的脸只有 2 种颜色。画出来都是一个色块，不像脸。
     * 留下的 6 个合成后有 5~41 种颜色，逐个看过亮度图确认是有五官的脸，且 6 张脸互不相同。
     *
     * <p>Dinnerbone 放第一位：64x64、带 hat 层。jeb_ 是 legacy 64x32，
     * {@link #extractHead} 的 {@code hasHatLayer} 按尺寸判，不会越界。
     */
    private static final List<String> BOT_SKIN_TEXTURE_HASHES = List.of(
        // Dinnerbone
        "50c410fad8d9d8825ad56b0e443e2777a6b46bfa20dacd1d2f55edc71fbeb06d",
        // jeb_（legacy 64x32）
        "7fd9ba42a7c81eeea22f1524271ae85a8e045ce0af5a6ae16c6406ae917e68b5",
        // Grumm
        "de377831c4fc89286cff1e5baeb24952b941dd3e0d8e4f0937f922fa088ab792",
        // GeorgeNotFound
        "2d7552678058720f8920bcee682ac4e7475e41e2155ae6700b2a58389f5b64f6",
        // Sapnap
        "99ba474cb840ca8692c867be459fb4142ff014a26c15bc6678a80a1d38d42495",
        // CaptainSparklez
        "ce61d4cc2e54105755b7e0a50808668308ea1cddd2243ef2294e119933ae1d67"
    );

    /** 上面那些哈希拼成的下载地址。常量拼串不可能不合法，真不合法就是代码笔误，当场炸掉。 */
    private static final List<URL> BOT_SKIN_URLS = BOT_SKIN_TEXTURE_HASHES.stream()
        .map(hash -> {
            try {
                return URI.create("https://textures.minecraft.net/texture/" + hash).toURL();
            } catch (MalformedURLException exception) {
                throw new IllegalStateException("内置机器人皮肤地址写错了：" + hash, exception);
            }
        })
        .toList();

    /** 一共备了几张机器人皮肤。同桌 bot 超过这个数才会开始重复长相。 */
    public static int botSkinCount() {
        return BOT_SKIN_URLS.size();
    }

    /**
     * 桌上某个机器人该用第几张皮肤。
     *
     * <p>要同时满足两条互相拉扯的要求：
     * <ul>
     *   <li><b>稳定</b>：同一个 bot 每帧都得是同一张脸，不能每次刷新就换头。</li>
     *   <li><b>不重脸</b>：同桌的 bot 必须长得不一样，否则玩家以为是同一个人。</li>
     * </ul>
     *
     * <p>单纯 {@code hash % n} 只能满足前者：两个随机 UUID 有 1/n 概率撞到同一张。
     * 单纯「按 UUID 排名取第 rank 张」能满足后者，但每桌都固定用前几张，
     * 备的 6 张里有一半永远见不到。
     *
     * <p>所以这里是【首选 + 探测】：先用 UUID 算一个首选下标（这一项只取决于 bot 自己，
     * 换桌、换座、换角色都不变），再按同桌 UUID 升序依次落座，首选被同桌先来的占了就顺位往后探。
     * 稳定性来自「名单和顺序在一局内都不变」（bot 名单只在 LOBBY 阶段变动），
     * 不重脸来自「探测跳过已占用下标」。
     *
     * <p>不用 {@link UUID#hashCode()}：那个实现只是「高低 64 位异或再折叠」，
     * 契约上并不保证跨版本稳定。这里直接自己按位算，写死在代码里就不会变。
     *
     * @param tableBotIds 这一桌所有机器人的 UUID，顺序随意、允许含 null 与重复
     * @param botId       要查的机器人
     * @return 皮肤下标；{@code botId} 为 null 或不在名单里时返回 -1（调用方应当走位图兜底）
     */
    public static int botSkinVariant(Collection<UUID> tableBotIds, UUID botId) {
        if (botId == null || tableBotIds == null) {
            return -1;
        }
        int skins = BOT_SKIN_URLS.size();
        // 去重 + 升序：落座顺序必须与调用方传进来的集合顺序无关，否则同一桌不同帧可能算出不同结果。
        List<UUID> roster = tableBotIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
        if (!roster.contains(botId)) {
            return -1;
        }
        boolean[] taken = new boolean[skins];
        for (UUID candidate : roster) {
            int preferred = Math.floorMod(stableHash(candidate), skins);
            int chosen = preferred;
            // 顺位探测：preferred, preferred+1, ... 各下标最多试一次。
            // 【上界必须有】全被占满时（同桌 bot 比皮肤还多）就接受重脸，
            // 没有上界会死循环 —— 那是比重脸严重得多的失败模式。
            for (int step = 1; step < skins && taken[chosen]; step++) {
                chosen = (preferred + step) % skins;
            }
            if (candidate.equals(botId)) {
                return chosen;
            }
            taken[chosen] = true;
        }
        return -1;
    }

    /** UUID 的稳定散列：不依赖 {@link UUID#hashCode()} 的实现细节，跨版本恒定。 */
    private static int stableHash(UUID uuid) {
        long bits = uuid.getMostSignificantBits() * 31L + uuid.getLeastSignificantBits();
        return (int) (bits ^ (bits >>> 32));
    }

    /**
     * 给头像像素矩阵加一圈描边，返回边长 +2 的新矩阵。
     *
     * <p>规则与构建期 {@code writeOutlinedGlyph} 一致：原本透明、四邻有不透明像素的位置
     * 填成描边色。四邻而非八邻，斜角不算，否则角上会多出一块。
     *
     * <p>向外扩而不是向内吃：向内会把最外圈皮肤像素盖掉，脸就缺一圈。
     * 代价是行数从 8 变 10，字形要多生成两行。
     *
     * @param head        [row][col] 的 ARGB，任意方阵
     * @param outlineArgb 描边色，需自带不透明的 alpha
     */
    public static int[][] withOutline(int[][] head, int outlineArgb) {
        int size = head.length;
        int padded = size + 2;
        int[][] out = new int[padded][padded];
        for (int row = 0; row < padded; row++) {
            for (int col = 0; col < padded; col++) {
                int sourceRow = row - 1;
                int sourceCol = col - 1;
                if (opaqueAt(head, sourceRow, sourceCol)) {
                    out[row][col] = head[sourceRow][sourceCol];
                    continue;
                }
                boolean touches = opaqueAt(head, sourceRow - 1, sourceCol)
                    || opaqueAt(head, sourceRow + 1, sourceCol)
                    || opaqueAt(head, sourceRow, sourceCol - 1)
                    || opaqueAt(head, sourceRow, sourceCol + 1);
                out[row][col] = touches ? outlineArgb : 0;
            }
        }
        return out;
    }

    /**
     * 渲染完一个头像后光标往右走了多少像素。
     *
     * <p>{@link #renderMiniMessage} 每列的【列距】正好是 {@code scale}：字形自带
     * {@code scale + GLYPH_TRAILING_SPACING} 的前进量，渲染时逐个用负偏移把多出来的
     * {@link #GLYPH_TRAILING_SPACING} 抵掉（否则列间露缝，见那个常量的说明）。
     * 最后一行不回退，所以整段的净前进量就是 {@code rows * scale} —— 也正好等于
     * 头像的【视觉宽度】，这一点是槽内居中能算对的前提。
     *
     * <p>出牌 HUD 的头像行要把每个头像在自己的槽位里居中，就必须知道这个值 ——
     * 三个头像倍数不同，估错就会整行歪掉。
     *
     * @param outlined 是否加了描边。描边把矩阵从 8x8 撑到 10x10，宽度跟着涨两列 ——
     *                 注意这只影响【画几列方块】，不影响字形本身的度量
     *                 （字形恒按 10 行预生成，见 {@link PackAssets#avatarRowDownOffset}）。
     */
    public static int advanceWidth(int scale, boolean outlined) {
        int rows = outlined ? PackAssets.AVATAR_OUTLINED_PIXELS : PackAssets.AVATAR_HEAD_PIXELS;
        return rows * scale;
    }

    private static boolean opaqueAt(int[][] head, int row, int col) {
        if (row < 0 || col < 0 || row >= head.length || col >= head[row].length) {
            return false;
        }
        return ((head[row][col] >>> 24) & 0xFF) >= ALPHA_THRESHOLD;
    }

    private final DoudizhuPlugin plugin;
    private final CraftEngineOffsetService offsetService;

    /** 玩家 -> 已渲染好的头像。key 里带皮肤 URL 与倍数，换皮肤或改 config 会自动失效。 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 正在下载的 key，避免同一张皮肤被并发拉多次。 */
    private final Map<String, Boolean> pending = new ConcurrentHashMap<>();

    public PlayerHeadRenderer(DoudizhuPlugin plugin, CraftEngineOffsetService offsetService) {
        this.plugin = plugin;
        this.offsetService = offsetService;
    }

    /**
     * 取该玩家的像素头像（MiniMessage 文本）。
     *
     * <p>皮肤要联网下载，所以【第一次调用通常返回 null】：这里只负责派一个异步任务去
     * 下载，下载完写进缓存，下一次调用就能拿到。调用方遇到 null 应当先不画头像，
     * 而不是阻塞等待 —— 主线程上做 HTTP 会直接卡服。
     *
     * @param scale           放大倍数，需在资源包预生成的范围内（见 {@link PackAssets#AVATAR_PIXEL_MIN_SCALE}）
     * @param downOffsetTier  向下偏移档，必须和同一行的牌用同一档，否则头像与牌上下错开
     * @return 头像的 MiniMessage 文本；皮肤还没就绪或不可用时返回 {@code null}
     */
    public String miniMessageFor(Player player, int scale, int downOffsetTier) {
        return miniMessageFor(skinUrlOf(player), scale, downOffsetTier);
    }

    /**
     * 取某个机器人的像素头像，皮肤来自内置常量池。
     *
     * <p>和真人走【同一条】渲染与缓存路径，只是皮肤 URL 的来源不同 —— 机器人只有 UUID、
     * 没有 {@link Player} 实体，取不到 {@code PlayerProfile}。
     *
     * <p>同真人一样【第一次调用通常返回 null】（皮肤要异步下载），调用方必须保留位图图标兜底。
     *
     * @param tableBotIds 这一桌所有机器人的 UUID，用来保证同桌不重脸（见 {@link #botSkinVariant}）
     * @param botId       要画的那个机器人
     * @return 头像的 MiniMessage 文本；皮肤还没就绪、下载失败或 botId 不在名单里时返回 {@code null}
     */
    public String miniMessageForBot(
        Collection<UUID> tableBotIds, UUID botId, int scale, int downOffsetTier) {
        int variant = botSkinVariant(tableBotIds, botId);
        if (variant < 0) {
            return null;
        }
        return miniMessageFor(BOT_SKIN_URLS.get(variant), scale, downOffsetTier);
    }

    /**
     * 按皮肤 URL 取像素头像。真人与机器人共用的那一层。
     *
     * <p>缓存 key 是 {@code scale|tier|outline|url}，所以机器人那几个固定 URL 天然命中缓存 ——
     * 同一张皮肤在同一组配置下只渲染一次。
     */
    private String miniMessageFor(URL skinUrl, int scale, int downOffsetTier) {
        if (!offsetService.isAvailable()) {
            // 没有负空格就没法换行，画出来会是横向拉长的一条，不如不画。
            return null;
        }
        if (skinUrl == null) {
            // 没有自定义皮肤（离线模式或默认皮肤），交给调用方兜底。
            return null;
        }
        // 偏移档与描边色都进 key：它们变了字形串就不同，共用一条缓存会画错。
        int outlineArgb = plugin.getTrickHudAvatarOutlineArgb();
        String key = scale + "|" + downOffsetTier + "|" + outlineArgb + "|" + skinUrl;
        String cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (pending.putIfAbsent(key, Boolean.TRUE) == null) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    BufferedImage skin = downloadSkin(skinUrl);
                    if (skin != null) {
                        int[][] head = extractHead(skin);
                        if (outlineArgb != 0) {
                            head = withOutline(head, outlineArgb);
                        }
                        cache.put(key, renderMiniMessage(head, scale, offsetService::offset, downOffsetTier));
                    }
                } catch (Exception exception) {
                    plugin.getLogger().warning("Failed to render player head: " + exception.getMessage());
                } finally {
                    pending.remove(key);
                }
            });
        }
        return null;
    }

    /** 玩家换皮肤或退出时清掉缓存条目。key 带 URL，所以这里按前缀之外的方式无法精确定位，直接整表按 URL 清。 */
    public void invalidate(Player player) {
        URL skinUrl = skinUrlOf(player);
        if (skinUrl == null) {
            return;
        }
        String suffix = "|" + skinUrl;
        cache.keySet().removeIf(key -> key.endsWith(suffix));
    }

    /**
     * 把 8x8 的头部像素渲染成 MiniMessage。
     *
     * <p>输出结构：逐行输出 8 个方块字形，每个套一层 {@code <color:#rrggbb>}；
     * 一行画完用负偏移把光标拉回行首，再画下一行。最后一行【不回退】，
     * 这样后面接着拼牌面字形就正好落在头像右边。
     *
     * <p>透明像素不画方块，改用一个正偏移空过去，保证列对齐不错位。
     *
     * @param head           [row][col] 的 ARGB 像素，row=0 是头顶那一行
     * @param scale          放大倍数，决定用哪一套方块字形
     * @param offsetProvider 给定像素数返回一段水平偏移文本；测试里可以注入假的
     */
    public static String renderMiniMessage(int[][] head, int scale, IntFunction<String> offsetProvider) {
        return renderMiniMessage(head, scale, offsetProvider, 0);
    }

    public static String renderMiniMessage(
        int[][] head, int scale, IntFunction<String> offsetProvider, int downOffsetTier) {
        // 行数取实际矩阵边长而不是 AVATAR_HEAD_PIXELS：加了描边就是 10x10。
        int rows = head.length;
        // 【列距是 scale，不是 scale + 1】：字形前进 scale + GLYPH_TRAILING_SPACING，
        // 多出来的那 1 像素必须逐个抵掉，否则每两列之间露一道透明缝（见该常量的说明）。
        int rowWidth = rows * scale;
        StringBuilder builder = new StringBuilder();
        // 方块字形挂在自己的字体上（见 PackAssets.AVATAR_PIXEL_FONT），必须套标签才有字形。
        // 整个头像包一次：8x8 放大后有上百个方块，逐个包标签会把这段文本撑到离谱。
        builder.append("<font:").append(PackAssets.AVATAR_PIXEL_FONT).append('>');
        // 待输出的水平偏移，攒着不立刻写。攒的意义是把三种偏移合成一段再输出：
        // 字间距抵消（-1）、透明像素占位（+scale）、换行回退（-rowWidth）。
        // 一段 CE 偏移片段光是 <font:minecraft:default></font> 这层包装就要 31 个字符，
        // 相邻两段合并能省下整整一层，实测整张 10x10 头像的文本从 5931 降到 5643。
        int pending = 0;
        for (int row = 0; row < rows; row++) {
            String glyph = PackAssets.avatarPixelChar(scale, row, downOffsetTier);
            for (int col = 0; col < rows; col++) {
                int argb = head[row][col];
                if (((argb >>> 24) & 0xFF) < ALPHA_THRESHOLD) {
                    // 透明像素不画方块，但必须占满一整个列距，否则该行右边的像素全体左移。
                    pending += scale;
                    continue;
                }
                if (pending != 0) {
                    builder.append(offsetProvider.apply(pending));
                    pending = 0;
                }
                builder.append("<color:#")
                    .append(String.format("%06x", argb & 0xFFFFFF))
                    .append('>')
                    .append(glyph)
                    .append("</color>");
                // 方块本身让光标走了 scale + 1，把多出的字间距记进待输出偏移，
                // 下一列（或换行、或整段收尾）时一并抵掉，列距就正好是 scale。
                pending -= GLYPH_TRAILING_SPACING;
            }
            if (row < rows - 1) {
                pending -= rowWidth;
            }
        }
        // 收尾必须把攒下的偏移吐干净：最后一行末尾那个 -1 若不输出，
        // 净前进量就比 advanceWidth 多 1 像素，槽内居中会整体偏半像素、并逐槽累积。
        if (pending != 0) {
            builder.append(offsetProvider.apply(pending));
        }
        builder.append("</font>");
        return builder.toString();
    }

    /**
     * 从整张皮肤里取出头部正面的 8x8 像素，并把 hat 层（帽子、头发）叠在上面。
     *
     * <p>不叠 hat 的话，戴帽子或有蓬松发型的皮肤会显示成光头，和原版玩家头颅不一致。
     */
    public static int[][] extractHead(BufferedImage skin) {
        int size = PackAssets.AVATAR_HEAD_PIXELS;
        int[][] head = new int[size][size];
        boolean hasHatLayer = skin.getWidth() >= HEAD_HAT_X + size && skin.getHeight() >= HEAD_HAT_Y + size;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int argb = skin.getRGB(HEAD_BASE_X + col, HEAD_BASE_Y + row);
                if (hasHatLayer) {
                    int hat = skin.getRGB(HEAD_HAT_X + col, HEAD_HAT_Y + row);
                    if (((hat >>> 24) & 0xFF) >= ALPHA_THRESHOLD) {
                        argb = hat;
                    }
                }
                head[row][col] = argb;
            }
        }
        return head;
    }

    private URL skinUrlOf(Player player) {
        PlayerTextures textures = player.getPlayerProfile().getTextures();
        return textures.getSkin();
    }

    private BufferedImage downloadSkin(URL url) throws Exception {
        URLConnection connection = url.openConnection();
        // 皮肤站偶发慢响应，别让异步线程无限期挂着。
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (InputStream stream = connection.getInputStream()) {
            return ImageIO.read(stream);
        }
    }
}
