package linmumua.doudizhu.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.scheduler.MuzScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 语音面板清理的 lane 归属行为测试。
 *
 * <p>意图（shutdown-review 指出的关闭期问题）：面板实体只能在它所属 region 的线程上删除。清理入口
 * （{@code clearPlayer}/{@code clearTable}/{@code clearAll}/{@code shutdown}）可能跑在玩家 lane（玩家
 * 已走远、离开面板所在 region）、桌 owner lane，或 {@code onDisable} 的主线程（global，没有 region）上。
 * 旧实现直接在调用线程 {@code display.remove()}，会像 Folia 一样抛
 * {@code Accessing entity state off owning region's thread}，并把异常沿 {@code onDisable} 链路外溢；
 * 而且 {@code shutdown()} 把 {@code stopped = true} 放在清理之后，异常一抛，服务既不关闭也不清空。
 *
 * <p>本测试用 {@link TableSpeechPanelService.PanelEntityLane} 替身（可指定「当前线程不拥有实体」并让
 * 删除投递失败）+ JDK 动态代理 fake {@link TextDisplay}/{@link World}/{@link Server} + {@link Unsafe}
 * 分配的空心 {@link DoudizhuPlugin}/{@link GameTable}，直接驱动**生产方法**并断言：
 * <ol>
 *   <li>错误 lane 上不得直接删实体，必须把删除投回实体自己的 region；</li>
 *   <li>owner lane 上直接删、不投递；</li>
 *   <li>删除投递失败必须留痕（不吞异常），且状态仍被摘除；</li>
 *   <li>关服期不得触碰实体；</li>
 *   <li>{@code shutdown()} 必封入口、清空 owner、幂等，即使清理失败也不外抛。</li>
 * </ol>
 * 夹具风格沿用仓库既有做法（见 {@code TableGadgetEffectReleaseBehaviorTest}）。
 */
class TableSpeechPanelServiceCleanupBehaviorTest {
    /** 强引用夹具 Proxy world：Paper 26.x 的 Location 只用弱引用持有 world，避免被 GC 回收。 */
    private static final List<World> WORLD_STRONG_REFS = new ArrayList<>();

    /**
     * 错误 lane 上清理必须把删除投回实体 region，绝不直接删。
     *
     * <p>替身在归属为假时 {@code removeNow} 直接抛异常——这正是 Folia 的真实行为。因此若生产代码未做归属
     * 判定就 {@code removeNow}，本用例会因抛异常而失败；这正是本条断言能「业务逻辑一改就红」的原因。
     */
    @Test
    void 错误lane上清理必须把删除投回实体region且不直接删() throws Exception {
        Fixture fixture = fixture();
        UUID ownerId = UUID.randomUUID();
        TextDisplayBundle display = fakeDisplay();
        seedSession(fixture.service, hollowTable(), ownerId, display.display());
        fixture.lane.owned = false;

        fixture.service.clearPlayer(ownerId); // 必须在错误 lane 上不抛异常

        assertEquals(0, fixture.lane.removeNowCalls, "错误 lane 上不得直接删除面板实体");
        assertEquals(1, fixture.lane.dispatchCalls, "错误 lane 上必须把删除投回实体自己的 region");
        assertEquals(0, display.removeCalls, "投递的删除尚未执行，实体此时不应被删");
        assertEquals(0, fixture.service.ownerCount(), "清理后 owner 会话必须摘除");

        // 投递出去的删除在实体所属 region 上执行：那里归属为真。
        fixture.lane.owned = true;
        fixture.lane.runPending();
        assertEquals(1, fixture.lane.removeNowCalls, "投递的删除任务必须真的删除实体");
        assertEquals(1, display.removeCalls, "投递的删除任务必须真的删除实体");
    }

    /** owner lane 上清理必须直接删、不投递，避免无谓的跨 lane 往返。 */
    @Test
    void owner_lane上清理必须直接删除且不投递() throws Exception {
        Fixture fixture = fixture();
        UUID ownerId = UUID.randomUUID();
        TextDisplayBundle display = fakeDisplay();
        seedSession(fixture.service, hollowTable(), ownerId, display.display());
        fixture.lane.owned = true;

        fixture.service.clearPlayer(ownerId);

        assertEquals(1, fixture.lane.removeNowCalls, "owner lane 上必须直接删除");
        assertEquals(0, fixture.lane.dispatchCalls, "owner lane 上无需投递");
        assertEquals(1, display.removeCalls, "实体必须被删除");
        assertEquals(0, fixture.service.ownerCount(), "清理后 owner 会话必须摘除");
    }

    /** 关桌清理（{@code GameTable.shutdown} 在 onDisable 期间也会走）同样必须按 lane 归属删实体。 */
    @Test
    void 关桌清理同样必须把删除投回实体region() throws Exception {
        Fixture fixture = fixture();
        UUID ownerId = UUID.randomUUID();
        GameTable table = hollowTable();
        TextDisplayBundle display = fakeDisplay();
        seedSession(fixture.service, table, ownerId, display.display());
        fixture.lane.owned = false;

        fixture.service.clearTable(table);

        assertEquals(0, fixture.lane.removeNowCalls, "关桌清理不得在错误 lane 上直接删实体");
        assertEquals(1, fixture.lane.dispatchCalls, "关桌清理必须把删除投回实体自己的 region");
        assertEquals(0, fixture.service.ownerCount(), "关桌后 owner 会话必须摘除");
    }

    /**
     * 删除投递失败（世界已卸载/调度拒绝）必须留痕且不吞异常。
     *
     * <p>这条守护的是「删不掉又没有日志」这一类实服最难排查的问题：失败可以放弃，但不能无声。
     */
    @Test
    void 删除投递失败必须留痕且不吞异常() throws Exception {
        Fixture fixture = fixture();
        UUID ownerId = UUID.randomUUID();
        TextDisplayBundle display = fakeDisplay();
        seedSession(fixture.service, hollowTable(), ownerId, display.display());
        fixture.lane.owned = false;
        fixture.lane.dispatchSucceeds = false;

        fixture.service.clearPlayer(ownerId); // 不得抛出

        assertEquals(0, fixture.lane.removeNowCalls, "投递失败时也不得在错误 lane 上直接删");
        assertEquals(1, fixture.lane.dispatchCalls, "必须先尝试投递");
        assertEquals(0, display.removeCalls, "投递失败，实体不会被删");
        assertEquals(0, fixture.service.ownerCount(), "清理失败也必须摘除 owner 会话");
        assertTrue(fixture.logs.warningCount() >= 1,
            "删除投递失败必须留痕：静默会让「面板删不掉又不报错」在实服无法排查");
    }

    /**
     * 关服期不得触碰实体。
     *
     * <p>关服时区域线程可能已不可用，实体随世界保存销毁；这里只清追踪，绝不在非法 owner 上操作实体
     *（与 {@code PhysicalTableManager} / {@code MahjongTableManager} 的 shutdown 分支同口径）。
     */
    @Test
    void 关服期清理不得触碰实体() throws Exception {
        Fixture fixture = fixture(server(true));
        UUID ownerId = UUID.randomUUID();
        TextDisplayBundle display = fakeDisplay();
        seedSession(fixture.service, hollowTable(), ownerId, display.display());
        fixture.lane.owned = true;

        fixture.service.clearPlayer(ownerId); // 不得抛出

        assertEquals(0, fixture.lane.removeNowCalls, "关服期不得直接删实体");
        assertEquals(0, fixture.lane.dispatchCalls, "关服期不得投递删除任务");
        assertEquals(0, display.removeCalls, "关服期不得触碰实体");
        assertEquals(0, fixture.service.ownerCount(), "关服期仍须清掉追踪状态");
    }

    /** {@code shutdown()} 必须封入口、清空全部 owner，并幂等。 */
    @Test
    void shutdown必须封入口并清空所有owner且幂等() throws Exception {
        Fixture fixture = fixture();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        TextDisplayBundle first = fakeDisplay();
        TextDisplayBundle second = fakeDisplay();
        GameTable table = hollowTable();
        seedSession(fixture.service, table, firstId, first.display());
        seedSession(fixture.service, table, secondId, second.display());
        fixture.lane.owned = true;
        assertEquals(2, fixture.service.ownerCount(), "夹具必须先有两个 owner");

        fixture.service.shutdown();

        assertEquals(0, fixture.service.ownerCount(), "shutdown 必须清空所有 owner");
        assertEquals(2, fixture.lane.removeNowCalls, "owner lane 上的两张面板都必须被删");

        // 幂等：再次 shutdown 不得重复删除，也不得抛异常。
        fixture.service.shutdown();
        assertEquals(2, fixture.lane.removeNowCalls, "shutdown 必须幂等");

        // 入口已封：shutdown 后 open 不得再创建面板（也不得触碰 Bukkit）。
        fixture.service.open(table, firstId);
        assertEquals(0, fixture.service.ownerCount(), "shutdown 后 open 必须为 no-op");
    }

    /**
     * 关闭期在错误 lane 且投递失败时，{@code shutdown()} 仍必须封入口并清空，不得外抛。
     *
     * <p>这是 shutdown-review 指出的原始场景：{@code onDisable} 主线程上 inline remove 跨 region 抛异常，
     * 旧实现下 {@code stopped} 永远为 false。本用例断言新实现下关闭语义一定落地。
     */
    @Test
    void shutdown在错误lane且投递失败时仍封入口且不外抛() throws Exception {
        Fixture fixture = fixture();
        UUID ownerId = UUID.randomUUID();
        TextDisplayBundle display = fakeDisplay();
        GameTable table = hollowTable();
        seedSession(fixture.service, table, ownerId, display.display());
        fixture.lane.owned = false;
        fixture.lane.dispatchSucceeds = false;

        fixture.service.shutdown(); // 不得抛出

        assertEquals(0, fixture.lane.removeNowCalls, "错误 lane 上不得直接删实体");
        assertEquals(0, fixture.service.ownerCount(), "即便清理失败，shutdown 也必须清空 owner");
        assertTrue(fixture.logs.warningCount() >= 1, "清理失败必须留痕");

        // stopped 必须已置位：再次 open 直接 no-op。
        fixture.service.open(table, ownerId);
        assertEquals(0, fixture.service.ownerCount(), "shutdown 后 open 必须为 no-op（stopped 已置位）");
    }

    /* ------------------------------------------------------------------ 夹具 ------------------------------------------------------------------ */

    private static final class Fixture {
        private final DoudizhuPlugin plugin;
        private final TableSpeechPanelService service;
        private final StubLane lane;
        private final CollectingLogger logs;

        private Fixture(
            DoudizhuPlugin plugin,
            TableSpeechPanelService service,
            StubLane lane,
            CollectingLogger logs
        ) {
            this.plugin = plugin;
            this.service = service;
            this.lane = lane;
            this.logs = logs;
        }
    }

    private static Fixture fixture() throws Exception {
        return fixture(server(false));
    }

    private static Fixture fixture(Server server) throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        CollectingLogger logs = new CollectingLogger();
        setField(plugin, "logger", logs.logger());
        setField(plugin, "server", server);
        StubLane lane = new StubLane();
        PlayerOutputDispatcher output = new PlayerOutputDispatcher(
            PlayerTaskRegistry.uuidFirst(playerId -> null, (playerId, delayTicks, task) -> new RecordedHandle()));
        TableSpeechPanelService service = new TableSpeechPanelService(
            plugin,
            new TableSpeechPanelService.Settings(true),
            (table, ownerId) -> List.of(),
            List::of,
            (table, ownerId, targetId, entry) -> {
            },
            output,
            (player, maxDistance) -> Double.POSITIVE_INFINITY,
            () -> 7,
            lane
        );
        return new Fixture(plugin, service, lane, logs);
    }

    /** 面板实体 lane 替身：可指定归属，并让删除投递失败。 */
    private static final class StubLane implements TableSpeechPanelService.PanelEntityLane {
        private boolean owned;
        private boolean dispatchSucceeds = true;
        private int removeNowCalls;
        private int dispatchCalls;
        private Runnable pending;

        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            return owned;
        }

        @Override
        public void removeNow(Entity entity) {
            // 归属为假时删除即非法：与 Folia 的真实行为一致，用于证明生产代码不会在错误 lane 上直接删。
            if (!owned) {
                throw new IllegalStateException("Accessing entity state off owning region's thread");
            }
            removeNowCalls++;
            entity.remove();
        }

        @Override
        public boolean removeOnOwnerRegion(Location anchor, Runnable removal) {
            dispatchCalls++;
            if (!dispatchSucceeds) {
                return false;
            }
            pending = removal;
            return true;
        }

        private void runPending() {
            Runnable removal = pending;
            pending = null;
            if (removal != null) {
                removal.run();
            }
        }
    }

    /** fake 面板实体及其 {@code remove()} 调用记录。 */
    private static final class TextDisplayBundle {
        private TextDisplay display;
        private int removeCalls;

        private TextDisplay display() {
            return display;
        }
    }

    private static TextDisplayBundle fakeDisplay() {
        TextDisplayBundle bundle = new TextDisplayBundle();
        World world = fakeWorld();
        bundle.display = (TextDisplay) Proxy.newProxyInstance(
            TableSpeechPanelServiceCleanupBehaviorTest.class.getClassLoader(),
            new Class<?>[] {TextDisplay.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "remove":
                        bundle.removeCalls++;
                        return null;
                    case "isValid":
                        return true;
                    case "getWorld":
                        return world;
                    case "getUniqueId":
                        return UUID.randomUUID();
                    case "toString":
                        return "fakeTextDisplay";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            }
        );
        return bundle;
    }

    /** 反射往 {@code owners} 里塞一个真实 {@code OwnerSession} 并挂一张面板实体（类型私有，只能反射构造）。 */
    private static void seedSession(
        TableSpeechPanelService service,
        GameTable table,
        UUID ownerId,
        TextDisplay display
    ) throws Exception {
        Location center = new Location(fakeWorld(), 4.5, 64.0, 4.5);
        TableSpeechPanelService.Panel panel = new TableSpeechPanelService.Panel(center, 0.5, 0.3, 0.0f);
        TableSpeechPanelService.SpeechEntry entry =
            new TableSpeechPanelService.SpeechEntry("hurry", Component.text("催促"), panel, true);

        Class<?> sessionType = Class.forName("linmumua.doudizhu.game.TableSpeechPanelService$OwnerSession");
        Constructor<?> sessionCtor = sessionType.getDeclaredConstructor(GameTable.class, UUID.class, int.class);
        sessionCtor.setAccessible(true);
        Object session = sessionCtor.newInstance(table, ownerId, 1);

        Class<?> viewType = Class.forName("linmumua.doudizhu.game.TableSpeechPanelService$PanelView");
        Constructor<?> viewCtor =
            viewType.getDeclaredConstructor(TableSpeechPanelService.SpeechEntry.class, TextDisplay.class);
        viewCtor.setAccessible(true);
        Object view = viewCtor.newInstance(entry, display);
        panelList(session).add(view);

        ownersMap(service).put(ownerId, session);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> ownersMap(TableSpeechPanelService service) throws Exception {
        Field field = TableSpeechPanelService.class.getDeclaredField("owners");
        field.setAccessible(true);
        return (Map<UUID, Object>) field.get(service);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> panelList(Object session) throws Exception {
        Field field = session.getClass().getDeclaredField("panels");
        field.setAccessible(true);
        return (List<Object>) field.get(session);
    }

    private static GameTable hollowTable() throws Exception {
        GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
        setField(table, "name", "speech-cleanup-test");
        return table;
    }

    private static World fakeWorld() {
        World world = (World) Proxy.newProxyInstance(
            TableSpeechPanelServiceCleanupBehaviorTest.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
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
            }
        );
        WORLD_STRONG_REFS.add(world);
        return world;
    }

    private static Server server(boolean stopping) {
        return (Server) Proxy.newProxyInstance(
            TableSpeechPanelServiceCleanupBehaviorTest.class.getClassLoader(),
            new Class<?>[] {Server.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "isStopping":
                        return stopping;
                    case "toString":
                        return "fakeServer";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            }
        );
    }

    private static final class RecordedHandle implements MuzScheduler.TaskHandle {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public String ownerId() {
            return "speech-cleanup-test";
        }
    }

    /** 收集日志的真实 Logger：断言「失败必须留痕」时统计 WARNING 条数。 */
    private static final class CollectingLogger {
        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger = Logger.getLogger(
            "TableSpeechPanelServiceCleanupBehaviorTest-" + UUID.randomUUID());

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
