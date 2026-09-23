package linmumua.doudizhu.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * {@code runWrite} 在「插件仍启用但调度器已关闭」这一残余窗口下的行为契约。
 *
 * <p>{@code runWrite} 对「插件已关服/已禁用」走同步回退，只有插件仍启用时才会走到异步提交；
 * 此时若 {@link MuzScheduler} 门面已拒绝注册新任务并返回「已取消句柄」，{@code DatabaseManager.runWrite}
 * 必须识别这种句柄：把该次写库从 {@code pendingOperations} 移除、以异常结束该 completion 并记一次
 * warning。否则 {@code flushPendingWrites(5000)} 会空等到超时，同时这条写库被静默丢弃。
 *
 * <p>证据等级：本测试用 {@link Unsafe} 分配插件实例，注入 logger / scheduler 替身并把插件标记为启用
 * （未启用的插件会直接走同步回退，到不了这段句柄逻辑），走的是真实 {@code runWrite}（反射调用私有方法）
 * 执行路径，属行为级证据；它<b>不</b>覆盖真实 Bukkit 调度器在插件禁用后抛出的
 * {@code IllegalPluginAccessException}——那只能由实服验证。
 */
class DatabaseRunWriteShutdownRejectionTest {

    @Test
    void cancelledAsyncHandleClearsPendingWriteAndLogsWarning() throws Exception {
        CapturedLogs logs = new CapturedLogs();
        DoudizhuPlugin plugin = pluginWith(logs.logger(), new NullAsyncBackend());
        DatabaseManager manager = initializedManager(plugin);
        AtomicInteger writes = new AtomicInteger();

        invokeRunWrite(manager, "写入战绩", noopWrite(writes));

        assertTrue(pendingSet(manager).isEmpty(),
            "被取消的异步写库不得留在 pendingOperations 里让 flush 空等超时");
        assertEquals(0, writes.get(), "句柄被取消时写库体绝不能执行");
        assertTrue(logs.contains("关闭阶段拒绝了异步写库任务"),
            "拒绝异步写库必须记 warning，不能静默丢数据；实际日志=" + logs.snapshot());
    }

    @Test
    void liveAsyncHandleStillRunsWriteAndDoesNotLogShutdownRejection() throws Exception {
        CapturedLogs logs = new CapturedLogs();
        RecordingAsyncBackend backend = new RecordingAsyncBackend();
        DoudizhuPlugin plugin = pluginWith(logs.logger(), backend);
        DatabaseManager manager = initializedManager(plugin);
        AtomicInteger writes = new AtomicInteger();

        invokeRunWrite(manager, "写入战绩", noopWrite(writes));
        assertFalse(pendingSet(manager).isEmpty(),
            "异步任务尚未执行时写库仍应挂在 pendingOperations 上");

        backend.fire();

        assertEquals(1, writes.get(), "正常句柄必须真正执行写库");
        assertTrue(pendingSet(manager).isEmpty(), "写库完成后必须从 pendingOperations 移除");
        assertFalse(logs.contains("关闭阶段拒绝了异步写库任务"), "正常路径不得误报关闭拒绝");
    }

    private static void invokeRunWrite(DatabaseManager manager, String what, Object write) throws Exception {
        Class<?> writeInterface = Class.forName("linmumua.doudizhu.storage.DatabaseManager$SqlWrite");
        Method runWrite = DatabaseManager.class.getDeclaredMethod("runWrite", String.class, writeInterface);
        runWrite.setAccessible(true);
        runWrite.invoke(manager, what, write);
    }

    private static Object noopWrite(AtomicInteger writes) throws Exception {
        Class<?> writeInterface = Class.forName("linmumua.doudizhu.storage.DatabaseManager$SqlWrite");
        return Proxy.newProxyInstance(
            DatabaseRunWriteShutdownRejectionTest.class.getClassLoader(),
            new Class<?>[] { writeInterface },
            (proxy, method, args) -> {
                if ("run".equals(method.getName())) {
                    writes.incrementAndGet();
                }
                return null;
            });
    }

    @SuppressWarnings("unchecked")
    private static Set<CompletableFuture<?>> pendingSet(DatabaseManager manager) throws Exception {
        Field field = DatabaseManager.class.getDeclaredField("pendingOperations");
        field.setAccessible(true);
        return (Set<CompletableFuture<?>>) field.get(manager);
    }

    private static DoudizhuPlugin pluginWith(Logger logger, SchedulerBackend backend) throws Exception {
        DoudizhuPlugin plugin = (DoudizhuPlugin) unsafe().allocateInstance(DoudizhuPlugin.class);
        setField(plugin, "logger", logger);
        setField(plugin, "scheduler", new MuzScheduler(backend));
        // 夹具必须把插件标成「已启用」：runWrite 现在对「已关服/已禁用」会先走同步回退，
        // 只有仍启用的插件才会进入异步提交，才能覆盖「门面返回已取消句柄」这段收尾逻辑。
        // Unsafe 分配跳过了 JavaPlugin 的字段初始化，isEnabled 默认是 false，必须显式置真。
        setField(plugin, "isEnabled", true);
        return plugin;
    }

    private static DatabaseManager initializedManager(DoudizhuPlugin plugin) throws Exception {
        DatabaseManager manager = new DatabaseManager(plugin);
        // 只把 initialized 置真，跳过真实 JDBC 初始化；runWrite 的关闭闸门要求 initialized 才继续。
        setField(manager, "initialized", true);
        return manager;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
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

    /** 把告警收进内存的 logger：只验证「有没有记、记了什么」，不冒充真实控制台输出。 */
    private static final class CapturedLogs {
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final Logger logger;

        private CapturedLogs() {
            // 用唯一名字避免与其它测试共享 JUL logger，也不向上传播污染控制台。
            this.logger = Logger.getLogger("muz-db-runwrite-" + System.nanoTime());
            this.logger.setUseParentHandlers(false);
            this.logger.setLevel(Level.ALL);
            this.logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    messages.add(record.getMessage());
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

        private boolean contains(String fragment) {
            return messages.stream().anyMatch(message -> message != null && message.contains(fragment));
        }

        private List<String> snapshot() {
            return new ArrayList<>(messages);
        }
    }

    /** runAsync 返回 null：MuzScheduler 会把它当「已取消句柄」，模拟关闭阶段门面拒绝注册的结果。 */
    private static final class NullAsyncBackend implements SchedulerBackend {
        @Override
        public MuzScheduler.TaskHandle runAsync(long delay, long period,
                                                Consumer<MuzScheduler.TaskHandle> task) {
            return null;
        }

        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            throw new AssertionError("runWrite 不应触碰 global lane");
        }

        @Override
        public MuzScheduler.TaskHandle runRegion(Location location, long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            throw new AssertionError("runWrite 不应触碰 region lane");
        }

        @Override
        public MuzScheduler.TaskHandle runEntity(Entity entity, long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            throw new AssertionError("runWrite 不应触碰 entity lane");
        }

        @Override
        public MuzScheduler.TaskHandle runPlayer(Player player, long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            throw new AssertionError("runWrite 不应触碰 player lane");
        }
    }

    /** 记录 runAsync 回调并返回活动句柄，用 {@link #fire()} 手动驱动，证明正常路径未被误伤。 */
    private static final class RecordingAsyncBackend implements SchedulerBackend {
        private Consumer<MuzScheduler.TaskHandle> pending;

        private void fire() {
            Consumer<MuzScheduler.TaskHandle> task = pending;
            if (task == null) {
                throw new AssertionError("尚未登记异步任务");
            }
            task.accept(new LiveHandle());
        }

        @Override
        public MuzScheduler.TaskHandle runAsync(long delay, long period,
                                                Consumer<MuzScheduler.TaskHandle> task) {
            this.pending = task;
            return new LiveHandle();
        }

        @Override
        public MuzScheduler.TaskHandle runGlobal(long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            throw new AssertionError("runWrite 不应触碰 global lane");
        }

        @Override
        public MuzScheduler.TaskHandle runRegion(Location location, long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            throw new AssertionError("runWrite 不应触碰 region lane");
        }

        @Override
        public MuzScheduler.TaskHandle runEntity(Entity entity, long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            throw new AssertionError("runWrite 不应触碰 entity lane");
        }

        @Override
        public MuzScheduler.TaskHandle runPlayer(Player player, long delay, long period,
                                                 Consumer<MuzScheduler.TaskHandle> task) {
            throw new AssertionError("runWrite 不应触碰 player lane");
        }
    }

    /** 未取消、未终止的句柄替身；默认 isCancelled() 即为 false，无需覆写。 */
    private static final class LiveHandle implements MuzScheduler.TaskHandle {
        @Override
        public void cancel() {
        }
    }
}
