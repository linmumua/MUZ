package linmumua.doudizhu.debug;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Debug Web 资源任务的截止期限与共享租约边界。
 *
 * <p>这个 helper 不接触 Bukkit、CraftEngine 或文件系统，只负责三件事：同一资源租约同时
 * 只能有一个任务；120 秒只结束对外 Future 而不取消 raw Future；关闭或超时后 raw 完成也
 * 不能把旧结果重新发布。租约释放只发生在 raw Future 真正完成之后。
 */
final class HudWebApplyTaskGate implements AutoCloseable {
    static final long DEFAULT_TIMEOUT_SECONDS = 120L;
    private static final Logger LOGGER = Logger.getLogger(HudWebApplyTaskGate.class.getName());

    private final HudWebApplyLease lease;
    private final ScheduledExecutorService timeoutExecutor;
    private final long timeoutSeconds;
    private final Object lock = new Object();
    private Task<?> current;
    private boolean closed;
    private long generation;

    HudWebApplyTaskGate(HudWebApplyLease lease, ScheduledExecutorService timeoutExecutor) {
        this(lease, timeoutExecutor, DEFAULT_TIMEOUT_SECONDS);
    }

    HudWebApplyTaskGate(HudWebApplyLease lease, ScheduledExecutorService timeoutExecutor,
                        long timeoutSeconds) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.timeoutExecutor = Objects.requireNonNull(timeoutExecutor, "timeoutExecutor");
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 0");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    <T> Task<T> tryAcquire() {
        synchronized (lock) {
            if (closed || lease.isBusy()) {
                return null;
            }
            HudWebApplyLease.Lease acquired = lease.tryAcquire();
            if (acquired == null) {
                return null;
            }
            Task<T> task = new Task<>(acquired, generation);
            current = task;
            return task;
        }
    }

    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    boolean isBusy() {
        return lease.isBusy();
    }

    /** 只判断本协调器是否仍持有一个 raw 任务，不能用共享 lease.isBusy() 代替。 */
    boolean hasLocalTask() {
        synchronized (lock) {
            return current != null;
        }
    }

    <T> boolean isActive(Task<T> task) {
        synchronized (lock) {
            return isActiveLocked(task);
        }
    }

    /**
     * 原子领取一次任务执行权，再在闸门锁外运行回调。
     *
     * <p>activeSupplier 可能执行 config.yml 等阻塞 I/O，不能在闸门锁内运行。领取发生在
     * 锁内，因此关闭/超时若先取得锁，本次回调会走 inactiveSupplier；若本次领取先发生，
     * 回调已经进入生产流程，调用方仍须在后续阶段重新检查任务有效性。
     */
    <T, R> R runIfActive(Task<T> task, Supplier<R> activeSupplier, Supplier<R> inactiveSupplier) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(activeSupplier, "activeSupplier");
        Objects.requireNonNull(inactiveSupplier, "inactiveSupplier");
        boolean claimed;
        synchronized (lock) {
            claimed = isActiveLocked(task);
        }
        return claimed ? activeSupplier.get() : inactiveSupplier.get();
    }

    /**
     * 在闸门锁内线性化一个极短的主线程应用动作。
     *
     * <p>只允许运行态 Snapshot 应用这类不含 I/O 的短回调。这样 close/timeout 与最终应用
     * 只有一个明确胜者，同时 Future 的 complete 仍由调用方在锁外执行。
     */
    <T, R> R runIfActiveAtomically(Task<T> task, Supplier<R> activeSupplier,
                                   Supplier<R> inactiveSupplier) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(activeSupplier, "activeSupplier");
        Objects.requireNonNull(inactiveSupplier, "inactiveSupplier");
        synchronized (lock) {
            return isActiveLocked(task) ? activeSupplier.get() : inactiveSupplier.get();
        }
    }

    /**
     * 监视一个完整 raw 流程。raw 完成前永远不释放租约；exposed 超时或关闭时只完成对外
     * Future，绝不调用 raw.cancel、orTimeout 或 join。
     */
    <T> CompletableFuture<T> monitor(Task<T> task, CompletableFuture<T> raw,
                                     Supplier<T> inactiveResult,
                                     Function<Throwable, T> failureResult) {
        return monitor(task, raw, inactiveResult, failureResult, null);
    }

    /**
     * 监视 raw 并在共享租约释放后运行一个轻量收尾回调。
     *
     * <p>回调在 raw 完成处理后执行，适合协调器关闭自己的执行器；它不能执行阻塞 I/O。
     */
    <T> CompletableFuture<T> monitor(Task<T> task, CompletableFuture<T> raw,
                                     Supplier<T> inactiveResult,
                                     Function<Throwable, T> failureResult,
                                     Runnable rawFinished) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(inactiveResult, "inactiveResult");
        Objects.requireNonNull(failureResult, "failureResult");

        CompletableFuture<T> exposed = new CompletableFuture<>();
        boolean inactive;
        synchronized (lock) {
            task.exposed = exposed;
            task.inactiveResult = inactiveResult;
            inactive = !isActiveLocked(task);
            if (!inactive && !raw.isDone()) {
                try {
                    task.timeout = timeoutExecutor.schedule(
                        () -> timeout(task), timeoutSeconds, TimeUnit.SECONDS);
                } catch (RuntimeException exception) {
                    // 定时器被外部关闭时不能取消 raw；把本次 Web 结果标记为失效，
                    // 仍等待 raw 完成后释放租约。
                    task.abandoned = true;
                    lease.abandon(task.lease);
                    inactive = true;
                }
            }
        }
        raw.whenComplete((result, failure) -> {
            boolean activeBeforeRelease;
            synchronized (lock) {
                activeBeforeRelease = isActiveLocked(task);
                if (task.timeout != null) {
                    task.timeout.cancel(false);
                }
                // 在闸门锁内原子决定任务是否仍有效并释放共享租约；不在锁内执行 Future
                // 依赖回调，避免结果消费者反向获取协调器生命周期锁造成死锁。
                lease.release(task.lease);
                if (current == task) {
                    current = null;
                }
            }
            // 该回调必须排在 release/current=null 之后，且不能挂到 exposed：exposed 可能因
            // 120 秒超时提前完成，此时 raw 仍持有租约，协调器执行器不能提前关闭。
            runRawFinished(rawFinished);
            try {
                if (!activeBeforeRelease) {
                    exposed.complete(inactiveResult.get());
                } else if (failure != null) {
                    exposed.complete(failureResult.apply(unwrap(failure)));
                } else {
                    exposed.complete(result);
                }
            } catch (Throwable throwable) {
                LOGGER.log(Level.WARNING, "Debug Web HUD raw 结果映射失败。", throwable);
                exposed.completeExceptionally(throwable);
            }
        });
        if (inactive) {
            completeInactive(task, inactiveResult);
        }
        return exposed;
    }

    private void timeout(Task<?> task) {
        Supplier<?> inactiveResult;
        synchronized (lock) {
            if (task.exposed == null || task.exposed.isDone() || !isActiveLocked(task)) {
                return;
            }
            task.abandoned = true;
            lease.abandon(task.lease);
            inactiveResult = task.inactiveResult;
        }
        completeInactive(task, inactiveResult);
    }

    private static void runRawFinished(Runnable rawFinished) {
        if (rawFinished == null) {
            return;
        }
        try {
            rawFinished.run();
        } catch (Throwable throwable) {
            LOGGER.log(Level.WARNING, "Debug Web HUD raw 任务收尾回调失败。", throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void completeInactive(Task<T> task, Supplier<?> inactiveResult) {
        if (inactiveResult == null) {
            return;
        }
        try {
            task.exposed.complete(((Supplier<T>) inactiveResult).get());
        } catch (Throwable throwable) {
            LOGGER.log(Level.WARNING, "Debug Web HUD 失效任务结果生成失败。", throwable);
            task.exposed.completeExceptionally(throwable);
        }
    }

    private boolean isActiveLocked(Task<?> task) {
        return !closed
            && current == task
            && generation == task.generation
            && !task.abandoned
            && lease.isCurrent(task.lease);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public void close() {
        Task<?> task;
        Supplier<?> inactiveResult;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            task = current;
            inactiveResult = task == null ? null : task.inactiveResult;
            if (task != null) {
                task.abandoned = true;
                lease.abandon(task.lease);
                if (task.timeout != null) {
                    task.timeout.cancel(false);
                }
            }
        }
        if (task != null && task.exposed != null && !task.exposed.isDone()) {
            completeInactive(task, inactiveResult);
        }
    }

    static final class Task<T> {
        private final HudWebApplyLease.Lease lease;
        private final long generation;
        private boolean abandoned;
        private CompletableFuture<T> exposed;
        private Supplier<T> inactiveResult;
        private ScheduledFuture<?> timeout;

        private Task(HudWebApplyLease.Lease lease, long generation) {
            this.lease = lease;
            this.generation = generation;
        }
    }
}
