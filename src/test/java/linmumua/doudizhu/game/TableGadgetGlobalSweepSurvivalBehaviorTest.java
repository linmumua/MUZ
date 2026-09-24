package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.scheduler.SchedulerBackend;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * TableManager 非并发 Map 被 global 扫描读取时的存活保证。
 *
 * <p>意图（对应仓库既有技术债）：{@code TableManager} 的 {@code tables} / {@code playerToTable} 仍是
 * 非并发 {@code LinkedHashMap}，全局 Map 迁移明确被推迟；而 {@code TableGadgetService.tick} 与
 * {@code TableGadgetBarHudService.prefetchActivePlayers} 都是注册在 **global lane** 的周期任务
 * （{@code plugin.scheduler().runTimer(...)} → {@code runGlobalTimer}），会用
 * {@code getTables()} 读取这张表。{@code getTables()} 内部是
 * {@code new ArrayList<>(tables.values())}：其它 lane（放置/重建/拆桌/恢复）在同一时刻做结构性修改时，
 * 这一步快照本身就可能抛 {@code ConcurrentModificationException} / 越界。
 *
 * <p>致命点在于 {@code MuzScheduler.schedule}：周期回调一旦抛异常就会走
 * {@code managed.fail(repeating=true)} → 取消后端句柄（并复抛给后端日志）。于是「读一次表快照失败」
 * 会升级成「整条扫描周期任务被永久取消」——所有桌的效果推进与目标高亮（或道具栏预取）静默失效且
 * 无法自愈。旧实现的 {@code for (GameTable table : tables())} 把这一步放在逐桌 try **之外**，兜不住。
 *
 * <p>本测试用「读 {@code values()} 就抛的表」把并发结构修改的后果做成确定性夹具，直接驱动生产方法
 * （{@code tick} / {@code prefetchActivePlayers} / 周期任务句柄），断言：
 * <ul>
 *   <li>表快照失败不得逃出 global 扫描／预取（逃出即被调度器取消整条周期任务）；</li>
 *   <li>逐桌 try 之外的收尾清理投递失败同样不得逃出；</li>
 *   <li>失败必须限频留痕（不得静默），且 manager 为空按既有语义安静早退。</li>
 * </ul>
 *
 * <p>夹具风格沿用仓库既有做法：{@link Unsafe} 分配的空心插件/牌桌 + 记录型调度后端
 * （见 {@code TableGadgetEffectReleaseBehaviorTest}、{@code ChunkLoadRepairPeriodicTaskSurvivalBehaviorTest}）。
 */
class TableGadgetGlobalSweepSurvivalBehaviorTest {
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000cc");

    /**
     * 表快照失败不得打死道具扫描周期任务。
     *
     * <p>旧实现把 {@code tables()} 放在逐桌 try 之外：{@code getTables()} 抛 CME 会直接逃出 {@code tick()}，
     * 被 {@code MuzScheduler} 取消整条 global 扫描任务。修复后本轮只跳过并限频记录。
     */
    @Test
    void 道具扫描在表快照失败时不得打死周期任务() throws Exception {
        GadgetSweepFixture fixture = gadgetSweepFixture(true);
        fixture.service.start();
        RecordedTask sweep = fixture.backend.lastGlobalTimer();
        assertNotNull(sweep, "道具扫描周期任务必须通过调度门面注册");

        assertDoesNotThrow(sweep::fire,
            "表快照失败不得逃出 global 扫描：一旦逃出，调度器会取消整条周期任务");
        assertDoesNotThrow(sweep::fire);

        assertFalse(sweep.cancelled(),
            "快照失败后扫描任务必须仍然存活，否则所有桌效果推进与目标高亮永久失效");
        assertEquals(1, fixture.logs.warningCount(), "失败必须限频记录：两次连续失败只应落一条日志");
        assertTrue(fixture.logs.contains("牌桌道具扫描异常"),
            "兜底日志必须能定位到「扫描异常」，不能是静默的空 catch");
    }

    /**
     * 逐桌 try 之外的收尾清理投递失败，同样不得打死扫描周期任务。
     *
     * <p>收尾循环 {@code output.runPlayer(...)} 在逐桌 try 之外；它一旦抛异常，旧实现会让整条 global
     * 扫描任务一起被打死。这里把 player lane 投递做成必然抛异常，断言外层兜底真的覆盖收尾阶段。
     */
    @Test
    void 道具扫描在收尾清理投递失败时不得打死周期任务() throws Exception {
        GadgetSweepFixture fixture = gadgetSweepFixture(true);
        fixture.service.start();
        RecordedTask sweep = fixture.backend.lastGlobalTimer();
        assertNotNull(sweep, "道具扫描周期任务必须通过调度门面注册");
        // 造一个「上一轮遗留的 actor 目标」：本轮无 eligible（无桌），收尾就会为该 actor 投递清理。
        trackTarget(fixture.service, ACTOR_ID);

        sweep.fire(); // tickCounter=1 → 非 sweep 轮，早退，不触碰收尾
        assertDoesNotThrow(sweep::fire, "收尾清理投递失败不得逃出 global 扫描");

        assertFalse(sweep.cancelled(), "收尾投递失败后扫描任务必须仍然存活");
        assertTrue(fixture.logs.contains("牌桌道具扫描异常"), "收尾失败同样必须被兜底并留痕");
    }

    /** manager 缺失时扫描按既有语义安静早退，不抛异常、也不刷日志。 */
    @Test
    void 道具扫描在牌桌管理器缺失时安静早退() throws Exception {
        GadgetSweepFixture fixture = gadgetSweepFixture(false);
        setField(fixture.plugin, "tableManager", null);
        fixture.service.start();
        RecordedTask sweep = fixture.backend.lastGlobalTimer();

        assertDoesNotThrow(sweep::fire);
        assertFalse(sweep.cancelled());
        assertEquals(0, fixture.logs.warningCount(), "manager 缺失是既有语义，不是失败，不应记警告");
    }

    /** 九格道具栏预取在表快照失败时不得抛异常，且必须限频留痕。 */
    @Test
    void 九格道具栏预取在表快照失败时不得抛异常并限频留痕() throws Exception {
        BarPrefetchFixture fixture = barPrefetchFixture(true);

        assertDoesNotThrow(fixture::invokePrefetch, "表快照失败不得逃出 global 预取任务");
        assertDoesNotThrow(fixture::invokePrefetch);

        assertEquals(1, fixture.logs.warningCount(), "失败必须限频记录：两次连续失败只应落一条日志");
        assertTrue(fixture.logs.contains("读取牌桌列表失败"),
            "预取失败必须留下可排查的日志，不能无声");
    }

    /**
     * 预取在牌桌管理器缺失时必须安静早退。
     *
     * <p>旧实现直接 {@code plugin.getTableManager().getTables()} 未判空，装配未完成或已清空时 NPE 会
     * 逃出周期任务并被调度器取消；这里按 {@code TableGadgetService.tick} 的同口径要求早退。
     */
    @Test
    void 九格道具栏预取在牌桌管理器缺失时不得抛异常() throws Exception {
        BarPrefetchFixture fixture = barPrefetchFixture(false);
        setField(fixture.plugin, "tableManager", null);

        assertDoesNotThrow(fixture::invokePrefetch, "manager 为空时必须安静早退，不能 NPE 打死预取任务");
        assertEquals(0, fixture.logs.warningCount(), "manager 缺失是既有语义，不应记警告");
    }

    /* ------------------------------------------------------------------ 夹具 ------------------------------------------------------------------ */

    /** 道具扫描夹具：真实 {@link TableManager} + 只登记不执行的调度后端。 */
    private static final class GadgetSweepFixture {
        private final DoudizhuPlugin plugin;
        private final TableGadgetService service;
        private final RecordingBackend backend;
        private final CollectingLogger logs;

        private GadgetSweepFixture(
            DoudizhuPlugin plugin,
            TableGadgetService service,
            RecordingBackend backend,
            CollectingLogger logs
        ) {
            this.plugin = plugin;
            this.service = service;
            this.backend = backend;
            this.logs = logs;
        }
    }

    /**
     * @param throwOnPlayerLane true 时 player lane 投递必然抛异常，用于验证收尾阶段的兜底。
     */
    private static GadgetSweepFixture gadgetSweepFixture(boolean throwOnPlayerLane) throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        CollectingLogger logs = new CollectingLogger();
        setField(plugin, "logger", logs.logger());
        // start() 会注册生命周期监听器，需要一个能吞下 registerEvents 的服务端替身。
        setField(plugin, "server", fakeServer());
        RecordingBackend backend = new RecordingBackend();
        setField(plugin, "scheduler", new MuzScheduler(backend));
        TableManager tableManager = new TableManager(plugin);
        setField(plugin, "tableManager", tableManager);
        // 先把表换成「读 values() 就抛」的替身，再登记一张桌：让夹具最接近真实（表里有桌，只是快照读失败）。
        @SuppressWarnings("unchecked")
        Map<String, GameTable> tables = (Map<String, GameTable>) failingSnapshotTables();
        setField(tableManager, "tables", tables);
        GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
        setField(table, "name", "snapshot-failing-table");
        tables.put("snapshot-failing-table", table);

        TableGadgetEffectService effects = new TableGadgetEffectService(plugin);
        PlayerOutputDispatcher output = new PlayerOutputDispatcher(PlayerTaskRegistry.uuidFirst(
            playerId -> null,
            (playerId, delayTicks, task) -> {
                if (throwOnPlayerLane) {
                    throw new IllegalStateException("模拟玩家任务投递失败");
                }
                return new RecordedHandle();
            }
        ));
        TableGadgetService service = new TableGadgetService(
            plugin, effects, new TableGadgetSettings(true, 6.0, 40, 10, 16, 32), output);
        return new GadgetSweepFixture(plugin, service, backend, logs);
    }

    /** 九格道具栏预取夹具：空心服务实例 + 直接反射驱动私有 prefetch 方法。 */
    private static final class BarPrefetchFixture {
        private final DoudizhuPlugin plugin;
        private final TableGadgetBarHudService service;
        private final CollectingLogger logs;

        private BarPrefetchFixture(DoudizhuPlugin plugin, TableGadgetBarHudService service, CollectingLogger logs) {
            this.plugin = plugin;
            this.service = service;
            this.logs = logs;
        }

        private void invokePrefetch() throws Exception {
            Method method = TableGadgetBarHudService.class.getDeclaredMethod("prefetchActivePlayers");
            method.setAccessible(true);
            try {
                method.invoke(service);
            } catch (java.lang.reflect.InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw exception;
            }
        }
    }

    private static BarPrefetchFixture barPrefetchFixture(boolean failingSnapshot) throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        CollectingLogger logs = new CollectingLogger();
        setField(plugin, "logger", logs.logger());
        setField(plugin, "scheduler", new MuzScheduler(new RecordingBackend()));
        TableManager tableManager = new TableManager(plugin);
        if (failingSnapshot) {
            setField(tableManager, "tables", failingSnapshotTables());
        }
        setField(plugin, "tableManager", tableManager);

        // prefetchActivePlayers 的失败路径只读 plugin（getTableManager/getLogger），其余依赖不会被触达，
        // 因此用空心实例即可，无需构造 store/gadgets/offsetService/voiceOpener。
        TableGadgetBarHudService service =
            (TableGadgetBarHudService) unsafe().allocateInstance(TableGadgetBarHudService.class);
        setField(service, "plugin", plugin);
        return new BarPrefetchFixture(plugin, service, logs);
    }

    /**
     * 读 {@code values()} 就抛的表，模拟并发结构修改下 {@code getTables()} 的快照失败。
     *
     * <p>{@code get/put/containsKey} 仍走正常 {@code LinkedHashMap} 语义，因此夹具登记桌、以及
     * {@code tables.get(key)} 之类的非快照读取都不受影响——被破坏的只有会遍历的 {@code values()}，
     * 这正是真实并发场景里 CME 的触发点。
     */
    private static Map<String, GameTable> failingSnapshotTables() {
        return new LinkedHashMap<>() {
            @Override
            public Collection<GameTable> values() {
                throw new ConcurrentModificationException("模拟 getTables 快照读取失败");
            }
        };
    }

    /** 反射登记一条真实 {@code TargetState}：记录是私有内部类，测试无法直接构造。 */
    @SuppressWarnings("unchecked")
    private static void trackTarget(TableGadgetService service, UUID actorId) throws Exception {
        Class<?> stateType = Class.forName("linmumua.doudizhu.game.TableGadgetService$TargetState");
        var constructor = stateType.getDeclaredConstructor(UUID.class);
        constructor.setAccessible(true);
        Object state = constructor.newInstance(UUID.randomUUID());
        Field field = TableGadgetService.class.getDeclaredField("targets");
        field.setAccessible(true);
        ((Map<UUID, Object>) field.get(service)).put(actorId, state);
    }

    private static Server fakeServer() {
        ClassLoader loader = TableGadgetGlobalSweepSurvivalBehaviorTest.class.getClassLoader();
        PluginManager pluginManager = (PluginManager) Proxy.newProxyInstance(
            loader, new Class<?>[] {PluginManager.class}, (proxy, method, args) -> switch (method.getName()) {
                case "toString" -> "fakePluginManager";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
        return (Server) Proxy.newProxyInstance(loader, new Class<?>[] {Server.class}, (proxy, method, args) ->
            switch (method.getName()) {
                case "getPluginManager" -> pluginManager;
                case "toString" -> "fakeServer";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }

    /** 只登记、由测试自己 fire 的调度后端；不替真实调度器做任何异常/取消决策。 */
    private static final class RecordingBackend implements SchedulerBackend {
        private final List<RecordedTask> globalTimers = new ArrayList<>();

        private RecordedTask lastGlobalTimer() {
            return globalTimers.get(globalTimers.size() - 1);
        }

        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            if (period == 0L) {
                // 一次性 global 任务：夹具只返回句柄，不驱动闭包（测试不关心牌桌 owner 派发）。
                return new RecordedHandle();
            }
            RecordedTask recorded = new RecordedTask(task);
            globalTimers.add(recorded);
            return recorded.handle;
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

    private static final class RecordedTask {
        private final Consumer<MuzScheduler.TaskHandle> callback;
        private final RecordedHandle handle = new RecordedHandle();

        private RecordedTask(Consumer<MuzScheduler.TaskHandle> callback) {
            this.callback = callback;
        }

        private void fire() {
            callback.accept(handle);
        }

        private boolean cancelled() {
            return handle.isCancelled();
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
            return "sweep-survival-test";
        }
    }

    /** 收集日志的真实 Logger：断言「失败必须限频记录」时用它统计 WARNING 条数。 */
    private static final class CollectingLogger {
        private final List<String> messages = new ArrayList<>();
        private final List<Level> levels = new ArrayList<>();
        private final Logger logger = Logger.getLogger("TableGadgetGlobalSweepSurvivalTest-" + UUID.randomUUID());

        private CollectingLogger() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getMessage() != null) {
                        messages.add(record.getMessage());
                        levels.add(record.getLevel());
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

        private int warningCount() {
            int total = 0;
            for (Level level : levels) {
                if (level.intValue() >= Level.WARNING.intValue()) {
                    total++;
                }
            }
            return total;
        }

        private boolean contains(String fragment) {
            for (String message : messages) {
                if (message.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
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
