package linmumua.doudizhu.scheduler;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * MUZ 的调度门面。
 *
 * <p>第一阶段把调度后端从业务调用点抽出，并明确区分 global、region、entity、player
 * 与 async。默认仍使用 Paper 后端；测试或后续区域线程适配可以注入其它
 * {@link SchedulerBackend}，而无需修改业务代码。
 *
 * <p>门面还负责插件生命周期边界：{@link #close()} 会取消已登记任务并拒绝迟到回调，
 * {@link #newGeneration()} 会使上一代任务失效。这样异步或区域任务即使已经从后端排队，
 * 在关闭或重启后也不能把旧状态写回业务对象。
 */
public final class MuzScheduler {
    private final SchedulerBackend backend;
    private final Object lifecycleLock = new Object();
    private final Set<ManagedTaskHandle> tasks = ConcurrentHashMap.newKeySet();
    private volatile long generation;
    private volatile boolean closed;
    /**
     * 「插件正在关闭」谓词，由插件入口在装配期注入。
     *
     * <p>【为什么在门面层拦而不是 Bukkit 适配层】：Paper/Folia 只要把插件标记为禁用，就
     * 拒绝一切新任务注册，BukkitScheduler / GlobalRegionScheduler 会直接抛
     * {@code IllegalPluginAccessException}。门面是 global/region/entity/player/async 五条 lane
     * 的唯一入口，在这里提前拦下可以一次性覆盖所有后端，也不必让每个后端各自判断插件可用性
     * （Paper 的 {@code Plugin} 接口根本没有可依赖的「正在关闭」信号）。
     *
     * <p>【为什么不用 Plugin#isEnabled()】：{@code isEnabled()} 在 onEnable 装配早期仍可能为
     * false，据此拒绝会误伤启动期注册的周期任务，是灾难性回归。谓词由插件入口注入，只反映
     * 「已进入 onDisable」这一确定状态；默认 {@code () -> false}，保证不注入谓词的测试构造行为不变。
     */
    private final BooleanSupplier shuttingDown;
    /**
     * 关闭期拒绝注册只记一次 WARNING：关服阶段每个延迟/周期调用点都可能重试，逐条打会刷屏。
     * 代次推进（reload/重启）时复位，让「只记一次」只约束当前代次。
     */
    private final AtomicBoolean shutdownRejectionLogged = new AtomicBoolean();
    /** 关闭期拒绝的告警出口；无 Plugin 注入的构造用同名 JUL logger 兜底，不做静默丢弃。 */
    private final java.util.logging.Logger logger;

    public MuzScheduler(Plugin plugin) {
        this(new PaperSchedulerBackend(plugin), () -> false, plugin.getLogger());
    }

    public MuzScheduler(Plugin plugin, BooleanSupplier shuttingDown) {
        this(new PaperSchedulerBackend(plugin), shuttingDown, plugin.getLogger());
    }

    public MuzScheduler(SchedulerBackend backend) {
        this(backend, () -> false, null);
    }

    /**
     * 包内可见：让契约测试注入 {@link SchedulerBackend} 替身并模拟关闭中谓词，
     * 无需构造真实 Paper 后端。
     */
    MuzScheduler(SchedulerBackend backend, BooleanSupplier shuttingDown) {
        this(backend, shuttingDown, null);
    }

    private MuzScheduler(
        SchedulerBackend backend,
        BooleanSupplier shuttingDown,
        java.util.logging.Logger logger
    ) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.shuttingDown = Objects.requireNonNull(shuttingDown, "shuttingDown");
        // 没有 Plugin 时退回 JUL 兜底 logger：关闭拒绝绝不能完全静默。
        this.logger = logger != null ? logger : java.util.logging.Logger.getLogger("MUZ");
    }

    /** 返回当前后端的 owner 标识；业务 lane owner 由 {@link TaskHandle#ownerId()} 提供。 */
    public String ownerId() {
        String owner = backend.ownerId();
        return owner == null || owner.isBlank() ? backend.getClass().getName() : owner;
    }

    /** 返回当前调度代次。关闭后不会再接受新任务，但代次仍保持单调递增。 */
    public long generation() {
        return generation;
    }

    /** 返回门面是否已经关闭。 */
    public boolean isClosed() {
        return closed;
    }

    /**
     * 进入下一代调度并取消旧代次任务。
     *
     * @return 新代次编号
     */
    public long newGeneration() {
        Set<ManagedTaskHandle> toCancel;
        long next;
        synchronized (lifecycleLock) {
            next = ++generation;
            // 代次推进后旧代次的关闭拒绝告警已过期，允许新代次重新告警一次。
            shutdownRejectionLogged.set(false);
            toCancel = Set.copyOf(tasks);
        }
        toCancel.forEach(ManagedTaskHandle::cancel);
        return next;
    }

    /** 取消当前仍登记的全部任务，但不改变代次。 */
    public void cancelAll() {
        Set<ManagedTaskHandle> toCancel;
        synchronized (lifecycleLock) {
            toCancel = Set.copyOf(tasks);
        }
        toCancel.forEach(ManagedTaskHandle::cancel);
    }

    /**
     * 幂等关闭调度门面，取消任务并通知后端释放其任务引用。
     * 关闭后所有新任务都会返回已取消句柄，用户回调不会执行。
     */
    public void close() {
        Set<ManagedTaskHandle> toCancel;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            toCancel = Set.copyOf(tasks);
        }
        toCancel.forEach(ManagedTaskHandle::cancel);
        backend.close();
    }

    /** 在 Paper global scheduler 上立即执行。 */
    public TaskHandle runGlobal(Runnable runnable) {
        return runGlobal(0L, runnable);
    }

    /** 在 Paper global scheduler 上延迟执行。 */
    public TaskHandle runGlobal(long delay, Runnable runnable) {
        return schedule("global", false, callback -> backend.runGlobal(delay, 0L, callback),
            ignored -> runnable.run());
    }

    /** 在 Paper global scheduler 上延迟执行，显式命名入口。 */
    public TaskHandle runGlobalLater(long delay, Runnable runnable) {
        return runGlobal(delay, runnable);
    }

    /** 在 Paper global scheduler 上周期执行。 */
    public TaskHandle runGlobalTimer(long delay, long period, Runnable runnable) {
        return schedule("global", true, callback -> backend.runGlobal(delay, period, callback),
            ignored -> runnable.run());
    }

    /** 在 Paper global scheduler 上周期执行，并暴露当前任务的取消句柄。 */
    public TaskHandle runGlobalTimer(long delay, long period, Consumer<TaskHandle> consumer) {
        return schedule("global", true, callback -> backend.runGlobal(delay, period, callback), consumer);
    }

    /** 在指定世界区块所属 region 上立即执行。 */
    public TaskHandle runRegion(Location location, Runnable runnable) {
        return runRegion(location, 0L, runnable);
    }

    /** 在指定世界区块所属 region 上延迟执行。 */
    public TaskHandle runRegion(Location location, long delay, Runnable runnable) {
        return schedule("region", false, callback -> backend.runRegion(location, delay, 0L, callback),
            ignored -> runnable.run());
    }

    /** 在指定世界区块所属 region 上延迟执行，显式命名入口。 */
    public TaskHandle runRegionLater(Location location, long delay, Runnable runnable) {
        return runRegion(location, delay, runnable);
    }

    /** 在指定世界区块所属 region 上周期执行。 */
    public TaskHandle runRegionTimer(Location location, long delay, long period, Runnable runnable) {
        return schedule("region", true, callback -> backend.runRegion(location, delay, period, callback),
            ignored -> runnable.run());
    }

    /** 在指定世界区块所属 region 上周期执行，并暴露当前任务的取消句柄。 */
    public TaskHandle runRegionTimer(Location location, long delay, long period, Consumer<TaskHandle> consumer) {
        return schedule("region", true, callback -> backend.runRegion(location, delay, period, callback), consumer);
    }

    /** 在指定实体所属 entity scheduler 上立即执行。 */
    public TaskHandle runEntity(Entity entity, Runnable runnable) {
        return runEntity(entity, 0L, runnable);
    }

    /** 在指定实体所属 entity scheduler 上延迟执行。 */
    public TaskHandle runEntity(Entity entity, long delay, Runnable runnable) {
        return schedule("entity", false, callback -> backend.runEntity(entity, delay, 0L, callback),
            ignored -> runnable.run());
    }

    /** 在指定实体所属 entity scheduler 上延迟执行，显式命名入口。 */
    public TaskHandle runEntityLater(Entity entity, long delay, Runnable runnable) {
        return runEntity(entity, delay, runnable);
    }

    /** 在指定实体所属 entity scheduler 上周期执行。 */
    public TaskHandle runEntityTimer(Entity entity, long delay, long period, Runnable runnable) {
        return schedule("entity", true, callback -> backend.runEntity(entity, delay, period, callback),
            ignored -> runnable.run());
    }

    /** 在指定玩家所属 entity scheduler 上立即执行。 */
    public TaskHandle runPlayer(Player player, Runnable runnable) {
        return runPlayer(player, 0L, runnable);
    }

    /** 在指定玩家所属 entity scheduler 上延迟执行。 */
    public TaskHandle runPlayer(Player player, long delay, Runnable runnable) {
        return schedule("player", false, callback -> backend.runPlayer(player, delay, 0L, callback),
            ignored -> runnable.run());
    }

    /** 在指定玩家所属 entity scheduler 上延迟执行，显式命名入口。 */
    public TaskHandle runPlayerLater(Player player, long delay, Runnable runnable) {
        return runPlayer(player, delay, runnable);
    }

    /** 在指定玩家所属 entity scheduler 上周期执行。 */
    public TaskHandle runPlayerTimer(Player player, long delay, long period, Runnable runnable) {
        return schedule("player", true, callback -> backend.runPlayer(player, delay, period, callback),
            ignored -> runnable.run());
    }

    /** 在 Paper async scheduler 上立即执行。 */
    public TaskHandle runAsync(Runnable runnable) {
        return runAsync(0L, runnable);
    }

    /** 在 Paper async scheduler 上延迟执行。 */
    public TaskHandle runAsync(long delay, Runnable runnable) {
        return schedule("async", false, callback -> backend.runAsync(delay, 0L, callback),
            ignored -> runnable.run());
    }

    /** 在 Paper async scheduler 上延迟执行，显式命名入口。 */
    public TaskHandle runAsyncLater(long delay, Runnable runnable) {
        return runAsync(delay, runnable);
    }

    /** 在 Paper async scheduler 上周期执行。 */
    public TaskHandle runAsyncTimer(long delay, long period, Runnable runnable) {
        return schedule("async", true, callback -> backend.runAsync(delay, period, callback),
            ignored -> runnable.run());
    }

    /** 兼容旧调用：原 runSync 语义对应 global scheduler，而不是 region/entity scheduler。 */
    public TaskHandle runSync(Runnable runnable) {
        return runGlobal(runnable);
    }

    /** 兼容旧调用：原 runLater 语义对应 global scheduler。 */
    public TaskHandle runLater(long delay, Runnable runnable) {
        return runGlobal(delay, runnable);
    }

    /** 兼容旧调用：原 runTimer 语义对应 global scheduler。 */
    public TaskHandle runTimer(long delay, long period, Runnable runnable) {
        return runGlobalTimer(delay, period, runnable);
    }

    /** 兼容旧调用：原 runTimer 的自取消回调仍使用同一 TaskHandle。 */
    public TaskHandle runTimer(long delay, long period, Consumer<TaskHandle> consumer) {
        return runGlobalTimer(delay, period, consumer);
    }

    private TaskHandle schedule(
        String ownerId,
        boolean repeating,
        BackendSchedule schedule,
        Consumer<TaskHandle> callback
    ) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(callback, "callback");
        long taskGeneration;
        ManagedTaskHandle managed;
        boolean reject;
        boolean duringShutdown = false;
        synchronized (lifecycleLock) {
            taskGeneration = generation;
            managed = new ManagedTaskHandle(ownerId, taskGeneration, repeating);
            // 关闭中同样按「拒绝注册」处理：插件进入 onDisable 后 Paper/Folia 会拒绝一切任务注册，
            // 与其让后端起真实注册、抛出 IllegalPluginAccessException 打断调用方（会中断整条关闭链），
            // 不如在门面层提前拦下，返回已取消句柄——语义与既有的 null-handle 分支完全一致。
            duringShutdown = !closed && shuttingDown.getAsBoolean();
            reject = closed || duringShutdown;
            if (!reject) {
                tasks.add(managed);
            }
        }
        if (reject) {
            managed.cancel();
            if (duringShutdown) {
                logShutdownRejectionOnce(ownerId);
            }
            return managed;
        }
        try {
            if (closed) {
                managed.cancel();
                return managed;
            }
            TaskHandle backendHandle = schedule.schedule(backendTask -> {
                managed.bind(backendTask);
                if (!managed.tryEnter()) {
                    return;
                }
                boolean failed = false;
                try {
                    callback.accept(managed);
                } catch (RuntimeException | Error failure) {
                    failed = true;
                    managed.fail(repeating);
                    throw failure;
                } finally {
                    managed.exit();
                    if (!repeating || failed) {
                        managed.complete();
                    }
                }
            });
            managed.bind(backendHandle);
            if (backendHandle == null || !managed.isActive()) {
                managed.cancel();
            }
            return managed;
        } catch (RuntimeException | Error failure) {
            managed.cancel();
            throw failure;
        }
    }

    /**
     * 关闭期拒绝注册只告警一次，避免关服阶段每个调用点重试刷屏；同一代次内不重复打。
     */
    private void logShutdownRejectionOnce(String ownerId) {
        if (!shutdownRejectionLogged.compareAndSet(false, true)) {
            return;
        }
        logger.warning(
            "MUZ 正在关闭，已拒绝在 " + ownerId + " lane 注册新的调度任务；"
                + "本插件关闭阶段不再提交 owner/实体清理，实体清理依赖世界卸载与启动期残留清理。"
        );
    }

    @FunctionalInterface
    private interface BackendSchedule {
        TaskHandle schedule(Consumer<TaskHandle> callback);
    }

    private final class ManagedTaskHandle implements TaskHandle {
        private final String ownerId;
        private final long taskGeneration;
        private final boolean repeating;
        private final AtomicReference<TaskHandle> delegate = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final Object terminationLock = new Object();
        private final List<Runnable> terminationListeners = new CopyOnWriteArrayList<>();

        private ManagedTaskHandle(String ownerId, long taskGeneration, boolean repeating) {
            this.ownerId = ownerId;
            this.taskGeneration = taskGeneration;
            this.repeating = repeating;
        }

        private void bind(TaskHandle handle) {
            if (handle == null) {
                cancelled.set(true);
                terminate();
                return;
            }
            if (delegate.compareAndSet(null, handle)) {
                handle.onTermination(this::delegateTerminated);
            }
            if (cancelled.get() || !isCurrentGeneration()) {
                handle.cancel();
            }
        }

        private void delegateTerminated() {
            if (!running.get()) {
                terminate();
            }
        }

        private boolean isCurrentGeneration() {
            return !closed && generation == taskGeneration;
        }

        private boolean isActive() {
            return !cancelled.get() && !terminated.get() && isCurrentGeneration();
        }

        private boolean tryEnter() {
            if (!isActive() || !running.compareAndSet(false, true)) {
                if (!running.get()) {
                    terminate();
                }
                return false;
            }
            if (!isActive()) {
                running.set(false);
                terminate();
                return false;
            }
            return true;
        }

        private void exit() {
            running.set(false);
            if (cancelled.get() || !isCurrentGeneration()) {
                terminate();
            }
        }

        private void complete() {
            terminate();
        }

        private void fail(boolean repeatingTask) {
            if (repeatingTask) {
                failed.set(true);
                TaskHandle handle = delegate.get();
                if (handle != null && handle != this) {
                    handle.cancel();
                }
            }
            terminate();
        }

        private void terminate() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            tasks.remove(this);
            List<Runnable> listeners;
            synchronized (terminationLock) {
                listeners = List.copyOf(terminationListeners);
                terminationListeners.clear();
            }
            listeners.forEach(Runnable::run);
        }

        @Override
        public void cancel() {
            if (terminated.get() || !cancelled.compareAndSet(false, true)) {
                return;
            }
            tasks.remove(this);
            TaskHandle handle = delegate.get();
            if (handle != null && handle != this) {
                handle.cancel();
            }
            if (!running.get()) {
                terminate();
            }
        }

        @Override
        public boolean isCancelled() {
            TaskHandle handle = delegate.get();
            return cancelled.get() || failed.get() || (handle != null && handle.isCancelled());
        }

        @Override
        public void onTermination(Runnable listener) {
            Objects.requireNonNull(listener, "listener");
            boolean invokeNow;
            synchronized (terminationLock) {
                invokeNow = terminated.get();
                if (!invokeNow) {
                    terminationListeners.add(listener);
                }
            }
            if (invokeNow) {
                listener.run();
            }
        }

        @Override
        public long generation() {
            return taskGeneration;
        }

        @Override
        public String ownerId() {
            return ownerId;
        }
    }

    public interface TaskHandle {
        void cancel();

        /** 在任务终止后调用；旧测试句柄可保持默认空实现。 */
        default void onTermination(Runnable listener) {
        }

        /** 返回任务是否已经取消；测试后端可按自身生命周期实现。 */
        default boolean isCancelled() {
            return false;
        }

        /** 返回创建该任务时绑定的调度代次；旧后端句柄未知时返回 {@code -1}。 */
        default long generation() {
            return -1L;
        }

        /** 返回该任务所属的 owner lane；旧后端句柄未知时返回空字符串。 */
        default String ownerId() {
            return "";
        }
    }
}
