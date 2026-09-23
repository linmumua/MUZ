package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.TableManager;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.scheduler.SchedulerBackend;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 重建流水线的 lane 顺序行为测试。
 *
 * <p>覆盖的是 {@link PhysicalTableManager#dispatchOwnerRegionAfter} /
 * {@link PhysicalTableManager#dispatchOwnerRegionValueAfter}——生产重建流水线每一步都经它们串联，
 * 因此这里断言的是**真实顺序**而不是源码字符串：
 * <ul>
 *   <li>footprint 异步加载没有完成之前，绝不允许把世界体投到锚点 region；</li>
 *   <li>加载失败时一个世界体都不许投递，失败绝不"复活"旧桌；</li>
 *   <li>旧锚点清理与新锚点生成各投各自锚点的 region（位移重建时两者必须可区分）；</li>
 *   <li>投递本身不执行世界体——真实世界操作留给锚点 region 线程；</li>
 *   <li>region 回调的返回值必须交回调用 chain。</li>
 * </ul>
 *
 * <p>夹具复用仓库既有做法：{@link Unsafe} 分配 {@link DoudizhuPlugin} + 最小 {@code PluginMeta}
 * 桩（{@code NamespacedKey(plugin, …)} 需要 namespace），再注入只登记、不自动执行的调度后端替身。
 */
class RebuildPipelineLaneOrderBehaviorTest {

    @Test
    void noWorldBodyIsDispatchedBeforeTheFootprintLoadStageCompletes() throws Exception {
        Fixture fixture = fixture();
        CompletableFuture<Void> loads = new CompletableFuture<>();
        List<String> ran = new ArrayList<>();

        fixture.manager.dispatchOwnerRegionAfter(loads, fixture.anchor, () -> ran.add("old-anchor-cleanup"));

        assertTrue(fixture.backend.regionTasks.isEmpty(),
            "footprint 异步加载未完成前不得投递任何锚点 region 世界体");
        assertTrue(ran.isEmpty(), "加载未完成前世界体不得执行");

        loads.complete(null);

        assertEquals(1, fixture.backend.regionTasks.size(),
            "加载完成后必须把世界体投到锚点 region");
        assertEquals(fixture.anchor, fixture.backend.regionTasks.get(0).location,
            "世界体必须投到给定锚点所属 region");
        assertTrue(ran.isEmpty(),
            "投递本身不得执行世界体：真实世界操作必须留给锚点 region 线程");

        fixture.backend.regionTasks.get(0).fire();
        assertEquals(List.of("old-anchor-cleanup"), ran,
            "锚点 region 任务真正执行时才允许动世界体");
    }

    @Test
    void failedLoadStageDispatchesNothingAndNeverRevivesTheTable() throws Exception {
        Fixture fixture = fixture();
        CompletableFuture<Void> loads = new CompletableFuture<>();
        List<String> ran = new ArrayList<>();

        CompletionStage<Void> staged =
            fixture.manager.dispatchOwnerRegionAfter(loads, fixture.anchor, () -> ran.add("cleanup"));

        loads.completeExceptionally(new IllegalStateException("区块加载失败"));

        assertTrue(fixture.backend.regionTasks.isEmpty(),
            "加载失败时不得投递世界体：失败不能复活旧桌");
        assertTrue(ran.isEmpty(), "加载失败时世界体不得执行");
        assertTrue(staged.toCompletableFuture().isCompletedExceptionally(),
            "上游失败必须沿 stage 传播下去，调用方据此跳过本桌收口");
    }

    @Test
    void newAnchorSpawnStageIsDispatchedToItsOwnAnchorLaneAndReturnsItsValue() throws Exception {
        Fixture fixture = fixture();
        Location oldAnchor = fixture.anchor;
        Location newAnchor = fixture.anchor.clone().add(0.0, 8.0, 0.0);
        CompletableFuture<Void> cleaned = new CompletableFuture<>();
        List<String> ran = new ArrayList<>();

        CompletionStage<String> staged = fixture.manager.dispatchOwnerRegionValueAfter(
            cleaned,
            newAnchor,
            () -> {
                ran.add("spawn");
                return "rebuilt";
            }
        );

        assertTrue(fixture.backend.regionTasks.isEmpty(), "清理阶段未完成时不得投递生成阶段");
        cleaned.complete(null);

        assertEquals(1, fixture.backend.regionTasks.size(), "生成阶段必须投到新锚点 region");
        assertEquals(newAnchor, fixture.backend.regionTasks.get(0).location,
            "生成必须投新锚点，不能投旧锚点：跨 region 时在新锚点线程清旧实体非法");
        assertFalse(oldAnchor.equals(fixture.backend.regionTasks.get(0).location),
            "新/旧锚点 lane 必须可区分，否则位移重建会投错线程");
        assertTrue(ran.isEmpty(), "投递本身不得执行生成");

        fixture.backend.regionTasks.get(0).fire();
        assertEquals(List.of("spawn"), ran);
        assertEquals("rebuilt", staged.toCompletableFuture().join(),
            "region 回调的返回值必须交回调用 chain 供 global 收口使用");
    }

    /**
     * 收口被闸门拒绝时，**已生成**的新桌必须投回它自己的锚点 region 清理。
     *
     * <p>意图：旧实现的身份闸门在 spawn 之后才检查，拒绝时新实体/方块已经落到世界里而没有 owner 也没有
     * 索引，只能变成永久孤儿。这里用记录型后端证明清理 stage 真实投递、投到新锚点（不是旧锚点、也不是
     * global）且真正会执行，而不是只断言源码里出现某个字符串。
     */
    @Test
    void uncommittedRebuildCleanupRunsOnTheTablesOwnAnchorLane() throws Exception {
        Fixture fixture = fixture();
        Location oldAnchor = fixture.anchor;
        Location newAnchor = fixture.anchor.clone().add(0.0, 8.0, 0.0);
        List<String> ran = new ArrayList<>();

        CompletionStage<Void> staged = fixture.manager.dispatchRebuildCleanupOnOwnerRegion(
            newAnchor, "table-x", () -> ran.add("orphan-cleanup"));

        assertEquals(1, fixture.backend.regionTasks.size(),
            "无法提交的重建必须投递一次清理，不能只丢结果留下孤儿实体/方块");
        assertEquals(newAnchor, fixture.backend.regionTasks.get(0).location,
            "清理必须投到新桌自己的锚点 region：在 global 或旧锚点 region 删新锚点侧实体在 Folia 上非法");
        assertFalse(oldAnchor.equals(fixture.backend.regionTasks.get(0).location),
            "清理不得投到旧锚点 region");
        assertTrue(ran.isEmpty(), "投递本身不得执行清理：真实世界操作留给锚点 region 线程");

        fixture.backend.regionTasks.get(0).fire();
        assertEquals(List.of("orphan-cleanup"), ran, "锚点 region 真正执行时清理才落地");
        assertTrue(staged.toCompletableFuture().isDone() && !staged.toCompletableFuture().isCompletedExceptionally(),
            "清理成功时 stage 必须正常完成，批流水线才能继续后面的桌");
    }

    /**
     * 孤儿清理失败只记日志，不得把整批重建链打断。
     *
     * <p>丢一整批比丢一张桌更糟：清理异常若沿链传播，后面的桌也会被跳过。
     */
    @Test
    void failedRebuildCleanupIsReportedButDoesNotBreakTheBatch() throws Exception {
        Fixture fixture = fixture();
        CompletionStage<Void> staged = fixture.manager.dispatchRebuildCleanupOnOwnerRegion(
            fixture.anchor,
            "table-y",
            () -> {
                throw new IllegalStateException("清理失败");
            });

        fixture.backend.regionTasks.get(0).fire();

        assertTrue(staged.toCompletableFuture().isDone(), "清理失败也必须让 stage 结束，链不能挂住");
        assertFalse(staged.toCompletableFuture().isCompletedExceptionally(),
            "清理失败只记日志：不得把失败沿 stage 传播去打断整批重建");
    }

    /**
     * 重建期间旧放置状态必须保留，且同桌重建互斥。
     *
     * <p>意图：旧实现在冻结快照时就把桌子从 {@code placedTables} 摘掉，开出一个"这张桌不存在"的窗口——
     * {@code TableManager.cleanupIfEmpty} 只看 {@code isPlaced}，空桌会在窗口里被注销（永久丢桌），
     * 重建失败也会连旧桌一起丢。这里直接对生产代码 {@code captureSingleRebuild} 断言：
     * <ul>
     *   <li>冻结后 {@code isPlaced} 仍为真（旧状态活到收口提交）；</li>
     *   <li>同桌第二次冻结被在飞标记挡下（互斥从"先摘除"改由显式标记承担）；</li>
     *   <li>被挡下也不得动旧状态。</li>
     * </ul>
     */
    @Test
    void rebuildKeepsTheOldPlacedEntryUntilCommitAndIsMutuallyExclusive() throws Exception {
        Fixture fixture = fixture();
        putPlacedEntry(fixture.manager, "table-a", placedTableStub("table-a", fixture.anchor));

        Object first = captureSingleRebuild(fixture.manager, "table-a", 0.0);
        assertNotNull(first, "首次重建必须冻结成功");
        assertTrue(fixture.manager.isPlaced("table-a"),
            "重建期间旧放置状态必须保留：cleanupIfEmpty 依赖 isPlaced，提前摘除会让空桌被注销（永久丢桌）");
        assertTrue(fixture.manager.hasPlacedOrRebuildingTables(),
            "重建在飞的桌也要被预热/修复门禁看见");

        Object second = captureSingleRebuild(fixture.manager, "table-a", 0.0);
        assertNull(second, "同桌已有重建在飞时必须让出，不能两条流水线同时提交同一张桌");
        assertTrue(fixture.manager.isPlaced("table-a"), "被互斥挡下也不得动旧放置状态");
    }

    // ---- 夹具 ----

    private record Fixture(RecordingBackend backend, PhysicalTableManager manager, Location anchor) {
    }

    private static Fixture fixture() throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        // Unsafe 分配出的插件实例没有 PluginMeta，而 PhysicalTableManager 构造器要用
        // NamespacedKey(plugin, …) 取 namespace，所以必须先补插件元数据桩再构造管理器（顺序不能反）。
        setField(plugin, "pluginMeta", pluginMetaStub());
        setField(plugin, "logger", Logger.getLogger("RebuildPipelineLaneOrderBehaviorTest"));
        // 调度必须换成只登记、不自动执行的后端替身：本测试要自己决定 lane 任务何时真正触发出，
        // 才能断言"加载未完成前没有任何世界体被投递"。
        RecordingBackend backend = new RecordingBackend();
        setField(plugin, "scheduler", new MuzScheduler(backend));
        // 冻结重建参数要查逻辑桌（plugin.getTableManager().getTable(…)），必须注入一个真实 TableManager；
        // 它只用空注册表回答查询，不注册任何关卡任务。
        setField(plugin, "tableManager", new TableManager(plugin));
        PhysicalTableManager manager = new PhysicalTableManager(plugin);
        return new Fixture(backend, manager, new Location(fakeWorld(), 1000.5, 64.0, 1000.5, 0.0f, 0.0f));
    }

    /**
     * 只登记不执行的调度后端替身：把每个 region 任务连锚点一起记下来，
     * 由测试自己调用 {@link RecordedTask#fire()} 决定何时真正执行。
     */
    private static final class RecordingBackend implements SchedulerBackend {
        final List<RecordedTask> regionTasks = new ArrayList<>();

        @Override
        public MuzScheduler.TaskHandle runGlobal(
            long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            return new RecordedHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runRegion(
            Location location, long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            regionTasks.add(new RecordedTask(location, task));
            return new RecordedHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runEntity(
            Entity entity, long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            return new RecordedHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runPlayer(
            Player player, long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            return new RecordedHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runAsync(
            long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            return new RecordedHandle();
        }
    }

    /** 已登记但尚未执行的 lane 任务。 */
    private static final class RecordedTask {
        private final Location location;
        private final Consumer<MuzScheduler.TaskHandle> task;
        private final MuzScheduler.TaskHandle handle = new RecordedHandle();

        private RecordedTask(Location location, Consumer<MuzScheduler.TaskHandle> task) {
            this.location = location;
            this.task = task;
        }

        private void fire() {
            task.accept(handle);
        }
    }

    /** 未取消的句柄：{@code runRegionStage} 会拒绝 null 或已取消的句柄。 */
    private static final class RecordedHandle implements MuzScheduler.TaskHandle {
        @Override
        public void cancel() {
        }
    }

    /** 只提供世界标识的最小 fake World；本测试不执行任何世界体，不需要更多行为。 */
    private static World fakeWorld() {
        ClassLoader loader = RebuildPipelineLaneOrderBehaviorTest.class.getClassLoader();
        return (World) Proxy.newProxyInstance(loader, new Class<?>[] {World.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getName":
                    return "rebuild-pipeline-test-world";
                case "getUID":
                    return UUID.fromString("00000000-0000-0000-0000-00000000c0de");
                case "toString":
                    return "fakeWorld";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    /**
     * Unsafe 分配的插件实例没有 PluginMeta，而 {@code new NamespacedKey(plugin, …)} 需要
     * {@code plugin.getPluginMeta().namespace()}。这里给一个最小桩：只保证 namespace/name 可用，
     * 其余集合类方法返回空集合，避免后续调用点被 null 砸中。
     */
    private static Object pluginMetaStub() {
        return Proxy.newProxyInstance(
            RebuildPipelineLaneOrderBehaviorTest.class.getClassLoader(),
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
            }
        );
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 直接把一条放置快照塞进 {@code placedTables}；本测试跑不起 Bukkit，只能走反射。 */
    @SuppressWarnings("unchecked")
    private static void putPlacedEntry(PhysicalTableManager manager, String tableKey, Object placed)
        throws Exception {
        Field field = findField(PhysicalTableManager.class, "placedTables");
        field.setAccessible(true);
        ((Map<String, Object>) field.get(manager)).put(tableKey, placed);
    }

    /** 直接驱动私有的 {@code captureSingleRebuild}，断言它在真实生产代码里的行为。 */
    private static Object captureSingleRebuild(PhysicalTableManager manager, String tableKey, double deltaY)
        throws Exception {
        Method method = PhysicalTableManager.class.getDeclaredMethod(
            "captureSingleRebuild", String.class, double.class);
        method.setAccessible(true);
        return method.invoke(manager, tableKey, deltaY);
    }

    /**
     * 造一条最小可用的 {@code PlacedTable} 放置快照。
     *
     * <p>记录是私有嵌套类型，只能走反射构造；集合类分量全给空集合、锚点给 fake World 上的 Location，
     * 使 {@code captureSingleRebuild} 冻结参数时不触碰任何真实世界状态。
     */
    private static Object placedTableStub(String tableName, Location anchor) throws Exception {
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
            null,                     // owner
            List.of(),                // footprintChunkKeys
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

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
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
