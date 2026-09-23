package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 桌内道具投掷的「跨 region 跳过」与「失败必须归还名额」行为测试。
 *
 * <p>意图（对应实服 Folia/Lophine 上「投掷卡住」的永久化路径）：效果实体只能在它所属 region 的线程上
 * 操作。Folia 的 {@code CraftEntity#getHandle} 会对非 owner region 的线程抛
 * {@code Accessing entity state off owning region's thread}（已用测试端 {@code folia-26.1.2.jar}
 * 反编译确认：{@code remove()} / {@code getBoundingBox()} / {@code setVisibleByDefault()} 全部经由
 * {@code getHandle()} → {@code TickThread.ensureTickThread}），而 {@code Bukkit.getCurrentTick()}
 * 只能在 ticking region 内读取。旧实现把「生成 + 清理」放在玩家 lane、把「推进」放在牌桌锚点 region，
 * 且清理顺序是「先删实体、后摘状态」，于是：
 * <ul>
 *   <li>actor/target 不在当前 region 时，跨 region 读状态直接抛异常；</li>
 *   <li>实体删除一旦抛异常，{@code effects.remove} 永远不会执行，{@code activeEntityCount()} 不再归还，
 *       之后所有投掷都被静默拒绝（{@code play} 直接返回 false，无日志）——这就是「卡住」被永久化；</li>
 *   <li>扫描周期任务如果被异常打挂，所有桌的效果推进与目标高亮一起永久失效。</li>
 * </ul>
 *
 * <p>本测试用 {@link TableGadgetEffectService.EffectRuntime} 替身 + fake {@link World}/{@link Player}/
 * {@link ItemDisplay}（JDK 动态代理）+ {@link Unsafe} 分配的空心 {@link GameTable} 直接驱动**生产方法**
 * {@code play}/{@code tickTable}，断言上面三条语义。夹具风格沿用仓库既有做法（见
 * {@code linmumua.doudizhu.world.AnchorChunkPreloadCoreBehaviorTest} 的 fakeWorld 与
 * {@code ChunkLoadRepairPeriodicTaskSurvivalBehaviorTest} 的 Unsafe 插件 + 记录型调度后端）。
 */
class TableGadgetEffectReleaseBehaviorTest {
    /**
     * 强引用夹具 Proxy world。
     *
     * <p>Paper 26.x 的 {@link Location} 只用弱引用持有 world；这里不额外持强引用的话，套件运行期间
     * Proxy 可能被 GC 回收，使 {@code Location.getWorld()} 抛 {@code World unloaded}。
     */
    private static final List<World> WORLD_STRONG_REFS = new ArrayList<>();

    /**
     * 隔离 {@link Material} 的延迟注册表查询。
     *
     * <p>正式代码在 {@code play} 里会调用 {@code item.getType().isAir()}，而 Paper 26.x 的
     * {@code Material#isAir()} 会经由 {@code blockType} 缓存查询方块注册表；单元测试没有服务端，
     * 直接调用会抛 {@code No RegistryAccess implementation found}。这里只把用到的普通物品的
     * {@code blockType} 缓存置空（它们本来也不是方块），退出时恢复——沿用仓库既有夹具写法
     * （见 {@code linmumua.doudizhu.debug.GadgetPreviewSnapshotServiceTest}）。
     */
    private final List<Runnable> materialCacheRestores = new ArrayList<>();

    @org.junit.jupiter.api.BeforeEach
    void isolateMaterialRegistry() throws Throwable {
        var lookup = java.lang.invoke.MethodHandles.lookup();
        var materialLookup = java.lang.invoke.MethodHandles.privateLookupIn(Material.class, lookup);
        var getter = materialLookup.findGetter(Material.class, "blockType", java.util.function.Supplier.class);
        for (Material material : List.of(Material.EGG, Material.WATER_BUCKET)) {
            Object cache = getter.invoke(material);
            var cacheLookup = java.lang.invoke.MethodHandles.privateLookupIn(cache.getClass(), lookup);
            var delegate = cacheLookup.findVarHandle(cache.getClass(), "delegate", com.google.common.base.Supplier.class);
            var value = cacheLookup.findVarHandle(cache.getClass(), "value", Object.class);
            Object originalDelegate = delegate.getVolatile(cache);
            Object originalValue = value.get(cache);
            materialCacheRestores.add(() -> {
                value.set(cache, originalValue);
                delegate.setVolatile(cache, originalDelegate);
            });
            value.set(cache, null);
            delegate.setVolatile(cache, (com.google.common.base.Supplier<Object>) () -> null);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void restoreMaterialRegistry() {
        materialCacheRestores.forEach(Runnable::run);
        materialCacheRestores.clear();
    }

    /**
     * 跨 region 的 actor/target 必须直接放弃投掷。
     *
     * <p>归属判定必须早于「取时钟」与任何实体/玩家状态读取：取时钟在非 ticking lane 上会抛异常，
     * 而 {@code getEyeLocation}/{@code getBoundingBox} 在非 owner region 上也会抛异常。
     */
    @Test
    void 跨region的投掷者或目标必须放弃投掷且不消费任何状态() throws Exception {
        Fixture fixture = fixture();
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        GameTable table = hollowTable(actorId, targetId);
        fixture.runtime.players.put(actorId, fakePlayer(actorId, fixture.world, 0.0));
        fixture.runtime.players.put(targetId, fakePlayer(targetId, fixture.world, 2.0));
        fixture.runtime.owned = false;

        assertFalse(
            fixture.service.play(table, actorId, targetId, egg(), gadgetSettings(TableGadgetSettings.DEFAULT_MAX_ACTIVE)),
            "actor/target 不在当前 region 时必须放弃投掷"
        );
        assertEquals(0, fixture.service.trackedEffectCount(), "跨 region 不得留下效果条目");
        assertEquals(0, fixture.service.activeEntityCount(), "跨 region 不得占用实体名额");
        assertEquals(0, fixture.worldRecorder.spawnCalls, "跨 region 不得生成任何效果实体");
        assertEquals(0, fixture.runtime.currentTickCalls,
            "归属判定必须早于取时钟：非 ticking lane 上 Bukkit.getCurrentTick 会抛 No currently ticking region");
        assertTrue(fixture.logs.warningCount() >= 1,
            "跨 region 放弃投掷不能是静默失败：实服「投掷没反应又没有日志」正是这条静默 return false 造成的");
    }

    /**
     * 效果清理失败也必须归还名额，否则后续投掷会被永久静默拒绝。
     *
     * <p>用 {@code maxActive = 1} 把「名额」变成一眼可判的开关：只要有一条效果没有归还名额，
     * 下一次投掷连实体生成都不会尝试（{@code spawnCalls} 保持 0）；归还之后必须重新尝试生成。
     */
    @Test
    void 效果清理失败也必须归还名额否则后续投掷会被永久静默拒绝() throws Exception {
        Fixture fixture = fixture();
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        GameTable table = hollowTable(actorId, targetId);
        TableGadgetSettings settings = gadgetSettings(1);
        fixture.runtime.players.put(actorId, fakePlayer(actorId, fixture.world, 0.0));
        fixture.runtime.players.put(targetId, fakePlayer(targetId, fixture.world, 2.0));

        // 夹具先放一条已登记效果，占满唯一名额。
        trackEffect(fixture.service, table, actorId, targetId, fixture.world);
        assertEquals(1, fixture.service.activeEntityCount(), "夹具必须先占满唯一名额");

        // (a) 名额被占用时投掷被「静默」拒绝：连生成都不会尝试（无异常、无日志）。
        assertFalse(fixture.service.play(table, actorId, targetId, egg(), settings));
        assertEquals(0, fixture.worldRecorder.spawnCalls, "名额耗尽时不得尝试生成效果实体");

        // (b) 目标离开 region → 推进时判定目标失效 → 清理；实体删除失败（跨 region）也必须归还名额。
        fixture.runtime.players.remove(targetId);
        fixture.runtime.removeFails = true;
        fixture.service.tickTable(table);

        assertEquals(1, fixture.runtime.removeAttempts, "夹具必须真的尝试过删除实体");
        assertEquals(0, fixture.service.trackedEffectCount(), "清理失败也必须摘除效果条目");
        assertEquals(0, fixture.service.activeEntityCount(), "清理失败也必须归还实体名额，否则永久卡住");
        assertTrue(fixture.logs.warningCount() >= 1, "清理失败必须限频记录日志，不得静默吞掉");

        // (c) 名额归还后，后续投掷必须重新进入生成流程（不再被永久拒绝）。
        fixture.runtime.players.put(targetId, fakePlayer(targetId, fixture.world, 2.0));
        assertFalse(fixture.service.play(table, actorId, targetId, egg(), settings),
            "夹具的 fake World 返回 null 实体，生成必然失败——这里断言的是「尝试过」");
        assertEquals(1, fixture.worldRecorder.spawnCalls, "名额归还后必须重新尝试生成效果实体");
        // 生成中途失败同样必须释放：留下条目/名额就等于把下一次投掷一起锁死。
        assertEquals(0, fixture.service.trackedEffectCount(), "生成失败必须不留下效果条目");
        assertEquals(0, fixture.service.activeEntityCount(), "生成失败必须归还实体名额，否则后续投掷被永久拒绝");
    }

    /**
     * 扫描周期任务不得因为单桌派发异常被取消。
     *
     * <p>{@code MuzScheduler} 在周期回调抛异常时会取消整条任务；扫描任务一旦被取消，所有桌的
     * 效果推进与目标高亮都永久失效且无法自愈，是「投掷卡住」最严重的形态。
     */
    @Test
    void 扫描周期任务不得因为单桌派发异常被取消() throws Exception {
        SweepFixture fixture = sweepFixture();
        fixture.service.start();
        RecordedTask sweep = fixture.backend.lastGlobalTimer();
        assertNotNull(sweep, "扫描周期任务必须通过调度门面注册");

        sweep.fire();
        sweep.fire();

        assertFalse(sweep.cancelled(),
            "单桌派发抛 RuntimeException 后扫描任务必须仍然存活，否则所有桌效果永久失效");
        assertEquals(1, fixture.logs.warningCount(), "派发失败必须限频记录：两次连续失败只应落一条日志");
    }

    /**
     * 推进发生在非 ticking lane（未放置桌回退到 global）时，本轮必须「安静跳过」。
     *
     * <p>{@code Bukkit.getCurrentTick()} 在 global lane 上抛 {@code No currently ticking region}；
     * 该异常若逃出推进入口，会把整条 owner/global 任务打挂，之后所有效果与高亮永久失效。这里断言
     * 读不到时钟时只是本轮跳过：效果仍留在表里，等该桌重新放置后由锚点 region 正常收尾。
     */
    @Test
    void 非ticking_lane推进必须安静跳过且保留效果等待收尾() throws Exception {
        Fixture fixture = fixture();
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        GameTable table = hollowTable(actorId, targetId);
        fixture.runtime.players.put(actorId, fakePlayer(actorId, fixture.world, 0.0));
        fixture.runtime.players.put(targetId, fakePlayer(targetId, fixture.world, 2.0));
        trackEffect(fixture.service, table, actorId, targetId, fixture.world);
        assertEquals(1, fixture.service.trackedEffectCount(), "夹具必须先登记一条效果");

        fixture.runtime.clockFails = true;
        fixture.service.tickTable(table); // 不得抛出

        assertEquals(1, fixture.service.trackedEffectCount(),
            "读不到时钟时只是本轮跳过：效果仍在，等该桌重新放置后由锚点 region 收尾");
        assertEquals(0, fixture.runtime.removeAttempts, "读不到时钟时不得触碰实体");
        assertTrue(fixture.logs.warningCount() >= 1, "跳过必须限频记录，不得静默");
    }

    /**
     * 推进中途目标离开本 region 时必须立即释放状态，且后续投掷不被永久拒绝。
     *
     * <p>这是「投掷卡住」最核心的一种形态：效果实体在本 region 生成，但 target 走远、离开本 region 后，
     * 继续读他的位置在真实 Folia 上会抛 {@code Accessing entity state off owning region's thread}。
     * 必须把 target 判为失效并回收名额，否则名额永不归还，之后所有投掷都被静默拒绝。
     */
    @Test
    void 推进中途目标跨region必须释放状态且后续投掷不被永久拒绝() throws Exception {
        Fixture fixture = fixture();
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        GameTable table = hollowTable(actorId, targetId);
        TableGadgetSettings settings = gadgetSettings(1);
        fixture.runtime.players.put(actorId, fakePlayer(actorId, fixture.world, 0.0));
        fixture.runtime.players.put(targetId, fakePlayer(targetId, fixture.world, 2.0));
        // 用 maxActive=1 把名额变成一眼可判的开关：不归还就再也投不出去。
        trackEffect(fixture.service, table, actorId, targetId, fixture.world);
        assertEquals(1, fixture.service.activeEntityCount(), "夹具必须先占满唯一名额");

        // 目标离开本 region；读他的位置会像真实 Folia 一样抛异常。
        fixture.runtime.players.put(targetId, fakePlayer(targetId, fixture.world, 2.0, true));
        fixture.service.tickTable(table); // 不得抛出

        assertEquals(0, fixture.service.trackedEffectCount(), "目标跨 region 必须摘除效果条目");
        assertEquals(0, fixture.service.activeEntityCount(), "目标跨 region 必须归还实体名额，否则永久卡住");

        // 名额归还后，后续投掷必须重新进入生成流程（不再被永久拒绝）。
        fixture.runtime.players.put(targetId, fakePlayer(targetId, fixture.world, 2.0));
        assertFalse(fixture.service.play(table, actorId, targetId, egg(), settings),
            "夹具的 fake World 返回 null 实体，生成必然失败——这里断言的是「尝试过」");
        assertEquals(1, fixture.worldRecorder.spawnCalls, "名额归还后必须重新尝试生成效果实体");
    }

    /* ------------------------------------------------------------------ 夹具 ------------------------------------------------------------------ */

    /** 效果服务测试夹具：空心插件 + 注入的运行时替身 + fake World。 */
    private static final class Fixture {
        private final DoudizhuPlugin plugin;
        private final TableGadgetEffectService service;
        private final StubRuntime runtime;
        private final WorldRecorder worldRecorder;
        private final World world;
        private final CollectingLogger logs;

        private Fixture(
            DoudizhuPlugin plugin,
            TableGadgetEffectService service,
            StubRuntime runtime,
            WorldRecorder worldRecorder,
            World world,
            CollectingLogger logs
        ) {
            this.plugin = plugin;
            this.service = service;
            this.runtime = runtime;
            this.worldRecorder = worldRecorder;
            this.world = world;
            this.logs = logs;
        }
    }

    private static Fixture fixture() throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        CollectingLogger logs = new CollectingLogger();
        setField(plugin, "logger", logs.logger());
        WorldRecorder worldRecorder = new WorldRecorder();
        StubRuntime runtime = new StubRuntime();
        TableGadgetEffectService service = new TableGadgetEffectService(plugin, runtime);
        return new Fixture(plugin, service, runtime, worldRecorder, fakeWorld(worldRecorder), logs);
    }

    /** 扫描任务夹具：真实 {@link TableManager} + 只投递一次性任务就抛异常的调度后端。 */
    private static final class SweepFixture {
        private final SweepBackend backend;
        private final TableManager tableManager;
        private final TableGadgetService service;
        private final CollectingLogger logs;

        private SweepFixture(
            SweepBackend backend,
            TableManager tableManager,
            TableGadgetService service,
            CollectingLogger logs
        ) {
            this.backend = backend;
            this.tableManager = tableManager;
            this.service = service;
            this.logs = logs;
        }
    }

    private static SweepFixture sweepFixture() throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        CollectingLogger logs = new CollectingLogger();
        setField(plugin, "logger", logs.logger());
        // start() 会注册生命周期监听器，需要一个能吞下 registerEvents 的服务端替身。
        setField(plugin, "server", fakeServer());
        SweepBackend backend = new SweepBackend();
        setField(plugin, "scheduler", new MuzScheduler(backend));
        TableManager tableManager = new TableManager(plugin);
        setField(plugin, "tableManager", tableManager);
        TableGadgetEffectService effects = new TableGadgetEffectService(plugin, new StubRuntime());
        PlayerOutputDispatcher output = new PlayerOutputDispatcher(
            PlayerTaskRegistry.uuidFirst(playerId -> null, (playerId, delayTicks, task) -> new RecordedHandle()));
        TableGadgetService service =
            new TableGadgetService(plugin, effects, gadgetSettings(TableGadgetSettings.DEFAULT_MAX_ACTIVE), output);
        // 空心逻辑桌直接登记进真实 TableManager：锚点缺失 → runTableNow 回退 global 一次性任务 → 后端抛异常。
        GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
        setField(table, "name", "release-test");
        putTable(tableManager, "release-test", table);
        return new SweepFixture(backend, tableManager, service, logs);
    }

    /** 效果服务的包内运行时替身：可控制时钟、玩家解析、实体归属，并让实体移除失败。 */
    private static final class StubRuntime implements TableGadgetEffectService.EffectRuntime {
        private final Map<UUID, Player> players = new ConcurrentHashMap<>();
        /** 位于别的 region 的玩家；读取他们的位置在 Folia 上会抛异常。 */
        private final java.util.Set<UUID> foreignPlayers = ConcurrentHashMap.newKeySet();
        private boolean owned = true;
        private boolean removeFails;
        private boolean clockFails;
        private int removeAttempts;
        private int currentTickCalls;

        @Override
        public int currentTick() {
            currentTickCalls++;
            // 未放置桌的 owner lane 回退到 global，那里没有 ticking region。
            if (clockFails) {
                throw new IllegalStateException("No currently ticking region");
            }
            return 7;
        }

        @Override
        public Player player(UUID playerId) {
            return players.get(playerId);
        }

        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            if (!owned || entity == null) {
                return false;
            }
            return !(entity instanceof Player player) || !foreignPlayers.contains(player.getUniqueId());
        }

        @Override
        public void remove(Entity entity) {
            removeAttempts++;
            if (removeFails) {
                throw new IllegalStateException("模拟跨 region 实体删除失败");
            }
        }
    }

    /** fake World 的调用记录；spawn 返回 null 用于模拟「生成中途失败」。 */
    private static final class WorldRecorder {
        private int spawnCalls;
    }

    private static World fakeWorld(WorldRecorder recorder) {
        ClassLoader loader = TableGadgetEffectReleaseBehaviorTest.class.getClassLoader();
        World world = (World) Proxy.newProxyInstance(loader, new Class<?>[] {World.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "spawn":
                    // VersionCompat.spawnEntity 走 World#spawn；返回 null 即「Paper 未返回实体」。
                    recorder.spawnCalls++;
                    return null;
                case "rayTraceBlocks":
                    // 命中判定返回 null 表示没有方块遮挡。
                    return null;
                case "getName":
                    return "fake-world";
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
        WORLD_STRONG_REFS.add(world);
        return world;
    }

    /**
     * fake 效果实体。
     *
     * <p>{@code remove()} 直接抛异常：这正是 Folia 在非 owner region 上的真实行为，夹具必须让
     * 「删除实体」这条路径无论走生产代码的哪一层都失败，才能证明名额归还只依赖记账顺序。
     */
    private static ItemDisplay fakeDisplay(World world) {
        ClassLoader loader = TableGadgetEffectReleaseBehaviorTest.class.getClassLoader();
        return (ItemDisplay) Proxy.newProxyInstance(loader, new Class<?>[] {ItemDisplay.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "remove":
                    throw new IllegalStateException("Accessing entity state off owning region's thread");
                case "getWorld":
                    return world;
                case "isDead":
                case "isValid":
                    return false;
                case "toString":
                    return "fakeDisplay";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    private static Player fakePlayer(UUID playerId, World world, double x) {
        return fakePlayer(playerId, world, x, false);
    }

    /**
     * fake 在线玩家。
     *
     * @param foreign true 表示这个玩家位于别的 region：读他的 {@code getLocation()} /
     *                {@code getEyeLocation()} 会像真实 Folia 一样抛
     *                {@code Accessing entity state off owning region's thread}，用于证明生产代码
     *                不会跨 region 读位置。
     */
    private static Player fakePlayer(UUID playerId, World world, double x, boolean foreign) {
        Location location = new Location(world, x, 64.0, 0.0, 0.0f, 0.0f);
        ClassLoader loader = TableGadgetEffectReleaseBehaviorTest.class.getClassLoader();
        return (Player) Proxy.newProxyInstance(loader, new Class<?>[] {Player.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getUniqueId":
                    return playerId;
                case "isOnline":
                    return true;
                case "getWorld":
                    return world;
                case "getLocation":
                    if (foreign) {
                        throw new IllegalStateException("Accessing entity state off owning region's thread");
                    }
                    return location.clone();
                // getEyeLocation 是可见性判定用的视线点：两名 fake 玩家必须分开，否则距离为 0 直接判定不可见。
                case "getEyeLocation":
                    if (foreign) {
                        throw new IllegalStateException("Accessing entity state off owning region's thread");
                    }
                    return location.clone().add(0.0, 1.6, 0.0);
                case "getName":
                    return "fake-" + playerId;
                case "toString":
                    return "fakePlayer";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    private static Server fakeServer() {
        ClassLoader loader = TableGadgetEffectReleaseBehaviorTest.class.getClassLoader();
        PluginManager pluginManager =
            (PluginManager) Proxy.newProxyInstance(loader, new Class<?>[] {PluginManager.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "toString":
                        return "fakePluginManager";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        return (Server) Proxy.newProxyInstance(loader, new Class<?>[] {Server.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getPluginManager":
                    return pluginManager;
                case "toString":
                    return "fakeServer";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return defaultValue(method.getReturnType());
            }
        });
    }

    /** 空心逻辑桌：只补效果服务会读到的字段（phase / seats / botNames / name）。 */
    private static GameTable hollowTable(UUID... seatIds) throws Exception {
        GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
        setField(table, "name", "hollow-table");
        setField(table, "phase", GamePhase.PLAYING);
        List<UUID> seats = new ArrayList<>(List.of(seatIds));
        setField(table, "seats", seats);
        setField(table, "botNames", new LinkedHashMap<UUID, String>());
        return table;
    }

    /**
     * 反射登记一条真实 {@code ProjectileEffect}：效果类型是私有内部类，测试无法直接构造。
     *
     * <p>只构造、不生成实体，用于把「名额已被占用」这一前置状态做成可控夹具。
     */
    private static void trackEffect(
        TableGadgetEffectService service,
        GameTable table,
        UUID actorId,
        UUID targetId,
        World world
    ) throws Exception {
        Class<?> projectile = Class.forName("linmumua.doudizhu.game.TableGadgetEffectService$ProjectileEffect");
        Constructor<?> constructor = projectile.getDeclaredConstructor(
            TableGadgetEffectService.class, UUID.class, GameTable.class, UUID.class, UUID.class,
            ItemDisplay.class, Location.class, Location.class, int.class, World.class);
        constructor.setAccessible(true);
        UUID effectId = UUID.randomUUID();
        Object effect = constructor.newInstance(
            service, effectId, table, actorId, targetId, fakeDisplay(world),
            new Location(world, 0.0, 65.0, 0.0), new Location(world, 2.0, 65.0, 0.0), 10, world);
        effectsMap(service).put(effectId, effect);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> effectsMap(TableGadgetEffectService service) throws Exception {
        Field field = TableGadgetEffectService.class.getDeclaredField("effects");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(service);
    }

    @SuppressWarnings("unchecked")
    private static void putTable(TableManager manager, String key, GameTable table) throws Exception {
        Field field = TableManager.class.getDeclaredField("tables");
        field.setAccessible(true);
        ((Map<String, GameTable>) field.get(manager)).put(key, table);
    }

    /** 只替代需要服务端 ItemFactory 的操作：正式 play 只读 getType/clone。 */
    private static ItemStack egg() {
        return new TestItem(Material.EGG);
    }

    private static TableGadgetSettings gadgetSettings(int maxActive) {
        return new TableGadgetSettings(
            true,
            TableGadgetSettings.DEFAULT_RANGE,
            TableGadgetSettings.DEFAULT_COOLDOWN_TICKS,
            TableGadgetSettings.DEFAULT_FLIGHT_TICKS,
            TableGadgetSettings.DEFAULT_WATER_TICKS,
            maxActive
        );
    }

    /** 只登记、由测试自己 fire 的调度后端；一次性 global/region 任务按需抛异常。 */
    private static final class SweepBackend implements SchedulerBackend {
        private final List<RecordedTask> globalTimers = new ArrayList<>();

        private RecordedTask lastGlobalTimer() {
            return globalTimers.get(globalTimers.size() - 1);
        }

        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period, Consumer<MuzScheduler.TaskHandle> task) {
            if (period == 0L) {
                // 牌桌 owner 一次性任务的派发失败：模拟 TableManager 侧派发异常。
                throw new IllegalStateException("模拟牌桌 owner 派发失败");
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
            if (period == 0L) {
                throw new IllegalStateException("模拟牌桌 owner 派发失败");
            }
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
            return "release-test";
        }
    }

    /** 收集日志的真实 Logger：断言「失败必须限频记录」时用它统计 WARNING 条数。 */
    private static final class CollectingLogger {
        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger = Logger.getLogger(
            "TableGadgetEffectReleaseBehaviorTest-" + UUID.randomUUID());

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
    }

    /** 只替代需要服务端 ItemFactory 的操作，其余由正式代码执行（沿用仓库既有夹具写法）。 */
    private static final class TestItem extends ItemStack {
        private final Material material;

        private TestItem(Material material) {
            this.material = material;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public ItemStack clone() {
            return new TestItem(material);
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

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    /** 依次向上查找声明类：logger/server 等字段声明在 JavaPlugin 上，不在 DoudizhuPlugin 上。 */
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
