package linmumua.doudizhu.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 基于 Paper threaded-region scheduler 的默认后端。
 *
 * <p>global 使用 GlobalRegionScheduler，世界坐标使用 RegionScheduler，实体与玩家使用
 * EntityScheduler，异步任务使用 AsyncScheduler。异步 API 使用毫秒，统一把 MUZ 的 tick
 * 转换为 50 毫秒；其它四类 API 直接使用 Paper tick 参数。
 */
public final class PaperSchedulerBackend implements SchedulerBackend {
    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final Server server;
    private final Set<PaperTaskHandle> tasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PaperSchedulerBackend(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
    }

    @Override
    public String ownerId() {
        return "paper";
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (PaperTaskHandle task : Set.copyOf(tasks)) {
            task.cancel();
        }
        tasks.clear();
    }

    @Override
    public MuzScheduler.TaskHandle runGlobal(
        long delay,
        long period,
        java.util.function.Consumer<MuzScheduler.TaskHandle> task
    ) {
        validateSchedule(delay, period);
        Objects.requireNonNull(task, "task");
        return schedule(period > 0, handle -> {
            if (period > 0) {
                // Folia/Leaf 的 runAtFixedRate 拒绝初始延迟 <= 0（"Initial delay ticks may not be <= 0"），
                // 周期任务的 0 延迟统一钳到 1 tick，与 entity/player 分支一致。
                return server.getGlobalRegionScheduler().runAtFixedRate(
                    plugin, ignored -> handle.dispatch(task), repeatingInitialDelay(delay), period);
            }
            if (delay == 0) {
                return server.getGlobalRegionScheduler().run(
                    plugin, ignored -> handle.dispatch(task));
            }
            return server.getGlobalRegionScheduler().runDelayed(
                plugin, ignored -> handle.dispatch(task), delay);
        });
    }

    @Override
    public MuzScheduler.TaskHandle runRegion(
        Location location,
        long delay,
        long period,
        java.util.function.Consumer<MuzScheduler.TaskHandle> task
    ) {
        validateSchedule(delay, period);
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(task, "task");
        return schedule(period > 0, handle -> {
            if (period > 0) {
                // 同 runGlobal：region 周期任务的初始延迟也不得为 0。
                return server.getRegionScheduler().runAtFixedRate(
                    plugin, location, ignored -> handle.dispatch(task), repeatingInitialDelay(delay), period);
            }
            if (delay == 0) {
                return server.getRegionScheduler().run(
                    plugin, location, ignored -> handle.dispatch(task));
            }
            return server.getRegionScheduler().runDelayed(
                plugin, location, ignored -> handle.dispatch(task), delay);
        });
    }

    @Override
    public MuzScheduler.TaskHandle runEntity(
        Entity entity,
        long delay,
        long period,
        java.util.function.Consumer<MuzScheduler.TaskHandle> task
    ) {
        validateSchedule(delay, period);
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(task, "task");
        long effectiveDelay = Math.max(1L, delay);
        return schedule(period > 0, handle -> period > 0
            ? entity.getScheduler().runAtFixedRate(
                plugin, ignored -> handle.dispatch(task), handle::retire, effectiveDelay, period)
            : entity.getScheduler().runDelayed(
                plugin, ignored -> handle.dispatch(task), handle::retire, effectiveDelay));
    }

    @Override
    public MuzScheduler.TaskHandle runPlayer(
        Player player,
        long delay,
        long period,
        java.util.function.Consumer<MuzScheduler.TaskHandle> task
    ) {
        validateSchedule(delay, period);
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(task, "task");
        long effectiveDelay = Math.max(1L, delay);
        return schedule(period > 0, handle -> period > 0
            ? player.getScheduler().runAtFixedRate(
                plugin, ignored -> handle.dispatch(task), handle::retire, effectiveDelay, period)
            : player.getScheduler().runDelayed(
                plugin, ignored -> handle.dispatch(task), handle::retire, effectiveDelay));
    }

    @Override
    public MuzScheduler.TaskHandle runAsync(
        long delay,
        long period,
        java.util.function.Consumer<MuzScheduler.TaskHandle> task
    ) {
        validateSchedule(delay, period);
        Objects.requireNonNull(task, "task");
        long delayMillis = ticksToMillis(delay);
        return schedule(period > 0, handle -> {
            if (period > 0) {
                return server.getAsyncScheduler().runAtFixedRate(
                    plugin,
                    ignored -> handle.dispatch(task),
                    delayMillis,
                    ticksToMillis(period),
                    TimeUnit.MILLISECONDS
                );
            }
            if (delay == 0) {
                return server.getAsyncScheduler().runNow(
                    plugin, ignored -> handle.dispatch(task));
            }
            return server.getAsyncScheduler().runDelayed(
                plugin,
                ignored -> handle.dispatch(task),
                delayMillis,
                TimeUnit.MILLISECONDS
            );
        });
    }

    private PaperTaskHandle schedule(boolean repeating, NativeRegistration registration) {
        PaperTaskHandle handle = new PaperTaskHandle(repeating);
        if (closed.get()) {
            handle.cancel();
            return handle;
        }
        tasks.add(handle);
        if (closed.get()) {
            handle.cancel();
            return handle;
        }
        try {
            ScheduledTask scheduled = registration.register(handle);
            handle.bind(scheduled);
            if (closed.get()) {
                handle.cancel();
            }
            return handle;
        } catch (RuntimeException | Error failure) {
            handle.cancel();
            throw failure;
        }
    }

    @FunctionalInterface
    private interface NativeRegistration {
        ScheduledTask register(PaperTaskHandle handle);
    }

    private final class PaperTaskHandle implements MuzScheduler.TaskHandle {
        private final boolean repeating;
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicBoolean retired = new AtomicBoolean();
        private final AtomicBoolean failed = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final Object terminationLock = new Object();
        private final List<Runnable> terminationListeners = new CopyOnWriteArrayList<>();
        private volatile ScheduledTask scheduled;

        private PaperTaskHandle(boolean repeating) {
            this.repeating = repeating;
        }

        private void bind(ScheduledTask scheduled) {
            if (scheduled == null) {
                cancelRequested.set(true);
                terminate();
                return;
            }
            this.scheduled = scheduled;
            if (cancelRequested.get() || (closed.get() && !completed.get())) {
                scheduled.cancel();
            }
        }

        private boolean tryEnter() {
            if (cancelRequested.get() || closed.get() || terminated.get()) {
                terminate();
                return false;
            }
            if (!running.compareAndSet(false, true)) {
                return false;
            }
            if (cancelRequested.get() || closed.get() || terminated.get()) {
                running.set(false);
                terminate();
                return false;
            }
            return true;
        }

        private void dispatch(java.util.function.Consumer<MuzScheduler.TaskHandle> task) {
            if (!tryEnter()) {
                return;
            }
            try {
                task.accept(this);
            } catch (RuntimeException | Error failure) {
                failed.set(true);
                if (repeating) {
                    cancelNative();
                }
                terminate();
                throw failure;
            } finally {
                running.set(false);
                if (!repeating && !failed.get()) {
                    completed.set(true);
                }
                if (!repeating || cancelRequested.get() || retired.get() || failed.get() || closed.get()) {
                    terminate();
                }
            }
        }

        private void cancelNative() {
            ScheduledTask current = scheduled;
            if (current != null) {
                current.cancel();
            }
        }

        /** EntityScheduler 的 retired 回调只能做终止登记，不能接触实体或世界。 */
        private void retire() {
            retired.set(true);
            if (!running.get()) {
                terminate();
            }
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
            if (terminated.get()) {
                return;
            }
            if (!cancelRequested.compareAndSet(false, true)) {
                return;
            }
            cancelNative();
            if (!running.get()) {
                terminate();
            }
        }

        @Override
        public boolean isCancelled() {
            ScheduledTask current = scheduled;
            return cancelRequested.get() || (current != null && current.isCancelled());
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
        public String ownerId() {
            return PaperSchedulerBackend.this.ownerId();
        }
    }

    private static void validateSchedule(long delay, long period) {
        if (delay < 0 || period < 0) {
            throw new IllegalArgumentException(
                "调度 tick 不能为负数: delay=" + delay + ", period=" + period
            );
        }
    }

    /**
     * 周期任务的初始延迟下限为 1 tick。
     *
     * <p>真实 Folia 与 Leaf 的 Global/Region scheduler 在 {@code runAtFixedRate} 里对 0 延迟直接抛
     * {@link IllegalArgumentException}；开局发牌 timer 以 0 延迟注册，异常被交互层转成玩家提示后，
     * 牌桌停在 DEALING（「卡在正在发牌」）。旧 Bukkit 定时器的 0 延迟本就是下一 tick 执行，语义不变。
     */
    private static long repeatingInitialDelay(long delay) {
        return Math.max(1L, delay);
    }

    private static long ticksToMillis(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("调度 tick 不能为负数: " + ticks);
        }
        try {
            return Math.multiplyExact(ticks, MILLIS_PER_TICK);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("调度 tick 超出毫秒范围: " + ticks, exception);
        }
    }
}
