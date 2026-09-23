package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.GameTable;
import linmumua.doudizhu.game.TableManager;
import linmumua.doudizhu.game.TablePeriodicTaskRegistry;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.scheduler.SchedulerBackend;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * ChunkLoad 修复期间的周期任务存活行为测试。
 *
 * <p>意图：{@code PhysicalTableManager.repairTableAfterChunkLoad} 曾经在破坏性摘除放置状态前调用
 * {@code TableManager.cancelOwnerPeriodicTasks(key)}，而该方法会把 {@link TablePeriodicTaskRegistry}
 * 里的条目**永久摘除**（{@code cancel}/{@code cancelOwner} 会 {@code entries.remove} + {@code cancelled=true}）：
 * <ul>
 *   <li>{@code ownerPeriodicTasks} 里的开局发牌 timer（{@code GameTable → TableManager.runTableTimer}）
 *       没有任何重新注册路径，修复一次就把整条发牌时间线永久打死；</li>
 *   <li>{@code periodicTasks} 里的 {@code tickActionBar} 同样消失，使修复收口的
 *       {@code notifyTableAnchorBinding} 抛「牌桌周期任务未重绑定」并跳过刷新。</li>
 * </ul>
 *
 * <p>这里用记录型调度后端直接驱动**生产方法**（{@code repairTableAfterChunkLoad}、
 * {@code TableManager.rebindTablePeriodicTask}）验证修复的新语义：离开旧 owner lane 靠
 * {@code markTableUnplaced} 的原子 rebind 完成，周期任务被保留，并在修复成功后重绑到新锚点。
 * 夹具沿用仓库既有做法：{@link Unsafe} 分配 {@link DoudizhuPlugin} + 最小 {@code PluginMeta} 桩，
 * 再注入只登记、不自动执行的调度后端替身。
 */
class ChunkLoadRepairPeriodicTaskSurvivalBehaviorTest {
    /**
     * 强引用夹具 Proxy world。
     *
     * <p>Paper 26.x 的 {@link Location} 只用 {@link java.lang.ref.WeakReference} 持有 world，
     * 若这里不额外持强引用，套件运行期间该 Proxy 可能被 GC 回收，使 {@code Location.getWorld()}
     * 抛 {@code IllegalArgumentException: World unloaded}（本测试会在
     * {@code TablePeriodicTaskRegistry.AnchorKey.of} 里命中）。这是夹具自身的 GC 稳定性保障，
     * 不改变生产代码语义。
     */
    private static final List<World> WORLD_STRONG_REFS = new ArrayList<>();

    /**
     * 修复成功路径：摘除放置状态后，两个周期任务都必须仍然登记，收口重绑必须成功，
     * 且发牌 timer 真的跑在新锚点 region 上；旧 global 句柄不得再执行。
     */
    @Test
    void 修复摘除放置状态后周期任务仍登记且收口重绑到新锚点并继续执行() throws Exception {
        Fixture fixture = fixture();
        GameTable table = fixture.registerTable("table-a");
        Counters counters = fixture.attachPeriodicTasks(table);

        assertEquals(1, fixture.periodicTaskCount(), "ActionBar 周期任务必须已登记");
        assertEquals(1, fixture.ownerPeriodicTaskCount(), "开局发牌 timer 必须已登记");
        RecordedTask actionBarBefore = fixture.backend.lastGlobalTimer();

        Location anchor = fixture.anchor;
        putPlacedEntry(
            fixture.physicalTableManager,
            "table-a",
            placedTableStub("table-a", anchor, new PhysicalTableManager.TableOwner(
                UUID.randomUUID(), "Alice", "table-a", 1L)));
        assertTrue(fixture.physicalTableManager.isPlaced("table-a"), "夹具必须先放一张已放置桌");

        // 驱动真实修复入口：它以"不完整桌"为前置，跑完破坏性摘除段并提交清理屏障。
        CompletionStage<Void> stage = invokeRepair(fixture.physicalTableManager, table);
        assertNotNull(stage, "修复必须返回可观察 stage");
        assertFalse(fixture.physicalTableManager.isPlaced("table-a"),
            "不完整桌的修复必须摘除旧放置状态，才能重新生成世界体");

        // 修复过程中不得永久丢任务：cancelOwnerPeriodicTasks 会 entries.remove + cancelled=true，
        // 之后 rebind 只能返回 false，这就是"永久失效"。
        assertEquals(1, fixture.periodicTaskCount(),
            "ChunkLoad 修复不得摘除 ActionBar 周期任务：摘掉后收口重绑必然失败");
        assertEquals(1, fixture.ownerPeriodicTaskCount(),
            "ChunkLoad 修复不得摘除开局发牌 timer：它没有任何重新注册路径");

        // 修复成功收口调用的同一个入口必须成功（返回 false 会让 notifyTableAnchorBinding 抛异常并跳过刷新）。
        assertTrue(fixture.tableManager.rebindTablePeriodicTask(table, anchor),
            "修复成功后 owner 周期任务必须能重绑到新锚点");

        // 发牌 timer 必须真的被重绑到新锚点 region，并且仍在推进时间线（这就是"正在发牌"能继续的画面）。
        RecordedTask reboundDealTimer = fixture.backend.lastRegionTimer(0L, 1L);
        assertNotNull(reboundDealTimer, "开局发牌 timer 必须重绑到锚点 region，而不是被取消后消失");
        assertEquals(anchor, reboundDealTimer.location, "发牌 timer 必须绑定到该桌的锚点 region");
        reboundDealTimer.fire();
        assertEquals(1, counters.dealTicks, "修复后开局发牌 timer 必须仍能执行");

        // 旧 global 句柄必须失效：既被取消，也被注册表 bindingGeneration 拦下，不得重复执行世界体。
        actionBarBefore.fire();
        assertEquals(0, counters.actionBarTicks, "旧 global 句柄不得越过重绑后的代次门禁执行");

        RecordedTask reboundActionBar = fixture.backend.lastRegionTimer(1L, 10L);
        assertNotNull(reboundActionBar, "ActionBar 周期任务必须重绑到锚点 region");
        reboundActionBar.fire();
        assertEquals(1, counters.actionBarTicks, "重绑后的 ActionBar 周期任务必须在锚点 region 上执行");
    }

    /**
     * 保守保留路径：清理计划预检失败时不得触碰周期任务。
     *
     * <p>这时 {@code repairTableAfterChunkLoad} 必须在任何破坏性步骤之前退出，放置状态、footprint/实体索引
     * 与全部周期任务原样保留，等待下一次 ChunkLoad 重试。
     */
    @Test
    void 预检失败保守保留时不取消任何周期任务也不动放置状态() throws Exception {
        Fixture fixture = fixture();
        GameTable table = fixture.registerTable("table-b");
        Counters counters = fixture.attachPeriodicTasks(table);

        // owner 为 null 会让 captureCleanupPlan 记入 unresolved("table-owner")，即保守保留分支。
        Location anchor = fixture.anchor;
        putPlacedEntry(fixture.physicalTableManager, "table-b", placedTableStub("table-b", anchor, null));

        invokeRepair(fixture.physicalTableManager, table);

        assertTrue(fixture.physicalTableManager.isPlaced("table-b"),
            "预检失败必须保留放置状态，等待下一次 ChunkLoad 重试");
        assertEquals(1, fixture.periodicTaskCount(), "预检失败不得取消 ActionBar 周期任务");
        assertEquals(1, fixture.ownerPeriodicTaskCount(), "预检失败不得取消开局发牌 timer");
        assertTrue(fixture.tableManager.rebindTablePeriodicTask(table, anchor),
            "保守保留后仍必须能重绑：任务不能在等待重试窗口里失效");

        // 任务仍在推进：保守保留不是"静默停摆"。
        fixture.backend.lastRegionTimer(0L, 1L).fire();
        assertEquals(1, counters.dealTicks, "保守保留期间发牌 timer 必须仍然存活可执行");
    }

    // ---- 夹具 ----

    /** 记录两个周期任务是否真的被执行。 */
    private static final class Counters {
        private int actionBarTicks;
        private int dealTicks;
    }

    private static final class Fixture {
        private final RecordingBackend backend;
        private final DoudizhuPlugin plugin;
        private final TableManager tableManager;
        private final PhysicalTableManager physicalTableManager;
        private final Location anchor;

        private Fixture(
            RecordingBackend backend,
            DoudizhuPlugin plugin,
            TableManager tableManager,
            PhysicalTableManager physicalTableManager,
            Location anchor
        ) {
            this.backend = backend;
            this.plugin = plugin;
            this.tableManager = tableManager;
            this.physicalTableManager = physicalTableManager;
            this.anchor = anchor;
        }

        /** 把一张只暴露名字的最小逻辑桌插进 TableManager，模拟 createTable 的注册结果。 */
        private GameTable registerTable(String name) throws Exception {
            GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
            setField(table, "plugin", plugin);
            setField(table, "name", name);
            @SuppressWarnings("unchecked")
            Map<String, GameTable> tables = (Map<String, GameTable>) field(TableManager.class, "tables").get(tableManager);
            tables.put(name, table);
            return table;
        }

        /**
         * 注册两个与对局真实用途同形的周期任务：
         * 一个是 {@code registerTablePeriodicTask} 的 ActionBar tick，一个是 {@code runTableTimer} 的
         * 开局发牌 timer（GameTable 的开局协调器就走后者）。
         */
        private Counters attachPeriodicTasks(GameTable table) {
            Counters counters = new Counters();
            tableManager.registerTablePeriodicTask(table, 1L, 10L, () -> counters.actionBarTicks++);
            tableManager.runTableTimer(table, 0L, 1L, handle -> counters.dealTicks++);
            return counters;
        }

        private int periodicTaskCount() throws Exception {
            return ((TablePeriodicTaskRegistry) field(TableManager.class, "periodicTasks").get(tableManager)).size();
        }

        private int ownerPeriodicTaskCount() throws Exception {
            return ((TablePeriodicTaskRegistry) field(TableManager.class, "ownerPeriodicTasks").get(tableManager)).size();
        }
    }

    private static Fixture fixture() throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        // Unsafe 分配出的插件实例没有 PluginMeta，而 PhysicalTableManager 构造器要用
        // NamespacedKey(plugin, …) 取 namespace，所以必须先补插件元数据桩再构造管理器（顺序不能反）。
        setField(plugin, "pluginMeta", pluginMetaStub());
        setField(plugin, "logger", Logger.getLogger("ChunkLoadRepairPeriodicTaskSurvivalBehaviorTest"));
        // 调度换成只登记、不自动执行的后端替身：本测试要自己决定 lane 任务何时真正触发，
        // 才能断言旧句柄失效与新句柄落地。
        RecordingBackend backend = new RecordingBackend();
        setField(plugin, "scheduler", new MuzScheduler(backend));
        // 修复路径要查逻辑桌并重绑周期任务，必须注入真实 TableManager（初始注册表为空）。
        TableManager tableManager = new TableManager(plugin);
        setField(plugin, "tableManager", tableManager);
        PhysicalTableManager physicalTableManager = new PhysicalTableManager(plugin);
        setField(plugin, "physicalTableManager", physicalTableManager);
        return new Fixture(
            backend,
            plugin,
            tableManager,
            physicalTableManager,
            new Location(chunkLoadedWorld(), 1000.5, 64.0, 1000.5, 0.0f, 0.0f));
    }

    /** 直接驱动私有的 {@code repairTableAfterChunkLoad}，断言它在真实生产代码里的行为。 */
    private static CompletionStage<Void> invokeRepair(PhysicalTableManager manager, GameTable table)
        throws Exception {
        Method method = PhysicalTableManager.class.getDeclaredMethod("repairTableAfterChunkLoad", GameTable.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        CompletionStage<Void> stage = (CompletionStage<Void>) method.invoke(manager, table);
        return stage;
    }

    /**
     * 只登记不执行的调度后端替身：把每个任务连 anchor、delay、period 一起记下来，
     * 由测试自己调用 {@link RecordedTask#fire()} 决定何时真正执行。
     */
    private static final class RecordingBackend implements SchedulerBackend {
        private final List<RecordedTask> globalTimers = new ArrayList<>();
        private final List<RecordedTask> regionTimers = new ArrayList<>();

        private RecordedTask lastGlobalTimer() {
            return globalTimers.get(globalTimers.size() - 1);
        }

        /** 按 delay/period 精确定位一条已重绑的 region 周期任务。 */
        private RecordedTask lastRegionTimer(long delay, long period) {
            for (int index = regionTimers.size() - 1; index >= 0; index--) {
                RecordedTask task = regionTimers.get(index);
                if (task.delay == delay && task.period == period) {
                    return task;
                }
            }
            return null;
        }

        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            RecordedTask recorded = new RecordedTask(null, delay, period, task);
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
            RecordedTask recorded = new RecordedTask(location, delay, period, task);
            regionTimers.add(recorded);
            return recorded.handle;
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

    /** 已登记但尚未执行的周期任务。 */
    private static final class RecordedTask {
        private final Location location;
        private final long delay;
        private final long period;
        private final Consumer<MuzScheduler.TaskHandle> task;
        private final RecordedHandle handle = new RecordedHandle();

        private RecordedTask(Location location, long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            this.location = location;
            this.delay = delay;
            this.period = period;
            this.task = task;
        }

        private void fire() {
            task.accept(handle);
        }
    }

    /** 可取消句柄：只记录取消状态，不自动执行回调。 */
    private static final class RecordedHandle implements MuzScheduler.TaskHandle {
        private boolean cancelled;
        private final List<Runnable> terminationListeners = new ArrayList<>();

        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            for (Runnable listener : List.copyOf(terminationListeners)) {
                listener.run();
            }
            terminationListeners.clear();
        }

        @Override
        public void onTermination(Runnable listener) {
            if (cancelled) {
                listener.run();
            } else {
                terminationListeners.add(listener);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /**
     * 所有 footprint 区块都视为已加载的最小 fake World：{@code isFootprintLoaded} 会逐区块问
     * {@code isChunkLoaded}，本测试的前置条件就是"锚点 footprint 已加载"。
     */
    private static World chunkLoadedWorld() {
        World world = (World) Proxy.newProxyInstance(
            ChunkLoadRepairPeriodicTaskSurvivalBehaviorTest.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getName":
                        return "chunk-load-repair-test-world";
                    case "getUID":
                        return UUID.fromString("00000000-0000-0000-0000-00000000cafe");
                    case "isChunkLoaded":
                        return true;
                    case "toString":
                        return "chunkLoadedWorld";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        // 持强引用：Location 只弱引用 world，套件运行中一旦被 GC 回收，getWorld() 会抛 World unloaded。
        WORLD_STRONG_REFS.add(world);
        return world;
    }

    /**
     * Unsafe 分配的插件实例没有 PluginMeta，而 {@code new NamespacedKey(plugin, …)} 需要
     * {@code plugin.getPluginMeta().namespace()}。这里给一个最小桩。
     */
    private static Object pluginMetaStub() {
        return Proxy.newProxyInstance(
            ChunkLoadRepairPeriodicTaskSurvivalBehaviorTest.class.getClassLoader(),
            new Class<?>[] {io.papermc.paper.plugin.configuration.PluginMeta.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if ("namespace".equals(name) || "getName".equals(name)) {
                    return "muz";
                }
                if ("toString".equals(name)) {
                    return "MUZ-PluginMetaStub";
                }
                if ("equals".equals(name)) {
                    return args != null && args.length == 1 && proxy == args[0];
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                Class<?> returnType = method.getReturnType();
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == int.class) {
                    return 0;
                }
                if (List.class.isAssignableFrom(returnType)) {
                    return List.of();
                }
                if (java.util.Set.class.isAssignableFrom(returnType)) {
                    return java.util.Set.of();
                }
                return null;
            });
    }

    /**
     * 造一条"不完整"的最小放置快照：status/playDetail 为 null、座位文字集合不足三个，
     * 使 {@code isIncomplete} 为真；成员集合全空（不会触碰 Bukkit），footprint 用真实锚点 3×3。
     *
     * <p>注意所有 tracked 实体集合都为空，因此 {@code captureCleanupPlan} 不会调用
     * {@code Bukkit.getEntity}，本测试得以在没有 Bukkit 服务端的情况下驱动真实修复路径。
     */
    private static Object placedTableStub(String tableName, Location anchor, Object owner) throws Exception {
        Class<?> type = Class.forName("linmumua.doudizhu.world.PhysicalTableManager$PlacedTable");
        Constructor<?> canonical = null;
        for (Constructor<?> candidate : type.getDeclaredConstructors()) {
            if (candidate.getParameterCount() == 19) {
                canonical = candidate;
                break;
            }
        }
        assertNotNull(canonical, "找不到 PlacedTable 的 19 参规范构造器：记录结构变了，测试夹具需同步");
        canonical.setAccessible(true);
        return canonical.newInstance(
            tableName,                // tableName
            anchor,                   // anchor
            0.0f,                     // yaw
            owner,                    // owner
            PhysicalTableManager.anchorChunkKeys(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4),
            List.of(),                // staticEntities
            List.of(),                // craftEngineVisualEntities
            List.of(),                // blockRestores
            new LinkedHashMap<>(),    // seatAssignments
            List.of(),                // seatBaseLocations
            new LinkedHashMap<>(),    // privateEntitiesByPlayer
            new LinkedHashMap<>(),    // privateVisualsByPlayer
            new LinkedHashMap<>(),    // backsideEntitiesByPlayer
            new LinkedHashMap<>(),    // backsideVisualsByPlayer
            null,                     // statusDisplayId
            null,                     // playDetailDisplayId
            List.of(),                // seatNameDisplayIds
            List.of(),                // seatInfoDisplayIds
            List.of()                 // actionEntities
        );
    }

    @SuppressWarnings("unchecked")
    private static void putPlacedEntry(PhysicalTableManager manager, String tableKey, Object placed) throws Exception {
        ((Map<String, Object>) field(PhysicalTableManager.class, "placedTables").get(manager)).put(tableKey, placed);
    }

    private static Field field(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field found = current.getDeclaredField(fieldName);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        field(target.getClass(), fieldName).set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
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
}
