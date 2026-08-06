package linmumua.doudizhu.world;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.assets.PackAssets;
import linmumua.doudizhu.compat.VersionCompat;
import linmumua.doudizhu.game.SimpleBotBrain;
import linmumua.doudizhu.game.GamePhase;
import linmumua.doudizhu.game.GameTable;
import linmumua.doudizhu.game.PlayerRole;
import linmumua.doudizhu.model.CardRank;
import linmumua.doudizhu.model.DoudizhuCard;
import linmumua.doudizhu.room.TableLevel;
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
import java.util.function.Supplier;
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
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class PhysicalTableManager {
    // 桌椅与按钮属于世界里的公共实体；手牌和个人按钮则是按玩家隐藏/显示的私有实体
    private static final String PROTECTED_ENTITY_TAG = "muz_table_protected";
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
    // 叫分阶段带明牌的五按钮布局。偏移取 ±0.96/±0.48/0，
    // 按默认弧度算相邻判定框间距约 0.267，远大于最宽标签 0.115，不会误触。
    private static final List<ActionButtonState> BIDDING_BUTTONS_WITH_REVEAL = List.of(
        new ActionButtonState("bid", "不叫", ButtonAction.BID_0, -0.96),
        new ActionButtonState("bid", "叫1分", ButtonAction.BID_1, -0.48),
        new ActionButtonState("bid", "叫2分", ButtonAction.BID_2, 0.00),
        new ActionButtonState("bid", "叫3分", ButtonAction.BID_3, 0.48),
        new ActionButtonState("inspect", "明牌", ButtonAction.REVEAL_HAND, 0.96)
    );
    // 已明牌后的四按钮布局，回到原来的对称偏移。
    private static final List<ActionButtonState> BIDDING_BUTTONS_ONLY = List.of(
        new ActionButtonState("bid", "不叫", ButtonAction.BID_0, -0.96),
        new ActionButtonState("bid", "叫1分", ButtonAction.BID_1, -0.32),
        new ActionButtonState("bid", "叫2分", ButtonAction.BID_2, 0.32),
        new ActionButtonState("bid", "叫3分", ButtonAction.BID_3, 0.96)
    );
    private long playDetailLastRefreshBucket = Long.MIN_VALUE;

    private final DoudizhuPlugin plugin;
    private final Map<String, PlacedTable> placedTables = new LinkedHashMap<>();
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
    /**
     * 「捕获器可能挡住按钮」这条告警是否已经喊过。
     *
     * <p>铺牌在出牌链路上是高频的，不去重会把控制台冲掉。见
     * {@link #warnIfCapturerCouldOccludeButtons}。
     */
    private boolean capturerOcclusionWarned;
    /** 开了 /muz debug show 的玩家。线框只对他自己可见。 */
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

    public PhysicalTableManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
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
        if (table == null) {
            table = plugin.getTableManager().createTable(name, plugin.defaultCreateRoomLevel());
        }
        // 和恢复牌桌走同一条清场逻辑，否则诊断桌测不出残留实体有没有被收掉。
        ensureChunkReady(anchor);
        purgeResidualWorldArtifacts(anchor, yaw);
        placedTables.put(key, spawnTable(table, anchor.clone(), yaw, null, "console"));
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
        if (anchor == null || anchor.getWorld() == null || !anchor.getChunk().isLoaded()) {
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
        if (table == null) {
            table = plugin.getTableManager().createTable(name, roomLevel);
        }
        placedTables.put(key, spawnTable(table, anchor.clone(), yaw, owner.getUniqueId(), owner.getName()));
        plugin.persistDoudizhuTable(table.getName(), table.getRoomLevel(), anchor, yaw, owner.getUniqueId(), owner.getName());
        refresh(table);
        return table;
    }

    public GameTable restoreTable(String name, TableLevel roomLevel, Location anchor, float yaw, UUID ownerId, String ownerName) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            return plugin.getTableManager().getTable(name);
        }
        ensureWorldVisualsReady("恢复牌桌");
        ensureChunkReady(anchor);
        purgeResidualWorldArtifacts(anchor, yaw);
        GameTable table = plugin.getTableManager().getTable(name);
        if (table == null) {
            table = plugin.getTableManager().createTable(name, roomLevel);
        } else {
            table.setRoomLevel(roomLevel);
        }
        placedTables.put(key, spawnTable(table, anchor.clone(), yaw, ownerId, ownerName));
        refresh(table);
        return table;
    }

    public boolean isPlaced(String tableName) {
        return placedTable(tableName) != null;
    }

    public int placedTableCount() {
        return placedTables.size();
    }

    public List<String> placedTableNames() {
        return placedTables.values().stream()
            .map(PlacedTable::tableName)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
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
     * 重建前把牌桌锚点所在区块拉起来
     * 区块未加载时 remove() 是空操作、spawn 出来的实体也留不住，
     * 直接重建会让整桌桌椅按钮凭空消失。
     * @param anchor 牌桌锚点
     */
    private void ensureAnchorChunkLoaded(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        for (long packed : anchorChunkKeys(anchor.getBlockX(), anchor.getBlockZ())) {
            int chunkX = (int) (packed >> 32);
            int chunkZ = (int) packed;
            if (!anchor.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                anchor.getWorld().getChunkAt(chunkX, chunkZ);
            }
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

    public void rebuildAllTables() {
        if (!canSafelyReplaceWorldVisuals()) {
            return;
        }
        // 重新武装遮挡告警：本方法在每次 /muz reload 时都会跑，而告警判据读的全是配置。
        // 不重置的话，服主改完 hand-center.distance 或 button-layout 再重载，
        // 新配置下的遮挡永远不会被报出来——那正是最需要这条告警的时刻。
        capturerOcclusionWarned = false;
        Map<String, PlacedTable> snapshot = new LinkedHashMap<>(placedTables);
        placedTables.clear();
        for (Map.Entry<String, PlacedTable> entry : snapshot.entrySet()) {
            PlacedTable previous = entry.getValue();
            // 区块没加载时旧实体删不掉、新实体也建不出来，重建等于把整桌实体丢光。
            // 先把锚点区块拉起来再动手。
            ensureAnchorChunkLoaded(previous.anchor());
            cleanupPlacedTable(previous);
            // HARD-CODED REBUILD SAFETY:
            // Startup warmup can rebuild the same persisted tables multiple times.
            // If any old chair/table visual survives tracked cleanup, the next rebuild would stack another copy on top.
            // Always purge anchor-side residual world artifacts before respawning the rebuilt table.
            purgeResidualWorldArtifacts(previous.anchor(), previous.yaw());
            GameTable table = plugin.getTableManager().getTable(previous.tableName());
            if (table == null) {
                continue;
            }
            PlacedTable rebuilt = spawnTable(table, previous.anchor().clone(), previous.yaw(), previous.ownerId(), previous.ownerName());
            rebuilt.seatAssignments().putAll(previous.seatAssignments());
            placedTables.put(entry.getKey(), rebuilt);
            refresh(table);
            // 重建后补一次显式恢复。重建换了一批椅子实体，理论上新实体没有历史隐藏状态，
            // 这一步偏兜底；真正需要它的是 syncViewer 那条（在线玩家身上的旧隐藏状态）。
            // 放在这里而不是刷新链路里，是因为恢复只需要一次：
            // 挂在 syncActionWidgets 上会变成每次出牌都重发判定框，把坐着的玩家挤开。
            // 这里传全场：重建换了全新的椅子实体，坐着的玩家已经被掀下来，
            // 不存在"把人挤开"的问题，而新实体本就需要让所有人都看见。
            restoreOccupiedChairHitboxVisibility(rebuilt, rebuilt.anchor().getWorld().getPlayers());
        }
    }

    public void repairIncompleteTables(String reason) {
        // 桌名和诊断原因分开存。以前只存 "桌名(原因)" 一串，重建时拿它去查表必然查不到，
        // 结果桌子被摘掉却没重建回来，表现就是桌椅整套凭空消失。
        List<String> targets = new ArrayList<>();
        List<String> logEntries = new ArrayList<>();
        for (PlacedTable placed : placedTables.values()) {
            if (placed == null) {
                continue;
            }
            if (isIncomplete(placed)) {
                targets.add(placed.tableName());
                logEntries.add(placed.tableName() + "(" + incompleteReason(placed) + ")");
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        if (reason != null && reason.startsWith("viewer-")) {
            plugin.getLogger().fine("[MUZ/repair/ddz] reason=" + reason + " tables=" + logEntries);
        } else {
            plugin.getLogger().warning("[MUZ/repair/ddz] reason=" + reason + " tables=" + logEntries);
        }
        for (String tableName : targets) {
            rebuildSingleTable(tableName);
        }
    }

    public void shiftAllAnchors(double deltaY) {
        if (Math.abs(deltaY) < 0.0001) {
            return;
        }
        if (!canSafelyReplaceWorldVisuals()) {
            return;
        }
        Map<String, PlacedTable> snapshot = new LinkedHashMap<>(placedTables);
        placedTables.clear();
        for (Map.Entry<String, PlacedTable> entry : snapshot.entrySet()) {
            PlacedTable previous = entry.getValue();
            ensureAnchorChunkLoaded(previous.anchor());
            cleanupPlacedTable(previous);
            purgeResidualWorldArtifacts(previous.anchor().clone().add(0.0, deltaY, 0.0), previous.yaw());
            GameTable table = plugin.getTableManager().getTable(previous.tableName());
            if (table == null) {
                continue;
            }
            PlacedTable rebuilt = spawnTable(table, previous.anchor().clone().add(0.0, deltaY, 0.0), previous.yaw(), previous.ownerId(), previous.ownerName());
            rebuilt.seatAssignments().putAll(previous.seatAssignments());
            placedTables.put(entry.getKey(), rebuilt);
            refresh(table);
        }
    }

    private void rebuildSingleTable(String tableName) {
        if (!canSafelyReplaceWorldVisuals()) {
            return;
        }
        String key = normalize(tableName);
        PlacedTable previous = placedTables.remove(key);
        if (previous == null) {
            // 查不到就说明调用方传错了名字。静默返回会让桌子被摘掉却建不回来，这里必须出声。
            plugin.getLogger().warning("[MUZ/repair/ddz] 重建失败，找不到已放置的牌桌: " + tableName);
            return;
        }
        ensureAnchorChunkLoaded(previous.anchor());
        cleanupPlacedTable(previous);
        purgeResidualWorldArtifacts(previous.anchor(), previous.yaw());
        GameTable table = plugin.getTableManager().getTable(previous.tableName());
        if (table == null) {
            return;
        }
        PlacedTable rebuilt = spawnTable(table, previous.anchor().clone(), previous.yaw(), previous.ownerId(), previous.ownerName());
        rebuilt.seatAssignments().putAll(previous.seatAssignments());
        placedTables.put(key, rebuilt);
        refresh(table);
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
        PlacedTable placed = placedTable(table.getName());
        if (placed == null) {
            return;
        }
        reconcileSeatAssignments(table, placed);
        refreshStatus(table, placed);
        refreshPlayDetail(table, placed);
        refreshSeatInfos(table, placed);
        refreshActionButtons(table, placed);
        refreshPrivateHands(table, placed);
        plugin.persistDoudizhuTable(table.getName(), table.getRoomLevel(), placed.anchor(), placed.yaw(), placed.ownerId(), placed.ownerName());
    }

    public void refreshPrivateHand(GameTable table, UUID playerId) {
        if (table == null) {
            return;
        }
        PlacedTable placed = placedTable(table.getName());
        if (placed == null) {
            return;
        }
        renderPrivateHand(table, placed, playerId);
    }

    public Location tableAnchor(String tableName) {
        PlacedTable placed = placedTable(tableName);
        return placed == null ? null : placed.anchor().clone();
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

    public void hidePrivateEntitiesFrom(Player viewer) {
        for (PlacedTable placed : placedTables.values()) {
            GameTable table = plugin.getTableManager().getTable(placed.tableName());
            for (Map.Entry<UUID, List<UUID>> entry : placed.privateEntitiesByPlayer().entrySet()) {
                if (entry.getKey().equals(viewer.getUniqueId())) {
                    continue;
                }
                // 明牌那家的正面牌本局对所有人公开，这里不能再一律隐藏，
                // 否则新进服或重新同步的玩家会看不到已经明出来的牌。
                // 判定框仍然只归牌主，所以这里只放开牌面显示。
                if (table != null && table.isHandRevealed(entry.getKey())) {
                    for (UUID entityId : entry.getValue()) {
                        Entity entity = Bukkit.getEntity(entityId);
                        if (entity instanceof Interaction) {
                            viewer.hideEntity(plugin, entity);
                        } else if (entity != null) {
                            viewer.showEntity(plugin, entity);
                        }
                    }
                    continue;
                }
                for (UUID entityId : entry.getValue()) {
                    Entity entity = Bukkit.getEntity(entityId);
                    if (entity != null) {
                        viewer.hideEntity(plugin, entity);
                    }
                }
            }
        }
    }

    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.isShuttingDown()) {
            return;
        }
        List<String> incomplete = new ArrayList<>();
        for (PlacedTable placed : placedTables.values()) {
            String reason = incompleteReason(placed);
            if (!reason.isBlank()) {
                incomplete.add(placed.tableName() + "(" + reason + ")");
            }
        }
        if (!incomplete.isEmpty()) {
            plugin.getLogger().fine("[MUZ/viewer-sync/ddz] viewer=" + viewer.getName() + " incomplete=" + incomplete);
            for (String tableName : incomplete.stream().map(entry -> entry.substring(0, entry.indexOf('('))).toList()) {
                rebuildSingleTable(tableName);
            }
        }
        clearHover(viewer.getUniqueId());
        actionSignatureByTable.clear();
        privateHandSignatureByTable.clear();
        backsideHandSignatureByTable.clear();
        for (PlacedTable placed : placedTables.values()) {
            GameTable table = plugin.getTableManager().getTable(placed.tableName());
            if (table != null) {
                refresh(table);
            }
            // 椅子判定框的恢复只在这类一次性时机做，不放在刷新链路里。
            // hideEntity 的隐藏状态按玩家持久，旧版本藏起来的实体不会自愈，
            // 所以每个重新进入视野的 viewer 都要显式恢复一次；
            // 但放进 syncActionWidgets 就会变成每次出牌都重发，把坐着的玩家挤开。
            restoreOccupiedChairHitboxVisibility(placed, List.of(viewer));
            showPublicEntitiesTo(viewer, placed.staticEntities());
            showPublicEntitiesTo(viewer, placed.seatNameDisplayIds());
            showPublicEntitiesTo(viewer, placed.seatInfoDisplayIds());
            if (placed.statusDisplayId() != null) {
                showPublicEntitiesTo(viewer, List.of(placed.statusDisplayId()));
            }
            if (placed.playDetailDisplayId() != null) {
                showPublicEntitiesTo(viewer, List.of(placed.playDetailDisplayId()));
            }
        }
        hidePrivateEntitiesFrom(viewer);
    }

    private void showPublicEntitiesTo(Player viewer, List<UUID> entityIds) {
        for (UUID entityId : entityIds) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                viewer.showEntity(plugin, entity);
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
                hint(player, "靠近一点再点击。", NamedTextColor.YELLOW);
                return true;
            }
            GameTable table = plugin.getTableManager().getTable(binding.tableName());
            if (table == null) {
                // 静默失败最难查：残留按钮指向已销毁的牌桌时必须给玩家反馈
                hint(player, "该牌桌已不存在，按钮已失效。", NamedTextColor.RED);
                plugin.getLogger().warning("玩家 " + player.getName() + " 点击了已失效的按钮，关联牌桌: " + binding.tableName());
                return true;
            }

            try {
                switch (binding.action()) {
                    case JOIN -> joinSeat(table, placedTable(table.getName()), player, binding.seatIndex());
                    case READY -> table.toggleReady(player);
                    case START -> table.startRound(player);
                    case STATUS -> hint(player, "抬头看桌子上方的状态牌。", NamedTextColor.YELLOW);
                    case LEAVE -> plugin.getTableManager().leaveTable(player);
                    case PLAY_SELECTED -> table.playSelected(player);
                    case PASS_TURN -> table.pass(player);
                    case HINT_PLAY -> applyHint(table, player);
                    case CLEAR_SELECTION -> {
                        table.clearSelection(player.getUniqueId());
                        refreshPrivateHand(table, player.getUniqueId());
                        hint(player, "已清除已选牌。", NamedTextColor.GRAY);
                    }
                    case DOUBLE_NO -> table.chooseDouble(player, false);
                    case DOUBLE_YES -> table.chooseDouble(player, true);
                    case OPEN_SETTINGS -> {
                        plugin.getHandGuiService().openSettings(player);
                        hint(player, "你的个人设置菜单开好了。", NamedTextColor.GREEN);
                    }
                    case BID_0 -> table.bid(player, 0);
                    case BID_1 -> table.bid(player, 1);
                    case BID_2 -> table.bid(player, 2);
                    case BID_3 -> table.bid(player, 3);
                    case REVEAL_HAND -> table.revealHand(player);
                }
                refresh(table);
            } catch (RuntimeException exception) {
                hint(player, exception.getMessage(), NamedTextColor.RED);
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
            playSelectionSound(player, !wasSelected);
        } catch (RuntimeException exception) {
            hint(player, exception.getMessage(), NamedTextColor.RED);
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
                hint(player, "请先右键选择要出的牌。", NamedTextColor.YELLOW);
                return;
            }
            if (!selection.contains(cardId)) {
                hint(player, "请左键点击已选中的牌来出牌。", NamedTextColor.YELLOW);
                return;
            }
            table.playSelected(player);
            refresh(table);
        } catch (RuntimeException exception) {
            hint(player, exception.getMessage(), NamedTextColor.RED);
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
        cleanupPlacedTable(placed);
        // HARD-CODED REMOVAL SAFETY:
        // After a server restart, tracked entity ids can be incomplete while world-side furniture/blocks still exist.
        // A normal tracked cleanup is not enough, so remove now also performs an anchor-based residual sweep over the
        // expected table/chair locations. Do not delete this fallback unless the user explicitly asks.
        purgeResidualWorldArtifacts(placed.anchor(), placed.yaw());
        if (table != null) {
            plugin.getTableManager().unregisterTable(table.getName());
        }
        plugin.deletePersistedTable("DOUDIZHU", tableName);
    }

    public void forceRemoveTable(String tableName) {
        PlacedTable placed = removePlacedTable(tableName);
        if (placed != null) {
            cleanupPlacedTable(placed);
            purgeResidualWorldArtifacts(placed.anchor(), placed.yaw());
        }
        plugin.getTableManager().unregisterTable(tableName);
        plugin.deletePersistedTable("DOUDIZHU", tableName);
    }

    public void shutdown() {
        // 放在最前、两条分支之外：关服和 reload 都必须把追踪日志的尾巴写掉。
        shutdownTraceLog();
        if (plugin.getServer().isStopping()) {
            placedTables.clear();
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
            return;
        }
        for (PlacedTable placed : new ArrayList<>(placedTables.values())) {
            cleanupPlacedTable(placed);
        }
        placedTables.clear();
        actionBindings.clear();
        cardBindings.clear();
        // reload 走的是这条分支。漏掉这几张表会让 hover 映射越reload越多，
        // 并且残留条目指向已删除的实体。
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

    public void tick() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            GameTable table = plugin.getTableManager().getTableOf(viewer);
            if (table == null) {
                clearHover(viewer.getUniqueId());
                // 离桌就把线框收掉，否则实体会留在原地，而且签名不变永远不会自愈。
                clearPickDebug(viewer.getUniqueId());
                continue;
            }
            updateHoverState(table, viewer);
            PlacedTable placed = placedTable(table.getName());
            if (placed != null) {
                updatePrivateSelection(table, placed, viewer.getUniqueId());
                updateBacksideSelection(table, placed, viewer.getUniqueId());
                // 排在悬停之后：线框要读 pickHandCard 的结果，那是悬停算出来的同一份。
                if (pickDebugViewers.contains(viewer.getUniqueId())) {
                    refreshPickDebug(table, placed, viewer);
                }
            }
        }
        long bucket = System.currentTimeMillis() / 2000L;
        if (bucket != playDetailLastRefreshBucket) {
            playDetailLastRefreshBucket = bucket;
            for (PlacedTable placed : placedTables.values()) {
                GameTable table = plugin.getTableManager().getTable(placed.tableName());
                if (table != null) {
                    refreshPlayDetail(table, placed);
                }
            }
        }
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
            hint(player, "你已切换到座位 " + (seatIndex + 1) + "。", NamedTextColor.GREEN);
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
        hint(player, "你已加入 " + table.getName() + " 号牌桌的座位 " + (seatIndex + 1) + "。", NamedTextColor.GREEN);
    }

    private void hint(Player player, String text, NamedTextColor color) {
        player.sendActionBar(message(text, color));
    }

    private void applyJoinVisibility(GameTable table, Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.showEntity(plugin, entity);
        }
    }

    public boolean isProtectedEntity(UUID entityId) {
        Entity entity = Bukkit.getEntity(entityId);
        while (entity != null) {
            if (entity.getScoreboardTags().contains(PROTECTED_ENTITY_TAG)) {
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
            ownerId,
            ownerName,
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
            addEntityTreeIds(tablePlacement.entityId(), staticEntities);
            tableVisualId = tablePlacement.entityId();
            if (tablePlacement.craftEngineEntity()) {
                addEntityTreeIds(tablePlacement.entityId(), placed.craftEngineVisualEntities());
            }
        } else {
            ItemDisplay fallbackTableDisplay = spawnFurnitureDisplay(tableLocation, tableItem(), plugin.getTableScale());
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
                addEntityTreeIds(chairPlacement.entityId(), staticEntities);
                if (chairPlacement.craftEngineEntity()) {
                    addEntityTreeIds(chairPlacement.entityId(), placed.craftEngineVisualEntities());
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
                seatNameScale(table, index)
            );
            staticEntities.add(seatName.getUniqueId());
            placed.seatNameDisplayIds().add(seatName.getUniqueId());

            TextDisplay seatInfo = spawnText(
                seatInfoLocation(table, index, seatBase, yaw),
                seatInfo(table, index),
                Display.Billboard.CENTER,
                false,
                plugin.getSmallTextScale()
            );
            staticEntities.add(seatInfo.getUniqueId());
            placed.seatInfoDisplayIds().add(seatInfo.getUniqueId());
        }

        TextDisplay status = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getStatusHeight(), 0.0),
            buildStatus(table),
            Display.Billboard.CENTER,
            false,
            plugin.getStatusTextScale()
        );
        placed = placed.withStatusDisplayId(status.getUniqueId());
        staticEntities.add(status.getUniqueId());

        TextDisplay playDetail = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getPlayDetailHeight(), 0.0),
            buildPlayDetail(table),
            Display.Billboard.CENTER,
            false,
            plugin.getSmallTextScale()
        );
        placed = placed.withPlayDetailDisplayId(playDetail.getUniqueId());
        staticEntities.add(playDetail.getUniqueId());

        return placed;
    }

    private void ensureChunkReady(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        // Startup restore can run before the destination chunk has been warmed up.
        // Force the anchor chunk loaded first so chairs, text, and CraftEngine furniture do not half-spawn.
        org.bukkit.Chunk chunk = anchor.getChunk();
        if (!chunk.isLoaded()) {
            chunk.load();
        }
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
                    clearEntities(new ArrayList<>(List.of(removedId)), false);
                }
                label = spawnText(
                    spec.labelLocation(),
                    spec.labelText(),
                    Display.Billboard.CENTER,
                    false,
                    labelScale
                );
                float boxHeight = actionHitboxHeight(spec.labelText(), labelScale);
                Location boxLocation = spec.interactionLocation().clone();
                boxLocation.setY(hitboxBottomForLabel(boxLocation.getY(), boxHeight));
                interaction = spawnInteraction(
                    boxLocation,
                    actionHitboxWidth(spec.labelText(), labelScale),
                    boxHeight
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
    private void restoreOccupiedChairHitboxVisibility(PlacedTable placed, List<Player> viewers) {
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
                for (Player viewer : viewers) {
                    if (plugin.getCraftEngineFurnitureService() != null) {
                        plugin.getCraftEngineFurnitureService().setFurnitureHitboxesVisible(nearby, viewer, true);
                    }
                    viewer.showEntity(plugin, nearby);
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
        // 且本方法在 tick() 中每 2 秒调用一次，加日志会刷屏
        Entity entity = Bukkit.getEntity(placed.playDetailDisplayId());
        updateTextEntity(entity, buildPlayDetail(table));
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
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (owner != null && viewer.getUniqueId().equals(owner) && !table.isBot(owner)) {
                    viewer.hideEntity(plugin, entity);
                } else {
                    viewer.showEntity(plugin, entity);
                }
            }
        }
        for (int index = 0; index < placed.seatInfoDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatInfoDisplayIds().get(index));
            if (entity == null) {
                continue;
            }
            UUID owner = placed.seatAssignments().get(index);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (owner != null && viewer.getUniqueId().equals(owner) && !table.isBot(owner)) {
                    viewer.hideEntity(plugin, entity);
                } else {
                    viewer.showEntity(plugin, entity);
                }
            }
        }
    }

    private void refreshPrivateHands(GameTable table, PlacedTable placed) {
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
        double startOffset = -((hand.size() - 1) * 0.5);
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
            ItemDisplay cardDisplay = spawnPlacedCard(cardBaseLocation, backCardItem(), privateCardScale(false, false), cardYaw, (float) lift);
            applyCardGlow(cardDisplay, playerId, isSelected, false);
            spawned.add(cardDisplay.getUniqueId());
            visuals.put(card.id(), cardDisplay.getUniqueId());
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(playerId) && !table.isBot(playerId)) {
                    viewer.hideEntity(plugin, cardDisplay);
                } else {
                    viewer.showEntity(plugin, cardDisplay);
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
        clearPrivateEntities(placed, playerId, reusableCapturers);
        try {
            renderPrivateHandCards(table, placed, playerId, reusableCapturers);
        } finally {
            // 没被这次铺牌认领的旧捕获器（对应的牌已经打出去了）必须销毁：
            // 它已经不在 privateEntitiesByPlayer 里，漏掉就是永久的孤儿实体，
            // 留在原地继续接事件，表现为「点空气选中了一张不存在的牌」。
            discardUnclaimedCapturers(reusableCapturers);
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

    private void discardUnclaimedCapturers(Map<Integer, Interaction> unclaimed) {
        if (unclaimed.isEmpty()) {
            return;
        }
        clearEntities(
            unclaimed.values().stream().map(Entity::getUniqueId).collect(java.util.stream.Collectors.toList()),
            false
        );
        unclaimed.clear();
    }

    private void renderPrivateHandCards(
        GameTable table,
        PlacedTable placed,
        UUID playerId,
        Map<Integer, Interaction> reusableCapturers
    ) {
        // 明牌的牌面要给全场看，所以牌主掉线时也得照常铺。
        // 只有未明牌时才需要牌主在线：那种情况下这层牌只有他自己能看见。
        boolean revealed = table.isHandRevealed(playerId);
        if (!revealed && Bukkit.getPlayer(playerId) == null) {
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
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        Map<CardRank, Integer> rankCounts = countRanks(hand);
        // 点击捕获器的尺寸与拾取包络同源，见 unifiedHandCardEnvelopes 与 handCardCapturerWidth。
        // 高度取两态并集（见 handCardCapturerEnvelope），所以它与选中状态无关。
        HandCardPickGeometry.Envelope[] pickEnvelopes = unifiedHandCardEnvelopes();
        HandCardPickGeometry.Envelope capturerEnvelope =
            handCardCapturerEnvelope(pickEnvelopes[0], pickEnvelopes[1]);
        float capturerWidth = (float) handCardCapturerWidth(plugin.getHandSpacing());
        float capturerHeight = (float) (capturerEnvelope.halfHeight() * 2.0);
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

            ItemDisplay cardDisplay = spawnPlacedCard(cardBaseLocation, cardItem(card), privateCardScale(hoverProgress), cardYaw, (float) lift);
            applyCardGlow(cardDisplay, playerId, isSelected, isHovered);
            spawned.add(cardDisplay.getUniqueId());
            cardBindings.put(cardDisplay.getUniqueId(), new CardBinding(table.getName(), playerId, card.id()));

            Interaction reusedCapturer = reusableCapturers.remove(card.id());
            UUID capturerId = reusedCapturer != null
                ? reuseHandCardCapturer(
                    table, playerId, card, reusedCapturer, cardBaseLocation,
                    capturerEnvelope, capturerWidth, capturerHeight, spawned)
                : spawnHandCardCapturer(
                    table, placed, playerId, card, cardBaseLocation,
                    capturerEnvelope, capturerWidth, capturerHeight, spawned);

            if (shouldShowPrivateLabel(playerId, card, rankCounts)) {
                Location labelLocation = privateCardLabelLocation(cardBaseLocation, seatIndex, placed.yaw(), lift);
                TextDisplay label = spawnText(
                    labelLocation,
                    MuzTheme.cardLabel(card.rank().label()),
                    Display.Billboard.CENTER,
                    false,
                    plugin.getLabelTextScale()
                );
                mountTextDisplay(cardDisplay, label, labelLocation, false);
                spawned.add(label.getUniqueId());
                cardBindings.put(label.getUniqueId(), new CardBinding(table.getName(), playerId, card.id()));
                applyPrivateVisibility(playerId, label, revealed);
                visuals.put(card.id(),
                    new HandCardVisual(cardDisplay.getUniqueId(), label.getUniqueId(), capturerId));
            } else {
                visuals.put(card.id(),
                    new HandCardVisual(cardDisplay.getUniqueId(), null, capturerId));
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
        Interaction capturer = spawnInteraction(capturerLocation, capturerWidth, capturerHeight);
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
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        float animationStep = cardAnimationStep();
        float animationFallStep = Math.min(1.0f, animationStep * 1.8f);
        HandCardPickGeometry.Envelope[] pickEnvelopes = unifiedHandCardEnvelopes();
        HandCardPickGeometry.Envelope capturerEnvelope =
            handCardCapturerEnvelope(pickEnvelopes[0], pickEnvelopes[1]);
        float capturerHeight = (float) (capturerEnvelope.halfHeight() * 2.0);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            HandCardVisual visual = visuals.get(card.id());
            if (visual == null) {
                renderPrivateHand(table, placed, playerId);
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
                return;
            }
            // 捕获器缺失就整手重建，和牌本体缺失同口径：它是右键选牌唯一的事件入口，
            // 被邻桌清场之类的路径删掉后若不补回来，这手牌就再也点不动，而且毫无报错。
            Entity capturerEntity = visual.capturerId() == null
                ? null
                : Bukkit.getEntity(visual.capturerId());
            if (!(capturerEntity instanceof Interaction)) {
                renderPrivateHand(table, placed, playerId);
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
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        float animationStep = cardAnimationStep();
        float animationFallStep = Math.min(1.0f, animationStep * 1.8f);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            UUID entityId = visuals.get(card.id());
            Entity entity = entityId == null ? null : Bukkit.getEntity(entityId);
            if (!(entity instanceof ItemDisplay cardDisplay)) {
                renderBacksideHand(table, placed, playerId);
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
        clearPrivateEntities(placed, playerId, Map.of());
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
     */
    private void clearPrivateEntities(PlacedTable placed, UUID playerId, Map<Integer, Interaction> keepCapturers) {
        List<UUID> entities = placed.privateEntitiesByPlayer().remove(playerId);
        placed.privateVisualsByPlayer().remove(playerId);
        if (entities == null) {
            return;
        }
        if (keepCapturers.isEmpty()) {
            clearEntities(entities, false);
            return;
        }
        Set<UUID> kept = keepCapturers.values().stream()
            .map(Entity::getUniqueId)
            .collect(java.util.stream.Collectors.toSet());
        clearEntities(entities.stream().filter(id -> !kept.contains(id)).toList(), false);
    }

    private void clearBacksideEntities(PlacedTable placed, UUID playerId) {
        List<UUID> entities = placed.backsideEntitiesByPlayer().remove(playerId);
        placed.backsideVisualsByPlayer().remove(playerId);
        selectedProgressByPlayer.remove(playerId);
        if (entities != null) {
            clearEntities(entities, false);
        }
    }

    private void clearEntities(List<UUID> entityIds, boolean publicCards) {
        for (UUID entityId : entityIds) {
            actionBindings.remove(entityId);
            cardBindings.remove(entityId);
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
        if (publicCards) {
            entityIds.clear();
        }
    }

    private void cleanupPlacedTable(PlacedTable placed) {
        clearCraftEngineEntities(placed.craftEngineVisualEntities());
        String tableKey = normalize(placed.tableName());
        actionSignatureByTable.remove(tableKey);
        privateHandSignatureByTable.remove(tableKey);
        backsideHandSignatureByTable.remove(tableKey);
        clearEntities(placed.actionEntities(), false);
        for (UUID playerId : new ArrayList<>(placed.backsideEntitiesByPlayer().keySet())) {
            clearBacksideEntities(placed, playerId);
        }
        for (UUID playerId : new ArrayList<>(placed.privateEntitiesByPlayer().keySet())) {
            clearPrivateEntities(placed, playerId);
        }
        clearEntities(placed.staticEntities(), false);
        restoreBlocks(placed.blockRestores());
    }

    private void clearCraftEngineEntities(List<UUID> entityIds) {
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
    private boolean isTrackedCardEntity(UUID entityId) {
        for (PlacedTable placed : placedTables.values()) {
            if (placed.privateEntitiesByPlayer().values().stream().anyMatch(ids -> ids.contains(entityId))
                || placed.backsideEntitiesByPlayer().values().stream().anyMatch(ids -> ids.contains(entityId))) {
                return true;
            }
        }
        return false;
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
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (revealed || viewer.getUniqueId().equals(ownerId)) {
                viewer.showEntity(plugin, entity);
            } else {
                viewer.hideEntity(plugin, entity);
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
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                viewer.hideEntity(plugin, entity);
            }
        }
    }

    private ItemDisplay spawnFurnitureDisplay(Location location, ItemStack item, float scale) {
        return VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
            ));
            protectEntity(spawned);
        });
    }

    private ItemDisplay spawnPlacedCard(Location location, ItemStack item, Vector3f scale, float yaw) {
        return spawnPlacedCard(location, item, scale, yaw, 0.0f);
    }

    private ItemDisplay spawnPlacedCard(Location location, ItemStack item, Vector3f scale, float yaw, float lift) {
        ItemDisplay display = VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(cardTransformation(scale, lift));
            configureCardAnimation(spawned);
            protectEntity(spawned);
        });
        applyStableYaw(display, yaw);
        return display;
    }

    private TextDisplay spawnText(Location location, Component text, Display.Billboard billboard, boolean background) {
        return spawnText(location, text, billboard, background, 1.0f);
    }

    private TextDisplay spawnText(Location location, Component text, Display.Billboard billboard, boolean background, float scale) {
        return VersionCompat.spawnEntity(location.getWorld(), location, TextDisplay.class, spawned -> {
            try {
                spawned.text(text);
            } catch (NoSuchMethodError e) {
                // 1.20.1 不支持 text() 方法，使用 setCustomName() 作为替代
                spawned.setCustomName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(text));
                spawned.setCustomNameVisible(true);
            }
            TypewriterTextStyle.apply(spawned, billboard, background, scale);
            protectEntity(spawned);
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

    private Interaction spawnInteraction(Location location, float width, float height) {
        return VersionCompat.spawnEntity(location.getWorld(), location, Interaction.class, spawned -> {
            spawned.setInteractionWidth(width);
            spawned.setInteractionHeight(height);
            spawned.setResponsive(true);
            protectEntity(spawned);
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
        entity.teleport(moved);
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

    private void protectEntity(Entity entity) {
        TableEntityGeometry.protectEntity(entity, PROTECTED_ENTITY_TAG);
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

    private void addEntityTreeIds(UUID rootId, List<UUID> target) {
        Entity root = Bukkit.getEntity(rootId);
        if (root == null) {
            if (!target.contains(rootId)) {
                target.add(rootId);
            }
            return;
        }
        protectEntityTree(root);
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

    private void protectEntityTree(Entity entity) {
        if (entity == null) {
            return;
        }
        protectEntity(entity);
        for (Entity passenger : entity.getPassengers()) {
            protectEntityTree(passenger);
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
        display.setInterpolationDuration(Math.max(2, Math.min(4, plugin.getCardHoverInterpolationTicks() / 2)));
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
            return removed;
        }
        for (Map.Entry<String, PlacedTable> entry : new ArrayList<>(placedTables.entrySet())) {
            if (entry.getValue().tableName().equalsIgnoreCase(tableName.trim())) {
                placedTables.remove(entry.getKey());
                return entry.getValue();
            }
        }
        return null;
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
            case BIDDING -> BIDDING_BUTTONS_WITH_REVEAL;
            case DOUBLING -> List.of(
                new ActionButtonState("pass", "不加倍", ButtonAction.DOUBLE_NO, -0.40),
                new ActionButtonState("ready", "加倍", ButtonAction.DOUBLE_YES, 0.40)
            );
            case PLAYING -> List.of(
                new ActionButtonState("inspect", "提示", ButtonAction.HINT_PLAY, -0.72),
                new ActionButtonState("pass", "不要", ButtonAction.PASS_TURN, -0.24),
                new ActionButtonState("refresh", "清选", ButtonAction.CLEAR_SELECTION, 0.24)
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
            // 已经明牌就把明牌按钮撤掉，并换回四按钮的对称布局，
            // 否则弧线右端会空出一格，看起来像按钮丢了。
            return table.isHandRevealed(owner) ? BIDDING_BUTTONS_ONLY : phaseStates;
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
            hint(player, "这手没什么能出的。", NamedTextColor.GRAY);
            return;
        }
        int index = hintIndices.getOrDefault(player.getUniqueId(), 0) % hints.size();
        table.replaceSelection(player.getUniqueId(), hints.get(index));
        hintIndices.put(player.getUniqueId(), index + 1);
        refreshPrivateHand(table, player.getUniqueId());
        hint(player, "提示第 " + (index + 1) + " 组，可再次点击切换。", NamedTextColor.YELLOW);
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

    private void playSelectionSound(Player player, boolean selected) {
        DoudizhuPlugin.SelectionSound sound = plugin.selectionSoundFor(player.getUniqueId());
        if (sound.volume() <= 0.0f) {
            return;
        }
        float pitch = selected ? sound.selectedPitch() : sound.deselectedPitch();
        player.playSound(player.getLocation(), sound.key(), sound.volume(), pitch);
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
        float progress = Math.max(0.0f, Math.min(1.0f, hoverProgress));
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
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        float inverted = 1.0f - clamped;
        return 1.0f - inverted * inverted * inverted;
    }

    private static float linear(float progress) {
        return Math.max(0.0f, Math.min(1.0f, progress));
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
        return Math.max(0.0f, Math.min(MAX_ANIMATION_OVERSHOOT, value));
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
            animatedCardLift(0.0f, 1.0f) * MAX_ANIMATION_OVERSHOOT,
            pickLaneHalfWidth);
        // 已选中牌的包络同样与动画状态无关，而且必须把牌【未抬起时的位置】并进去：
        // 选中抬升（render.selected-card.lift 默认 0.18 格）大于牌本体全高（约 0.139 格），
        // 牌抬到位后原位置整块空出来，包络若只贴合牌本体就会被相邻未选中牌按「index 最小」抢走，
        // 右键表现为选不中/选错张，左键则因命中牌不在选中集合里而出不了牌。
        // 详见 HandCardPickGeometry#envelopeForSelected。
        HandCardPickGeometry.Envelope selectedRaw = HandCardPickGeometry.envelopeForSelected(
            unselectedRaw, restScale.y,
            animatedCardLift(1.0f, 0.0f) * MAX_ANIMATION_OVERSHOOT);
        return HandCardPickGeometry.unifiedEnvelopes(unselectedRaw, selectedRaw);
    }

    private HandCardPickGeometry.Hit pickHandCard(GameTable table, PlacedTable placed, Player viewer) {
        UUID playerId = viewer.getUniqueId();
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
        Location eye = viewer.getEyeLocation();
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
        org.bukkit.util.Vector direction = eye.getDirection();
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
        player.sendMessage(prefix.append(body));
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
     * @param viewer 开了显示的玩家
     */
    private void refreshPickDebug(GameTable table, PlacedTable placed, Player viewer) {
        UUID playerId = viewer.getUniqueId();
        int seatIndex = placedSeatIndex(placed, playerId);
        Map<Integer, HandCardVisual> visuals = placed.privateVisualsByPlayer().get(playerId);
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (seatIndex < 0 || visuals == null || visuals.isEmpty() || hand.isEmpty()) {
            clearPickDebug(playerId);
            return;
        }
        HandCardPickGeometry.Hit hit = pickHandCard(table, placed, viewer);
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
            animatedCardLift(0.0f, 1.0f) * MAX_ANIMATION_OVERSHOOT,
            pickLaneHalfWidth);
        HandCardPickGeometry.Envelope selectedRaw = HandCardPickGeometry.envelopeForSelected(
            unselectedRaw, restScale.y,
            animatedCardLift(1.0f, 0.0f) * MAX_ANIMATION_OVERSHOOT);
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
        applyPickDebugPool(viewer, pending, lateral, depth, panelYaw);
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
        Player viewer,
        List<PendingDebugRect> pending,
        Vector lateral,
        Vector depth,
        float panelYaw
    ) {
        UUID playerId = viewer.getUniqueId();
        World world = viewer.getWorld();
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
                } else {
                    if (existing != null) {
                        existing.remove();
                    }
                    line = spawnPickDebugPanel(viewer, target);
                    pool.set(i, line.getUniqueId());
                }
            } else {
                line = spawnPickDebugPanel(viewer, target);
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
    private TextDisplay spawnPickDebugPanel(Player viewer, Location location) {
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
            protectEntity(spawned);
            // 只给开启者看：其余在线玩家一律隐藏
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (!other.getUniqueId().equals(viewer.getUniqueId())) {
                    other.hideEntity(plugin, spawned);
                }
            }
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

    private void updateHoverState(GameTable table, Player viewer) {
        PlacedTable placed = placedTable(table.getName());
        if (placed == null) {
            clearHover(viewer.getUniqueId());
            return;
        }
        HandCardPickGeometry.Hit hit = pickHandCard(table, placed, viewer);
        Integer hovered = hit == null ? null : hit.cardId();
        Integer previous = hoveredCardIds.get(viewer.getUniqueId());
        // IMPORTANT REGRESSION GUARD:
        // Hover state must clear immediately when the pointer leaves a card.
        // Candidate/grace retention caused stale hover residue and made cards look stuck in an old hover frame.
        hoverCandidateCardIds.remove(viewer.getUniqueId());
        hoverCandidateTicksByViewer.remove(viewer.getUniqueId());
        hoverGraceTicksByViewer.remove(viewer.getUniqueId());
        if ((previous == null && hovered == null) || (previous != null && previous.equals(hovered))) {
            return;
        }
        if (hovered == null) {
            hoveredCardIds.remove(viewer.getUniqueId());
        } else {
            hoveredCardIds.put(viewer.getUniqueId(), hovered);
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
    private record HandCardVisual(UUID cardDisplayId, UUID labelId, UUID capturerId) {
    }

    private record PlacedTable(
        String tableName,
        Location anchor,
        float yaw,
        UUID ownerId,
        String ownerName,
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
        private PlacedTable withStatusDisplayId(UUID newStatusDisplayId) {
            return new PlacedTable(
                tableName,
                anchor,
                yaw,
                ownerId,
                ownerName,
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
                ownerId,
                ownerName,
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
        BID_0,
        BID_1,
        BID_2,
        BID_3,
        REVEAL_HAND
    }
}

