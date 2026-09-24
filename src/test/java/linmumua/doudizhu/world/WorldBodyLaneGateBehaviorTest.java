package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.game.GameTable;
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
 * 牌桌世界体 lane 门禁行为测试。
 *
 * <p>【为什么这条门禁是必需的】实服（Lophine 26.2，Folia 系）日志 {@code E:/QQ/file/latest (2).log}
 * 里 136 次堆栈的形态是：
 *
 * <pre>
 * IllegalStateException: Thread failed main thread check: Accessing entity state off owning region's thread
 *   at CraftTextDisplay.text
 *   at PhysicalTableManager.updateTextEntity(6442)
 *   at PhysicalTableManager.refreshStatus(4548) ← refreshWith(2558) ← refresh(2544)
 *   ← GameTable.refreshPhysicalTable(1997) ← RoundOpeningCoordinator.tickFlip
 *   ← TableManager.lambda$runTableTimer$0(265) ← TablePeriodicTaskRegistry.dispatch(376)
 *   ← MuzScheduler.lambda$runGlobalTimer$1(167) ← FoliaGlobalRegionScheduler.tick
 * </pre>
 *
 * <p>即：**开局发牌 timer 被绑到了 global lane**（{@code region={null}}），然后在 global 上写牌桌
 * TextDisplay。根因链是 {@code onChunkUnload → markTableUnplaced} 清掉 owner 锚点并把周期任务切到
 * global，而区块重新加载后的修复链必须先经 {@code runTableLater} 排任务——它的 lane 只看当时的锚点，
 * 锚点为空就落 global，于是在 global 上读实体抛异常、修复中断、{@code notifyTableAnchorBinding}
 * 永远不会被调用，桌子**永久停在 global**。
 *
 * <p>本测试用可注入的 {@link PhysicalTableManager.WorldBodyLane} 替身，在没有服务端的环境里锁定三条契约：
 * <ol>
 *   <li>当前线程不拥有锚点 region 时，世界体刷新（{@code refresh} / {@code refreshPrivateHand} /
 *       {@code tickTable}）**不得触碰任何实体，也不得抛异常**；拥有时必须放行（对照组防"门禁恒假"）；</li>
 *   <li>ChunkLoad 修复抓取清理计划时，读到**不属于当前 lane 的现存实体**必须按既有保守分支
 *       （unresolved）处理：不调用 {@code getVehicle()}、不抛异常、放置状态原样保留；</li>
 *   <li>ChunkLoad 修复排入之前必须先把 owner 锚点恢复回放置快照里的锚点。</li>
 * </ol>
 *
 * <p>夹具沿用仓库既有写法（见 {@code ChunkLoadRepairPeriodicTaskSurvivalBehaviorTest}）：
 * {@link Unsafe} 分配 {@link DoudizhuPlugin} + 最小 {@code PluginMeta} 桩 + 只登记不执行的调度后端。
 */
class WorldBodyLaneGateBehaviorTest {
    /**
     * 强引用夹具 Proxy world。
     *
     * <p>Paper 26.x 的 {@link Location} 只用弱引用持有 world，不额外持强引用时套件运行期间该 Proxy
     * 可能被 GC 回收，使 {@code Location.getWorld()} 抛 {@code World unloaded}。这是夹具自身的
     * GC 稳定性保障，不改变生产代码语义。
     */
    private static final List<World> WORLD_STRONG_REFS = new ArrayList<>();

    /**
     * 世界体刷新：当前线程不拥有锚点 region 时必须整体跳过。
     *
     * <p>{@code refreshStatus} 会读牌桌 TextDisplay（实服就是这一行抛 {@code CraftTextDisplay.text}），
     * {@code refreshPlayDetail} 还会遍历在线玩家做按人可见性。门禁一旦缺失，这些调用在 global lane 上
     * 必然抛异常；异常逃到调度器会让周期任务被取消（{@code MuzScheduler.schedule} 的 fail 分支），
     * 桌子从此永久停摆。所以这里断言的核心是"不抛异常"，而不只是"没画出来"。
     */
    @Test
    void 当前线程不拥有锚点region时世界体刷新不触碰实体也不抛异常() throws Exception {
        Fixture fixture = fixture(false);
        GameTable table = fixture.registerTable("lane-a");
        putPlacedEntry(fixture.physicalTableManager, "lane-a", placedTableStub(
            "lane-a",
            fixture.anchor,
            new PhysicalTableManager.TableOwner(UUID.randomUUID(), "Alice", "lane-a", 1L)));

        assertDoesNotThrow(() -> fixture.physicalTableManager.refresh(table),
            "不拥有锚点 region 时刷新必须静默跳过，绝不能让 lane 异常逃到调度器");
        assertDoesNotThrow(() -> fixture.physicalTableManager.refreshPrivateHand(table, UUID.randomUUID()),
            "手牌刷新是同一类世界体读取，也必须被门禁拦住");
        assertDoesNotThrow(() -> fixture.physicalTableManager.tickTable(table),
            "世界 owner tick 在 global lane 上同样不得触碰实体");

        // 放置状态不能被门禁改写：跳过刷新只影响这一次渲染，不是"把桌子摘掉"。
        assertTrue(fixture.physicalTableManager.isPlaced("lane-a"),
            "跳过世界体刷新不得改动放置状态");
        // 门禁必须留痕：静默放弃是实服里最难排查的形态，限频日志是唯一线索。
        assertEquals(3, fixture.lane.anchorChecks.get(),
            "refresh / refreshPrivateHand / tickTable 三条世界体路径都必须真的问过 lane 归属");
        assertEquals(3, fixture.lane.loggedSkips.get(),
            "每次因 lane 不归属而跳过都必须记一次限频日志计数");
    }

    /**
     * 对照组：当前线程拥有锚点 region 时门禁必须放行。
     *
     * <p>这一条防的是"门禁写反了 / 恒假"这类回归——只有拒绝没有放行，刷新就永远不会执行，
     * 而单看拒绝侧的测试是发现不了的。
     */
    @Test
    void 当前线程拥有锚点region时世界体刷新放行() throws Exception {
        Fixture fixture = fixture(true);
        GameTable table = fixture.registerTable("lane-b", true);
        putPlacedEntry(fixture.physicalTableManager, "lane-b", placedTableStub(
            "lane-b",
            fixture.anchor,
            new PhysicalTableManager.TableOwner(UUID.randomUUID(), "Alice", "lane-b", 1L)));

        // 门禁放行后会继续往世界体深处走。这里没有真实服务端，链路迟早会在某个 Bukkit 读取或
        // 未桩字段上停下——那是夹具的边界，不是被测行为。所以断言的是"门禁被问过且判定为拥有、
        // 没有产生跳过日志"，而不是"整条刷新成功"。
        try {
            fixture.physicalTableManager.refresh(table);
        } catch (RuntimeException | Error expectedFixtureLimit) {
            assertFalse(expectedFixtureLimit.getMessage() != null
                    && expectedFixtureLimit.getMessage().contains("off owning region"),
                "放行路径不得因 lane 归属抛错（夹具能观察到的唯一越界形态）");
        }
        assertTrue(fixture.lane.anchorChecks.get() > 0, "拥有锚点时也必须真的做归属判定");
        assertEquals(0, fixture.lane.loggedSkips.get(),
            "拥有锚点 region 时不得记跳过日志（否则门禁恒真/恒假就分不出来）");
    }

    /**
     * ChunkLoad 修复抓取清理计划：不属于当前 lane 的现存实体必须按"不可安全定位"走保守分支。
     *
     * <p>实服栈正是在这里断的：{@code captureCleanupPlan} 读 {@code entity.getVehicle()}
     * （{@code CraftEntity.getVehicle → isInsideVehicle → getHandle}），线程是 region 线程但
     * **不拥有该实体**，于是 {@code [MUZ] 区块加载修复未完成: 4，原因=Accessing entity state off
     * owning region's thread}。修复被中断后 {@code removePlacedTableIfSame} 与
     * {@code notifyTableAnchorBinding} 都不会执行，锚点再也回不来。
     */
    @Test
    void 修复路径遇到不属于当前线程的现存实体不抛异常且保留放置状态() throws Exception {
        Fixture fixture = fixture(false);
        GameTable table = fixture.registerTable("lane-c");
        // craftEngineVisualEntities 里放一个"现存但不由当前 lane 拥有"的实体：
        // 这是唯一会在 captureCleanupPlan 里走 getVehicle() 的分支，也是实服的爆点。
        UUID foreignId = UUID.fromString("00000000-0000-0000-0000-00000000c001");
        fixture.lane.entities.put(foreignId, foreignEntityStub(fixture.lane.vehicleReads));
        putPlacedEntry(fixture.physicalTableManager, "lane-c", placedTableStub(
            "lane-c",
            fixture.anchor,
            new PhysicalTableManager.TableOwner(UUID.randomUUID(), "Alice", "lane-c", 1L),
            List.of(foreignId)));

        CompletionStage<Void> stage = assertDoesNotThrow(
            () -> invokeRepair(fixture.physicalTableManager, table),
            "跨 region 实体必须走保守分支，不能让异常逃出修复入口");

        assertEquals(0, fixture.lane.vehicleReads.get(),
            "不得对不属于当前 lane 的实体调用 getVehicle()：那正是实服抛异常的那一行");
        // 保守分支（unresolved 非空）在摘除放置状态之前返回，桌子必须原样保留、等待下次重试。
        assertTrue(fixture.physicalTableManager.isPlaced("lane-c"),
            "预检把跨 region 实体记为不可安全定位后必须保留放置状态，等待下一次 ChunkLoad 重试");
        assertNotNull(stage, "修复必须返回可观察 stage");
        assertTrue(stage.toCompletableFuture().isDone(), "保守分支必须返回已完成 stage，不提交清理屏障");
    }

    /**
     * 对照：实体**属于**当前 lane 时，修复路径照常读位置、不把桌子误判成"不可安全定位"。
     *
     * <p>这一条与上一条互为反例：没有它，一个"永远记 unresolved"的实现也能让上一条变绿。
     */
    @Test
    void 修复路径对属于当前线程的实体照常解析() throws Exception {
        Fixture fixture = fixture(true);
        GameTable table = fixture.registerTable("lane-d");
        UUID localId = UUID.fromString("00000000-0000-0000-0000-00000000c002");
        fixture.lane.entities.put(localId, localEntityStub(fixture.anchor));
        putPlacedEntry(fixture.physicalTableManager, "lane-d", placedTableStub(
            "lane-d",
            fixture.anchor,
            new PhysicalTableManager.TableOwner(UUID.randomUUID(), "Alice", "lane-d", 1L),
            List.of(localId)));

        assertDoesNotThrow(() -> invokeRepair(fixture.physicalTableManager, table));
        assertEquals(0, fixture.lane.vehicleReads.get(),
            "craftEngine 实体在夹具里没有乘客，getVehicle 不该被调用（null 走既有独立根家具分支）");
    }

    /**
     * ChunkLoad 修复排入前必须先把 owner 锚点恢复回放置快照里的锚点。
     *
     * <p>这是"永久停在 global"的另一半修复：{@code onChunkUnload → markTableUnplaced} 清掉了锚点，
     * 若不先恢复，{@code runTableLater} 会按空锚点把整条修复排到 global，而 global 上根本无法合法读实体。
     */
    @Test
    void 区块加载修复排入前恢复owner锚点() throws Exception {
        Fixture fixture = fixture(false);
        GameTable table = fixture.registerTable("lane-e");
        fixture.tableManager.registerTablePeriodicTask(table, 1L, 10L, () -> { });
        putPlacedEntry(fixture.physicalTableManager, "lane-e", placedTableStub(
            "lane-e",
            fixture.anchor,
            new PhysicalTableManager.TableOwner(UUID.randomUUID(), "Alice", "lane-e", 1L)));

        // 模拟区块卸载：锚点被清空、周期任务切回 global。
        fixture.tableManager.markTableUnplaced("lane-e");
        assertEquals(0, fixture.backend.regionTimerCount(),
            "unload 后周期任务必须已切回 global（前置条件）");

        // 驱动真实入口：区块加载 → 排入修复（内部必须先把锚点恢复回来）。
        assertEquals(List.of("lane-e"), fixture.physicalTableManager.tableNamesForFootprintChunk(
                fixture.anchor.getWorld().getUID(),
                fixture.anchor.getBlockX() >> 4,
                fixture.anchor.getBlockZ() >> 4),
            "夹具自检：区块加载接缝必须能通过 footprint 索引反查到该桌");
        fixture.physicalTableManager.onChunkLoad(
            fixture.anchor.getWorld().getUID(),
            fixture.anchor.getBlockX() >> 4,
            fixture.anchor.getBlockZ() >> 4);

        assertTrue(fixture.backend.regionTimerCount() > 0,
            "区块加载后必须先恢复 owner 锚点，否则修复会整条落到 global lane（实服 region={null} 的根因）");
    }

    // ---- 夹具 ----

    private static final class Fixture {
        private final PhysicalTableManager physicalTableManager;
        private final TableManager tableManager;
        private final RecordingBackend backend;
        private final RecordingLane lane;
        private final Location anchor;

        private Fixture(
            PhysicalTableManager physicalTableManager,
            TableManager tableManager,
            RecordingBackend backend,
            RecordingLane lane,
            Location anchor
        ) {
            this.physicalTableManager = physicalTableManager;
            this.tableManager = tableManager;
            this.backend = backend;
            this.lane = lane;
            this.anchor = anchor;
        }

        /** 把一张只暴露名字的最小逻辑桌插进 TableManager，模拟 createTable 的注册结果。 */
        private GameTable registerTable(String name) throws Exception {
            return registerTable(name, false);
        }

        /**
         * @param stubSeats 是否补上 {@code seats} 空表。门禁**放行**的用例会继续走进
         *                  {@code reconcileSeatAssignments → table.getSeats()}，那里需要一个非 null 集合；
         *                  门禁**拦截**的用例不会走到那一步，因此不需要（保持最小桩）。
         */
        private GameTable registerTable(String name, boolean stubSeats) throws Exception {
            GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
            setField(table, "plugin", null);
            setField(table, "name", name);
            if (stubSeats) {
                setField(table, "seats", new ArrayList<UUID>());
            }
            @SuppressWarnings("unchecked")
            Map<String, GameTable> tables = (Map<String, GameTable>) field(TableManager.class, "tables").get(tableManager);
            tables.put(name, table);
            return table;
        }
    }

    private static Fixture fixture(boolean owned) throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        // Unsafe 分配出的插件实例没有 PluginMeta，而物理桌管理器构造器要用 NamespacedKey(plugin, …)
        // 取 namespace，所以必须先补插件元数据桩再构造管理器（顺序不能反）。
        setField(plugin, "pluginMeta", pluginMetaStub());
        setField(plugin, "logger", Logger.getLogger("WorldBodyLaneGateBehaviorTest"));
        // 调度换成只登记、不自动执行的后端替身：本测试只关心 lane 选择，不需要真正跑回调。
        RecordingBackend backend = new RecordingBackend();
        setField(plugin, "scheduler", new MuzScheduler(backend));
        TableManager tableManager = new TableManager(plugin);
        setField(plugin, "tableManager", tableManager);
        RecordingLane lane = new RecordingLane(owned);
        PhysicalTableManager physicalTableManager = new PhysicalTableManager(
            plugin,
            new PhysicalTableManager.PlayerPresenceRegistry(List.of()),
            lane);
        setField(plugin, "physicalTableManager", physicalTableManager);
        return new Fixture(
            physicalTableManager,
            tableManager,
            backend,
            lane,
            new Location(chunkLoadedWorld(), 1000.5, 64.0, 1000.5, 0.0f, 0.0f));
    }

    /**
     * 可注入的 lane 替身：{@code owned} 决定"当前线程是否拥有世界体 region"，
     * {@code entities} 提供 tracked 实体解析，{@code vehicleReads} 记录实服爆点是否被触碰。
     */
    private static final class RecordingLane implements PhysicalTableManager.WorldBodyLane {
        private final boolean owned;
        private final Map<UUID, Entity> entities = new LinkedHashMap<>();
        private final AtomicInteger anchorChecks = new AtomicInteger();
        private final AtomicInteger loggedSkips = new AtomicInteger();
        private final AtomicInteger vehicleReads = new AtomicInteger();

        private RecordingLane(boolean owned) {
            this.owned = owned;
        }

        @Override
        public boolean isOwnedByCurrentRegion(Location anchor) {
            anchorChecks.incrementAndGet();
            if (!owned || anchor == null || anchor.getWorld() == null) {
                loggedSkips.incrementAndGet();
                return false;
            }
            return true;
        }

        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            // 实体归属与锚点归属解耦，才能表达"父桌归属、子家具不归属"这类实服形态。
            return owned && entity != null;
        }

        @Override
        public Entity resolveEntity(UUID entityId) {
            return entityId == null ? null : entities.get(entityId);
        }
    }

    /** 现存但被判定为不属于当前 lane 的实体；{@code getVehicle()} 一旦被调用即抛实服同款异常。 */
    private static Entity foreignEntityStub(AtomicInteger vehicleReads) {
        return (Entity) Proxy.newProxyInstance(
            WorldBodyLaneGateBehaviorTest.class.getClassLoader(),
            new Class<?>[] {Entity.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getUniqueId":
                        return UUID.fromString("00000000-0000-0000-0000-00000000c001");
                    case "getVehicle":
                        vehicleReads.incrementAndGet();
                        throw new IllegalStateException(
                            "Thread failed main thread check: Accessing entity state off owning region's thread");
                    case "getLocation":
                        throw new IllegalStateException(
                            "Thread failed main thread check: Accessing entity state off owning region's thread");
                    case "toString":
                        return "foreignEntity";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
    }

    /** 正常可解析的实体：位置有效、没有乘客。 */
    private static Entity localEntityStub(Location anchor) {
        return (Entity) Proxy.newProxyInstance(
            WorldBodyLaneGateBehaviorTest.class.getClassLoader(),
            new Class<?>[] {Entity.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getUniqueId":
                        return UUID.fromString("00000000-0000-0000-0000-00000000c002");
                    case "getLocation":
                        return anchor.clone();
                    case "getVehicle":
                        return null;
                    case "toString":
                        return "localEntity";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
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

    /** 只登记不执行的调度后端替身。 */
    private static final class RecordingBackend implements SchedulerBackend {
        private final List<Location> regionTimers = new ArrayList<>();

        private int regionTimerCount() {
            return regionTimers.size();
        }

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
            regionTimers.add(location);
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

    /** 可取消句柄：只记录取消状态，不自动执行回调。 */
    private static final class RecordedHandle implements MuzScheduler.TaskHandle {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
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
            WorldBodyLaneGateBehaviorTest.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getName":
                        return "world-body-lane-test-world";
                    case "getUID":
                        return UUID.fromString("00000000-0000-0000-0000-00000000beef");
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
            WorldBodyLaneGateBehaviorTest.class.getClassLoader(),
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
     * 使 {@code isIncomplete} 为真；footprint 用真实锚点 3×3。
     */
    private static Object placedTableStub(String tableName, Location anchor, Object owner) throws Exception {
        return placedTableStub(tableName, anchor, owner, List.of());
    }

    private static Object placedTableStub(
        String tableName,
        Location anchor,
        Object owner,
        List<UUID> craftEngineEntities
    ) throws Exception {
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
            // anchorChunkKeys 接收的是**方块坐标**（内部自己 >>4），这里不能先位移一次。
            PhysicalTableManager.anchorChunkKeys(anchor.getBlockX(), anchor.getBlockZ()),
            List.of(),                // staticEntities
            craftEngineEntities,      // craftEngineVisualEntities
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

    /**
     * 走**生产**的 {@code putPlacedTable} 登记放置快照，而不是直接往 Map 里塞。
     *
     * <p>这里必须走生产入口：{@code onChunkLoad} 是用 footprint 索引反查桌名的，
     * 直接写 {@code placedTables} 会让索引为空、事件接缝看不到任何桌（夹具自身的问题，
     * 不是被测行为）。
     */
    private static void putPlacedEntry(PhysicalTableManager manager, String tableKey, Object placed) throws Exception {
        Method method = PhysicalTableManager.class.getDeclaredMethod(
            "putPlacedTable", String.class, Class.forName("linmumua.doudizhu.world.PhysicalTableManager$PlacedTable"));
        method.setAccessible(true);
        method.invoke(manager, tableKey, placed);
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
