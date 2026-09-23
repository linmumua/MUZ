package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import linmumua.doudizhu.scheduler.MuzScheduler;
import org.bukkit.Location;

/**
 * 牌桌周期任务注册表。
 *
 * <p>周期描述和牌桌实例身份与具体调度 lane 分离：逻辑桌尚未放置时使用 global，
 * 放置成功后切换到锚点所在 region。重绑定先使旧绑定代次失效，再在共享锁外取消旧
 * 任务并注册新任务，避免迟到回调写回同一名称的新桌，也避免业务回调运行在注册表锁内。
 *
 * @author linmumua
 * @Desc 管理牌桌周期任务的 global/region 重绑定与生命周期
 * @date 2026-09-21
 */
public final class TablePeriodicTaskRegistry {
    /* 调度门面；所有实际任务都通过此门面创建。 */
    private final MuzScheduler scheduler;
    /* 注册表共享锁；只保护条目和绑定代次，不包住业务回调或取消调用。 */
    private final Object lock = new Object();
    /* 按规范化牌桌名保存当前实例条目。 */
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    /* 牌桌 owner 的一次性延迟任务；与周期绑定分开，允许同桌同时等待多个回调。 */
    private final Map<String, List<OneShotEntry>> oneShotEntries = new LinkedHashMap<>();

    /**
     * @param scheduler 调度门面
     */
    public TablePeriodicTaskRegistry(MuzScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * 注册一个尚未重绑定的牌桌周期任务。
     *
     * @param ownerKey 牌桌规范化名称
     * @param ownerInstance 牌桌实例身份
     * @param description 周期描述
     * @param anchor 锚点；为空或没有世界时使用 global
     * @param callback 业务回调
     * @return 稳定的外部取消句柄
     */
    public MuzScheduler.TaskHandle register(
        String ownerKey,
        Object ownerInstance,
        PeriodicDescription description,
        Location anchor,
        Runnable callback
    ) {
        String key = requireKey(ownerKey);
        Objects.requireNonNull(ownerInstance, "ownerInstance");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(callback, "callback");
        Entry entry = new Entry(key, ownerInstance, description, callback);
        synchronized (lock) {
            if (entries.containsKey(key)) {
                throw new IllegalStateException("牌桌周期任务已注册: " + ownerKey);
            }
            entries.put(key, entry);
        }
        try {
            if (!bind(entry, anchor)) {
                throw new IllegalStateException("牌桌周期任务未能建立有效绑定: " + ownerKey);
            }
            return entry.handle;
        } catch (RuntimeException | Error failure) {
            removeAfterFailure(entry);
            throw failure;
        }
    }

    /**
     * 为牌桌 owner 注册一次性延迟任务。
     *
     * <p>一次性任务也经过 owner 实例校验：桌被注销或同名新桌接管后，迟到回调只会失效，
     * 不会把旧状态写回新桌。锚点为空时使用 global，否则使用锚点所在 region。
     *
     * @param ownerKey 牌桌规范化名称
     * @param ownerInstance 牌桌实例身份
     * @param anchor 锚点；为空或没有世界时使用 global
     * @param delayTicks 延迟 tick
     * @param callback 业务回调
     * @return 稳定的取消句柄
     */
    public MuzScheduler.TaskHandle scheduleLater(
        String ownerKey,
        Object ownerInstance,
        Location anchor,
        long delayTicks,
        Runnable callback
    ) {
        String key = requireKey(ownerKey);
        Objects.requireNonNull(ownerInstance, "ownerInstance");
        Objects.requireNonNull(callback, "callback");
        if (delayTicks < 0L) {
            throw new IllegalArgumentException("牌桌延迟任务 tick 不能为负数: " + delayTicks);
        }
        OneShotEntry entry = new OneShotEntry(key, ownerInstance, callback);
        synchronized (lock) {
            oneShotEntries.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }
        MuzScheduler.TaskHandle delegate;
        try {
            Runnable guarded = () -> dispatchOneShot(entry);
            delegate = AnchorKey.of(anchor) == null
                ? scheduler.runGlobalLater(delayTicks, guarded)
                : scheduler.runRegionLater(anchor, delayTicks, guarded);
            boolean accepted;
            synchronized (lock) {
                entry.delegate = delegate;
                accepted = !entry.cancelled && containsOneShotLocked(entry);
            }
            delegate.onTermination(() -> removeOneShot(entry));
            boolean invalidated;
            synchronized (lock) {
                invalidated = entry.cancelled;
            }
            if (!accepted || invalidated) {
                // 重绑定/注销可能在后端注册期间已经使 entry 失效；状态先在锁内摘除，
                // 这里只负责在锁外补取消，避免迟到 forceFire 越过代次屏障。
                cancelQuietly(delegate);
                return new OneShotHandle(entry);
            }
            if (delegate.isCancelled()) {
                removeOneShot(entry);
                throw new IllegalStateException("调度后端返回了已终止的牌桌延迟任务: " + ownerKey);
            }
            return new OneShotHandle(entry);
        } catch (RuntimeException | Error failure) {
            removeOneShot(entry);
            throw failure;
        }
    }

    /**
     * 将当前实例绑定到新的锚点。相同锚点通知幂等，已取消或实例不匹配时不会复活任务。
     *
     * @param ownerKey 牌桌规范化名称
     * @param ownerInstance 牌桌实例身份
     * @param anchor 新锚点；为空时切回 global
     * @return 是否接受了通知；不存在或身份不匹配时返回 false
     */
    public boolean rebind(String ownerKey, Object ownerInstance, Location anchor) {
        String key = requireKey(ownerKey);
        Objects.requireNonNull(ownerInstance, "ownerInstance");
        Entry entry;
        synchronized (lock) {
            entry = entries.get(key);
            if (entry == null || entry.ownerInstance != ownerInstance || entry.cancelled) {
                return false;
            }
        }
        return bind(entry, anchor);
    }

    /**
     * 将同一 owner 实例的全部周期任务重绑定到新锚点。
     *
     * @param ownerInstance 牌桌实例身份
     * @param anchor 新锚点；为空时切回 global
     * @return 实际重绑定的任务数量
     */
    public int rebindOwner(Object ownerInstance, Location anchor) {
        Objects.requireNonNull(ownerInstance, "ownerInstance");
        List<Entry> snapshot;
        synchronized (lock) {
            snapshot = entries.values().stream()
                .filter(entry -> entry.ownerInstance == ownerInstance && !entry.cancelled)
                .toList();
        }
        int rebound = 0;
        for (Entry entry : snapshot) {
            if (bind(entry, anchor)) {
                rebound++;
            }
        }
        return rebound;
    }

    /**
     * 取消同一 owner 实例的全部周期任务与一次性延迟任务。
     *
     * @param ownerInstance 牌桌实例身份
     * @return 是否找到并取消了至少一个任务
     */
    public boolean cancelOwner(Object ownerInstance) {
        Objects.requireNonNull(ownerInstance, "ownerInstance");
        List<String> keys;
        synchronized (lock) {
            keys = entries.values().stream()
                .filter(entry -> entry.ownerInstance == ownerInstance)
                .map(entry -> entry.ownerKey)
                .toList();
        }
        boolean cancelled = false;
        for (String key : keys) {
            cancelled |= cancel(key, ownerInstance);
        }
        return cancelled;
    }

    /**
     * 取消当前实例的周期任务。重复调用安全，取消后旧句柄不能被重绑定复活。
     *
     * @param ownerKey 牌桌规范化名称
     * @param ownerInstance 牌桌实例身份
     * @return 是否找到并取消了当前实例
     */
    public boolean cancel(String ownerKey, Object ownerInstance) {
        String key = requireKey(ownerKey);
        Objects.requireNonNull(ownerInstance, "ownerInstance");
        Entry entry;
        MuzScheduler.TaskHandle current;
        List<MuzScheduler.TaskHandle> oneShots;
        synchronized (lock) {
            entry = entries.get(key);
            if (entry == null || entry.ownerInstance != ownerInstance) {
                return false;
            }
            entries.remove(key);
            entry.cancelled = true;
            entry.bindingGeneration++;
            current = entry.boundTask;
            entry.boundTask = null;
            oneShots = invalidateOneShots(key, ownerInstance);
        }
        cancelQuietly(current);
        for (MuzScheduler.TaskHandle oneShot : oneShots) {
            cancelQuietly(oneShot);
        }
        return true;
    }

    /**
     * 取消并清空所有牌桌周期任务。
     */
    public void cancelAll() {
        List<MuzScheduler.TaskHandle> tasksToCancel = new ArrayList<>();
        synchronized (lock) {
            List<Entry> snapshot = new ArrayList<>(entries.values());
            entries.clear();
            for (Entry entry : snapshot) {
                entry.cancelled = true;
                entry.bindingGeneration++;
                if (entry.boundTask != null) {
                    tasksToCancel.add(entry.boundTask);
                }
                entry.boundTask = null;
                tasksToCancel.addAll(invalidateOneShots(entry.ownerKey, entry.ownerInstance));
            }
            oneShotEntries.clear();
        }
        for (MuzScheduler.TaskHandle task : tasksToCancel) {
            cancelQuietly(task);
        }
    }

    /**
     * 返回当前注册数量，供可控行为测试与诊断使用。
     */
    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    private boolean bind(Entry entry, Location anchor) {
        AnchorKey targetKey = AnchorKey.of(anchor);
        long generation;
        MuzScheduler.TaskHandle oldTask;
        List<MuzScheduler.TaskHandle> oneShotsToCancel;
        synchronized (lock) {
            if (entry.cancelled || entries.get(entry.ownerKey) != entry) {
                return false;
            }
            boolean sameActiveBinding = Objects.equals(entry.anchorKey, targetKey)
                && entry.boundTask != null
                && !entry.boundTask.isCancelled();
            if (sameActiveBinding) {
                return true;
            }
            // 只先推进旧周期代次并摘除 owner 的一次性任务；anchorKey 要等新任务真正接受后再提交，
            // 失败时允许同锚点重试，但旧 one-shot 即使后端 forceFire 也只能命中失效门禁。
            generation = ++entry.bindingGeneration;
            oldTask = entry.boundTask;
            entry.boundTask = null;
            oneShotsToCancel = invalidateOneShots(entry.ownerKey, entry.ownerInstance);
        }
        // 旧 generation 与一次性任务状态已在锁内失效，后端取消统一在锁外执行，避免回调反向进入注册表时死锁。
        cancelQuietly(oldTask);
        for (MuzScheduler.TaskHandle oneShot : oneShotsToCancel) {
            cancelQuietly(oneShot);
        }

        MuzScheduler.TaskHandle newTask;
        try {
            Runnable guarded = () -> dispatch(entry, generation);
            newTask = targetKey == null
                ? scheduler.runGlobalTimer(entry.description.delayTicks(), entry.description.periodTicks(), guarded)
                : scheduler.runRegionTimer(anchor, entry.description.delayTicks(), entry.description.periodTicks(), guarded);
        } catch (RuntimeException | Error failure) {
            clearFailedBinding(entry, generation);
            throw failure;
        }
        if (newTask == null || newTask.isCancelled()) {
            cancelQuietly(newTask);
            clearFailedBinding(entry, generation);
            throw new IllegalStateException("调度后端返回了已终止的牌桌周期任务: " + entry.ownerKey);
        }
        try {
            newTask.onTermination(() -> onTaskTerminated(entry, generation, newTask));
        } catch (RuntimeException | Error failure) {
            clearFailedBinding(entry, generation);
            cancelQuietly(newTask);
            throw failure;
        }

        boolean accepted;
        synchronized (lock) {
            accepted = !entry.cancelled
                && entries.get(entry.ownerKey) == entry
                && entry.bindingGeneration == generation
                && !newTask.isCancelled();
            if (accepted) {
                entry.anchorKey = targetKey;
                entry.boundTask = newTask;
            }
        }
        if (!accepted) {
            cancelQuietly(newTask);
        }
        if (!accepted) {
            throw new IllegalStateException("牌桌周期任务绑定已失效: " + entry.ownerKey);
        }
        return true;
    }

    private void clearFailedBinding(Entry entry, long generation) {
        synchronized (lock) {
            if (entries.get(entry.ownerKey) == entry && entry.bindingGeneration == generation) {
                entry.boundTask = null;
            }
        }
    }

    private void onTaskTerminated(Entry entry, long generation, MuzScheduler.TaskHandle task) {
        synchronized (lock) {
            if (entries.get(entry.ownerKey) == entry
                && entry.bindingGeneration == generation
                && entry.boundTask == task) {
                entry.boundTask = null;
                // 终止后旧 callback 永远不能重新进入；保留 anchorKey 供同锚点重试。
                entry.bindingGeneration++;
            }
        }
    }

    private void dispatch(Entry entry, long generation) {
        Runnable callback;
        synchronized (lock) {
            if (entry.cancelled
                || entries.get(entry.ownerKey) != entry
                || entry.bindingGeneration != generation
                || entry.boundTask == null) {
                return;
            }
            callback = entry.callback;
        }
        // 严禁在共享注册表锁内执行牌桌业务。
        callback.run();
    }

    /**
     * 在共享锁内推进并摘除 owner 的一次性任务；返回值只供调用方在锁外取消。
     */
    private List<MuzScheduler.TaskHandle> invalidateOneShots(String ownerKey, Object ownerInstance) {
        List<OneShotEntry> ownerEntries = oneShotEntries.remove(ownerKey);
        if (ownerEntries == null || ownerEntries.isEmpty()) {
            return List.of();
        }
        List<MuzScheduler.TaskHandle> tasks = new ArrayList<>();
        for (OneShotEntry entry : ownerEntries) {
            if (entry.ownerInstance == ownerInstance) {
                entry.cancelled = true;
                if (entry.delegate != null) {
                    tasks.add(entry.delegate);
                }
            } else {
                oneShotEntries.computeIfAbsent(ownerKey, ignored -> new ArrayList<>()).add(entry);
            }
        }
        return tasks;
    }

    private boolean containsOneShotLocked(OneShotEntry entry) {
        List<OneShotEntry> ownerEntries = oneShotEntries.get(entry.ownerKey);
        return ownerEntries != null && ownerEntries.contains(entry);
    }

    private void dispatchOneShot(OneShotEntry entry) {
        synchronized (lock) {
            if (entry.cancelled || !removeOneShotLocked(entry)) {
                return;
            }
            entry.cancelled = true;
        }
        entry.callback.run();
    }

    private void removeOneShot(OneShotEntry entry) {
        synchronized (lock) {
            removeOneShotLocked(entry);
        }
    }

    private boolean removeOneShotLocked(OneShotEntry entry) {
        List<OneShotEntry> ownerEntries = oneShotEntries.get(entry.ownerKey);
        if (ownerEntries == null || !ownerEntries.remove(entry)) {
            return false;
        }
        if (ownerEntries.isEmpty()) {
            oneShotEntries.remove(entry.ownerKey);
        }
        return true;
    }

    private void removeAfterFailure(Entry entry) {
        MuzScheduler.TaskHandle task;
        synchronized (lock) {
            if (entries.get(entry.ownerKey) != entry) {
                return;
            }
            entries.remove(entry.ownerKey);
            entry.cancelled = true;
            entry.bindingGeneration++;
            task = entry.boundTask;
            entry.boundTask = null;
        }
        cancelQuietly(task);
    }

    private void cancelQuietly(MuzScheduler.TaskHandle task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException ignored) {
            // 调度后端的取消失败不能恢复旧绑定，也不能阻止其它桌继续注销。
        }
    }

    private static String requireKey(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new IllegalArgumentException("牌桌周期任务 owner 不能为空。");
        }
        return ownerKey;
    }

    /**
     * 周期任务的不可变描述。
     *
     * @param delayTicks 首次延迟
     * @param periodTicks 周期间隔
     */
    public record PeriodicDescription(long delayTicks, long periodTicks) {
        public PeriodicDescription {
            if (delayTicks < 0L || periodTicks <= 0L) {
                throw new IllegalArgumentException(
                    "牌桌周期任务 tick 必须为 delay>=0 且 period>0: " + delayTicks + ", " + periodTicks
                );
            }
        }
    }

    private final class Entry {
        /* 牌桌规范化名称。 */
        private final String ownerKey;
        /* 牌桌实例身份；同名新桌不会复用旧条目。 */
        private final Object ownerInstance;
        /* 不变的周期描述。 */
        private final PeriodicDescription description;
        /* 不在注册表锁内执行的业务回调。 */
        private final Runnable callback;
        /* 对外稳定句柄。 */
        private final RebindingHandle handle = new RebindingHandle(this);
        /* 当前锚点身份；null 代表 global。 */
        private AnchorKey anchorKey;
        /* 当前绑定代次。 */
        private long bindingGeneration;
        /* 当前后端句柄。 */
        private MuzScheduler.TaskHandle boundTask;
        /* 取消后永久失效。 */
        private boolean cancelled;

        private Entry(String ownerKey, Object ownerInstance, PeriodicDescription description, Runnable callback) {
            this.ownerKey = ownerKey;
            this.ownerInstance = ownerInstance;
            this.description = description;
            this.callback = callback;
        }

    }

    private final class OneShotEntry {
        /* 牌桌规范化名称。 */
        private final String ownerKey;
        /* 牌桌实例身份；同名新桌不能接管旧回调。 */
        private final Object ownerInstance;
        /* 不在注册表锁内执行的业务回调。 */
        private final Runnable callback;
        /* 调度后端句柄；注册期间可能尚未写入。 */
        private MuzScheduler.TaskHandle delegate;
        /* 取消或已消费后永久失效。 */
        private boolean cancelled;

        private OneShotEntry(String ownerKey, Object ownerInstance, Runnable callback) {
            this.ownerKey = ownerKey;
            this.ownerInstance = ownerInstance;
            this.callback = callback;
        }
    }

    private final class OneShotHandle implements MuzScheduler.TaskHandle {
        /* 关联的一次性 owner 任务。 */
        private final OneShotEntry entry;

        private OneShotHandle(OneShotEntry entry) {
            this.entry = entry;
        }

        @Override
        public void cancel() {
            MuzScheduler.TaskHandle delegate;
            synchronized (lock) {
                if (entry.cancelled) {
                    return;
                }
                entry.cancelled = true;
                removeOneShotLocked(entry);
                delegate = entry.delegate;
            }
            cancelQuietly(delegate);
        }

        @Override
        public boolean isCancelled() {
            synchronized (lock) {
                return entry.cancelled || (entry.delegate != null && entry.delegate.isCancelled());
            }
        }

        @Override
        public long generation() {
            synchronized (lock) {
                return entry.delegate == null ? -1L : entry.delegate.generation();
            }
        }

        @Override
        public String ownerId() {
            synchronized (lock) {
                return entry.delegate == null ? "table-owner" : entry.delegate.ownerId();
            }
        }
    }

    private final class RebindingHandle implements MuzScheduler.TaskHandle {
        /* 关联的稳定注册条目。 */
        private final Entry entry;

        private RebindingHandle(Entry entry) {
            this.entry = entry;
        }

        @Override
        public void cancel() {
            TablePeriodicTaskRegistry.this.cancel(entry.ownerKey, entry.ownerInstance);
        }

        @Override
        public boolean isCancelled() {
            synchronized (lock) {
                return entry.cancelled;
            }
        }

        @Override
        public long generation() {
            synchronized (lock) {
                return entry.bindingGeneration;
            }
        }

        @Override
        public String ownerId() {
            synchronized (lock) {
                return entry.boundTask == null ? "table-periodic" : entry.boundTask.ownerId();
            }
        }
    }

    private record AnchorKey(java.util.UUID worldId, long x, long y, long z) {
        private static AnchorKey of(Location anchor) {
            if (anchor == null || anchor.getWorld() == null) {
                return null;
            }
            return new AnchorKey(
                anchor.getWorld().getUID(),
                Double.doubleToLongBits(anchor.getX()),
                Double.doubleToLongBits(anchor.getY()),
                Double.doubleToLongBits(anchor.getZ())
            );
        }
    }
}
