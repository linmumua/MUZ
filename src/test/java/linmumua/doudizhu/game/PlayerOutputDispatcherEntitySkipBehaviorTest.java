package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * @author linmumua
 * @Desc PlayerOutputDispatcher 的 showEntity/hideEntity 在「跨 region 跳过」时必须留下可排查痕迹
 * @date 2026-09-23
 *
 * <p>意图：这两个方法此前把「实体不存在 / 已失效 / 不归当前 region」合并成一个静默 {@code if}，
 * 实服表现是「某个玩家的桌边动态或座位名没按预期显示/隐藏」而服务端毫无痕迹——与本项目此前修过的
 * 「道具投掷卡住且无日志」是同一类可排查性缺陷。跨 region 跳过本身是 Folia 语义下的正常结果
 * （实体本就不在该玩家追踪范围，调用方会在下一次 refresh / owner tick 重算），所以不能改成抛异常，
 * 只能补限频痕迹。
 *
 * <p>本测试钉四条语义：
 * <ol>
 *   <li>正常归属（{@code isOwnedByCurrentRegion} 为真）时调用玩家 API 且【不】打日志，避免把正常路径噪化；</li>
 *   <li>跨 region 跳过必须调用 {@link Player} 的 show/hide <b>零次</b>（不能靠抛异常来暴露问题），
 *       同时必须留下 WARNING；</li>
 *   <li>同一个实体 UUID 重复跳过必须限频——这是本类的真实放大形态：
 *       {@code PhysicalTableManager} 对每个在线玩家 × 每个实体显式调一次 show/hide，且每张桌的
 *       owner tick 每 2 秒重算一遍，不限频就会按「玩家数 × 每 2 秒」刷屏；</li>
 *   <li>限频不得变成丢信息：窗口内被压掉的次数必须随<b>下一次</b>日志报出，且窗口过去后该实体必须能
 *       重新记录（否则一次跳过之后整轮运行期都不可排查）。这条需要推进注入的毫秒时钟来验证。</li>
 * </ol>
 *
 * <p>夹具风格沿用仓库既有做法：{@link Unsafe} 分配的空心 {@link DoudizhuPlugin} 注入收集型
 * {@link Logger}（见 {@code TableGadgetEffectReleaseBehaviorTest}），记录型
 * {@link PlayerTaskRegistry} 让测试手动 fire 闭包（见 {@code PlayerOutputDispatcherTest}），
 * 毫秒时钟经 {@code PlayerOutputDispatcher} 构造器注入（见 {@code TableSpeechPanelService} 的 tickSource）。
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

    /**
     * 每个用例结束后还原 {@link Bukkit} 的静态 {@code server} 字段，避免本测试的桩污染同 JVM 的其它测试。
     *
     * <p>用 {@code @AfterEach} 而不是 {@code @AfterAll}：四个用例都各自 {@link BukkitStub#install()}，
     * 逐用例还原才能保证任一用例失败时也不会把桩留下来。
     */
    @org.junit.jupiter.api.AfterEach
    void restoreBukkitServer() throws Exception {
        BukkitStub.uninstall();
    }

    /** 实体归当前 region：必须真正调用玩家 API，且不得产生任何告警。 */
    @Test
    void 归属本region时必须调用玩家API且不打日志() throws Exception {
        Fixture fixture = fixture(true);
        UUID entityId = BukkitStub.entityId();
        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), entityId);
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), entityId);
        fixture.fireAll();

        assertEquals(List.of("showEntity", "hideEntity"), fixture.playerCalls(),
            "实体归当前 region 时必须真实调用玩家可见性 API");
        assertEquals(0, fixture.logs().warningCount(),
            "正常归属路径不得打告警，否则限频日志会被噪声淹没");
    }

    /** 跨 region：必须跳过玩家 API（不是抛异常），但必须留下可排查的 WARNING。 */
    @Test
    void 跨region跳过必须零玩家调用且留痕() throws Exception {
        Fixture fixture = fixture(false);
        UUID entityId = BukkitStub.entityId();
        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), entityId);
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), entityId);
        fixture.fireAll();

        assertTrue(fixture.playerCalls().isEmpty(),
            "跨 region 的实体不能在玩家 lane 访问，必须跳过而不是调用玩家 API");
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
     * 限频不得变成丢信息：窗口内被压掉的次数必须在<b>下一次</b>日志里报出。
     *
     * <p>这条语义是 {@code reportCrossRegionSkip} javadoc 明确写下的（「把被限频窗口丢掉的次数随下一次日志
     * 一起报出」），但只靠真实等待无法在单测里跨越 30 秒窗口，所以夹具注入可控毫秒时钟把窗口推过去——
     * 否则该语义等于没有测试覆盖，只能靠读代码相信它。
     */
    @Test
    void 窗口内被压掉的次数必须随下一次日志报出() throws Exception {
        Fixture fixture = fixture(false);
        UUID entityId = BukkitStub.entityId();
        // 第一次跳过：打日志，报 0 次被丢弃。
        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), entityId);
        fixture.fireAll();
        assertEquals(1, fixture.logs().warningCount(), "首次跳过必须先打出可排查的日志");

        // 窗口内再跳过两次：都被限频压掉，不新增日志。
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), entityId);
        fixture.dispatcher().showEntity(VIEWER_ID, fixture.plugin(), entityId);
        fixture.fireAll();
        assertEquals(1, fixture.logs().warningCount(),
            "同一实体的重复跳过在窗口内必须被压成一条，否则会按玩家数刷屏");

        // 把时钟推过限频窗口，再跳过一次：这一次必须重新打日志，并把窗口内压掉的 2 次一并报出。
        fixture.advanceClockMillis(31_000L);
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), entityId);
        fixture.fireAll();
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
        fixture.fireAll();
        UUID another = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        fixture.dispatcher().hideEntity(VIEWER_ID, fixture.plugin(), another);
        fixture.fireAll();

        assertEquals(2, fixture.logs().warningCount(),
            "不同实体的跳过必须各自记录一次，否则只有第一个实体可排查");
        assertEquals(1, fixture.logs().mentions(another.toString()), "新实体的日志必须带它自己的 UUID");
    }

    private static Fixture fixture(boolean ownsEntity) throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        CollectingLogger logs = new CollectingLogger();
        setField(plugin, "logger", logs.logger());
        BukkitStub.ownsEntity = ownsEntity;
        BukkitStub.install();
        List<String> playerCalls = new ArrayList<>();
        Player viewer = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("showEntity") || name.equals("hideEntity")) {
                    playerCalls.add(name);
                }
                return defaultValue(method.getReturnType());
            }
        );
        // 生产路径在 player lane 内用 Bukkit.getPlayer 重新解析玩家；这里必须让解析器返回 fake viewer，
        // 否则闭包会在「玩家已离线」分支提前返回，跳过分支根本不会被执行。
        // 解析器经构造器注入（与 PlayerOutputDispatcherTest 同法）：该字段是 final，不属于可注入的测试接缝，
        // 反射改写 final 字段在 JDK 17+ 会抛 IllegalArgumentException，不能靠 setField 绕过。
        List<CapturedTask> scheduled = new ArrayList<>();
        PlayerTaskRegistry registry = new PlayerTaskRegistry(
            ignored -> viewer,
            (target, delay, task) -> {
                CapturedTask captured = new CapturedTask(task);
                scheduled.add(captured);
                return captured;
            }
        );
        // 可控毫秒时钟：限频窗口是 30 秒真实时间，只有注入时钟才能在不睡眠的前提下验证
        // 「窗口过去后能重新记录」以及「被压掉的次数随下一次日志报出」。
        AtomicLong clock = new AtomicLong(1_000_000L);
        PlayerOutputDispatcher dispatcher = new PlayerOutputDispatcher(registry, clock::get);
        return new Fixture(plugin, dispatcher, logs, scheduled, playerCalls, clock);
    }

    /**
     * 以「服务端不提供实体」的方式模拟跨 region 跳过。
     *
     * <p>生产代码在 player lane 内调 {@code Bukkit.getEntity(entityId)}，再用
     * {@code Bukkit.isOwnedByCurrentRegion(entity)} 判定归属。Folia 上「实体属于别的 region」在调用方
     * 看来与「实体不存在」走的是同一个跳过分支（条件为假即跳过）。本机测试类路径没有可用的服务端注册表，
     * 所以用反射注入 {@code Bukkit} 的静态 server 字段：{@code getEntity} 由 fake server 决定返回假实体
     * 还是 null，{@code isOwnedByCurrentRegion} 由 {@link #ownsEntity} 决定真假——这样能在不依赖 Folia
     * 内核的前提下驱动生产代码的两条分支，而不是把生产判定逻辑抄进测试。
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
                    if (method.getName().equals("getEntity")) {
                        return ownsEntity ? fakeEntity() : null;
                    }
                    if (method.getName().equals("isOwnedByCurrentRegion")) {
                        return ownsEntity;
                    }
                    return PlayerOutputDispatcherEntitySkipBehaviorTest.defaultValue(method.getReturnType());
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
                            return PlayerOutputDispatcherEntitySkipBehaviorTest.defaultValue(method.getReturnType());
                    }
                }
            );
        }
    }

    /** 记录型调度：只登记闭包，由测试自己 fire，保证断言发生在闭包真正执行之后。 */
    private static final class Fixture {
        private final DoudizhuPlugin plugin;
        private final PlayerOutputDispatcher dispatcher;
        private final CollectingLogger logs;
        private final List<CapturedTask> scheduled;
        private final List<String> playerCalls;
        private final AtomicLong clock;

        private Fixture(
            DoudizhuPlugin plugin,
            PlayerOutputDispatcher dispatcher,
            CollectingLogger logs,
            List<CapturedTask> scheduled,
            List<String> playerCalls,
            AtomicLong clock
        ) {
            this.plugin = plugin;
            this.dispatcher = dispatcher;
            this.logs = logs;
            this.scheduled = scheduled;
            this.playerCalls = playerCalls;
            this.clock = clock;
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

        /** 把注入的毫秒时钟向前推进，用于跨过 30 秒限频窗口而不真实等待。 */
        private void advanceClockMillis(long millis) {
            clock.addAndGet(millis);
        }

        private void fireAll() {
            for (CapturedTask task : scheduled) {
                task.fire();
            }
        }
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

    private static final class CapturedTask implements MuzScheduler.TaskHandle {
        private final Runnable task;

        private CapturedTask(Runnable task) {
            this.task = task;
        }

        private void fire() {
            task.run();
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
