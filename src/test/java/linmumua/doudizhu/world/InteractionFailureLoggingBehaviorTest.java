package linmumua.doudizhu.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
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
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/**
 * 交互处理 catch 的服务端留痕行为测试。
 *
 * <p>【为什么必须留痕】{@code PhysicalTableManager.handleInteraction} 及其同源的点击 catch（手牌选牌/出牌）
 * 会把任意 {@code RuntimeException} 转成一条玩家 ActionBar 提示。此前这些 catch 只提示、不写服务端日志，
 * 于是真正的失败——典型是开局 {@code runAtFixedRate(0, 1, …)} 被调度器拒绝抛出的
 * {@code IllegalArgumentException}——在服务端完全不可见：玩家只看到一句红字，控制台没有任何堆栈，
 * 「卡在正在发牌」这类问题事后无从定位（AGENTS 里记录的真实案例）。
 *
 * <p>本测试用 {@link Unsafe} 夹具驱动**真实** {@code handleInteraction}（按钮动作拒绝场景），锁定三条契约：
 * <ol>
 *   <li>catch 必须记一条带完整异常的服务端日志（含动作与玩家上下文），不再静默；</li>
 *   <li>同一「动作 + 异常摘要」在限频窗口内只记一条，避免高频点击刷屏；</li>
 *   <li>玩家提示与对局流程语义不变：仍返回 {@code true}（事件被当成已处理），且不同动作/不同异常的
 *       失败各自留痕，不被彼此的限频吞掉。</li>
 * </ol>
 */
class InteractionFailureLoggingBehaviorTest {
    /**
     * 强引用夹具 Proxy world。
     *
     * <p>Paper 26.x 的 {@link Location} 只用弱引用持有 world，不额外持强引用时套件运行期间该 Proxy
     * 可能被 GC 回收，使 {@code Location.getWorld()} 抛 {@code World unloaded}。这是夹具自身的
     * GC 稳定性保障，不改变生产代码语义。
     */
    private static final List<World> WORLD_STRONG_REFS = new ArrayList<>();

    @Test
    void 交互失败必须留痕且限频且流程语义不变() throws Exception {
        CapturedLogs logs = new CapturedLogs();
        Fixture fixture = fixture(logs.logger());
        UUID playerId = UUID.fromString("00000000-0000-0000-0000-00000000a001");
        fixture.registerTable("t1", playerId);
        UUID passEntityId = UUID.fromString("00000000-0000-0000-0000-00000000b001");
        fixture.bindAction(passEntityId, "t1", PhysicalTableManager.ButtonAction.PASS_TURN, 0);
        Player player = playerStub(playerId, "Alice", fixture.world);
        Entity passEntity = actionEntityStub(passEntityId, fixture.world);

        // 规则拒绝：不在出牌阶段 → pass() 抛 IllegalStateException("现在还不到出牌的时候。")
        boolean handled = fixture.manager.handleInteraction(player, passEntity);

        assertTrue(handled, "按钮点击仍必须被当成已处理返回 true，流程语义不变");
        assertEquals(1, logs.records.size(),
            "catch 只提示不记日志是缺陷：必须补一条服务端日志，实际=" + logs.messages());
        LogRecord record = logs.records.get(0);
        assertEquals(Level.WARNING, record.getLevel(), "交互失败必须按 WARNING 记录");
        assertNotNull(record.getThrown(), "必须带上异常本体，否则实服只看得到一句提示、看不到堆栈");
        assertTrue(record.getThrown() instanceof IllegalStateException,
            "记录的必须是真实抛出的异常，实际=" + record.getThrown());
        assertTrue(record.getMessage().contains("PASS_TURN"),
            "日志必须带上动作，否则无法定位是哪次点击失败：" + record.getMessage());
        assertTrue(record.getMessage().contains("Alice"),
            "日志必须带上玩家，否则无法定位是谁触发：" + record.getMessage());
        assertTrue(record.getMessage().contains("现在还不到出牌的时候。"),
            "日志必须包含异常文本：" + record.getMessage());

        // 立刻重复同一动作：同一「动作 + 异常摘要」在窗口内必须限频。
        fixture.manager.handleInteraction(player, passEntity);
        assertEquals(1, logs.records.size(),
            "同一动作+同一异常在限频窗口内只允许一条，否则高频点击会刷屏：" + logs.messages());

        // 换一个动作（不同异常文本）不得被上面的限频吞掉：READY 走 toggleReady → 阶段拒绝。
        UUID readyEntityId = UUID.fromString("00000000-0000-0000-0000-00000000b002");
        fixture.bindAction(readyEntityId, "t1", PhysicalTableManager.ButtonAction.READY, 0);
        fixture.manager.handleInteraction(player, actionEntityStub(readyEntityId, fixture.world));

        assertEquals(2, logs.records.size(),
            "不同动作/不同异常的失败必须各自留痕，限频不得跨原因吞掉：" + logs.messages());
        assertTrue(logs.messages().stream().anyMatch(text -> text.contains("现在不是准备阶段。")),
            "换动作后的失败也必须留痕：" + logs.messages());
    }

    // ---- 夹具 ----

    private static final class Fixture {
        private final PhysicalTableManager manager;
        private final TableManager tableManager;
        private final World world;

        private Fixture(PhysicalTableManager manager, TableManager tableManager, World world) {
            this.manager = manager;
            this.tableManager = tableManager;
            this.world = world;
        }

        /**
         * 插一张最小逻辑桌：只补 {@code plugin} / {@code name} / {@code seats}。
         *
         * <p>{@code seats} 必须含该玩家，否则 {@code pass}/{@code toggleReady} 会先在
         * {@code requireAtTable}（"你不在这桌。"）处停下来，测不到我们要的阶段拒绝分支。
         * {@code phase} 保持 null，于是 {@code ensurePhase(...)} 必然拒绝并抛出带文本的领域异常。
         */
        private void registerTable(String name, UUID playerId) throws Exception {
            GameTable table = (GameTable) unsafe().allocateInstance(GameTable.class);
            setField(table, "plugin", null);
            setField(table, "name", name);
            setField(table, "seats", new ArrayList<>(List.of(playerId)));
            @SuppressWarnings("unchecked")
            Map<String, GameTable> tables = (Map<String, GameTable>) field(TableManager.class, "tables").get(tableManager);
            tables.put(name.trim().toLowerCase(java.util.Locale.ROOT), table);
        }

        /** 给一个按钮实体登记 ActionBinding——走生产同形的私有 record，避免自造一份形状。 */
        private void bindAction(UUID entityId, String tableName, PhysicalTableManager.ButtonAction action, Integer seat)
            throws Exception {
            Class<?> bindingType = Class.forName("linmumua.doudizhu.world.PhysicalTableManager$ActionBinding");
            Constructor<?> canonical = bindingType.getDeclaredConstructors()[0];
            canonical.setAccessible(true);
            Object binding = canonical.newInstance(tableName, action, seat);
            @SuppressWarnings("unchecked")
            Map<UUID, Object> bindings =
                (Map<UUID, Object>) field(PhysicalTableManager.class, "actionBindings").get(manager);
            bindings.put(entityId, binding);
        }
    }

    private static Fixture fixture(Logger logger) throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        // Unsafe 分配出的插件实例没有 PluginMeta，而管理器构造器要用 NamespacedKey(plugin, …) 取 namespace。
        setField(plugin, "pluginMeta", pluginMetaStub());
        setField(plugin, "logger", logger);
        // 调度换成只登记、不执行的后端替身：hint 会经 player lane 投递，不该在测试线程真的碰 Bukkit。
        setField(plugin, "scheduler", new MuzScheduler(new RecordingBackend()));
        TableManager tableManager = new TableManager(plugin);
        setField(plugin, "tableManager", tableManager);
        PhysicalTableManager manager = new PhysicalTableManager(plugin);
        setField(plugin, "physicalTableManager", manager);
        return new Fixture(manager, tableManager, eyeWorld());
    }

    /** 眼睛位置取 (0,0,0)，判定框取 (0,0,0)..(1,1,1)，按钮距离判为 0，必在 3 格范围内。 */
    private static Player playerStub(UUID playerId, String name, World world) {
        return (Player) Proxy.newProxyInstance(
            InteractionFailureLoggingBehaviorTest.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getUniqueId":
                        return playerId;
                    case "getName":
                        return name;
                    case "getWorld":
                        return world;
                    case "getEyeLocation":
                        return new Location(world, 0.0, 0.0, 0.0);
                    case "isOnline":
                        return true;
                    case "toString":
                        return "player:" + name;
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
    }

    private static Entity actionEntityStub(UUID entityId, World world) {
        return (Entity) Proxy.newProxyInstance(
            InteractionFailureLoggingBehaviorTest.class.getClassLoader(),
            new Class<?>[] {Entity.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getUniqueId":
                        return entityId;
                    case "getWorld":
                        return world;
                    case "getLocation":
                        return new Location(world, 0.0, 0.0, 0.0);
                    case "getBoundingBox":
                        return new BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
                    case "toString":
                        return "actionEntity";
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
     * 所有方块都视为已加载的最小 fake World：本测试只需要 world 身份与相等性
     * （{@code player.getWorld().equals(entity.getWorld())}），不触碰区块或方块。
     */
    private static World eyeWorld() {
        World world = (World) Proxy.newProxyInstance(
            InteractionFailureLoggingBehaviorTest.class.getClassLoader(),
            new Class<?>[] {World.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getName":
                        return "interaction-failure-logging-test-world";
                    case "getUID":
                        return UUID.fromString("00000000-0000-0000-0000-00000000feed");
                    case "toString":
                        return "eyeWorld";
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

    private static Object pluginMetaStub() {
        return Proxy.newProxyInstance(
            InteractionFailureLoggingBehaviorTest.class.getClassLoader(),
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

    /** 只登记、不执行的后端替身：hint 的 player lane 投递经此丢掉，不让测试线程真去解析 Player。 */
    private static final class RecordingBackend implements SchedulerBackend {
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

    private static final class RecordedHandle implements MuzScheduler.TaskHandle {
        private final AtomicInteger ignored = new AtomicInteger();

        private RecordedHandle() {
            ignored.incrementAndGet();
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }

    /** 收集告警的 logger：只验证"记没记、记了什么"，不冒充真实控制台输出。 */
    private static final class CapturedLogs {
        private final List<LogRecord> records = new ArrayList<>();
        private final Logger logger;

        private CapturedLogs() {
            // 用唯一名字避免与其它测试共享 JUL logger，也不向上传播污染控制台。
            this.logger = Logger.getLogger("muz-interaction-failure-" + System.nanoTime());
            this.logger.setUseParentHandlers(false);
            this.logger.setLevel(Level.ALL);
            this.logger.addHandler(new Handler() {
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
            });
        }

        private Logger logger() {
            return logger;
        }

        private List<String> messages() {
            List<String> snapshot = new ArrayList<>();
            for (LogRecord record : records) {
                snapshot.add(record.getMessage());
            }
            return snapshot;
        }
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
