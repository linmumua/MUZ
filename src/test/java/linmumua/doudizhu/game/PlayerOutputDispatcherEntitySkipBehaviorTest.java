package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.scheduler.MuzScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * @author linmumua
 * @Desc PlayerOutputDispatcher 的 showEntity/hideEntity 必须落在实体 owner region，且未能执行时必须留痕
 * @date 2026-09-23（2026-09-25 按 lane 机制变更升级）
 *
 * <p>意图：这两个入口以前把可见性操作投到**玩家自己的 lane**，在那里读实体状态会被 Folia 的
 * {@code CraftEntity#getHandle()} owner region 门禁直接拒绝（实服日志：同一玩家对 15 个桌面实体的
 * {@code showEntity} 全部落进「跨 region 跳过」分支）。现在改为经 {@code EntityRegionLane}
 * 投递到**实体所属 region** 执行——那里实体侧访问合法，玩家侧只发包含实体状态的包，
 * 而且隐藏状态本身按「玩家 × 实体」记账、由实体追踪的 {@code canSee} 生效，所以对跨 region 玩家也有效。
 *
 * <p>机制变了，所以本类钉的语义也整体前移到新 lane 上：
 * <ol>
 *   <li>投递目标必须是实体 owner region，<b>不能</b>再排进 player lane（否则缺陷原样复发）；</li>
 *   <li>lane 内归属成立时调用玩家 API 且【不】打日志，避免把正常路径噪化；</li>
 *   <li>lane 内归属不成立（实体中途换了 region）时零玩家调用，但必须留下 WARNING；</li>
 *   <li>正常结果不留痕：实体不存在、玩家离线都不该打日志，否则「降低重复警告」等于没做；</li>
 *   <li>投递被拒绝（关闭期）与玩家 API 抛错必须留痕，且异常不得抛回调用方
 *       （调用方是 owner tick / refresh，抛出去会打断整轮刷新）；</li>
 *   <li>同一实体 UUID 的重复留痕必须限频，且窗口内被压掉的次数必须随<b>下一次</b>日志报出。</li>
 * </ol>
 *
 * <p>夹具风格沿用仓库既有做法：{@link Unsafe} 分配的空心 {@link DoudizhuPlugin} 注入收集型
 * {@link Logger}（见 {@code TableGadgetEffectReleaseBehaviorTest}），记录型
 * {@link PlayerTaskRegistry} 让测试能观察 player lane 是否被误用（见 {@code PlayerOutputDispatcherTest}），
 * 毫秒时钟与实体 lane 都经 {@code PlayerOutputDispatcher} 构造器注入
 * （见 {@code TableSpeechPanelService} 的 tickSource）。
 *
 * <p>【夹具必须还原全局静态态】本测试是仓库里少数直接改写 {@link Bukkit} 静态字段 {@code server} 的用例，
 * 而 {@code Bukkit.server} 是**整个 JVM 共享**的：一旦把桩留在那里，之后任何走生产构造器
 * {@code new PhysicalTableManager(plugin)} 的测试（它会经 {@code seedOnlinePlayerIds()} 调
 * {@code getServer().getOnlinePlayers()}）都会拿到本桩的 {@code null} 默认值并抛 NPE——这是真实的
 * 跨测试污染，而不是被测代码的问题。因此 {@link BukkitStub#install()} 先快照原值，并由
 * {@link #restoreBukkitServer()} 在每个用例结束后还原，保证不把桩泄漏给其它测试类。
 */
class PlayerOutputDispatcherEntitySkipBehaviorTest {
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    /**
     * 每个用例结束后还原 {@link Bukkit} 的静态 {@code server} 字段，避免本测试的桩污染同 JVM 的其它测试。
     *
     * <p>用 {@code @AfterEach} 而不是 {@code @AfterAll}：所有用例都各自 {@link BukkitStub#install()}，
     * 逐用例还原才能保证任一用例失败时也不会把桩留下来。
     */
    @AfterEach
    void restoreBukkitServer() throws Exception {
        BukkitStub.uninstall();
    }

    /**
     * 核心契约：可见性操作必须投递到实体 owner region，且玩家 API 只在该 lane 内被调用。
     *
     * <p>这条直接守住本次修复的缺陷：改造前这两个入口走的是 player lane，跨 region 时读实体状态必然抛。
     */
    @Test
    void 实体可见性必须投递到实体owner_region而不是player_lane() throws Exception {
        Fixture fixture = fixture(true);
        UUID entityId = BukkitStub.entityId();

        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), entityId);
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), entityId);

        assertEquals(List.of(entityId, entityId), fixture.deliveredEntityIds(),
            "两次调用都必须投递到实体 owner region，并以实体 UUID 为投递键");
        assertEquals(List.of("showEntity", "hideEntity"), fixture.playerCalls(),
            "玩家可见性 API 必须在实体 lane 内真实执行");
        assertEquals(0L, fixture.playerLaneSchedulerCalls(),
            "实体可见性不得排进 player lane：那里读实体状态会被 owner region 门禁拒绝");
    }

    /** 实体归当前 region：必须真正调用玩家 API，且不得产生任何告警。 */
    @Test
    void 归属本region时必须调用玩家API且不打日志() throws Exception {
        Fixture fixture = fixture(true);

        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());

        assertEquals(List.of("showEntity", "hideEntity"), fixture.playerCalls(),
            "实体归当前 region 时必须真实调用玩家可见性 API");
        assertEquals(0, fixture.logs().warningCount(),
            "正常归属路径不得打告警，否则限频日志会被噪声淹没");
    }

    /** 实体在投递后换了 region：必须跳过玩家 API（不是抛异常），但必须留下可排查的 WARNING。 */
    @Test
    void lane内归属不成立时必须零玩家调用且留痕() throws Exception {
        Fixture fixture = fixture(false);

        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());

        assertTrue(fixture.playerCalls().isEmpty(),
            "实体已不在本 region 时不能在错误 lane 访问实体，必须跳过而不是调用玩家 API");
        assertEquals(1, fixture.logs().warningCount(),
            "同一个实体的连续跳过必须在 30 秒窗口内限频成一条，否则会按玩家数刷屏");
        assertEquals(1, fixture.logs().mentions("showEntity"),
            "限频日志必须写明是哪一种可见性操作");
        assertEquals(1, fixture.logs().mentions(VIEWER_ID.toString()),
            "日志必须带被跳过的玩家 UUID，否则无法定位是谁的 HUD 没刷新");
        // 本条日志对应第 1 次跳过，它自己即将被打印、不算「被丢弃」，所以此刻窗口内被压掉的次数是 0。
        assertTrue(fixture.logs().hasMention("已静默丢弃 0 次"),
            "打日志的那一次不能把自己算成「被静默丢弃」");
    }

    /**
     * 玩家已离线：不调用玩家 API，也【不】留痕。
     *
     * <p>这是「降低重复警告」的另一半：改造前这类情况也走同一条告警分支，但它并不是故障
     * （没有客户端需要更新），按玩家数重算会把告警刷满。
     */
    @Test
    void 玩家离线时不调用玩家API且不留痕() throws Exception {
        Fixture fixture = fixture(true, ignored -> null);

        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());

        assertTrue(fixture.playerCalls().isEmpty(), "玩家离线时不得调用玩家 API");
        assertEquals(0, fixture.logs().warningCount(),
            "「玩家离线」是正常结果，不该留告警（否则会按玩家数刷屏）");
    }

    /** 实体已不存在：连投递都不该发生，也不留痕。 */
    @Test
    void 实体不存在时不投递且不留痕() throws Exception {
        Fixture fixture = fixture(true).withLane(Fixture.ABSENT_LANE);

        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());

        assertTrue(fixture.deliveredEntityIds().isEmpty(), "实体不存在时没有可见性状态可维护，不该投递");
        assertTrue(fixture.playerCalls().isEmpty(), "实体不存在时不得调用玩家 API");
        assertEquals(0, fixture.logs().warningCount(),
            "实体不存在是拆除/重建竞态下的正常结果，不该留告警");
    }

    /** 投递被拒绝（关闭期 / 调度已关闭）：必须留痕，且不能调用玩家 API。 */
    @Test
    void 投递被拒绝时限频留痕() throws Exception {
        Fixture fixture = fixture(true).withLane(Fixture.REJECTED_LANE);

        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());

        assertTrue(fixture.playerCalls().isEmpty(), "投递不成立时不得假装执行");
        assertEquals(1, fixture.logs().warningCount(),
            "投递被拒绝必须留痕，否则「没生效」在服务端完全不可见");
        assertTrue(fixture.logs().hasMention("投递被拒绝"), "日志必须说明是投递被拒，而不是笼统的「跳过」");
    }

    /** 投递接缝本身抛错：同样不得抛回调用方，且必须留痕。 */
    @Test
    void 投递接缝抛错不得外抛且限频留痕() throws Exception {
        Fixture fixture = fixture(true).withLane((entityId, task) -> {
            throw new IllegalStateException("scheduler closed");
        });

        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());

        assertTrue(fixture.playerCalls().isEmpty(), "投递失败时不得假装执行");
        assertEquals(1, fixture.logs().warningCount(), "投递异常必须留痕");
        assertTrue(fixture.logs().hasMention("投递实体 owner region 失败"), "日志必须点明是投递失败");
    }

    /**
     * 玩家可见性 API 抛错：不得抛回调用方，但必须留痕。
     *
     * <p>调用方是 {@code PhysicalTableManager} 的 owner tick / refresh；异常一旦外抛会打断整轮刷新，
     * 把一次可见性失败放大成整桌停更。
     */
    @Test
    void 玩家API抛错不得外抛且限频留痕() throws Exception {
        Fixture fixture = fixture(true, PlayerOutputDispatcherEntitySkipBehaviorTest::throwingViewer);

        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());

        assertEquals(1, fixture.logs().warningCount(), "玩家 API 抛错必须留痕");
        assertTrue(fixture.logs().hasMention("玩家可见性 API 抛错"), "日志必须点明是玩家 API 抛错");
    }

    /**
     * 限频不得变成丢信息：窗口内被压掉的次数必须在<b>下一次</b>日志里报出。
     *
     * <p>这条语义是 {@code reportEntityLaneSkip} javadoc 明确写下的（「把被限频窗口丢掉的次数随下一次
     * 日志一起报出」），但只靠真实等待无法在单测里跨越 30 秒窗口，所以夹具注入可控毫秒时钟把窗口推过去——
     * 否则该语义等于没有测试覆盖，只能靠读代码相信它。
     */
    @Test
    void 窗口内被压掉的次数必须随下一次日志报出() throws Exception {
        Fixture fixture = fixture(false);
        UUID entityId = BukkitStub.entityId();

        // 第一次跳过：打日志，报 0 次被丢弃。
        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), entityId);
        assertEquals(1, fixture.logs().warningCount(), "首次跳过必须先打出可排查的日志");

        // 窗口内再跳过两次：都被限频压掉，不新增日志。
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), entityId);
        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), entityId);
        assertEquals(1, fixture.logs().warningCount(),
            "同一实体的重复跳过在窗口内必须被压成一条，否则会按玩家数刷屏");

        // 把时钟推过限频窗口，再跳过一次：这一次必须重新打日志，并把窗口内压掉的 2 次一并报出。
        fixture.advanceClockMillis(31_000L);
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), entityId);
        assertEquals(2, fixture.logs().warningCount(),
            "窗口过去后必须能重新记录，否则该实体在整轮运行期都不可排查");
        assertTrue(fixture.logs().hasMention("已静默丢弃 2 次"),
            "被限频压掉的次数必须随下一次日志报出，不能变成丢信息");
    }

    /** 换一个实体必须能重新打日志：限频按实体 UUID 记账，不能被前一个实体的窗口压住。 */
    @Test
    void 限频按实体UUID记账且换实体可重新记录() throws Exception {
        Fixture fixture = fixture(false);

        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), BukkitStub.entityId());
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), OTHER_ENTITY_ID);

        assertEquals(2, fixture.logs().warningCount(),
            "不同实体的跳过必须各自记录一次，否则只有第一个实体可排查");
        assertEquals(1, fixture.logs().mentions(OTHER_ENTITY_ID.toString()), "新实体的日志必须带它自己的 UUID");
    }

    private static Fixture fixture(boolean ownsEntity) throws Exception {
        return fixture(ownsEntity, PlayerOutputDispatcherEntitySkipBehaviorTest::recordingViewer);
    }

    /**
     * 组装夹具：玩家解析结果与实体 lane 都可替换。
     *
     * <p>{@code viewerFactory} 收到夹具自己的「玩家 API 调用记录」列表，返回 {@code null} 表示玩家离线。
     */
    private static Fixture fixture(boolean ownsEntity, Function<List<String>, Player> viewerFactory)
        throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        CollectingLogger logs = new CollectingLogger();
        setField(plugin, "logger", logs.logger());
        BukkitStub.ownsEntity = ownsEntity;
        BukkitStub.install();

        List<String> playerCalls = new ArrayList<>();
        List<CapturedTask> playerLaneTasks = new ArrayList<>();
        Fixture fixture = new Fixture(plugin, logs, playerCalls, playerLaneTasks, new AtomicLong(1_000_000L));
        Player viewer = viewerFactory.apply(playerCalls);
        fixture.bindRegistry(viewer);
        return fixture.withLane(Fixture.inlineLane(fixture));
    }

    /** 记录 show/hide 调用的 viewer；其它方法返回类型默认值。 */
    private static Player recordingViewer(List<String> playerCalls) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("isOnline")) {
                    return true;
                }
                if (name.equals("showEntity") || name.equals("hideEntity")) {
                    playerCalls.add(name);
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    /** 可见性 API 直接抛错的 viewer：验证异常不会外抛给调用方。 */
    private static Player throwingViewer(List<String> ignoredCalls) {
        return (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("isOnline")) {
                    return true;
                }
                if (name.equals("showEntity") || name.equals("hideEntity")) {
                    throw new IllegalStateException("client refused");
                }
                return defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == float.class) {
            return 0.0f;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    /**
     * {@code Bukkit.isOwnedByCurrentRegion(entity)} 的真实来源。
     *
     * <p>Folia 上「实体属于别的 region」在调用方看来就是该判定为假。本机测试类路径没有可用的服务端注册表，
     * 所以用反射注入 {@code Bukkit} 的静态 server 字段：{@code isOwnedByCurrentRegion} 由
     * {@link #ownsEntity} 决定真假——这样能在不依赖 Folia 内核的前提下驱动生产代码的两条分支，
     * 而不是把生产判定逻辑抄进测试。实体本身由测试提供的 lane 直接交给回调（生产里那一步是
     * {@code Bukkit.getEntity} + 实体调度），所以桩不必再伪造实体表。
     */
    private static final class BukkitStub {
        private static final UUID ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        private static boolean ownsEntity = true;
        /** 首次安装时快照的原 {@code Bukkit.server}，供 {@link #uninstall()} 还原；未安装时为 {@code null}。 */
        private static Server originalServer;
        private static boolean installed;

        private static void install() throws Exception {
            if (!installed) {
                originalServer = readBukkitServer();
                installed = true;
            }
            Server server = (Server) Proxy.newProxyInstance(
                PlayerOutputDispatcherEntitySkipBehaviorTest.class.getClassLoader(),
                new Class<?>[] {Server.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isOwnedByCurrentRegion")) {
                        return ownsEntity;
                    }
                    return defaultValue(method.getReturnType());
                }
            );
            setField(Bukkit.class, "server", server);
        }

        /**
         * 还原安装前的 {@code Bukkit.server}，避免桩被后续测试类继承。
         *
         * <p>幂等且可重复安装：只有 {@link #install()} 确实改写过全局态时才还原一次，之后再次
         * {@code install()} 会重新快照当前值（在干净的测试 JVM 里就是 {@code null}）。
         */
        private static void uninstall() throws Exception {
            if (!installed) {
                return;
            }
            setField(Bukkit.class, "server", originalServer);
            installed = false;
            originalServer = null;
        }

        private static Server readBukkitServer() throws Exception {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            return (Server) field.get(null);
        }

        private static UUID entityId() {
            return ENTITY_ID;
        }

        private static Entity fakeEntity() {
            return (Entity) Proxy.newProxyInstance(
                ItemDisplay.class.getClassLoader(),
                new Class<?>[] {ItemDisplay.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return ENTITY_ID;
                        case "isValid":
                            return true;
                        case "toString":
                            return "fake-entity";
                        default:
                            return defaultValue(method.getReturnType());
                    }
                }
            );
        }
    }

    /** 记录型夹具：实体 lane 可替换，玩家 API 调用、投递键与日志都可观察。 */
    private static final class Fixture {
        /** 实体已不存在的 lane 替身。 */
        private static final PlayerOutputDispatcher.EntityRegionLane ABSENT_LANE =
            (entityId, task) -> PlayerOutputDispatcher.EntityRegionLane.Submission.ENTITY_ABSENT;
        /** 投递被拒绝（关闭期）的 lane 替身。 */
        private static final PlayerOutputDispatcher.EntityRegionLane REJECTED_LANE =
            (entityId, task) -> PlayerOutputDispatcher.EntityRegionLane.Submission.REJECTED;

        private final DoudizhuPlugin plugin;
        private final CollectingLogger logs;
        private final List<String> playerCalls;
        private final List<CapturedTask> playerLaneTasks;
        private final AtomicLong clock;
        private final List<UUID> deliveredEntityIds = new ArrayList<>();
        private PlayerTaskRegistry registry;
        private PlayerOutputDispatcher dispatcher;

        private Fixture(
            DoudizhuPlugin plugin,
            CollectingLogger logs,
            List<String> playerCalls,
            List<CapturedTask> playerLaneTasks,
            AtomicLong clock
        ) {
            this.plugin = plugin;
            this.logs = logs;
            this.playerCalls = playerCalls;
            this.playerLaneTasks = playerLaneTasks;
            this.clock = clock;
        }

        /**
         * 「已经在实体 owner region 上」的 lane 替身：直接执行并记录投递键。
         *
         * <p>这样测试能观察「投递到哪条 lane」，同时让 {@code Bukkit.isOwnedByCurrentRegion} 仍由
         * {@link BukkitStub} 真实驱动（而不是把生产判定抄进测试）。
         */
        private static PlayerOutputDispatcher.EntityRegionLane inlineLane(Fixture fixture) {
            return (entityId, task) -> {
                fixture.deliveredEntityIds.add(entityId);
                task.accept(BukkitStub.fakeEntity());
                return PlayerOutputDispatcher.EntityRegionLane.Submission.SUBMITTED;
            };
        }

        /** 绑定玩家解析结果；{@code null} 表示玩家离线。 */
        private void bindRegistry(Player viewer) {
            this.registry = new PlayerTaskRegistry(
                ignored -> viewer,
                (target, delay, task) -> {
                    // 生产实现把可见性投到实体 lane 后，这个分支一次都不该被走到。
                    CapturedTask captured = new CapturedTask(task);
                    playerLaneTasks.add(captured);
                    return captured;
                }
            );
        }

        private Fixture withLane(PlayerOutputDispatcher.EntityRegionLane lane) {
            this.dispatcher = new PlayerOutputDispatcher(registry, clock::get, lane);
            return this;
        }

        private DoudizhuPlugin plugin() {
            return plugin;
        }

        private PlayerOutputDispatcher dispatcher() {
            return dispatcher;
        }

        private CollectingLogger logs() {
            return logs;
        }

        private List<String> playerCalls() {
            return playerCalls;
        }

        private List<UUID> deliveredEntityIds() {
            return deliveredEntityIds;
        }

        /** player lane 被用于实体可见性的次数：正确实现里永远是 0。 */
        private long playerLaneSchedulerCalls() {
            return playerLaneTasks.size();
        }

        /** 把注入的毫秒时钟向前推进，用于跨过 30 秒限频窗口而不真实等待。 */
        private void advanceClockMillis(long millis) {
            clock.addAndGet(millis);
        }
    }

    private static final class CapturedTask implements MuzScheduler.TaskHandle {
        private final Runnable task;

        private CapturedTask(Runnable task) {
            this.task = task;
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public String ownerId() {
            return "entity-skip-test";
        }
    }

    private static final class CollectingLogger {
        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger = Logger.getLogger(
            "PlayerOutputDispatcherEntitySkipBehaviorTest-" + UUID.randomUUID());

        private CollectingLogger() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            Handler handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            handler.setLevel(Level.ALL);
            logger.addHandler(handler);
        }

        private Logger logger() {
            return logger;
        }

        private int warningCount() {
            return (int) records.stream()
                .filter(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
                .count();
        }

        private int mentions(String text) {
            return (int) records.stream()
                .filter(record -> record.getMessage() != null && record.getMessage().contains(text))
                .count();
        }

        /** 是否出现过包含该片段的日志（{@code mentions} 的同口径布尔版）。 */
        private boolean hasMention(String text) {
            return mentions(text) > 0;
        }
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    /**
     * 注入字段，同时支持实例字段与静态字段。
     *
     * <p>调用方要注入 {@code Bukkit.server}（静态字段，见 {@link BukkitStub#install()}），所以 target 可能是一个
     * {@link Class} 字面量。此时 {@code target.getClass()} 是 {@code java.lang.Class}，按它找 {@code server} 永远
     * 找不到——必须直接在传入的 {@code Class} 上查找，并按静态语义用 {@code field.set(null, value)} 写入。
     */
    private static void setField(Object target, String name, Object value) throws Exception {
        boolean isStatic = target instanceof Class<?>;
        Class<?> type = isStatic ? (Class<?>) target : target.getClass();
        Object receiver = isStatic ? null : target;
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(receiver, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
