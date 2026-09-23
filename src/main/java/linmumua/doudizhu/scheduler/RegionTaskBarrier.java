package linmumua.doudizhu.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Location;

/**
 * 多 region 一次性任务的最小完成屏障。
 *
 * <p>该类只负责登记、聚合和代次隔离，不接入牌桌实体清理。每个 action 都通过
 * {@link MuzScheduler#runRegion(Location, Runnable)} 投递；超时由 global lane 驱动。
 */
public final class RegionTaskBarrier {
    /** 屏障的最终状态。 */
    public enum Status {
        COMPLETED,
        FAILED,
        TIMED_OUT,
        CANCELLED
    }

    /** 单个 request 的最终状态。 */
    public enum RequestStatus {
        PENDING,
        COMPLETED,
        FAILED,
        TIMED_OUT,
        CANCELLED
    }

    /** 要投递到一个 region owner 的一次性 action。 */
    public record Request(String id, Location owner, Runnable action) {
        public Request {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("request id 不能为空");
            }
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(action, "action");
        }
    }

    /** 单个 request 的聚合结果。 */
    public record RequestResult(String id, RequestStatus status, Throwable failure) {
        public RequestResult {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            if (status != RequestStatus.FAILED && failure != null) {
                throw new IllegalArgumentException("非 FAILED request 不得携带 failure");
            }
        }
    }

    /** 整个屏障的聚合结果。 */
    public record Result(Status status, Map<String, RequestResult> requests, Throwable firstFailure) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(requests, "requests");
            LinkedHashMap<String, RequestResult> copy = new LinkedHashMap<>();
            requests.forEach((id, result) -> {
                if (id == null || result == null) {
                    throw new NullPointerException("requests 不得包含 null");
                }
                if (!id.equals(result.id())) {
                    throw new IllegalArgumentException("request 结果 id 不一致: " + id);
                }
                if (copy.put(id, result) != null) {
                    throw new IllegalArgumentException("重复 request id: " + id);
                }
            });
            requests = Collections.unmodifiableMap(copy);
        }

        /** 按 id 读取单个 request 结果。 */
        public RequestResult request(String id) {
            return requests.get(id);
        }
    }

    private final MuzScheduler scheduler;
    private final long timeoutTicks;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final CompletableFuture<Result> future = new CompletableFuture<>();
    private final Object lock = new Object();
    private long generation = 1L;
    private boolean started;
    private boolean registrationComplete;
    private boolean terminal;
    private boolean completionPublished;
    private Status terminalStatus;
    private Throwable firstFailure;
    private MuzScheduler.TaskHandle timeoutHandle;

    /**
     * 创建屏障；构造阶段只校验输入，不会投递任务。
     *
     * @param scheduler 调度门面
     * @param requests 要投递的 request 集合
     * @param timeoutTicks global lane 超时 tick，必须非负
     */
    public RegionTaskBarrier(MuzScheduler scheduler, Collection<Request> requests, long timeoutTicks) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        if (timeoutTicks < 0L) {
            throw new IllegalArgumentException("timeoutTicks 必须非负");
        }
        this.timeoutTicks = timeoutTicks;
        Objects.requireNonNull(requests, "requests");
        for (Request request : requests) {
            Objects.requireNonNull(request, "request");
            if (entries.put(request.id(), new Entry(request)) != null) {
                throw new IllegalArgumentException("重复 request id: " + request.id());
            }
        }
    }

    /** 返回可观察的 CompletableFuture；调用 start 前保持未完成。 */
    public CompletableFuture<Result> future() {
        return future;
    }

    /** 返回只读 CompletionStage 视图。 */
    public CompletionStage<Result> completion() {
        return future;
    }

    /**
     * 注册所有 region action 并开始屏障。
     *
     * <p>该方法只能调用一次。即使后端注册失败，也会把失败收进聚合结果，不让计数悬挂。
     */
    public CompletableFuture<Result> start() {
        final long token;
        synchronized (lock) {
            if (started) {
                throw new IllegalStateException("RegionTaskBarrier 只能启动一次");
            }
            started = true;
            token = generation;
            if (entries.isEmpty()) {
                registrationComplete = true;
                if (!terminal) {
                    terminal = true;
                    terminalStatus = Status.COMPLETED;
                }
            }
        }
        if (entries.isEmpty()) {
            CompletionEffects effects;
            synchronized (lock) {
                effects = publishIfReadyLocked();
            }
            apply(effects);
            return future;
        }

        for (Entry entry : snapshotEntries()) {
            try {
                MuzScheduler.TaskHandle handle = scheduler.runRegion(entry.request.owner(),
                    () -> runAction(token, entry.request.id()));
                bindRegionHandle(token, entry.request.id(), handle);
                if (handle == null || handle.isCancelled()) {
                    markFailure(token, entry.request.id(),
                        new IllegalStateException("region task 注册失败: " + entry.request.id()));
                }
            } catch (Throwable failure) {
                markFailure(token, entry.request.id(), failure);
            }
        }

        CompletionEffects effects;
        boolean scheduleTimeout;
        synchronized (lock) {
            registrationComplete = true;
            effects = maybeCompleteWhenReadyLocked();
            scheduleTimeout = !terminal;
        }
        apply(effects);
        if (scheduleTimeout) {
            scheduleTimeout(token);
        }
        return future;
    }

    /** submit 是 start 的语义别名，便于调用方按提交动作命名。 */
    public CompletableFuture<Result> submit() {
        return start();
    }

    /**
     * 主动取消屏障；已完成 request 保留原状态，未完成 request 标记为 CANCELLED。
     *
     * @return 本次调用是否首次把屏障推进到 CANCELLED
     */
    public boolean cancel() {
        CompletionEffects effects;
        synchronized (lock) {
            if (!started) {
                throw new IllegalStateException("RegionTaskBarrier 必须先 start 再 cancel");
            }
            if (terminal) {
                return false;
            }
            terminal = true;
            terminalStatus = Status.CANCELLED;
            markPendingLocked(RequestStatus.CANCELLED, null);
            effects = publishIfReadyLocked();
        }
        apply(effects);
        return true;
    }

    private List<Entry> snapshotEntries() {
        synchronized (lock) {
            return new ArrayList<>(entries.values());
        }
    }

    private void bindRegionHandle(long token, String id, MuzScheduler.TaskHandle handle) {
        if (handle == null) {
            return;
        }
        boolean cancel;
        synchronized (lock) {
            Entry entry = entries.get(id);
            cancel = entry == null || token != generation || terminal;
            if (!cancel) {
                entry.handle = handle;
            }
        }
        if (cancel) {
            handle.cancel();
        }
    }

    private void scheduleTimeout(long token) {
        MuzScheduler.TaskHandle handle;
        try {
            handle = scheduler.runGlobal(timeoutTicks, () -> timeout(token));
        } catch (Throwable failure) {
            markOperationFailure(token, failure);
            return;
        }
        boolean cancel;
        synchronized (lock) {
            cancel = token != generation || terminal;
            if (!cancel) {
                timeoutHandle = handle;
            }
        }
        if (cancel && handle != null) {
            handle.cancel();
        } else if (!cancel && (handle == null || handle.isCancelled())) {
            markOperationFailure(token,
                new IllegalStateException("global timeout task 注册失败"));
        }
    }

    private void runAction(long token, String id) {
        Entry entry;
        synchronized (lock) {
            entry = entries.get(id);
            if (entry == null || terminal || token != generation || entry.status != RequestStatus.PENDING
                || entry.invoked) {
                return;
            }
            entry.invoked = true;
        }

        Throwable failure = null;
        try {
            entry.request.action().run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        finishRequest(token, id, failure);
    }

    private void finishRequest(long token, String id, Throwable failure) {
        CompletionEffects effects;
        synchronized (lock) {
            Entry entry = entries.get(id);
            if (entry == null || token != generation || entry.status != RequestStatus.PENDING || !entry.invoked) {
                return;
            }
            // timeout/cancel 只能阻止尚未开始的 action；已经进入 region owner 的世界写入无法抢占。
            // 因此必须等待它真实返回后再发布 barrier 结果，禁止 Future 先完成而 action 仍继续修改世界。
            entry.status = failure == null ? RequestStatus.COMPLETED : RequestStatus.FAILED;
            entry.failure = failure;
            rememberFailureLocked(failure);
            effects = terminal ? publishIfReadyLocked() : maybeCompleteWhenReadyLocked();
        }
        apply(effects);
    }

    private void markFailure(long token, String id, Throwable failure) {
        CompletionEffects effects;
        synchronized (lock) {
            if (token != generation || terminal) {
                return;
            }
            Entry entry = entries.get(id);
            if (entry == null || entry.status != RequestStatus.PENDING) {
                return;
            }
            entry.status = RequestStatus.FAILED;
            entry.failure = failure;
            rememberFailureLocked(failure);
            effects = maybeCompleteWhenReadyLocked();
        }
        apply(effects);
    }

    private void markOperationFailure(long token, Throwable failure) {
        CompletionEffects effects;
        synchronized (lock) {
            if (token != generation || terminal) {
                return;
            }
            terminal = true;
            terminalStatus = Status.FAILED;
            rememberFailureLocked(failure);
            markPendingLocked(RequestStatus.FAILED, failure);
            effects = publishIfReadyLocked();
        }
        apply(effects);
    }

    private void timeout(long token) {
        CompletionEffects effects;
        synchronized (lock) {
            if (token != generation || terminal || allRequestsFinishedLocked()) {
                return;
            }
            terminal = true;
            terminalStatus = Status.TIMED_OUT;
            markPendingLocked(RequestStatus.TIMED_OUT, null);
            effects = publishIfReadyLocked();
        }
        apply(effects);
    }

    private CompletionEffects maybeCompleteWhenReadyLocked() {
        if (terminal) {
            return publishIfReadyLocked();
        }
        if (!registrationComplete || !allRequestsFinishedLocked()) {
            return null;
        }
        terminal = true;
        terminalStatus = firstFailure == null ? Status.COMPLETED : Status.FAILED;
        return publishIfReadyLocked();
    }

    private boolean allRequestsFinishedLocked() {
        for (Entry entry : entries.values()) {
            if (entry.status == RequestStatus.PENDING) {
                return false;
            }
        }
        return true;
    }

    private void markPendingLocked(RequestStatus status, Throwable failure) {
        for (Entry entry : entries.values()) {
            if (entry.status == RequestStatus.PENDING && !entry.invoked) {
                entry.status = status;
                entry.failure = status == RequestStatus.FAILED ? failure : null;
                entry.cancelHandle = true;
            }
        }
    }

    private void rememberFailureLocked(Throwable failure) {
        if (failure != null && firstFailure == null) {
            firstFailure = failure;
        }
    }

    private CompletionEffects publishIfReadyLocked() {
        if (!terminal || !registrationComplete || !allRequestsFinishedLocked() || completionPublished) {
            return null;
        }
        completionPublished = true;
        generation++;
        List<MuzScheduler.TaskHandle> handles = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (entry.cancelHandle && entry.handle != null) {
                handles.add(entry.handle);
            }
        }
        if (timeoutHandle != null) {
            handles.add(timeoutHandle);
        }
        return new CompletionEffects(buildResultLocked(), handles);
    }

    private Result buildResultLocked() {
        LinkedHashMap<String, RequestResult> results = new LinkedHashMap<>();
        for (Entry entry : entries.values()) {
            results.put(entry.request.id(),
                new RequestResult(entry.request.id(), entry.status, entry.failure));
        }
        return new Result(terminalStatus, results, firstFailure);
    }

    private void apply(CompletionEffects effects) {
        if (effects == null) {
            return;
        }
        for (MuzScheduler.TaskHandle handle : effects.handles()) {
            handle.cancel();
        }
        future.complete(effects.result());
    }

    private static final class Entry {
        private final Request request;
        private RequestStatus status = RequestStatus.PENDING;
        private Throwable failure;
        private boolean invoked;
        private boolean cancelHandle;
        private MuzScheduler.TaskHandle handle;

        private Entry(Request request) {
            this.request = request;
        }
    }

    private record CompletionEffects(Result result, List<MuzScheduler.TaskHandle> handles) {
    }
}
