package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.scheduler.SchedulerBackend;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 九格道具栏重画在「牌桌已注销」时必须静默丢弃，而不是把异常抛给调度器。
 *
 * <p>意图（对应实服 Lophine 26.2 的真实日志）：
 * <pre>
 * [MUZ] Entity task for MUZ v1.10.50 generated an exception
 *   java.lang.IllegalArgumentException: 牌桌尚未注册: 3
 *     at TableManager.runTableLater(213) ← runTableNow(231)
 *     ← TableGadgetBarHudService.lambda$redrawHud$0(318)
 *     ← PlayerTaskRegistry.dispatch
 * </pre>
 * {@code redrawHud} 先把闭包投到 player lane，闭包执行时再调 {@code runTableNow}。拆桌 / 回 LOBBY
 * 与选槽回调交错时，牌桌可能在「投递之后、执行之前」被注销，于是 {@code runTableNow} 抛
 * {@code IllegalArgumentException}。这条异常从 player lane 的闭包里逃出去，被调度器记成
 * 「Entity task … generated an exception」，在日志里持续刷屏；而这次重画本来就【没有任何意义】
 * ——桌已经不存在了。
 *
 * <p>所以本测试钉两条语义：
 * <ol>
 *   <li>投递【之前】牌桌已注销：一次都不该往 player lane 投递（注册检查在投递之前）；</li>
 *   <li>投递【之后、执行之前】牌桌被注销（真实 TOCTOU 窗口）：{@code runTableNow} 抛出的
 *       异常必须被吞掉，绝不能逃到调度器。</li>
 * </ol>
 *
 * <p>夹具风格沿用仓库既有做法：{@link Unsafe} 分配的空心 {@link GameTable}/{@link DoudizhuPlugin}
 * 加记录型调度后端（见 {@code TableGadgetEffectReleaseBehaviorTest}、
 * {@code ChunkLoadRepairPeriodicTaskSurvivalBehaviorTest}）。{@link GameTable} 是 final 类，
 * 无法用子类观察，所以可观察量取「玩家任务是否被投递」与「异常是否逃逸」。
 */
class TableGadgetBarHudRedrawRegistrationTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    /** 牌桌仍在注册表里时，重画必须被真实投递到 player lane 并执行。 */
    @Test
    void registeredTableRedrawIsDispatched() throws Exception {
        Fixture fixture = fixture("registered-table");

        fixture.service.clearTable(fixture.table);

        assertEquals(1, fixture.enqueues().get(), "已注册牌桌的重画必须投递到 player lane");
        fixture.drain();
        assertEquals(1, fixture.executions().get(), "已注册牌桌的重画必须真正执行");
    }

    /** 牌桌已注销时，必须在投递之前就放弃——不投递、不执行、也不报错。 */
    @Test
    void unregisteredTableIsNotDispatchedAtAll() throws Exception {
        Fixture fixture = fixture("gone-table");
        // 模拟拆桌：牌桌已从注册表摘除，但调用方还握着旧实例。
        fixture.unregister();

        assertDoesNotThrow(() -> fixture.service.clearTable(fixture.table));

        assertEquals(0, fixture.enqueues().get(),
            "牌桌已注销时不该再往 player lane 投递（投递后必然在 runTableNow 抛“牌桌尚未注册”）");
        fixture.drain();
        assertEquals(0, fixture.executions().get(), "注销后的重画不该有任何副作用");
    }

    /**
     * 真实 TOCTOU 窗口：注册检查通过、任务已入队，随后牌桌被注销，任务才执行。
     *
     * <p>此时 {@code runTableNow} 会抛 {@code IllegalArgumentException}。这条异常【必须】被
     * 静默吞掉；否则它会从 player lane 闭包逃到调度器，把玩家/周期任务打挂（实服日志里的
     * “Entity task … generated an exception”正是这个形态）。
     */
    @Test
    void unregisterAfterDispatchThrowsNothingToTheScheduler() throws Exception {
        Fixture fixture = fixture("toctou-table");

        fixture.service.clearTable(fixture.table);
        assertEquals(1, fixture.enqueues().get(), "前置条件：任务应已入队");
        // 任务入队之后、执行之前，牌桌被别的路径注销。
        fixture.unregister();

        // 这一步会真实执行玩家任务，闭包里的 runTableNow 此时必然抛 IllegalArgumentException。
        // 断言「没有异常逃出来」就是本测试的核心：生产代码必须把它吞掉，
        // 否则实服会持续刷 “Entity task … generated an exception”。
        assertDoesNotThrow(fixture::drain,
            "牌桌在投递与执行之间注销时，runTableNow 的 IllegalArgumentException 不得逃到调度器");
        // 任务本身确实跑了（不是被跳过的空断言），但在桌已注销的前提下不产生任何副作用。
        assertEquals(1, fixture.executions().get(), "前置条件：闭包应被真实执行过");
    }

    /** null 牌桌不得触发任何投递（既有防御语义不许回退）。 */
    @Test
    void nullTableIsIgnored() throws Exception {
        Fixture fixture = fixture("null-guard-table");

        assertDoesNotThrow(() -> fixture.service.clearTable(null));

        assertEquals(0, fixture.enqueues().get(), "null 牌桌不该投递任何玩家任务");
    }

    /**
     * 被吞掉的异常必须留痕，且按「桌 + 玩家」限频——不能退回空 catch。
     *
     * <p>意图：这条竞态本身无需处理，但它同时是「重画链路整体打不通」的唯一信号（后端持续返回已终止句柄、
     * 关闭期注册被拒……）。旧写法整段空 catch，实服表现为「选框不跟着动、九格栏不再更新」而服务端毫无痕迹，
     * 与桌内道具投掷那次「静默返回 false 导致无日志」同类。所以：吞掉可以，无声不行。
     *
     * <p>限频按「桌 + 玩家」而不是整体一次，是因为 {@code clearTable} 会遍历全部座位；
     * 若只记一次，"哪个玩家/哪张桌"这条最有用的定位信息就丢了。
     */
    @Test
    void skippedRedrawIsLoggedOncePerTableAndPlayer() throws Exception {
        Fixture fixture = fixture("logged-toctou-table");

        fixture.service.clearTable(fixture.table);
        fixture.unregister();
        fixture.drain();

        assertEquals(1, fixture.logs.count("跳过桌内道具栏重画"),
            "被吞掉的注册异常必须留下一条可排查的日志，不能是完全静默的空 catch");
        assertTrue(fixture.logs.contains("logged-toctou-table"),
            "日志必须带上桌名，否则无法定位是哪张桌的重画链路失效：" + fixture.logs.messages());

        // 同一桌同一玩家的第二次跳过不再记录：这条路径在拆桌/回 LOBBY 时会随座位遍历反复触发。
        fixture.service.clearTable(fixture.table);
        fixture.service.clearTable(fixture.table);
        fixture.drain();
        assertEquals(1, fixture.logs.count("跳过桌内道具栏重画"),
            "同一「桌 + 玩家」的跳过告警必须限频，否则会给日志刷屏");
    }

    /** message 为 null 的异常也必须能描述，不能只留下 “: null”。 */
    @Test
    void failureDescriptionNeverDegeneratesToNull() throws Exception {
        Method describeFailure = TableGadgetBarHudService.class.getDeclaredMethod(
            "describeFailure", Throwable.class);
        describeFailure.setAccessible(true);

        String text = (String) describeFailure.invoke(null, new IllegalStateException());
        assertTrue(text.contains("IllegalStateException"),
            "message 为 null 时必须退回到异常类型，不能只留 null：" + text);

        String withMessage = (String) describeFailure.invoke(null, new IllegalStateException("牌桌尚未注册"));
        assertEquals("牌桌尚未注册", withMessage, "有 message 时必须原样保留");
    }

    private static Fixture fixture(String tableName) throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        CollectingLogs logs = new CollectingLogs();
        setField(plugin, "logger", logs.logger());
        // TableManager 构造器要读 plugin.scheduler()，所以调度器必须先就位。
        // 后端只登记、不执行闭包，因此牌桌 owner 任务不会真的落到世界里。
        setField(plugin, "scheduler", new MuzScheduler(new QuietBackend()));
        TableManager tableManager = new TableManager(plugin);
        setField(plugin, "tableManager", tableManager);
        TableManager finalManager = tableManager;

        GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
        setField(table, "name", tableName);
        // clearTable 先遍历座位再逐人重画，所以空心桌必须有一个座位（用不可变快照避免额外依赖）。
        setField(table, "seats", List.of(PLAYER_ID));
        putTable(tableManager, tableName, table);

        RecordingScheduler recording = new RecordingScheduler();
        ActionBarOverlayService overlay = (ActionBarOverlayService)
            unsafe().allocateInstance(ActionBarOverlayService.class);
        setField(overlay, "output", new PlayerOutputDispatcher(recording.registry()));
        setField(overlay, "overlays", new LinkedHashMap<UUID, Object>());

        TableGadgetBarHudService service = (TableGadgetBarHudService)
            unsafe().allocateInstance(TableGadgetBarHudService.class);
        // redrawHud 只读 actionBarOverlay.outputDispatcher() 与 plugin.getTableManager()，
        // 其余依赖（store/gadgets/offsetService/voiceOpener）在本路径上不会被触达。
        setField(service, "plugin", plugin);
        setField(service, "actionBarOverlay", overlay);
        setField(service, "bars", new java.util.concurrent.ConcurrentHashMap<>());
        setField(service, "selectedSlots", new java.util.concurrent.ConcurrentHashMap<>());
        setField(service, "loading", new java.util.concurrent.ConcurrentHashMap<>());
        setField(service, "refreshStates", new java.util.concurrent.ConcurrentHashMap<>());
        // 跳过告警在真实构造器里初始化；这里是空心实例，必须显式补上，否则 reportRedrawSkipped 会 NPE。
        setField(service, "redrawSkipLogs", java.util.concurrent.ConcurrentHashMap.<String>newKeySet());

        return new Fixture(service, table, finalManager, tableName, recording, logs);
    }

    private static final class Fixture {
        private final TableGadgetBarHudService service;
        private final GameTable table;
        private final TableManager tableManager;
        private final String tableName;
        private final RecordingScheduler recording;
        private final CollectingLogs logs;

        private Fixture(
            TableGadgetBarHudService service,
            GameTable table,
            TableManager tableManager,
            String tableName,
            RecordingScheduler recording,
            CollectingLogs logs
        ) {
            this.service = service;
            this.table = table;
            this.tableManager = tableManager;
            this.tableName = tableName;
            this.recording = recording;
            this.logs = logs;
        }

        /** 模拟拆桌：把牌桌从注册表摘除（走生产路径 unregisterTable）。 */
        private void unregister() {
            tableManager.unregisterTable(tableName);
        }

        private AtomicInteger enqueues() {
            return recording.enqueues;
        }

        private AtomicInteger executions() {
            return recording.executions;
        }

        /** 执行所有已入队的玩家任务，复现「投递之后、执行之前」的时间差。 */
        private void drain() {
            recording.drain();
        }
    }

    /**
     * 记录型 UUID 玩家调度器：入队时不解引用闭包，只登记；{@link #drain()} 时再执行。
     *
     * <p>「登记」与「执行」分离是本测试的关键——真实的 TOCTOU 窗口就发生在这两步之间。
     */
    private static final class RecordingScheduler {
        private final AtomicInteger enqueues = new AtomicInteger();
        private final AtomicInteger executions = new AtomicInteger();
        private final List<Runnable> pending = new ArrayList<>();

        private PlayerTaskRegistry registry() {
            return PlayerTaskRegistry.uuidFirst(
                // 解析器只返回在线玩家；本测试用 fake Player，故始终解析成功。
                playerId -> PLAYER_ID.equals(playerId) ? fakePlayer(PLAYER_ID) : null,
                (playerId, delayTicks, task) -> {
                    enqueues.incrementAndGet();
                    pending.add(task);
                    return new RecordedHandle();
                });
        }

        private void drain() {
            List<Runnable> snapshot = new ArrayList<>(pending);
            pending.clear();
            for (Runnable task : snapshot) {
                // 只统计【正常跑完】的任务：抛异常的玩家任务在实服会被调度器记成
                // “generated an exception”，语义上并没有「生效」，不能算作执行成功。
                task.run();
                executions.incrementAndGet();
            }
        }
    }

    private static Player fakePlayer(UUID playerId) {
        return (Player) Proxy.newProxyInstance(
            TableGadgetBarHudRedrawRegistrationTest.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getUniqueId" -> playerId;
                case "isOnline" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "fake-player:" + playerId;
                default -> null;
            });
    }

    /** 收集日志文本的 handler（沿用 CraftEngineFurnitureRegionGateTest 的既有做法）。 */
    private static final class CollectingLogs {
        private final List<String> messages = new ArrayList<>();
        private final Logger logger = Logger.getLogger("muz-redraw-test-" + UUID.randomUUID());

        private CollectingLogs() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getMessage() != null) {
                        messages.add(record.getMessage());
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
        }

        private Logger logger() {
            return logger;
        }

        private List<String> messages() {
            return new ArrayList<>(messages);
        }

        private boolean contains(String fragment) {
            return count(fragment) > 0;
        }

        private int count(String fragment) {
            int total = 0;
            for (String message : messages) {
                if (message.contains(fragment)) {
                    total++;
                }
            }
            return total;
        }
    }

    private static final class RecordedHandle implements MuzScheduler.TaskHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public String ownerId() {
            return "redraw-test";
        }
    }

    /**
     * 只登记、不执行的调度后端。
     *
     * <p>注册表外层的闭包由 {@link RecordingScheduler} 记录并手动 drain；牌桌 owner 侧的一次性任务
     * 由这个后端吞掉（返回句柄但不运行），使空心 {@link GameTable} 不会被真正驱动，
     * 从而把测试焦点保持在「注册检查」与「异常不逃逸」两条语义上。
     */
    private static final class QuietBackend implements SchedulerBackend {
        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            return new RecordedHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runRegion(
            Location location,
            long delay,
            long period,
            Consumer<MuzScheduler.TaskHandle> task
        ) {
            return new RecordedHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runEntity(
            Entity entity,
            long delay,
            long period,
            Consumer<MuzScheduler.TaskHandle> task
        ) {
            return new RecordedHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runPlayer(
            Player player,
            long delay,
            long period,
            Consumer<MuzScheduler.TaskHandle> task
        ) {
            return new RecordedHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runAsync(long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            return new RecordedHandle();
        }
    }

    @SuppressWarnings("unchecked")
    private static void putTable(TableManager manager, String key, GameTable table) throws Exception {
        Field field = TableManager.class.getDeclaredField("tables");
        field.setAccessible(true);
        ((Map<String, GameTable>) field.get(manager)).put(key, table);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
