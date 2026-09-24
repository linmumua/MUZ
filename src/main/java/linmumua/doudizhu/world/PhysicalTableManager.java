package linmumua.doudizhu.world;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.compat.VersionCompat;
import linmumua.doudizhu.game.SimpleBotBrain;
import linmumua.doudizhu.game.GamePhase;
import linmumua.doudizhu.game.GameTable;
import linmumua.doudizhu.game.PlayerRole;
import linmumua.doudizhu.game.TableManager;
import linmumua.doudizhu.game.PlayerOutputDispatcher;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.room.TableLevel;
import linmumua.doudizhu.scheduler.RegionTaskBarrier;
import linmumua.doudizhu.ui.MuzTheme;
import linmumua.doudizhu.ui.TypewriterTextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class PhysicalTableManager {
    /** 牌桌实体的不可变归属快照，供后续 region/entity owner 路由使用。 */
    public record TableOwner(UUID id, String name, String tableName, long generation) {
        public TableOwner {
            name = name == null || name.isBlank() ? null : name;
            tableName = tableName == null || tableName.isBlank()
                ? null
                : tableName.trim().toLowerCase(Locale.ROOT);
            if (tableName == null) {
                throw new IllegalArgumentException("牌桌实体 owner 缺少桌标识");
            }
            if (generation <= 0L) {
                throw new IllegalArgumentException("牌桌实体 owner 缺少有效代次");
            }
        }
    }

    private static final String ENTITY_ROLE_UNKNOWN = "unknown";
    private static final String ENTITY_ROLE_TABLE = "table";
    private static final String ENTITY_ROLE_CHAIR = "chair";
    private static final String ENTITY_ROLE_ACTION_LABEL = "action-label";
    private static final String ENTITY_ROLE_ACTION_HITBOX = "action-hitbox";
    private static final String ENTITY_ROLE_CARD = "card";
    private static final String ENTITY_ROLE_CARD_LABEL = "card-label";
    private static final String ENTITY_ROLE_CARD_CAPTURER = "card-capturer";
    private static final String ENTITY_ROLE_CARD_EDGE = "card-edge";
    private static final String ENTITY_ROLE_DEBUG_PANEL = "debug-panel";

    /** Hover/点击仅缩小命中触发范围，不改变牌面显示尺寸。 */
    private static final double HAND_CARD_HIT_AREA_SCALE = 0.90;
    // 桌椅与按钮属于世界里的公共实体；手牌和个人按钮则是按玩家隐藏/显示的私有实体
    // 这个常量只用于「牌桌自有实体」的归属判定（写入 tag 与残留清理），tag 字符串本身
    // 登记在 TableEntityGeometry.PROTECTED_TAGS；isProtectedEntity 走的是共享保护链，
    // 除牌桌实体外还认麻将 tag，见那里的注释。
    private static final String PROTECTED_ENTITY_TAG = TableEntityGeometry.TABLE_PROTECTED_TAG;

    /** 牌桌实体清理屏障的超时 tick；只用于观察聚合结果，绝不在调用线程上阻塞等待。 */
    private static final long CLEANUP_BARRIER_TIMEOUT_TICKS = 100L;

    /**
     * 当前是否运行在 Folia 这类区域化核心上（只算一次）。
     *
     * <p><b>为什么必须按核心类型分支</b>：同一处「锚点区块就绪」逻辑在两类核心上合法且正确的
     * 实现是不同的——Paper/Leaf 的**主线程允许同步加载区块**，牌桌的放置/重建路径
     * （{@code placeNewTableInternal} → {@code spawnTable}、{@code rebuildAllTables} /
     * {@code rebuildSingleTable} / {@code shiftAllAnchors} → {@code cleanupPlacedTable} →
     * {@code spawnTable}）本就依赖「执行前锚点邻域已加载」这一保证：区块未加载时实体与方块操作
     * 会静默失效，结果是把旧实体清掉却没能重建，整桌桌椅按钮凭空消失（真实 Leaf 26.1.2 实服复现）。
     * 而 Folia 的区域线程在**任何**上下文——主线程与 region 线程——都禁止同步区块获取，
     * 抛 {@code IllegalArgumentException("Async chunk retrieval")}，只能走异步加载 + region 投递。
     * 因此不能只留一条实现：留同步会在 Folia 上永久失败，留只读检查会在 Paper/Leaf 上丢整桌实体。
     *
     * <p><b>判定方式</b>已收口到 {@link RegionizedCoreDetector}：优先官方品牌接口
     * {@code ServerBuildInfo.buildInfo().isBrandCompatible(Key.key("papermc","folia"))}，
     * 品牌不可用时才回退到**只认 {@code RegionizedServer}** 的类存在性。判定细节、现场两份内核
     * 的判别力证据与失败策略都写在那个类里。
     *
     * <p><b>历史纠错（2026-09-23）</b>：本字段曾经是 {@code detectRegionizedCore()}，用
     * {@code RegionizedServer <b>或</b> TickRegions} 的类存在性判定。该判据是错的——
     * 现场 {@code leaf-26.1.2.jar} 打了一个 484 字节的 {@code TickRegions} 兼容桩却没有
     * {@code RegionizedServer}，于是 Leaf 被误判成区域化核心，同步恢复/预热分支根本不执行。
     * 现在删掉了 TickRegions 判据。
     */
    private static final boolean REGIONIZED = RegionizedCoreDetector.isRegionized();

    /**
     * 最近一次 reload 关闭清理的完成屏障。
     *
     * <p>只观察不阻塞，与 {@code MahjongTableManager.shutdownCompletion()} 同口径：
     * Paper 后端把 region 任务排到主线程，{@code onDisable} 在主线程上阻塞等待会把任务本身饿死，
     * Folia 下未加载 region 更是永远不会返回。未关闭时保持已完成空 future。
     */
    private volatile CompletableFuture<RegionTaskBarrier.Result> shutdownCompletion =
        CompletableFuture.completedFuture(null);
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final float DEFAULT_PRIVATE_CARD_RENDER_SCALE = 0.50f;
    private static final int CARD_HOVER_GRACE_TICKS = 2;
    private static final int CARD_HOVER_SWITCH_TICKS = 1;

    /**
     * 动画曲线的过冲上界：BACK_OUT 会冲过目标值再回落，这里是它的钳位上限。
     *
     * <p>判定包络必须按这个上界算抬升，否则牌上沿会在过冲那一瞬冲出判定区。
     */
    private static final float MAX_ANIMATION_OVERSHOOT = 1.15f;

    /**
     * 当前动画曲线真实能达到的进度上界。
     *
     * <p>只有 {@code BACK_OUT} 会冲过目标值（{@code backOut} 内部就钳在
     * {@link #MAX_ANIMATION_OVERSHOOT}），另外三条曲线
     * （{@code LINEAR} / {@code EASE_OUT} / {@code EASE_IN_OUT}）峰值<b>恰好是 1.0</b>：
     * {@code easeOutCubic(1)=1}、{@code linear} 先钳到 [0,1]、{@code easeInOutCubic(1)=1}。
     *
     * <p>包络按这个上界算抬升，而不是无条件乘 1.15。默认曲线是
     * {@code animation-type: 1}（{@code EASE_OUT}），无条件乘 1.15 等于凭空把判定区
     * 上沿抬高 {@code lift × 0.15} —— 默认配置下是 0.06×0.15 = <b>0.009 格</b>白送的空气。
     * 这不是安全余量：那 15% 只有 BACK_OUT 用得上，其余曲线的牌永远到不了那个高度，
     * 却让所有玩家都得为它多出一截「牌上方空气也能选中」。
     *
     * @return BACK_OUT 返回 1.15，其余返回 1.0
     */
    private float animationOvershootBound() {
        return plugin.cardHoverAnimationCurve() == DoudizhuPlugin.AnimationCurve.BACK_OUT
            ? MAX_ANIMATION_OVERSHOOT
            : 1.0f;
    }

    /** 通用的位移死区（距离平方）：位移小于 0.02 格的实体不重新传送，省掉大量无意义的同步包。 */
    private static final double DEFAULT_TELEPORT_EPSILON_SQUARED = 0.0004;

    /**
     * 手牌链路专用的位移死区（距离平方），约 1e-5 格。
     *
     * <p>牌、判定框、悬浮标签必须用同一个死区：只要三者阈值不同，就会出现一方动了另一方没动，
     * 判定框相对牌漂移。而且压层深度是 0.005 量级、远小于通用死区 0.02 格，
     * 用通用死区会让它在更新时被整体吃掉，只在生成瞬间生效。
     */
    private static final double CARD_TRACK_EPSILON_SQUARED = 1.0e-10;

    private static final float TEXT_DISPLAY_PIXELS_PER_BLOCK = 40.0f;
    private static final int DEFAULT_FONT_LINE_HEIGHT_PIXELS = 9;
    // 字形之间的 1 像素间隙不是墨迹。advance 里带着它，末字之后那一格必须扣掉，
    // 否则判定框会比文字宽出一小截。
    private static final int GLYPH_SPACING_PIXELS = 1;
    // 放宽到包内可见，供 HandCardArbitrationVerdictTest 锁住
    // 「按钮范围 < 手牌范围」这个前提；按钮让位的距离条件依赖它才有意义。
    static final double MAX_ACTION_INTERACTION_DISTANCE = 3.0;
    /**
     * 手牌解析拾取的射程（格）。
     *
     * <p>按产品要求，左右键点牌<b>不设距离限制</b>：只要准星落在牌上就算命中。
     * 这里仍是个有限值而不是无穷，只因为它同时用作视线阻挡射线的长度
     * （见 {@code pickHandCard}）——{@code rayTraceBlocks} 需要有限距离，
     * 而阻挡射程必须 ≥ 拾取射程，否则超出阻挡射程的那一段就能穿墙选牌。
     *
     * <p>取 256 格：等于原版最大可视距离量级，远超任何一张桌子能被看到的距离，
     * 实际等价于「无限制」。真正的上限由客户端决定——原版只在约 3～4.5 格内
     * 发交互包，更远的点击服务端收不到任何包，这个常量再大也无法越过那道墙。
     * 所以调大它不会带来「隔着半个世界点牌」的行为，只是把本插件这一侧的闸门撤掉。
     */
    static final double MAX_HAND_CARD_PICK_DISTANCE = 256.0;

    /**
     * 桌心悬浮头像被删除前 render.status-avatar-offset.vertical 的默认值。
     *
     * <p>那个 ItemDisplay 和它的配置键都已移除，但升级前生成的实体还留在世界里，
     * 而持久化不存实体 id，只能靠 purgeResidualWorldArtifacts 的坐标扫回收。
     * 这个常量就是为了算出它当年的悬挂高度，别当成还能调的参数。
     */
    private static final double RETIRED_STATUS_AVATAR_VERTICAL_OFFSET = 0.82;
    /**
     * 锚点到支撑方块上表面的高度。
     *
     * 放桌锚点取的是支撑方块自己的坐标，也就是那一格的**底面**，所以锚点本身埋在方块里。
     * 手牌（1.23）和按钮（1.02）的默认高度都大于 1，等于早就把这一格补进去了；
     * 桌面（0.55）和椅子（0.20）没补，于是桌椅和预览粒子都陷在地里。
     * 这里统一把桌椅抬到上表面，锚点语义保持不变，已存库的牌桌不用迁移。
     */
    static final double SUPPORT_SURFACE_LIFT = 1.0;
    /**
     * 放桌检测用的桌面水平半径。
     * 桌子模型是 2.5x2.5 格，半径 1.25；留一点余量取 1.20，
     * 避免贴着整格边界的墙把正常放置也判成被挡。
     * 这个值同时被 placementObstruction 和 placementBlockedBlocks 使用，
     * 必须共用常量：两处写成不同数字会导致"提示被挡但高亮不出方块"。
     */
    // 以下放桌检测常量放宽到包级可见，供 PlacementSurfaceClampTest 直接引用。
    // 之前测试各自抄了一份数字（抄的还是早已过时的 0.95 半径），
    // 生产值改动时测试不会失败，检测几何因此长期脱节。
    static final double TABLE_PLACEMENT_RADIUS = 1.20;
    /** 放桌检测用的桌面竖直范围，相对桌面中心。 */
    static final double TABLE_PLACEMENT_MIN_Y = -0.10;
    static final double TABLE_PLACEMENT_MAX_Y = 0.95;
    /** 放桌检测用的椅子尺寸。椅子模型 0.875 宽、1.56 高。 */
    static final double CHAIR_PLACEMENT_RADIUS = 0.55;
    static final double CHAIR_PLACEMENT_MIN_Y = -0.10;
    static final double CHAIR_PLACEMENT_MAX_Y = 1.05;
    // 叫分阶段只保留四个叫分按钮；明牌按钮仅在独立 REVEALING 窗口出现。
    private static final List<ActionButtonState> BIDDING_BUTTONS_ONLY = List.of(
        new ActionButtonState("bid", "不叫", ButtonAction.BID_0, -0.96),
        new ActionButtonState("bid", "叫1分", ButtonAction.BID_1, -0.32),
        new ActionButtonState("bid", "叫2分", ButtonAction.BID_2, 0.32),
        new ActionButtonState("bid", "叫3分", ButtonAction.BID_3, 0.96)
    );
    /** 每张牌桌独立记录桌边动态的刷新桶；owner tick 不能共享全局节流状态。 */
    private final Map<String, Long> playDetailLastRefreshBucketByTable = new LinkedHashMap<>();
    /**
     * 每桌因 lane 不归属而跳过世界体刷新的累计次数，只用于限频日志。
     *
     * <p>键是归一化桌名，值只增不减；表大小受牌桌数量限制（不是按 tick 增长），所以不需要清理。
     * 由多个 owner lane 与 global lane 并发读写，必须是并发容器。
     */
    private final Map<String, Long> worldBodySkipCounters = new ConcurrentHashMap<>();
    /** 世界体跳过日志的限频间隔：每 N 次跳过最多记一条。 */
    private static final long WORLD_BODY_SKIP_LOG_INTERVAL = 200L;

    private final DoudizhuPlugin plugin;
    /** 连接生命周期维护的不可变 UUID 快照；region owner 不直接枚举 Player。 */
    private final PlayerPresenceRegistry playerPresence;
    /** 玩家输出统一经 UUID 快照投递到 player lane，region owner 不直接触碰玩家 API。 */
    private final PlayerOutputDispatcher playerOutput;
    /** 世界体 lane 归属判定；生产实现直接问 Bukkit，测试替身可指定"当前线程不拥有该锚点"。 */
    private final WorldBodyLane worldBodyLane;
    /** 实体 owner 的持久化键；仅 MUZ 自有实体写入，CraftEngine 家具不伪造这些字段。 */
    private final NamespacedKey entityOwnerKey;
    private final NamespacedKey entityOwnerNameKey;
    private final NamespacedKey entityTableKey;
    private final NamespacedKey entityRoleKey;
    private final NamespacedKey entityGenerationKey;
    private final Map<String, Long> tableGenerationByName = new LinkedHashMap<>();
    private final Map<String, PlacedTable> placedTables = new LinkedHashMap<>();
    /** 每个牌桌锚点的 3x3 footprint；这里只做运行期索引，不伪造区块事件屏障。 */
    private final Map<ChunkOwnerKey, Set<String>> tableNamesByFootprintChunk = new LinkedHashMap<>();
    private final Map<String, Set<ChunkOwnerKey>> footprintChunksByTable = new LinkedHashMap<>();
    /** 区块连续加载会产生多个事件；同一逻辑桌同一时刻只允许一个 owner 修复任务。 */
    private final Set<String> chunkLoadRepairQueued = new LinkedHashSet<>();
    /** 单桌清理代次；barrier 完成后必须仍匹配，避免旧结果重建新桌。 */
    private final Map<String, Long> cleanupEpochByTable = new LinkedHashMap<>();
    /** 已经捕获 cleanup plan 或运行 barrier 的桌，禁止重复 ChunkLoad 创建第二个 barrier。 */
    private final Set<String> activeChunkLoadRepairs = new LinkedHashSet<>();
    /**
     * 在飞重建的桌（归一化桌名）。
     *
     * <p>重建不再把 {@code placedTables} / footprint 索引 / 实体索引整批摘除：旧放置快照会一直保留到
     * global 收口**成功提交**为止。旧状态在替换成功前绝不能丢——否则窗口期 {@code TableManager.cleanupIfEmpty}
     * 会把空桌当成"已拆"注销（永久丢桌），重建失败也会连旧桌一起丢掉。
     *
     * <p>本集合同时承担两个职责：
     * <ul>
     *   <li>同桌重建互斥：旧逻辑靠"先把旧桌从 {@code placedTables} 摘掉再重建"实现天然互斥，现在改成不动
     *       旧状态，就必须显式互斥——标记还在时第二次重建直接让出；</li>
     *   <li>世界体门禁：重建在飞时旧实体马上要被替换，{@code refresh} / {@code tickTable} 这类会按旧
     *       {@code PlacedTable} 继续生成实体的路径必须让位（见 {@link #placedTableForWorldBody}），
     *       否则会在窗口里造出无人跟踪的孤儿实体。</li>
     * </ul>
     * 标记由调用 lane 写入、由多个 owner lane 读取，因此必须是并发容器。
     */
    private final Set<String> rebuildingTableKeys = ConcurrentHashMap.newKeySet();
    /** 残留清理的邻桌保护按实体 UUID O(1) 查询，不再扫描 placedTables。 */
    private final Map<UUID, TableOwner> trackedEntityOwnersById = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> indexedEntityIdsByTable = new LinkedHashMap<>();
    private final Map<UUID, ActionBinding> actionBindings = new LinkedHashMap<>();
    private final Map<UUID, CardBinding> cardBindings = new LinkedHashMap<>();
    private final Map<UUID, Integer> hintIndices = new LinkedHashMap<>();
    private final Map<UUID, Integer> hoveredCardIds = new LinkedHashMap<>();
    /** 上一次真正当成手牌点击处理掉的 tick，用来吞掉同一次右键的主手/副手重复事件。 */
    private final Map<UUID, Long> lastHandCardClickTicks = new LinkedHashMap<>();
    private final Map<UUID, Integer> hoverCandidateCardIds = new LinkedHashMap<>();
    private final Map<UUID, Integer> hoverCandidateTicksByViewer = new LinkedHashMap<>();
    private final Map<UUID, Integer> hoverGraceTicksByViewer = new LinkedHashMap<>();
    private final Map<UUID, Map<Integer, Float>> hoverProgressByPlayer = new LinkedHashMap<>();
    private final Map<UUID, Map<Integer, Float>> selectedProgressByPlayer = new LinkedHashMap<>();
    private final Map<String, String> actionSignatureByTable = new LinkedHashMap<>();
    private final Map<String, Map<UUID, String>> privateHandSignatureByTable = new LinkedHashMap<>();
    private final Map<String, Map<UUID, String>> backsideHandSignatureByTable = new LinkedHashMap<>();
    /** 开局逐批发牌与原地翻面的固定槽位展示缓存。 */
    private final Map<String, HandDealPresentation> handDealPresentations = new LinkedHashMap<>();
    /**
     * 「捕获器可能挡住按钮」这条告警是否已经喊过。
     *
     * <p>铺牌在出牌链路上是高频的，不去重会把控制台冲掉。见
     * {@link #warnIfCapturerCouldOccludeButtons}。
     */
    private boolean capturerOcclusionWarned;
    /** 开启判定区可视化的玩家。线框只对他自己可见。 */
    private final Set<UUID> pickDebugViewers = new LinkedHashSet<>();
    /** 判定区面板实体池，按玩家。只 teleport 复用，不每 tick 重建，见 refreshPickDebug。 */
    private final Map<UUID, List<UUID>> pickDebugPool = new LinkedHashMap<>();
    /** 上一帧线框对应的场景签名。签名不变就整帧跳过，静止时零开销。 */
    private final Map<UUID, String> pickDebugSignatures = new LinkedHashMap<>();

    // ---- 运行时追踪（/muz debug trace，诊断完可整段移除） ----
    /** 开了 /muz debug trace 的玩家。追踪消息只发给他自己，避免刷屏。 */
    private final Set<UUID> traceViewers = new LinkedHashSet<>();
    /**
     * 追踪日志相对插件根目录的路径，同时用作命令反馈里告诉玩家的位置。
     *
     * <p>写死不进 config：这是诊断开关的附属产物，不是给服务主调的参数；
     * 加配置项只会让「文件到底在哪」多出一个需要核对的地方。
     */
    public static final String TRACE_LOG_RELATIVE_PATH = "plugins/MUZ/debug/trace.log";
    /**
     * 追踪日志的大小上限（2 MB）。超过就轮转成 trace.log.1，只留一代。
     *
     * <p>trace 是每次点击都打的高频路径，不设上限时一场长时间排查就能写出几百 MB。
     * 只留一代是够的：排查看的都是刚发生的那几十行，更早的历史没有价值。
     */
    private static final long TRACE_LOG_MAX_BYTES = 2L * 1024 * 1024;
    /**
     * 待落盘的追踪行缓冲。主线程只往里 add（纯内存，不碰 IO），异步任务整批取走。
     *
     * <p>每条消息 open/close 一次文件会把阻塞 IO 直接塞进点击链路，
     * 所以主线程侧只允许做这一次 list.add。
     */
    private final List<String> traceLineBuffer = new ArrayList<>();
    /**
     * 是否已经有一个异步 flush 在排队。
     *
     * <p>用它做合并：一次点击会连打好几行 trace，没有这个标志就会调度好几个
     * 异步任务去抢同一个文件。有了它，一串消息只落一次盘。
     */
    private boolean traceFlushScheduled;
    /** 异步 flush 复用的 writer。追加模式常驻，省掉每批一次的 open/close。 */
    private java.io.Writer traceWriter;
    /** 写盘失败是否已经在控制台喊过。高频路径上每条都 warn 会把控制台冲掉，只喊一次。 */
    private boolean traceWriteFailureWarned;
    /** 追踪行的时间戳格式。到毫秒：同一 tick 内的多行要能看出先后。 */
    private static final java.time.format.DateTimeFormatter TRACE_TIME_FORMAT =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    // ---- 追踪结束 ----

    /**
     * 连接生命周期维护的最小 UUID presence registry。
     *
     * <p>只保存不可变 UUID 快照，供牌桌 owner lane 使用；连接监听器可通过
     * {@link #markPlayerConnected(UUID)} / {@link #markPlayerDisconnected(UUID)} 接入，
     * 不需要把 Player 带进世界实体刷新链路。
     */
    static final class PlayerPresenceRegistry {
        private final LinkedHashSet<UUID> connected = new LinkedHashSet<>();
        private volatile List<UUID> snapshot = List.of();

        PlayerPresenceRegistry() {
        }

        PlayerPresenceRegistry(Collection<UUID> initialPlayerIds) {
            if (initialPlayerIds != null) {
                for (UUID playerId : initialPlayerIds) {
                    if (playerId != null) {
                        connected.add(playerId);
                    }
                }
                snapshot = List.copyOf(connected);
            }
        }

        synchronized void markConnected(UUID playerId) {
            if (playerId != null && connected.add(playerId)) {
                snapshot = List.copyOf(connected);
            }
        }

        synchronized void markDisconnected(UUID playerId) {
            if (playerId != null && connected.remove(playerId)) {
                snapshot = List.copyOf(connected);
            }
        }

        List<UUID> snapshot() {
            return snapshot;
        }
    }

    /**
     * 世界体 lane 归属判定的可注入接缝。
     *
     * <p>【为什么需要这一层】{@code Bukkit.isOwnedByCurrentRegion(Location)} 是静态调用，单测里
     * 没有运行中的服务端可控制，因此把判定收口到接口后面，让行为测试能指定"当前线程**不**拥有该锚点"。
     * 写法与 {@code TableGadgetEffectService.EffectRuntime} 一致；生产实现只调 Bukkit 公开 API。
     *
     * <p>【判定的语义】区域化核心（Folia/Lophine）上，只有持有该锚点 region 的线程才能读写这块区域里的
     * 实体与世界状态；其余 lane（尤其是 global，实服日志里表现为 {@code region={null}}）读实体状态会直接抛
     * {@code Accessing entity state off owning region's thread}。Paper/Leaf 这类非区域化核心没有 region
     * 概念，{@code Bukkit.isOwnedByCurrentRegion} 恒真，因此判定不会误杀单线程核心的正常刷新。
     */
    interface WorldBodyLane {
        /** 当前线程是否拥有该锚点所在 region；锚点为 null 时返回 false（没有可归属的世界体）。 */
        boolean isOwnedByCurrentRegion(Location anchor);

        /** 当前线程是否拥有该实体所在 region；实体为 null 时返回 false。 */
        boolean isOwnedByCurrentRegion(Entity entity);

        /** 按 UUID 解析 tracked 实体；不存在时返回 null。 */
        Entity resolveEntity(UUID entityId);
    }

    /** 生产边界：直接使用 Bukkit 的 region 归属判定。 */
    private static final class BukkitWorldBodyLane implements WorldBodyLane {
        // 门禁自身绝不抛异常：Location.getWorld() 在世界已卸载时会抛 IllegalArgumentException("World unloaded")，
        // 而这些门禁跑在周期任务回调里，抛出会让调度器取消任务。拿不到归属一律按「不拥有」处理（安全侧：跳过世界体）。
        @Override
        public boolean isOwnedByCurrentRegion(Location anchor) {
            try {
                return anchor != null && anchor.getWorld() != null && Bukkit.isOwnedByCurrentRegion(anchor);
            } catch (RuntimeException failure) {
                return false;
            }
        }

        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            try {
                return entity != null && Bukkit.isOwnedByCurrentRegion(entity);
            } catch (RuntimeException failure) {
                return false;
            }
        }

        @Override
        public Entity resolveEntity(UUID entityId) {
            return Bukkit.getEntity(entityId);
        }
    }

    public PhysicalTableManager(DoudizhuPlugin plugin) {
        this(plugin, new PlayerPresenceRegistry(seedOnlinePlayerIds()));
    }

    /**
     * 仅在管理器装配时拍一次现存玩家 UUID，作为连接监听器接手前的初始在线集合。
     *
     * <p>owner tick 后续只读 {@link PlayerPresenceRegistry} 快照，不再枚举 Player；这里也不能假设
     * 存在运行中的服务端——单元测试会直接构造本管理器，此时 {@code Bukkit.getServer()} 为 null，
     * 必须退回空集合，而不是在构造器里抛 NPE。之后由连接监听器用
     * {@link #markPlayerConnected(UUID)} / {@link #markPlayerDisconnected(UUID)} 维护。
     */
    private static List<UUID> seedOnlinePlayerIds() {
        Server server = Bukkit.getServer();
        if (server == null) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (Player player : server.getOnlinePlayers()) {
            ids.add(player.getUniqueId());
        }
        return ids;
    }

    PhysicalTableManager(DoudizhuPlugin plugin, PlayerPresenceRegistry playerPresence) {
        this(plugin, playerPresence, new BukkitWorldBodyLane());
    }

    /**
     * 测试注入点：允许替身指定"当前线程是否拥有锚点 region"，从而在没有服务端的环境里
     * 驱动世界体门禁。生产装配只走 {@link #PhysicalTableManager(DoudizhuPlugin)}。
     */
    PhysicalTableManager(DoudizhuPlugin plugin, PlayerPresenceRegistry playerPresence, WorldBodyLane worldBodyLane) {
        this.plugin = plugin;
        this.playerPresence = Objects.requireNonNull(playerPresence, "playerPresence");
        this.worldBodyLane = Objects.requireNonNull(worldBodyLane, "worldBodyLane");
        this.playerOutput = new PlayerOutputDispatcher(plugin);
        this.entityOwnerKey = new NamespacedKey(plugin, "table-owner");
        this.entityOwnerNameKey = new NamespacedKey(plugin, "table-owner-name");
        this.entityTableKey = new NamespacedKey(plugin, "table-name");
        this.entityRoleKey = new NamespacedKey(plugin, "table-role");
        this.entityGenerationKey = new NamespacedKey(plugin, "table-generation");
    }

    /** 连接生命周期注入点：只登记 UUID，不把 Player 带入 owner lane。 */
    public void markPlayerConnected(UUID playerId) {
        playerPresence.markConnected(playerId);
    }

    /** 连接生命周期注入点：只移除 UUID，不触碰 Bukkit Player。 */
    public void markPlayerDisconnected(UUID playerId) {
        playerPresence.markDisconnected(playerId);
    }

    public GameTable placeNewTable(Player owner, String name) {
        return placeNewTable(owner, name, TableLevel.FUN);
    }

    public GameTable placeNewTable(Player owner, String name, TableLevel roomLevel) {
        Location anchor = plugin.defaultTableAnchor(owner);
        return placeNewTableAt(owner, name, roomLevel, anchor, placementYaw(owner));
    }

    public GameTable placeNewTableAt(Player owner, String name, Location anchor, float yaw) {
        return placeNewTableAt(owner, name, TableLevel.FUN, anchor, yaw);
    }

    public GameTable placeNewTableAt(Player owner, String name, TableLevel roomLevel, Location anchor, float yaw) {
        return placeNewTableInternal(owner, name, roomLevel, anchor, yaw);
    }

    /**
     * 不依赖玩家在场地放一张牌桌，供控制台排查渲染与判定框问题
     * 注意这张桌子只活在内存里，不写持久化。reload 会照常重建它，但重启之后就没了，
     * 排查时别把"重启后这张桌没了"当成实体丢失的证据。
     * @param name 牌桌名
     * @param anchor 放置基准点
     * @param yaw 朝向
     * @return 创建出来的牌桌
     */
    public GameTable placeDiagnosticTable(String name, Location anchor, float yaw) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            throw new IllegalArgumentException("这儿已经有张桌子了。");
        }
        GameTable table = plugin.getTableManager().getTable(name);
        boolean newlyCreated = false;
        if (table == null) {
            table = plugin.getTableManager().createTable(name, plugin.defaultCreateRoomLevel());
            newlyCreated = true;
        }
        try {
            // 和恢复牌桌走同一条清场逻辑，否则诊断桌测不出残留实体有没有被收掉。
            ensureChunkReady(anchor);
            purgeResidualWorldArtifacts(anchor, yaw);
            putPlacedTable(key, spawnTable(table, anchor.clone(), yaw, null, "console"));
            notifyTableAnchorBinding(table);
        } catch (RuntimeException | Error failure) {
            rollbackPlacedTableAfterPlacementFailure(key, table, newlyCreated, failure);
            throw failure;
        }
        refresh(table);
        return table;
    }

    /**
     * 调试批量放桌的专用入口，仅绕过桌面/椅子方块占用检测（placementObstruction），
     * 保留重复桌名保护、区块预加载、残留实体清理与实体生成等完整流程。
     *
     * <p>与 {@link #placeNewTableAt} 的唯一区别是跳过了方块占用检测，使 {@code /muz debug add 99}
     * 等大批量调试放桌不会因为相邻桌位的方块碰撞而失败。正式入口 placeNewTableAt 不受影响，
     * 继续做完整的 placementObstruction 检测。
     *
     * <p>调试桌沿用持久化调用，但 {@code debug-} 名称会被插件持久化层隔离，因此不会写入数据库；
     * 该桌只在当前运行期和 reload 重建流程中存在，重启后不会恢复。若需要无 {@code debug-} 语义的诊断桌请用 {@link #placeDiagnosticTable}。
     *
     * @param owner 触发放桌的玩家（记录归属，并检查该玩家是否已在其他桌）
     * @param name 牌桌名
     * @param roomLevel 房间等级
     * @param anchor 放置基准点
     * @param yaw 朝向
     * @return 创建出来的牌桌
     */
    public GameTable placeDebugTableAt(Player owner, String name, TableLevel roomLevel, Location anchor, float yaw) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            throw new IllegalArgumentException("这儿已经有张桌子了。");
        }
        if (plugin.getTableManager().getTableOf(owner) != null) {
            throw new IllegalArgumentException("你已经坐在别的桌了。");
        }
        // 调试放桌仅跳过桌面/椅子方块占用检测：
        // 批量放桌时相邻桌位的桌面/椅子区域可能重叠，跳过这一项才能连续生成；
        // 玩家已在其他桌的保护仍然保留，避免调试命令把同一玩家同时挂到多张桌。
        GameTable table = plugin.getTableManager().getTable(name);
        boolean newlyCreated = false;
        if (table == null) {
            table = plugin.getTableManager().createTable(name, roomLevel);
            newlyCreated = true;
        }
        try {
            ensureChunkReady(anchor);
            purgeResidualWorldArtifacts(anchor, yaw);
            putPlacedTable(key, spawnTable(table, anchor.clone(), yaw, owner.getUniqueId(), owner.getName()));
            notifyTableAnchorBinding(table);
        } catch (RuntimeException | Error failure) {
            rollbackNewTableAfterPlacementFailure(table, newlyCreated, failure);
            throw failure;
        }
        plugin.persistDoudizhuTable(table.getName(), table.getRoomLevel(), anchor, yaw, owner.getUniqueId(), owner.getName());
        refresh(table);
        return table;
    }

    public float placementYaw(Player owner) {
        // HARD-CODED TABLE FACING:
        // The player's own side must stay open when placing a 斗地主 table.
        // We therefore flip the snapped facing by 180 degrees so the side nearest the placer is the missing-chair side.
        // Do not change this back unless the user explicitly asks for a different placement convention.
        return snappedYaw(owner.getLocation().getYaw() + 180.0f);
    }

    public Location placementAnchor(org.bukkit.block.Block floorBlock) {
        return floorBlock.getLocation().add(0.5, plugin.getTableSpawnOffsetY(), 0.5);
    }

    public Location previewTableCenter(Location anchor) {
        return anchor.clone().add(0.0, tableVisualHeight(), 0.0);
    }

    /**
     * 支撑方块上表面（玩家站立面）的世界 Y 坐标。
     *
     * 锚点是 placementAnchor 里叠了 tableSpawnOffsetY 之后的结果，而这个偏移是负的，
     * 所以锚点落在支撑方块**内部**；先减掉偏移还原成方块底面，再加一格才是上表面。
     * 放桌阻挡检测拿它当扫描下界，避免把地板自己算成障碍物。
     * @param anchor 放桌锚点
     * @return 站立面的世界 Y 坐标
     */
    private double supportSurfaceY(Location anchor) {
        return anchor.getY() - plugin.getTableSpawnOffsetY() + SUPPORT_SURFACE_LIFT;
    }

    /**
     * 桌面相对锚点的高度，已含支撑方块那一格。
     * @return 配置的桌面高度加上表面补偿
     */
    private double tableVisualHeight() {
        return plugin.getTableDisplayHeight() + SUPPORT_SURFACE_LIFT;
    }

    /**
     * 椅子底座相对锚点的高度，已含支撑方块那一格。
     * previewOpenSide 不走 chairVisualAdjustment，所以单独用这个方法补齐同一基准。
     * @return 配置的椅子高度加上表面补偿
     */
    private double chairVisualHeight() {
        return plugin.getChairBaseHeight() + SUPPORT_SURFACE_LIFT;
    }

    public List<Location> previewChairBases(Location anchor, float yaw) {
        List<Location> seats = new ArrayList<>(3);
        for (int index = 0; index < 3; index++) {
            seats.add(chairLocation(anchor, yaw, index));
        }
        return seats;
    }

    public Location previewOpenSide(Location anchor, float yaw) {
        return rotate(anchor, yaw, 0.0, chairVisualHeight(), plugin.getChairDistance());
    }

    /**
     * 返回放桌失败的玩家提示文案
     * @param anchor 放桌锚点
     * @param yaw 放桌朝向
     * @return 可以放置时返回 null，否则返回失败原因
     */
    public String placementObstructionReason(Location anchor, float yaw) {
        PlacementObstruction obstruction = placementObstruction(anchor, yaw);
        return obstruction == null ? null : obstruction.reason();
    }

    /**
     * 检测放桌区域的阻挡情况，返回第一个失败的详细结果
     * @param anchor 放桌锚点
     * @param yaw 放桌朝向
     * @return 可以放置时返回 null，否则返回带原因与被挡方块的结果
     */
    public PlacementObstruction placementObstruction(Location anchor, float yaw) {
        if (anchor == null || anchor.getWorld() == null) {
            return PlacementObstruction.ofReason("这里暂时还不能放牌桌。");
        }
        ensureChunkReady(anchor);
        PlacementObstruction tableObstruction = PlacementObstruction.detect(
            "桌面",
            blockPlacementLocation(previewTableCenter(anchor)),
            TABLE_PLACEMENT_RADIUS,
            TABLE_PLACEMENT_MIN_Y,
            TABLE_PLACEMENT_MAX_Y,
            supportSurfaceY(anchor)
        );
        if (tableObstruction != null) {
            return tableObstruction;
        }
        List<Location> chairBases = previewChairBases(anchor, yaw);
        for (int index = 0; index < chairBases.size(); index++) {
            PlacementObstruction chairObstruction = PlacementObstruction.detect(
                "椅子 " + (index + 1),
                blockPlacementLocation(chairBases.get(index)),
                CHAIR_PLACEMENT_RADIUS,
                CHAIR_PLACEMENT_MIN_Y,
                CHAIR_PLACEMENT_MAX_Y,
                supportSurfaceY(anchor)
            );
            if (chairObstruction != null) {
                return chairObstruction;
            }
        }
        return null;
    }

    /**
     * 收集放桌区域内所有被挡方块，供粒子高亮复用
     * @param anchor 放桌锚点
     * @param yaw 放桌朝向
     * @return 桌面与全部椅子的被挡方块整格坐标，去重后按检测顺序排列；区块未加载时返回空列表
     */
    public List<Location> placementBlockedBlocks(Location anchor, float yaw) {
        // 不能再用 anchor.getChunk().isLoaded()：Location#getChunk() 内部就是 World#getChunkAt，
        // 在真实 Folia 上会同步拉区块并抛 Async chunk retrieval。本方法经
        // WorldTableInteractionListener 的放桌预览（spawnTablePlacerPreview ← tickTablePlacerPreviews）
        // 跑在 player lane，属于可达路径，与上一轮修掉的 ensureChunkReady 是同类违规。
        // 改用只读的 world.isChunkLoaded(...)，语义不变：区块未加载时返回空列表（预览视为被阻挡）。
        if (anchor == null || anchor.getWorld() == null
            || !anchor.getWorld().isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
            return List.of();
        }
        Set<Location> blocked = new LinkedHashSet<>();
        blocked.addAll(PlacementObstruction.collectBlockingBlocks(
            blockPlacementLocation(previewTableCenter(anchor)),
            TABLE_PLACEMENT_RADIUS,
            TABLE_PLACEMENT_MIN_Y,
            TABLE_PLACEMENT_MAX_Y,
            supportSurfaceY(anchor)
        ));
        for (Location chairBase : previewChairBases(anchor, yaw)) {
            blocked.addAll(PlacementObstruction.collectBlockingBlocks(
                blockPlacementLocation(chairBase),
                CHAIR_PLACEMENT_RADIUS,
                CHAIR_PLACEMENT_MIN_Y,
                CHAIR_PLACEMENT_MAX_Y,
                supportSurfaceY(anchor)
            ));
        }
        return new ArrayList<>(blocked);
    }

    private GameTable placeNewTableInternal(Player owner, String name, TableLevel roomLevel, Location anchor, float yaw) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            throw new IllegalArgumentException("这儿已经有张桌子了。");
        }
        if (plugin.getTableManager().getTableOf(owner) != null) {
            throw new IllegalArgumentException("你已经坐在别的桌了。");
        }
        String obstruction = placementObstructionReason(anchor, yaw);
        if (obstruction != null) {
            throw new IllegalStateException(obstruction);
        }
        GameTable table = plugin.getTableManager().getTable(name);
        boolean newlyCreated = false;
        if (table == null) {
            table = plugin.getTableManager().createTable(name, roomLevel);
            newlyCreated = true;
        }
        try {
            putPlacedTable(key, spawnTable(table, anchor.clone(), yaw, owner.getUniqueId(), owner.getName()));
            notifyTableAnchorBinding(table);
        } catch (RuntimeException | Error failure) {
            rollbackNewTableAfterPlacementFailure(table, newlyCreated, failure);
            throw failure;
        }
        plugin.persistDoudizhuTable(table.getName(), table.getRoomLevel(), anchor, yaw, owner.getUniqueId(), owner.getName());
        refresh(table);
        return table;
    }

    /**
     * 从存档恢复一张牌桌。**按核心类型（{@link #REGIONIZED}）分支**。
     *
     * <p><b>非区域化核心（Paper/Leaf）</b>：主线程允许同步加载区块，也没有「世界体必须落在某
     * region」的约束，所以同步执行——先 {@link #ensureChunkReady} 强拉锚点区块（否则 spawn 的
     * 实体留不住），再在调用线程直接完成恢复。这样 onEnable 内即可完成恢复，恢复时序与旧版一致，
     * 不会把「已恢复 N 张」推迟到下一个 100 tick pass。返回已完成的 stage，调用方无需区分核心类型。
     *
     * <p><b>区域化核心（Folia）</b>：真实 Folia 26.1.2 上原地拉区块会抛
     * {@code IllegalArgumentException("Async chunk retrieval")}：{@code Location#getChunk()}
     * 内部走 {@code World#getChunkAt}，在 region 线程与主线程都非法。因此走两段式——
     * 「先异步把锚点区块拉起来，再把整套世界体（清残留方块/实体、CE 家具、桌椅文字生成）
     * 投递到锚点 region」。恢复流程本身位于 global lane（onEnable 与 5 秒重试定时器），
     * 所以世界体绝不能在调用线程原地执行。
     *
     * @return 世界体执行完毕后才完成的 stage，成功值是恢复出来的牌桌；
     *         加载或世界体失败时以异常完成，调用方必须处理，不能静默丢弃。
     */
    public CompletionStage<GameTable> restoreTable(String name, TableLevel roomLevel, Location anchor, float yaw, UUID ownerId, String ownerName) {
        String key = normalize(name);
        // 纯内存早退：桌已在运行期注册表里，没必要再走异步加载与 region 投递。
        if (placedTables.containsKey(key)) {
            return CompletableFuture.completedFuture(plugin.getTableManager().getTable(name));
        }
        ensureWorldVisualsReady("恢复牌桌");
        if (!REGIONIZED) {
            // 非区域化核心（Paper/Leaf）：同步执行，恢复旧版「onEnable 内即完成恢复」的时序。
            ensureChunkReady(anchor);
            GameTable table = restoreTableOnLoadedChunk(key, name, roomLevel, anchor, yaw, ownerId, ownerName);
            return CompletableFuture.completedFuture(table);
        }
        // 区域化核心（Folia）：先异步加载锚点区块，再经 thenCompose 把世界体投到锚点 region；
        // 绝不能在 global lane 上直接动世界（Folia 会抛 No currently ticking region / Async chunk retrieval）。
        return ensureChunkLoadedAsync(anchor).thenCompose(ignored ->
            runRegionStageValue(anchor, () ->
                restoreTableOnLoadedChunk(key, name, roomLevel, anchor, yaw, ownerId, ownerName)));
    }

    /**
     * 恢复流程中真正动世界的部分，只能在锚点 region 内部调用。
     *
     * <p>从 {@link #restoreTable} 拆出来，是为了把「世界体必须落在 anchor region」这条约束
     * 固定在一个方法体内：region 投递之前会被异步加载撑开几帧，期间桌可能已被其它路径放好，
     * 所以这里必须再查一次幂等早退，避免重复生成一整套实体。
     */
    private GameTable restoreTableOnLoadedChunk(String key, String name, TableLevel roomLevel, Location anchor, float yaw, UUID ownerId, String ownerName) {
        if (placedTables.containsKey(key)) {
            return plugin.getTableManager().getTable(name);
        }
        purgeResidualWorldArtifacts(anchor, yaw);
        GameTable table = plugin.getTableManager().getTable(name);
        boolean newlyCreated = false;
        if (table == null) {
            table = plugin.getTableManager().createTable(name, roomLevel);
            newlyCreated = true;
        } else {
            table.setRoomLevel(roomLevel);
        }
        try {
            putPlacedTable(key, spawnTable(table, anchor.clone(), yaw, ownerId, ownerName));
            notifyTableAnchorBinding(table);
        } catch (RuntimeException | Error failure) {
            rollbackPlacedTableAfterPlacementFailure(key, table, newlyCreated, failure);
            throw failure;
        }
        refresh(table);
        return table;
    }

    public boolean isPlaced(String tableName) {
        return placedTable(tableName) != null;
    }

    public int placedTableCount() {
        return placedTables.size();
    }

    /**
     * 是否有「已放置」或「重建在飞」的牌桌。
     *
     * <p>预热门禁不能只看瞬时 {@link #placedTableCount()}：重建改为异步流水线后，放置快照会在收口阶段
     * 短暂处于"尚未提交"的状态。用这个合并判据，只要本桌还登记在 {@code placedTables} 里、或还有一条
     * 重建在飞，门禁就不会误判成"没有桌可重建"而跳过后面的预热 pass。
     */
    public boolean hasPlacedOrRebuildingTables() {
        return !placedTables.isEmpty() || !rebuildingTableKeys.isEmpty();
    }

    /**
     * 世界体路径读取放置快照的唯一入口：重建在飞时返回 {@code null}，与"未放置"同一处理方式。
     *
     * <p>重建期间旧 {@code PlacedTable} 仍在 {@code placedTables} 里（见 {@link #rebuildingTableKeys}），
     * 但它的实体可能已经被旧锚点 region 清掉。{@code refresh} / {@code tickTable} 这类世界体路径若继续
     * 按旧快照生成实体，会造出既不在新桌、也不在旧桌的孤儿。因此这里统一让位，等收口提交后再按新桌工作。
     *
     * <p>重建流水线自己收尾的那次刷新**不走**这个门禁（它拿的就是刚提交的新桌，见
     * {@link #refreshWith(GameTable, PlacedTable)}）。
     */
    private PlacedTable placedTableForWorldBody(String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return null;
        }
        if (rebuildingTableKeys.contains(normalize(placed.tableName()))) {
            return null;
        }
        return placed;
    }

    public List<String> placedTableNames() {
        return placedTables.values().stream()
            .map(PlacedTable::tableName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    /**
     * 返回覆盖指定世界区块的牌桌名快照。
     *
     * <p>查询只读取本管理器维护的 3x3 footprint 索引，不暴露 {@code PlacedTable}，
     * 供区块生命周期监听器把事件转换成牌桌 owner 任务。
     *
     * @param worldId 世界 UUID
     * @param chunkX 区块 X
     * @param chunkZ 区块 Z
     * @return 不可变牌桌名快照
     */
    public List<String> tableNamesForFootprintChunk(UUID worldId, int chunkX, int chunkZ) {
        if (worldId == null) {
            return List.of();
        }
        Set<String> indexed = tableNamesByFootprintChunk.get(
            new ChunkOwnerKey(worldId, packedChunkKey(chunkX, chunkZ)));
        if (indexed == null || indexed.isEmpty()) {
            return List.of();
        }
        return indexed.stream()
            .map(placedTables::get)
            .filter(Objects::nonNull)
            .map(PlacedTable::tableName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    /**
     * 返回指定世界内当前已登记牌桌名的不可变快照。
     *
     * <p>这是世界卸载的接缝查询，不代表世界卸载屏障已完成，也不触碰实体。
     *
     * @param worldId 世界 UUID
     * @return 不可变牌桌名快照
     */
    public List<String> tableNamesInWorld(UUID worldId) {
        if (worldId == null) {
            return List.of();
        }
        return placedTables.values().stream()
            .filter(Objects::nonNull)
            .filter(placed -> placed.anchor().getWorld() != null
                && worldId.equals(placed.anchor().getWorld().getUID()))
            .map(PlacedTable::tableName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    /**
     * 区块卸载接缝：只把 footprint 受影响的逻辑桌切回 global/unplaced，保留实体与索引。
     *
     * @param worldId 世界 UUID
     * @param chunkX 区块 X
     * @param chunkZ 区块 Z
     */
    public void onChunkUnload(UUID worldId, int chunkX, int chunkZ) {
        for (String tableName : tableNamesForFootprintChunk(worldId, chunkX, chunkZ)) {
            plugin.getTableManager().markTableUnplaced(tableName);
        }
    }

    /**
     * 世界卸载接缝：只把该世界的逻辑桌切回 global/unplaced，保留实体与索引。
     *
     * @param worldId 世界 UUID
     */
    public void onWorldUnload(UUID worldId) {
        for (String tableName : tableNamesInWorld(worldId)) {
            plugin.getTableManager().markTableUnplaced(tableName);
        }
    }

    /**
     * 区块加载接缝：只排入受影响牌桌的 owner lane，实体检查和刷新不在 global 回调中执行。
     *
     * @param worldId 世界 UUID
     * @param chunkX 区块 X
     * @param chunkZ 区块 Z
     */
    public void onChunkLoad(UUID worldId, int chunkX, int chunkZ) {
        for (String tableName : tableNamesForFootprintChunk(worldId, chunkX, chunkZ)) {
            queueChunkLoadRepair(tableName);
        }
    }

    /**
     * 【为什么必须在排入修复之前先恢复锚点】{@code onChunkUnload} 的 {@code markTableUnplaced} 会把
     * 牌桌周期任务（含开局发牌 timer）切到 global，并清掉 {@code tableOwnerAnchors}。之后的修复
     * 依赖 {@code TableManager.runTableLater}，而它的 lane 选择**只看当时的 owner 锚点**：锚点为空
     * 就落 global，而 global 既没有 ticking region 也不拥有世界实体，一定会失败。
     *
     * <p>实服证据（Lophine 26.2，{@code region={null}}）：修复一失败（{@code captureCleanupPlan} 读实体抛
     * {@code Accessing entity state off owning region's thread}），{@code notifyTableAnchorBinding} 就永远
     * 不会被调用，桌子**永久停在 global**——随后开局发牌 timer 在 global 上写牌桌 TextDisplay，刷出 136 次
     * 同类堆栈（{@code CraftTextDisplay.text ← updateTextEntity ← refreshStatus ← refreshWith ← refresh}）。
     *
     * <p>放置快照 {@code placedTables} 在 unload 时**没有**被摘除（见 {@code markTableUnplaced} 的注释），
     * 锚点信息一直有效；区块重新加载时把它重新提交给 {@code TableManager} 是安全且幂等的
     * （同锚点 rebind 直接返回 true）。这样修复链路才真正跑在锚点 region 上，能合法读实体。
     */
    private void restoreOwnerAnchorForLoadedChunk(String tableName) {
        GameTable table = plugin.getTableManager().getTable(tableName);
        PlacedTable placed = placedTable(tableName);
        if (table == null || placed == null) {
            return;
        }
        Location anchor = placed.anchor();
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        try {
            plugin.getTableManager().rebindTablePeriodicTask(table, anchor);
        } catch (RuntimeException | Error failure) {
            // 重绑失败不能阻断本次修复排入：修复本身仍会在 global 上跑，只是实体读取会被
            // captureCleanupPlan 的 lane 门禁判成"不可安全定位"走保守分支，不再抛异常。
            plugin.getLogger().warning("区块加载后恢复牌桌锚点失败: " + tableName
                + "，原因=" + failure.getMessage());
        }
    }

    private void queueChunkLoadRepair(String tableName) {
        String key = normalize(tableName);
        GameTable table = plugin.getTableManager().getTable(tableName);
        if (key == null || table == null) {
            return;
        }
        // 【必须在 runTableLater 之前】runTableLater 的 lane 由当时的 owner 锚点决定；
        // unload 已把锚点清空，不先恢复就会把整条修复排到 global（实服 region={null} 的根因）。
        restoreOwnerAnchorForLoadedChunk(tableName);
        synchronized (chunkLoadRepairQueued) {
            if (activeChunkLoadRepairs.contains(key)) {
                return;
            }
            if (!chunkLoadRepairQueued.add(key)) {
                return;
            }
        }
        final CompletionStage<?>[] stageRef = new CompletionStage<?>[1];
        try {
            var handle = plugin.getTableManager().runTableLater(table, 1L, () -> {
                CompletionStage<Void> stage;
                try {
                    stage = repairTableAfterChunkLoad(table);
                } catch (Throwable failure) {
                    stage = failedStage(failure);
                }
                stageRef[0] = stage;
                stage.whenComplete((ignored, failure) -> {
                    synchronized (chunkLoadRepairQueued) {
                        chunkLoadRepairQueued.remove(key);
                        activeChunkLoadRepairs.remove(key);
                    }
                    if (failure != null) {
                        plugin.getLogger().warning("区块加载修复未完成: " + key + "，原因=" + failure.getMessage());
                    }
                });
            });
            // owner lane 若在回调开始前被卸载/取消，不能永久卡住 ChunkLoad 去重集合。
            handle.onTermination(() -> {
                if (stageRef[0] == null) {
                    synchronized (chunkLoadRepairQueued) {
                        chunkLoadRepairQueued.remove(key);
                        activeChunkLoadRepairs.remove(key);
                    }
                }
            });
        } catch (RuntimeException | Error failure) {
            synchronized (chunkLoadRepairQueued) {
                chunkLoadRepairQueued.remove(key);
                activeChunkLoadRepairs.remove(key);
            }
            throw failure;
        }
    }

    /**
     * 只允许由 {@link TableManager#runTableLater(GameTable, long, Runnable)} 的 owner 回调进入。
     *
     * <p>不完整桌不会再同步 rebuildSingleTable：先冻结单桌 cleanup plan 并完成 unresolved 预检；缺失 tracked
     * entity 视为已不存在，可提交空或非空 barrier，只有无法安全定位的现存对象或基础身份异常才保留索引等待重试。
     * 预检成功后才由每个实体/方块所在 region 执行清理，最后回到 global 校验并在 anchor region 重建。返回
     * stage 前不释放 ChunkLoad 去重状态，避免 owner 回调刚返回就有第二个 barrier 进入。
     */
    private CompletionStage<Void> repairTableAfterChunkLoad(GameTable table) {
        if (table == null) {
            return CompletableFuture.completedFuture(null);
        }
        String key = normalize(table.getName());
        PlacedTable current = placedTable(table.getName());
        if (key == null || current == null || !isFootprintLoaded(current)) {
            return CompletableFuture.completedFuture(null);
        }
        if (!isIncomplete(current)) {
            notifyTableAnchorBinding(table);
            refresh(table);
            return CompletableFuture.completedFuture(null);
        }

        final long epoch;
        synchronized (chunkLoadRepairQueued) {
            if (!activeChunkLoadRepairs.add(key)) {
                return CompletableFuture.completedFuture(null);
            }
            epoch = cleanupEpochByTable.merge(key, 1L, Long::sum);
        }
        // 先冻结完整清理计划；只有捕获成功且没有未解析项，才允许取消旧 owner 任务并摘除牌桌。
        // 缺失 tracked entity 已在 captureCleanupPlan 中视为 absent；预检失败仅保留 placed 与 footprint/entity 索引，等待下一次 ChunkLoad 重试。
        CleanupPlan plan = captureCleanupPlan(key, table, current, epoch);
        if (!plan.unresolved().isEmpty()) {
            plugin.getLogger().warning("区块加载修复预检失败，保留牌桌索引: " + key + "，未解析=" + plan.unresolved());
            return CompletableFuture.completedFuture(null);
        }
        if (placedTable(key) != current) {
            throw new IllegalStateException("区块加载修复放置身份已变化，不摘除牌桌: " + key);
        }

        // 先让逻辑桌回到 global/unplaced；GameTable 实例本身保留。
        //
        // 【严禁在这里硬取消周期任务】这里曾经先调 TableManager.cancelOwnerPeriodicTasks(key)，
        // 它会把三个注册表里的条目**永久摘除**（TablePeriodicTaskRegistry 的 cancel/cancelOwner 会
        // entries.remove + cancelled=true），之后的 rebindTablePeriodicTask 只能返回 false：
        //   - ownerPeriodicTasks 里的开局发牌 timer（GameTable → TableManager.runTableTimer）没有任何
        //     重新注册路径，ChunkLoad 修复一次就把整条发牌时间线永久打死（窗口内不再 tick）；
        //   - periodicTasks 里的 tickActionBar 同样消失，于是修复成功后的 notifyTableAnchorBinding
        //     会抛「牌桌周期任务未重绑定」，把 finishCleanupPlan 里随后的 refresh 一起跳过。
        // 这里真正需要的只是「离开旧 owner lane」，而下面的 removePlacedTableIfSame → markTableUnplaced
        // 已经用原子 rebind 做到：periodicTasks / ownerPeriodicTasks 带 bindingGeneration 门禁重绑到
        // global（旧 region/global 句柄被取消，迟到回调被代次拦下），world 任务按「未放置」语义摘除、
        // 等修复收口 notifyTableAnchorBinding 用新锚点重新注册。
        // 因此这里只摘放置状态：周期任务由 markTableUnplaced 保留，并由修复成功后的重绑复活到新锚点。
        removePlacedTableIfSame(key, current);
        return submitCleanupPlan(plan);
    }

    private CleanupPlan captureCleanupPlan(
        String tableKey,
        GameTable table,
        PlacedTable placed,
        long epoch
    ) {
        Location anchor = placed.anchor() == null ? null : placed.anchor().clone();
        TableOwner owner = placed.owner();
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        if (anchor == null || anchor.getWorld() == null) {
            unresolved.add("anchor-location");
        }
        if (owner == null) {
            unresolved.add("table-owner");
        }

        List<UUID> staticEntities = List.copyOf(placed.staticEntities());
        List<UUID> craftEngineEntities = List.copyOf(placed.craftEngineVisualEntities());
        List<UUID> actionEntities = List.copyOf(placed.actionEntities());
        List<UUID> seatNameEntities = List.copyOf(placed.seatNameDisplayIds());
        List<UUID> seatInfoEntities = List.copyOf(placed.seatInfoDisplayIds());
        List<UUID> privateEntities = flattenEntityBuckets(placed.privateEntitiesByPlayer());
        List<UUID> backsideEntities = flattenEntityBuckets(placed.backsideEntitiesByPlayer());
        UUID statusDisplayId = placed.statusDisplayId();
        UUID playDetailDisplayId = placed.playDetailDisplayId();
        Map<Integer, UUID> seatAssignments = Map.copyOf(new LinkedHashMap<>(placed.seatAssignments()));
        List<Long> footprintChunkKeys = List.copyOf(placed.footprintChunkKeys());

        LinkedHashSet<UUID> allEntityIds = new LinkedHashSet<>();
        allEntityIds.addAll(staticEntities);
        allEntityIds.addAll(craftEngineEntities);
        allEntityIds.addAll(actionEntities);
        allEntityIds.addAll(seatNameEntities);
        allEntityIds.addAll(seatInfoEntities);
        allEntityIds.addAll(privateEntities);
        allEntityIds.addAll(backsideEntities);
        if (statusDisplayId != null) {
            allEntityIds.add(statusDisplayId);
        }
        if (playDetailDisplayId != null) {
            allEntityIds.add(playDetailDisplayId);
        }

        Map<UUID, Location> locations = new LinkedHashMap<>();
        for (UUID entityId : allEntityIds) {
            if (entityId == null) {
                unresolved.add("entity-null");
                continue;
            }
            Entity entity = worldBodyLane.resolveEntity(entityId);
            // tracked UUID 查不到实体表示它已经不存在；不能把正常的缺失恢复场景误判为预检失败。
            if (entity == null) {
                continue;
            }
            // 【Folia 关键门禁】本方法的调用 lane 由 runTableLater 决定：锚点在上一步
            // markTableUnplaced 之后被清空，因此这里通常会落在 **global**（实服日志 region={null}）。
            // global 不拥有任何实体 region，读位置会直接抛 "Accessing entity state off owning
            // region's thread" 并中断整条修复——这正是「区块加载修复未完成: 4」的真实来源。
            // 不归属当前 lane 的实体按"不可安全定位"处理：记入 unresolved 走既有保守分支，
            // 保留 placed 与索引等待下一次 ChunkLoad 重试，绝不抛异常。
            if (!worldBodyLane.isOwnedByCurrentRegion(entity)) {
                unresolved.add("entity-off-lane:" + entityId);
                continue;
            }
            Location location = entity.getLocation();
            // 只有实体对象仍存在但无法安全定位时才阻断 destructive cleanup。
            if (location == null || location.getWorld() == null) {
                unresolved.add("entity-location:" + entityId);
                continue;
            }
            locations.put(entityId, location.clone());
        }

        Map<ChunkOwnerKey, CleanupRegionPartBuilder> parts = new LinkedHashMap<>();
        LinkedHashSet<UUID> craftEntitySet = new LinkedHashSet<>(craftEngineEntities);
        for (UUID entityId : allEntityIds) {
            if (craftEntitySet.contains(entityId)) {
                continue;
            }
            Location location = locations.get(entityId);
            if (location != null) {
                addCleanupEntity(parts, entityId, location);
            }
        }
        for (UUID entityId : craftEngineEntities) {
            Location location = locations.get(entityId);
            if (location == null) {
                continue;
            }
            Entity entity = worldBodyLane.resolveEntity(entityId);
            // 【Folia 关键门禁，实服栈就停在这一行】getVehicle() 内部会读实体状态
            // （CraftEntity.isInsideVehicle → getHandle），在 global lane 上必然抛
            // "Accessing entity state off owning region's thread"。上面 locations 已经把不归属
            // 当前 lane 的实体过滤掉了，但父/子家具可能被拆到不同 region（父归属、子不归属），
            // 所以这里仍要单独判定；读不到乘客关系时按"独立根家具"处理并交给 fabric 清理，
            // 与 vehicleId 为 null 的既有语义一致，既不抛异常也不丢清理项。
            UUID vehicleId = entity == null || !worldBodyLane.isOwnedByCurrentRegion(entity)
                || entity.getVehicle() == null
                ? null
                : entity.getVehicle().getUniqueId();
            if (vehicleId == null || !craftEntitySet.contains(vehicleId)) {
                addCleanupFurniture(parts, entityId, location);
            }
        }
        for (BlockRestore blockRestore : placed.blockRestores()) {
            if (blockRestore == null || blockRestore.originalState() == null) {
                unresolved.add("block-state");
                continue;
            }
            Location location = blockRestore.originalState().getLocation();
            if (location == null || location.getWorld() == null) {
                unresolved.add("block-location");
                continue;
            }
            addCleanupBlock(parts, blockRestore.originalState(), location.clone());
        }

        List<CleanupRegionPart> frozenParts = parts.values().stream()
            .map(CleanupRegionPartBuilder::freeze)
            .toList();
        return new CleanupPlan(
            tableKey,
            table,
            owner,
            anchor,
            placed.yaw(),
            seatAssignments,
            footprintChunkKeys,
            staticEntities,
            craftEngineEntities,
            actionEntities,
            seatNameEntities,
            seatInfoEntities,
            privateEntities,
            backsideEntities,
            statusDisplayId,
            playDetailDisplayId,
            frozenParts,
            List.copyOf(unresolved),
            epoch
        );
    }

    private static List<UUID> flattenEntityBuckets(Map<UUID, List<UUID>> buckets) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        if (buckets != null) {
            for (List<UUID> bucket : buckets.values()) {
                if (bucket != null) {
                    ids.addAll(bucket);
                }
            }
        }
        return List.copyOf(ids);
    }

    private static CleanupRegionPartBuilder regionPart(
        Map<ChunkOwnerKey, CleanupRegionPartBuilder> parts,
        Location location
    ) {
        UUID worldId = location.getWorld() == null ? null : location.getWorld().getUID();
        ChunkOwnerKey key = new ChunkOwnerKey(worldId, packedChunkKey(
            location.getBlockX() >> 4,
            location.getBlockZ() >> 4
        ));
        return parts.computeIfAbsent(key, ignored -> new CleanupRegionPartBuilder(location.clone()));
    }

    private static void addCleanupEntity(
        Map<ChunkOwnerKey, CleanupRegionPartBuilder> parts,
        UUID entityId,
        Location location
    ) {
        regionPart(parts, location).entities.add(new CleanupEntity(entityId, location.clone()));
    }

    private static void addCleanupFurniture(
        Map<ChunkOwnerKey, CleanupRegionPartBuilder> parts,
        UUID rootId,
        Location location
    ) {
        regionPart(parts, location).furniture.add(new CleanupFurniture(rootId, location.clone()));
    }

    private static void addCleanupBlock(
        Map<ChunkOwnerKey, CleanupRegionPartBuilder> parts,
        BlockState state,
        Location location
    ) {
        regionPart(parts, location).blocks.add(new CleanupBlock(state, location.clone()));
    }

    private void executeCleanupRegion(CleanupPlan plan, CleanupRegionPart part) {
        for (CleanupEntity cleanup : part.entities()) {
            actionBindings.remove(cleanup.entityId());
            cardBindings.remove(cleanup.entityId());
            Entity entity = Bukkit.getEntity(cleanup.entityId());
            if (entity == null || !sameWorldAndChunk(entity.getLocation(), part.ownerLocation())) {
                if (entity != null) {
                    plugin.getLogger().warning("跳过跨 region 牌桌实体清理: " + cleanup.entityId());
                }
                continue;
            }
            if (ownedBy(entity, plan.owner())) {
                entity.remove();
            }
        }
        for (CleanupFurniture cleanup : part.furniture()) {
            Entity root = Bukkit.getEntity(cleanup.rootId());
            if (root == null || !sameWorldAndChunk(root.getLocation(), part.ownerLocation())) {
                if (root != null) {
                    plugin.getLogger().warning("跳过跨 region CE 家具清理: " + cleanup.rootId());
                }
                continue;
            }
            // CE 家具不写 MUZ owner PDC；root 是一个原子操作，passenger 树不跨 request 拆分。
            plugin.getCraftEngineFurnitureService().removeFurniture(root);
            forceRemoveEntityTree(root);
        }
        for (CleanupBlock cleanup : part.blocks()) {
            cleanup.state().update(true, false);
        }
    }

    private static boolean sameWorldAndChunk(Location left, Location right) {
        return left != null && right != null
            && left.getWorld() != null && right.getWorld() != null
            && left.getWorld().getUID().equals(right.getWorld().getUID())
            && (left.getBlockX() >> 4) == (right.getBlockX() >> 4)
            && (left.getBlockZ() >> 4) == (right.getBlockZ() >> 4);
    }

    private CompletionStage<Void> submitCleanupPlan(CleanupPlan plan) {
        List<RegionTaskBarrier.Request> requests = new ArrayList<>();
        int index = 0;
        for (CleanupRegionPart part : plan.parts()) {
            String requestId = plan.tableKey() + "#cleanup-" + index++;
            requests.add(new RegionTaskBarrier.Request(
                requestId,
                part.ownerLocation().clone(),
                () -> executeCleanupRegion(plan, part)
            ));
        }
        RegionTaskBarrier barrier;
        try {
            barrier = new RegionTaskBarrier(plugin.scheduler(), requests, 100L);
            barrier.start();
        } catch (Throwable failure) {
            plugin.getLogger().warning("区块加载修复无法提交清理屏障: " + plan.tableKey() + "，原因=" + failure.getMessage());
            return CompletableFuture.completedFuture(null);
        }
        return barrier.completion()
            .thenCompose(result -> runGlobalStage(() -> validateCleanupBarrier(plan, result)))
            .thenCompose(result -> finishCleanupPlan(plan, result));
    }

    private RegionTaskBarrier.Result validateCleanupBarrier(
        CleanupPlan plan,
        RegionTaskBarrier.Result result
    ) {
        if (result == null || result.status() != RegionTaskBarrier.Status.COMPLETED) {
            return null;
        }
        if (!canRebuildCleanupPlan(plan)) {
            plugin.getLogger().warning("区块加载修复屏障完成但状态已失效: " + plan.tableKey());
            return null;
        }
        return result;
    }

    private CompletionStage<Void> finishCleanupPlan(CleanupPlan plan, RegionTaskBarrier.Result result) {
        if (result == null || result.status() != RegionTaskBarrier.Status.COMPLETED) {
            plugin.getLogger().warning("区块加载修复清理屏障失败: " + plan.tableKey()
                + "，状态=" + (result == null ? "null" : result.status()));
            return CompletableFuture.completedFuture(null);
        }
        return runRegionStage(plan.anchor(), () -> {
            if (!canRebuildCleanupPlan(plan)) {
                plugin.getLogger().warning("区块加载修复放弃重建: " + plan.tableKey()
                    + "，桌实例/代次/区块状态已变化");
                return;
            }
            if (canPurgeResidualForPlan(plan)) {
                try {
                    purgeResidualWorldArtifacts(plan.anchor().clone(), plan.yaw());
                } catch (Throwable failure) {
                    plugin.getLogger().warning("区块加载修复残留清理失败，不重建: " + plan.tableKey()
                        + "，原因=" + failure.getMessage());
                    return;
                }
            } else {
                plugin.getLogger().warning("区块加载修复跳过残留扫描: " + plan.tableKey()
                    + "，无法证明扫描范围属于同一 owner region");
            }
            PlacedTable rebuilt = spawnTable(
                plan.table(),
                plan.anchor().clone(),
                plan.yaw(),
                plan.owner().id(),
                plan.owner().name()
            );
            rebuilt.seatAssignments().putAll(plan.seatAssignments());
            putPlacedTable(plan.tableKey(), rebuilt);
            notifyTableAnchorBinding(plan.table());
            refresh(plan.table());
        });
    }

    private boolean canRebuildCleanupPlan(CleanupPlan plan) {
        synchronized (chunkLoadRepairQueued) {
            return !plugin.isShuttingDown()
                && cleanupEpochByTable.getOrDefault(plan.tableKey(), -1L) == plan.epoch()
                && plugin.getTableManager().getTable(plan.tableKey()) == plan.table()
                && placedTable(plan.tableKey()) == null
                && isFootprintLoaded(plan.anchor(), plan.footprintChunkKeys());
        }
    }

    private boolean canPurgeResidualForPlan(CleanupPlan plan) {
        Location anchor = plan.anchor();
        if (anchor == null || anchor.getWorld() == null) {
            return false;
        }
        int anchorChunkX = anchor.getBlockX() >> 4;
        int anchorChunkZ = anchor.getBlockZ() >> 4;
        UUID worldId = anchor.getWorld().getUID();
        for (CleanupRegionPart part : plan.parts()) {
            Location owner = part.ownerLocation();
            if (owner == null || owner.getWorld() == null
                || !worldId.equals(owner.getWorld().getUID())
                || owner.getBlockX() >> 4 != anchorChunkX
                || owner.getBlockZ() >> 4 != anchorChunkZ) {
                return false;
            }
        }
        return true;
    }

    private boolean isFootprintLoaded(PlacedTable placed) {
        return placed != null && isFootprintLoaded(placed.anchor(), placed.footprintChunkKeys());
    }

    private boolean isFootprintLoaded(Location anchor, List<Long> footprintChunkKeys) {
        World world = anchor == null ? null : anchor.getWorld();
        if (world == null || footprintChunkKeys == null) {
            return false;
        }
        for (long packed : footprintChunkKeys) {
            int chunkX = (int) (packed >> 32);
            int chunkZ = (int) packed;
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                return false;
            }
        }
        return true;
    }

    private <T> CompletionStage<T> runGlobalStage(Supplier<T> supplier) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            var handle = plugin.scheduler().runGlobal(() -> {
                try {
                    result.complete(supplier.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            if (handle == null || handle.isCancelled()) {
                result.completeExceptionally(new IllegalStateException("global stage 注册失败"));
            }
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private CompletionStage<Void> runRegionStage(Location owner, Runnable action) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            var handle = plugin.scheduler().runRegion(owner.clone(), () -> {
                try {
                    action.run();
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            if (handle == null || handle.isCancelled()) {
                result.completeExceptionally(new IllegalStateException("anchor region stage 注册失败"));
            }
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /**
     * 与 {@link #runRegionStage} 同一范式，只是把返回值带回调用线程。
     *
     * <p>恢复存档牌桌要经它把世界体投到锚点 region，并把恢复出来的牌桌交还异步调用链；
     * 句柄被取消时与 runRegionStage 同口径 completeExceptionally 并记日志，绝不静默吞掉。
     */
    private <T> CompletionStage<T> runRegionStageValue(Location owner, Supplier<T> supplier) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            var handle = plugin.scheduler().runRegion(owner.clone(), () -> {
                try {
                    result.complete(supplier.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            if (handle == null || handle.isCancelled()) {
                result.completeExceptionally(new IllegalStateException("anchor region stage 注册失败"));
            }
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static <T> CompletionStage<T> failedStage(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }

    private static long packedChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * 汇报空位入座判定框的实际情况，用于排查点不中椅子的问题
     * @param tableName 牌桌名
     * @return 每个空位一行描述，牌桌不存在时返回空列表
     */
    public List<String> describeJoinHitboxes(String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        int unresolved = 0;
        for (Map.Entry<UUID, ActionBinding> entry : actionBindings.entrySet()) {
            ActionBinding binding = entry.getValue();
            if (!binding.tableName().equalsIgnoreCase(tableName)) {
                continue;
            }
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity == null) {
                // 区块未加载时 getEntity 返回 null，这只是查不到，不等于判定框没了。
                unresolved++;
                continue;
            }
            if (!(entity instanceof Interaction interaction)) {
                // 一个按钮的图标和文字也登记在 actionBindings 里，它们不是判定框，跳过就好，
                // 别当成"查不到"，否则 3 个座位会误报成 6 个未加载。
                continue;
            }
            int seatIndex = binding.seatIndex();
            Location seatBase = seatIndex < placed.seatBaseLocations().size()
                ? placed.seatBaseLocations().get(seatIndex)
                : null;
            double distance = seatBase == null
                ? -1.0
                : interaction.getLocation().distance(seatBase);
            // 判定框是从底边往上长的。文字要能点到，就必须落在 [底, 底+高] 之间。
            // 文字实体的渲染中心比实体坐标高出一段基准位移，比较时要加上这段补偿。
            double boxBottom = interaction.getLocation().getY();
            double boxTop = boxBottom + interaction.getInteractionHeight();
            boolean isJoin = binding.action() == ButtonAction.JOIN;
            double labelEntityY = actionBase(placed.anchor(), placed.yaw(), seatIndex).getY()
                + (isJoin ? plugin.getJoinLabelHeight() : plugin.getActionLabelHeight());
            double labelY = labelEntityY + buttonLabelBaseLift();
            lines.add(String.format(
                "座位%d %s 判定框 %.2fx%.2f 距椅子 %.3f 格 响应=%s 罩住文字=%s",
                seatIndex + 1,
                isJoin ? "加入按钮" : binding.action().toString(),
                interaction.getInteractionWidth(),
                interaction.getInteractionHeight(),
                distance,
                interaction.isResponsive(),
                describeLabelCoverage(boxBottom, boxTop, labelY)
            ));
        }
        if (unresolved > 0) {
            lines.add(unresolved + " 个判定框所在区块未加载，查不到实体（不代表判定框没了）");
        }
        return lines;
    }

    public String describeHandEntityHealth(String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return "手牌实体: 牌桌不存在";
        }
        List<UUID> privateIds = placed.privateEntitiesByPlayer().values().stream().flatMap(List::stream).toList();
        List<UUID> backsideIds = placed.backsideEntitiesByPlayer().values().stream().flatMap(List::stream).toList();
        long privateLive = privateIds.stream().filter(id -> Bukkit.getEntity(id) != null).count();
        long backsideLive = backsideIds.stream().filter(id -> Bukkit.getEntity(id) != null).count();
        return "手牌实体: 正面 " + privateLive + "/" + privateIds.size()
            + "，背面 " + backsideLive + "/" + backsideIds.size();
    }

    /**
     * 列出手牌那条带上的每个实体，以及仲裁会怎么判它。
     *
     * <p>为什么现有诊断不够：{@code describeChairInteractGuards} 已经报了保护/椅子/绑定三项，
     * 但它只扫椅子周围，而且用 isLikelyFurnitureEntity 把非家具直接过滤掉。
     * 吞掉点牌的桌子家具判定框不在椅子周围，怪物挡牌也被过滤看不见——
     * 恰好是仲裁最需要确认的两类。
     *
     * <p>不做过滤、按座位扫手牌中心那一圈，每个实体报一行，直接给出仲裁裁决。
     * 判据复用 {@code yieldsToBlockingEntity}，和生产同源，不另立一套。
     *
     * @param tableName 牌桌名
     * @return 每个座位一行，牌桌不存在时返回空列表
     */
    public List<String> describeHandCardArbitration(String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
            Vector center = handCenter(seatIndex);
            Vector adjustment = globalHandAdjustment(seatIndex);
            Location handLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x(),
                center.y() + adjustment.y(),
                center.z() + adjustment.z()
            );
            if (handLocation.getWorld() == null
                || !handLocation.getWorld().isChunkLoaded(
                    handLocation.getBlockX() >> 4,
                    handLocation.getBlockZ() >> 4)) {
                lines.add("座位" + (seatIndex + 1) + " 手牌带: 区块未加载");
                continue;
            }
            List<String> hits = new ArrayList<>();
            // 半径取够大以覆盖整排牌加桌心方向的家具，且刻意不过滤实体类型。
            for (Entity nearby : handLocation.getWorld().getNearbyEntities(handLocation, 1.6, 1.2, 1.6)) {
                if (nearby instanceof Player) {
                    continue;
                }
                UUID id = nearby.getUniqueId();
                boolean furniture = isLikelyFurnitureEntity(nearby);
                boolean bound = actionBindings.containsKey(id);
                boolean chairFurniture = isChairFurnitureEntity(id);
                // 这条诊断按牌桌扫，没有具体玩家，算不出「按钮够不着」那一半判据。
                // 所以按钮这一档只能给出条件裁决：真正落到手牌还是按钮，取决于玩家站位。
                // 不能直接把 bound 当 usableButton 传进去——那会在玩家站到按钮 3 格之外时
                // （按钮 MAX_ACTION_INTERACTION_DISTANCE=3.0；手牌不设距离限制）
                // 报出和实际相反的结论。要精确判定用 /muz debug hitbox <桌> player <名>，
                // 那条路有 Player，能把距离算进去。
                boolean yields = yieldsToBlockingEntity(furniture, bound, chairFurniture);
                String verdictText;
                if (!yields) {
                    verdictText = "手牌赢";
                } else if (bound && !chairFurniture) {
                    // 按钮这一档带上「3格内」，明说这个裁决有距离前提。
                    verdictText = "让位(仅玩家在3格内)";
                } else {
                    verdictText = "让位";
                }
                hits.add(String.format(
                    "%s[家具=%s 按钮=%s 椅子=%s 裁决=%s]",
                    nearby.getType(),
                    furniture,
                    bound,
                    chairFurniture,
                    verdictText
                ));
            }
            lines.add("座位" + (seatIndex + 1) + " 手牌带: " + (hits.isEmpty() ? "无实体" : String.join(" ", hits)));
        }
        return lines;
    }

    public String describePlayerInteractionState(String tableName, Player player) {
        PlacedTable placed = placedTable(tableName);
        GameTable table = plugin.getTableManager().getTable(tableName);
        if (placed == null || table == null) {
            return "玩家交互状态: 牌桌不存在";
        }
        int seatIndex = placedSeatIndex(placed, player.getUniqueId());
        Entity target = actionTarget(player);
        ActionBinding targetBinding = target == null ? null : actionBindings.get(target.getUniqueId());
        double targetDistance = target == null
            ? Double.NaN
            : Math.sqrt(distanceSquaredToBoundingBox(player.getEyeLocation(), target.getBoundingBox()));
        String targetText = target == null
            ? "无"
            : target.getType() + (targetBinding == null ? "" : "/" + targetBinding.action());
        // 手牌拾取与仲裁结果：光看"目标=xxx"不够，它只说准星撞上了哪个实体，
        // 说不出这次点击最后会算点牌还是算点那个实体。点牌没反应时要区分两种成因：
        // 拾取压根没命中（准星没对准牌，几何问题），还是命中了但仲裁让位给了实体（路由问题）。
        HandCardPickGeometry.Hit pick = pickHandCardForArbitration(player);
        String pickText = pick == null
            ? "无"
            : "牌#" + pick.cardId() + String.format("(%.2f)", pick.distance());
        // 路由：这次点击会走哪条事件路。必须报出来，否则诊断会给出和实际相反的结论。
        // 踩过的坑：actionTarget 用 rayTraceEntities 且只认带 ActionBinding 的实体，
        // 所以它永远只找得到按钮，找不到桌子家具；而桌子判定框是 CE 的 shulker 发包伪实体，
        // 服务端没有对应 Bukkit 实体，rayTraceEntities 本来也扫不到。
        // 于是点牌时 target 恒为 null，verdict 直接落到「手牌」——
        // 而在仲裁还挂在 PlayerInteractEntityEvent 上的那段时间里，那条路压根收不到事件，
        // 玩家实际是点了没反应。诊断报「手牌」、现实是「无反应」，结论正好相反。
        String route;
        if (target != null) {
            // 准星命中按钮：按钮是 MUZ 自己 spawn 的 Interaction 真实体，走原版实体事件。
            route = "原版/按钮";
        } else if (pick != null) {
            // 准星落在牌上：牌是 ItemDisplay 无判定框，射线穿过它命中后面桌子的 shulker 伪判定框，
            // 只有 CE 的 FurnitureInteractEvent / FurnitureHitEvent 收得到。
            route = "CE家具事件";
        } else {
            route = "无";
        }
        String verdict;
        if (pick == null) {
            verdict = "非手牌";
        } else if (target == null) {
            verdict = "手牌";
        } else if (!isLikelyFurnitureEntity(target)) {
            verdict = "让位/非家具";
        } else if (targetBinding != null && isWithinActionInteractionRange(player, target)) {
            verdict = "让位/按钮" + targetBinding.action();
        } else if (targetBinding != null) {
            // 有绑定但够不着：生产会判给手牌（让位给消费不了这次点击的按钮等于白丢一次点牌），
            // 诊断必须跟着这么报，否则玩家站 3~6 格排查时会得出和实际相反的结论。
            verdict = "手牌/按钮超距";
        } else if (isChairFurnitureEntity(target.getUniqueId())) {
            verdict = "让位/椅子";
        } else {
            verdict = "手牌";
        }
        return String.format(
            "玩家交互状态: 入桌=%s 座位=%s 坐下=%s 目标=%s 距离=%s 可点击=%s 准备=%s 手牌拾取=%s 仲裁=%s 路由=%s",
            table.getSeats().contains(player.getUniqueId()),
            seatIndex < 0 ? "-" : String.valueOf(seatIndex + 1),
            player.getVehicle() != null,
            targetText,
            Double.isNaN(targetDistance) ? "-" : String.format("%.2f", targetDistance),
            target != null && targetDistance <= MAX_ACTION_INTERACTION_DISTANCE,
            table.isReady(player.getUniqueId()),
            pickText,
            verdict,
            route
        );
    }

    /**
     * 汇报椅子附近的实体是否会放行右键，用于排查坐不上椅子的问题
     * @param tableName 牌桌名
     * @return 每把椅子一行描述，牌桌不存在时返回空列表
     */
    /**
     * 从玩家站位模拟射线，报告先命中的是加入按钮还是椅子
     * 判定框存在且尺寸正确，仍可能因为被椅子挡在后面而点不到。
     * @param tableName 牌桌名
     * @return 每个座位一行描述
     */
    public List<String> describeSeatRayHits(String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Location chairLocation = chairLocation(placed, index);
            if (!chairLocation.getWorld().isChunkLoaded(
                chairLocation.getBlockX() >> 4,
                chairLocation.getBlockZ() >> 4
            )) {
                lines.add("座位" + (index + 1) + " 射线: 区块未加载，测不了");
                continue;
            }
            org.bukkit.util.Vector outward =
                chairLocation.toVector().subtract(placed.anchor().toVector());
            outward.setY(0.0);
            if (outward.lengthSquared() < 1.0E-6) {
                continue;
            }
            outward.normalize();
            // 沿椅子切向的单位向量，用来构造侧面站位。
            org.bukkit.util.Vector tangent = new org.bukkit.util.Vector(-outward.getZ(), 0.0, outward.getX());
            Location buttonBase = actionBase(placed.anchor(), placed.yaw(), index);

            // 单一站位不足以断定遮挡。这里覆盖外侧远近、贴椅、两侧斜角，
            // 每个站位再取三个瞄准点（按钮中心、偏上、偏下）模拟不同俯仰。
            List<String> stances = new ArrayList<>();
            record Stance(String name, double outwardDistance, double tangentOffset) { }
            List<Stance> probes = List.of(
                new Stance("外侧2格", 2.0, 0.0),
                new Stance("外侧1格", 1.0, 0.0),
                new Stance("贴椅0.4格", 0.4, 0.0),
                new Stance("斜前左", 1.0, -1.2),
                new Stance("斜前右", 1.0, 1.2),
                new Stance("正侧左", 0.2, -1.6),
                new Stance("正侧右", 0.2, 1.6)
            );
            for (Stance probe : probes) {
                Location eye = chairLocation.clone()
                    .add(outward.clone().multiply(probe.outwardDistance()))
                    .add(tangent.clone().multiply(probe.tangentOffset()))
                    .add(0.0, 1.62, 0.0);
                String best = null;
                for (double aimY : new double[] {0.17, 0.30, 0.05}) {
                    org.bukkit.util.Vector aim = buttonBase.clone()
                        .add(0.0, aimY, 0.0)
                        .toVector()
                        .subtract(eye.toVector());
                    if (aim.lengthSquared() < 1.0E-6) {
                        continue;
                    }
                    org.bukkit.util.RayTraceResult hit = chairLocation.getWorld().rayTraceEntities(
                        eye,
                        aim.normalize(),
                        8.0,
                        0.0,
                        entity -> isLikelyFurnitureEntity(entity)
                    );
                    String what = describeRayHit(hit);
                    if ("加入按钮".equals(what)) {
                        best = what;
                        break;
                    }
                    if (best == null) {
                        best = what;
                    }
                }
                stances.add(probe.name() + "=" + best);
            }
            lines.add("座位" + (index + 1) + " 射线: " + String.join(" ", stances));
        }
        return lines;
    }

    private String describeRayHit(org.bukkit.util.RayTraceResult hit) {
        if (hit == null || hit.getHitEntity() == null) {
            return "空";
        }
        Entity first = hit.getHitEntity();
        UUID firstId = first.getUniqueId();
        if (actionBindings.containsKey(firstId)) {
            return "加入按钮";
        }
        if (isChairFurnitureEntity(firstId)) {
            return "椅子";
        }
        return first.getType().toString();
    }

    public List<String> describeChairInteractGuards(String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Location chairLocation = chairLocation(placed, index);
            boolean chunkLoaded = chairLocation.getWorld().isChunkLoaded(
                chairLocation.getBlockX() >> 4,
                chairLocation.getBlockZ() >> 4
            );
            if (!chunkLoaded) {
                lines.add("椅子" + (index + 1) + " 附近: 区块未加载，查不到实体（不代表家具丢了）");
                continue;
            }
            List<String> hits = new ArrayList<>();
            for (Entity nearby : chairLocation.getWorld().getNearbyEntities(chairLocation, 0.9, 1.7, 0.9)) {
                if (!isLikelyFurnitureEntity(nearby)) {
                    continue;
                }
                UUID id = nearby.getUniqueId();
                boolean protectedEntity = isProtectedEntity(id);
                boolean chairFurniture = isChairFurnitureEntity(id);
                boolean bound = actionBindings.containsKey(id) || cardBindings.containsKey(id);
                int resolvedSeat = chairFurniture ? nearestChairSeatIndex(nearby, placed) : -1;
                String owner = chairFurniture ? resolveChairOwnerLabel(nearby, tableName) : "-";
                hits.add(String.format(
                    "%s[保护=%s 椅子家具=%s 解析座位=%s 归属=%s 有绑定=%s 右键放行=%s]",
                    nearby.getType(),
                    protectedEntity,
                    chairFurniture,
                    resolvedSeat < 0 ? "-" : String.valueOf(resolvedSeat + 1),
                    owner,
                    bound,
                    !bound && (!protectedEntity || chairFurniture)
                ));
            }
            lines.add("椅子" + (index + 1) + " 附近: " + (hits.isEmpty() ? "无家具实体" : String.join(" ", hits)));
        }
        return lines;
    }

    /**
     * 重建前确认牌桌锚点 3x3 区块已加载。**按核心类型（{@link #REGIONIZED}）分支**。
     *
     * <p>原契约（保留其意图）：区块未加载时 remove() 是空操作、spawn 出来的实体也留不住，
     * 直接重建会让整桌桌椅按钮凭空消失。
     *
     * <p><b>非区域化核心（Paper/Leaf）</b>：恢复原实现的阻塞强加载（{@code World#getChunkAt}）。
     * 主线程允许同步加载区块，重建路径依赖「执行前锚点邻域已加载」这一保证，缺了它整桌实体就丢。
     *
     * <p><b>区域化核心（Folia）</b>：只读判断 + 未加载时告警。原实现用阻塞的
     * {@code World#getChunkAt} 强拉区块，真实 Folia 上直接抛 {@code Async chunk retrieval}。
     * 区块加载必须由异步侧（{@link #ensureChunkLoadedAsync} 或 ChunkLoad 事件接缝）完成，
     * 若仍走到未加载分支，说明重建调用方漏了异步加载，属于流程缺陷，必须出声而不是静默半重建。
     * @param anchor 牌桌锚点
     */
    private void ensureAnchorChunkLoaded(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        ensureAnchorChunkLoadedCore(anchor.getWorld(), anchor.getBlockX(), anchor.getBlockZ(), REGIONIZED,
            message -> plugin.getLogger().warning(message));
    }

    /**
     * 锚点 3x3 邻域预热的核心，**按核心类型分支**，且不依赖实例——便于以 fake World 做真实行为测试。
     *
     * <p>分支语义与 {@link #ensureAnchorChunkLoaded} 的文档一致：区域化核心对未加载区块只告警不拉取，
     * 非区域化核心（Paper/Leaf）才允许同步强加载。{@code regionized} 由调用方从 {@link #REGIONIZED}
     * 传入，绝不能在非区域化分支硬编码放行同步获取。
     *
     * @param world      牌桌锚点所在世界；null 直接返回
     * @param blockX     锚点方块 X
     * @param blockZ     锚点方块 Z
     * @param regionized 当前核心是否区域化（Folia）
     * @param warn       未加载告警出口，调用方负责落到服务器日志
     */
    static void ensureAnchorChunkLoadedCore(World world, int blockX, int blockZ, boolean regionized, Consumer<String> warn) {
        if (world == null) {
            return;
        }
        for (long packed : anchorChunkKeys(blockX, blockZ)) {
            int chunkX = (int) (packed >> 32);
            int chunkZ = (int) packed;
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                continue;
            }
            if (regionized) {
                // 区域化核心不能同步拉区块：Folia 上 world.getChunkAt(...) 在 region/主线程都非法且阻塞。
                warn.accept("重建牌桌时锚点邻域区块未加载，本应已由异步加载保证: world="
                    + world.getName() + " chunk=[" + chunkX + ", " + chunkZ + "]");
                continue;
            }
            // 非区域化核心（Paper/Leaf）主线程可同步加载：重建前把锚点邻域强拉起来。
            world.getChunkAt(chunkX, chunkZ);
        }
    }

    /**
     * 列出重建一张牌桌需要保证加载的区块
     * 桌椅按钮会铺开到锚点周围几格，锚点贴着区块边界时会跨到邻接区块，
     * 所以把 3x3 的邻域一起算进来，避免漏掉边缘实体。
     * @param blockX 锚点方块 X
     * @param blockZ 锚点方块 Z
     * @return 打包成 long 的区块坐标，高 32 位是 chunkX，低 32 位是 chunkZ
     */
    static List<Long> anchorChunkKeys(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        List<Long> keys = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                keys.add((((long) (chunkX + dx)) << 32) | ((chunkZ + dz) & 0xFFFFFFFFL));
            }
        }
        return keys;
    }

    /**
     * 运行期索引使用的单桌 footprint。它与区块预加载使用同一份 3x3 计算，避免两边漂移。
     * @param anchor 锚点
     * @return 该桌覆盖的 3x3 区块键
     */
    private static List<Long> footprintChunkKeys(Location anchor) {
        if (anchor == null) {
            return List.of();
        }
        return anchorChunkKeys(anchor.getBlockX(), anchor.getBlockZ());
    }

    private void indexPlacedTable(String tableKey, PlacedTable placed) {
        if (tableKey == null || placed == null) {
            return;
        }
        unindexPlacedTable(tableKey, placed);
        Set<ChunkOwnerKey> footprint = new LinkedHashSet<>();
        UUID worldId = placed.anchor().getWorld() == null ? null : placed.anchor().getWorld().getUID();
        for (long chunkKey : placed.footprintChunkKeys()) {
            ChunkOwnerKey ownerKey = new ChunkOwnerKey(worldId, chunkKey);
            tableNamesByFootprintChunk.computeIfAbsent(ownerKey, ignored -> new LinkedHashSet<>()).add(tableKey);
            footprint.add(ownerKey);
        }
        footprintChunksByTable.put(tableKey, footprint);
        reindexPlacedTableEntities(tableKey, placed);
    }

    private void unindexPlacedTable(String tableKey, PlacedTable placed) {
        if (tableKey == null) {
            return;
        }
        Set<ChunkOwnerKey> footprint = footprintChunksByTable.remove(tableKey);
        if (footprint != null) {
            for (ChunkOwnerKey ownerKey : footprint) {
                Set<String> names = tableNamesByFootprintChunk.get(ownerKey);
                if (names == null) {
                    continue;
                }
                names.remove(tableKey);
                if (names.isEmpty()) {
                    tableNamesByFootprintChunk.remove(ownerKey);
                }
            }
        }
        Set<UUID> entityIds = indexedEntityIdsByTable.remove(tableKey);
        if (entityIds != null) {
            for (UUID entityId : entityIds) {
                TableOwner owner = trackedEntityOwnersById.get(entityId);
                if (owner != null && tableKey.equals(owner.tableName())) {
                    trackedEntityOwnersById.remove(entityId);
                }
            }
        }
    }

    private void reindexPlacedTableEntities(String tableKey, PlacedTable placed) {
        Set<UUID> current = new LinkedHashSet<>();
        collectEntityIds(current, placed.staticEntities());
        collectEntityIds(current, placed.craftEngineVisualEntities());
        collectEntityIds(current, placed.actionEntities());
        collectEntityIds(current, placed.seatNameDisplayIds());
        collectEntityIds(current, placed.seatInfoDisplayIds());
        collectEntityBuckets(current, placed.privateEntitiesByPlayer().values());
        collectEntityBuckets(current, placed.backsideEntitiesByPlayer().values());
        if (placed.statusDisplayId() != null) {
            current.add(placed.statusDisplayId());
        }
        if (placed.playDetailDisplayId() != null) {
            current.add(placed.playDetailDisplayId());
        }
        Set<UUID> previous = indexedEntityIdsByTable.put(tableKey, current);
        if (previous != null) {
            for (UUID entityId : previous) {
                if (!current.contains(entityId)) {
                    TableOwner owner = trackedEntityOwnersById.get(entityId);
                    if (owner != null && tableKey.equals(owner.tableName())) {
                        trackedEntityOwnersById.remove(entityId);
                    }
                }
            }
        }
        for (UUID entityId : current) {
            trackedEntityOwnersById.put(entityId, placed.owner());
        }
    }

    private static void collectEntityIds(Set<UUID> target, Collection<UUID> ids) {
        if (ids == null) {
            return;
        }
        for (UUID id : ids) {
            if (id != null) {
                target.add(id);
            }
        }
    }

    private static void collectEntityBuckets(Set<UUID> target, Collection<? extends Collection<UUID>> buckets) {
        if (buckets == null) {
            return;
        }
        for (Collection<UUID> bucket : buckets) {
            collectEntityIds(target, bucket);
        }
    }

    private void putPlacedTable(String tableKey, PlacedTable placed) {
        PlacedTable previous = placedTables.put(tableKey, placed);
        if (previous != null) {
            unindexPlacedTable(tableKey, previous);
        }
        indexPlacedTable(tableKey, placed);
    }

    private void clearPlacedTableIndexes() {
        tableNamesByFootprintChunk.clear();
        footprintChunksByTable.clear();
        trackedEntityOwnersById.clear();
        indexedEntityIdsByTable.clear();
    }

    /**
     * 重建全部已放置牌桌（reload 与启动视觉预热）。
     *
     * <p>【为什么改成异步流水线】真实 Folia 上「同步拉区块」与「在调用线程动实体」都会抛异常
     * （{@code Async chunk retrieval} / {@code No currently ticking region}），原地重建会整批失败。
     * 现在每张桌都走同一条固定顺序的 lane 流水线（见 {@link #runSingleRebuild}）：
     * 调用 lane 冻结快照并打上在飞标记 → 异步加载新旧锚点 footprint → 旧锚点 region 清旧实体 →
     * 新锚点 region 扫残留并生成新桌 → global 收口写入索引与 owner 重绑 → 锚点 region 刷新世界体。
     *
     * <p>【旧状态必须活到收口成功】这里**不再**提前 {@code placedTables.clear()} / 摘索引 /
     * {@code markTableUnplaced}：旧放置快照与索引会一直保留，直到 global 收口把新桌写进同一张 Map。
     * 理由见 {@link #rebuildingTableKeys}——提前清空会开出一个"这张桌不存在"的秒级窗口，窗口内
     * {@code TableManager.cleanupIfEmpty} 会把空桌注销（永久丢桌），重建失败也会连旧桌一起丢。
     *
     * <p>【非并发 Map 的写入边界】{@code placedTables} / footprint 索引 / 实体索引都是非并发
     * LinkedHashMap，本重建流水线**自身**的收口写入只发生在调用 lane 与 global 收口步骤；
     * region 回调只读 {@link RebuildRequest} 里的冻结快照，并把新建出来的 {@code PlacedTable} 当返回值交回。
     * 注意这只描述重建流水线自己的边界：既有的 owner tick / 交互路径（{@code refresh}、{@code tickTable}
     * 等）仍会在各自 owner lane 上读写同一批非并发 Map，尚未统一并发边界（见 {@link #rebuildingTableKeys}
     * 的世界体门禁如何把重建窗口从这些路径里隔出去）。统一迁移不做，属待偿技术债。
     *
     * @return 整批（含每桌刷新）走完后完成的 stage；单桌失败只记录日志并继续后面的桌，
     *         不会把整批打断，也不会把失败桌伪装成已重建。
     */
    public CompletionStage<Void> rebuildAllTables() {
        if (!canSafelyReplaceWorldVisuals()) {
            return CompletableFuture.completedFuture(null);
        }
        // 重新武装遮挡告警：本方法在每次 /muz reload 时都会跑，而告警判据读的全是配置。
        // 不重置的话，服主改完 hand-center.distance 或 button-layout 再重载，
        // 新配置下的遮挡永远不会被报出来——那正是最需要这条告警的时刻。
        capturerOcclusionWarned = false;
        List<String> tableKeys = new ArrayList<>(placedTables.keySet());
        List<RebuildRequest> requests = new ArrayList<>(tableKeys.size());
        for (String tableKey : tableKeys) {
            RebuildRequest request = captureSingleRebuild(tableKey, 0.0);
            if (request != null) {
                requests.add(request);
            }
        }
        // 重建后补一次显式恢复。重建换了一批椅子实体，理论上新实体没有历史隐藏状态，
        // 这一步偏兜底；真正需要它的是 syncViewer 那条（在线玩家身上的旧隐藏状态）。
        // 放在这里而不是刷新链路里，是因为恢复只需要一次：
        // 挂在 syncActionWidgets 上会变成每次出牌都重发判定框，把坐着的玩家挤开。
        // 这里传全场：重建换了全新的椅子实体，坐着的玩家已经被掀下来，
        // 不存在"把人挤开"的问题，而新实体本就需要让所有人都看见。
        return runRebuildBatch(requests,
            rebuilt -> restoreOccupiedChairHitboxVisibility(rebuilt, onlinePlayerIdsSnapshot()));
    }

    /**
     * 扫描并重建不完整桌。
     *
     * <p>【为什么先切回 global】扫描会遍历并改写 {@code placedTables}，必须与重建流水线里
     * 写同一批非并发 Map 的收口步骤落在同一条 lane 上，因此整段扫描作为 global stage 执行。
     * 现在返回 stage，调用方可以在 {@code rebuildAllTables()} 之后串接它，避免在旧实体已被清掉、
     * 新桌尚未生成时扫出一整批"不完整桌"。
     */
    public CompletionStage<Void> repairIncompleteTables(String reason) {
        return runGlobalStage(() -> captureIncompleteRebuildRequests(reason))
            .thenCompose(requests -> {
                if (requests == null || requests.isEmpty()) {
                    return CompletableFuture.completedFuture(null);
                }
                return runRebuildBatch(requests, null);
            });
    }

    /**
     * 在调用 lane（global）上扫描不完整桌并冻结重建请求。
     *
     * <p>桌名和诊断原因分开存。以前只存 "桌名(原因)" 一串，重建时拿它去查表必然查不到，
     * 结果桌子被摘掉却没重建回来，表现就是桌椅整套凭空消失。
     *
     * <p>先只读扫描出桌名，再逐个摘除：直接在 {@code placedTables} 上边遍历边摘会抛
     * {@code ConcurrentModificationException}。
     */
    private List<RebuildRequest> captureIncompleteRebuildRequests(String reason) {
        List<String> targets = new ArrayList<>();
        List<String> logEntries = new ArrayList<>();
        for (Map.Entry<String, PlacedTable> entry : placedTables.entrySet()) {
            PlacedTable placed = entry.getValue();
            if (placed == null) {
                continue;
            }
            if (isIncomplete(placed)) {
                targets.add(entry.getKey());
                logEntries.add(placed.tableName() + "(" + incompleteReason(placed) + ")");
            }
        }
        if (targets.isEmpty()) {
            return List.of();
        }
        if (reason != null && reason.startsWith("viewer-")) {
            plugin.getLogger().fine("[MUZ/repair/ddz] reason=" + reason + " tables=" + logEntries);
        } else {
            plugin.getLogger().warning("[MUZ/repair/ddz] reason=" + reason + " tables=" + logEntries);
        }
        List<RebuildRequest> requests = new ArrayList<>(targets.size());
        for (String tableKey : targets) {
            RebuildRequest request = captureSingleRebuild(tableKey, 0.0);
            if (request != null) {
                requests.add(request);
            }
        }
        return requests;
    }

    /**
     * 把所有已放置牌的锚点整体上下位移后重建（管理菜单改 {@code table.spawn-offset-y} 时使用）。
     *
     * <p>位移会同时改变锚点，因此清旧实体必须投**旧锚点** region、生成新桌投**新锚点** region：
     * 两者可能不在同一 region，在新锚点线程上清旧实体在 Folia 上非法。
     *
     * <p>与 {@link #rebuildAllTables()} 同样**不提前摘除**旧 {@code PlacedTable} / 索引，旧状态活到收口提交；
     * owner 周期任务也仍绑在旧锚点 region，直到收口时 {@code notifyTableAnchorBinding} 重绑到新锚点。
     */
    public CompletionStage<Void> shiftAllAnchors(double deltaY) {
        if (Math.abs(deltaY) < 0.0001) {
            return CompletableFuture.completedFuture(null);
        }
        if (!canSafelyReplaceWorldVisuals()) {
            return CompletableFuture.completedFuture(null);
        }
        List<String> tableKeys = new ArrayList<>(placedTables.keySet());
        List<RebuildRequest> requests = new ArrayList<>(tableKeys.size());
        for (String tableKey : tableKeys) {
            RebuildRequest request = captureSingleRebuild(tableKey, deltaY);
            if (request != null) {
                requests.add(request);
            }
        }
        return runRebuildBatch(requests, null);
    }

    /**
     * 重建单张桌。
     *
     * @return 该桌重建流水线走完后完成的 stage；找不到旧桌时返回已完成 stage 并记录告警
     */
    private CompletionStage<Void> rebuildSingleTable(String tableName) {
        if (!canSafelyReplaceWorldVisuals()) {
            return CompletableFuture.completedFuture(null);
        }
        RebuildRequest request = captureSingleRebuild(normalize(tableName), 0.0);
        if (request == null) {
            // 查不到就说明调用方传错了名字（或该桌已有一条重建在飞）。静默返回会让桌子被摘掉却建不回来，
            // 这里必须出声。
            plugin.getLogger().warning("[MUZ/repair/ddz] 重建失败，找不到已放置的牌桌（或该桌重建已在飞行中）: " + tableName);
            return CompletableFuture.completedFuture(null);
        }
        return runRebuildBatch(List.of(request), null);
    }

    /**
     * 在调用 lane 上冻结一张桌的重建参数，并在 {@link #rebuildingTableKeys} 里打上在飞标记。
     *
     * <p>【为什么不摘旧状态】旧 {@code PlacedTable} 与三份索引都保持原样，直到 global 收口把新桌写进
     * 同一张 Map。调用方（{@code rebuildAllTables} / {@code shiftAllAnchors} / 单桌修复）不再提前
     * {@code remove} / {@code clear} / {@code markTableUnplaced}。
     *
     * <p>【互斥改由标记承担】旧逻辑靠"先摘除再重建"提供同桌天然互斥；既然不动旧状态，就靠标记：
     * 同桌已有流水线在飞时第二次重建直接返回 null，不可能出现两条流水线同时提交同一张桌。
     *
     * @param deltaY 新锚点相对旧锚点的 Y 位移（非位移场景传 0）
     * @return 冻结好的请求；该桌当前未放置或已有重建在飞时返回 null
     */
    private RebuildRequest captureSingleRebuild(String tableKey, double deltaY) {
        if (tableKey == null) {
            return null;
        }
        PlacedTable previous = placedTables.get(tableKey);
        if (previous == null) {
            return null;
        }
        if (!rebuildingTableKeys.add(tableKey)) {
            // 该桌已有一条重建流水线在飞：让出，迟到结果不得把两条流水线交错提交。
            plugin.getLogger().fine("[MUZ/repair/ddz] 该桌重建已在飞行中，跳过重复派发: " + previous.tableName());
            return null;
        }
        return freezeRebuildRequest(
            tableKey,
            previous,
            previous.anchor().clone().add(0.0, deltaY, 0.0)
        );
    }

    /**
     * 冻结一次单桌重建的参数快照。
     *
     * <p>快照只携带值（旧桌、旧/新锚点、座位归属与目标桌实例），这样后面的 region 回调完全不必
     * 回头读 {@code placedTables} / footprint 索引 / 实体索引，也就不存在跨 lane 读写非并发 Map
     * 的问题。座位归属做浅拷贝：旧 {@code PlacedTable} 之后没人再引用它，不需要深拷贝开销。
     */
    private RebuildRequest freezeRebuildRequest(String tableKey, PlacedTable previous, Location newAnchor) {
        return new RebuildRequest(
            tableKey,
            previous.tableName(),
            previous,
            previous.anchor().clone(),
            newAnchor.clone(),
            previous.yaw(),
            previous.ownerId(),
            previous.ownerName(),
            new LinkedHashMap<>(previous.seatAssignments()),
            plugin.getTableManager().getTable(previous.tableName())
        );
    }

    /**
     * 顺序执行一批已冻结的单桌重建请求。
     *
     * <p>【为什么必须顺序化】{@code placedTables} 与 footprint/实体索引都是非并发 Map，而
     * {@code refresh}（读）与收口步骤（写）都会碰它们。若 N 张桌并行推进，多个 region 回调与
     * global 收口会并发读写同一批 LinkedHashMap，轻则丢索引、重则遍历时死循环。因此这里用
     * {@code thenCompose} 把每张桌的完整流水线首尾相接：任一时刻只有一个 lane 在推进。
     *
     * <p>单桌失败只记录日志并继续后面的桌——丢一整批比丢一张更糟。失败桌保持"未放置"状态，
     * 等下一次重建或修复，绝不会被当成已重建。
     *
     * @param requests     调用 lane 已完成摘除与索引清理的冻结请求
     * @param afterRefresh 每桌刷新后在锚点 region 内执行的一次性收尾（可为 null）
     */
    private CompletionStage<Void> runRebuildBatch(List<RebuildRequest> requests, Consumer<PlacedTable> afterRefresh) {
        if (requests == null || requests.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
        for (RebuildRequest request : requests) {
            chain = chain.thenCompose(ignored -> runSingleRebuild(request, afterRefresh)
                .exceptionally(failure -> {
                    plugin.getLogger().warning("牌桌异步重建失败: " + request.tableName()
                        + "，原因=" + failure.getMessage());
                    return null;
                }));
        }
        return chain;
    }

    /**
     * 单桌重建流水线：加载 → 清旧 → 生成新 → 收口索引 → 刷新世界体。
     *
     * <p>lane 分配遵循两条硬约束：
     * <ul>
     *   <li><b>先加载再动世界</b>：区块没加载时旧实体删不掉、新实体也建不出来，重建等于把整桌
     *       实体丢光。因此旧锚点 footprint（清旧实体前）与新锚点 footprint（生成前）都必须先就绪。</li>
     *   <li><b>清旧实体投旧锚点、生成投新锚点</b>：{@code shiftAllAnchors} 会改变锚点，跨 region 时
     *       在新锚点线程上清旧实体非法。</li>
     * </ul>
     * 失败或关闭时流水线会提前退出，绝不在未完成状态下提交索引（见 {@link #commitRebuiltTable}）。
     *
     * <p>【闸门必须前移到 spawn 之前】身份/关闭/放置代次检查在这里先做一次：spawn 之后才发现不能提交，
     * 会留下一批既无 owner 也无索引的孤儿实体与方块（只有真实世界里的残留，没有任何登记可以再找到它）。
     * 收口阶段仍会再查一次（并发拆桌、关闭可能发生在这两步之间）；那一次若拦下，新生成的桌子必须投回
     * **它自己的新锚点 region** 清理（{@link #dispatchRebuildCleanupOnOwnerRegion}），不能在 global
     * 或旧锚点 region 上删新锚点侧的实体。
     */
    private CompletionStage<Void> runSingleRebuild(RebuildRequest request, Consumer<PlacedTable> afterRefresh) {
        Location oldAnchor = request.oldAnchor();
        Location newAnchor = request.newAnchor();
        CompletionStage<Void> loads = footprintLoadsForRebuild(oldAnchor, newAnchor);
        // 旧锚点 region：只清旧实体与旧锚点侧的残留。
        CompletionStage<Void> cleaned = dispatchOwnerRegionAfter(
            loads,
            oldAnchor,
            () -> cleanupPlacedTable(request.previous())
        );
        // 新锚点 region：闸门通过后才扫新锚点残留并生成新桌；region 回调只读冻结快照，返回构建结果。
        CompletionStage<PlacedTable> rebuilt = dispatchOwnerRegionValueAfter(
            cleaned,
            newAnchor,
            () -> {
                String rejection = rebuildGateRejection(request);
                if (rejection != null) {
                    plugin.getLogger().warning("牌桌重建前置闸门拦下，不再生成新桌: "
                        + request.tableName() + "，原因=" + rejection);
                    return null;
                }
                return rebuildTableOnOwnerRegion(request);
            }
        );
        CompletionStage<Void> committed = rebuilt.thenCompose(value -> {
            if (value == null) {
                // 前置闸门或 spawn 自身已放弃：没有任何新世界体需要收口或清理。
                return CompletableFuture.completedFuture(null);
            }
            return runGlobalStage(() -> {
                    // 收口 throw 也必须走同一条收尾：不能让 thenCompose 的失败分支绕过孤儿清理。
                    try {
                        return commitRebuiltTable(request, value);
                    } catch (RuntimeException | Error failure) {
                        plugin.getLogger().warning("牌桌重建收口异常: " + request.tableName()
                            + "，原因=" + failure.getMessage());
                        return null;
                    }
                })
                .thenCompose(committedTable -> {
                    if (committedTable != null) {
                        // 新锚点 region：刷新世界体（状态/座位/按钮/手牌）与一次性收尾。刷新会碰实体，
                        // 必须留在锚点 region，不能在 global 收口线程上做。
                        return runRegionStage(newAnchor,
                            () -> refreshRebuiltTableOnOwnerRegion(request, committedTable, afterRefresh));
                    }
                    // 已生成但收口没通过：新实体必须投回它自己的锚点 region 清理。
                    return dispatchRebuildCleanupOnOwnerRegion(
                        newAnchor,
                        request.tableName(),
                        () -> cleanupPlacedTable(value)
                    );
                });
        });
        // 无论成功、被闸门拒绝还是异常，都必须撤掉在飞标记，否则该桌永远无法再次重建。
        return committed.whenComplete((ignored, failure) ->
            rebuildingTableKeys.remove(request.tableKey()));
    }

    /**
     * 重建的身份/关闭/放置代次闸门，前置与收口共用同一份判据。
     *
     * <p>用「拒绝原因」而不是布尔，是为了让两处调用点各自记日志时还能保留原来那条区分度高的信息
     * （关闭 / 实例已注销 / 放置状态被改写），同时保证两处判据不会漂移。
     *
     * @return 通过时返回 {@code null}，否则返回拒绝原因
     */
    private String rebuildGateRejection(RebuildRequest request) {
        if (plugin.isShuttingDown()) {
            return "插件正在关闭";
        }
        GameTable current = plugin.getTableManager().getTable(request.tableName());
        if (current == null || current != request.table()) {
            return "牌桌实例已注销或已替换";
        }
        if (placedTables.get(request.tableKey()) != request.previous()) {
            // 放置状态已不是冻结时那一份：期间被拆桌或另一条清理路径改过，收口不得再复活它。
            return "牌桌放置状态已被其它路径改写";
        }
        return null;
    }

    /**
     * 清理"已生成但收口没通过"的重建新桌，**必须**投到该桌自己的锚点 region。
     *
     * <p>抽成包内可见接缝是为了让「闸门拒绝后清理 stage 真实执行、且不在错误 region」这条能用记录型
     * 调度后端做行为级验证（见 {@code RebuildPipelineLaneOrderBehaviorTest}）。
     */
    CompletionStage<Void> dispatchRebuildCleanupOnOwnerRegion(
        Location ownerAnchor,
        String tableName,
        Runnable cleanupBody
    ) {
        plugin.getLogger().warning("牌桌重建已生成但无法提交，清理新实体: " + tableName);
        return runRegionStage(ownerAnchor, cleanupBody).exceptionally(failure -> {
            plugin.getLogger().warning("未能提交的重建清理失败: " + tableName
                + "，原因=" + failure.getMessage());
            return null;
        });
    }

    /**
     * 一次重建需要先加载的 footprint：旧锚点（清旧实体前）与新锚点（生成前）。
     * 两个锚点落在同一区块时只加载一份，避免对同一批区块重复发起异步加载。
     */
    private CompletionStage<Void> footprintLoadsForRebuild(Location oldAnchor, Location newAnchor) {
        CompletionStage<Void> loads = ensureFootprintLoadedAsync(oldAnchor);
        if (sameAnchorChunk(oldAnchor, newAnchor)) {
            return loads;
        }
        return loads.thenCompose(ignored -> ensureFootprintLoadedAsync(newAnchor));
    }

    /**
     * 把世界体投到目标锚点 region，且**必须**等给定前置 stage 完成之后才投递。
     *
     * <p>抽成包内可见接缝是为了让「异步加载先于 owner region 世界体」这条顺序能用 fake World +
     * 记录型调度后端做**行为级**验证（见 {@code RebuildPipelineLaneOrderBehaviorTest}），
     * 而不是只对源码做字符串匹配；生产重建流水线的每一步都经它串联。
     */
    CompletionStage<Void> dispatchOwnerRegionAfter(
        CompletionStage<?> prerequisite,
        Location ownerAnchor,
        Runnable worldBody
    ) {
        return prerequisite.thenCompose(ignored -> runRegionStage(ownerAnchor, worldBody));
    }

    /**
     * {@link #dispatchOwnerRegionAfter} 的带返回值版本，用于需要把构建结果交回调用 chain 的步骤。
     */
    <T> CompletionStage<T> dispatchOwnerRegionValueAfter(
        CompletionStage<?> prerequisite,
        Location ownerAnchor,
        Supplier<T> worldBody
    ) {
        return prerequisite.thenCompose(ignored -> runRegionStageValue(ownerAnchor, worldBody));
    }

    /**
     * 单桌重建的世界体，只能在目标锚点 region 内调用。
     *
     * <p>先扫新锚点残留：启动预热可能对同一批持久化桌连续重建多次，旧椅子/桌子若有一次躲过
     * 追踪清理，下一次重建就会在它上面再叠一层。
     */
    private PlacedTable rebuildTableOnOwnerRegion(RebuildRequest request) {
        if (plugin.isShuttingDown()) {
            // 关闭中不得再生成新实体：迟到的流水线只能放弃，不能留下无人清理的世界残留。
            return null;
        }
        // HARD-CODED REBUILD SAFETY:
        // Startup warmup can rebuild the same persisted tables multiple times.
        // If any old chair/table visual survives tracked cleanup, the next rebuild would stack another copy on top.
        // Always purge anchor-side residual world artifacts before respawning the rebuilt table.
        purgeResidualWorldArtifacts(request.newAnchor().clone(), request.yaw());
        GameTable table = request.table();
        if (table == null) {
            return null;
        }
        PlacedTable rebuilt = spawnTable(
            table,
            request.newAnchor().clone(),
            request.yaw(),
            request.ownerId(),
            request.ownerName()
        );
        rebuilt.seatAssignments().putAll(request.seatAssignments());
        return rebuilt;
    }

    /**
     * 在 global 收口 lane 上提交重建结果：写入 {@code placedTables} 与 footprint/实体索引，
     * 并重绑 owner 周期任务。region 回调不得写这些非并发结构，所有写入都在这里一次完成。
     *
     * <p>闸门与 spawn 前那次是同一份判据（见 {@link #rebuildGateRejection}）：关闭、桌实例被注销/替换、
     * 放置状态被别的路径改写，都不得再提交——迟到的重建结果绝不能复活一张已经被移除的牌桌。闸门在
     * 收口才拦下意味着新桌**已经生成**，调用方必须据此做锚点 region 清理，不能只把结果丢掉。
     *
     * @return 提交成功时返回新桌，被闸门拦下时返回 null
     */
    private PlacedTable commitRebuiltTable(RebuildRequest request, PlacedTable rebuilt) {
        if (rebuilt == null) {
            return null;
        }
        String rejection = rebuildGateRejection(request);
        if (rejection != null) {
            plugin.getLogger().warning("放弃提交牌桌重建结果: " + request.tableName() + "，原因=" + rejection);
            return null;
        }
        putPlacedTable(request.tableKey(), rebuilt);
        notifyTableAnchorBinding(request.table());
        return rebuilt;
    }

    /**
     * 提交成功后的锚点 region 刷新：世界体渲染与一次性收尾都留在这里做。
     * 收口被闸门拦下（{@code committedTable == null}）时整体跳过，避免对着不存在的桌子刷新。
     */
    private void refreshRebuiltTableOnOwnerRegion(
        RebuildRequest request,
        PlacedTable committedTable,
        Consumer<PlacedTable> afterRefresh
    ) {
        if (committedTable == null) {
            return;
        }
        // 这里拿的就是刚收口提交的新桌，**不走** world-body 门禁（标记此刻还在），否则重建后永远不会刷新。
        refreshWith(request.table(), committedTable);
        if (afterRefresh != null) {
            afterRefresh.accept(committedTable);
        }
    }

    /** 两个锚点是否落在同一世界的同一区块（用于省掉重复的 footprint 异步加载）。 */
    private static boolean sameAnchorChunk(Location left, Location right) {
        if (left == null || right == null) {
            return false;
        }
        World leftWorld = left.getWorld();
        World rightWorld = right.getWorld();
        if (leftWorld == null || rightWorld == null) {
            return false;
        }
        return leftWorld.getUID().equals(rightWorld.getUID())
            && (left.getBlockX() >> 4) == (right.getBlockX() >> 4)
            && (left.getBlockZ() >> 4) == (right.getBlockZ() >> 4);
    }

    private boolean isIncomplete(PlacedTable placed) {
        return !incompleteReason(placed).isBlank();
    }

    private String incompleteReason(PlacedTable placed) {
        List<String> missing = new ArrayList<>();
        if (placed.statusDisplayId() == null || Bukkit.getEntity(placed.statusDisplayId()) == null) {
            missing.add("status");
        }
        if (placed.playDetailDisplayId() == null || Bukkit.getEntity(placed.playDetailDisplayId()) == null) {
            missing.add("play-detail");
        }
        if (placed.seatNameDisplayIds().size() < 3 || placed.seatInfoDisplayIds().size() < 3) {
            missing.add("seat-display-count");
        }
        for (UUID id : placed.seatNameDisplayIds()) {
            if (Bukkit.getEntity(id) == null) {
                missing.add("seat-name");
                break;
            }
        }
        for (UUID id : placed.seatInfoDisplayIds()) {
            if (Bukkit.getEntity(id) == null) {
                missing.add("seat-info");
                break;
            }
        }
        return String.join(",", missing);
    }

    public void refresh(GameTable table) {
        // 统一刷新入口：桌面状态、座位信息、按钮、私人手牌都在这里协同更新
        if (table == null) {
            return;
        }
        PlacedTable placed = placedTableForWorldBody(table.getName());
        if (placed == null) {
            return;
        }
        refreshWith(table, placed);
    }

    /**
     * 刷新世界体的实体部分，直接消费已确定的 {@code PlacedTable}。
     *
     * <p>拆出这一层，是为了让重建流水线收尾的那次刷新能拿**刚提交的新桌**直接刷新，
     * 而不必走 {@link #placedTableForWorldBody} 的在飞门禁（重建标记要到整条链结束才撤）。
     *
     * <p>【为什么在这里做 lane 门禁】这是所有世界体刷新（{@link #refresh}、
     * {@link #refreshPrivateHand} 走的手牌分支、重建收尾、ChunkLoad 修复收尾）的共同出口，
     * 门禁放在这一层才不会被某条路径漏掉。当前线程不拥有锚点 region 时，后面每一个
     * {@code Bukkit.getEntity(...)} / {@code CraftTextDisplay.text(...)} / {@code show/hide} 都非法：
     * 实服日志里表现为 {@code Accessing entity state off owning region's thread}（{@code region={null}}，
     * 即牌桌周期任务被切到 global lane 的情形）。此时直接放弃本次世界体刷新——牌桌状态已经推进，
     * 下一次在正确 lane 上的刷新会照常重画，绝不能让异常逃到调度器（那会取消周期任务，让桌子永久停摆）。
     */
    private void refreshWith(GameTable table, PlacedTable placed) {
        if (table == null || placed == null) {
            return;
        }
        if (!worldBodyLane.isOwnedByCurrentRegion(placed.anchor())) {
            reportWorldBodyLaneSkip("刷新", placed);
            return;
        }
        reconcileSeatAssignments(table, placed);
        refreshStatus(table, placed);
        refreshPlayDetail(table, placed);
        refreshSeatInfos(table, placed);
        refreshActionButtons(table, placed);
        refreshPrivateHands(table, placed);
        reindexPlacedTableEntities(normalize(placed.tableName()), placed);
        plugin.persistDoudizhuTable(table.getName(), table.getRoomLevel(), placed.anchor(), placed.yaw(), placed.ownerId(), placed.ownerName());
    }

    /**
     * 世界体刷新因 lane 不归属而跳过时的限频日志。
     *
     * <p>【为什么必须留痕】这条分支是"按设计放弃"，但静默放弃会让「桌子停在 global、世界体再也刷不动」
     * 这类问题在服务端完全不可见（实服里只有一行行堆栈，看不出是哪条路径被跳过）。刷新会被每 tick /
     * 每 2 秒的 owner tick 命中，所以按桌限频，每 200 次最多记一条，避免把控制台冲掉。
     */
    private void reportWorldBodyLaneSkip(String action, PlacedTable placed) {
        String key = normalize(placed.tableName());
        if (key == null) {
            return;
        }
        long count = worldBodySkipCounters.merge(key, 1L, Long::sum);
        if (count % WORLD_BODY_SKIP_LOG_INTERVAL == 1L) {
            plugin.getLogger().warning("跳过牌桌世界体" + action + "（当前线程不拥有锚点 region）: "
                + placed.tableName() + "，累计跳过 " + count + " 次");
        }
    }

    public void refreshPrivateHand(GameTable table, UUID playerId) {
        if (table == null) {
            return;
        }
        PlacedTable placed = placedTableForWorldBody(table.getName());
        if (placed == null) {
            return;
        }
        // 手牌渲染同样读写世界实体（Display/Interaction 与按玩家可见性），必须在锚点 owner lane 上执行；
        // 与 refreshWith 同一门禁，避免"只刷手牌"这条窄路径绕过世界体 lane 判定。
        if (!worldBodyLane.isOwnedByCurrentRegion(placed.anchor())) {
            reportWorldBodyLaneSkip("手牌刷新", placed);
            return;
        }
        renderPrivateHand(table, placed, playerId);
        reindexPlacedTableEntities(normalize(placed.tableName()), placed);
    }

    public Location tableAnchor(String tableName) {
        PlacedTable placed = placedTable(tableName);
        return placed == null ? null : placed.anchor().clone();
    }

    /** 放置状态变化后通知逻辑桌周期注册表选择正确的调度 lane。 */
    private void notifyTableAnchorBinding(GameTable table) {
        if (table == null) {
            return;
        }
        if (!plugin.getTableManager().rebindTablePeriodicTask(table, tableAnchor(table.getName()))) {
            throw new IllegalStateException("牌桌周期任务未重绑定，桌实例可能已注销: " + table.getName());
        }
    }

    private void rollbackPlacedTableAfterPlacementFailure(
        String tableKey,
        GameTable table,
        boolean newlyCreated,
        Throwable failure
    ) {
        PlacedTable placed = removePlacedTable(tableKey);
        if (placed != null) {
            try {
                cleanupPlacedTable(placed);
            } catch (RuntimeException | Error cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
        rollbackNewTableAfterPlacementFailure(table, newlyCreated, failure);
    }

    private void rollbackNewTableAfterPlacementFailure(GameTable table, boolean newlyCreated, Throwable failure) {
        if (!newlyCreated || table == null) {
            return;
        }
        try {
            plugin.getTableManager().unregisterTable(table.getName());
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        try {
            table.shutdown();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    public float tableYaw(String tableName) {
        PlacedTable placed = placedTable(tableName);
        return placed == null ? 0.0f : placed.yaw();
    }

    public boolean canRemoveTable(Player player, String tableName) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission("muz.admin")) {
            return true;
        }
        PlacedTable placed = placedTable(tableName);
        return placed != null && placed.ownerId() != null && placed.ownerId().equals(player.getUniqueId());
    }

    public String removeDeniedReason(Player player, String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return "找不到这张牌桌。";
        }
        if (player != null && player.hasPermission("muz.admin")) {
            return "";
        }
        if (placed.ownerId() == null) {
            return "这张牌桌没有记录放置者，只有管理员才能拆。";
        }
        String owner = placed.ownerName() == null || placed.ownerName().isBlank() ? "原放置者" : placed.ownerName();
        return "这张牌桌是 " + owner + " 放的，你不能拆。";
    }

    public String targetedTable(Player player, double maxDistance) {
        if (player == null) {
            return null;
        }
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector direction = eye.getDirection().normalize();
        String bestTable = null;
        double bestDistance = Double.MAX_VALUE;
        for (PlacedTable placed : placedTables.values()) {
            Location center = previewTableCenter(placed.anchor());
            double distance = sightDistance(eye, direction, center, 1.25, maxDistance);
            if (distance >= 0.0 && distance < bestDistance) {
                bestDistance = distance;
                bestTable = placed.tableName();
            }
        }
        return bestTable;
    }

    private void hidePrivateEntitiesFrom(UUID viewerId, PlacedTable placed) {
        if (viewerId == null || placed == null) {
            return;
        }
        GameTable table = plugin.getTableManager().getTable(placed.tableName());
        for (Map.Entry<UUID, List<UUID>> entry : placed.privateEntitiesByPlayer().entrySet()) {
            if (entry.getKey().equals(viewerId)) {
                continue;
            }
            // 明牌那家的正面牌本局对所有人公开，这里不能再一律隐藏，
            // 否则新进服或重新同步的玩家会看不到已经明出来的牌。
            // 判定框仍然只归牌主，所以这里只放开牌面显示。
            if (table != null && table.isHandRevealed(entry.getKey())) {
                for (UUID entityId : entry.getValue()) {
                    Entity entity = Bukkit.getEntity(entityId);
                    if (entity instanceof Interaction) {
                        playerOutput.hideEntity(viewerId, plugin, entity);
                    } else if (entity != null) {
                        playerOutput.showEntity(viewerId, plugin, entity);
                    }
                }
                continue;
            }
            for (UUID entityId : entry.getValue()) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) {
                    playerOutput.hideEntity(viewerId, plugin, entity);
                }
            }
        }
    }

    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.isShuttingDown()) {
            return;
        }
        UUID viewerId = viewer.getUniqueId();
        markPlayerConnected(viewerId);
        syncViewer(viewerId);
    }

    /**
     * 跨桌 viewer 同步只负责拍 UUID 快照并逐桌投递 owner；不在 global/player 回调中直接改实体。
     */
    private void syncViewer(UUID viewerId) {
        if (viewerId == null || plugin.isShuttingDown()) {
            return;
        }
        clearHover(viewerId);
        actionSignatureByTable.clear();
        privateHandSignatureByTable.clear();
        backsideHandSignatureByTable.clear();
        List<String> tableNames = placedTables.values().stream()
            .map(PlacedTable::tableName)
            .toList();
        for (String tableName : tableNames) {
            GameTable table = plugin.getTableManager().getTable(tableName);
            if (table == null) {
                continue;
            }
            plugin.getTableManager().runTableNow(table, () -> syncViewerOnOwner(table, viewerId));
        }
    }

    /**
     * 在单桌 owner 内完成实体修复、变换与 viewer 输出投递。
     *
     * <p>不完整桌的重建现在是异步流水线，因此这里分成两段：投递重建并把后续同步挂到重建完成回调上，
     * 由回调重新投回本桌 owner lane 再动实体——既不能在 global 收口线程上改实体，也不能在新桌
     * 尚未生成时读取 {@code placedTable}。完整桌仍走原同步路径，次数与语义不变。
     */
    private void syncViewerOnOwner(GameTable table, UUID viewerId) {
        if (table == null || viewerId == null || plugin.isShuttingDown()) {
            return;
        }
        PlacedTable placed = placedTable(table.getName());
        if (placed == null) {
            return;
        }
        // 与 continueViewerSyncOnOwner 同理：锚点区块已卸载时本任务落在 global，incompleteReason 会读实体，必须跳过。
        if (!Bukkit.isOwnedByCurrentRegion(placed.anchor())) {
            return;
        }
        String reason = incompleteReason(placed);
        if (reason.isBlank() || isRebuildInFlight(normalize(placed.tableName()))) {
            // 重建在飞时旧实体正在被替换：这里不能再把"实体查不到"当成不完整去触发又一次重建
            // （会和在飞流水线撞互斥，还会刷一条误导告警）。直接走同步收尾：refresh 会因世界体
            // 门禁让位，椅子判定框一次性恢复仍在（历史隐藏状态不会自愈，不能连它一起跳过）。
            continueViewerSyncOnOwner(table, viewerId);
            return;
        }
        plugin.getLogger().fine("[MUZ/viewer-sync/ddz] viewer=" + viewerId + " table="
            + placed.tableName() + " incomplete=" + reason);
        rebuildSingleTable(table.getName()).whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().warning("[MUZ/viewer-sync/ddz] 重建后同步失败: viewer=" + viewerId
                    + " table=" + table.getName() + "，原因=" + failure.getMessage());
                return;
            }
            if (plugin.isShuttingDown()) {
                return;
            }
            plugin.getTableManager().runTableNow(table, () -> continueViewerSyncOnOwner(table, viewerId));
        });
    }

    /** 本桌是否有一条重建流水线在飞（归一化桌名）。 */
    private boolean isRebuildInFlight(String tableKey) {
        return tableKey != null && rebuildingTableKeys.contains(tableKey);
    }

    /** 单桌 viewer 同步的实体部分；必须在本桌 owner lane 内执行。 */
    private void continueViewerSyncOnOwner(GameTable table, UUID viewerId) {
        if (table == null || viewerId == null || plugin.isShuttingDown()) {
            return;
        }
        PlacedTable placed = placedTable(table.getName());
        if (placed == null) {
            return;
        }
        // Folia：桌子区块卸载后 owner 锚点被清空，runTableNow 会回落到 global lane；
        // global 上不得读世界/实体（getNearbyEntities 会抛 "Cannot getEntities asynchronously"）。
        // 此时桌子不在任何玩家附近，跳过即可，区块重新加载后由 ChunkLoad 修复链路刷新。
        if (!Bukkit.isOwnedByCurrentRegion(placed.anchor())) {
            return;
        }
        refresh(table);
        // 椅子判定框的恢复只在这类一次性时机做，不放在刷新链路里。
        // hideEntity 的隐藏状态按玩家持久，旧版本藏起来的实体不会自愈，
        // 所以每个重新进入视野的 viewer 都要显式恢复一次；
        // 但放进 syncActionWidgets 就会变成每次出牌都重发，把坐着的玩家挤开。
        restoreOccupiedChairHitboxVisibility(placed, List.of(viewerId));
        showPublicEntitiesTo(viewerId, placed.staticEntities());
        showPublicEntitiesTo(viewerId, placed.seatNameDisplayIds());
        showPublicEntitiesTo(viewerId, placed.seatInfoDisplayIds());
        if (placed.statusDisplayId() != null) {
            showPublicEntitiesTo(viewerId, List.of(placed.statusDisplayId()));
        }
        if (placed.playDetailDisplayId() != null) {
            showPublicEntitiesTo(viewerId, List.of(placed.playDetailDisplayId()));
        }
        // staticEntities 与上面的显式 show 会无条件显示 playDetail，
        // 这里在其后按阶段/入座权威重算一次，确保开局后入座真人（含刚上线的这名 viewer）不被重新显示出来。
        updatePlayDetailVisibility(table, placed);
        hidePrivateEntitiesFrom(viewerId, placed);
    }

    private void showPublicEntitiesTo(UUID viewerId, List<UUID> entityIds) {
        for (UUID entityId : entityIds) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                playerOutput.showEntity(viewerId, plugin, entity);
            }
        }
    }

    private double sightDistance(Location eye, org.bukkit.util.Vector direction, Location center, double radius, double maxDistance) {
        return TableEntityGeometry.sightDistance(eye, direction, center, radius, maxDistance);
    }

    public boolean handleInteraction(Player player, Entity entity) {
        ActionBinding binding = actionBindings.get(entity.getUniqueId());
        if (binding != null) {
            if (!isWithinActionInteractionRange(player, entity)) {
                hint(player.getUniqueId(), "靠近一点再点击。", NamedTextColor.YELLOW);
                return true;
            }
            GameTable table = plugin.getTableManager().getTable(binding.tableName());
            if (table == null) {
                // 静默失败最难查：残留按钮指向已销毁的牌桌时必须给玩家反馈
                hint(player.getUniqueId(), "该牌桌已不存在，按钮已失效。", NamedTextColor.RED);
                plugin.getLogger().warning("玩家 " + player.getName() + " 点击了已失效的按钮，关联牌桌: " + binding.tableName());
                return true;
            }

            try {
                switch (binding.action()) {
                    case JOIN -> joinSeat(table, placedTable(table.getName()), player, binding.seatIndex());
                    case READY -> table.toggleReady(player);
                    case START -> table.startRound(player);
                    case STATUS -> hint(player.getUniqueId(), "抬头看桌子上方的状态牌。", NamedTextColor.YELLOW);
                    case LEAVE -> plugin.getTableManager().leaveTable(player);
                    case PLAY_SELECTED -> table.playSelected(player);
                    case PASS_TURN -> table.pass(player);
                    case HINT_PLAY -> applyHint(table, player);
                    case CLEAR_SELECTION -> {
                        table.clearSelection(player.getUniqueId());
                        refreshPrivateHand(table, player.getUniqueId());
                        hint(player.getUniqueId(), "已清除已选牌。", NamedTextColor.GRAY);
                    }
                    case DOUBLE_NO -> table.chooseDouble(player, false);
                    case DOUBLE_YES -> table.chooseDouble(player, true);
                    case OPEN_SETTINGS -> {
                        plugin.getHandGuiService().openSettings(player);
                        hint(player.getUniqueId(), "你的个人设置菜单开好了。", NamedTextColor.GREEN);
                    }
                    // 道具栏已改为牌桌内屏幕额外栏；这里仅刷新数据，不再打开九格 Inventory GUI。
                    case GADGET -> {
                        if (plugin.getTableGadgetBarHudService() != null) {
                            plugin.getTableGadgetBarHudService().refresh(player.getUniqueId());
                        }
                        hint(player.getUniqueId(), "牌桌额外道具栏已显示。", NamedTextColor.GREEN);
                    }
                    case BID_0 -> table.bid(player, 0);
                    case BID_1 -> table.bid(player, 1);
                    case BID_2 -> table.bid(player, 2);
                    case BID_3 -> table.bid(player, 3);
                    case REVEAL_HAND -> table.revealHand(player);
                }
                refresh(table);
            } catch (RuntimeException exception) {
                hint(player.getUniqueId(), exception.getMessage(), NamedTextColor.RED);
            }
            return true;
        }

        // 椅子放最后：前两个是哈希查表，这个要遍历牌桌算坐标。
        // 椅子是纯装饰，只负责坐下，不加入牌桌；加入走桌面的加入按钮。
        // 注意这里返回 false，事件不能被吞掉，否则 CraftEngine 收不到就坐不下去。
        handleChairSeatInteraction(player, entity);
        return false;
    }

    /**
     * 手牌点击：右键选中/取消选中，左键把已选的牌打出去。
     *
     * <p>牌上不再挂 Interaction 触发器，点击因此不走实体事件，而是由
     * {@code PlayerInteractEvent} 转进来，点到哪张牌一律由 {@link #pickHandCard} 解析裁决——
     * 和悬停高亮出自同一份计算，不可能出现「高亮的是这张、翻的是另一张」。
     *
     * <p>删掉触发器顺带消灭了它自带的死区：Interaction 的碰撞箱是正方形，在牌面之外的深度
     * 方向鼓出约半个牌宽，那圈里右键会触发事件但求交判不中，只能吞掉，于是贴着牌边点桌面
     * 既选不到牌也放不了方块。现在判不中就返回 false 放行，方块照常能放。
     *
     * @param player 点击的玩家
     * @param rightClick 右键为 true（选牌），左键为 false（出牌）
     * @return 已当成手牌点击处理时返回 true，调用方需要取消事件
     */
    public boolean handleHandCardClick(Player player, boolean rightClick) {
        GameTable table = plugin.getTableManager().getTableOf(player);
        if (table == null) {
            return false;
        }
        if (table.isOpeningHandLocked()) {
            hint(player.getUniqueId(), table.openingRevealLabel(), NamedTextColor.YELLOW);
            return true;
        }
        PlacedTable placed = placedTable(table.getName());
        if (placed == null || table.getHand(player.getUniqueId()).isEmpty()) {
            return false;
        }
        // 一次右键会为主手和副手各发一次事件。不去重的话 toggle 会执行两遍，等于没点。
        // 这里不靠"只认主手"来去重：主手空手时某些客户端只发得出副手那一次。
        long tick = Bukkit.getCurrentTick();
        Long handled = lastHandCardClickTicks.get(player.getUniqueId());
        // 追踪：同 tick 去重是否命中，能直接看出"点了没反应"是不是重复事件导致的
        trace(player, NamedTextColor.YELLOW, () ->
            "handleHandCardClick 同 tick 去重: "
            + (handled != null && handled == tick ? "命中(已处理过的重复事件, tick=" + tick + ")" : "未命中"));
        if (handled != null && handled == tick) {
            return true;
        }
        HandCardPickGeometry.Hit hit = pickHandCard(table, placed, player);
        // 追踪：handleHandCardClick 内部的解析拾取结果（与 BlockedBy 里那一次是独立的计算）
        trace(player, NamedTextColor.AQUA, () ->
            "handleHandCardClick 拾取: "
            + (hit == null ? "null" : "card#" + hit.cardId() + "(idx=" + hit.index() + ")"));
        if (hit == null) {
            return false;
        }
        lastHandCardClickTicks.put(player.getUniqueId(), tick);
        if (rightClick) {
            toggleHandCardSelection(table, placed, player, hit.cardId());
        } else {
            playSelectedHandCard(table, player, hit.cardId());
        }
        return true;
    }

    /**
     * 某个实体抢走了本该算作点牌的这次点击时，判定这次点击归谁。
     *
     * <p>为什么需要这条路：牌是 ItemDisplay 没有判定框，所以「点牌」本来只走
     * {@code PlayerInteractEvent}。但客户端的实体射线会直接跳过牌，命中牌【后面】
     * 桌子家具的 CE 判定框。准星落在判定框上时客户端只发实体事件
     * （右键 {@code PlayerInteractEntityEvent}、左键 {@code EntityDamageByEntityEvent}，
     * 因为判定框 setResponsive(true)），{@code PlayerInteractEvent} 压根不触发，
     * 于是 onHandCardClick 永远不执行——表现就是牌高亮得好好的却选不动、出不掉。
     * 悬停不受影响，因为它走 tick 里的解析求交，从不看实体。
     *
     * <p>按「这个实体自己能不能消费这次点击」让位，不比距离。距离方案试过，不可靠：
     * 桌子家具的 CE 判定框是一整块，手牌很可能落在它内部，此时射线先撞判定框前表面，
     * 算出来的实体距离反而比牌近，仲裁会判桌子赢——点击照样被吞，等于没修。
     * 而那个尺寸由 CraftEngine 配置决定，不在本插件控制下，任何依赖它的阈值都是空中楼阁。
     *
     * <p>能消费点击的只有两类：带 ActionBinding 的按钮判定框，和椅子家具（玩家要坐上去，
     * 且它被 shouldCancelProtectedInteract 特意放行）。这两类让位。
     * 其余实体（桌子本体、装饰）没有 binding，点它们本来就走到静默取消、什么都不发生，
     * 让手牌赢不损失任何既有行为。
     *
     * @param player 点击的玩家
     * @param rightClick true 表示右键选牌，false 表示左键出牌
     * @param blocking 抢到这次点击的实体
     * @return 已经当成手牌点击处理时返回 true，调用方需要取消事件
     */
    public boolean handleHandCardClickBlockedBy(Player player, boolean rightClick, Entity blocking) {
        // 追踪：记录阻塞实体的类型、是否为 null，以及本次是右键还是左键
        trace(player, NamedTextColor.DARK_AQUA, () ->
            "BlockedBy 入: rightClick=" + rightClick
            + " blocking=" + (blocking == null ? "null" : blocking.getType().name()));
        if (blocking == null) {
            return false;
        }
        boolean furniture = isLikelyFurnitureEntity(blocking);
        boolean hasBinding = actionBindings.containsKey(blocking.getUniqueId());
        boolean inRange = isWithinActionInteractionRange(player, blocking);
        boolean usableButton = hasBinding && inRange;
        boolean chairFurniture = isChairFurnitureEntity(blocking.getUniqueId());
        boolean yield = yieldsToBlockingEntity(furniture, usableButton, chairFurniture);
        // 追踪：把 yieldsToBlockingEntity 的三个入参和返回值都打出来，
        // 一眼能看出让位判据到底怎么裁决的，不用再去扣源码
        trace(player, NamedTextColor.DARK_AQUA, () ->
            "yields 入→出: furniture=" + furniture + " usableButton=" + usableButton
            + " chairFurniture=" + chairFurniture + " -> yield=" + yield);
        // 按钮要连「够得着」一起判。让位的前提是那个实体真能消费这次点击，
        // 而按钮超过 MAX_ACTION_INTERACTION_DISTANCE 时 handleInteraction 只会回一句
        // 「靠近一点再点击」再把事件吃掉——那不算消费，让位给它就是白丢一次点牌。
        //
        // 这个分支多久真的走到，取决于客户端在多远还会发实体事件，那个值不在本插件里，
        // 没有实测数据。所以别把它当成「修了某个已知场景」：它是把判据和自己的定义对齐，
        // 即使实际永远不触发也不会让任何情况变坏。
        //
        // 提示不会因此消失：只有命中牌时才由手牌接手；没命中牌时仲裁仍返回 false，
        // handleInteraction 照样跑到那句提示。
        if (yield) {
            return false;
        }
        // 已知冗余：这里判一次命中，紧接着 handleHandCardClick 内部又完整算一遍
        // pickHandCard（射线 × 手牌逐张求交），同一帧跑两遍。
        // 没有消除，因为消除必须改 handleHandCardClick 的签名（把命中结果传进去）——
        // 它是 public、由事件监听器直接调用，改签名会动到调用契约。
        // 纯性能开销，两次结果同帧必然一致，不影响正确性。
        HandCardPickGeometry.Hit arbitrationPick = pickHandCardForArbitration(player);
        // 追踪：仲裁拾取结果（null = 没命中任何牌）
        trace(player, NamedTextColor.AQUA, () ->
            "仲裁拾取: " + (arbitrationPick == null ? "null" : "card#" + arbitrationPick.cardId()
                + "(idx=" + arbitrationPick.index() + ")"));
        if (arbitrationPick == null) {
            return false;
        }
        return handleHandCardClick(player, rightClick);
    }

    /**
     * 判断这次点击该不该让位给抢到它的实体。
     *
     * <p>判据统一是「点它本来就有事发生」：按钮有 ActionBinding；椅子要坐上去；
     * 怪物和玩家要打要交互。反过来，桌子本体和装饰点了本来就走到静默取消、什么都不发生，
     * 让手牌赢不损失任何既有行为。
     *
     * <p>非家具让位这一条同时挡掉一个回归：怪物晃到手牌那条带上时，左键会被判成点牌，
     * 攻击被取消还弹一句「请先右键选择要出的牌」。
     *
     * <p>家具判定必须复用 {@code isLikelyFurnitureEntity}，别另立一套：ArmorStand 也可能是家具，
     * 按 LivingEntity 一刀切会把 ArmorStand 家具挡在修法之外。
     *
     * <p>抽成 static 布尔谓词是为了能真的跑起来测：这条链路要 Bukkit 的实体，
     * 整个判断否则只能靠源码扫描断言。写法沿用 {@code shouldCancelProtectedInteract}。
     *
     * @param furniture 抢到点击的实体是否像家具
     * @param usableButton 它是否是带 ActionBinding 且当前够得着的按钮判定框。
     *     必须带上距离：超距的按钮消费不了这次点击（只会回一句「靠近一点再点击」
     *     再把事件吃掉），让位给它等于白丢一次点牌
     * @param chairFurniture 它是否是椅子家具
     * @return 需要让位时返回 true
     */
    static boolean yieldsToBlockingEntity(boolean furniture, boolean usableButton, boolean chairFurniture) {
        if (!furniture) {
            return true;
        }
        return usableButton || chairFurniture;
    }

    /**
     * 仲裁专用的手牌拾取：只判断准星有没有落在牌上，不做任何状态改动。
     *
     * @param player 点击的玩家
     * @return 命中的牌，没命中返回 null
     */
    private HandCardPickGeometry.Hit pickHandCardForArbitration(Player player) {
        GameTable table = plugin.getTableManager().getTableOf(player);
        if (table == null) {
            return null;
        }
        PlacedTable placed = placedTable(table.getName());
        if (placed == null || table.getHand(player.getUniqueId()).isEmpty()) {
            return null;
        }
        return pickHandCard(table, placed, player);
    }

    /**
     * 右键选中/取消选中一张手牌。
     *
     * <p>异常保护和左键 {@link #playSelectedHandCard} 同口径，不能省：
     * {@code table.toggleSelection} 第一步就是 {@code requireAtTable}，玩家不在
     * {@code GameTable.seats} 里就抛 {@code IllegalStateException}。异常穿出去的话，
     * 调用链上的事件处理器只来得及往控制台记一条报错，{@code event.setCancelled(true)}
     * 那一行根本执行不到，玩家侧零反馈——表现就是「右键没反应」，比报错更难查。
     *
     * <p>成因是两层座位状态会失同步：右键选牌的前置检查全程只看世界层的
     * {@code placed.seatAssignments()}（{@link #pickHandCard} 靠它算座位号），
     * 而最后一步 {@code toggleSelection} 校验的是逻辑层的 {@code GameTable.seats}。
     * 两层一旦不一致，前置全部放行、偏偏最后一步抛异常。
     *
     * <p>catch 里跟着 {@code refresh}，和左键一样：既然已经确认两层状态不一致，
     * 就顺手按逻辑层重画一次，把世界层的显示拉回去。
     */
    private void toggleHandCardSelection(GameTable table, PlacedTable placed, Player player, int cardId) {
        try {
            // 追踪：toggle 前后各打一次选中集合大小，
            // "选不动"的断点如果在这一层，前后数字会完全一样，一眼可见
            int before = table.getSelection(player.getUniqueId()).size();
            boolean wasSelected = table.getSelection(player.getUniqueId()).contains(cardId);
            trace(player, NamedTextColor.GREEN, () ->
                "toggleHandCardSelection BEFORE: card#" + cardId
                + " selectionSize=" + before + " wasSelected=" + wasSelected);
            table.toggleSelection(player.getUniqueId(), cardId);
            int after = table.getSelection(player.getUniqueId()).size();
            trace(player, NamedTextColor.GREEN, () ->
                "toggleHandCardSelection AFTER: selectionSize=" + after
                + " delta=" + (after - before));
            updatePrivateSelection(table, placed, player.getUniqueId());
            updateBacksideSelection(table, placed, player.getUniqueId());
            playSelectionSound(player.getUniqueId(), !wasSelected);
        } catch (RuntimeException exception) {
            hint(player.getUniqueId(), exception.getMessage(), NamedTextColor.RED);
            refresh(table);
        }
    }

    /**
     * 左键出牌。必须点在已选中的牌上：左键是"确认出这一手"，不是"选这一张再出"。
     */
    private void playSelectedHandCard(GameTable table, Player player, int cardId) {
        try {
            Set<Integer> selection = table.getSelection(player.getUniqueId());
            if (selection.isEmpty()) {
                hint(player.getUniqueId(), "请先右键选择要出的牌。", NamedTextColor.YELLOW);
                return;
            }
            if (!selection.contains(cardId)) {
                hint(player.getUniqueId(), "请左键点击已选中的牌来出牌。", NamedTextColor.YELLOW);
                return;
            }
            table.playSelected(player);
            refresh(table);
        } catch (RuntimeException exception) {
            hint(player.getUniqueId(), exception.getMessage(), NamedTextColor.RED);
            refresh(table);
        }
    }

    public void removeTable(String tableName) {
        GameTable table = plugin.getTableManager().getTable(tableName);
        if (table != null && table.getSeats().stream().anyMatch(playerId -> !table.isBot(playerId))) {
            throw new IllegalStateException("桌上还有人，先让他们离桌再拆。");
        }
        PlacedTable placed = removePlacedTable(tableName);
        if (placed == null) {
            throw new IllegalArgumentException("这桌还没摆出来。");
        }
        // HARD-CODED REMOVAL SAFETY:
        // After a server restart, tracked entity ids can be incomplete while world-side furniture/blocks still exist.
        // A normal tracked cleanup is not enough, so remove now also performs an anchor-based residual sweep over the
        // expected table/chair locations. Do not delete this fallback unless the user explicitly asks.
        cleanupPlacedTableOnOwnerRegion(placed);
        if (table != null) {
            plugin.getTableManager().unregisterTable(table.getName());
        }
        plugin.deletePersistedTable("DOUDIZHU", tableName);
    }

    public void forceRemoveTable(String tableName) {
        PlacedTable placed = removePlacedTable(tableName);
        if (placed != null) {
            cleanupPlacedTableOnOwnerRegion(placed);
        }
        plugin.getTableManager().unregisterTable(tableName);
        plugin.deletePersistedTable("DOUDIZHU", tableName);
    }

    public void shutdown() {
        // 放在最前、两条分支之外：关服和 reload 都必须把追踪日志的尾巴写掉。
        shutdownTraceLog();
        synchronized (chunkLoadRepairQueued) {
            chunkLoadRepairQueued.clear();
        }
        List<PlacedTable> remaining = new ArrayList<>(placedTables.values());
        if (plugin.getServer().isStopping()) {
            // 关服分支：region 可能已不可用，实体随世界销毁，只清追踪与运行态；
            // 绝不在非法 owner 上操作实体（与 MahjongTableManager.shutdown 一致）。
            for (PlacedTable placed : remaining) {
                plugin.getTableManager().cancelOwnerPeriodicTasks(placed.tableName());
            }
            placedTables.clear();
            clearPlacedTableIndexes();
            playDetailLastRefreshBucketByTable.clear();
            actionBindings.clear();
            cardBindings.clear();
            hintIndices.clear();
            hoveredCardIds.clear();
            hoverCandidateCardIds.clear();
            hoverCandidateTicksByViewer.clear();
            hoverGraceTicksByViewer.clear();
            hoverProgressByPlayer.clear();
            selectedProgressByPlayer.clear();
            actionSignatureByTable.clear();
            privateHandSignatureByTable.clear();
            backsideHandSignatureByTable.clear();
            handDealPresentations.clear();
            return;
        }
        // reload 分支：调度仍可用。每张桌登记为一个清理 request，真实删除在桌锚点 region 内执行
        // （牌桌实体属于桌的 owner region，不是调用方 region）；用完成屏障观察聚合结果，
        // 失败只记录日志，不阻塞调用线程。
        List<RegionTaskBarrier.Request> requests = new ArrayList<>();
        for (PlacedTable placed : remaining) {
            plugin.getTableManager().cancelOwnerPeriodicTasks(placed.tableName());
            requests.add(new RegionTaskBarrier.Request(
                placed.tableName(), placed.anchor().clone(), () -> cleanupPlacedTable(placed)));
        }
        shutdownCompletion = submitShutdownCleanupBarrier(requests);
        placedTables.clear();
        clearPlacedTableIndexes();
        playDetailLastRefreshBucketByTable.clear();
        actionBindings.clear();
        cardBindings.clear();
        // reload 走的是这条分支。漏掉这几张表会让 hover 映射越reload越多，
        // 并且残留条目指向已删除的实体。
    }

    /**
     * 提交 reload 关闭清理屏障。
     *
     * <p>只观察与日志，不让调用线程等待：屏障永远返回一个 future，失败不抛出，
     * 因为它在 {@code onDisable} 主线程上被调用，抛异常会打断后续的数据库 flush 与 backend 关闭。
     */
    private CompletableFuture<RegionTaskBarrier.Result> submitShutdownCleanupBarrier(
        List<RegionTaskBarrier.Request> requests
    ) {
        try {
            return new RegionTaskBarrier(plugin.scheduler(), requests, CLEANUP_BARRIER_TIMEOUT_TICKS)
                .start()
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        plugin.getLogger().warning("牌桌清理屏障异常结束: " + failure.getMessage());
                    } else if (result == null || result.status() != RegionTaskBarrier.Status.COMPLETED) {
                        plugin.getLogger().warning("牌桌清理屏障未全部完成，状态="
                            + (result == null ? "null" : result.status()));
                    }
                });
        } catch (Throwable failure) {
            plugin.getLogger().warning("牌桌清理屏障无法提交，跳过残留清理: " + failure.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 返回最近一次 reload 关闭清理的完成屏障（未关闭时为已完成空 future），供关闭流程观察，不阻塞等待。 */
    public CompletableFuture<RegionTaskBarrier.Result> shutdownCompletion() {
        return shutdownCompletion;
    }

    private void ensureWorldVisualsReady(String action) {
        if (canSafelyReplaceWorldVisuals()) {
            return;
        }
        throw new IllegalStateException("CraftEngine 的桌椅还没加载好，等会儿再" + action + "。");
    }

    private boolean canSafelyReplaceWorldVisuals() {
        org.bukkit.plugin.Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return true;
        }
        if (plugin.getCraftEngineFurnitureService().isAvailable()) {
            return true;
        }
        return usesVanillaBlockFallback(DoudizhuPlugin.FurnitureType.TABLE)
            && usesVanillaBlockFallback(DoudizhuPlugin.FurnitureType.CHAIR);
    }

    private boolean usesVanillaBlockFallback(DoudizhuPlugin.FurnitureType type) {
        ItemStack configured = plugin.getConfiguredFurnitureItem(type);
        return configured != null && configured.getType().isBlock();
    }

    /**
     * 在指定牌桌的 owner region 内推进一次世界刷新。
     *
     * <p>这里故意只接收一张牌桌：桌面 hover、私有手牌/调试实体和桌边动态都必须由
     * 该桌自己的周期任务触发，不能再由 global 任务遍历全服玩家或全部已放置牌桌。
     * 入座玩家的 Player 解析仍按 UUID 进行；没有在线 Player 时只清理该 UUID 的缓存，
     * 不会跨桌扫描或直接读取其它玩家。
     */
    public void tickTable(GameTable table) {
        if (table == null) {
            return;
        }
        // 世界体门禁：重建在飞时旧实体正在被替换，鼠标悬停/桌边动态都不能再按旧快照生成实体。
        PlacedTable placed = placedTableForWorldBody(table.getName());
        if (placed == null) {
            // 未放置的纯逻辑桌没有世界实体，不执行任何世界 tick。
            return;
        }
        // 【lane 门禁】本方法是世界实体 owner tick 的回调，正常情况下由锚点 region 的周期任务驱动。
        // 但牌桌未放置（锚点被清空）时 runTableTimer 会回退到 global，而 global 不拥有实体 region：
        // 下面的 refreshPlayDetail / clearHover 都会读写世界实体并直接抛异常。此时整次世界 tick 无意义
        // （桌子不在任何玩家附近），直接跳过；区块重新加载后由 ChunkLoad 修复链重新绑定锚点。
        if (!worldBodyLane.isOwnedByCurrentRegion(placed.anchor())) {
            reportWorldBodyLaneSkip("tick", placed);
            return;
        }
        for (UUID viewerId : table.getSeats()) {
            if (viewerId == null || table.isBot(viewerId)) {
                if (viewerId != null) {
                    clearHover(viewerId);
                    clearPickDebug(viewerId);
                }
                continue;
            }
            dispatchTickPlayerSnapshot(table, viewerId);
        }
        long bucket = System.currentTimeMillis() / 2000L;
        String tableKey = normalize(table.getName());
        Long previousBucket = playDetailLastRefreshBucketByTable.get(tableKey);
        if (previousBucket == null || bucket != previousBucket) {
            playDetailLastRefreshBucketByTable.put(tableKey, bucket);
            refreshPlayDetail(table, placed);
        }
    }

    /**
     * region owner 只拍桌内玩家 UUID；眼睛位置和朝向在 player lane 快照，再回到同桌 owner 做实体计算。
     */
    private void dispatchTickPlayerSnapshot(GameTable table, UUID viewerId) {
        playerOutput.runPlayer(viewerId, viewer -> {
            if (!viewer.isOnline()) {
                return;
            }
            Location eye = viewer.getEyeLocation().clone();
            org.bukkit.util.Vector direction = eye.getDirection().normalize();
            plugin.getTableManager().runTableNow(table, () -> {
                PlacedTable current = placedTableForWorldBody(table.getName());
                if (current == null || plugin.isShuttingDown()) {
                    return;
                }
                updateHoverState(table, current, viewerId, eye, direction);
                if (!table.isOpeningHandLocked()) {
                    updatePrivateSelection(table, current, viewerId);
                    updateBacksideSelection(table, current, viewerId);
                    // 排在悬停之后：线框要读 pickHandCard 的结果，那是悬停算出来的同一份。
                    if (pickDebugViewers.contains(viewerId)) {
                        refreshPickDebug(table, current, viewerId, eye, direction);
                    }
                }
            });
        });
    }

    /**
     * 右键椅子家具时只认出椅子，不改任何牌桌状态
     * 椅子是纯装饰：点它只由 CraftEngine 的 seats 把玩家放上去坐着，不加入牌桌。
     * 加入牌桌的唯一入口是桌面上的加入按钮（ButtonAction.JOIN）。
     * 这里必须返回 false 让事件继续传下去，否则 CraftEngine 收不到就坐不下去。
     * @param player 右键的玩家，当前不参与判定，保留以便后续做提示
     * @param entity 被右键的实体
     * @return 恒为 false，椅子交互永远不吞事件
     */
    private boolean handleChairSeatInteraction(Player player, Entity entity) {
        resolveChairSeatTarget(entity);
        return CHAIR_INTERACTION_NEVER_CONSUMED;
    }

    /**
     * 椅子交互的返回值常量：永远不吞事件。
     *
     * 抽成常量是为了能被测试断言。这个值一旦变成 true，
     * CraftEngine 就收不到右键事件，玩家会连椅子都坐不下去，
     * 而这种回归在纯逻辑测试里很难被间接发现。
     */
    static final boolean CHAIR_INTERACTION_NEVER_CONSUMED = false;

    /** 右键椅子后识别出的座位状态，只用于诊断，不再驱动入座。 */
    enum ChairSeatDecision {
        /** 不是椅子家具，插件不插手。 */
        NOT_CHAIR,
        /** 是椅子但找不到对应牌桌或座位，只让 CraftEngine 坐下。 */
        NO_SEAT,
        /** 座位已经有人，只让 CraftEngine 坐下。 */
        OCCUPIED,
        /** 座位空着，同样只让 CraftEngine 坐下，不加入牌桌。 */
        EMPTY
    }

    private record ChairSeatTarget(
        ChairSeatDecision decision,
        PlacedTable placed,
        GameTable table,
        int seatIndex
    ) { }

    /**
     * 解析右键的椅子属于哪张牌桌的哪个座位
     * 只做识别，不碰玩家状态也不加入牌桌，可以在没有玩家的情况下单独跑，方便排查。
     * @param entity 被右键的实体
     * @return 不是椅子返回 null，否则给出座位状态
     */
    private ChairSeatTarget resolveChairSeatTarget(Entity entity) {
        if (!isChairFurnitureEntity(entity.getUniqueId())) {
            return null;
        }
        List<ChairSeatTarget> candidates = new ArrayList<>();
        List<Double> distancesSquared = new ArrayList<>();
        Set<Integer> ownedCandidates = new LinkedHashSet<>();
        for (PlacedTable placed : placedTables.values()) {
            int seatIndex = nearestChairSeatIndex(entity, placed);
            if (seatIndex < 0) {
                continue;
            }
            GameTable table = plugin.getTableManager().getTable(placed.tableName());
            ChairSeatDecision decision = decideChairSeat(
                seatIndex,
                table != null,
                placed.seatAssignments().keySet()
            );
            int candidateIndex = candidates.size();
            candidates.add(new ChairSeatTarget(decision, placed, table, seatIndex));
            distancesSquared.add(entity.getLocation().distanceSquared(chairLocation(placed, seatIndex)));
            if (ownsChairEntity(placed, entity)) {
                ownedCandidates.add(candidateIndex);
            }
        }
        int candidateIndex = closestChairCandidateIndex(distancesSquared, ownedCandidates);
        return candidateIndex < 0
            ? new ChairSeatTarget(ChairSeatDecision.NO_SEAT, null, null, -1)
            : candidates.get(candidateIndex);
    }

    /**
     * 真实家具实体优先按登记归属路由；独立的虚拟 hitbox 没有归属时，选择最近的椅子。
     */
    static int closestChairCandidateIndex(List<Double> distancesSquared, Set<Integer> ownedCandidates) {
        if (distancesSquared == null || distancesSquared.isEmpty()) {
            return -1;
        }
        boolean requireOwned = ownedCandidates != null && !ownedCandidates.isEmpty();
        int closestIndex = -1;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < distancesSquared.size(); index++) {
            if (requireOwned && !ownedCandidates.contains(index)) {
                continue;
            }
            Double distance = distancesSquared.get(index);
            if (distance != null && Double.isFinite(distance) && distance < closestDistance) {
                closestIndex = index;
                closestDistance = distance;
            }
        }
        return closestIndex;
    }

    /**
     * 识别右键的椅子对应哪个座位、座位是否有人
     * 椅子只负责坐下，任何分支都不会加入牌桌，结果只用于 /doudizhu 诊断输出。
     * @param seatIndex 椅子对应的座位下标，负数表示没解析出座位
     * @param tableExists 该牌桌是否还注册着
     * @param occupiedSeats 已经有人的座位下标
     * @return 识别出的座位状态
     */
    static ChairSeatDecision decideChairSeat(
        int seatIndex,
        boolean tableExists,
        Set<Integer> occupiedSeats
    ) {
        if (seatIndex < 0 || !tableExists) {
            return ChairSeatDecision.NO_SEAT;
        }
        if (occupiedSeats.contains(seatIndex)) {
            return ChairSeatDecision.OCCUPIED;
        }
        return ChairSeatDecision.EMPTY;
    }

    /**
     * 报告每把椅子被右键时识别出的座位状态
     * 椅子只负责坐下，这里的状态纯粹用于排查椅子归属和座位映射是否正确。
     * @param tableName 牌桌名
     * @return 每把椅子一行描述
     */
    public List<String> describeChairSeatDecisions(String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Location chairLocation = chairLocation(placed, index);
            if (!chairLocation.getWorld().isChunkLoaded(
                chairLocation.getBlockX() >> 4,
                chairLocation.getBlockZ() >> 4
            )) {
                lines.add("椅子" + (index + 1) + " 右键分支: 区块未加载，测不了");
                continue;
            }
            List<String> perEntity = new ArrayList<>();
            for (Entity nearby : chairLocation.getWorld().getNearbyEntities(chairLocation, 0.9, 1.7, 0.9)) {
                if (!isLikelyFurnitureEntity(nearby)) {
                    continue;
                }
                ChairSeatTarget target = resolveChairSeatTarget(nearby);
                if (target == null) {
                    continue;
                }
                perEntity.add(String.format(
                    "%s=%s(座位%s 判给%s)",
                    nearby.getType(),
                    target.decision(),
                    target.seatIndex() < 0 ? "-" : String.valueOf(target.seatIndex() + 1),
                    target.placed() == null
                        ? "-"
                        : (target.placed() == placed ? "本桌" : target.placed().tableName())
                ));
            }
            lines.add("椅子" + (index + 1) + " 右键分支: "
                + (perEntity.isEmpty() ? "没有椅子家具实体" : String.join(" ", perEntity)));
        }
        return lines;
    }

    /**
     * 报告判定框上下范围是否真的罩住了文字
     * 判定框从底边往上长，文字挂在配置高度上。两者错开的话玩家就点不到文字。
     */
    static String describeLabelCoverage(double boxBottom, double boxTop, double labelY) {
        if (Double.isNaN(labelY)) {
            return "算不出";
        }
        if (labelY < boxBottom) {
            return String.format("否(文字低于框底 %.3f)", boxBottom - labelY);
        }
        if (labelY > boxTop) {
            return String.format("否(文字高出框顶 %.3f)", labelY - boxTop);
        }
        double margin = Math.min(labelY - boxBottom, boxTop - labelY);
        return String.format("是(余量 %.3f)", margin);
    }

    /**
     * 用无过滤射线检查玩家视线最先命中的实体。
     * 任何实体都可能挡在文字按钮前面，这项诊断会标出命中的是按钮还是遮挡物。
     * @param tableName 牌桌名
     * @return 每个座位每个站位的首个命中实体及按钮绑定
     */
    public List<String> describeUnfilteredActionRays(String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Location chairLocation = chairLocation(placed, index);
            if (!chairLocation.getWorld().isChunkLoaded(
                chairLocation.getBlockX() >> 4,
                chairLocation.getBlockZ() >> 4
            )) {
                lines.add("座位" + (index + 1) + " 无过滤射线: 区块未加载，测不了");
                continue;
            }
            org.bukkit.util.Vector outward =
                chairLocation.toVector().subtract(placed.anchor().toVector());
            outward.setY(0.0);
            if (outward.lengthSquared() < 1.0E-6) {
                continue;
            }
            outward.normalize();
            List<String> stances = new ArrayList<>();
            for (double distance : new double[] {2.0, 1.0, 0.5}) {
                Location eye = chairLocation.clone()
                    .add(outward.clone().multiply(distance))
                    .add(0.0, 1.62, 0.0);
                org.bukkit.util.Vector aim = chairLocation.clone()
                    .add(0.0, 0.5, 0.0)
                    .toVector()
                    .subtract(eye.toVector());
                if (aim.lengthSquared() < 1.0E-6) {
                    continue;
                }
                // 关键：这里不加任何 predicate，和 getTargetEntity 一致。
                org.bukkit.util.RayTraceResult hit = chairLocation.getWorld().rayTraceEntities(
                    eye,
                    aim.normalize(),
                    6.0
                );
                stances.add(distance + "格=" + describeUnfilteredHit(hit, placed));
            }
            lines.add("座位" + (index + 1) + " 按钮射线: " + String.join(" ", stances));
        }
        return lines;
    }

    private String describeUnfilteredHit(org.bukkit.util.RayTraceResult hit, PlacedTable placed) {
        if (hit == null || hit.getHitEntity() == null) {
            return "没命中";
        }
        Entity entity = hit.getHitEntity();
        ActionBinding binding = actionBindings.get(entity.getUniqueId());
        if (binding == null) {
            return entity.getType() + "[遮挡物]";
        }
        String where = binding.tableName().equalsIgnoreCase(placed.tableName()) ? "本桌" : binding.tableName();
        return entity.getType() + "[" + where + "/" + binding.action() + "]";
    }

    /**
     * 检查这张桌上所有按钮判定框是否互相重叠
     * 判定框改成按文字缩放推算后宽了不少（0.22 → 0.53），相邻按钮可能会误触。
     * 这里把每两个框的水平间距和各自半宽加总做比对，重叠就报出来。
     * @param tableName 牌桌名
     * @return 重叠情况描述，没有牌桌时返回空列表
     */
    public List<String> describeHitboxOverlaps(String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return List.of();
        }
        record Box(String label, Location center, double halfWidth) { }
        List<Box> boxes = new ArrayList<>();
        for (Map.Entry<UUID, ActionBinding> entry : actionBindings.entrySet()) {
            if (!entry.getValue().tableName().equalsIgnoreCase(tableName)) {
                continue;
            }
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Interaction box)) {
                continue;
            }
            boxes.add(new Box(
                entry.getValue().action() + "@座位" + (entry.getValue().seatIndex() + 1),
                box.getLocation(),
                box.getInteractionWidth() / 2.0
            ));
        }
        if (boxes.size() < 2) {
            return List.of("判定框不足 2 个，无法比对重叠");
        }
        List<String> lines = new ArrayList<>();
        int overlaps = 0;
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                Box a = boxes.get(i);
                Box b = boxes.get(j);
                if (!a.center().getWorld().equals(b.center().getWorld())) {
                    continue;
                }
                double dx = a.center().getX() - b.center().getX();
                double dz = a.center().getZ() - b.center().getZ();
                double gap = Math.sqrt(dx * dx + dz * dz);
                double needed = a.halfWidth() + b.halfWidth();
                if (gap < needed) {
                    overlaps++;
                    lines.add(String.format(
                        "重叠: %s 与 %s 间距 %.3f < 需要 %.3f",
                        a.label(), b.label(), gap, needed
                    ));
                }
            }
        }
        lines.add(0, "判定框 " + boxes.size() + " 个，重叠 " + overlaps + " 对");
        return lines;
    }

    /**
     * 报告这张桌存了多少个按钮实体 id，用于确认多余的实体被收干净
     * 每个按钮 2 个实体（文字 + 判定框）。数字不是 2 的倍数，
     * 说明有升级前的旧实体没被回收。
     * @param tableName 牌桌名
     * @return 实体 id 数量，牌桌不存在时返回 -1
     */
    public int actionEntityCount(String tableName) {
        PlacedTable placed = placedTable(tableName);
        return placed == null ? -1 : placed.actionEntities().size();
    }

    /**
     * 报告这把椅子登记在哪张牌桌名下，用于排查相邻牌桌串座
     * @param entity 椅子实体
     * @param expectedTable 当前正在查看的牌桌名
     * @return 本桌返回"本桌"，别的桌返回桌名，没登记返回"未登记"
     */
    private String resolveChairOwnerLabel(Entity entity, String expectedTable) {
        for (PlacedTable placed : placedTables.values()) {
            if (ownsChairEntity(placed, entity)) {
                return placed.tableName().equalsIgnoreCase(expectedTable)
                    ? "本桌"
                    : placed.tableName();
            }
        }
        return "未登记";
    }

    private boolean ownsChairEntity(PlacedTable placed, Entity entity) {
        Entity current = entity;
        while (current != null) {
            if (placed.craftEngineVisualEntities().contains(current.getUniqueId())) {
                return true;
            }
            current = current.getVehicle();
        }
        return false;
    }

    /**
     * 找出实体贴着这张牌桌的哪把椅子
     * @param entity 待判定的实体
     * @param placed 已放置的牌桌
     * @return 座位下标，不属于这张桌的椅子返回 -1
     */
    private int nearestChairSeatIndex(Entity entity, PlacedTable placed) {
        Location location = entity.getLocation();
        for (int index = 0; index < 3; index++) {
            if (nearExpectedLocation(location, chairLocation(placed, index), 0.85, 1.60)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 计算指定座位的椅子位置（含视觉偏移）。
     * <p>被桌子放置、状态检查、碰撞检测、实体清理等多处共用。</p>
     */
    private Location chairLocation(Location anchor, float yaw, int seatIndex) {
        Vector chairAdjustment = chairVisualAdjustment(seatIndex);
        return rotate(
            anchor,
            yaw,
            chairOffsets(seatIndex)[0] + chairAdjustment.x(),
            plugin.getChairBaseHeight() + chairAdjustment.y(),
            chairOffsets(seatIndex)[1] + chairAdjustment.z()
        );
    }

    /** @see #chairLocation(Location, float, int) */
    private Location chairLocation(PlacedTable placed, int seatIndex) {
        return chairLocation(placed.anchor(), placed.yaw(), seatIndex);
    }

    private void reconcileSeatAssignments(GameTable table, PlacedTable placed) {
        reconcileSeatAssignments(placed.seatAssignments(), table.getSeats());
    }

    /**
     * 让座位绑定与牌桌实际玩家列表对齐
     * 清掉已离桌的绑定、同一玩家的重复绑定，再把还没有座位的玩家补进空位。
     * @param seatAssignments 座位号到玩家的绑定，会被就地修改
     * @param seated 牌桌上的玩家，顺序决定补位顺序
     */
    static void reconcileSeatAssignments(Map<Integer, UUID> seatAssignments, Collection<UUID> seated) {
        seatAssignments.entrySet().removeIf(entry -> !seated.contains(entry.getValue()));
        // 用 Set 去重。原先写的是 List.add，而它总是返回 true，等于压根没去重，
        // 同一个玩家会同时占住两个座位，另一个真人就再也坐不进来。
        Set<UUID> seen = new LinkedHashSet<>();
        seatAssignments.entrySet().removeIf(entry -> !seen.add(entry.getValue()));
        for (UUID playerId : seated) {
            if (seatAssignments.containsValue(playerId)) {
                continue;
            }
            for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
                if (!seatAssignments.containsKey(seatIndex)) {
                    seatAssignments.put(seatIndex, playerId);
                    break;
                }
            }
        }
    }

    private void joinSeat(GameTable table, PlacedTable placed, Player player, Integer seatIndex) {
        // 点击哪张座位的“加入”按钮，就强制把该玩家绑定到哪个座位
        if (placed == null || seatIndex == null) {
            throw new IllegalStateException("座位信息异常。");
        }
        if (placed.seatAssignments().containsKey(seatIndex)) {
            if (player.getUniqueId().equals(placed.seatAssignments().get(seatIndex))) {
                throw new IllegalStateException("你已经在这个座位上了。");
            }
            throw new IllegalStateException("这个座位已经有人了。");
        }
        GameTable currentTable = plugin.getTableManager().getTableOf(player);
        boolean switchingFromOtherDdz = currentTable != null && !currentTable.getName().equalsIgnoreCase(table.getName());
        if (switchingFromOtherDdz && table.getPhase() != GamePhase.LOBBY) {
            throw new IllegalStateException("那桌正在打，等这局结束再来。");
        }
        if (currentTable != null && table.getPhase() != GamePhase.LOBBY) {
            throw new IllegalStateException("已经开局了，中途不能换座。");
        }
        UUID playerId = player.getUniqueId();
        if (switchingFromOtherDdz && !plugin.canAffordEntry(playerId, table.getRoomLevel())) {
            throw new IllegalStateException(plugin.insufficientEntryMessage(playerId, table.getRoomLevel()));
        }
        int previousSeat = placedSeatIndex(placed, playerId);
        if (previousSeat == seatIndex) {
            throw new IllegalStateException("你已经在这个座位上了。");
        }
        if (switchingFromOtherDdz) {
            plugin.getTableManager().leaveTable(player);
            currentTable = null;
        }
        if (previousSeat >= 0) {
            placed.seatAssignments().remove(previousSeat);
            placed.seatAssignments().put(seatIndex, playerId);
            hint(player.getUniqueId(), "你已切换到座位 " + (seatIndex + 1) + "。", NamedTextColor.GREEN);
            return;
        }
        placed.seatAssignments().put(seatIndex, player.getUniqueId());
        try {
            plugin.getTableManager().joinTable(player, table.getName());
        } catch (RuntimeException exception) {
            placed.seatAssignments().remove(seatIndex);
            throw exception;
        }
        placed.seatAssignments().entrySet().removeIf(entry -> !entry.getKey().equals(seatIndex) && entry.getValue().equals(player.getUniqueId()));
        hint(player.getUniqueId(), "你已加入 " + table.getName() + " 号牌桌的座位 " + (seatIndex + 1) + "。", NamedTextColor.GREEN);
    }

    private void hint(UUID playerId, String text, NamedTextColor color) {
        playerOutput.sendActionBar(playerId, message(text, color));
    }

    private void applyJoinVisibility(GameTable table, Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (UUID viewerId : onlinePlayerIdsSnapshot()) {
            playerOutput.showEntity(viewerId, plugin, entity);
        }
    }

    /**
     * 共享保护链的实体保护判定：牌桌自有实体与麻将自有实体都算受保护。
     *
     * <p>破坏/攻击类入口直接用它（麻将实体也不该被玩家拆掉）。但右键入口不能只看这个结果，
     * 必须再经 {@code WorldTableInteractionListener.shouldCancelProtectedInteract} 的放行口，
     * 否则会取消麻将入座 Interaction —— 麻将实体既不是椅子家具、也不是动作按钮。
     */
    public boolean isProtectedEntity(UUID entityId) {
        Entity entity = Bukkit.getEntity(entityId);
        while (entity != null) {
            // 共享保护链：牌桌与麻将各自的保护 tag 都由 TableEntityGeometry 登记，
            // 这里必须走共用的 hasProtectionTag，否则麻将实体会漏掉破坏保护。
            // 注意只在这里放宽：本类的残留清理仍只认牌桌 tag（见 PROTECTED_ENTITY_TAG 的注释）。
            if (TableEntityGeometry.hasProtectionTag(entity)) {
                return true;
            }
            UUID currentId = entity.getUniqueId();
            for (PlacedTable placed : placedTables.values()) {
                if (placed.staticEntities().contains(currentId)
                    || placed.actionEntities().contains(currentId)
                    || matchesExpectedFurnitureEntity(entity, placed)) {
                    return true;
                }
            }
            entity = entity.getVehicle();
        }
        return false;
    }

    /**
     * 判断实体是否是牌桌按钮，用于放行右键点击。
     *
     * <p>按钮同时也是保护实体（登记在 {@code actionEntities} 里），但保护的目的是防止玩家
     * 破坏桌椅，而按钮的点击【就是它的用途】，必须放行。
     *
     * <p>已取证的故障：客户端一次右键实体会先发 INTERACT_AT 再发 INTERACT。
     * {@code onInteractAt} 见到保护实体就取消事件，而取消 AT 会让后续的 INTERACT 不再送达，
     * 于是只挂在 INTERACT 那一路的 {@code handleInteraction} 永不执行 —— 按钮完全没反应。
     * 判据用 {@code actionBindings} 而不是 {@code actionEntities}：前者是「当前真的绑着某个
     * 动作」的登记表，标签实体等附属件不在其中，放行范围不会超出实际可点的按钮。
     *
     * @param entityId 被右键的实体 id
     * @return 是绑着动作的按钮时返回 true
     */
    public boolean isActionButtonEntity(UUID entityId) {
        return entityId != null && actionBindings.containsKey(entityId);
    }

    /**
     * 判断实体是否属于某张牌桌的椅子家具，用于放行右键坐下
     * @param entityId 被右键的实体 id
     * @return 属于椅子家具时返回 true
     */
    public boolean isChairFurnitureEntity(UUID entityId) {
        Entity entity = Bukkit.getEntity(entityId);
        while (entity != null) {
            UUID currentId = entity.getUniqueId();
            for (PlacedTable placed : placedTables.values()) {
                if (!nearAnyChair(entity, placed)) {
                    continue;
                }
                if (placed.craftEngineVisualEntities().contains(currentId)) {
                    return true;
                }
                // 重启后 CE 家具可能没被插件记录，这里用"像家具且不是插件自己生成"兜底。
                //
                // 牌实体必须单独排除：手牌/背面牌登记在 privateEntitiesByPlayer 和
                // backsideEntitiesByPlayer，既不在 staticEntities 也不在 actionEntities，
                // 光靠上面两个 contains 拦不住，会一路走进兜底。
                // 而 nearAnyChair 的两个阈值按默认几何也挡不住：
                // 手牌 radius 1.62、椅子 radius 2.35，水平距离 0.73 < 0.85 阈值；
                // 手牌高 1.23、椅子高 1.16（chairBaseHeight 0.20 + chairVisualVerticalOffset -0.04 + SUPPORT_SURFACE_LIFT 1.0），
                // 竖直距离 0.07 < 1.60 阈值。两个都过，
                // 于是手牌自己被判成「椅子家具」。
                // 一旦误判，yieldsToBlockingEntity 会因为 chairFurniture 让位而 return false，
                // 手牌就无声丢掉这次点击。
                if (fallsBackToChairFurniture(
                    isLikelyFurnitureEntity(entity),
                    placed.staticEntities().contains(currentId),
                    placed.actionEntities().contains(currentId),
                    isTrackedCardEntity(currentId))) {
                    return true;
                }
            }
            entity = entity.getVehicle();
        }
        return false;
    }

    /**
     * {@code isChairFurnitureEntity} 的兜底判据：「像家具且不是插件自己生成的」就当椅子。
     *
     * <p>抽成 static 布尔谓词是为了能真的跑起来测——{@code isChairFurnitureEntity} 要 Bukkit
     * 实体和已放置牌桌，这个项目跑不起 Bukkit，整条判据原先只能靠源码扫描断言。
     * 写法沿用 {@code yieldsToBlockingEntity} 与 {@code shouldCancelProtectedInteract}。
     *
     * <p><b>{@code trackedCard} 这一条是手牌链路的命门。</b>手牌的点击捕获器是 Interaction，
     * 而 {@code isFurnitureEntityClass} 显式把 Interaction 算作家具；捕获器又既不在
     * {@code staticEntities} 也不在 {@code actionEntities}。少了这一条排除，
     * 捕获器会被判成椅子家具，于是
     * {@code yieldsToBlockingEntity(furniture=true, usableButton=false, chairFurniture=true)}
     * 判定<b>让位</b>——点击既不选牌也不 cancel，被静默丢弃，症状与完全没修一模一样。
     * 牌本体和牌面标签当年踩的是同一个坑。
     *
     * @param furniture 实体是否像家具（{@code isLikelyFurnitureEntity}）
     * @param staticEntity 是否登记在该桌的 staticEntities
     * @param actionEntity 是否登记在该桌的 actionEntities
     * @param trackedCard 是否是插件登记的牌实体（牌本体 / 标签 / 点击捕获器 / 背面牌）
     * @return 该走兜底判成椅子家具时返回 true
     */
    static boolean fallsBackToChairFurniture(
        boolean furniture,
        boolean staticEntity,
        boolean actionEntity,
        boolean trackedCard
    ) {
        return furniture && !staticEntity && !actionEntity && !trackedCard;
    }

    private boolean nearAnyChair(Entity entity, PlacedTable placed) {
        if (entity == null) {
            return false;
        }
        Location location = entity.getLocation();
        for (int index = 0; index < 3; index++) {
            Location chairLocation = chairLocation(placed, index);
            if (nearExpectedLocation(location, chairLocation, 0.85, 1.60)) {
                return true;
            }
        }
        return false;
    }

    public boolean isProtectedPlacedBlock(org.bukkit.block.Block block) {
        if (block == null) {
            return false;
        }
        for (PlacedTable placed : placedTables.values()) {
            if (matchesExpectedPlacedBlock(block, placed)) {
                return true;
            }
            for (BlockRestore blockRestore : placed.blockRestores()) {
                Location location = blockRestore.originalState().getLocation();
                if (location.getWorld() != null
                    && location.getWorld().equals(block.getWorld())
                    && location.getBlockX() == block.getX()
                    && location.getBlockY() == block.getY()
                    && location.getBlockZ() == block.getZ()) {
                    return true;
                }
            }
        }
        return false;
    }

    private PlacedTable spawnTable(GameTable table, Location anchor, float yaw, UUID ownerId, String ownerName) {
        ensureChunkReady(anchor);
        // 这里只生成“永久桌面层”：桌子、椅子、桌顶状态文字
        List<UUID> staticEntities = new ArrayList<>();
        PlacedTable placed = new PlacedTable(
            table.getName(),
            anchor.clone(),
            yaw,
            tableOwner(table.getName(), ownerId, ownerName),
            footprintChunkKeys(anchor),
            staticEntities,
            new ArrayList<>(),
            new ArrayList<>(),
            new LinkedHashMap<>(),
            new ArrayList<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            null,
            null,
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>()
        );

        Location tableLocation = previewTableCenter(anchor);
        TablePlacement tablePlacement = spawnTableVisual(tableLocation, yaw);
        UUID tableVisualId;
        if (tablePlacement.entityId() != null) {
            if (tablePlacement.craftEngineEntity()) {
                // CE 家具必须保留持久化状态，让 CE 自己处理区块卸载/加载和旧映射失效；这里只登记实体树 UUID。
                collectEntityTreeIds(tablePlacement.entityId(), staticEntities);
                collectEntityTreeIds(tablePlacement.entityId(), placed.craftEngineVisualEntities());
            } else {
                addEntityTreeIds(tablePlacement.entityId(), staticEntities, placed.owner(), ENTITY_ROLE_TABLE);
            }
            tableVisualId = tablePlacement.entityId();
        } else {
            ItemDisplay fallbackTableDisplay = spawnFurnitureDisplay(
                tableLocation, tableItem(), plugin.getTableScale(), placed.owner(), ENTITY_ROLE_TABLE);
            staticEntities.add(fallbackTableDisplay.getUniqueId());
            tableVisualId = fallbackTableDisplay.getUniqueId();
        }
        if (tablePlacement.blockRestore() != null) {
            placed.blockRestores().add(tablePlacement.blockRestore());
        }

        for (int index = 0; index < 3; index++) {
            Location chairLocation = chairLocation(anchor, yaw, index);
            ChairPlacement chairPlacement = spawnChairVisual(chairLocation, yaw + chairYawOffset(index) + (float) plugin.getChairRotationDegrees());
            if (chairPlacement.entityId() != null) {
                if (chairPlacement.craftEngineEntity()) {
                    // 椅子同样由 CE 管理生命周期，不能被 MUZ 的非持久化保护覆盖。
                    collectEntityTreeIds(chairPlacement.entityId(), staticEntities);
                    collectEntityTreeIds(chairPlacement.entityId(), placed.craftEngineVisualEntities());
                } else {
                    addEntityTreeIds(chairPlacement.entityId(), staticEntities, placed.owner(), ENTITY_ROLE_CHAIR);
                }
            }
            if (chairPlacement.blockRestore() != null) {
                placed.blockRestores().add(chairPlacement.blockRestore());
            }

            Location seatBase = chairPlacement.seatBaseLocation();
            placed.seatBaseLocations().add(seatBase.clone());

            // HARD-CODED SEAT DISPLAY SPLIT:
            // Chair-side player name and chair-side meta text must stay as two separate display entities.
            TextDisplay seatName = spawnText(
                seatNameLocation(table, index, seatBase, yaw),
                seatName(table, index),
                Display.Billboard.CENTER,
                false,
                seatNameScale(table, index),
                true,
                placed.owner(),
                "seat-name"
            );
            staticEntities.add(seatName.getUniqueId());
            placed.seatNameDisplayIds().add(seatName.getUniqueId());

            TextDisplay seatInfo = spawnText(
                seatInfoLocation(table, index, seatBase, yaw),
                seatInfo(table, index),
                Display.Billboard.CENTER,
                false,
                plugin.getSmallTextScale(),
                true,
                placed.owner(),
                "seat-info"
            );
            staticEntities.add(seatInfo.getUniqueId());
            placed.seatInfoDisplayIds().add(seatInfo.getUniqueId());
        }

        TextDisplay status = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getStatusHeight(), 0.0),
            buildStatus(table),
            Display.Billboard.CENTER,
            false,
            plugin.getStatusTextScale(),
            true,
            placed.owner(),
            "status"
        );
        placed = placed.withStatusDisplayId(status.getUniqueId());
        staticEntities.add(status.getUniqueId());

        TextDisplay playDetail = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getPlayDetailHeight(), 0.0),
            buildPlayDetail(table),
            Display.Billboard.CENTER,
            false,
            plugin.getSmallTextScale(),
            true,
            placed.owner(),
            "play-detail"
        );
        placed = placed.withPlayDetailDisplayId(playDetail.getUniqueId());
        staticEntities.add(playDetail.getUniqueId());

        return placed;
    }

    /**
     * 恢复/重建与放桌入口的锚点区块就绪检查。**按核心类型（{@link #REGIONIZED}）分支**。
     *
     * <p><b>非区域化核心（Paper/Leaf）</b>：恢复成本轮改动前的阻塞强加载。原英文注释保留其意图：
     * Startup restore can run before the destination chunk has been warmed up.
     * Force the anchor chunk loaded first so chairs, text, and CraftEngine furniture do not half-spawn.
     * 主线程允许同步加载区块，且放置/重建路径依赖「执行前锚点区块已加载」——区块未加载时实体与
     * 方块操作静默失效，会把整桌实体清掉却建不回来（真实 Leaf 26.1.2 实服复现的回归）。
     *
     * <p><b>区域化核心（Folia）</b>：只读判断 + 未加载时告警。原实现在这里调 {@code anchor.getChunk()}
     * （内部 {@code World#getChunkAt}）与 {@code chunk.load()}，在真实 Folia 上直接抛
     * {@code Async chunk retrieval}，导致存档牌桌永远恢复不了。区块加载必须由
     * {@link #ensureChunkLoadedAsync} 在异步侧完成，这里退化为「只检查 + 未加载时告警」：
     * 若仍走到未加载分支，说明调用方漏了异步加载，属于流程缺陷，必须出声而不是静默半生成。
     *
     * <p>实际逻辑收口到包内可见、不依赖实例的静态核心 {@link #ensureChunkReadyCore}，
     * 使「区域化核心不得同步拉区块」可以用 fake World 做真实行为测试；本方法只做入口归一化。
     */
    private void ensureChunkReady(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        ensureChunkReadyCore(anchor.getWorld(), anchor.getBlockX(), anchor.getBlockZ(), REGIONIZED,
            message -> plugin.getLogger().warning(message));
    }

    /**
     * 锚点区块就绪检查的核心，**按核心类型分支**，且不依赖实例——便于以 fake World 做真实行为测试。
     *
     * <p>分支语义与 {@link #ensureChunkReady} 的文档一致：区域化核心只做只读检查 + 未加载时告警，
     * 非区域化核心（Paper/Leaf）才允许同步强加载。这里的 {@code regionized} 由调用方从
     * {@link #REGIONIZED} 传入，绝不能在非区域化分支硬编码放行同步获取。
     *
     * @param world      牌桌锚点所在世界；null 直接返回
     * @param blockX     锚点方块 X
     * @param blockZ     锚点方块 Z
     * @param regionized 当前核心是否区域化（Folia）
     * @param warn       未加载告警出口，调用方负责落到服务器日志
     */
    static void ensureChunkReadyCore(World world, int blockX, int blockZ, boolean regionized, Consumer<String> warn) {
        if (world == null) {
            return;
        }
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        if (regionized) {
            // 区域化核心不能同步拉区块：Location#getChunk() 内部即 World#getChunkAt，Folia 上非法且阻塞。
            // 该「先把锚点区块拉起来」的职责已上移到 ensureChunkLoadedAsync（异步侧），此处只负责发现漏网。
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                warn.accept("牌桌锚点区块尚未加载，本应已由异步加载保证: world="
                    + world.getName() + " chunk=[" + chunkX + ", " + chunkZ + "]");
            }
            return;
        }
        // 非区域化核心（Paper/Leaf）主线程可同步加载区块：这正是放置/重建路径原本依赖的保证。
        org.bukkit.Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        if (!chunk.isLoaded()) {
            chunk.load();
        }
    }

    /**
     * 异步把锚点所在区块拉起来。
     *
     * <p>真实 Folia 上 {@code World#getChunkAt} / {@code Location#getChunk} 会抛
     * {@code Async chunk retrieval}（region 线程与主线程都失败），所以恢复存档牌桌必须走
     * {@code World#getChunkAtAsync}。这里只加载锚点所在那一个区块，区块坐标算法与
     * {@link #anchorChunkKeys} 一致（{@code blockX >> 4}）。
     *
     * <p>异步获取与回调归一化收口到不依赖实例的静态核心 {@link #loadChunkAsyncCore}，
     * 使「异步加载」可用 fake World 做真实行为测试；本方法只做入口归一化。
     *
     * @param anchor 牌桌锚点
     * @return 区块加载完成后完成的 stage；世界缺失或加载失败时以异常完成
     */
    private CompletionStage<Void> ensureChunkLoadedAsync(Location anchor) {
        World world = anchor == null ? null : anchor.getWorld();
        if (world == null) {
            return failedStage(new IllegalStateException("恢复牌桌缺少有效世界"));
        }
        return loadChunkAsyncCore(world, anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4);
    }

    /**
     * 异步把一张牌桌的 3x3 footprint（锚点所在区块及其八邻域）全部拉起来。
     *
     * <p>【为什么不复用单锚点的 {@link #ensureChunkLoadedAsync}】桌椅、判定框与残留方块可能落在
     * 锚点相邻区块里；重建/位移前只加载锚点那一格，相邻区块里的旧实体依然删不掉、新实体也建不出来。
     * 因此重建路径要的是整份 footprint。反过来，存档恢复链只需要锚点单区块，**绝不能**顺手把它
     * 改成 3x3：那会把恢复成本放大九倍，并扩大对邻桌区块的副作用。
     *
     * <p>九个区块并行发起异步加载，全部完成后 stage 才完成；任一失败即整体失败，不做部分成功——
     * 半加载状态下清旧实体同样会丢整桌。
     */
    private CompletionStage<Void> ensureFootprintLoadedAsync(Location anchor) {
        World world = anchor == null ? null : anchor.getWorld();
        if (world == null) {
            return failedStage(new IllegalStateException("牌桌 footprint 缺少有效世界"));
        }
        return ensureFootprintLoadedAsyncCore(world, anchor.getBlockX(), anchor.getBlockZ());
    }

    /**
     * {@link #ensureFootprintLoadedAsync} 的静态核心：不依赖实例，便于用 fake World 做真实行为
     * 测试（断言真正请求的是锚点 3x3、且整体在九个区块全部加载完成后才完成）。
     */
    static CompletionStage<Void> ensureFootprintLoadedAsyncCore(World world, int blockX, int blockZ) {
        if (world == null) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("牌桌 footprint 缺少有效世界"));
            return failed;
        }
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        List<CompletableFuture<Void>> loads = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                loads.add(loadChunkAsyncCore(world, chunkX + dx, chunkZ + dz));
            }
        }
        return CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]));
    }

    /**
     * 单个区块的异步加载核：把 {@code World#getChunkAtAsync} 的回调归一化成 Void stage。
     *
     * <p>{@code getChunkAtAsync} 的回调完成线程在 Folia 上没有源码可证的保证（Paper 的
     * {@code CompletableFuture} 回调线程同样未文档化），因此回调里不做任何世界操作，只完成这个
     * stage；真正的世界体由 {@link #runRegionStage} / {@link #runRegionStageValue} 再显式投递到
     * 锚点 region 兜底；即便 {@code getChunkAtAsync} 已在正确的 region 线程回调，重复投到同一
     * region 也是安全的。
     */
    private static CompletableFuture<Void> loadChunkAsyncCore(World world, int chunkX, int chunkZ) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            world.getChunkAtAsync(chunkX, chunkZ).whenComplete((chunk, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                } else {
                    result.complete(null);
                }
            });
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private void refreshActionButtons(GameTable table, PlacedTable placed) {
        // 按钮区优先复用已有实体，避免点击和打牌时整排闪烁
        String signature = actionSignature(table, placed);
        String tableKey = normalize(table.getName());
        // 只看签名。别在这里再查实体存活：区块未加载时 Bukkit.getEntity 一律返回 null，
        // 那会被误判成实体已死而每次都强制重建，反而在卸载区块里丢实体。
        // 真正的实体缺失由下面 syncActionWidgets 逐个解析时处理，那里解析不到就会重建。
        if (Objects.equals(actionSignatureByTable.get(tableKey), signature)) {
            return;
        }
        actionSignatureByTable.put(tableKey, signature);

        List<ActionButtonState> phaseStates = phaseButtonStates(table.getPhase());

        List<ActionWidgetSpec> specs = new ArrayList<>();
        for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
            Location base = actionBase(placed.anchor(), placed.yaw(), seatIndex);
            UUID owner = placed.seatAssignments().get(seatIndex);
            float rowYaw = handCardYaw(placed.yaw(), seatIndex);
            List<ActionButtonState> activeStates = actionStatesForSeat(table, owner, phaseStates);
            double maxOffset = activeStates.stream()
                .mapToDouble(state -> Math.abs(state.offsetX()))
                .max()
                .orElse(1.0);

            if (owner == null) {
                Location joinLocation = base.clone();
                // 判定框贴在"加入座位"图标本体上。别再往椅子上放一个大判定框：
                // CraftEngine 椅子自带 interaction hitbox（还带 seats），插件再叠一个
                // 会抢掉射线命中，导致既坐不上椅子、又点不动按钮。
                Vector joinHitbox = buttonHitboxAdjustment(placed.yaw(), seatIndex);
                specs.add(new ActionWidgetSpec(
                    rowYaw,
                    TypewriterTextStyle.focus("加入座位" + (seatIndex + 1)),
                    joinLocation.clone().add(0.0, plugin.getJoinLabelHeight(), 0.0),
                    // 判定框以文字为中心，别从按钮基座往上长：
                    // 基座起算时框顶可能刚好压在文字上，文字就点不到了。
                    joinLocation.clone().add(
                        joinHitbox.x(),
                        plugin.getJoinLabelHeight() + joinHitbox.y(),
                        joinHitbox.z()
                    ),
                    new ActionBinding(table.getName(), ButtonAction.JOIN, seatIndex),
                    owner,
                    true
                ));
                continue;
            }

            if (table.isBot(owner)) {
                continue;
            }

            for (ActionButtonState state : activeStates) {
                Vector arcOffset = actionArcOffset(placed.yaw(), seatIndex, state.offsetX(), maxOffset, activeStates.size());
                Location buttonLocation = base.clone().add(arcOffset.x(), 0.0, arcOffset.z());
                Vector buttonHitbox = buttonHitboxAdjustment(placed.yaw(), seatIndex);
                specs.add(new ActionWidgetSpec(
                    rowYaw,
                    TypewriterTextStyle.focus(state.label()),
                    buttonLocation.clone().add(0.0, plugin.getActionLabelHeight(), 0.0),
                    // 同上：判定框跟着文字走，不然普通按钮只剩 0.01 余量，稍微调小字就点不到。
                    buttonLocation.clone().add(
                        buttonHitbox.x(),
                        plugin.getActionLabelHeight() + buttonHitbox.y(),
                        buttonHitbox.z()
                    ),
                    new ActionBinding(table.getName(), state.action(), seatIndex),
                    owner,
                    false
                ));
            }

        }
        syncActionWidgets(table, placed, specs);
    }

    private void syncActionWidgets(GameTable table, PlacedTable placed, List<ActionWidgetSpec> specs) {
        // 每个按钮只有 2 个实体：文字 + 判定框。图标已经删掉，玩家点的就是文字。
        int required = specs.size() * 2;
        for (int index = 0; index < specs.size(); index++) {
            int base = index * 2;
            ActionWidgetSpec spec = specs.get(index);
            float labelScale = spec.joinVisibility() ? joinLabelTextScale() : actionLabelTextScale();
            TextDisplay label = null;
            Interaction interaction = null;
            if (placed.actionEntities().size() >= base + 2) {
                Entity labelEntity = Bukkit.getEntity(placed.actionEntities().get(base));
                Entity interactionEntity = Bukkit.getEntity(placed.actionEntities().get(base + 1));
                if (labelEntity instanceof TextDisplay existingLabel
                    && interactionEntity instanceof Interaction existingInteraction
                    && buttonLabelScaleMatches(existingLabel, labelScale)) {
                    label = existingLabel;
                    interaction = existingInteraction;
                }
            }
            if (label == null || interaction == null) {
                while (placed.actionEntities().size() > base) {
                    UUID removedId = placed.actionEntities().remove(placed.actionEntities().size() - 1);
                    clearActionMappings(List.of(removedId));
                    clearOwnedEntities(placed.owner(), new ArrayList<>(List.of(removedId)), false);
                }
                label = spawnText(
                    spec.labelLocation(),
                    spec.labelText(),
                    Display.Billboard.CENTER,
                    false,
                    labelScale,
                    true,
                    placed.owner(),
                    ENTITY_ROLE_ACTION_LABEL
                );
                float boxHeight = actionHitboxHeight(spec.labelText(), labelScale);
                Location boxLocation = spec.interactionLocation().clone();
                boxLocation.setY(hitboxBottomForLabel(boxLocation.getY(), boxHeight));
                interaction = spawnInteraction(
                    boxLocation,
                    actionHitboxWidth(spec.labelText(), labelScale),
                    boxHeight,
                    placed.owner(),
                    ENTITY_ROLE_ACTION_HITBOX
                );
                placed.actionEntities().add(label.getUniqueId());
                placed.actionEntities().add(interaction.getUniqueId());
            } else {
                teleportIfMoved(label, spec.labelLocation());
                updateTextEntity(label, spec.labelText());
                float boxHeight = actionHitboxHeight(spec.labelText(), labelScale);
                Location boxLocation = spec.interactionLocation().clone();
                boxLocation.setY(hitboxBottomForLabel(boxLocation.getY(), boxHeight));
                teleportIfMoved(interaction, boxLocation);
                interaction.setInteractionWidth(actionHitboxWidth(spec.labelText(), labelScale));
                interaction.setInteractionHeight(boxHeight);
            }
            actionBindings.put(label.getUniqueId(), spec.binding());
            actionBindings.put(interaction.getUniqueId(), spec.binding());
            if (spec.joinVisibility()) {
                applyJoinVisibility(table, label);
                applyJoinVisibility(table, interaction);
            } else {
                applyPrivateVisibility(spec.owner(), label);
                applyPrivateVisibility(spec.owner(), interaction);
            }
        }
        // 多余的实体逐个回收，不能按 2 个一组走。
        // 升级前每个按钮存 3 个实体（图标+文字+判定框），旧牌桌的列表长度是 3n。
        // 按 2 步跳会漏掉最后那个落单的：9 个实体时索引 8 永远处理不到，
        // 结果玩家看到一个悬空的旧图标。这里改成一次清到底。
        if (staleActionEntityCount(placed.actionEntities().size(), required) > 0) {
            List<UUID> stale = new ArrayList<>(
                placed.actionEntities().subList(required, placed.actionEntities().size())
            );
            clearActionMappings(stale);
            // 沿用既有策略：隐藏而不删除，阶段切回来时能直接复用这些实体。
            deactivateEntities(stale);
        }
        // 这里不做任何椅子判定框的可见性操作。
        //
        // 历史上这里先是调 syncOccupiedChairHitboxVisibility 把占座玩家自己的判定框藏起来
        // （"入座后卡人"的真凶，已删），后来改成在这里调 restoreOccupiedChairHitboxVisibility
        // 恢复可见 —— 那个调用也已经移走，原因是本方法在出牌链路上是高频的：
        // 出牌 → currentTurn 易主 → actionStatesForSeat 按 currentTurn 分叉 → actionSignature 必变
        // → refreshActionButtons 的签名早退失效 → 每次出牌都会走到这里。
        // 恢复可见只需要发生一次，放在这里等于每次出牌都对坐着的玩家重发一遍判定框实体包。
        // 恢复改挂在一次性时机：syncViewer（玩家上线/重生/换世界/资源包就绪）与 rebuildAllTables（重建）。
        // 详见 restoreOccupiedChairHitboxVisibility 的注释。
    }

    /**
     * 算出有多少个按钮实体是多余的，需要收掉
     * 升级前每个按钮存 3 个实体（图标+文字+判定框），现在只存 2 个，
     * 所以旧牌桌的列表长度是 3n，不是 2 的倍数。按 2 个一组遍历会漏掉落单的那个，
     * 玩家会看到一个悬空的旧图标。这里直接算出从 required 往后的全部数量。
     * @param stored 当前存了多少个实体 id
     * @param required 本次需要多少个
     * @return 多余的数量，没有多余时返回 0
     */
    static int staleActionEntityCount(int stored, int required) {
        return Math.max(0, stored - required);
    }

    /**
     * 椅子判定框对所有人保持可见。
     * <p>
     * 这个方法取代了原来的 syncOccupiedChairHitboxVisibility。那段逻辑会在玩家入座后
     * 对他自己调用 {@code viewer.hideEntity} 与 CE 的 {@code hideHitboxes}，
     * 是"加入座位后卡住玩家"的真凶：
     * <ul>
     *   <li>CE 的 {@code hideHitboxes} 只遍历 hitboxes 列表调 {@code hide}，
     *       而 {@code ShulkerFurnitureHitbox.hide} 只发一个 despawnPacket 给客户端；</li>
     *   <li>{@code viewer.hideEntity} 同样只是停止给该客户端发实体包。</li>
     * </ul>
     * 两者都不销毁服务端的 BukkitCollider。于是客户端以为那里没有实体、预测可以走过去，
     * 服务端却仍用 Collider 判定被挡住 —— 这就是玩家感觉"陷进实体、走不动"的来源。
     * 同时判定框对自己隐藏后，占座玩家也点不到椅子，表现为"坐不下"。
     * <p>
     * 原逻辑的理由是"入座后椅子判定框会挡住后方 READY / 叫分 / 加倍按钮的点击"。
     * 按用户实配算过几何：按钮在离桌心 1.56 格、高 2.5；椅子判定框在 2.5 格、
     * 竖直范围 1.17~1.97。水平相距 0.94 格，竖直区间与按钮高度完全不相交，
     * 坐姿视线还是自上往下看向按钮，射线不会先命中椅子。遮挡前提不成立。
     * <p>
     * 保留这个方法而不是直接删调用点：{@code hideEntity} 的隐藏状态是按玩家持久的，
     * 旧版本已经把实体藏起来的在线玩家不会自动恢复，必须主动显式 show 一次。
     * <p>
     * 注意"一次"是这个方法的全部意图，所以调用点只挂一次性时机
     * （{@link #syncViewer} 与 {@link #rebuildAllTables}），不要放回 syncActionWidgets
     * 这类刷新链路。曾经放在那里导致"每次出牌椅子抖一下把人挤开"：
     * 出牌使 currentTurn 易主 → actionSignature 必变 → 刷新链路每次出牌都走到恢复调用 →
     * 对正坐在椅子上的玩家反复重发判定框实体的显示。
     * 推断（非已验证事实）：CE 的 showHitboxes 会重发 Shulker hitbox 的 spawn 包，
     * 客户端每次收到都重新判定一次"卡在实体里"并把玩家挤出去。
     * CE 不在本项目依赖里、读不到 showHitboxes 实现，这一步是按调用频率与
     * "离散单次抖动、无周期任务、这是刷新链路里唯一碰椅子的操作"推出来的成因。
     */
    private void restoreOccupiedChairHitboxVisibility(PlacedTable placed, Collection<UUID> viewerIds) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (int index = 0; index < 3; index++) {
            Location chairLocation = chairLocation(placed, index);
            if (!chairLocation.getWorld().isChunkLoaded(
                chairLocation.getBlockX() >> 4,
                chairLocation.getBlockZ() >> 4
            )) {
                continue;
            }
            for (Entity nearby : chairLocation.getWorld().getNearbyEntities(chairLocation, 0.9, 1.7, 0.9)) {
                if (!(nearby instanceof Interaction) || actionBindings.containsKey(nearby.getUniqueId())) {
                    continue;
                }
                if (nearestChairSeatIndex(nearby, placed) != index) {
                    continue;
                }
                if (!closestChairIsThisTable(nearby, placed, chairLocation)) {
                    continue;
                }
                // 无条件恢复可见，不再看座位是否被占。
                // 只发给传进来的 viewer：重发 hitbox 包会把正坐在这把椅子上的玩家挤开，
                // 所以别人上线时不该顺带惊动全场，只补他自己那一份。
                for (UUID viewerId : viewerIds) {
                    playerOutput.runPlayer(viewerId, viewer -> {
                        if (plugin.getCraftEngineFurnitureService() != null) {
                            plugin.getCraftEngineFurnitureService().setFurnitureHitboxesVisible(nearby, viewer, true);
                        }
                    });
                    playerOutput.showEntity(viewerId, plugin, nearby);
                }
            }
        }
    }

    /**
     * 判断这个 hitbox 是否离本桌的目标椅子最近
     * CE 的 hitbox 不挂在家具载具链上，ownsChairEntity 对它恒为 false，
     * 没法靠归属判断，只能比距离。
     * @param hitbox 待判定的 interaction 实体
     * @param owner 本桌
     * @param chairLocation 本桌目标椅子的位置
     * @return 本桌这把椅子确实是最近的椅子时返回 true
     */
    private boolean closestChairIsThisTable(Entity hitbox, PlacedTable owner, Location chairLocation) {
        double ownDistance = hitbox.getLocation().distanceSquared(chairLocation);
        List<Double> otherDistances = new ArrayList<>();
        for (PlacedTable other : placedTables.values()) {
            if (other == owner) {
                continue;
            }
            if (other.anchor() == null
                || other.anchor().getWorld() == null
                || !other.anchor().getWorld().equals(chairLocation.getWorld())) {
                continue;
            }
            for (int seat = 0; seat < 3; seat++) {
                Location otherChair = chairLocation(other, seat);
                otherDistances.add(hitbox.getLocation().distanceSquared(otherChair));
            }
        }
        return ownChairIsClosest(ownDistance, otherDistances);
    }

    /**
     * 判断本桌这把椅子是否比所有邻桌椅子都更靠近该 hitbox
     * 平方距离即可，不用开方。同距时判归本桌：扫描本就是从本桌发起的，
     * 而且两桌椅子完全重合属于摆放错误，不该让 hover 直接失灵。
     * @param ownDistanceSquared hitbox 到本桌目标椅子的平方距离
     * @param otherDistancesSquared hitbox 到各邻桌椅子的平方距离
     * @return 本桌椅子最近时返回 true
     */
    static boolean ownChairIsClosest(double ownDistanceSquared, List<Double> otherDistancesSquared) {
        for (double other : otherDistancesSquared) {
            if (other < ownDistanceSquared) {
                return false;
            }
        }
        return true;
    }

    private void refreshStatus(GameTable table, PlacedTable placed) {
        if (placed.statusDisplayId() == null) {
            return;
        }
        // 实体缺失时静默跳过是有意的：区块未加载时 Bukkit.getEntity 返回 null 属正常情况，
        // 且本方法会被 tick 间接高频调用，加日志会刷屏
        Entity entity = Bukkit.getEntity(placed.statusDisplayId());
        updateTextEntity(entity, buildStatus(table));
    }

    private void refreshPlayDetail(GameTable table, PlacedTable placed) {
        if (placed.playDetailDisplayId() == null) {
            return;
        }
        // 实体缺失时静默跳过是有意的：区块未加载时 Bukkit.getEntity 返回 null 属正常情况，
        // 且本方法由每桌 owner tick 每 2 秒调用一次，加日志会刷屏
        Entity entity = Bukkit.getEntity(placed.playDetailDisplayId());
        updateTextEntity(entity, buildPlayDetail(table));
        // 开局后（非 LOBBY）对入座真人隐藏桌边动态，避免浮空字挡住其低头看牌；文本刷新后立即重算可见性。
        updatePlayDetailVisibility(table, placed);
    }

    /**
     * 「桌边动态」浮空字对某座位玩家是否应隐藏。
     * 开局后（非 LOBBY）对坐在本桌的在线真人隐藏，避免浮空字挡住其低头看牌；
     * 大厅阶段以及旁观者一律可见，机器人不参与可见性控制。
     * 抽成 static 纯函数以便单测锁定“开局后才隐藏”这条边界，防止回归。
     */
    static boolean playDetailHiddenForSeatedPlayer(GamePhase phase, boolean seatedHuman) {
        return seatedHuman && phase != GamePhase.LOBBY;
    }

    /**
     * 桌边动态浮空字是公共实体（默认对所有人可见），这里按阶段+入座情况做按人可见性控制。
     * hideEntity 的隐藏状态按玩家持久，故每次都对在线玩家显式 show/hide；
     * 玩家离桌或本局结束回到 LOBBY 后，下一次 refresh/owner tick 会把可见性重新算回来。
     * 写法与 {@link #updateSeatInfoVisibility} 一致（同样排除机器人）。
     */
    private void updatePlayDetailVisibility(GameTable table, PlacedTable placed) {
        if (plugin.isShuttingDown()) {
            return;
        }
        if (placed.playDetailDisplayId() == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(placed.playDetailDisplayId());
        if (entity == null) {
            return;
        }
        GamePhase phase = table.getPhase();
        for (UUID viewerId : onlinePlayerIdsSnapshot()) {
            // 坐在本桌且不是机器人的真人，开局后对本人隐藏桌边动态；旁观者与大厅阶段一律显示。
            boolean seatedHuman = table.contains(viewerId) && !table.isBot(viewerId);
            if (playDetailHiddenForSeatedPlayer(phase, seatedHuman)) {
                playerOutput.hideEntity(viewerId, plugin, entity);
            } else {
                playerOutput.showEntity(viewerId, plugin, entity);
            }
        }
    }

    private void refreshSeatInfos(GameTable table, PlacedTable placed) {
        for (int index = 0; index < placed.seatNameDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatNameDisplayIds().get(index));
            updateTextEntity(entity, seatName(table, index));
            if (entity != null && index < placed.seatBaseLocations().size()) {
                teleportIfMoved(entity, seatNameLocation(table, index, placed.seatBaseLocations().get(index), placed.yaw()));
            }
        }
        for (int index = 0; index < placed.seatInfoDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatInfoDisplayIds().get(index));
            updateTextEntity(entity, seatInfo(table, index));
            if (entity != null && index < placed.seatBaseLocations().size()) {
                teleportIfMoved(entity, seatInfoLocation(table, index, placed.seatBaseLocations().get(index), placed.yaw()));
            }
        }
        updateSeatInfoVisibility(table, placed);
    }

    private void updateSeatInfoVisibility(GameTable table, PlacedTable placed) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (int index = 0; index < placed.seatNameDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatNameDisplayIds().get(index));
            if (entity == null) {
                continue;
            }
            UUID owner = placed.seatAssignments().get(index);
            for (UUID viewerId : onlinePlayerIdsSnapshot()) {
                if (owner != null && viewerId.equals(owner) && !table.isBot(owner)) {
                    playerOutput.hideEntity(viewerId, plugin, entity);
                } else {
                    playerOutput.showEntity(viewerId, plugin, entity);
                }
            }
        }
        for (int index = 0; index < placed.seatInfoDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatInfoDisplayIds().get(index));
            if (entity == null) {
                continue;
            }
            UUID owner = placed.seatAssignments().get(index);
            for (UUID viewerId : onlinePlayerIdsSnapshot()) {
                if (owner != null && viewerId.equals(owner) && !table.isBot(owner)) {
                    playerOutput.hideEntity(viewerId, plugin, entity);
                } else {
                    playerOutput.showEntity(viewerId, plugin, entity);
                }
            }
        }
    }

    /**
     * 开局展示使用固定 17 槽位和独立旋转变换。
     * 普通手牌仍走下方既有 renderPrivateHand/updatePrivateSelection 路径。
     */
    private void refreshOpeningHands(GameTable table, PlacedTable placed) {
        String tableKey = normalize(table.getName());
        HandDealPresentation presentation = handDealPresentations.computeIfAbsent(
            tableKey, ignored -> new HandDealPresentation());
        if (!presentation.active()) {
            presentation.activate();
            privateHandSignatureByTable.remove(tableKey);
            backsideHandSignatureByTable.remove(tableKey);
            for (UUID playerId : new ArrayList<>(placed.privateEntitiesByPlayer().keySet())) {
                clearPrivateEntities(placed, playerId);
            }
            for (UUID playerId : new ArrayList<>(placed.backsideEntitiesByPlayer().keySet())) {
                clearBacksideEntities(placed, playerId);
            }
        }
        GameTable.OpeningPresentationSnapshot snapshot = table.getOpeningPresentationSnapshot();
        boolean openingPhase = snapshot.phase() == GamePhase.DEALING;
        double flipDegrees = presentation.continuousDegrees(snapshot.flipDegrees(), openingPhase);
        Set<UUID> currentPlayers = snapshot.seats().stream()
            .map(GameTable.OpeningSeatSnapshot::playerId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (UUID stale : new ArrayList<>(presentation.seats().keySet())) {
            if (!currentPlayers.contains(stale)) {
                clearOpeningSeat(placed, presentation, stale);
            }
        }
        for (GameTable.OpeningSeatSnapshot seat : snapshot.seats()) {
            List<DoudizhuCard> hand = table.getHand(seat.playerId());
            renderOpeningLayer(table, placed, presentation, seat.playerId(), hand, false, flipDegrees);
            renderOpeningLayer(table, placed, presentation, seat.playerId(), hand, true, flipDegrees);
        }
    }

    private void renderOpeningLayer(
        GameTable table,
        PlacedTable placed,
        HandDealPresentation presentation,
        UUID playerId,
        List<DoudizhuCard> hand,
        boolean backside,
        double flipDegrees
    ) {
        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }
        HandDealPresentation.Seat seat = presentation.seat(playerId);
        List<UUID> entityIds = backside
            ? placed.backsideEntitiesByPlayer().computeIfAbsent(playerId, ignored -> new ArrayList<>())
            : placed.privateEntitiesByPlayer().computeIfAbsent(playerId, ignored -> new ArrayList<>());
        for (int index = 0; index < HandDealPresentation.SLOT_COUNT; index++) {
            int cardId = index < hand.size() ? hand.get(index).id() : -1;
            HandDealPresentation.Slot previous = backside ? seat.backsideSlot(index) : seat.privateSlot(index);
            ItemDisplay display = previous == null ? null : asItemDisplay(previous.displayId());
            boolean created = display == null;
            boolean cardChanged = HandDealPresentation.itemNeedsUpdate(previous, cardId);
            Location location = openingCardLocation(placed, playerId, index, backside);
            if (created) {
                display = spawnOpeningCard(location,
                    cardId < 0 ? new ItemStack(Material.AIR) : (backside ? backCardItem() : cardItem(hand.get(index))),
                    privateCardScale(false, false), handCardYaw(placed.yaw(), seatIndex), flipDegrees, placed.owner());
                replaceOpeningEntityId(entityIds, index, display.getUniqueId());
            } else {
                teleportIfMoved(display, location, CARD_TRACK_EPSILON_SQUARED);
                if (cardChanged) {
                    display.setItemStack(cardId < 0 ? new ItemStack(Material.AIR)
                        : (backside ? backCardItem() : cardItem(hand.get(index))));
                }
                applyOpeningRotation(display, privateCardScale(false, false), flipDegrees);
            }
            if (!backside && cardId >= 0 && (created || cardChanged)) {
                cardBindings.put(display.getUniqueId(), new CardBinding(table.getName(), playerId, cardId));
            } else if (!backside && cardChanged) {
                cardBindings.remove(display.getUniqueId());
            }
            boolean revealed = table.isHandRevealed(playerId);
            if (backside && (revealed || cardId < 0)) {
                hideEntityFromEveryone(display);
            } else if (backside) {
                applyBacksideVisibility(playerId, display);
            } else {
                applyPrivateVisibility(playerId, display, revealed);
            }
            HandDealPresentation.Slot slot = new HandDealPresentation.Slot(index, display.getUniqueId(), cardId);
            if (backside) {
                seat.backsideSlot(index, slot);
            } else {
                seat.privateSlot(index, slot);
            }
        }
    }

    private void replaceOpeningEntityId(List<UUID> entityIds, int slot, UUID entityId) {
        while (entityIds.size() <= slot) {
            entityIds.add(null);
        }
        entityIds.set(slot, entityId);
    }

    private ItemDisplay asItemDisplay(UUID id) {
        Entity entity = id == null ? null : Bukkit.getEntity(id);
        return entity instanceof ItemDisplay display ? display : null;
    }

    private Location openingCardLocation(PlacedTable placed, UUID playerId, int slot, boolean backside) {
        int seatIndex = placedSeatIndex(placed, playerId);
        Vector step = backside ? handStep(seatIndex) : privateHandStep(seatIndex, playerId);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = backside ? globalHandAdjustment(seatIndex) : privateHandAdjustment(seatIndex, playerId);
        double delta = HandDealPresentation.slotOffset(slot);
        return rotate(
            placed.anchor(), placed.yaw(),
            center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
            center.y() + adjustment.y(),
            center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
        );
    }

    private ItemDisplay spawnOpeningCard(
        Location location,
        ItemStack item,
        Vector3f scale,
        float yaw,
        double degrees,
        TableOwner owner
    ) {
        ItemDisplay display = VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            // 开局牌先以不可见默认值出生；授权可见性在实体创建后统一按牌主/旁观者逐人放行。
            spawned.setVisibleByDefault(false);
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(openingCardTransformation(scale, 0.0f, degrees));
            configureOpeningAnimation(spawned);
            protectEntity(spawned, owner, ENTITY_ROLE_CARD);
        });
        applyStableYaw(display, yaw);
        return display;
    }

    private void applyOpeningRotation(ItemDisplay display, Vector3f scale, double degrees) {
        configureOpeningAnimation(display);
        display.setTransformation(openingCardTransformation(scale, 0.0f, degrees));
    }

    private Transformation openingCardTransformation(Vector3f scale, float lift, double degrees) {
        // 只改 Display 的本地右旋转；实体 yaw 仍由既有稳定朝向路径维护。
        // 资源模型已有 Y -90，故此处使用本地竖轴，不绕桌心公转。
        return new Transformation(
            new Vector3f(0.0f, lift, 0.0f),
            new AxisAngle4f(),
            scale,
            new AxisAngle4f((float) Math.toRadians(degrees), 0.0f, 1.0f, 0.0f)
        );
    }

    private void configureOpeningAnimation(Display display) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        try {
            display.setTeleportDuration(0);
        } catch (NoSuchMethodError ignored) {
            // 旧目标没有 setTeleportDuration，插值仍由 Display 变换持续时间保证。
        }
    }

    private void applyBacksideVisibility(UUID ownerId, Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (UUID viewerId : onlinePlayerIdsSnapshot()) {
            if (viewerId.equals(ownerId)) {
                playerOutput.hideEntity(viewerId, plugin, entity);
            } else {
                playerOutput.showEntity(viewerId, plugin, entity);
            }
        }
    }

    private void hideEntityFromEveryone(Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (UUID viewerId : onlinePlayerIdsSnapshot()) {
            playerOutput.hideEntity(viewerId, plugin, entity);
        }
    }

    private void clearOpeningSeat(PlacedTable placed, HandDealPresentation presentation, UUID playerId) {
        HandDealPresentation.Seat seat = presentation.seats().remove(playerId);
        if (seat == null) {
            return;
        }
        List<UUID> privateIds = placed.privateEntitiesByPlayer().remove(playerId);
        List<UUID> backsideIds = placed.backsideEntitiesByPlayer().remove(playerId);
        placed.privateVisualsByPlayer().remove(playerId);
        placed.backsideVisualsByPlayer().remove(playerId);
        if (privateIds != null) {
            privateIds.forEach(cardBindings::remove);
            clearOwnedEntities(placed.owner(), privateIds, false);
        }
        if (backsideIds != null) {
            backsideIds.forEach(cardBindings::remove);
            clearOwnedEntities(placed.owner(), backsideIds, false);
        }
    }

    private void clearOpeningPresentation(GameTable table, PlacedTable placed) {
        String tableKey = normalize(table.getName());
        HandDealPresentation presentation = handDealPresentations.remove(tableKey);
        if (presentation == null) {
            return;
        }
        for (UUID playerId : new ArrayList<>(presentation.seats().keySet())) {
            clearOpeningSeat(placed, presentation, playerId);
        }
    }

    private void migrateOpeningPresentation(GameTable table, PlacedTable placed) {
        String tableKey = normalize(table.getName());
        HandDealPresentation presentation = handDealPresentations.get(tableKey);
        if (presentation == null) {
            return;
        }
        GameTable.OpeningPresentationSnapshot snapshot = table.getOpeningPresentationSnapshot();
        Set<UUID> currentPlayers = snapshot.seats().stream()
            .map(GameTable.OpeningSeatSnapshot::playerId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (UUID stale : new ArrayList<>(presentation.seats().keySet())) {
            if (!currentPlayers.contains(stale)) {
                clearOpeningSeat(placed, presentation, stale);
            }
        }
        boolean migrated = true;
        for (GameTable.OpeningSeatSnapshot seat : snapshot.seats()) {
            if (!migrateOpeningSeat(table, placed, presentation, seat.playerId())) {
                migrated = false;
                break;
            }
        }
        if (!migrated) {
            // 任一槽位实体已经丢失就回退到既有完整重建，优先保证牌面与点击捕获器不残缺。
            clearOpeningPresentation(table, placed);
            return;
        }
        Map<UUID, String> privateSignatures = privateHandSignatureByTable.computeIfAbsent(
            tableKey, ignored -> new LinkedHashMap<>());
        Map<UUID, String> backsideSignatures = backsideHandSignatureByTable.computeIfAbsent(
            tableKey, ignored -> new LinkedHashMap<>());
        for (UUID playerId : currentPlayers) {
            String signature = handSignature(table, placed, playerId);
            privateSignatures.put(playerId, signature);
            backsideSignatures.put(playerId, signature);
        }
        presentation.seats().clear();
        handDealPresentations.remove(tableKey);
    }

    private boolean migrateOpeningSeat(
        GameTable table,
        PlacedTable placed,
        HandDealPresentation presentation,
        UUID playerId
    ) {
        HandDealPresentation.Seat dealSeat = presentation.seat(playerId);
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (hand.isEmpty() || hand.size() > HandDealPresentation.SLOT_COUNT) {
            return false;
        }
        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return false;
        }
        List<UUID> openingPrivateIds = placed.privateEntitiesByPlayer()
            .getOrDefault(playerId, List.of());
        List<UUID> openingBacksideIds = placed.backsideEntitiesByPlayer()
            .getOrDefault(playerId, List.of());
        List<UUID> privateIds = new ArrayList<>();
        List<UUID> backsideIds = new ArrayList<>();
        Map<Integer, HandCardVisual> visuals = new LinkedHashMap<>();
        Map<Integer, UUID> backsideVisuals = new LinkedHashMap<>();
        Set<Integer> selected = table.getSelection(playerId);
        Integer hovered = hoveredCardIds.get(playerId);
        HandCardPickGeometry.Envelope[] pickEnvelopes = unifiedHandCardEnvelopes();
        HandCardPickGeometry.Envelope capturerEnvelope =
            handCardCapturerEnvelope(pickEnvelopes[0], pickEnvelopes[1]);
        float capturerWidth = (float) handCardCapturerWidth(plugin.getHandSpacing());
        float capturerHeight = (float) (capturerEnvelope.halfHeight() * 2.0);
        double edgeTileWidth = HandCardPickGeometry.edgeTileWidth(
            pickEnvelopes[0].halfWidth(), capturerWidth);
        Vector privateStep = privateHandStep(seatIndex, playerId);
        Vector privateCenter = handCenter(seatIndex);
        Vector privateDepth = handDepth(seatIndex);
        Vector privateAdjustment = privateHandAdjustment(seatIndex, playerId);
        Vector backsideStep = handStep(seatIndex);
        Vector backsideCenter = handCenter(seatIndex);
        Vector backsideDepth = handDepth(seatIndex);
        Vector backsideAdjustment = globalHandAdjustment(seatIndex);
        Map<CardRank, Integer> rankCounts = countRanks(hand);
        boolean revealed = table.isHandRevealed(playerId);
        try {
            for (int index = 0; index < hand.size(); index++) {
                DoudizhuCard card = hand.get(index);
                HandDealPresentation.Slot slot = dealSeat.privateSlot(index);
                ItemDisplay display = slot == null ? null : asItemDisplay(slot.displayId());
                if (display == null) {
                    clearOwnedEntities(placed.owner(), privateIds, false);
                    clearOwnedEntities(placed.owner(), backsideIds, false);
                    return false;
                }
                privateIds.add(display.getUniqueId());
                if (slot.cardId() != card.id()) {
                    display.setItemStack(cardItem(card));
                }
                double delta = HandDealPresentation.centeredSlotOffset(hand.size(), index);
                Location cardBaseLocation = rotate(
                    placed.anchor(), placed.yaw(),
                    privateCenter.x() + privateAdjustment.x() + privateStep.x() * delta + privateDepth.x() * delta,
                    privateCenter.y() + privateAdjustment.y(),
                    privateCenter.z() + privateAdjustment.z() + privateStep.z() * delta + privateDepth.z() * delta
                );
                teleportIfMoved(display, cardBaseLocation, CARD_TRACK_EPSILON_SQUARED);
                float selectedProgress = currentAnimationProgress(selectedProgressByPlayer, playerId, card.id());
                float hoverProgress = currentAnimationProgress(hoverProgressByPlayer, playerId, card.id());
                double lift = animatedCardLift(selectedProgress, hoverProgress);
                configureCardAnimation(display);
                display.setTransformation(cardTransformation(privateCardScale(hoverProgress), (float) lift));
                applyStableYaw(display, handCardYaw(placed.yaw(), seatIndex));
                applyCardGlow(display, playerId, selected.contains(card.id()), hovered != null && hovered == card.id());
                cardBindings.put(display.getUniqueId(), new CardBinding(table.getName(), playerId, card.id()));
                UUID capturerId = spawnHandCardCapturer(
                    table, placed, playerId, card, cardBaseLocation,
                    capturerEnvelope, capturerWidth, capturerHeight, privateIds);
                UUID leftEdgeTileId = index == 0
                    ? placeHandCardEdgeTile(table, placed.owner(), playerId, card, HandEdge.LEFT, cardBaseLocation, privateStep,
                        capturerEnvelope, capturerWidth, capturerHeight, edgeTileWidth, null, privateIds)
                    : null;
                UUID rightEdgeTileId = index == hand.size() - 1
                    ? placeHandCardEdgeTile(table, placed.owner(), playerId, card, HandEdge.RIGHT, cardBaseLocation, privateStep,
                        capturerEnvelope, capturerWidth, capturerHeight, edgeTileWidth, null, privateIds)
                    : null;
                UUID labelId = null;
                if (shouldShowPrivateLabel(playerId, card, rankCounts)) {
                    TextDisplay label = spawnText(
                        privateCardLabelLocation(cardBaseLocation, seatIndex, placed.yaw(), lift),
                        MuzTheme.cardLabel(card.rank().label()),
                        Display.Billboard.CENTER,
                        false,
                        plugin.getLabelTextScale(),
                        false,
                        placed.owner(),
                        ENTITY_ROLE_CARD_LABEL
                    );
                    mountTextDisplay(display, label, label.getLocation(), false);
                    privateIds.add(label.getUniqueId());
                    cardBindings.put(label.getUniqueId(), new CardBinding(table.getName(), playerId, card.id()));
                    applyPrivateVisibility(playerId, label, revealed);
                    labelId = label.getUniqueId();
                }
                visuals.put(card.id(), new HandCardVisual(
                    display.getUniqueId(), labelId, capturerId, leftEdgeTileId, rightEdgeTileId));
                applyPrivateVisibility(playerId, display, revealed);
            }
            clearOwnedEntities(placed.owner(), openingPrivateIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> !privateIds.contains(id))
                .toList(), false);
            placed.privateEntitiesByPlayer().put(playerId, privateIds);
            placed.privateVisualsByPlayer().put(playerId, visuals);

            if (revealed) {
                clearOwnedEntities(placed.owner(), openingBacksideIds.stream().filter(Objects::nonNull).toList(), false);
                placed.backsideEntitiesByPlayer().remove(playerId);
                placed.backsideVisualsByPlayer().remove(playerId);
            } else {
                for (int index = 0; index < hand.size(); index++) {
                    DoudizhuCard card = hand.get(index);
                    HandDealPresentation.Slot slot = dealSeat.backsideSlot(index);
                    ItemDisplay display = slot == null ? null : asItemDisplay(slot.displayId());
                    if (display == null) {
                        clearOwnedEntities(placed.owner(), privateIds, false);
                        clearOwnedEntities(placed.owner(), backsideIds, false);
                        return false;
                    }
                    backsideIds.add(display.getUniqueId());
                    if (slot.cardId() != card.id()) {
                        display.setItemStack(backCardItem());
                    }
                    double delta = HandDealPresentation.centeredSlotOffset(hand.size(), index);
                    Location cardBaseLocation = rotate(
                        placed.anchor(), placed.yaw(),
                        backsideCenter.x() + backsideAdjustment.x() + backsideStep.x() * delta + backsideDepth.x() * delta,
                        backsideCenter.y() + backsideAdjustment.y(),
                        backsideCenter.z() + backsideAdjustment.z() + backsideStep.z() * delta + backsideDepth.z() * delta
                    );
                    teleportIfMoved(display, cardBaseLocation, CARD_TRACK_EPSILON_SQUARED);
                    configureCardAnimation(display);
                    display.setTransformation(cardTransformation(privateCardScale(false, false),
                        (float) selectedCardLift(selected.contains(card.id()), false)));
                    applyStableYaw(display, handCardYaw(placed.yaw(), seatIndex));
                    applyCardGlow(display, playerId, selected.contains(card.id()), false);
                    applyBacksideVisibility(playerId, display);
                    backsideVisuals.put(card.id(), display.getUniqueId());
                }
                clearOwnedEntities(placed.owner(), openingBacksideIds.stream()
                    .filter(Objects::nonNull)
                    .filter(id -> !backsideIds.contains(id))
                    .toList(), false);
                placed.backsideEntitiesByPlayer().put(playerId, backsideIds);
                placed.backsideVisualsByPlayer().put(playerId, backsideVisuals);
            }
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                "开局手牌接管失败，将重建牌桌 " + table.getName() + " 的手牌", exception);
            clearOwnedEntities(placed.owner(), privateIds, false);
            clearOwnedEntities(placed.owner(), backsideIds, false);
            return false;
        }
    }

    private void refreshPrivateHands(GameTable table, PlacedTable placed) {
        if (table.isOpeningHandLocked()) {
            refreshOpeningHands(table, placed);
            return;
        }
        migrateOpeningPresentation(table, placed);
        // 每位玩家都有两层牌：
        // 1. 只有自己能看到的正面牌
        // 2. 其他人能看到的背面牌
        Set<UUID> currentPlayers = Set.copyOf(table.getSeats());
        String tableKey = normalize(table.getName());
        Map<UUID, String> privateSignatures = privateHandSignatureByTable.computeIfAbsent(tableKey, ignored -> new LinkedHashMap<>());
        Map<UUID, String> backsideSignatures = backsideHandSignatureByTable.computeIfAbsent(tableKey, ignored -> new LinkedHashMap<>());
        for (UUID playerId : new ArrayList<>(placed.backsideEntitiesByPlayer().keySet())) {
            if (!currentPlayers.contains(playerId)) {
                clearBacksideEntities(placed, playerId);
                backsideSignatures.remove(playerId);
            }
        }
        for (UUID playerId : new ArrayList<>(placed.privateEntitiesByPlayer().keySet())) {
            if (!currentPlayers.contains(playerId)) {
                clearPrivateEntities(placed, playerId);
                privateSignatures.remove(playerId);
            }
        }
        for (UUID playerId : table.getSeats()) {
            String handSignature = handSignature(table, placed, playerId);
            if (!Objects.equals(backsideSignatures.get(playerId), handSignature)) {
                renderBacksideHand(table, placed, playerId);
                backsideSignatures.put(playerId, handSignature);
            }
            if (!Objects.equals(privateSignatures.get(playerId), handSignature)) {
                renderPrivateHand(table, placed, playerId);
                privateSignatures.put(playerId, handSignature);
            }
        }
    }

    private void renderBacksideHand(GameTable table, PlacedTable placed, UUID playerId) {
        // 背面牌是给“其他人”看的，所以会对牌主人隐藏
        clearBacksideEntities(placed, playerId);
        if (table.getPhase() == GamePhase.LOBBY) {
            return;
        }
        // 明牌的玩家不再铺背面牌，正面牌会直接对全场公开。
        // 两层同时存在会在同一位置叠出两张牌。
        if (table.isHandRevealed(playerId)) {
            return;
        }
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (hand.isEmpty()) {
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }

        List<UUID> spawned = new ArrayList<>();
        Map<Integer, UUID> visuals = new LinkedHashMap<>();
        Set<Integer> selected = table.getSelection(playerId);
        Vector step = handStep(seatIndex);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = globalHandAdjustment(seatIndex);
        double startOffset = HandDealPresentation.centeredStartOffset(hand.size());
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            boolean isSelected = selected.contains(card.id());
            double delta = startOffset + index;
            Location cardBaseLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
                center.y() + adjustment.y(),
                center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
            );
            double lift = selectedCardLift(isSelected, false);
            ItemDisplay cardDisplay = spawnPlacedCard(
                cardBaseLocation,
                backCardItem(),
                privateCardScale(false, false),
                cardYaw,
                (float) lift,
                placed.owner()
            );
            applyCardGlow(cardDisplay, playerId, isSelected, false);
            spawned.add(cardDisplay.getUniqueId());
            visuals.put(card.id(), cardDisplay.getUniqueId());
            for (UUID viewerId : onlinePlayerIdsSnapshot()) {
                if (viewerId.equals(playerId) && !table.isBot(playerId)) {
                    playerOutput.hideEntity(viewerId, plugin, cardDisplay);
                } else {
                    playerOutput.showEntity(viewerId, plugin, cardDisplay);
                }
            }
        }
        placed.backsideEntitiesByPlayer().put(playerId, spawned);
        placed.backsideVisualsByPlayer().put(playerId, visuals);
    }

    private void renderPrivateHand(GameTable table, PlacedTable placed, UUID playerId) {
        // 正面手牌只给主人自己看，右键切换选择、左键对已选牌执行出牌。
        //
        // 捕获器在重建前后【复用】而不是重新生成，所以必须先把还活着的那批摘出来，
        // 让 clearPrivateEntities 跳过它们，否则会被连带删掉。理由见 reusableHandCardCapturers。
        Map<Integer, Interaction> reusableCapturers = reusableHandCardCapturers(placed, playerId);
        // 两端的边缘瓦片走同一套复用逻辑，只是按端位键而不是牌 id 键。
        // 理由见 reusableEdgeTiles：它和捕获器一样是点击事件入口，重建会换 entity id 而丢事件。
        Map<HandEdge, Interaction> reusableTiles = reusableEdgeTiles(placed, playerId);
        clearPrivateEntities(placed, playerId, reusableCapturers, reusableTiles);
        try {
            renderPrivateHandCards(table, placed, playerId, reusableCapturers, reusableTiles);
        } finally {
            // 没被这次铺牌认领的旧捕获器（对应的牌已经打出去了）必须销毁：
            // 它已经不在 privateEntitiesByPlayer 里，漏掉就是永久的孤儿实体，
            // 留在原地继续接事件，表现为「点空气选中了一张不存在的牌」。
            discardUnclaimedCapturers(placed.owner(), reusableCapturers);
            discardUnclaimedEdgeTiles(placed.owner(), reusableTiles);
        }
    }

    /**
     * 把这位玩家当前还活着的手牌点击捕获器按牌 id 摘出来，供本次铺牌复用。
     *
     * <p><b>为什么必须复用而不是重建。</b>销毁再新建会换一个 entity id。客户端在收到
     * remove + add 这两个包之前，仍然按<b>旧 id</b> 发 {@code ServerboundInteractPacket}，
     * 服务端按 id 解析不到实体，事件压根不触发 —— 点击被静默丢弃，
     * 窗口约 1 tick 加半个 RTT（50ms ping 约 1~2 tick，150ms 约 3~4 tick）。
     * 玩家的感受就是「刚出牌那一下点牌没反应」。
     *
     * <p>退一步说，{@code clearEntities} 还会把 {@code cardBindings} 一起抹掉，
     * 而没有 binding 的 Interaction 会被 {@code isChairFurnitureEntity} 的兜底判成椅子家具，
     * {@code yieldsToBlockingEntity} 让位 —— 照样静默丢弃。两道都是死的。
     *
     * <p>复用池这个模式在本文件里早有先例：按钮区的 {@link #syncActionWidgets} 就是
     * 「优先复用已有实体，避免点击和打牌时整排闪烁」。手牌需要的正是同一套做法。
     */
    private Map<Integer, Interaction> reusableHandCardCapturers(PlacedTable placed, UUID playerId) {
        Map<Integer, Interaction> reusable = new LinkedHashMap<>();
        Map<Integer, HandCardVisual> visuals = placed.privateVisualsByPlayer().get(playerId);
        if (visuals == null) {
            return reusable;
        }
        for (Map.Entry<Integer, HandCardVisual> entry : visuals.entrySet()) {
            UUID capturerId = entry.getValue().capturerId();
            if (capturerId == null) {
                continue;
            }
            // 解析不到（被邻桌清场删掉、所在区块卸载过）就不进池子，
            // 于是下面照常 spawn 一个新的 —— 捕获器缺失必须被补齐这条不变量不变。
            if (Bukkit.getEntity(capturerId) instanceof Interaction capturer) {
                reusable.put(entry.getKey(), capturer);
            }
        }
        return reusable;
    }

    /**
     * 把这位玩家当前还活着的两块边缘瓦片按端位摘出来，供本次铺牌复用。
     *
     * <p><b>为什么瓦片也必须复用。</b>与捕获器同一个理由，见
     * {@link #reusableHandCardCapturers}：销毁再新建会换 entity id，客户端在收到
     * remove + add 之前仍按旧 id 发 {@code ServerboundInteractPacket}，服务端解析不到实体，
     * 事件压根不触发，窗口约 1 tick 加半个 RTT。瓦片是同一类实体、承担同一个
     * 「点击事件唯一入口」的角色，让它每次铺牌重建就是在两端那两条边缘条上
     * 精确重建这个丢事件窗口 —— 表现为「刚出完一手牌，点牌边没反应」。
     *
     * <p>不能拿「与牌本体同步闪烁」当理由：牌本体是 {@code ItemDisplay}，不接收点击，
     * 它被重建不产生任何丢事件窗口，没有可对齐的对象。
     *
     * <p>按<b>端位</b>而不是牌 id 键：N=1 时同一张牌要挂左右两块，牌 id 做键装不下。
     * 端位语义也更贴合复用意图 —— 出牌后手牌整体重排，「最左那块瓦片」该继续当最左，
     * 哪怕最左现在换成了另一张牌。
     */
    private Map<HandEdge, Interaction> reusableEdgeTiles(PlacedTable placed, UUID playerId) {
        Map<HandEdge, Interaction> reusable = new java.util.EnumMap<>(HandEdge.class);
        Map<Integer, HandCardVisual> visuals = placed.privateVisualsByPlayer().get(playerId);
        if (visuals == null) {
            return reusable;
        }
        for (HandCardVisual visual : visuals.values()) {
            claimLiveTile(reusable, HandEdge.LEFT, visual.leftEdgeTileId());
            claimLiveTile(reusable, HandEdge.RIGHT, visual.rightEdgeTileId());
        }
        return reusable;
    }

    /**
     * 把一个还活着的瓦片登记进复用池。
     *
     * <p>解析不到（被邻桌清场删掉、所在区块卸载过）就不进池子，于是上面照常 spawn 一个新的
     * —— 「瓦片缺失必须被补齐」这条不变量与捕获器同口径。
     */
    private void claimLiveTile(Map<HandEdge, Interaction> pool, HandEdge edge, UUID tileId) {
        if (tileId == null || pool.containsKey(edge)) {
            return;
        }
        if (Bukkit.getEntity(tileId) instanceof Interaction tile) {
            pool.put(edge, tile);
        }
    }

    private void discardUnclaimedCapturers(TableOwner owner, Map<Integer, Interaction> unclaimed) {
        if (unclaimed.isEmpty()) {
            return;
        }
        clearOwnedEntities(
            owner,
            unclaimed.values().stream().map(Entity::getUniqueId).collect(java.util.stream.Collectors.toList()),
            false
        );
        unclaimed.clear();
    }

    /**
     * 收掉本次铺牌没被认领的边缘瓦片。
     *
     * <p>端位会变化：手牌从 N≥2 掉到 N=1 时两块瓦片改挂同一张牌，
     * 从 N≥1 掉到 0 时两块都不再需要。不收就是孤儿实体。
     */
    private void discardUnclaimedEdgeTiles(TableOwner owner, Map<HandEdge, Interaction> unclaimed) {
        if (unclaimed.isEmpty()) {
            return;
        }
        clearOwnedEntities(
            owner,
            unclaimed.values().stream().map(Entity::getUniqueId).collect(java.util.stream.Collectors.toList()),
            false
        );
        unclaimed.clear();
    }

    private void renderPrivateHandCards(
        GameTable table,
        PlacedTable placed,
        UUID playerId,
        Map<Integer, Interaction> reusableCapturers,
        Map<HandEdge, Interaction> reusableEdgeTiles
    ) {
        // 明牌的牌面要给全场看，所以牌主掉线时也得照常铺。
        // 只有未明牌时才需要牌主在线：那种情况下这层牌只有他自己能看见。
        boolean revealed = table.isHandRevealed(playerId);
        if (!revealed && !onlinePlayerIdsSnapshot().contains(playerId)) {
            return;
        }
        if (table.getPhase() == GamePhase.LOBBY) {
            return;
        }

        List<DoudizhuCard> hand = table.getHand(playerId);
        if (hand.isEmpty()) {
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }

        Set<Integer> selected = table.getSelection(playerId);
        Integer hovered = hoveredCardIds.get(playerId);
        List<UUID> spawned = new ArrayList<>();
        Map<Integer, HandCardVisual> visuals = new LinkedHashMap<>();
        Vector step = privateHandStep(seatIndex, playerId);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = privateHandAdjustment(seatIndex, playerId);
        double startOffset = HandDealPresentation.centeredStartOffset(hand.size());
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        Map<CardRank, Integer> rankCounts = countRanks(hand);
        // 点击捕获器的尺寸与拾取包络同源，见 unifiedHandCardEnvelopes 与 handCardCapturerWidth。
        // 高度取两态并集（见 handCardCapturerEnvelope），所以它与选中状态无关。
        HandCardPickGeometry.Envelope[] pickEnvelopes = unifiedHandCardEnvelopes();
        HandCardPickGeometry.Envelope capturerEnvelope =
            handCardCapturerEnvelope(pickEnvelopes[0], pickEnvelopes[1]);
        float capturerWidth = (float) handCardCapturerWidth(plugin.getHandSpacing());
        float capturerHeight = (float) (capturerEnvelope.halfHeight() * 2.0);
        // 两端补覆盖用的瓦片宽度。捕获器宽 == 铺牌步长，所以 N 个捕获器的并集比拾取包络窄，
        // 两端各缺 0.0715 格（默认配置）——那里看得见牌面却点不动。见 edgeTileWidth。
        double edgeTileWidth = HandCardPickGeometry.edgeTileWidth(
            pickEnvelopes[0].halfWidth(), capturerWidth);
        warnIfCapturerCouldOccludeButtons(capturerEnvelope);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            boolean isSelected = selected.contains(card.id());
            boolean isHovered = hovered != null && hovered == card.id();
            double delta = startOffset + index;
            Location cardBaseLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
                center.y() + adjustment.y(),
                center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
            );
            // 出生就用动画的【当前值】，不用 selectedCardLift/privateCardScale 那种阶跃值。
            //
            // 阶跃值会让悬停中的牌一出生就是满态（lift 0.06、scale 1.08），而下一 tick
            // updatePrivateSelection 走 advanceAnimation 从存量进度起步，只推进一步 ——
            // 于是 lift 0.06 → 0.025、scale 1.08 → ~1.034，牌先掉下去缩一下、再花约 6 tick 长回来。
            // 玩家眼睛正盯着那张牌，这一下「落下再长起来」100% 看得见。
            // 读存量进度（clearPrivateEntities 已经不再清这两张表）就完全消掉这个跳变：
            // 没有存量时是 0，牌从平躺平滑升起，与下一 tick 的推进方向一致。
            float selectedProgress = currentAnimationProgress(selectedProgressByPlayer, playerId, card.id());
            float hoverProgress = currentAnimationProgress(hoverProgressByPlayer, playerId, card.id());
            double lift = animatedCardLift(selectedProgress, hoverProgress);

            ItemDisplay cardDisplay = spawnPlacedCard(
                cardBaseLocation,
                cardItem(card),
                privateCardScale(hoverProgress),
                cardYaw,
                (float) lift,
                placed.owner()
            );
            applyCardGlow(cardDisplay, playerId, isSelected, isHovered);
            spawned.add(cardDisplay.getUniqueId());
            cardBindings.put(cardDisplay.getUniqueId(), new CardBinding(table.getName(), playerId, card.id()));

            Interaction reusedCapturer = reusableCapturers.remove(card.id());
            UUID capturerId = reusedCapturer != null
                ? reuseHandCardCapturer(
                    table, placed.owner(), playerId, card, reusedCapturer, cardBaseLocation,
                    capturerEnvelope, capturerWidth, capturerHeight, spawned)
                : spawnHandCardCapturer(
                    table, placed, playerId, card, cardBaseLocation,
                    capturerEnvelope, capturerWidth, capturerHeight, spawned);

            // 两端补边缘瓦片。N=1 时 index 0 同时是最左和最右，两块都挂在这一张上 ——
            // 这正是最不能漏的场合：只剩一张时整张牌面完整露出，缺口占可见牌面 29.4%，
            // 而斗地主每局必然经过「手上 1 张、必须点它出牌获胜」这个状态。
            UUID leftEdgeTileId = index == 0
                ? placeHandCardEdgeTile(
                    table, placed.owner(), playerId, card, HandEdge.LEFT, cardBaseLocation, step,
                    capturerEnvelope, capturerWidth, capturerHeight, edgeTileWidth,
                    reusableEdgeTiles.remove(HandEdge.LEFT), spawned)
                : null;
            UUID rightEdgeTileId = index == hand.size() - 1
                ? placeHandCardEdgeTile(
                    table, placed.owner(), playerId, card, HandEdge.RIGHT, cardBaseLocation, step,
                    capturerEnvelope, capturerWidth, capturerHeight, edgeTileWidth,
                    reusableEdgeTiles.remove(HandEdge.RIGHT), spawned)
                : null;

            if (shouldShowPrivateLabel(playerId, card, rankCounts)) {
                Location labelLocation = privateCardLabelLocation(cardBaseLocation, seatIndex, placed.yaw(), lift);
                TextDisplay label = spawnText(
                    labelLocation,
                    MuzTheme.cardLabel(card.rank().label()),
                    Display.Billboard.CENTER,
                    false,
                    plugin.getLabelTextScale(),
                    false,
                    placed.owner(),
                    ENTITY_ROLE_CARD_LABEL
                );
                mountTextDisplay(cardDisplay, label, labelLocation, false);
                spawned.add(label.getUniqueId());
                cardBindings.put(label.getUniqueId(), new CardBinding(table.getName(), playerId, card.id()));
                applyPrivateVisibility(playerId, label, revealed);
                visuals.put(card.id(), new HandCardVisual(
                    cardDisplay.getUniqueId(), label.getUniqueId(), capturerId,
                    leftEdgeTileId, rightEdgeTileId));
            } else {
                visuals.put(card.id(), new HandCardVisual(
                    cardDisplay.getUniqueId(), null, capturerId,
                    leftEdgeTileId, rightEdgeTileId));
            }

            applyPrivateVisibility(playerId, cardDisplay, revealed);
        }

        placed.privateEntitiesByPlayer().put(playerId, spawned);
        placed.privateVisualsByPlayer().put(playerId, visuals);
    }

    /**
     * 给一张自己的手牌挂上点击捕获器，并登记到能让它活下来的两张表里。
     *
     * <h2>为什么必须有这个实体（已用服务端字节码取证）</h2>
     *
     * <p>手牌是悬浮 {@code ItemDisplay}，<b>没有任何实体判定框</b>。玩家空手时，客户端右键空气
     * 发出的 {@code ServerboundUseItemPacket} 在 {@code ServerGamePacketListenerImpl.handleUseItem}
     * 里被提前拦掉：方法开头 {@code ItemStack.isEmpty()} 为真就直接跳到末尾 return，
     * 而 Paper 的 {@code CraftEventFactory.callPlayerInteractEvent(..., RIGHT_CLICK_AIR, ...)}
     * 就在被跳过的那一段里。所以<b>空手右键空气时 {@code PlayerInteractEvent} 根本不触发</b>，
     * 而手牌点击这条链路（{@code WorldTableInteractionListener.onHandCardClick}）完全依赖它——
     * 表现就是手牌右键选牌彻底失效，HUD 永远「已选 0 张」。
     *
     * <p>悬停发光却正常，因为它走服务端每 tick 的射线（{@code updatePrivateSelection}），
     * 与点击包无关。这正解释了「牌会发光但点不动」这个看起来矛盾的现象。
     *
     * <p>挂一个原版 {@code Interaction} 就能让空手右键产生 {@code PlayerInteractAtEntityEvent}、
     * 左键产生 {@code EntityDamageByEntityEvent}，两条路都已经接了手牌仲裁。
     * 命中哪张牌<b>仍然完全交给 {@code pickHandCard}</b>：捕获器只负责把事件引进来，
     * 不负责精度，于是悬停高亮与点击判定继续出自同一份计算。
     *
     * <h2>为什么不会重建历史上那个死区</h2>
     *
     * <p>牌上曾经挂过 Interaction 又被刻意删掉，原因是它的碰撞箱在牌面之外的深度方向
     * 鼓出约半个牌宽，那圈里右键会触发事件但求交判不中，只能吞掉。这一版把尺寸收紧到
     * 与拾取几何一致（宽 = 通道宽见 {@link #handCardCapturerWidth}、
     * 高 = 两态拾取盒的并集见 {@link #handCardCapturerEnvelope}），
     * 于是「命中捕获器 ⟹ 几乎必然命中包络」；而且 {@code handleHandCardClick} 在
     * {@code pickHandCard} 判不中时仍然 {@code return false} 放行，放行语义没有改。
     *
     * <h2>两处登记都不能少</h2>
     *
     * <ul>
     *   <li>进 {@code spawned}（也就是 {@code privateEntitiesByPlayer}）——
     *       {@code clearPrivateEntities} 才回收得到，否则换手牌时留一堆孤儿实体；</li>
     *   <li>进 {@code cardBindings}——{@code clearResidualEntities} 对
     *       {@code ItemDisplay/TextDisplay/Interaction} 一律强删，唯一豁免是
     *       {@code isTrackedActionEntity}（查 {@code actionBindings} / {@code cardBindings}）。
     *       登记进 {@code cardBindings} 同时还消掉另一个误判：{@code isChairFurnitureEntity}
     *       的兜底分支会把「像家具且不是插件自己生成」的实体判成椅子，而 Interaction
     *       正是家具类型；一旦被判成椅子，{@code yieldsToBlockingEntity} 会让位，
     *       点击既不选牌也不 cancel，被静默丢弃。用 {@code cardBindings} 而<b>不是</b>
     *       {@code actionBindings}：后者会让它被当成按钮而参与让位判断。</li>
     * </ul>
     *
     * <h2>按钮遮挡验算（默认配置，单位格，相对放桌锚点）</h2>
     *
     * <p>动作按钮也用 Interaction 判定框，捕获器若挡在玩家与按钮之间，客户端射线会先命中
     * 捕获器，{@code handleActionButtonOnce} 判 false，按钮点击被静默丢弃。两个方向都算过：
     *
     * <ul>
     *   <li><b>深度（决定性的那一条）</b>：手牌离桌心
     *       {@code hand-center.distance 1.62 − private-hand-offset.depth 0.55 = 1.07}，
     *       逐张错层 {@code card-depth-offset 0.005 × ±8} 后落在 1.03～1.11；
     *       按钮离桌心 {@code (front 1.40 + side 1.72)/2 + (1.40−1.10)×0.45 = 1.695}，
     *       减去弧线深度补偿约 0.045 得 1.65。<b>按钮比手牌更靠外</b>，也就是更靠近玩家，
     *       捕获器永远在按钮<b>背后</b>，不可能挡住射线。</li>
     *   <li><b>竖直（第二道独立余量）</b>：捕获器底边
     *       {@code 3.13 + 0.00345 − 0.5245/2 ≈ 2.8712}（未选中）；按钮判定框顶边
     *       {@code 2.5 + 0.2 + 0.02 + 0.03 + 0.09/2 = 2.795}。两者相差约 <b>0.076</b>，不重叠。</li>
     * </ul>
     *
     * <p>结论：不遮挡，因此<b>不必</b>为了避让而把捕获器高度缩到牌本体 0.3175
     * ——那会让牌抬起后它下方那截空气点不到，是实打实的手感损失。
     * 这两个数字由 {@code HandCardCapturerGeometryTest} 锁住，配置漂移时会变红；
     * 深度那一条还在 {@link #warnIfCapturerCouldOccludeButtons} 里按实机配置再核一次。
     *
     * @param cardBaseLocation 牌实体的位置（不含抬升，抬升走 transformation）
     * @param capturerEnvelope 两态并集包络，见 {@link #handCardCapturerEnvelope}
     * @param spawned 本次铺牌生成的实体清单，捕获器会追加进去
     * @return 捕获器实体 id
     */
    private UUID spawnHandCardCapturer(
        GameTable table,
        PlacedTable placed,
        UUID playerId,
        DoudizhuCard card,
        Location cardBaseLocation,
        HandCardPickGeometry.Envelope capturerEnvelope,
        float capturerWidth,
        float capturerHeight,
        List<UUID> spawned
    ) {
        Location capturerLocation = handCardCapturerLocation(
            cardBaseLocation, capturerEnvelope, capturerHeight);
        Interaction capturer = spawnInteraction(
            capturerLocation,
            capturerWidth,
            capturerHeight,
            placed.owner(),
            ENTITY_ROLE_CARD_CAPTURER
        );
        spawned.add(capturer.getUniqueId());
        cardBindings.put(capturer.getUniqueId(),
            new CardBinding(table.getName(), playerId, card.id()));
        // 只给牌主自己看。别人看到的是背面牌（renderBacksideHand，刻意不挂捕获器），
        // 用不着捕获器；而隐藏的实体不会同步到那个客户端，也就不会有三家的捕获器互相抢射线。
        // 这里刻意不跟 revealed 走：明牌时把捕获器也发给旁观者，旁观者右键它会走进手牌仲裁，
        // 而 pickHandCard 按【点击者自己】的手牌求交必然判不中，事件最后被保护判定静默取消——
        // 等于在别人牌面上凭空造出一片点不动方块的死区。
        applyPrivateVisibility(playerId, capturer);
        return capturer.getUniqueId();
    }

    /**
     * 在手牌某一端补一块边缘瓦片，把捕获器并集盖不到的那条缝填上。
     *
     * <h2>为什么会有这条缝</h2>
     *
     * <p>铺牌步长与捕获器宽度是<b>同一个公式</b>，于是 N 个捕获器恰好首尾相接，
     * 并集只有 {@code [P₀−capHalf, P_{N−1}+capHalf]}；而拾取包络半宽由<b>放大态牌面</b>
     * 胜出（默认 0.1215 &gt; 通道半宽 0.05）。两端于是各差 0.0715 格 ——
     * 那里玩家看得见牌面却点不动。中间的牌不受影响：牌 i 的可见条被牌 i−1 的捕获器
     * 盖住（错位但连续），唯独最两端没有邻居补位。算式见
     * {@link HandCardPickGeometry#edgeTileWidth}。
     *
     * <p>瓦片宽度<b>比现有捕获器更窄</b>（0.0715 &lt; 0.1），所以它在深度方向鼓出更小，
     * 永远在现有捕获器的近面之内 —— {@code warnIfCapturerCouldOccludeButtons} 按
     * {@code handCardCapturerWidth * 0.5} 推出来的「手牌盒最外面那一面」仍然成立，
     * 按钮遮挡余量只增不减。
     *
     * <h2>三张表一件都不能少</h2>
     *
     * <p>与 {@link #spawnHandCardCapturer} 完全同口径：进 {@code spawned} 才回收得到、
     * 进 {@code cardBindings} 才不被邻桌清场误删（也避免 {@code isChairFurnitureEntity}
     * 的兜底分支把它判成椅子家具而 {@code yieldsToBlockingEntity} 让位）、
     * 调 {@code applyPrivateVisibility} 才只发给牌主自己。
     *
     * <p>可见性这一条尤其不能漏：把瓦片发给旁观者，旁观者右键它会走进手牌仲裁，
     * 而 {@code pickHandCard} 按<b>点击者自己</b>的手牌求交必然判不中，
     * 事件最后被保护判定静默取消 —— 等于在每位对手手牌的两端各造出一片
     * 0.0715 格点不动方块的死区，症状与本次要修的缺口镜像对称，反而更难查。
     *
     * <p>瓦片绑的 cardId 与端点牌相同，N=1 时两块瓦片绑同一个 cardId
     * <b>不冲突</b>：{@code cardBindings} 是 {@code entityUUID → CardBinding}，
     * 键是实体 id，两块各占一行；而绑定只做路由与豁免，
     * 「点到哪张牌」永远出自 {@code pickHandCard}。
     *
     * @param edge 端位，决定瓦片摆在端点牌的哪一侧
     * @param anchorCard 端点那张牌（左端是 index 0、右端是 index N−1；N=1 时是同一张）
     * @param reused 复用池里摘到的瓦片，为 null 则新建
     * @return 瓦片实体 id；缺口不为正时返回 null（不需要瓦片）
     */
    private UUID placeHandCardEdgeTile(
        GameTable table,
        TableOwner owner,
        UUID playerId,
        DoudizhuCard anchorCard,
        HandEdge edge,
        Location anchorCardLocation,
        Vector step,
        HandCardPickGeometry.Envelope capturerEnvelope,
        double capturerWidth,
        float capturerHeight,
        double tileWidth,
        Interaction reused,
        List<UUID> spawned
    ) {
        if (tileWidth <= 0.0) {
            return null;
        }
        double offset = HandCardPickGeometry.edgeTileCenterOffset(capturerWidth, tileWidth);
        if (offset <= 0.0) {
            return null;
        }
        // step 是「下一张牌相对上一张」的位移，长度等于铺牌步长，方向就是手牌铺开方向。
        // 归一化后乘偏移量即可，不必再关心座位朝向与桌子 yaw —— 那些已经烘进 step 里了。
        Vector along = normalizeHorizontal(step);
        double sign = edge == HandEdge.LEFT ? -1.0 : 1.0;
        Location tileCardLocation = anchorCardLocation.clone().add(
            along.x() * offset * sign,
            0.0,
            along.z() * offset * sign
        );
        // 竖直位置与捕获器同源：同一个并集包络、同一个底边换算，
        // 于是瓦片与捕获器在竖直方向严格齐平，不会出现「牌边能点、牌角点不到」。
        Location tileLocation = handCardCapturerLocation(
            tileCardLocation, capturerEnvelope, capturerHeight);
        Interaction tile;
        if (reused != null) {
            teleportIfMoved(reused, tileLocation, CARD_TRACK_EPSILON_SQUARED);
            reused.setInteractionWidth((float) tileWidth);
            reused.setInteractionHeight(capturerHeight);
            tile = reused;
        } else {
            tile = spawnInteraction(
                tileLocation,
                (float) tileWidth,
                capturerHeight,
                owner,
                ENTITY_ROLE_CARD_EDGE
            );
        }
        protectEntity(tile, owner, ENTITY_ROLE_CARD_EDGE);
        spawned.add(tile.getUniqueId());
        cardBindings.put(tile.getUniqueId(),
            new CardBinding(table.getName(), playerId, anchorCard.id()));
        applyPrivateVisibility(playerId, tile);
        return tile.getUniqueId();
    }

    /**
     * 复用上一次铺牌留下的捕获器：只搬位置、改尺寸，<b>不销毁不新建</b>。
     *
     * <p>保住 entity id 不变是这条修法的全部意义，理由见 {@link #reusableHandCardCapturers}。
     * 登记的两张表照旧要写：{@code spawned} 让它继续被 {@code clearPrivateEntities} 管着，
     * {@code cardBindings} 在 {@code clearEntities} 那轮虽已跳过它，这里仍然重写一次，
     * 把「捕获器必然有 binding」这条不变量留在本方法内部，不依赖上游的跳过集合。
     *
     * <p>可见性也要重新应用：牌主中途掉线又回来时，{@code hideEntity/showEntity} 的
     * 逐玩家状态需要按当前在线玩家重算一遍。
     */
    private UUID reuseHandCardCapturer(
        GameTable table,
        TableOwner owner,
        UUID playerId,
        DoudizhuCard card,
        Interaction capturer,
        Location cardBaseLocation,
        HandCardPickGeometry.Envelope capturerEnvelope,
        float capturerWidth,
        float capturerHeight,
        List<UUID> spawned
    ) {
        // 用同一个 CARD_TRACK_EPSILON_SQUARED：死区与 updatePrivateSelection 那条不一致
        // 会让牌动了捕获器没动而相对漂移。
        teleportIfMoved(
            capturer,
            handCardCapturerLocation(cardBaseLocation, capturerEnvelope, capturerHeight),
            CARD_TRACK_EPSILON_SQUARED
        );
        capturer.setInteractionWidth(capturerWidth);
        capturer.setInteractionHeight(capturerHeight);
        protectEntity(capturer, owner, ENTITY_ROLE_CARD_CAPTURER);
        spawned.add(capturer.getUniqueId());
        cardBindings.put(capturer.getUniqueId(),
            new CardBinding(table.getName(), playerId, card.id()));
        applyPrivateVisibility(playerId, capturer);
        return capturer.getUniqueId();
    }

    /**
     * 捕获器该摆在哪：与两态并集包络的中心对齐，再换算成 Interaction 的底边。
     *
     * <p>竖直中心取包络的 {@code centerVOffset}，<b>不是</b>牌当帧的抬升。包络是动画的不动点，
     * 捕获器跟着当帧抬升走就会和判定几何脱节：牌抬起时捕获器上移、判定区没动，
     * 中间那条缝里点得到事件却判不中，又是一圈死区。
     *
     * <p>并集包络同时盖住未选中与已选中两态（见 {@link #handCardCapturerEnvelope}），
     * 所以这个位置<b>与选中状态无关</b>——选牌时捕获器根本不需要动。
     * 这比「选中就 teleport」结实得多：点击与 teleport 之间隔着至少一个 tick，
     * 按状态搬盒子必然存在一帧窗口，盒子还在旧位置而判定区已经换了，那一帧的点击会丢。
     */
    private Location handCardCapturerLocation(
        Location cardBaseLocation,
        HandCardPickGeometry.Envelope capturerEnvelope,
        float capturerHeight
    ) {
        Location location = cardBaseLocation.clone();
        location.setY(handCardCapturerBottomY(
            cardBaseLocation.getY() + capturerEnvelope.centerVOffset(), capturerHeight));
        return location;
    }

    /**
     * 实机核一遍「捕获器不会挡住按钮」，不成立就在控制台喊一声。
     *
     * <p>为什么需要运行时检查而不是只靠测试：测试锁的是默认配置推出来的数字，
     * 而这些位置<b>全部可配</b>（{@code hand-center.distance}、{@code private-hand-offset.depth}、
     * {@code button-layout.*}）。服主把手牌往外挪或把按钮往里挪，捕获器就会插到玩家与按钮之间，
     * 客户端射线先命中捕获器，{@code handleActionButtonOnce} 判 false，
     * <b>按钮点击被静默丢弃</b>——没有异常、没有日志，只有玩家报「按钮点不动」。
     * 这类事故有先例（往椅子上放大判定框，结果既坐不上椅子又点不动按钮）。
     *
     * <p>判据取<b>深度序</b>而不是竖直余量：深度序是拓扑性质（按钮比手牌更靠近玩家 ⟹
     * 捕获器永远在按钮背后），比 0.076 格那道竖直余量结实得多。
     *
     * <p>只喊一次。这条路径在出牌链路上是高频的，每次铺牌都打日志会把控制台冲掉。
     */
    /**
     * 让一块边缘瓦片跟上端点牌的布局位置。
     *
     * <p>只搬位置不改尺寸：宽度只跟 hand-spacing 与包络有关，而那两者一变就会走
     * {@code renderPrivateHand} 整手重建（配置重载会重铺），不需要在这条每 tick 的路上改。
     *
     * <p>与捕获器共用 {@code CARD_TRACK_EPSILON_SQUARED}：死区不一致会让牌动了瓦片没动。
     *
     * @param tileId 瓦片实体 id，非端点牌为 null（此时直接返回）
     * @param signedOffset 带符号的偏移量，左端为负、右端为正
     */
    private void followEdgeTile(
        UUID tileId,
        Location anchorCardLocation,
        Vector along,
        double signedOffset,
        HandCardPickGeometry.Envelope capturerEnvelope,
        float capturerHeight
    ) {
        if (tileId == null || signedOffset == 0.0) {
            return;
        }
        if (!(Bukkit.getEntity(tileId) instanceof Interaction tile)) {
            // 瓦片被邻桌清场之类的路径删掉了。这里刻意不触发整手重建：
            // 缺一块瓦片只是两端边缘条点不到（回到修复前的状态），而整手重建会让
            // 整排牌闪烁。下一次正常铺牌（出牌、换手牌）会把它补回来。
            return;
        }
        Location target = handCardCapturerLocation(
            anchorCardLocation.clone().add(
                along.x() * signedOffset,
                0.0,
                along.z() * signedOffset
            ),
            capturerEnvelope,
            capturerHeight
        );
        teleportIfMoved(tile, target, CARD_TRACK_EPSILON_SQUARED);
    }

    private void warnIfCapturerCouldOccludeButtons(HandCardPickGeometry.Envelope capturerEnvelope) {
        if (capturerOcclusionWarned) {
            return;
        }
        // 离桌心越远越靠近玩家。手牌盒最靠外的那一面 = 手牌中心距 − 全局深度偏移
        // + 逐张错层极值 + 捕获器半宽（盒子在深度方向也有半宽，Interaction 横截面是正方形）。
        double handNearFace = plugin.getHandCenterDistance()
            - plugin.getGlobalPrivateHandDepthOffset()
            + Math.abs(plugin.getCardDepthOffset()) * 8.0
            + handCardCapturerWidth(plugin.getHandSpacing()) * 0.5;
        double buttonNearFace = unifiedActionDistance();
        if (buttonNearFace > handNearFace) {
            capturerOcclusionWarned = true;
            return;
        }
        capturerOcclusionWarned = true;
        plugin.getLogger().warning(
            "手牌点击捕获器可能挡住动作按钮：按钮离桌心 " + String.format(Locale.ROOT, "%.3f", buttonNearFace)
                + " 格，手牌盒最外面已到 " + String.format(Locale.ROOT, "%.3f", handNearFace)
                + " 格。按钮不再比手牌靠近玩家，客户端射线会先命中捕获器，"
                + "按钮点击将被静默丢弃（无报错、无提示）。"
                + "请调小 render.layout.hand-center.distance、调大 render.private-hand-offset.depth，"
                + "或调大 render.button-layout 的按钮距离。");
    }

    private void updatePrivateSelection(GameTable table, PlacedTable placed, UUID playerId) {
        // 为了避免右键选牌整排闪烁，这里只移动/发光已存在的实体，不整手重建
        Map<Integer, HandCardVisual> visuals = placed.privateVisualsByPlayer().get(playerId);
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (visuals == null || visuals.size() != hand.size()) {
            renderPrivateHand(table, placed, playerId);
            reindexPlacedTableEntities(normalize(placed.tableName()), placed);
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }
        Set<Integer> selected = table.getSelection(playerId);
        Integer hovered = hoveredCardIds.get(playerId);
        Vector step = privateHandStep(seatIndex, playerId);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = privateHandAdjustment(seatIndex, playerId);
        double startOffset = HandDealPresentation.centeredStartOffset(hand.size());
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        float animationStep = cardAnimationStep();
        float animationFallStep = Math.min(1.0f, animationStep * 1.8f);
        HandCardPickGeometry.Envelope[] pickEnvelopes = unifiedHandCardEnvelopes();
        HandCardPickGeometry.Envelope capturerEnvelope =
            handCardCapturerEnvelope(pickEnvelopes[0], pickEnvelopes[1]);
        float capturerHeight = (float) (capturerEnvelope.halfHeight() * 2.0);
        // 边缘瓦片也要跟着布局走。adjustPlayerHandOffset 允许玩家在运行时改
        // spacing/lateral，牌会被下面的 teleportIfMoved 挪到新位置，而张数没变、
        // visuals.size() == hand.size() 成立，不触发整手重建 —— 瓦片若没人管就留在原地，
        // 与牌相对漂移，两端缺口重新出现且位置还错了。
        double capturerWidth = handCardCapturerWidth(plugin.getHandSpacing());
        double edgeTileWidth = HandCardPickGeometry.edgeTileWidth(
            pickEnvelopes[0].halfWidth(), capturerWidth);
        double edgeTileOffset = HandCardPickGeometry.edgeTileCenterOffset(capturerWidth, edgeTileWidth);
        Vector edgeAlong = normalizeHorizontal(step);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            HandCardVisual visual = visuals.get(card.id());
            if (visual == null) {
                renderPrivateHand(table, placed, playerId);
                reindexPlacedTableEntities(normalize(placed.tableName()), placed);
                return;
            }
            boolean isSelected = selected.contains(card.id());
            boolean isHovered = hovered != null && hovered == card.id();
            boolean previewAnimated = isHovered && !isSelected;
            double delta = startOffset + index;
            Location cardBaseLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
                center.y() + adjustment.y(),
                center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
            );
            double lift = selectedCardLift(isSelected, previewAnimated);

            Entity cardEntity = Bukkit.getEntity(visual.cardDisplayId());
            Entity labelEntity = visual.labelId() == null ? null : Bukkit.getEntity(visual.labelId());
            if (!(cardEntity instanceof ItemDisplay cardDisplay)) {
                renderPrivateHand(table, placed, playerId);
                reindexPlacedTableEntities(normalize(placed.tableName()), placed);
                return;
            }
            // 捕获器缺失就整手重建，和牌本体缺失同口径：它是右键选牌唯一的事件入口，
            // 被邻桌清场之类的路径删掉后若不补回来，这手牌就再也点不动，而且毫无报错。
            Entity capturerEntity = visual.capturerId() == null
                ? null
                : Bukkit.getEntity(visual.capturerId());
            if (!(capturerEntity instanceof Interaction)) {
                renderPrivateHand(table, placed, playerId);
                reindexPlacedTableEntities(normalize(placed.tableName()), placed);
                return;
            }

            float selectedProgress = advanceAnimation(selectedProgressByPlayer, playerId, card.id(), isSelected, animationStep, animationFallStep);
            float hoverProgress = advanceAnimation(hoverProgressByPlayer, playerId, card.id(), previewAnimated, animationStep, animationFallStep);
            // 悬停不再产生任何位移：牌的实体位置只由布局决定，与 hoverProgress 无关。
            // 这是判定几何能当成动画不动点的前提——一旦这里再叠加悬停派生的平移，
            // pickHandCard 读到的牌面平面就会跟着悬停前后挪，抖动闭环立刻复活。
            double animatedLift = animatedCardLift(selectedProgress, hoverProgress);
            Vector3f animatedScale = privateCardScale(hoverProgress);
            float currentLift = cardDisplay.getTransformation().getTranslation().y;
            Vector3f currentScale = cardDisplay.getTransformation().getScale();
            boolean transformChanged = Math.abs(currentLift - animatedLift) >= 0.0001f
                || Math.abs(currentScale.x - animatedScale.x) >= 0.0001f
                || Math.abs(currentScale.y - animatedScale.y) >= 0.0001f
                || Math.abs(currentScale.z - animatedScale.z) >= 0.0001f;

            teleportIfMoved(cardEntity, cardBaseLocation, CARD_TRACK_EPSILON_SQUARED);
            // Keep the card yaw locked to the table layout.
            // Do not add hover/click rotation here: that old regression made cards visibly rotate and rebound on click.
            applyStableYaw(cardDisplay, cardYaw);
            if (transformChanged) {
                configureCardAnimation(cardDisplay);
                cardDisplay.setTransformation(cardTransformation(animatedScale, (float) animatedLift));
            }
            applyCardGlow(cardDisplay, playerId, isSelected, isHovered);
            // 捕获器只跟着牌的【布局位置】走，既不读当帧抬升也不读选中状态：
            // 包络是动画的不动点，而并集包络同时盖住两态（见 handCardCapturerEnvelope），
            // 所以选牌时它压根不需要动。这一步只为手牌张数变化后的重新铺排兜底。
            // 用同一个 CARD_TRACK_EPSILON_SQUARED：死区不一致会让牌动了捕获器没动而相对漂移。
            teleportIfMoved(
                capturerEntity,
                handCardCapturerLocation(cardBaseLocation, capturerEnvelope, capturerHeight),
                CARD_TRACK_EPSILON_SQUARED
            );
            // 边缘瓦片跟着端点牌的布局位置走，与捕获器同一个死区常量。
            // 只有端点牌挂了瓦片，其余牌两个槽都是 null，这两行是 no-op。
            followEdgeTile(visual.leftEdgeTileId(), cardBaseLocation, edgeAlong,
                -edgeTileOffset, capturerEnvelope, capturerHeight);
            followEdgeTile(visual.rightEdgeTileId(), cardBaseLocation, edgeAlong,
                edgeTileOffset, capturerEnvelope, capturerHeight);
            if (labelEntity != null) {
                teleportIfMoved(
                    labelEntity,
                    privateCardLabelLocation(cardBaseLocation, seatIndex, placed.yaw(), animatedLift),
                    CARD_TRACK_EPSILON_SQUARED
                );
            }
        }
    }

    private void updateBacksideSelection(GameTable table, PlacedTable placed, UUID playerId) {
        Map<Integer, UUID> visuals = placed.backsideVisualsByPlayer().get(playerId);
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (visuals == null || visuals.size() != hand.size()) {
            renderBacksideHand(table, placed, playerId);
            reindexPlacedTableEntities(normalize(placed.tableName()), placed);
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }

        Set<Integer> selected = table.getSelection(playerId);
        Vector step = handStep(seatIndex);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = globalHandAdjustment(seatIndex);
        double startOffset = HandDealPresentation.centeredStartOffset(hand.size());
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        float animationStep = cardAnimationStep();
        float animationFallStep = Math.min(1.0f, animationStep * 1.8f);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            UUID entityId = visuals.get(card.id());
            Entity entity = entityId == null ? null : Bukkit.getEntity(entityId);
            if (!(entity instanceof ItemDisplay cardDisplay)) {
                renderBacksideHand(table, placed, playerId);
                reindexPlacedTableEntities(normalize(placed.tableName()), placed);
                return;
            }
            boolean isSelected = selected.contains(card.id());
            double delta = startOffset + index;
            Location cardBaseLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
                center.y() + adjustment.y(),
                center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
            );
            float selectedProgress = advanceAnimation(selectedProgressByPlayer, playerId, card.id(), isSelected, animationStep, animationFallStep);
            double lift = animatedCardLift(selectedProgress, 0.0f);
            float currentLift = cardDisplay.getTransformation().getTranslation().y;
            teleportIfMoved(cardDisplay, cardBaseLocation);
            applyStableYaw(cardDisplay, cardYaw);
            if (Math.abs(currentLift - lift) >= 0.0001f) {
                configureCardAnimation(cardDisplay);
                cardDisplay.setTransformation(cardTransformation(privateCardScale(false, false), (float) lift));
            }
            applyCardGlow(cardDisplay, playerId, isSelected, false);
        }
    }

    private void clearPrivateEntities(PlacedTable placed, UUID playerId) {
        clearPrivateEntities(placed, playerId, Map.of(), Map.of());
    }

    /**
     * 收掉这位玩家的正面手牌实体。
     *
     * <p>这里刻意<b>不</b>清 {@code hoverProgressByPlayer} / {@code selectedProgressByPlayer}：
     * 清掉会让重建后的牌丢掉动画存量，悬停中的牌出生即满态、下一 tick 又从 0 起步，
     * 表现为「落下再长起来」（见 {@code renderPrivateHandCards} 里读存量进度那段）。
     * 不会泄漏：{@code advanceAnimation} 在进度归零时自己 remove，
     * {@code clearHover} 每 tick 兜 hover 那张表，玩家离桌与关服路径另有整表清理。
     *
     * @param keepCapturers 本次要复用的捕获器（牌 id → 实体），这些实体不销毁、
     *                      也不从 {@code cardBindings} 摘掉，理由见 {@code reusableHandCardCapturers}
     * @param keepEdgeTiles 本次要复用的边缘瓦片（端位 → 实体）。<b>必须一起算进 kept</b>：
     *                      漏掉就会被当成普通私有实体删掉，复用池里拿到的是已死实体，
     *                      {@code Bukkit.getEntity} 返回 null 于是重新 spawn ——
     *                      复用等于没做，换 entity id 的丢事件窗口照旧回来
     */
    private void clearPrivateEntities(
        PlacedTable placed,
        UUID playerId,
        Map<Integer, Interaction> keepCapturers,
        Map<HandEdge, Interaction> keepEdgeTiles
    ) {
        List<UUID> entities = placed.privateEntitiesByPlayer().remove(playerId);
        placed.privateVisualsByPlayer().remove(playerId);
        if (entities == null) {
            return;
        }
        if (keepCapturers.isEmpty() && keepEdgeTiles.isEmpty()) {
            clearOwnedEntities(placed.owner(), entities, false);
            return;
        }
        Set<UUID> kept = java.util.stream.Stream
            .concat(keepCapturers.values().stream(), keepEdgeTiles.values().stream())
            .map(Entity::getUniqueId)
            .collect(java.util.stream.Collectors.toSet());
            clearOwnedEntities(placed.owner(), entities.stream().filter(id -> !kept.contains(id)).toList(), false);
    }

    private void clearBacksideEntities(PlacedTable placed, UUID playerId) {
        List<UUID> entities = placed.backsideEntitiesByPlayer().remove(playerId);
        placed.backsideVisualsByPlayer().remove(playerId);
        selectedProgressByPlayer.remove(playerId);
        if (entities != null) {
            clearOwnedEntities(placed.owner(), entities, false);
        }
    }

    private void clearOwnedEntities(TableOwner owner, List<UUID> entityIds, boolean publicCards) {
        for (UUID entityId : entityIds) {
            actionBindings.remove(entityId);
            cardBindings.remove(entityId);
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null && ownedBy(entity, owner)) {
                entity.remove();
            }
        }
        if (publicCards) {
            entityIds.clear();
        }
    }

    /**
     * 拆桌的世界操作必须投递到桌锚点 region。
     *
     * <p>牌桌实体属于桌的 owner region，不是发起拆桌的玩家所在 region：两者可以不在同一个区域，
     * 在错误 region 上动实体会在 Folia 上直接非法。方向与麻将 {@code removeVisuals} 的收口一致。
     *
     * <p>异步提交，因此调用方不再等实体真正删除；失败必须记录日志，不能静默吞掉
     * （{@code runRegionStage} 只在 future 上完成异常，不抛回调用线程）。
     */
    private void cleanupPlacedTableOnOwnerRegion(PlacedTable placed) {
        runRegionStage(placed.anchor(), () -> {
            cleanupPlacedTable(placed);
            purgeResidualWorldArtifacts(placed.anchor(), placed.yaw());
        }).exceptionally(failure -> {
            plugin.getLogger().warning("拆桌清理失败: " + placed.tableName() + "，原因=" + failure.getMessage());
            return null;
        });
    }

    private void cleanupPlacedTable(PlacedTable placed) {
        cleanupPlacedTable(placed == null ? null : placed.owner(), placed);
    }

    private void cleanupPlacedTable(TableOwner owner, PlacedTable placed) {
        requireOwner(owner, placed);
        clearCraftEngineEntities(owner, placed.craftEngineVisualEntities());
        String tableKey = normalize(placed.tableName());
        actionSignatureByTable.remove(tableKey);
        privateHandSignatureByTable.remove(tableKey);
        backsideHandSignatureByTable.remove(tableKey);
        handDealPresentations.remove(tableKey);
        clearOwnedEntities(owner, placed.actionEntities(), false);
        for (UUID playerId : new ArrayList<>(placed.backsideEntitiesByPlayer().keySet())) {
            clearBacksideEntities(placed, playerId);
        }
        for (UUID playerId : new ArrayList<>(placed.privateEntitiesByPlayer().keySet())) {
            clearPrivateEntities(placed, playerId);
        }
        clearOwnedEntities(owner, placed.staticEntities(), false);
        restoreBlocks(placed.blockRestores());
    }

    static boolean matchesEntityOwner(
        TableOwner expected,
        String actualOwnerId,
        String actualOwnerName,
        String actualTableName,
        String actualRole,
        Long actualGeneration
    ) {
        if (expected == null
            || actualOwnerId == null
            || actualOwnerName == null
            || actualTableName == null
            || actualRole == null
            || actualRole.isBlank()
            || actualGeneration == null) {
            return false;
        }
        String expectedOwnerId = expected.id() == null ? "" : expected.id().toString();
        String expectedOwnerName = expected.name() == null ? "" : expected.name();
        return expectedOwnerId.equals(actualOwnerId)
            && expectedOwnerName.equals(actualOwnerName)
            && expected.tableName().equals(actualTableName)
            && expected.generation() == actualGeneration;
    }

    private boolean ownedBy(Entity entity, TableOwner owner) {
        if (entity == null || owner == null) {
            diagnoseOwnershipMismatch(entity, owner, null, null, null, null, null);
            return false;
        }
        var pdc = entity.getPersistentDataContainer();
        String actualOwnerId = pdc.get(entityOwnerKey, PersistentDataType.STRING);
        String actualOwnerName = pdc.get(entityOwnerNameKey, PersistentDataType.STRING);
        String actualTableName = pdc.get(entityTableKey, PersistentDataType.STRING);
        String actualRole = pdc.get(entityRoleKey, PersistentDataType.STRING);
        Long actualGeneration = pdc.get(entityGenerationKey, PersistentDataType.LONG);
        boolean matches = matchesEntityOwner(
            owner,
            actualOwnerId,
            actualOwnerName,
            actualTableName,
            actualRole,
            actualGeneration
        );
        if (!matches) {
            diagnoseOwnershipMismatch(
                entity,
                owner,
                actualOwnerId,
                actualOwnerName,
                actualTableName,
                actualRole,
                actualGeneration
            );
        }
        return matches;
    }

    private void diagnoseOwnershipMismatch(
        Entity entity,
        TableOwner expected,
        String actualOwnerId,
        String actualOwnerName,
        String actualTableName,
        String actualRole,
        Long actualGeneration
    ) {
        plugin.getLogger().warning(
            "跳过牌桌实体清理：owner PDC 不匹配"
                + " entity=" + (entity == null ? "null" : entity.getUniqueId())
                + " expectedTable=" + (expected == null ? "null" : expected.tableName())
                + " expectedGeneration=" + (expected == null ? "null" : expected.generation())
                + " actualTable=" + actualTableName
                + " actualGeneration=" + actualGeneration
                + " actualRole=" + actualRole
                + " actualOwnerId=" + actualOwnerId
                + " actualOwnerName=" + actualOwnerName
        );
    }

    private void requireOwner(TableOwner owner, PlacedTable placed) {
        if (placed == null) {
            return;
        }
        if (owner != null && placed.owner() != null && !Objects.equals(owner, placed.owner())) {
            throw new IllegalStateException("牌桌实体 owner 不匹配: " + placed.tableName());
        }
    }

    private void clearCraftEngineEntities(TableOwner owner, List<UUID> entityIds) {
        java.util.LinkedHashSet<UUID> uniqueIds = new java.util.LinkedHashSet<>(entityIds);
        for (UUID entityId : uniqueIds) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                plugin.getCraftEngineFurnitureService().removeFurniture(entity);
                forceRemoveEntityTree(entity);
            }
        }
        entityIds.clear();
    }

    private void forceRemoveEntityTree(Entity entity) {
        if (entity == null) {
            return;
        }
        for (Entity passenger : new ArrayList<>(entity.getPassengers())) {
            forceRemoveEntityTree(passenger);
        }
        if (entity.isValid()) {
            entity.remove();
        }
    }

    private void restoreBlocks(List<BlockRestore> blockRestores) {
        for (BlockRestore blockRestore : blockRestores) {
            blockRestore.originalState().update(true, false);
        }
        blockRestores.clear();
    }

    private void purgeResidualWorldArtifacts(Location anchor, float yaw) {
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        List<Location> hotspots = new ArrayList<>();
        Location tableLocation = previewTableCenter(anchor);
        hotspots.add(tableLocation);
        clearResidualPlacementBlock(blockPlacementLocation(tableLocation));
        for (int index = 0; index < 3; index++) {
            Location chairLocation = chairLocation(anchor, yaw, index);
            hotspots.add(chairLocation);
            clearResidualPlacementBlock(blockPlacementLocation(chairLocation));
            // 按钮位置也要扫。按钮离桌 2.1 格、椅子 3.1 格，两者相差 1.0 格，
            // 而清理半径只有 0.95——只扫桌面和椅子的话，升级前生成的按钮图标
            // 会永久留在世界里没人回收（图标已删，actionEntities 不再追踪它们）。
            hotspots.add(actionBase(anchor, yaw, index));
        }
        // 桌顶上方也要扫，理由和按钮图标那条完全一样：桌心悬浮头像已删除，
        // 生成代码没了、持久化又不存实体 id，升级前生成的那个 ItemDisplay
        // 只能靠坐标扫回收。而它悬在 anchor 上方约 3.9 格（旧默认 status-height 3.10
        // + status-avatar-offset.vertical 0.82），所有其他热点都在低处，
        // 垂直半径 1.6 根本够不到，不补这一条它会永久留在世界里。
        // 高度用当前 status-height 加旧偏移默认值：偏移的配置键已随头像一起退休，
        // 只有高度键还在，玩家调过高度的桌子也要能扫到。
        hotspots.add(rotate(anchor, yaw, 0.0, plugin.getStatusHeight() + RETIRED_STATUS_AVATAR_VERTICAL_OFFSET, 0.0));
        clearResidualEntities(hotspots, 0.95, 1.6);
    }

    private void clearResidualPlacementBlock(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        org.bukkit.block.Block block = snappedBlockLocation(location).getBlock();
        if (!block.getType().isAir()) {
            block.setType(Material.AIR, false);
        }
    }

    /**
     * 判断某个实体是否仍是某张牌桌在用的按钮部件
     * 清场按坐标扫，扫到的可能是邻桌的按钮。凡是还登记在 actionBindings
     * 或 cardBindings 里的，都说明有牌桌正在用它，不能删。
     * @param entityId 实体 id
     * @return 仍被追踪时返回 true
     */
    private boolean isTrackedActionEntity(UUID entityId) {
        // 手牌也得算进来。手牌离桌 1.62 格、按钮 2.01 格，只差 0.39 格，
        // 面对面摆放且桌间距 2.7~4.6 格时，A 的按钮热点会扫到 B 的手牌。
        if (actionBindings.containsKey(entityId) || cardBindings.containsKey(entityId)) {
            return true;
        }
        // bot 没有在线 Player，只生成给旁观者看的背面牌，不会进入 cardBindings。
        // 因此还要以牌桌自己的实体清单为准，否则 bot 桌依然会被邻桌清场误删。
        return isTrackedCardEntity(entityId);
    }

    /**
     * 判断某个实体是否是牌桌登记的牌实体（手牌或背面牌）
     * 牌按玩家分桶存在 privateEntitiesByPlayer / backsideEntitiesByPlayer，
     * 不进 staticEntities 也不进 actionEntities，判「是不是插件自己生成的」时必须单独看这两个桶。
     * @param entityId 实体 id
     * @return 属于任意牌桌的牌实体时返回 true
     */
    static boolean isTrackedEntity(Map<UUID, TableOwner> trackedOwners, UUID entityId) {
        return entityId != null && trackedOwners != null && trackedOwners.containsKey(entityId);
    }

    private boolean isTrackedCardEntity(UUID entityId) {
        return isTrackedEntity(trackedEntityOwnersById, entityId);
    }

    private void clearResidualEntities(List<Location> hotspots, double radiusXz, double radiusY) {
        java.util.LinkedHashSet<UUID> removed = new java.util.LinkedHashSet<>();
        for (Location hotspot : hotspots) {
            if (hotspot == null || hotspot.getWorld() == null) {
                continue;
            }
            for (Entity entity : hotspot.getWorld().getNearbyEntities(hotspot, radiusXz, radiusY, radiusXz)) {
                if (!removed.add(entity.getUniqueId())) {
                    continue;
                }
                if (entity instanceof Player) {
                    continue;
                }
                if (entity instanceof org.bukkit.entity.LivingEntity living && !(living instanceof org.bukkit.entity.ArmorStand)) {
                    continue;
                }
                // 邻桌正在用的按钮不能删。清场半径 0.95 加上弧线跨度，
                // 面对面摆放且桌间距 2.5~5.5 格时会波及隔壁桌的按钮，
                // 而 5 格间距是完全合理的摆法。本桌的实体此刻还没登记（清场在生成之前，
                // 重建路径也已先撤销旧映射），所以这个豁免只会保护别人。
                if (isTrackedActionEntity(entity.getUniqueId())) {
                    continue;
                }
                if (entity instanceof ItemDisplay || entity instanceof TextDisplay || entity instanceof Interaction) {
                    if (entity.getScoreboardTags().contains(PROTECTED_ENTITY_TAG)) {
                        plugin.getLogger().warning(
                            "跳过残留实体清理：MUZ 实体缺少当前牌桌 owner 匹配，避免误删邻桌实体: "
                                + entity.getUniqueId());
                        continue;
                    }
                    forceRemoveEntityTree(entity);
                    continue;
                }
                if (plugin.getCraftEngineFurnitureService().removeFurniture(entity)) {
                    forceRemoveEntityTree(entity);
                }
            }
        }
    }

    private void applyPrivateVisibility(UUID ownerId, Entity entity) {
        applyPrivateVisibility(ownerId, entity, false);
    }

    /**
     * 正面手牌的可见性。
     * @param revealed true 表示这家已明牌，正面牌对全场公开；false 时只有牌主自己能看到
     */
    private void applyPrivateVisibility(UUID ownerId, Entity entity, boolean revealed) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (UUID viewerId : onlinePlayerIdsSnapshot()) {
            if (revealed || viewerId.equals(ownerId)) {
                playerOutput.showEntity(viewerId, plugin, entity);
            } else {
                playerOutput.hideEntity(viewerId, plugin, entity);
            }
        }
    }

    private void deactivateEntities(List<UUID> entityIds) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (UUID entityId : entityIds) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity == null) {
                continue;
            }
            for (UUID viewerId : onlinePlayerIdsSnapshot()) {
                playerOutput.hideEntity(viewerId, plugin, entity);
            }
        }
    }

    private ItemDisplay spawnFurnitureDisplay(
        Location location,
        ItemStack item,
        float scale,
        TableOwner owner,
        String role
    ) {
        return VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
            ));
            protectEntity(spawned, owner, role);
        });
    }

    private ItemDisplay spawnPlacedCard(
        Location location,
        ItemStack item,
        Vector3f scale,
        float yaw,
        TableOwner owner
    ) {
        return spawnPlacedCard(location, item, scale, yaw, 0.0f, owner);
    }

    private ItemDisplay spawnPlacedCard(
        Location location,
        ItemStack item,
        Vector3f scale,
        float yaw,
        float lift,
        TableOwner owner
    ) {
        ItemDisplay display = VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            // 与开局槽位同样先隐藏，再由牌主/背面可见性路由放行，重建也不能先泄露牌面。
            spawned.setVisibleByDefault(false);
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(cardTransformation(scale, lift));
            configureCardAnimation(spawned);
            protectEntity(spawned, owner, ENTITY_ROLE_CARD);
        });
        applyStableYaw(display, yaw);
        return display;
    }

    private TextDisplay spawnText(
        Location location,
        Component text,
        Display.Billboard billboard,
        boolean background,
        float scale,
        boolean visibleByDefault,
        TableOwner owner,
        String role
    ) {
        return VersionCompat.spawnEntity(location.getWorld(), location, TextDisplay.class, spawned -> {
            // 私有牌点数标签与牌面同步，出生即隐藏，不能在后续 hide 前暴露一次。
            spawned.setVisibleByDefault(visibleByDefault);
            try {
                spawned.text(text);
            } catch (NoSuchMethodError e) {
                // 1.20.1 不支持 text() 方法，使用 setCustomName() 作为替代
                spawned.setCustomName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(text));
                spawned.setCustomNameVisible(true);
            }
            TypewriterTextStyle.apply(spawned, billboard, background, scale);
            protectEntity(spawned, owner, role);
        });
    }

    private void mountTextDisplay(Entity anchor, TextDisplay display, Location desiredLocation, boolean panel) {
        if (display == null || desiredLocation == null) {
            return;
        }
        teleportIfMoved(display, desiredLocation);
    }

    private void updateTextEntity(Entity entity, Component text) {
        if (entity instanceof TextDisplay display) {
            try {
                display.text(MuzTheme.plain(text).decoration(TextDecoration.ITALIC, false));
            } catch (NoSuchMethodError e) {
                // 1.20.1 不支持 text() 方法，使用 setCustomName() 作为替代
                display.setCustomName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(MuzTheme.plain(text).decoration(TextDecoration.ITALIC, false)));
                display.setCustomNameVisible(true);
            }
        }
    }

    private Interaction spawnInteraction(
        Location location,
        float width,
        float height,
        TableOwner owner,
        String role
    ) {
        return VersionCompat.spawnEntity(location.getWorld(), location, Interaction.class, spawned -> {
            spawned.setInteractionWidth(width);
            spawned.setInteractionHeight(height);
            spawned.setResponsive(true);
            protectEntity(spawned, owner, role);
        });
    }

    private void teleportIfMoved(Entity entity, Location target) {
        teleportIfMoved(entity, target, DEFAULT_TELEPORT_EPSILON_SQUARED);
    }

    private void teleportIfMoved(Entity entity, Location target, double epsilonSquared) {
        Location current = entity.getLocation();
        if (current.getWorld() == target.getWorld()
            && current.distanceSquared(target) < epsilonSquared) {
            return;
        }
        Location moved = target.clone();
        moved.setYaw(current.getYaw());
        moved.setPitch(current.getPitch());
        // IMPORTANT FOLIA: 区域线程里禁止同步传送，必须走 teleportAsync。
        // 实服验收证据：/muz bot add → refreshActionButtons → syncActionWidgets 链在一条 region
        // 线程上触发 UnsupportedOperationException("Must use teleportAsync while in region threading")。
        // teleportAsync 在 Paper 上同样可用，故两条后端共用这一条路径，不做平台分支。
        // 返回的 future 刻意不消费：这是「每 tick 校正一次」的幂等位置写入，下一次 refresh 会重新判定。
        entity.teleportAsync(moved);
    }

    /**
     * 把 Display 的 yaw 稳定地写到目标角度。
     *
     * <p>参数类型取 {@link Display} 而不是 ItemDisplay，是为了让判定区调试面板（TextDisplay）
     * 复用同一道死区闸门：面板走 FIXED 朝向，复用池里的旧实体时必须纠正 yaw，
     * 而 {@link #teleportIfMoved} 刻意保留原朝向、纠不了。
     */
    private void applyStableYaw(Display display, float targetYaw) {
        // IMPORTANT REGRESSION GUARD:
        // Never spam equivalent yaw writes every tick.
        // Repeated setRotation calls with near-identical angles can still make cards/buttons appear to twist and snap back.
        float normalizedTarget = normalizeYaw(targetYaw);
        float normalizedCurrent = normalizeYaw(display.getLocation().getYaw());
        float diff = Math.abs(normalizeYaw(normalizedTarget - normalizedCurrent));
        if (diff < 0.01f) {
            return;
        }
        display.setRotation(normalizedTarget, 0.0f);
    }

    private void protectEntity(Entity entity, TableOwner owner, String role) {
        TableEntityGeometry.protectEntity(entity, PROTECTED_ENTITY_TAG);
        writeEntityOwnership(entity, owner, role);
    }

    private void writeEntityOwnership(Entity entity, TableOwner owner, String role) {
        if (entity == null || owner == null) {
            return;
        }
        entity.getPersistentDataContainer().set(
            entityOwnerKey,
            PersistentDataType.STRING,
            owner.id() == null ? "" : owner.id().toString()
        );
        entity.getPersistentDataContainer().set(
            entityOwnerNameKey,
            PersistentDataType.STRING,
            owner.name() == null ? "" : owner.name()
        );
        entity.getPersistentDataContainer().set(entityTableKey, PersistentDataType.STRING, owner.tableName());
        entity.getPersistentDataContainer().set(
            entityRoleKey,
            PersistentDataType.STRING,
            role == null || role.isBlank() ? ENTITY_ROLE_UNKNOWN : role
        );
        entity.getPersistentDataContainer().set(entityGenerationKey, PersistentDataType.LONG, owner.generation());
    }

    private boolean matchesExpectedPlacedBlock(org.bukkit.block.Block block, PlacedTable placed) {
        Location tableBlock = snappedBlockLocation(blockPlacementLocation(previewTableCenter(placed.anchor())));
        if (sameBlock(block, tableBlock)) {
            return true;
        }
        for (int index = 0; index < 3; index++) {
            Location chairLocation = chairLocation(placed, index);
            if (sameBlock(block, snappedBlockLocation(blockPlacementLocation(chairLocation)))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesExpectedFurnitureEntity(Entity entity, PlacedTable placed) {
        if (entity == null || !isLikelyFurnitureEntity(entity)) {
            return false;
        }
        // HARD-CODED CE FURNITURE PROTECTION:
        // After restart, CraftEngine furniture entities can come back with ids/tags that MUZ did not track in memory.
        // We still protect them by checking whether a furniture-like entity is standing on the expected table/chair
        // positions for this placed table. Do not remove this fallback unless the user explicitly asks.
        Location location = entity.getLocation();
        Location tableLocation = previewTableCenter(placed.anchor());
        if (nearExpectedLocation(location, tableLocation, 1.10, 1.80)) {
            return true;
        }
        for (int index = 0; index < 3; index++) {
            Location chairLocation = chairLocation(placed, index);
            if (nearExpectedLocation(location, chairLocation, 0.85, 1.60)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyFurnitureEntity(Entity entity) {
        return entity != null && isFurnitureEntityClass(entity.getClass());
    }

    /**
     * 判断某个实体【类型】算不算家具，纯类型谓词，不碰任何 Bukkit 实例。
     *
     * <p>抽出来的唯一目的是让「家具那个布尔的取值来源」能真的跑起来测：
     * {@code isLikelyFurnitureEntity} 要 Bukkit 实体，本项目跑不起 Bukkit，
     * 原先整条类型规则只能靠源码扫描断言。写法沿用 {@code yieldsToBlockingEntity}。
     *
     * <p>行为与原先的 instanceof 链完全一致，只是把判据从实例挪到 Class 上，
     * 不改变任何调用点的结论。
     *
     * <p><b>Shulker 刻意不在这里</b>，这是防回归而非遗漏。牌桌家具的判定框在
     * furniture.yml 里配的是 {@code type: shulker}，但 CE 的 ShulkerFurnitureHitbox
     * 只有 {@code spawnPacket / despawnPacket / int[] entityIds} 三个字段
     * （craft-engine-bukkit 0.0.67 与 26.7.4 字节码均已核对），它是靠发包在客户端造出来的
     * 伪实体，服务端不存在对应的 Bukkit 实体，所以 <b>不可能</b> 有 Shulker 作为
     * blocking 实体走到这条谓词上。桌子那一路的点击由 CE 自己 fire 的
     * FurnitureInteractEvent / FurnitureHitEvent 送来，传进仲裁的是家具基座
     * {@code BukkitFurniture.bukkitEntity()}，其类型是 ItemDisplay，本谓词认它。
     *
     * <p>反过来把 Shulker 加进来会引入一个真实回归：野生潜影贝晃到手牌那条带上时会被
     * 判成家具，左键攻击被仲裁接手、取消掉，还弹一句「请先右键选择要出的牌」。
     *
     * @param entityClass 待判定的实体类型，null 视为非家具
     * @return 是家具类型时返回 true
     */
    static boolean isFurnitureEntityClass(Class<?> entityClass) {
        return entityClass != null
            && (Display.class.isAssignableFrom(entityClass)
                || Interaction.class.isAssignableFrom(entityClass)
                || org.bukkit.entity.ArmorStand.class.isAssignableFrom(entityClass)
                || org.bukkit.entity.Hanging.class.isAssignableFrom(entityClass));
    }

    private boolean nearExpectedLocation(Location current, Location expected, double radiusXz, double radiusY) {
        if (current == null || expected == null || current.getWorld() == null || expected.getWorld() == null) {
            return false;
        }
        if (!current.getWorld().equals(expected.getWorld())) {
            return false;
        }
        double dx = current.getX() - expected.getX();
        double dz = current.getZ() - expected.getZ();
        double dy = Math.abs(current.getY() - expected.getY());
        return (dx * dx + dz * dz) <= (radiusXz * radiusXz) && dy <= radiusY;
    }

    private boolean sameBlock(org.bukkit.block.Block block, Location location) {
        return location != null
            && location.getWorld() != null
            && location.getWorld().equals(block.getWorld())
            && location.getBlockX() == block.getX()
            && location.getBlockY() == block.getY()
            && location.getBlockZ() == block.getZ();
    }

    private void addEntityTreeIds(UUID rootId, List<UUID> target, TableOwner owner, String role) {
        Entity root = Bukkit.getEntity(rootId);
        if (root == null) {
            if (!target.contains(rootId)) {
                target.add(rootId);
            }
            return;
        }
        protectEntityTree(root, owner, role);
        collectEntityTreeIds(root, target);
    }

    /**
     * 只登记实体树 UUID，不改变实体持久化属性。
     *
     * <p>CraftEngine 家具必须走这条路径：CE 需要通过持久化状态接管区块卸载/加载，
     * 否则旧家具映射会在区块返回后留下失效的 BukkitEntity。</p>
     *
     * @param rootId 实体树根 UUID
     * @param target 接收实体 UUID 的列表
     */
    private void collectEntityTreeIds(UUID rootId, List<UUID> target) {
        Entity root = Bukkit.getEntity(rootId);
        if (root == null) {
            if (!target.contains(rootId)) {
                target.add(rootId);
            }
            return;
        }
        collectEntityTreeIds(root, target);
    }

    private void collectEntityTreeIds(Entity entity, List<UUID> target) {
        if (entity == null) {
            return;
        }
        if (!target.contains(entity.getUniqueId())) {
            target.add(entity.getUniqueId());
        }
        for (Entity passenger : entity.getPassengers()) {
            collectEntityTreeIds(passenger, target);
        }
    }

    private void protectEntityTree(Entity entity, TableOwner owner, String role) {
        if (entity == null) {
            return;
        }
        protectEntity(entity, owner, role);
        for (Entity passenger : entity.getPassengers()) {
            protectEntityTree(passenger, owner, role);
        }
    }

    private void configureDisplayAnimation(Display display) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(3);
        try {
            display.setTeleportDuration(0);
        } catch (NoSuchMethodError e) {
            // 1.20.1 不支持 setTeleportDuration，忽略
        }
    }

    private void configureCardAnimation(Display display) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(Math.clamp(plugin.getCardHoverInterpolationTicks() / 2, 2, 4));
        // IMPORTANT REGRESSION GUARD:
        // Card teleports must not interpolate, otherwise click/hover refreshes can look like a rotate-and-rebound bug.
        try {
            display.setTeleportDuration(0);
        } catch (NoSuchMethodError e) {
            // 1.20.1 不支持 setTeleportDuration，忽略
        }
    }

    private ItemStack tableItem() {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.TABLE);
        if (configured != null) {
            configured.setAmount(1);
            return configured;
        }
        NamespacedKey model = configuredModelKey(
            plugin.getTableItemModelId(),
            PackAssets.furnitureModel(plugin, DoudizhuPlugin.tableFurnitureId())
        );
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        VersionCompat.setItemModel(meta, model);
        meta.displayName(message(plugin.getTableDisplayName(), NamedTextColor.GOLD));
        item.setItemMeta(meta);
        return item;
    }

    private TablePlacement spawnTableVisual(Location location, float yaw) {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.TABLE);
        Location target = location.clone();
        target.setYaw(yaw);
        for (String itemId : plugin.getTableFurnitureItemIdCandidates()) {
            linmumua.doudizhu.compat.CraftEngineFurnitureService.PlacementKind kind =
                plugin.getCraftEngineFurnitureService().detectPlacementKind(itemId);
            if (kind == linmumua.doudizhu.compat.CraftEngineFurnitureService.PlacementKind.FURNITURE) {
                Entity furniture = plugin.getCraftEngineFurnitureService().placeFurniture(target, itemId);
                if (furniture != null) {
                    return TablePlacement.furniture(furniture.getUniqueId(), true);
                }
            } else if (kind == linmumua.doudizhu.compat.CraftEngineFurnitureService.PlacementKind.BLOCK) {
                Location blockLocation = blockPlacementLocation(location);
                BlockRestore restore = captureBlockRestore(blockLocation);
                Location snappedBlockLocation = snappedBlockLocation(blockLocation);
                String orientedState = orientedCraftEngineBlockState(itemId, yaw);
                if (plugin.getCraftEngineFurnitureService().placeBlockWithState(snappedBlockLocation, orientedState)) {
                    return TablePlacement.block(restore);
                }
            }
        }
        if (configured != null && configured.getType().isBlock()) {
            Location blockLocation = blockPlacementLocation(location);
            BlockRestore restore = captureBlockRestore(blockLocation);
            Location snappedBlockLocation = snappedBlockLocation(blockLocation);
            snappedBlockLocation.getBlock().setType(configured.getType(), false);
            orientVanillaBlockToYaw(snappedBlockLocation, yaw);
            return TablePlacement.block(restore);
        }
        return TablePlacement.none();
    }

    private ChairPlacement spawnChairVisual(Location location, float yaw) {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.CHAIR);
        Location target = location.clone();
        target.setYaw(yaw);
        for (String itemId : plugin.getChairFurnitureItemIdCandidates()) {
            linmumua.doudizhu.compat.CraftEngineFurnitureService.PlacementKind kind =
                plugin.getCraftEngineFurnitureService().detectPlacementKind(itemId);
            if (kind == linmumua.doudizhu.compat.CraftEngineFurnitureService.PlacementKind.FURNITURE) {
                Entity furniture = plugin.getCraftEngineFurnitureService().placeFurniture(target, itemId);
                if (furniture != null) {
                    return ChairPlacement.furniture(furniture.getUniqueId(), location.clone(), true);
                }
            } else if (kind == linmumua.doudizhu.compat.CraftEngineFurnitureService.PlacementKind.BLOCK) {
                Location blockLocation = blockPlacementLocation(location);
                BlockRestore restore = captureBlockRestore(blockLocation);
                Location snappedBlockLocation = snappedBlockLocation(blockLocation);
                String orientedState = orientedCraftEngineBlockState(itemId, yaw);
                if (plugin.getCraftEngineFurnitureService().placeBlockWithState(snappedBlockLocation, orientedState)) {
                    return ChairPlacement.block(snappedBlockCenter(blockLocation), restore);
                }
            }
        }
        if (configured != null && configured.getType().isBlock()) {
            Location blockLocation = blockPlacementLocation(location);
            BlockRestore restore = captureBlockRestore(blockLocation);
            Location snappedBlockLocation = snappedBlockLocation(blockLocation);
            snappedBlockLocation.getBlock().setType(configured.getType(), false);
            orientVanillaBlockToYaw(snappedBlockLocation, yaw);
            return ChairPlacement.block(snappedBlockCenter(blockLocation), restore);
        }
        return ChairPlacement.none(location.clone());
    }

    private String orientedCraftEngineBlockState(String itemId, float yaw) {
        String normalized = itemId == null ? "" : itemId.trim();
        if (normalized.isEmpty() || normalized.contains("[")) {
            return normalized;
        }
        BlockFace facing = yawToBlockFace(yaw);
        return normalized + "[facing=" + facing.name().toLowerCase(Locale.ROOT) + "]";
    }

    private void orientVanillaBlockToYaw(Location location, float yaw) {
        BlockData data = location.getBlock().getBlockData();
        BlockFace facing = yawToBlockFace(yaw);
        boolean changed = false;
        if (data instanceof Directional directional && directional.getFaces().contains(facing)) {
            directional.setFacing(facing);
            changed = true;
        } else if (data instanceof Rotatable rotatable) {
            rotatable.setRotation(facing);
            changed = true;
        }
        if (changed) {
            location.getBlock().setBlockData(data, false);
        }
    }

    private BlockFace yawToBlockFace(float yaw) {
        float normalized = yaw % 360.0f;
        if (normalized < 0.0f) {
            normalized += 360.0f;
        }
        if (normalized >= 315.0f || normalized < 45.0f) {
            return BlockFace.SOUTH;
        }
        if (normalized < 135.0f) {
            return BlockFace.WEST;
        }
        if (normalized < 225.0f) {
            return BlockFace.NORTH;
        }
        return BlockFace.EAST;
    }

    private BlockRestore captureBlockRestore(Location location) {
        return new BlockRestore(snappedBlockLocation(location).getBlock().getState());
    }

    /**
     * 方块型家具真正占用的那一格。
     *
     * 以前这里要额外 +1.0，用来补锚点埋在支撑方块里的那一格——方块型家具因此落对了位置，
     * 而家具型和 ItemDisplay 走的是未补偿的原坐标，于是只有它们陷在地里。
     * 现在桌椅几何已经统一以上表面为基准，这里不再补偿，方块型的落点保持不变。
     */
    private Location blockPlacementLocation(Location location) {
        return location.clone();
    }

    private Location snappedBlockLocation(Location location) {
        return location.getBlock().getLocation();
    }

    private Location snappedBlockCenter(Location location) {
        Location base = snappedBlockLocation(location);
        return base.add(0.5, 0.0, 0.5);
    }

    private TableOwner tableOwner(String tableName, UUID ownerId, String ownerName) {
        String normalized = normalize(tableName);
        long current = tableGenerationByName.getOrDefault(normalized, 0L);
        if (current == Long.MAX_VALUE) {
            throw new IllegalStateException("牌桌实体 owner 代次已耗尽: " + tableName);
        }
        long generation = current + 1L;
        tableGenerationByName.put(normalized, generation);
        return new TableOwner(ownerId, ownerName, normalized, generation);
    }

    private PlacedTable placedTable(String tableName) {
        if (tableName == null) {
            return null;
        }
        String normalized = normalize(tableName);
        PlacedTable placed = placedTables.get(normalized);
        if (placed != null) {
            return placed;
        }
        for (PlacedTable candidate : placedTables.values()) {
            if (candidate.tableName().equalsIgnoreCase(tableName.trim())) {
                return candidate;
            }
        }
        return null;
    }

    private PlacedTable removePlacedTable(String tableName) {
        if (tableName == null) {
            return null;
        }
        String normalized = normalize(tableName);
        PlacedTable removed = placedTables.remove(normalized);
        if (removed != null) {
            unindexPlacedTable(normalized, removed);
            playDetailLastRefreshBucketByTable.remove(normalized);
            plugin.getTableManager().markTableUnplaced(removed.tableName());
            return removed;
        }
        for (Map.Entry<String, PlacedTable> entry : new ArrayList<>(placedTables.entrySet())) {
            if (entry.getValue().tableName().equalsIgnoreCase(tableName.trim())) {
                placedTables.remove(entry.getKey());
                unindexPlacedTable(entry.getKey(), entry.getValue());
                playDetailLastRefreshBucketByTable.remove(entry.getKey());
                plugin.getTableManager().markTableUnplaced(entry.getValue().tableName());
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 只允许按捕获时的对象身份摘除牌桌；身份变化必须显式失败，不能静默摘错桌或吞掉失败。
     */
    private void removePlacedTableIfSame(String tableName, PlacedTable expected) {
        String normalized = normalize(tableName);
        if (normalized == null || expected == null) {
            throw new IllegalStateException("区块加载修复缺少放置身份: " + tableName);
        }
        PlacedTable current = placedTables.get(normalized);
        if (current != expected) {
            throw new IllegalStateException("区块加载修复放置身份不匹配，不摘除牌桌: " + tableName);
        }
        if (!placedTables.remove(normalized, expected)) {
            throw new IllegalStateException("区块加载修复摘除牌桌失败，放置身份已变化: " + tableName);
        }
        unindexPlacedTable(normalized, expected);
        playDetailLastRefreshBucketByTable.remove(normalized);
        plugin.getTableManager().markTableUnplaced(expected.tableName());
    }

    private NamespacedKey configuredModelKey(String itemModelId, NamespacedKey fallback) {
        if (itemModelId == null || itemModelId.isBlank()) {
            return fallback;
        }
        NamespacedKey configured = NamespacedKey.fromString(itemModelId.trim());
        return configured == null ? fallback : configured;
    }

    private ItemStack cardItem(DoudizhuCard card) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        VersionCompat.setItemModel(meta, PackAssets.cardModel(plugin, card));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backCardItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        VersionCompat.setItemModel(meta, PackAssets.backModel(plugin));
        item.setItemMeta(meta);
        return item;
    }

    private Vector globalHandAdjustment(int seatIndex) {
        double lateral = plugin.getGlobalPrivateHandLateralOffset();
        double vertical = plugin.getGlobalPrivateHandVerticalOffset();
        double depth = plugin.getGlobalPrivateHandDepthOffset();
        Vector lateralAxis = normalizeHorizontal(handStep(seatIndex));
        Vector depthAxis = towardTableAxis(seatIndex);
        return new Vector(
            lateralAxis.x() * lateral + depthAxis.x() * depth,
            vertical,
            lateralAxis.z() * lateral + depthAxis.z() * depth
        );
    }

    private Vector privateHandAdjustment(int seatIndex, UUID playerId) {
        Vector global = globalHandAdjustment(seatIndex);
        double lateral = plugin.getPlayerHandLateralOffset(playerId);
        double vertical = plugin.getPlayerHandVerticalOffset(playerId);
        double depth = plugin.getPlayerHandDepthOffset(playerId);
        Vector lateralAxis = normalizeHorizontal(handStep(seatIndex));
        Vector depthAxis = towardTableAxis(seatIndex);
        return new Vector(
            global.x() + lateralAxis.x() * lateral + depthAxis.x() * depth,
            global.y() + vertical,
            global.z() + lateralAxis.z() * lateral + depthAxis.z() * depth
        );
    }

    private Vector privateHandStep(int seatIndex, UUID playerId) {
        double spacing = Math.max(0.02, plugin.getHandSpacing());
        return switch (seatIndex) {
            case 0 -> new Vector(spacing, 0.0, 0.0);
            case 1 -> new Vector(0.0, 0.0, -spacing);
            default -> new Vector(0.0, 0.0, spacing);
        };
    }

    private Vector actionArcOffset(float tableYaw, int seatIndex, double offset, double maxOffset, int buttonCount) {
        double normalized = maxOffset <= 0.0001 ? 0.0 : offset / maxOffset;
        double maxAngle = Math.toRadians(buttonCount >= 4 ? plugin.getButtonArcLargeAngleDegrees() : plugin.getButtonArcSmallAngleDegrees());
        double radius = buttonCount >= 4 ? plugin.getButtonArcLargeRadius() : plugin.getButtonArcSmallRadius();
        double angle = normalized * maxAngle * plugin.getButtonSpacingScale();
        double lateral = Math.sin(angle) * radius;
        double depth = radius * (1.0 - Math.cos(maxAngle)) * 0.22;
        Vector lateralAxis = rotateVector(normalizeHorizontal(actionStep(seatIndex)), tableYaw);
        Vector depthAxis = rotateVector(towardTableAxis(seatIndex), tableYaw);
        return new Vector(
            lateralAxis.x() * lateral + depthAxis.x() * depth,
            0.0,
            lateralAxis.z() * lateral + depthAxis.z() * depth
        );
    }

    /**
     * 椅子视觉微调量。
     * 竖直分量里带上 SUPPORT_SURFACE_LIFT：全部 19 处椅子坐标都经过这里，
     * 在这一处抬升就能让椅子和预览粒子统一落到支撑方块上表面，
     * 不必在每个调用点各加一次（漏一处就会出现椅子陷地）。
     */
    private Vector chairVisualAdjustment(int seatIndex) {
        Vector lateralAxis = normalizeHorizontal(actionStep(seatIndex));
        return new Vector(
            lateralAxis.x() * plugin.getChairVisualLateralOffset(),
            plugin.getChairVisualVerticalOffset() + SUPPORT_SURFACE_LIFT,
            lateralAxis.z() * plugin.getChairVisualLateralOffset()
        );
    }

    private Vector chairHitboxAdjustment(int seatIndex) {
        return chairHitboxAdjustment(
            normalizeHorizontal(actionStep(seatIndex)),
            plugin.getChairHitboxLateralOffset(),
            plugin.getChairHitboxVerticalOffset()
        );
    }

    /**
     * 计算椅子判定框相对座位基准点的偏移
     * @param lateralAxis 已归一化的座位横向轴
     * @param lateralOffset 横向偏移配置
     * @param verticalOffset 垂直偏移配置
     * @return 偏移向量
     */
    static Vector chairHitboxAdjustment(Vector lateralAxis, double lateralOffset, double verticalOffset) {
        return new Vector(
            lateralAxis.x() * lateralOffset,
            verticalOffset,
            lateralAxis.z() * lateralOffset
        );
    }

    private Vector buttonHitboxAdjustment(float tableYaw, int seatIndex) {
        Vector lateralAxis = rotateVector(normalizeHorizontal(actionStep(seatIndex)), tableYaw);
        Vector depthAxis = rotateVector(towardTableAxis(seatIndex), tableYaw);
        return new Vector(
            lateralAxis.x() * plugin.getButtonHitboxLateralOffset() + depthAxis.x() * plugin.getButtonHitboxDepthOffset(),
            plugin.getButtonHitboxVerticalOffset(),
            lateralAxis.z() * plugin.getButtonHitboxLateralOffset() + depthAxis.z() * plugin.getButtonHitboxDepthOffset()
        );
    }

    /**
     * 每个阶段的默认按钮布局。
     * 渲染和签名两处都读这里，避免两边写重复的按钮表而漏改一处导致按钮不重建。
     */
    private List<ActionButtonState> phaseButtonStates(GamePhase phase) {
        return switch (phase) {
            case BIDDING -> BIDDING_BUTTONS_ONLY;
            case REVEALING -> List.of();
            case DEALING -> List.of();
            case DOUBLING -> List.of(
                new ActionButtonState("pass", "不加倍", ButtonAction.DOUBLE_NO, -0.40),
                new ActionButtonState("ready", "加倍", ButtonAction.DOUBLE_YES, 0.40)
            );
            case PLAYING -> List.of(
                // 桌上「道具」按钮已删除：道具改由物品栏直接使用（默认鸡蛋/水桶/番茄 + 第九格聊天气泡）。
                new ActionButtonState("inspect", "提示", ButtonAction.HINT_PLAY, -0.60),
                new ActionButtonState("pass", "不要", ButtonAction.PASS_TURN, 0.0),
                new ActionButtonState("refresh", "清选", ButtonAction.CLEAR_SELECTION, 0.60)
            );
            case LOBBY -> List.of(
                new ActionButtonState("ready", "准备", ButtonAction.READY, -0.64),
                new ActionButtonState("start", "开始", ButtonAction.START, 0.00),
                new ActionButtonState("leave", "离开", ButtonAction.LEAVE, 0.64)
            );
        };
    }

    private List<ActionButtonState> actionStatesForSeat(GameTable table, UUID owner, List<ActionButtonState> phaseStates) {
        if (table.getPhase() == GamePhase.LOBBY && owner != null) {
            return List.of(
                new ActionButtonState("ready", table.isReady(owner) ? "取消准备" : "准备", ButtonAction.READY, -0.64),
                new ActionButtonState("start", "开始", ButtonAction.START, 0.00),
                new ActionButtonState("leave", "离开", ButtonAction.LEAVE, 0.64)
            );
        }
        if (table.getPhase() == GamePhase.BIDDING) {
            if (owner == null || !owner.equals(table.getCurrentTurn())) {
                return List.of();
            }
            return phaseStates;
        }
        if (table.getPhase() == GamePhase.REVEALING) {
            if (owner == null || table.isBot(owner) || !table.canRevealHand(owner)) {
                return List.of();
            }
            return List.of(new ActionButtonState(
                "inspect", table.openingRevealButtonLabel(), ButtonAction.REVEAL_HAND, 0.0));
        }
        if (table.getPhase() == GamePhase.DEALING) {
            return List.of();
        }
        if (table.getPhase() == GamePhase.DOUBLING) {
            if (owner == null || !owner.equals(table.getCurrentTurn())) {
                return List.of();
            }
            return phaseStates;
        }
        if (table.getPhase() != GamePhase.PLAYING || owner == null) {
            return phaseStates;
        }
        if (!owner.equals(table.getCurrentTurn())) {
            return List.of();
        }
        boolean canPass = table.getLeadPlayer() != null && !owner.equals(table.getLeadPlayer());
        return canPass
            ? phaseStates
            : List.of(
                new ActionButtonState("inspect", "提示", ButtonAction.HINT_PLAY, -0.56),
                new ActionButtonState("refresh", "清选", ButtonAction.CLEAR_SELECTION, 0.56)
            );
    }

    private Component buildStatus(GameTable table) {
        // Keep the status text head-free.
        // The top avatar/name pair already shows the focused player, so the banner should only describe the phase and state.
        Component top = MuzTheme.accent("斗地主")
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.multiplierWarm(table.getName() + " 号桌"));
        Component middle = TypewriterTextStyle.joinInline(
            MuzTheme.warm(plugin.roomDisplayTag(table.getRoomLevel())),
            MuzTheme.accent(table.getPhase().displayName()),
            currentTurnStatusBanner(table)
        );
        Component detail = TypewriterTextStyle.joinInline(
            table.getBottomCards().isEmpty() ? null : MuzTheme.warm("底牌 " + table.getBottomCards().size() + " 张"),
            table.getLandlord() == null ? null : MuzTheme.landlord("地主"),
            table.getLandlord() == null ? null : plugin.playerNameComponent(table.getLandlord(), table.displayName(table.getLandlord()), NamedTextColor.WHITE)
        );
        return TypewriterTextStyle.joinLines(top, middle, detail, table.currentMultiplierBannerComponent());
    }

    private Component buildPlayDetail(GameTable table) {
        List<Component> lines = new ArrayList<>();
        lines.add(MuzTheme.warning("桌边动态"));
        if (table.getPhase() == GamePhase.LOBBY) {
            lines.addAll(table.recentLobbyPreviewComponents());
        } else {
            if (table.getPhase() == GamePhase.PLAYING) {
                lines.addAll(table.slidingTrickPreviewComponents(System.currentTimeMillis()));
                lines.add(MINI.deserialize("<!i><#8fc7da>右键选择</#8fc7da><dark_gray>｜</dark_gray><#ffd670><bold>左键出牌</bold></#ffd670>"));
            } else {
                lines.add(table.lastActionComponent());
            }
        }
        return TypewriterTextStyle.joinLines(lines.toArray(Component[]::new));
    }

    private Component append(Component... components) {
        return MuzTheme.concat(components);
    }

    private void applyHint(GameTable table, Player player) {
        // “提示”按钮每按一次就切到下一组建议牌
        List<List<DoudizhuCard>> hints = buildHints(table, player.getUniqueId());
        if (hints.isEmpty()) {
            hint(player.getUniqueId(), "这手没什么能出的。", NamedTextColor.GRAY);
            return;
        }
        int index = hintIndices.getOrDefault(player.getUniqueId(), 0) % hints.size();
        table.replaceSelection(player.getUniqueId(), hints.get(index));
        hintIndices.put(player.getUniqueId(), index + 1);
        refreshPrivateHand(table, player.getUniqueId());
        hint(player.getUniqueId(), "提示第 " + (index + 1) + " 组，可再次点击切换。", NamedTextColor.YELLOW);
    }

    private List<List<DoudizhuCard>> buildHints(GameTable table, UUID playerId) {
        List<DoudizhuCard> hand = new ArrayList<>(table.getHand(playerId));
        hand.sort(DoudizhuCard.ORDER);
        List<List<DoudizhuCard>> hints = new ArrayList<>();
        List<DoudizhuCard> best = SimpleBotBrain.choosePlay(
            hand,
            table.getLeadPlayer() != null && !table.getLeadPlayer().equals(playerId) ? table.getCurrentPattern() : null
        );
        if (!best.isEmpty()) {
            hints.add(best);
        }
        for (DoudizhuCard card : hand) {
            List<DoudizhuCard> single = List.of(card);
            if (table.getCurrentPattern() != null && table.getLeadPlayer() != null && !table.getLeadPlayer().equals(playerId)) {
                boolean legal = linmumua.doudizhu.model.PatternAnalyzer.analyze(single)
                    .map(pattern -> pattern.canBeat(table.getCurrentPattern()))
                    .orElse(false);
                if (!legal) {
                    continue;
                }
            }
            if (hints.stream().noneMatch(existing -> sameCards(existing, single))) {
                hints.add(single);
            }
            if (hints.size() >= 6) {
                break;
            }
        }
        return hints;
    }

    private boolean sameCards(List<DoudizhuCard> left, List<DoudizhuCard> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index).id() != right.get(index).id()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 牌面数字标签的判定核心，抽成 static 纯函数以便单测直接断言。
     *
     * 三层开关的优先级：总开关（labelsEnabled）是硬否决，关掉则一张都不标；
     * 玩家个人开关在调用方 {@link #shouldShowPrivateLabel} 里先过一遍；
     * duplicateOnly 是最后一层筛子，只放过点数重复的牌。
     *
     * rankCounts 允许为 null 或缺键（统计表尚未建好、或牌不在统计范围内），
     * 这两种情况都当作「不重复」处理，绝不抛 NPE。
     */
    static boolean shouldLabelRank(
        boolean labelsEnabled,
        boolean duplicateOnly,
        CardRank rank,
        Map<CardRank, Integer> rankCounts
    ) {
        if (!labelsEnabled) {
            return false;
        }
        if (!duplicateOnly) {
            return true;
        }
        if (rank == null || rankCounts == null) {
            return false;
        }
        return rankCounts.getOrDefault(rank, 0) > 1;
    }

    private boolean shouldShowLabel(DoudizhuCard card, Map<CardRank, Integer> rankCounts) {
        return shouldLabelRank(
            plugin.isCardHologramLabelsEnabled(),
            plugin.isDuplicateOnlyCardLabels(),
            card == null ? null : card.rank(),
            rankCounts
        );
    }

    private boolean shouldShowPrivateLabel(UUID playerId, DoudizhuCard card, Map<CardRank, Integer> rankCounts) {
        if (!plugin.isCardLabelsEnabledFor(playerId)) {
            return false;
        }
        return shouldShowLabel(card, rankCounts);
    }

    private Map<CardRank, Integer> countRanks(List<DoudizhuCard> cards) {
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        for (DoudizhuCard card : cards) {
            counts.merge(card.rank(), 1, Integer::sum);
        }
        return counts;
    }

    private Component seatName(GameTable table, int seatIndex) {
        UUID seat = placedSeat(table, seatIndex);
        if (seat == null) {
            return TypewriterTextStyle.warning("空位");
        }
        NamedTextColor color = table.isBot(seat) ? NamedTextColor.AQUA : NamedTextColor.WHITE;
        Component name = plugin.playerNameComponent(seat, table.displayName(seat), color)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false);
        if (!table.isBot(seat)) {
            return name;
        }
        // 机器人没有皮肤，在名字前面拼一个位图字体图标当头像。
        // 角色已定时取描边版：地主金边、农民黑边。
        return botAvatarIcon(table.getRole(seat)).append(name);
    }

    /**
     * 机器人头像图标。
     * <p>
     * 图标是 CraftEngine images 注册的位图字形，本质上仍是一个文本字符，
     * 会被外层 Component 的颜色染色 —— 如果不显式指定颜色，它就会继承
     * 机器人名字的 AQUA，整个图标被染成青色，原图的配色全部丢失。
     * <p>
     * 这里显式设 WHITE 而不是用 reset：WHITE 是明确的白色染色，
     * 位图字形按白色渲染即等于保留贴图原色；reset 只清样式，
     * 在某些客户端上仍可能落回父节点的颜色。
     * <p>
     * 同时关掉粗体和斜体：名字带 BOLD，如果图标跟着变粗，
     * 客户端会把字形横向拉伸一像素，图标看起来会糊。
     */
    private Component botAvatarIcon(PlayerRole role) {
        return PackAssets.botAvatarIcon(role, true);
    }

    private Component seatInfo(GameTable table, int seatIndex) {
        UUID seat = placedSeat(table, seatIndex);
        if (seat == null) {
            return TypewriterTextStyle.meta("座位 " + (seatIndex + 1));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(TypewriterTextStyle.meta("座位 " + (seatIndex + 1)));
        lines.add(TypewriterTextStyle.meta(table.isBot(seat) ? "机器人" : "玩家"));
        if (table.isReady(seat)) {
            lines.add(TypewriterTextStyle.success("已准备"));
        } else if (table.getPhase() == GamePhase.LOBBY) {
            lines.add(TypewriterTextStyle.meta("未准备"));
        }
        if (table.getRole(seat) != null) {
            lines.add(table.getRole(seat) == linmumua.doudizhu.game.PlayerRole.LANDLORD
                ? MuzTheme.landlord(table.getRole(seat).displayName())
                : MuzTheme.farmer(table.getRole(seat).displayName()));
        }
        if (table.getPhase() == GamePhase.PLAYING) {
            lines.add(MuzTheme.hotMetric("剩余", String.valueOf(table.getHand(seat).size()), "张"));
        }
        lines.add(TypewriterTextStyle.meta("分数 " + table.getScore(seat)));
        if (seat.equals(table.getCurrentTurn())) {
            lines.add(TypewriterTextStyle.accent("当前操作"));
        }
        return TypewriterTextStyle.joinLines(lines.toArray(Component[]::new));
    }

    /**
     * 椅子外侧字条的大小，有人无人都读 EMPTY_SEAT_SCALE。
     * 原来空位读 EMPTY_SEAT_*、入座读 SEAT_NAME_*，两组出厂默认值相同，
     * 但玩家只会去调看得见的空位字条，一坐下字条就跳回没调过的 SEAT_NAME_*
     * （表现为「抬高的大字」瞬间变成「贴脸的小字」）。统一成一组就不会跳。
     * @param table 桌子，用于判断该座位有没有人（保留形参，方便以后再区分）
     * @param seatIndex 座位序号 0/1/2
     * @return 字条缩放
     */
    private float seatNameScale(GameTable table, int seatIndex) {
        return Math.max(0.08f, plugin.getEmptySeatScale());
    }

    private float seatInfoScale() {
        return Math.max(0.08f, plugin.getSeatInfoScale());
    }

    private Location seatNameLocation(GameTable table, int seatIndex, Location seatBase, float yaw) {
        // 与 seatNameScale 同理：有人无人都读 EMPTY_SEAT_*，
        // 否则玩家一入座，字条会从调好的空位位置跳到没调过的 SEAT_NAME_* 位置。
        double lateral = plugin.getEmptySeatLateralOffset();
        double vertical = plugin.getEmptySeatVerticalOffset();
        double depth = plugin.getEmptySeatDepthOffset();
        // 座位名字/空位文字都归属某一把椅子，偏移按该座位自身朝向换算到世界坐标，
        // 这样「向左」对上方座位是它自己的左，而不是所有座位一起朝同一个世界方向走。
        Vector offset = seatRelativeOffset(
            seatIndex,
            yaw,
            lateral,
            plugin.getChairLabelHeight() + vertical,
            depth
        );
        return seatBase.clone().add(offset.x(), offset.y(), offset.z());
    }

    /**
     * 椅子外侧那行小字（座位号/准备状态/分数）的位置。
     * 基准仍是主字条位置减去一行的间距 gap；SEAT_INFO_* 三项是叠在这个基准上的微调。
     * gap 固定让开——座位名字始终显示，每个座位都需要错开间距。
     * @param table 桌子
     * @param seatIndex 座位序号 0/1/2
     * @param seatBase 该座位的世界坐标基准点
     * @param yaw 桌子朝向
     * @return 小字的世界坐标
     */
    private Location seatInfoLocation(GameTable table, int seatIndex, Location seatBase, float yaw) {
        Location base = seatNameLocation(table, seatIndex, seatBase, yaw);
        double gap = 0.18 + Math.max(0.0f, plugin.getSmallTextScale() - 0.46f) * 0.06;
        base = base.clone().add(0.0, -gap, 0.0);
        Vector extra = seatInfoExtraOffset(
            seatIndex,
            yaw,
            plugin.getSeatInfoLateralOffset(),
            plugin.getSeatInfoVerticalOffset(),
            plugin.getSeatInfoDepthOffset()
        );
        return base.clone().add(extra.x(), extra.y(), extra.z());
    }

    private Component currentTurnStatusBanner(GameTable table) {
        if (table == null || table.getCurrentTurn() == null) {
            return null;
        }
        return switch (table.getPhase()) {
            case DEALING -> MuzTheme.warm("当前发牌");
            case REVEALING -> MuzTheme.warm("当前明牌");
            case BIDDING -> MuzTheme.warm("当前叫分");
            case DOUBLING -> MuzTheme.warm("当前加倍");
            case PLAYING -> MuzTheme.warm("当前出牌");
            case LOBBY -> null;
        };
    }

    private Component currentTurnSeatBadge(GameTable table) {
        if (table == null || table.getCurrentTurn() == null) {
            return null;
        }
        return switch (table.getPhase()) {
            case DEALING -> TypewriterTextStyle.accent("当前发牌");
            case REVEALING -> TypewriterTextStyle.accent("当前明牌");
            case BIDDING -> TypewriterTextStyle.accent("当前叫分");
            case DOUBLING -> TypewriterTextStyle.accent("当前加倍");
            case PLAYING -> TypewriterTextStyle.accent("当前出牌");
            case LOBBY -> null;
        };
    }

    private float joinLabelTextScale() {
        return Math.max(0.08f, plugin.getJoinLabelScale());
    }

    private float actionLabelTextScale() {
        return Math.max(0.08f, plugin.getActionLabelScale());
    }

    private UUID placedSeat(GameTable table, int seatIndex) {
        PlacedTable placed = placedTable(table.getName());
        return placed == null ? null : placed.seatAssignments().get(seatIndex);
    }

    private int placedSeatIndex(PlacedTable placed, UUID playerId) {
        return placed.seatAssignments().entrySet().stream()
            .filter(entry -> entry.getValue().equals(playerId))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(-1);
    }

    /**
     * 三边按钮统一的离桌半径。
     *
     * <p>抽出来是因为 {@link #warnIfCapturerCouldOccludeButtons} 也要用它做深度序比较，
     * 两处各算一遍必然漂移，而漂移的后果是那道遮挡告警按错的数字判断、失去意义。
     */
    private double unifiedActionDistance() {
        double baseDistance = plugin.getButtonDistance();
        double frontDistance = plugin.getButtonFrontBaseDistance()
            + Math.max(0.0, (baseDistance - 1.10) * plugin.getButtonDistanceFactor());
        double sideDistance = plugin.getButtonSideBaseDistance()
            + Math.max(0.0, (baseDistance - 1.10) * plugin.getButtonDistanceFactor());
        return (frontDistance + sideDistance) * 0.5;
    }

    private Location actionBase(Location anchor, float tableYaw, int seatIndex) {
        // 斗地主三边按钮统一使用同一个离桌半径，前座/侧座两个参数共同决定这个半径。
        double unifiedDistance = unifiedActionDistance();
        double height = plugin.getButtonHeight();
        return switch (seatIndex) {
            case 0 -> rotate(anchor, tableYaw, 0.0, height, -unifiedDistance);
            case 1 -> rotate(anchor, tableYaw, -unifiedDistance, height, 0.0);
            default -> rotate(anchor, tableYaw, unifiedDistance, height, 0.0);
        };
    }

    private void playSelectionSound(UUID playerId, boolean selected) {
        DoudizhuPlugin.SelectionSound sound = plugin.selectionSoundFor(playerId);
        if (sound.volume() <= 0.0f) {
            return;
        }
        float pitch = selected ? sound.selectedPitch() : sound.deselectedPitch();
        playerOutput.playSound(playerId, sound.key(), sound.volume(), pitch);
    }

    /** 只读取连接生命周期提供的不可变 UUID 快照；后续玩家 API 一律交给 PlayerOutputDispatcher。 */
    private List<UUID> onlinePlayerIdsSnapshot() {
        return playerPresence.snapshot();
    }

    private Component message(String text, NamedTextColor color) {
        return MuzTheme.named(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static float snappedYaw(float yaw) {
        return Math.round(yaw / 90.0f) * 90.0f;
    }

    private static Location rotate(Location anchor, float yaw, double x, double y, double z) {
        double radians = Math.toRadians(yaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double rx = x * cos - z * sin;
        double rz = x * sin + z * cos;
        return anchor.clone().add(rx, y, rz);
    }

    private Vector handCenter(int seatIndex) {
        double distance = plugin.getHandCenterDistance();
        return switch (seatIndex) {
            case 0 -> new Vector(0.0, plugin.getHandCenterHeight(), -distance);
            case 1 -> new Vector(-distance, plugin.getHandCenterHeight(), 0.0);
            default -> new Vector(distance, plugin.getHandCenterHeight(), 0.0);
        };
    }

    private Vector handStep(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(plugin.getHandSpacing(), 0.0, 0.0);
            case 1 -> new Vector(0.0, 0.0, -plugin.getHandSpacing());
            default -> new Vector(0.0, 0.0, plugin.getHandSpacing());
        };
    }

    private Vector handDepth(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(0.0, 0.0, plugin.getCardDepthOffset());
            case 1 -> new Vector(plugin.getCardDepthOffset(), 0.0, 0.0);
            default -> new Vector(-plugin.getCardDepthOffset(), 0.0, 0.0);
        };
    }

    private Vector towardTableAxis(int seatIndex) {
        Vector center = handCenter(seatIndex);
        return normalizeHorizontal(new Vector(-center.x(), 0.0, -center.z()));
    }

    private static Vector normalizeHorizontal(Vector vector) {
        double length = Math.sqrt(vector.x() * vector.x() + vector.z() * vector.z());
        if (length < 0.0001) {
            return new Vector(0.0, 0.0, 0.0);
        }
        return new Vector(vector.x() / length, 0.0, vector.z() / length);
    }

    private static Vector rotateVector(Vector vector, float yaw) {
        double radians = Math.toRadians(yaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double rx = vector.x() * cos - vector.z() * sin;
        double rz = vector.x() * sin + vector.z() * cos;
        return new Vector(rx, vector.y(), rz);
    }

    /**
     * 座位自身的横向轴（桌子局部坐标系，未经 tableYaw 旋转）。
     * 方向与 handStep 一致：+1 指向该座位玩家自己的左手边。
     * 这里不含 handSpacing 之类的幅度，只表达纯方向，便于单测。
     * 座位方位：0 = 局部 -Z（对面），1 = 局部 -X（左），2 = 局部 +X（右）。
     * @param seatIndex 座位序号 0/1/2
     * @return 归一化的横向轴，仍在桌子局部坐标系
     */
    static Vector seatLateralAxis(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(1.0, 0.0, 0.0);
            case 1 -> new Vector(0.0, 0.0, -1.0);
            default -> new Vector(0.0, 0.0, 1.0);
        };
    }

    /**
     * 座位朝向桌心的轴（桌子局部坐标系，未经 tableYaw 旋转）。
     * 与 towardTableAxis 同向：+1 表示朝桌子中心靠近，-1 表示远离桌子。
     * @param seatIndex 座位序号 0/1/2
     * @return 归一化的纵深轴，仍在桌子局部坐标系
     */
    static Vector seatDepthAxis(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(0.0, 0.0, 1.0);
            case 1 -> new Vector(1.0, 0.0, 0.0);
            default -> new Vector(-1.0, 0.0, 0.0);
        };
    }

    /**
     * 把「相对某个座位自身朝向」的偏移量换算成世界坐标位移。
     * 关键点：seatLateralAxis / seatDepthAxis 给出的是桌子局部轴，
     * 必须再乘 tableYaw 才能加到世界坐标的基准点上；
     * 少了这一步，三个座位就会一起朝同一个世界方向平移
     * （表现为「上方的座位也跟着往左走」）。
     * @param seatIndex 座位序号 0/1/2
     * @param tableYaw 桌子朝向，用于把局部轴转到世界坐标
     * @param lateral 横向偏移，正值朝该座位玩家的左手边
     * @param vertical 垂直偏移，世界 Y 轴不受 yaw 影响
     * @param depth 纵深偏移，正值朝桌心靠近
     * @return 可直接加到世界坐标基准点上的位移向量
     */
    static Vector seatRelativeOffset(int seatIndex, float tableYaw, double lateral, double vertical, double depth) {
        Vector lateralAxis = rotateVector(seatLateralAxis(seatIndex), tableYaw);
        Vector depthAxis = rotateVector(seatDepthAxis(seatIndex), tableYaw);
        return new Vector(
            lateralAxis.x() * lateral + depthAxis.x() * depth,
            vertical,
            lateralAxis.z() * lateral + depthAxis.z() * depth
        );
    }

    /**
     * SEAT_INFO_* 三项相对「出厂默认」的净偏移，换算成世界坐标位移。
     *
     * 为什么要减默认值：这三项的出厂默认不是 0（SEAT_INFO_VERTICAL 默认 -0.22），
     * 而现在小字的位置是由硬编码 gap 独自决定的。若直接把设置值加上去，
     * 老服务器一升级，小字会凭空再往下掉 0.22 格。
     * 减掉默认值之后，「没调过」等价于「零位移」，视觉完全不变；
     * 玩家动一格设置，小字就跟着动一格，语义仍然是所见即所得。
     *
     * 轴向沿用 {@link #seatRelativeOffset}：偏移属于某一把椅子，
     * 必须按该座位自身朝向换算，否则三个座位会一起朝同一个世界方向平移。
     * @param seatIndex 座位序号 0/1/2
     * @param tableYaw 桌子朝向
     * @param lateral SEAT_INFO_LATERAL 的当前值，正值朝该座位玩家的左手边
     * @param vertical SEAT_INFO_VERTICAL 的当前值，世界 Y 轴
     * @param depth SEAT_INFO_DEPTH 的当前值，正值朝桌心靠近
     * @return 可直接加到基准位置上的位移向量，全部为默认值时是零向量
     */
    static Vector seatInfoExtraOffset(int seatIndex, float tableYaw, double lateral, double vertical, double depth) {
        return seatRelativeOffset(
            seatIndex,
            tableYaw,
            lateral - DoudizhuPlugin.AdminSetting.SEAT_INFO_LATERAL.defaultValue(),
            vertical - DoudizhuPlugin.AdminSetting.SEAT_INFO_VERTICAL.defaultValue(),
            depth - DoudizhuPlugin.AdminSetting.SEAT_INFO_DEPTH.defaultValue()
        );
    }

    private static Vector actionStep(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(1.0, 0.0, 0.0);
            case 1 -> new Vector(0.0, 0.0, 1.0);
            default -> new Vector(0.0, 0.0, -1.0);
        };
    }

    private static float handCardYaw(float tableYaw, int seatIndex) {
        return switch (seatIndex) {
            case 0 -> tableYaw;
            case 1 -> tableYaw - 90.0f;
            default -> tableYaw + 90.0f;
        };
    }

    private String handSignature(GameTable table, PlacedTable placed, UUID playerId) {
        int seatIndex = placedSeatIndex(placed, playerId);
        String cards = table.getHand(playerId).stream()
            .map(card -> Integer.toString(card.id()))
            .collect(java.util.stream.Collectors.joining(","));
        // 明牌状态必须进签名：点明牌时手牌没变，签名不带这一位就不会重建，
        // 结果牌面翻不过来。
        //
        // 完整 phase 刻意【不】进签名：牌的几何、物品、标签、可见性都不看 phase，
        // 它只驱动按钮，而按钮有 actionSignature 这道独立闸门。带上 phase 会让
        // 叫分→加倍→出牌每次阶段切换都白白整手重建一次（整排手牌闪一下）。
        //
        // 但 LOBBY 这一位必须留：renderPrivateHand 在 phase == LOBBY 时直接 return 不铺牌，
        // 所以 LOBBY ↔ 非 LOBBY 的切换是真正会改变牌面存在与否的维度，签名漏了它，
        // 开局时牌铺不出来、散局时牌收不回去。
        return "lobby=" + (table.getPhase() == GamePhase.LOBBY) + "|" + seatIndex
            + "|revealed=" + table.isHandRevealed(playerId)
            + "|" + cards;
    }

    private String actionSignature(GameTable table, PlacedTable placed) {
        List<ActionButtonState> phaseStates = phaseButtonStates(table.getPhase());
        StringBuilder builder = new StringBuilder(table.getPhase().displayName());
        for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
            UUID owner = placed.seatAssignments().get(seatIndex);
            builder.append("|seat=").append(seatIndex).append(":owner=").append(owner);
            List<ActionButtonState> activeStates = actionStatesForSeat(table, owner, phaseStates);
            for (ActionButtonState state : activeStates) {
                builder.append(buttonSignatureFragment(
                    state.modelId(),
                    state.label(),
                    state.action(),
                    state.offsetX()
                ));
            }
        }
        return builder.toString();
    }

    /**
     * 拼出单个按钮在签名里的片段
     * modelId 在多个阶段里重名（LOBBY 的"准备"和 DOUBLING 的"加倍"都是 ready），
     * 所以片段必须同时带上 label、action 和 offsetX，否则阶段切换时按钮不会重建，
     * 玩家会看到上一阶段的文字。
     * @param modelId 历史遗留的模型 id，已无视觉作用
     * @param label 按钮文字
     * @param action 按钮动作
     * @param offsetX 弧线上的偏移
     * @return 签名片段
     */
    static String buttonSignatureFragment(String modelId, String label, ButtonAction action, double offsetX) {
        return ":" + modelId + "/" + label + "/" + action + "/" + offsetX;
    }

    private double[] chairOffsets(int index) {
        double distance = plugin.getChairDistance();
        return switch (index) {
            case 0 -> new double[] {0.0, -distance};
            case 1 -> new double[] {-distance, 0.0};
            default -> new double[] {distance, 0.0};
        };
    }

    private Vector3f privateCardScale(boolean selected, boolean hovered) {
        return privateCardScale(hovered ? 1.0f : 0.0f);
    }

    /**
     * 手牌缩放，随悬停进度插值放大——但只放大牌面的长与宽，厚度恒定。
     *
     * <p>厚度（display 的 Z，也就是牌的法向）刻意不参与放大。判定用的包络取
     * {@code privateCardScale(0)} 与 {@code privateCardScale(1)} 的并集，而牌面所在
     * 平面的位置由法向尺寸决定：一旦法向随悬停变化，牌面就会沿法向前后挪，
     * 射线与牌平面的交点跟着漂移，「命中 → 放大 → 交点漂移 → 脱靶 → 缩回 → 又命中」
     * 的抖动闭环就会回来。长宽方向的放大不会移动牌面所在的平面，包络在横向、
     * 竖向取最大值即可严格覆盖，所以这两个方向可以安全地留作悬停反馈。
     *
     * <p>过渡照旧是逐帧插值的：hoverProgress 由 advanceAnimation 推进，
     * 这里只是把插值结果限制在长宽两个方向上，牌看起来仍然是平滑地涨大。
     *
     * @param hoverProgress 悬停动画进度，0 为静止、1 为完全悬停
     * @return 该帧手牌应当使用的缩放
     */
    private Vector3f privateCardScale(float hoverProgress) {
        float baseFactor = Math.max(0.01f, plugin.getPrivateCardScale() / DEFAULT_PRIVATE_CARD_RENDER_SCALE);
        float progress = Math.clamp(hoverProgress, 0.0f, 1.0f);
        float hoverFactor = 1.0f + (Math.max(1.0f, plugin.getHoverCardScale()) - 1.0f) * progress;
        float faceFactor = baseFactor * hoverFactor;
        return new Vector3f(
            plugin.getPrivateCardWidthScale() * faceFactor,
            plugin.getPrivateCardHeightScale() * faceFactor,
            plugin.getPrivateCardDepthScale() * baseFactor
        );
    }

    private double selectedCardLift(boolean selected, boolean hovered) {
        return animatedCardLift(selected ? 1.0f : 0.0f, hovered ? 1.0f : 0.0f);
    }

    private double animatedCardLift(float selectedProgress, float hoverProgress) {
        return plugin.getSelectedCardLift() * applyCurve(selectedProgress, plugin.cardHoverAnimationCurve())
            + plugin.getHoverCardLift() * applyCurve(hoverProgress, plugin.cardHoverAnimationCurve());
    }

    private Transformation cardTransformation(Vector3f scale, float lift) {
        // Translation + scale only.
        // Never introduce roll/pitch/yaw here, otherwise hovered or clicked cards start tilting and snapping back.
        return new Transformation(
            new Vector3f(0.0f, lift, 0.0f),
            new AxisAngle4f(),
            scale,
            new AxisAngle4f()
        );
    }

    /**
     * 读动画进度的当前值，<b>不推进</b>。
     *
     * <p>铺牌用它、每 tick 的 {@code updatePrivateSelection} 用 {@code advanceAnimation}：
     * 铺牌不该替下一 tick 走一步，否则同一 tick 内进度会被推进两次，动画比配置的时长更快。
     * 没有存量时返回 0，牌从平躺开始平滑升起。
     */
    private float currentAnimationProgress(Map<UUID, Map<Integer, Float>> animationMap, UUID playerId, int cardId) {
        Map<Integer, Float> cardMap = animationMap.get(playerId);
        return cardMap == null ? 0.0f : cardMap.getOrDefault(cardId, 0.0f);
    }

    private float advanceAnimation(Map<UUID, Map<Integer, Float>> animationMap, UUID playerId, int cardId, boolean active, float riseStep, float fallStep) {
        Map<Integer, Float> cardMap = animationMap.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        float current = cardMap.getOrDefault(cardId, 0.0f);
        float next;
        if (active) {
            next = Math.min(1.0f, current + riseStep);
        } else {
            next = Math.max(0.0f, current - fallStep);
        }
        if (next <= 0.0001f) {
            cardMap.remove(cardId);
            if (cardMap.isEmpty()) {
                animationMap.remove(playerId);
            }
            return 0.0f;
        }
        cardMap.put(cardId, next);
        return next;
    }

    private float cardAnimationStep() {
        return 1.0f / Math.max(1, plugin.getCardHoverInterpolationTicks());
    }

    private static float easeOutCubic(float progress) {
        float clamped = Math.clamp(progress, 0.0f, 1.0f);
        float inverted = 1.0f - clamped;
        return 1.0f - inverted * inverted * inverted;
    }

    private static float linear(float progress) {
        return Math.clamp(progress, 0.0f, 1.0f);
    }

    private static float easeInOutCubic(float progress) {
        float clamped = linear(progress);
        return clamped < 0.5f
            ? 4.0f * clamped * clamped * clamped
            : 1.0f - (float) Math.pow(-2.0f * clamped + 2.0f, 3.0f) / 2.0f;
    }

    private static float backOut(float progress) {
        float clamped = linear(progress);
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float value = 1.0f + c3 * (float) Math.pow(clamped - 1.0f, 3.0f) + c1 * (float) Math.pow(clamped - 1.0f, 2.0f);
        return Math.clamp(value, 0.0f, MAX_ANIMATION_OVERSHOOT);
    }

    private static float applyCurve(float progress, DoudizhuPlugin.AnimationCurve curve) {
        return switch (curve) {
            case LINEAR -> linear(progress);
            case EASE_OUT -> easeOutCubic(progress);
            case EASE_IN_OUT -> easeInOutCubic(progress);
            case BACK_OUT -> backOut(progress);
        };
    }

    private void applyCardGlow(ItemDisplay display, UUID playerId, boolean selected, boolean hovered) {
        if (hovered && plugin.isHoverGlowEnabled()) {
            display.setGlowing(true);
            display.setGlowColorOverride(plugin.previewGlowColorFor(playerId));
            return;
        }
        if (selected && plugin.isSelectedGlowEnabled()) {
            display.setGlowing(true);
            display.setGlowColorOverride(plugin.selectionGlowColorFor(playerId));
            return;
        }
        display.setGlowing(false);
        display.setGlowColorOverride(null);
    }

    /**
     * 牌面标签位置。
     * cardBaseLocation 已经是世界坐标（调用方用 rotate(tableYaw, ...) 生成），
     * 所以这里的横向/纵深偏移必须先经 tableYaw 转到世界坐标再相加。
     * 之前直接把桌子局部轴加到世界坐标上，桌子一旦不是 yaw=0，
     * 三个座位的标签就会一起朝同一个世界方向偏，而不是各自朝自己的左右前后。
     * @param cardBaseLocation 牌的世界坐标基准点
     * @param seatIndex 座位序号 0/1/2
     * @param tableYaw 桌子朝向，用于把局部偏移轴转到世界坐标
     * @param lift 选中/悬浮动画的抬升量
     * @return 标签的世界坐标
     */
    private Location privateCardLabelLocation(Location cardBaseLocation, int seatIndex, float tableYaw, double lift) {
        Vector offset = seatRelativeOffset(
            seatIndex,
            tableYaw,
            plugin.getCardLabelLateralOffset(),
            plugin.getCardLabelHeight() + 0.08 + lift,
            plugin.getCardLabelDepthOffset()
        );
        return cardBaseLocation.clone().add(offset.x(), offset.y(), offset.z());
    }

    private static float normalizeYaw(float yaw) {
        float normalized = yaw % 360.0f;
        if (normalized > 180.0f) {
            normalized -= 360.0f;
        } else if (normalized <= -180.0f) {
            normalized += 360.0f;
        }
        return normalized;
    }

    /**
     * 解析拾取：视线射线 × 每张牌的牌面矩形，命中多张时取 index 最小（最上层）那张。
     *
     * <p>矩形<b>不读牌这一帧的动画状态</b>，而是用 {@link HandCardPickGeometry#envelope} 算出的
     * 包络：静止态与「完全悬停且已选中」态扫过的空间的并集。抬升和放大都是悬停的输出，
     * 判定跟着它们走就成了闭环——命中 → 牌抬起 → 判定区上移 → 脱靶 → 落回 → 又命中，
     * 准星停在牌边缘时牌会以动画周期上下抖。包络让命中集合与动画状态无关，环被掐断。
     *
     * <p>位置仍取自 {@code display.getLocation()}：实体位置只含铺排和悬停后退，不含抬升
     * （抬升走 transformation 的 translation.y），所以它是稳定基准。
     *
     * <p>投影用的两个轴：{@code u} = 铺开方向、{@code n} = 朝桌心的深度方向，都乘过
     * tableYaw。两者水平且互相垂直，配上世界 Y 构成正交基，因此可以直接点乘世界坐标
     * 而不必先减去某个原点——只要眼睛和牌用的是同一组基，差值就是对的。
     *
     * @return 命中信息；没有命中任何牌、或被方块挡住时返回 null
     */
    /**
     * 统一后的手牌判定包络：[0] 未选中、[1] 已选中，两者宽高相同、只有竖直中心不同。
     *
     * <p>抽出来是因为现在有<b>三</b>个消费者：解析拾取（{@code pickHandCard}）、
     * 点击捕获器的尺寸与位置（{@code renderPrivateHand} / {@code updatePrivateSelection}）、
     * 调试线框（{@code refreshPickDebug}）。三处各算一遍必然漂移，而漂移的后果正是
     * 这套几何最想避免的那类问题：捕获器比包络大就重建吞事件的死区，比包络小就点不到牌边。
     *
     * <p>包络本身<b>与牌当帧的动画状态无关</b>，只读配置：悬停的抬升和缩放是「被悬停」的输出，
     * 判定跟着它们走会形成闭环抖动。详见 {@link HandCardPickGeometry#envelope}。
     *
     * @return 长度 2 的数组，[0] 未选中包络、[1] 已选中包络
     */
    private HandCardPickGeometry.Envelope[] unifiedHandCardEnvelopes() {
        // scale.x 是牌面宽度方向：card 模型带 "rotation": {"y": -90}，
        // 旋转后 4.5 单位的牌面跨度落在 display X 上，0.25 单位的厚度落在 Z 上。
        Vector3f restScale = privateCardScale(0.0f);
        Vector3f maxScale = privateCardScale(1.0f);
        // maxLift 只取悬停抬升（不含选中抬升）：已选中的牌不走此包络，走 envelopeForSelected。
        // 钳位与 privateHandStep、handCardCapturerWidth 的 Math.max(0.02, ...) 同口径。
        double pickLaneHalfWidth = handCardCapturerWidth(plugin.getHandSpacing()) * 0.5;
        HandCardPickGeometry.Envelope unselectedRaw = HandCardPickGeometry.envelope(
            restScale.x, restScale.y, maxScale.x, maxScale.y,
            animatedCardLift(0.0f, 1.0f) * animationOvershootBound(),
            pickLaneHalfWidth);
        // 已选中牌的包络同样与动画状态无关，而且必须把牌【未抬起时的位置】并进去：
        // 选中抬升（render.selected-card.lift 默认 0.18 格）大于牌本体全高（约 0.139 格），
        // 牌抬到位后原位置整块空出来，包络若只贴合牌本体就会被相邻未选中牌按「index 最小」抢走，
        // 右键表现为选不中/选错张，左键则因命中牌不在选中集合里而出不了牌。
        // 详见 HandCardPickGeometry#envelopeForSelected。
        HandCardPickGeometry.Envelope selectedRaw = HandCardPickGeometry.envelopeForSelected(
            unselectedRaw, restScale.y,
            animatedCardLift(1.0f, 0.0f) * animationOvershootBound());
        HandCardPickGeometry.Envelope[] unified = HandCardPickGeometry.unifiedEnvelopes(unselectedRaw, selectedRaw);
        // 只收紧实际命中触发区，牌面仍按原配置渲染；两个状态保持同一比例，避免悬停/选中切换时漂移。
        return new HandCardPickGeometry.Envelope[] {
            HandCardPickGeometry.scaleEnvelope(unified[0], HAND_CARD_HIT_AREA_SCALE),
            HandCardPickGeometry.scaleEnvelope(unified[1], HAND_CARD_HIT_AREA_SCALE)
        };
    }

    private HandCardPickGeometry.Hit pickHandCard(GameTable table, PlacedTable placed, Player viewer) {
        Location eye = viewer.getEyeLocation();
        return pickHandCard(table, placed, viewer.getUniqueId(), eye, eye.getDirection());
    }

    private HandCardPickGeometry.Hit pickHandCard(
        GameTable table,
        PlacedTable placed,
        UUID playerId,
        Location eye,
        org.bukkit.util.Vector direction
    ) {
        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return null;
        }
        Map<Integer, HandCardVisual> visuals = placed.privateVisualsByPlayer().get(playerId);
        if (visuals == null || visuals.isEmpty()) {
            return null;
        }
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (hand.isEmpty()) {
            return null;
        }
        if (eye.getWorld() == null || placed.anchor().getWorld() == null || !eye.getWorld().equals(placed.anchor().getWorld())) {
            return null;
        }
        Vector lateral = rotateVector(seatLateralAxis(seatIndex), placed.yaw());
        Vector depth = rotateVector(seatDepthAxis(seatIndex), placed.yaw());
        HandCardPickGeometry.Envelope[] unified = unifiedHandCardEnvelopes();
        HandCardPickGeometry.Envelope unselectedEnvelope = unified[0];
        HandCardPickGeometry.Envelope selectedEnvelope = unified[1];
        Set<Integer> selected = table.getSelection(playerId);
        List<HandCardPickGeometry.CardQuad> quads = new ArrayList<>(hand.size());
        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            HandCardVisual visual = visuals.get(card.id());
            if (visual == null) {
                continue;
            }
            Entity entity = Bukkit.getEntity(visual.cardDisplayId());
            if (!(entity instanceof ItemDisplay display)) {
                continue;
            }
            // 实体位置本身不含抬升（抬升走 transformation 的 translation.y），所以这个 Y 是稳定基准。
            Location cardLocation = display.getLocation();
            // 逐牌选用包络：两者都与动画状态无关，都不读当帧 transformation，
            // 所以「点到哪张」不随悬停/选中动画漂移。
            HandCardPickGeometry.Envelope env = selected.contains(card.id())
                ? selectedEnvelope
                : unselectedEnvelope;
            quads.add(new HandCardPickGeometry.CardQuad(
                card.id(),
                index,
                cardLocation.getX() * lateral.x() + cardLocation.getZ() * lateral.z(),
                cardLocation.getY() + env.centerVOffset(),
                cardLocation.getX() * depth.x() + cardLocation.getZ() * depth.z(),
                env.halfWidth(),
                env.halfHeight()
            ));
        }
        HandCardPickGeometry.Hit hit = HandCardPickGeometry.pick(
            quads,
            eye.getX() * lateral.x() + eye.getZ() * lateral.z(),
            eye.getY(),
            eye.getX() * depth.x() + eye.getZ() * depth.z(),
            direction.getX() * lateral.x() + direction.getZ() * lateral.z(),
            direction.getY(),
            direction.getX() * depth.x() + direction.getZ() * depth.z(),
            MAX_HAND_CARD_PICK_DISTANCE
        );
        if (hit == null) {
            return null;
        }
        // 旧方案用 getTargetEntity 顺带拿到了视线阻挡，解析求交必须自己补这一步，
        // 否则隔着墙也能选牌。
        org.bukkit.util.RayTraceResult blocked = eye.getWorld().rayTraceBlocks(
            eye,
            direction,
            MAX_HAND_CARD_PICK_DISTANCE,
            org.bukkit.FluidCollisionMode.NEVER,
            true
        );
        double blockDistance = blocked == null
            ? Double.POSITIVE_INFINITY
            : blocked.getHitPosition().distance(eye.toVector());
        return HandCardPickGeometry.occluded(hit, blockDistance) ? null : hit;
    }

    /** 一次最多画几张牌的判定区：命中那张 + 左右各一。 */
    private static final int PICK_DEBUG_CARD_SPAN = 1;
    /** 牌本体面的颜色（白）。 */
    private static final Color PICK_DEBUG_BODY_COLOR = Color.fromARGB(0x58, 0xFF, 0xFF, 0xFF);
    /** 未选中理论包络的颜色（青）：牌本体底边 + hover 抬升与放大。 */
    private static final Color PICK_DEBUG_UNSELECTED_COLOR = Color.fromARGB(0x58, 0x60, 0xB4, 0xFF);
    /** 已选中理论包络的颜色（黄）。 */
    private static final Color PICK_DEBUG_SELECTED_COLOR = Color.fromARGB(0x58, 0xFF, 0xE2, 0x5C);
    /** 统一后实际生效包络的颜色（红）：真正决定点不点得到的那圈。 */
    private static final Color PICK_DEBUG_EFFECTIVE_COLOR = Color.fromARGB(0x58, 0xFF, 0x5C, 0x5C);

    /**
     * TextDisplay 背景板基准宽度（格）：单个半角空格的 advance 宽度 4 像素 × 每像素 1/40 格。
     * 这是把"目标格数"换成"scale 倍数"的换算分母：scaleX = (halfWidth * 2) / PANEL_BASE_WIDTH。
     * 基准尺寸为估算值，若实机矩形与包络边界不吻合，只需调这两个常量，不要动几何计算。
     */
    private static final float PICK_DEBUG_PANEL_BASE_WIDTH = 4.0f / 40.0f;
    /**
     * TextDisplay 背景板基准高度（格）：单行行高 9 像素（含背景 padding）× 每像素 1/40 格。
     * 同上：scaleY = (halfHeight * 2) / PANEL_BASE_HEIGHT。基准尺寸为估算值，与 PANEL_BASE_WIDTH
     * 一起调，以对齐实机边界；不碰几何计算。
     */
    private static final float PICK_DEBUG_PANEL_BASE_HEIGHT = 9.0f / 40.0f;
    /**
     * 三层面板在深度方向（n 轴，朝玩家方向为负）上的错开量（格）。
     * 三层颜色分别为牌本体 / 理论包络 / 实际生效包络，若完全重合则会视觉糊在一起。
     * 虽然 setSeeThrough(true) 能让牌透视、面板穿透，但各错开一点能更直观地区分三层。
     * 牌本体层错开 0（贴牌），理论包络层 -0.008，实际生效包络层 -0.016（离玩家更近）。
     */
    private static final float PICK_DEBUG_PANEL_DEPTH_STEP = -0.008f;

    /**
     * 切换某个玩家的手牌可点范围显示。
     *
     * @param player 目标玩家
     * @return 切换后是否为开启状态
     */
    public boolean togglePickDebug(Player player) {
        UUID playerId = player.getUniqueId();
        if (pickDebugViewers.remove(playerId)) {
            clearPickDebug(playerId);
            return false;
        }
        pickDebugViewers.add(playerId);
        return true;
    }

    // ---- 手牌点击链路追踪（/muz debug trace，诊断完可整段移除） ----
    /**
     * 切换某个玩家的手牌点击链路追踪。
     * 追踪消息只发给他自己的聊天，不写控制台，避免刷屏；
     * 同时按 {@link #TRACE_LOG_RELATIVE_PATH} 落一份纯文本，供事后翻查。
     *
     * @param player 目标玩家
     * @return 切换后是否为开启状态
     */
    public boolean toggleHandCardTrace(Player player) {
        UUID playerId = player.getUniqueId();
        if (traceViewers.remove(playerId)) {
            return false;
        }
        traceViewers.add(playerId);
        return true;
    }

    /**
     * 追踪消息的唯一出口：先判开关，再拼字符串，关闭状态零开销。
     *
     * <p>格式化为 Supplier（lazy）而非直接把 message 做入参，是因为调用点大量出现
     * {@code String.format(...)} / 长连接串 —— 这些必须延迟到判开关之后再拼。
     * 颜色统一用 NamedTextColor，前缀 [trace] 便于和玩家正常聊天区分。
     *
     * @param player   发送对象
     * @param color    消息颜色
     * @param message  延迟格式化的消息内容
     */
    void trace(Player player, NamedTextColor color, Supplier<String> message) {
        // 第一行就判开关：关闭时不做任何字符串拼接或方法调用，零开销。
        // 落盘也在这道闸门之后，关闭状态一样不碰文件。
        if (!traceViewers.contains(player.getUniqueId())) {
            return;
        }
        // Supplier 只 get 一次：调用点大量是 String.format，重复 get 等于白算一遍，
        // 而且带副作用的 Supplier 会被执行两次。聊天和落盘共用这一份结果。
        String raw = message.get();
        Component prefix = MuzTheme.named("[trace] ", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false);
        Component body = MuzTheme.named(raw, color)
            .decoration(TextDecoration.ITALIC, false);
        playerOutput.sendMessage(player.getUniqueId(), prefix.append(body));
        // 落盘用未上色的 raw，不走 Component：文件是给人和 AI 读的，
        // 颜色码/MiniMessage 标签在文本里只是噪音。
        appendTraceLine(player.getName(), raw);
    }

    /**
     * 把一行追踪塞进内存缓冲，并确保有一个异步任务会把它写下去。
     *
     * <p>带时间戳和玩家名：多人同时开 trace 时，只有这两样能把交错的行分开。
     *
     * @param playerName 触发这条追踪的玩家名
     * @param raw        未上色的消息正文
     */
    private void appendTraceLine(String playerName, String raw) {
        String line = "[" + java.time.LocalTime.now().format(TRACE_TIME_FORMAT) + "] ["
            + playerName + "] " + raw;
        boolean needsSchedule;
        synchronized (traceLineBuffer) {
            traceLineBuffer.add(line);
            // 已经排了一个 flush 就不再排：一次点击的连续多行合并成一次写盘。
            needsSchedule = !traceFlushScheduled;
            traceFlushScheduled = true;
        }
        if (needsSchedule) {
            // 主线程绝不做阻塞 IO：写盘整段挪到异步任务里。
            // 不用定时 flush 而是「有内容就排一次」，是为了让文件立刻可读——
            // 排查的人开完 trace 点几下就会去 cat 这个文件，攒够一批再写会让他看到空文件。
            plugin.scheduler().runAsync(this::flushTraceBuffer);
        }
    }

    /**
     * 异步侧：把缓冲整批写盘并 flush。
     *
     * <p>flush 时机就是每批结束，不留脏数据在 BufferedWriter 里：追踪日志的用途是
     * 出问题时马上被人读走，晚一秒都不如省下的那点 IO 值钱。
     */
    private void flushTraceBuffer() {
        List<String> pending;
        synchronized (traceLineBuffer) {
            traceFlushScheduled = false;
            if (traceLineBuffer.isEmpty()) {
                return;
            }
            pending = new ArrayList<>(traceLineBuffer);
            traceLineBuffer.clear();
        }
        try {
            java.io.File file = traceLogFile();
            rotateTraceLogIfTooLarge(file);
            if (traceWriter == null) {
                // 目录可能整个不存在（全新服第一次开 trace）。
                java.io.File parent = file.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                traceWriter = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(file, true), java.nio.charset.StandardCharsets.UTF_8));
            }
            for (String line : pending) {
                traceWriter.write(line);
                traceWriter.write(System.lineSeparator());
            }
            traceWriter.flush();
        } catch (java.io.IOException | RuntimeException ex) {
            // 写盘失败绝不能把游戏逻辑带崩：诊断日志掉了顶多排查费点劲，
            // 抛出去会让点击链路整条断掉。控制台只喊一次，避免高频刷屏。
            closeTraceWriterQuietly();
            if (!traceWriteFailureWarned) {
                traceWriteFailureWarned = true;
                plugin.getLogger().warning("追踪日志写入失败，后续追踪只发聊天不落盘：" + ex.getMessage());
            }
        }
    }

    /** 追踪日志的实际文件。路径与 {@link #TRACE_LOG_RELATIVE_PATH} 必须一致。 */
    private java.io.File traceLogFile() {
        return new java.io.File(new java.io.File(plugin.getDataFolder(), "debug"), "trace.log");
    }

    /**
     * 超过上限就把当前文件轮转成 trace.log.1，只留一代。
     *
     * @param file 当前追踪日志文件
     */
    private void rotateTraceLogIfTooLarge(java.io.File file) {
        if (file.length() < TRACE_LOG_MAX_BYTES) {
            return;
        }
        closeTraceWriterQuietly();
        java.io.File rolled = new java.io.File(file.getParentFile(), "trace.log.1");
        rolled.delete();
        file.renameTo(rolled);
    }

    /** 关掉 writer 并忘掉它。失败也不抛：调用点都在善后路径上。 */
    private void closeTraceWriterQuietly() {
        if (traceWriter == null) {
            return;
        }
        try {
            traceWriter.close();
        } catch (java.io.IOException ignored) {
            // 关闭失败没有补救手段，继续走即可。
        }
        traceWriter = null;
    }

    /**
     * 关服/reload 时把剩下的追踪行写掉并关闭文件。
     *
     * <p>这里是唯一一处允许在主线程同步写盘的地方：调度器在关闭阶段已经不会再跑异步任务，
     * 不同步写就会丢掉最后一批——而崩服前的最后几行恰恰是排查最需要的。
     */
    private void shutdownTraceLog() {
        flushTraceBuffer();
        closeTraceWriterQuietly();
    }
    /** 给包外监听器（listener 包）暴露的追踪入口。只做可见性包装，不重复逻辑。 */
    public void traceForListener(Player player, NamedTextColor color, Supplier<String> message) {
        trace(player, color, message);
    }
    // ---- 追踪结束 ----

    /**
     * 删掉某个玩家的线框实体，并忘掉它的签名。
     *
     * @param playerId 玩家 id
     */
    public void clearPickDebug(UUID playerId) {
        List<UUID> pool = pickDebugPool.remove(playerId);
        if (pool != null) {
            for (UUID entityId : pool) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) {
                    entity.remove();
                }
            }
        }
        pickDebugSignatures.remove(playerId);
    }

    /**
     * 每 tick 刷新判定区可视化。开销集中在「签名变化」的那一帧，静止时直接返回。
     *
     * <p>改用 TextDisplay 背景板画半透明实心矩形（不再是 ItemDisplay 细线段）：
     * 实心面能一眼看出可点区域，细线在实机里太细看不清。历史坑：TextDisplay 用空文本
     * 时背景板面积为 0、永远不可见（见 PickDebugRenderingTest 的 panelsAreDrawnWithSpaceTextDisplay）。
     * 现用单空格 " " 撑开背景板面积，再用 setTransformation 的 scale 拉成任意矩形。
     *
     * <p>三层面板叠着画，一眼能看出「不严丝合缝」差多少：白 = 牌本体、青/黄 = 未选中/已选中
     * 的理论包络、红 = 统一后真正生效的包络。三层在深度方向（n 轴）各错开一点以区分颜色。
     *
     * @param table 玩家所在牌桌
     * @param placed 对应的实体桌
     * @param viewerId 开了显示的玩家 UUID
     * @param eye 玩家在 player lane 拍下的视线位置
     * @param direction 玩家在 player lane 拍下的视线方向
     */
    private void refreshPickDebug(GameTable table, PlacedTable placed, UUID viewerId, Location eye, org.bukkit.util.Vector direction) {
        UUID playerId = viewerId;
        int seatIndex = placedSeatIndex(placed, playerId);
        Map<Integer, HandCardVisual> visuals = placed.privateVisualsByPlayer().get(playerId);
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (seatIndex < 0 || visuals == null || visuals.isEmpty() || hand.isEmpty()) {
            clearPickDebug(playerId);
            return;
        }
        HandCardPickGeometry.Hit hit = pickHandCard(table, placed, playerId, eye, direction);
        Set<Integer> selection = table.getSelection(playerId);
        // 签名覆盖所有会改变线框位置的输入：命中哪张、选中集合、手牌张数。
        // 手牌位置本身只在这三者之一变化时才动，所以不必把坐标纳入签名。
        String signature = (hit == null ? "none" : Integer.toString(hit.cardId()))
            + "|" + selection + "|" + hand.size();
        if (signature.equals(pickDebugSignatures.get(playerId))) {
            return;
        }
        pickDebugSignatures.put(playerId, signature);

        Vector lateral = rotateVector(seatLateralAxis(seatIndex), placed.yaw());
        Vector depth = rotateVector(seatDepthAxis(seatIndex), placed.yaw());
        // 面板朝向必须与牌面完全同源：FIXED 朝向下，面板朝向只由实体自身 yaw 决定、不跟视角转，
        // 所以 yaw 错了面板就是被侧着看，而厚度 scale 只有 0.01f，侧棱几乎零宽 = 静默隐形。
        // 历史 bug：这里没传 yaw，实体 yaw 取默认 0，座位 1/2 差 ±90°、桌子 yaw 非 0 时座位 0 也差，
        // 结果整个调试面板玩家根本看不见。必须复用 handCardYaw，不能另写一份角度换算。
        float panelYaw = handCardYaw(placed.yaw(), seatIndex);
        Vector3f restScale = privateCardScale(0.0f);
        Vector3f maxScale = privateCardScale(1.0f);
        double pickLaneHalfWidth = Math.max(0.02, plugin.getHandSpacing()) * 0.5;
        HandCardPickGeometry.Envelope unselectedRaw = HandCardPickGeometry.envelope(
            restScale.x, restScale.y, maxScale.x, maxScale.y,
            animatedCardLift(0.0f, 1.0f) * animationOvershootBound(),
            pickLaneHalfWidth);
        HandCardPickGeometry.Envelope selectedRaw = HandCardPickGeometry.envelopeForSelected(
            unselectedRaw, restScale.y,
            animatedCardLift(1.0f, 0.0f) * animationOvershootBound());
        HandCardPickGeometry.Envelope[] unified =
            HandCardPickGeometry.unifiedEnvelopes(unselectedRaw, selectedRaw);
        HandCardPickGeometry.Envelope body =
            HandCardPickGeometry.cardBody(restScale.x, restScale.y);

        // 只画命中那张及左右各一张：改用 TextDisplay 实心面板后每张牌只需 3 个实体（3 层包络），
        // 成本远低于之前的 96 个 ItemDisplay 段，全画 17 张也只有 51 个实体，
        // 理论上可以考虑放宽 PICK_DEBUG_CARD_SPAN，但 span 本身不改，保持原有视野范围。
        int focus = hit == null ? hand.size() / 2 : Math.max(0, hit.index());
        int from = Math.max(0, focus - PICK_DEBUG_CARD_SPAN);
        int to = Math.min(hand.size() - 1, focus + PICK_DEBUG_CARD_SPAN);

        List<PendingDebugRect> pending = new ArrayList<>();
        for (int index = from; index <= to; index++) {
            DoudizhuCard card = hand.get(index);
            HandCardVisual visual = visuals.get(card.id());
            if (visual == null) {
                continue;
            }
            Entity entity = Bukkit.getEntity(visual.cardDisplayId());
            if (!(entity instanceof ItemDisplay display)) {
                continue;
            }
            Location cardLocation = display.getLocation();
            double centerU = cardLocation.getX() * lateral.x() + cardLocation.getZ() * lateral.z();
            double centerN = cardLocation.getX() * depth.x() + cardLocation.getZ() * depth.z();
            double baseY = cardLocation.getY();
            boolean isSelected = selection.contains(card.id());
            collectRect(pending, body, centerU, centerN, baseY,
                PICK_DEBUG_BODY_COLOR, 0, isSelected);
            collectRect(pending, isSelected ? selectedRaw : unselectedRaw,
                centerU, centerN, baseY,
                isSelected ? PICK_DEBUG_SELECTED_COLOR : PICK_DEBUG_UNSELECTED_COLOR,
                1, isSelected);
            collectRect(pending, isSelected ? unified[1] : unified[0],
                centerU, centerN, baseY, PICK_DEBUG_EFFECTIVE_COLOR, 2, isSelected);
        }
        applyPickDebugPool(playerId, eye.getWorld(), placed.owner(), pending, lateral, depth, panelYaw);
    }

    /** 一块待落的实心判定区面板：中心局部坐标 (u, n)、世界 Y、半宽、半高、颜色与层序。 */
    private record PendingDebugRect(double u, double y, double n,
        double halfWidth, double halfHeight, Color color, int layer) {
    }

    private void collectRect(
        List<PendingDebugRect> out,
        HandCardPickGeometry.Envelope env,
        double centerU,
        double centerN,
        double baseY,
        Color color,
        int layer,
        boolean isSelected
    ) {
        // 直接从 Envelope 取半宽/中心 v 偏移/半高，不再调 wireframe。
        // 中心 V 由 baseY（牌中心世界 Y）加上中心 v 偏移得到。
        // 已核对：TextDisplay 背景板绕实体原点【竖直居中】缩放，与 Interaction 从底边向上生长不同，
        // 所以这里的 Y 直接就是面板中心该在的位置，【不要】照 Interaction 那样再减半个高度。
        // 依据：ButtonLabelCoverageTest.glyphCenterSitsAtBoxCenter 断言渲染中心 = 实体 Y + translation.y，
        // 即 translation 为 0 时渲染中心正好落在实体原点；本方法这条路 translation 恒为 0
        // （见 stylePickDebugPanel 传的 new Vector3f()），故中心即 baseY + centerVOffset。
        // 反例参考：按钮那条路要 hitboxBottomForLabel 换算，正因为 Interaction 是底边生长的。
        // 深度方向按层序错开：layer=0 贴牌，layer>0 朝玩家方向递增（负方向）。
        double centerV = env.centerVOffset();
        out.add(new PendingDebugRect(
            centerU,
            baseY + centerV,
            centerN + layer * PICK_DEBUG_PANEL_DEPTH_STEP,
            env.halfWidth(),
            env.halfHeight(),
            color,
            layer
        ));
    }

    /**
     * 把算好的面板落到实体上：池里够用就 teleport 复用，不够补，多了删。
     *
     * <p>不每帧重建实体是刻意的：虽然后台从 96 段降到 3 个/张牌，仍是可感知开销。
     * teleport 比 spawn/remove 便宜得多，池只在牌数变化时才伸缩。
     *
     * <p>{@code panelYaw} 必须由调用方按 {@link #handCardYaw} 算好传进来：面板是 FIXED 朝向，
     * 朝向完全由实体自身 yaw 决定，yaw 不对面板就被侧着看（厚度 0.01f，等于隐形）。
     * <b>复用分支尤其要注意</b>：{@link #teleportIfMoved} 会刻意保留实体当前的 yaw/pitch，
     * 光靠它纠不回朝向，所以复用与新建两条路都要显式落 yaw。
     */
    private void applyPickDebugPool(
        UUID playerId,
        World world,
        TableOwner owner,
        List<PendingDebugRect> pending,
        Vector lateral,
        Vector depth,
        float panelYaw
    ) {
        List<UUID> pool = pickDebugPool.computeIfAbsent(playerId, key -> new ArrayList<>());
        for (int i = 0; i < pending.size(); i++) {
            PendingDebugRect rect = pending.get(i);
            // 局部 (u, n) 还原成世界 XZ：两个轴都是单位向量且互相垂直，直接线性组合。
            double worldX = rect.u() * lateral.x() + rect.n() * depth.x();
            double worldZ = rect.u() * lateral.z() + rect.n() * depth.z();
            // yaw 必须写进 Location：新建那条路是靠 spawn 时的 Location 定朝向的。
            Location target = new Location(world, worldX, rect.y(), worldZ, panelYaw, 0.0f);
            TextDisplay line = null;
            if (i < pool.size()) {
                Entity existing = Bukkit.getEntity(pool.get(i));
                if (existing instanceof TextDisplay reused && reused.getWorld().equals(world)) {
                    line = reused;
                    teleportIfMoved(line, target, CARD_TRACK_EPSILON_SQUARED);
                    // teleportIfMoved 只比位置、且刻意保留实体原有 yaw/pitch，复用旧面板时朝向不会被纠正。
                    // 少了这一步，改完 yaw 仍然看不见——池里的老实体会一直停在 yaw=0 被侧着看。
                    applyStableYaw(line, panelYaw);
                    protectEntity(line, owner, ENTITY_ROLE_DEBUG_PANEL);
                } else {
                    if (existing != null) {
                        existing.remove();
                    }
                    line = spawnPickDebugPanel(playerId, target, owner);
                    pool.set(i, line.getUniqueId());
                }
            } else {
                line = spawnPickDebugPanel(playerId, target, owner);
                pool.add(line.getUniqueId());
            }
            stylePickDebugPanel(line, rect);
        }
        // 池比这一帧需要的长，多出来的删掉，避免上一帧的面板留在原地
        while (pool.size() > pending.size()) {
            Entity stale = Bukkit.getEntity(pool.remove(pool.size() - 1));
            if (stale != null) {
                stale.remove();
            }
        }
    }

    /**
     * 生成一块判定区面板（TextDisplay 背景板画半透明实心矩形）。
     *
     * <p>为什么现在 TextDisplay 可行，而历史上不行：TextDisplay 背景板尺寸是被文本撑开的。
     * 历史坑：{@code Component.empty()} 时包围盒为 0，{@code setBackgroundColor} 没面积可画，
     * transformation.scale 乘上去仍是 0——永远不可见且不报错（PickDebugRenderingTest
     * 的 panelsAreDrawnWithSpaceTextDisplay 专门守这条）。
     * 现在用 {@code Component.text(" ")}：单个半角空格在默认字体里有非零 advance 宽度，
     * 背景板因此有真实面积，scale 可以把它拉成任意矩形。尺寸换算见 PANEL_BASE_WIDTH/HEIGHT 常量。
     *
     * <p><b>FIXED 与 yaw 是强耦合的</b>：FIXED 意味着朝向完全交给实体自身 yaw，
     * 传进来的 {@code location} 必须已经带上 {@link #handCardYaw} 算出的 yaw。
     * 否则实体 yaw 为默认 0、面板与牌面差一个角度被侧着看，而厚度 scale 只有 0.01f，
     * 侧棱几乎零宽——表现为面板完全看不见且不报错。这是真实踩过的坑。
     *
     * <p>关键设置：FIXED 朝向让面板贴牌面、不跟视角转；setSeeThrough(true) 让面板穿透牌本身，
     * 否则面板会被牌挡在背后白画了；setShadowed(false) + setTextOpacity 1 消除空格文字本体干扰。
     */
    private TextDisplay spawnPickDebugPanel(UUID viewerId, Location location, TableOwner owner) {
        return VersionCompat.spawnEntity(location.getWorld(), location, TextDisplay.class, spawned -> {
            // 单空格撑开背景板面积，绝不能换成空文本组件（面积为 0、静默不可见，见类上方 Javadoc）。
            spawned.text(Component.text(" "));
            spawned.setBillboard(Display.Billboard.FIXED);
            // 满亮度，免得面板在桌下阴影里看不出颜色
            spawned.setBrightness(new Display.Brightness(15, 15));
            spawned.setViewRange(2.0f);
            // 面板能穿透牌和其他实体，调试面才看得到，否则被牌挡住就白画了
            spawned.setSeeThrough(true);
            // 消除空格文字本体的阴影与本影（空格本身不可见，但保险起见）
            spawned.setShadowed(false);
            spawned.setTextOpacity((byte) 1);
            protectEntity(spawned, owner, ENTITY_ROLE_DEBUG_PANEL);
            // 只给开启者看：其余在线玩家一律隐藏；玩家 API 由 dispatcher 投递到 player lane。
            for (UUID otherId : onlinePlayerIdsSnapshot()) {
                if (!otherId.equals(viewerId)) {
                    playerOutput.hideEntity(otherId, plugin, spawned);
                }
            }
            playerOutput.showEntity(viewerId, plugin, spawned);
        });
    }

    private void stylePickDebugPanel(TextDisplay panel, PendingDebugRect line) {
        if (panel == null) {
            return;
        }
        // 背景色即该层颜色，alpha 已降为 0x58（实心面叠三层容易糊，alpha 必须比线框低）。
        panel.setBackgroundColor(line.color());
        // 用 transformation.scale 把背景板基准尺寸（由单空格撑开）拉到目标矩形。
        // 基准宽度 = 空格 advance（4 像素 × 1/40 格/像素），基准高度 = 单行行高（9 像素 × 1/40）。
        float fullWidth = (float) (line.halfWidth() * 2.0);
        float fullHeight = (float) (line.halfHeight() * 2.0);
        float scaleX = fullWidth / PICK_DEBUG_PANEL_BASE_WIDTH;
        float scaleY = fullHeight / PICK_DEBUG_PANEL_BASE_HEIGHT;
        // 厚度方向给很小的正值（0.01f），不能给 0，否则实体退化不可见。
        panel.setTransformation(new Transformation(
            new Vector3f(),
            new AxisAngle4f(),
            new Vector3f(scaleX, scaleY, 0.01f),
            new AxisAngle4f()
        ));
    }

    private void updateHoverState(GameTable table, PlacedTable placed, UUID viewerId, Location eye, org.bukkit.util.Vector direction) {
        if (placed == null || viewerId == null || eye == null || direction == null) {
            if (viewerId != null) {
                clearHover(viewerId);
            }
            return;
        }
        HandCardPickGeometry.Hit hit = pickHandCard(table, placed, viewerId, eye, direction);
        Integer hovered = hit == null ? null : hit.cardId();
        Integer previous = hoveredCardIds.get(viewerId);
        // IMPORTANT REGRESSION GUARD:
        // Hover state must clear immediately when the pointer leaves a card.
        // Candidate/grace retention caused stale hover residue and made cards look stuck in an old hover frame.
        hoverCandidateCardIds.remove(viewerId);
        hoverCandidateTicksByViewer.remove(viewerId);
        hoverGraceTicksByViewer.remove(viewerId);
        if ((previous == null && hovered == null) || (previous != null && previous.equals(hovered))) {
            return;
        }
        if (hovered == null) {
            hoveredCardIds.remove(viewerId);
        } else {
            hoveredCardIds.put(viewerId, hovered);
        }
    }

    private void clearHover(UUID playerId) {
        hoveredCardIds.remove(playerId);
        lastHandCardClickTicks.remove(playerId);
        hoverCandidateCardIds.remove(playerId);
        hoverCandidateTicksByViewer.remove(playerId);
        hoverGraceTicksByViewer.remove(playerId);
        hoverProgressByPlayer.remove(playerId);
    }

    /**
     * 玩家下线时清掉他在这里的所有按玩家分组的缓存。
     *
     * <p>【为什么必须由退出事件显式调用】：单桌 owner tick 只处理当前牌桌座位，
     * 不再遍历 {@code Bukkit.getOnlinePlayers()} 清理全服玩家；离线玩家的 key 根本轮不到，
     * 于是 hover/选中/调试面板这几张 map 会把已经下线的 UUID 永久留着。
     * 单个 key 很小，但服务器长期运行、玩家反复进出，累积量是无上限的。
     *
     * <p>{@code selectedProgressByPlayer} 也要清：它不在 clearHover 里，
     * 而是跟着私有手牌实体走（见 clearPrivateEntities），玩家下线时那批实体
     * 会被收掉，但进度条目不会跟着消失。
     */
    public void clearPlayerCaches(UUID playerId) {
        if (playerId == null) {
            return;
        }
        clearHover(playerId);
        clearPickDebug(playerId);
        selectedProgressByPlayer.remove(playerId);
    }

    private Entity actionTarget(Player viewer) {
        org.bukkit.util.RayTraceResult hit = viewer.getWorld().rayTraceEntities(
            viewer.getEyeLocation(),
            viewer.getEyeLocation().getDirection(),
            MAX_ACTION_INTERACTION_DISTANCE,
            0.0,
            entity -> viewer.canSee(entity) && actionBindings.containsKey(entity.getUniqueId())
        );
        return hit == null ? null : hit.getHitEntity();
    }

    private boolean isWithinActionInteractionRange(Player player, Entity entity) {
        if (player == null || entity == null || !player.getWorld().equals(entity.getWorld())) {
            return false;
        }
        double distanceSquared = distanceSquaredToBoundingBox(player.getEyeLocation(), entity.getBoundingBox());
        return isWithinActionInteractionRange(distanceSquared);
    }

    static boolean isWithinActionInteractionRange(double distanceSquared) {
        return distanceSquared <= MAX_ACTION_INTERACTION_DISTANCE * MAX_ACTION_INTERACTION_DISTANCE;
    }

    private static double distanceSquaredToBoundingBox(Location point, org.bukkit.util.BoundingBox box) {
        return distanceSquaredToBox(
            point.getX(),
            point.getY(),
            point.getZ(),
            box.getMinX(),
            box.getMinY(),
            box.getMinZ(),
            box.getMaxX(),
            box.getMaxY(),
            box.getMaxZ()
        );
    }

    static double distanceSquaredToBox(
        double x,
        double y,
        double z,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
    ) {
        double dx = x < minX ? minX - x : Math.max(0.0, x - maxX);
        double dy = y < minY ? minY - y : Math.max(0.0, y - maxY);
        double dz = z < minZ ? minZ - z : Math.max(0.0, z - maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean buttonLabelScaleMatches(TextDisplay label, float expectedScale) {
        Vector3f scale = label.getTransformation().getScale();
        return buttonLabelScaleMatches(scale.x(), scale.y(), scale.z(), expectedScale);
    }

    static boolean buttonLabelScaleMatches(float scaleX, float scaleY, float scaleZ, float expectedScale) {
        return Math.abs(scaleX - expectedScale) <= 1.0E-4f
            && Math.abs(scaleY - expectedScale) <= 1.0E-4f
            && Math.abs(scaleZ - expectedScale) <= 1.0E-4f;
    }

    private float actionHitboxWidth(Component label, float labelScale) {
        return resolveHitboxWidth(label, labelScale);
    }

    /**
     * 按实际文字像素宽度和 TextDisplay 缩放计算判定框宽度。
     * Minecraft 的 TextDisplay 以 40 像素对应 1 格；不额外加边距、倍率或最小尺寸。
     */
    static float resolveHitboxWidth(Component label, float labelScale) {
        String text = PlainTextComponentSerializer.plainText().serialize(label == null ? Component.empty() : label);
        boolean bold = label != null && label.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
        return resolveHitboxWidth(text, labelScale, bold);
    }

    static float resolveHitboxWidth(String text, float labelScale, boolean bold) {
        int pixels = Math.max(1, textPixelWidth(text, bold));
        return pixels * Math.max(0.0f, labelScale) / TEXT_DISPLAY_PIXELS_PER_BLOCK;
    }

    private float actionHitboxHeight(Component label, float labelScale) {
        return resolveHitboxHeight(label, labelScale);
    }

    /**
     * 按实际文本行数和默认字体 9 像素行高计算判定框高度。
     */
    static float resolveHitboxHeight(Component label, float labelScale) {
        String text = PlainTextComponentSerializer.plainText().serialize(label == null ? Component.empty() : label);
        return resolveHitboxHeight(text, labelScale);
    }

    static float resolveHitboxHeight(String text, float labelScale) {
        int lineCount = 1;
        if (text != null) {
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) == '\n') {
                    lineCount++;
                }
            }
        }
        return lineCount * DEFAULT_FONT_LINE_HEIGHT_PIXELS * Math.max(0.0f, labelScale)
            / TEXT_DISPLAY_PIXELS_PER_BLOCK;
    }

    /**
     * 算出文字实际占用的墨迹宽度（像素）
     * 字体的 advance 里含 1 像素字间距，那段是空白不是墨迹。整行累加 advance
     * 会把行尾那段空白也算进判定框，框就比文字宽出来一截。所以每行末尾要把
     * 这 1 像素间距减掉，只留真正画出像素的宽度。
     * @param text 纯文本，可含换行
     * @param bold 是否加粗
     * @return 最宽那一行的墨迹宽度
     */
    static int textPixelWidth(String text, boolean bold) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int widest = 0;
        int current = 0;
        boolean lineHasGlyph = false;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\n') {
                widest = Math.max(widest, inkWidth(current, lineHasGlyph));
                current = 0;
                lineHasGlyph = false;
                continue;
            }
            int advance = glyphAdvancePixels(codePoint);
            if (bold && codePoint != ' ') {
                advance++;
            }
            current += advance;
            lineHasGlyph = true;
        }
        return Math.max(widest, inkWidth(current, lineHasGlyph));
    }

    /** 去掉行尾那 1 像素字间距，它是空白不是墨迹。 */
    private static int inkWidth(int advanceTotal, boolean hasGlyph) {
        return hasGlyph ? Math.max(1, advanceTotal - GLYPH_SPACING_PIXELS) : 0;
    }

    private static int glyphAdvancePixels(int codePoint) {
        if (codePoint == ' ') {
            return 4;
        }
        if (codePoint > 0x7F) {
            return 9;
        }
        if ("!.,:;|'i".indexOf(codePoint) >= 0) {
            return 2;
        }
        if ("`l".indexOf(codePoint) >= 0) {
            return 3;
        }
        if ("I[]t".indexOf(codePoint) >= 0) {
            return 4;
        }
        if ("(){}<>fkr".indexOf(codePoint) >= 0) {
            return 5;
        }
        return 6;
    }

    /**
     * 算出判定框底边该放在哪，使文字正好落在框的竖直中点
     * Interaction 实体是从底边往上长的，所以要先减半个框高。但只减半个框高还不够：
     * TypewriterTextStyle.apply 会把文字整体抬高一小段（CENTER 且无背景板时 0.03），
     * 而判定框是按传入坐标摆的，不补这段位移，框就整体低于文字。
     * action-label-scale 默认 0.20 时框高只有 0.045，位移 0.03 占了三分之二，
     * 表现就是"框比文字矮"、文字上半部分点不到。
     * @param labelY 文字实体所在高度
     * @param boxHeight 判定框高度
     * @return 判定框底边该放的高度
     */
    static double hitboxBottomForLabel(double labelY, double boxHeight) {
        return hitboxBottomForLabel(labelY, boxHeight, buttonLabelBaseLift());
    }

    /**
     * 手牌点击捕获器的宽度（格）：与拾取包络的通道宽度<b>同源</b>。
     *
     * <p>捕获器只负责把点击事件引进来，命中哪张牌仍然由 {@code pickHandCard} 解析裁决。
     * 因此这里唯一要保证的性质是：<b>命中捕获器 ⟹ 几乎必然命中拾取包络</b>。
     * 一旦捕获器比包络胖，那圈多出来的部分右键会触发事件但求交判不中，
     * 就重建了历史上那个死区——牌上曾经挂过 Interaction，正是因为它的正方形碰撞箱在牌面
     * 之外的深度方向鼓出约半个牌宽，贴着牌边点桌面既选不到牌也放不了方块，才被刻意删掉。
     *
     * <p>取值与 {@code pickHandCard} 里的 {@code pickLaneHalfWidth * 2} 完全一致：
     * 每张牌「只属于自己」的那条可见条宽度。钳位下限 0.02 与 {@code privateHandStep} 同口径，
     * 否则把 hand-spacing 配成 0 时捕获器会退化成零宽而完全点不到。
     *
     * @param handSpacing 手牌左右间距配置（render.hand-spacing）
     * @return 捕获器宽度，单位格
     */
    static double handCardCapturerWidth(double handSpacing) {
        return Math.max(0.02, handSpacing);
    }

    /**
     * 手牌点击捕获器的底边 Y。
     *
     * <p><b>Interaction 实体是从底边往上长的</b>，而拾取包络是以中心锚定的，
     * 所以必须先把包络中心换算成底边。少了这一步最直接的表现是：把牌实体 Y 直接当底边，
     * 整个盒子会浮到牌上方（默认约 [3.13, 3.726] 而不是 [2.871, 3.467]），
     * 既点不到牌又白挡视线。只减一半也不行——那会让下半张牌点不到、牌上方那截空气反而能点，
     * 症状是「有时能选中、有时选不中」，比完全失效更难查。
     * 按钮那条路踩过同一个坑，见 {@link #hitboxBottomForLabel}。
     *
     * @param envelopeCenterY 捕获器包络中心的世界 Y
     *     （牌实体 Y + {@link #handCardCapturerEnvelope} 的 {@code centerVOffset()}）
     * @param capturerHeight 捕获器高度，应等于 {@link #handCardCapturerEnvelope} 的
     *     {@code halfHeight() * 2}
     * @return 捕获器实体该摆在的 Y
     */
    static double handCardCapturerBottomY(double envelopeCenterY, double capturerHeight) {
        return envelopeCenterY - capturerHeight * 0.5;
    }

    /**
     * 捕获器的包络：<b>未选中盒与已选中盒的并集</b>，因此与选中状态无关。
     *
     * <h2>为什么必须取并集，而不能用 unifiedEnvelopes 的 halfHeight</h2>
     *
     * <p>{@link HandCardPickGeometry#unifiedEnvelopes} 只把两态的 {@code halfHeight} 取了 max，
     * <b>两态中心的差异被它丢掉了</b>：默认配置下未选中盒是 {@code [2.8712, 3.3957]}、
     * 已选中盒是 {@code [2.9425, 3.4670]}（世界 Y，相对放桌锚点），
     * 各自高 0.5245 但中心差 0.0713。
     *
     * <p>照 {@code halfHeight × 2 = 0.5245} 配一个盒子，无论摆在哪一态的中心上都会漏掉另一态。
     * 摆在未选中态时已选中盒的上半截落在盒外——<b>玩家选中一张牌后就再也点不到它的上半部分，
     * 取消选中失败</b>。这不是理论风险：选中抬升（{@code render.selected-card.lift} 默认 0.18 格）
     * 比牌本体全高（约 0.139 格）还大，两态几乎完全错开。
     *
     * <p>所以这里取并集 {@code [2.8712, 3.4670]}，高 <b>0.5958</b>，一个盒子同时盖住两态，
     * 捕获器的位置于是<b>不随选中状态改变</b>——这与「判定几何是动画的不动点」是同一套哲学，
     * 用一点宽容度换掉一整类状态同步问题。
     *
     * <p><b>刻意不硬编码 0.5958</b>：它从两个包络推出来，
     * 于是 {@code selected-card.lift}、{@code card-hover.lift}、{@code private-card-scale}
     * 任何一项改动时捕获器都自动跟随。写死数值会让配置一改就静默漂移，
     * 而漂移的后果恰好是上面那个「取消不了选中」。
     *
     * @param unselected 统一后的未选中包络（{@link #unifiedHandCardEnvelopes} 的 [0]）
     * @param selected 统一后的已选中包络（{@link #unifiedHandCardEnvelopes} 的 [1]）
     * @return 并集包络，{@code halfWidth} 沿用入参，{@code centerVOffset}/{@code halfHeight} 取并集
     */
    static HandCardPickGeometry.Envelope handCardCapturerEnvelope(
        HandCardPickGeometry.Envelope unselected,
        HandCardPickGeometry.Envelope selected
    ) {
        double bottom = Math.min(
            unselected.centerVOffset() - unselected.halfHeight(),
            selected.centerVOffset() - selected.halfHeight());
        double top = Math.max(
            unselected.centerVOffset() + unselected.halfHeight(),
            selected.centerVOffset() + selected.halfHeight());
        return new HandCardPickGeometry.Envelope(
            Math.max(unselected.halfWidth(), selected.halfWidth()),
            (bottom + top) * 0.5,
            (top - bottom) * 0.5
        );
    }

    static double hitboxBottomForLabel(double labelY, double boxHeight, double labelBaseLift) {
        return labelY + labelBaseLift - boxHeight / 2.0;
    }

    /**
     * 取出按钮文字被 apply 抬高的那段位移
     * 按钮统一用 CENTER 朝向且不带背景板，这里必须跟 spawnText 的调用保持一致：
     * 写死数值的话，apply 那边一改基准位移，判定框就会静默错位。
     * @return 竖直方向的基准位移
     */
    private static double buttonLabelBaseLift() {
        return TypewriterTextStyle.baseTranslationFor(Display.Billboard.CENTER, false).y();
    }

    private void clearActionMappings(List<UUID> ids) {
        ids.forEach(actionBindings::remove);
    }

    private static float chairYawOffset(int index) {
        return switch (index) {
            case 0 -> 0.0f;
            case 1 -> -90.0f;
            default -> 90.0f;
        };
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    record Vector(double x, double y, double z) {
    }

    private record ButtonDefinition(String modelId, String label, ButtonAction action, double offsetX, double offsetY, double offsetZ) {
    }

    private record ActionBinding(String tableName, ButtonAction action, Integer seatIndex) {
    }

    private record CardBinding(String tableName, UUID ownerId, int cardId) {
    }

    /**
     * 按钮的一项状态
     * modelId 曾经用来选图标贴图。图标已经删掉，玩家点的是文字，
     * 这个字段现在只作为 actionSignature 的一部分参与重建判定，没有视觉作用。
     * 保留它是为了避免升级后所有已放置牌桌都重建一次按钮；要清理的话得单独做。
     */
    private record ActionButtonState(String modelId, String label, ButtonAction action, double offsetX) {
    }

    private record ActionWidgetSpec(
        float yaw,
        Component labelText,
        Location labelLocation,
        Location interactionLocation,
        ActionBinding binding,
        UUID owner,
        boolean joinVisibility
    ) {
    }

    /**
     * 一张手牌对应的实体三件套。
     *
     * @param cardDisplayId 牌本体（ItemDisplay）
     * @param labelId 牌面数字标签（TextDisplay），不显示标签时为 null
     * @param capturerId 点击捕获器（Interaction）。见 {@code spawnHandCardCapturer}：
     *     牌本体没有判定框，空手右键空气时 PlayerInteractEvent 压根不触发，
     *     缺了它右键选牌整体失效
     */
    /**
     * 手牌铺开方向的两个端位。
     *
     * <p>边缘瓦片的复用池<b>按端位键，不按牌 id 键</b>：手上只剩 1 张时 index 0
     * 同时是最左和最右，需要左右各挂一块瓦片，按牌 id 的 map 装不下同一张牌的两块。
     */
    private enum HandEdge {
        LEFT,
        RIGHT
    }

    /**
     * 一张手牌对应的实体集合。
     *
     * <p>{@code leftEdgeTileId} / {@code rightEdgeTileId} 是<b>两端补覆盖用的边缘瓦片</b>，
     * 只有端点牌非 null，中间的牌两个槽都是 null。为什么要两个槽而不是一个：
     * 手上只剩 1 张时 index 0 同时是最左和最右，一个槽装不下两块，
     * 漏掉的那一侧就是「最后一手点不动」——而斗地主每局必然经过这个状态。
     *
     * @param leftEdgeTileId 左端瓦片，非端点牌为 null
     * @param rightEdgeTileId 右端瓦片，非端点牌为 null；N=1 时与 left 同挂在这一张上
     */
    private record HandCardVisual(
        UUID cardDisplayId,
        UUID labelId,
        UUID capturerId,
        UUID leftEdgeTileId,
        UUID rightEdgeTileId
    ) {
    }

    private record CleanupPlan(
        String tableKey,
        GameTable table,
        TableOwner owner,
        Location anchor,
        float yaw,
        Map<Integer, UUID> seatAssignments,
        List<Long> footprintChunkKeys,
        List<UUID> staticEntities,
        List<UUID> craftEngineEntities,
        List<UUID> actionEntities,
        List<UUID> seatNameEntities,
        List<UUID> seatInfoEntities,
        List<UUID> privateEntities,
        List<UUID> backsideEntities,
        UUID statusDisplayId,
        UUID playDetailDisplayId,
        List<CleanupRegionPart> parts,
        List<String> unresolved,
        long epoch
    ) {
        private CleanupPlan {
            anchor = anchor == null ? null : anchor.clone();
            seatAssignments = Map.copyOf(new LinkedHashMap<>(seatAssignments));
            footprintChunkKeys = List.copyOf(footprintChunkKeys);
            staticEntities = List.copyOf(staticEntities);
            craftEngineEntities = List.copyOf(craftEngineEntities);
            actionEntities = List.copyOf(actionEntities);
            seatNameEntities = List.copyOf(seatNameEntities);
            seatInfoEntities = List.copyOf(seatInfoEntities);
            privateEntities = List.copyOf(privateEntities);
            backsideEntities = List.copyOf(backsideEntities);
            parts = List.copyOf(parts);
            unresolved = List.copyOf(unresolved);
        }
    }

    private record CleanupRegionPart(
        Location ownerLocation,
        List<CleanupEntity> entities,
        List<CleanupFurniture> furniture,
        List<CleanupBlock> blocks
    ) {
        private CleanupRegionPart {
            ownerLocation = ownerLocation.clone();
            entities = List.copyOf(entities);
            furniture = List.copyOf(furniture);
            blocks = List.copyOf(blocks);
        }
    }

    private record CleanupEntity(UUID entityId, Location location) {
        private CleanupEntity {
            location = location.clone();
        }
    }

    private record CleanupFurniture(UUID rootId, Location rootLocation) {
        private CleanupFurniture {
            rootLocation = rootLocation.clone();
        }
    }

    private record CleanupBlock(BlockState state, Location location) {
        private CleanupBlock {
            location = location.clone();
        }
    }

    private static final class CleanupRegionPartBuilder {
        private final Location ownerLocation;
        private final List<CleanupEntity> entities = new ArrayList<>();
        private final List<CleanupFurniture> furniture = new ArrayList<>();
        private final List<CleanupBlock> blocks = new ArrayList<>();

        private CleanupRegionPartBuilder(Location ownerLocation) {
            this.ownerLocation = ownerLocation.clone();
        }

        private CleanupRegionPart freeze() {
            return new CleanupRegionPart(ownerLocation, entities, furniture, blocks);
        }
    }

    /**
     * 一次异步重建的冻结参数快照。
     *
     * <p>异步流水线把「加载 → 清旧 → 生成新 → 收口索引 → 刷新」拆到不同 lane 上执行，期间共享的
     * {@code placedTables} / footprint 索引 / 实体索引可能被别的路径改写。因此 region 回调
     * **只允许**读取这份冻结快照（旧桌、旧/新锚点、座位归属、目标桌实例），新的
     * {@code PlacedTable} 由 region 回调作为返回值交回，由 global 收口 stage 统一提交。
     */
    private record RebuildRequest(
        String tableKey,
        String tableName,
        PlacedTable previous,
        Location oldAnchor,
        Location newAnchor,
        float yaw,
        UUID ownerId,
        String ownerName,
        Map<Integer, UUID> seatAssignments,
        GameTable table
    ) {
    }

    private record PlacedTable(
        String tableName,
        Location anchor,
        float yaw,
        TableOwner owner,
        List<Long> footprintChunkKeys,
        List<UUID> staticEntities,
        List<UUID> craftEngineVisualEntities,
        List<BlockRestore> blockRestores,
        Map<Integer, UUID> seatAssignments,
        List<Location> seatBaseLocations,
        Map<UUID, List<UUID>> privateEntitiesByPlayer,
        Map<UUID, Map<Integer, HandCardVisual>> privateVisualsByPlayer,
        Map<UUID, List<UUID>> backsideEntitiesByPlayer,
        Map<UUID, Map<Integer, UUID>> backsideVisualsByPlayer,
        UUID statusDisplayId,
        UUID playDetailDisplayId,
        List<UUID> seatNameDisplayIds,
        List<UUID> seatInfoDisplayIds,
        List<UUID> actionEntities
    ) {
        private UUID ownerId() {
            return owner == null ? null : owner.id();
        }

        private String ownerName() {
            return owner == null ? null : owner.name();
        }

        private PlacedTable withStatusDisplayId(UUID newStatusDisplayId) {
            return new PlacedTable(
                tableName,
                anchor,
                yaw,
                owner,
                footprintChunkKeys,
                staticEntities,
                craftEngineVisualEntities,
                blockRestores,
                seatAssignments,
                seatBaseLocations,
                privateEntitiesByPlayer,
                privateVisualsByPlayer,
                backsideEntitiesByPlayer,
                backsideVisualsByPlayer,
                newStatusDisplayId,
                playDetailDisplayId,
                seatNameDisplayIds,
                seatInfoDisplayIds,
                actionEntities
            );
        }

        private PlacedTable withPlayDetailDisplayId(UUID newPlayDetailDisplayId) {
            return new PlacedTable(
                tableName,
                anchor,
                yaw,
                owner,
                footprintChunkKeys,
                staticEntities,
                craftEngineVisualEntities,
                blockRestores,
                seatAssignments,
                seatBaseLocations,
                privateEntitiesByPlayer,
                privateVisualsByPlayer,
                backsideEntitiesByPlayer,
                backsideVisualsByPlayer,
                statusDisplayId,
                newPlayDetailDisplayId,
                seatNameDisplayIds,
                seatInfoDisplayIds,
                actionEntities
            );
        }
    }

    private record ChairPlacement(UUID entityId, Location seatBaseLocation, BlockRestore blockRestore, boolean craftEngineEntity) {
        private static ChairPlacement furniture(UUID entityId, Location seatBaseLocation, boolean craftEngineEntity) {
            return new ChairPlacement(entityId, seatBaseLocation, null, craftEngineEntity);
        }

        private static ChairPlacement block(Location seatBaseLocation, BlockRestore blockRestore) {
            return new ChairPlacement(null, seatBaseLocation, blockRestore, false);
        }

        private static ChairPlacement none(Location seatBaseLocation) {
            return new ChairPlacement(null, seatBaseLocation, null, false);
        }
    }

    private record TablePlacement(UUID entityId, BlockRestore blockRestore, boolean craftEngineEntity) {
        private static TablePlacement furniture(UUID entityId, boolean craftEngineEntity) {
            return new TablePlacement(entityId, null, craftEngineEntity);
        }

        private static TablePlacement block(BlockRestore blockRestore) {
            return new TablePlacement(null, blockRestore, false);
        }

        private static TablePlacement none() {
            return new TablePlacement(null, null, false);
        }
    }

    private record BlockRestore(BlockState originalState) {
    }

    /** 单桌 footprint 的世界区块键；世界 UUID 与打包后的 chunk 坐标共同构成 owner 索引键。 */
    private record ChunkOwnerKey(UUID worldId, long chunkKey) {
    }

    enum ButtonAction {
        JOIN,
        READY,
        START,
        STATUS,
        LEAVE,
        PLAY_SELECTED,
        PASS_TURN,
        HINT_PLAY,
        CLEAR_SELECTION,
        DOUBLE_NO,
        DOUBLE_YES,
        OPEN_SETTINGS,
        GADGET,
        BID_0,
        BID_1,
        BID_2,
        BID_3,
        REVEAL_HAND
    }
}

