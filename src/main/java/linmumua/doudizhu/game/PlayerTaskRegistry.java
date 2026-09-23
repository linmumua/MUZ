package linmumua.doudizhu.game;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import linmumua.doudizhu.scheduler.MuzScheduler;
import org.bukkit.entity.Player;

/**
 * 按玩家 UUID 管理玩家归属任务。
 *
 * <p>调用方只保存 UUID。兼容的 {@link #runPlayer(UUID, long, Consumer)} 仍沿用旧的
 * Player scheduler 入参；{@link #enqueuePlayer(UUID, long, Consumer)} 则只排 UUID，
 * 在 owner lane 回调内解析当前在线玩家，避免把旧 Player 实例传到跨线程或迟到回调中。
 */
public final class PlayerTaskRegistry {
    @FunctionalInterface
    public interface PlayerResolver extends Function<UUID, Player> {
    }

    @FunctionalInterface
    public interface PlayerScheduler {
        MuzScheduler.TaskHandle run(Player player, long delayTicks, Runnable task);
    }

    /**
     * UUID-first 的玩家调度入口。实现方必须保证回调已经位于该 UUID 的 owner lane，
     * 这样回调内部的 Player 解析才不会发生在调用线程。
     */
    @FunctionalInterface
    public interface UuidPlayerScheduler {
        MuzScheduler.TaskHandle run(UUID playerId, long delayTicks, Runnable task);
    }

    private final PlayerResolver playerResolver;
    private final PlayerScheduler playerScheduler;
    private final UuidPlayerScheduler uuidPlayerScheduler;
    private final Map<UUID, Set<RegisteredTask>> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cancellationGenerations = new HashMap<>();
    private final Object registrationLock = new Object();
    private boolean accepting = true;

    public PlayerTaskRegistry(PlayerResolver playerResolver, PlayerScheduler playerScheduler) {
        this(playerResolver, Objects.requireNonNull(playerScheduler, "playerScheduler"), null);
    }

    /**
     * 创建只排 UUID 的注册表。
     *
     * <p>现有 {@link #PlayerTaskRegistry(PlayerResolver, PlayerScheduler)} 保持兼容；
     * 新入口不在调用线程解析 Player，解析只会发生在 UUID scheduler 回调的 owner lane。
     */
    public static PlayerTaskRegistry uuidFirst(
        PlayerResolver playerResolver,
        UuidPlayerScheduler uuidPlayerScheduler
    ) {
        return new PlayerTaskRegistry(
            Objects.requireNonNull(playerResolver, "playerResolver"),
            null,
            Objects.requireNonNull(uuidPlayerScheduler, "uuidPlayerScheduler")
        );
    }

    private PlayerTaskRegistry(
        PlayerResolver playerResolver,
        PlayerScheduler playerScheduler,
        UuidPlayerScheduler uuidPlayerScheduler
    ) {
        this.playerResolver = Objects.requireNonNull(playerResolver, "playerResolver");
        this.playerScheduler = playerScheduler;
        this.uuidPlayerScheduler = uuidPlayerScheduler;
    }

    /** 返回当前注册表是否已配置 UUID-first 调度。 */
    public boolean isUuidFirst() {
        return uuidPlayerScheduler != null;
    }

    /** 返回解析器当前提供的玩家；生产解析器只返回在线玩家，UUID 为空时返回 null。 */
    public Player currentPlayer(UUID playerId) {
        synchronized (registrationLock) {
            return resolveCurrentPlayerLocked(playerId);
        }
    }

    /** 将一个玩家操作投递到当前玩家所属的 scheduler lane。 */
    public MuzScheduler.TaskHandle runPlayer(UUID playerId, Consumer<Player> action) {
        return runPlayer(playerId, 0L, action);
    }

    /**
     * 仅排入 UUID 的玩家操作；调用线程不解析 Player。
     *
     * <p>当前 UUID scheduler 不可用时显式失败，避免悄悄退回调用线程解析；旧
     * {@link #runPlayer(UUID, long, Consumer)} 继续保留兼容语义。
     */
    public MuzScheduler.TaskHandle enqueuePlayer(UUID playerId, Consumer<Player> action) {
        return enqueuePlayer(playerId, 0L, action);
    }

    /** 在 UUID owner lane 延迟执行玩家操作，并在该 lane 内解析当前 Player。 */
    public MuzScheduler.TaskHandle enqueuePlayer(UUID playerId, long delayTicks, Consumer<Player> action) {
        Objects.requireNonNull(action, "action");
        UuidPlayerScheduler scheduler = uuidPlayerScheduler;
        if (scheduler == null) {
            throw new IllegalStateException("UUID player scheduler is not configured");
        }
        RegisteredTask registered;
        synchronized (registrationLock) {
            if (!accepting || playerId == null) {
                return null;
            }
            long generation = cancellationGenerations.getOrDefault(playerId, 0L);
            registered = new RegisteredTask(playerId, generation);
            tasks.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(registered);
        }
        try {
            MuzScheduler.TaskHandle delegate = scheduler.run(
                playerId,
                delayTicks,
                () -> dispatch(registered, action)
            );
            registered.bind(delegate);
            return registered;
        } catch (RuntimeException | Error exception) {
            registered.cancel();
            throw exception;
        }
    }

    /** 延迟投递一个玩家操作；玩家离线、注册表关闭或取消代次变化时不创建任务。 */
    public MuzScheduler.TaskHandle runPlayer(UUID playerId, long delayTicks, Consumer<Player> action) {
        Objects.requireNonNull(action, "action");
        PlayerScheduler scheduler = playerScheduler;
        if (scheduler == null) {
            throw new IllegalStateException("legacy player scheduler is not configured");
        }
        Player scheduledPlayer;
        RegisteredTask registered;
        synchronized (registrationLock) {
            if (!accepting || playerId == null) {
                return null;
            }
            long generation = cancellationGenerations.getOrDefault(playerId, 0L);
            scheduledPlayer = resolveCurrentPlayerLocked(playerId);
            if (!accepting || generation != cancellationGenerations.getOrDefault(playerId, 0L)
                || scheduledPlayer == null) {
                return null;
            }
            registered = new RegisteredTask(playerId, generation);
            tasks.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(registered);
        }
        try {
            MuzScheduler.TaskHandle delegate = scheduler.run(
                scheduledPlayer,
                delayTicks,
                () -> dispatch(registered, action)
            );
            registered.bind(delegate);
            return registered;
        } catch (RuntimeException | Error exception) {
            registered.cancel();
            throw exception;
        }
    }

    /** 取消指定玩家的所有待执行任务，并递增该 UUID 的不可复用取消代次。 */
    public void cancel(UUID playerId) {
        if (playerId == null) {
            return;
        }
        Set<RegisteredTask> registered;
        synchronized (registrationLock) {
            cancellationGenerations.put(playerId, cancellationGenerations.getOrDefault(playerId, 0L) + 1L);
            registered = tasks.remove(playerId);
            if (registered == null) {
                return;
            }
            registered = Set.copyOf(registered);
        }
        for (RegisteredTask task : registered) {
            task.cancel();
        }
    }

    /**
     * 不可逆关闭注册表：取消当前全部任务，并拒绝之后所有新注册。
     *
     * <p>旧 Player scheduler 退休后不自动重排；若后端丢弃已排任务，业务输出随之丢弃。
     */
    public void close() {
        Set<RegisteredTask> registered;
        synchronized (registrationLock) {
            if (!accepting) {
                return;
            }
            accepting = false;
            registered = tasks.values().stream()
                .flatMap(Set::stream)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            tasks.clear();
        }
        for (RegisteredTask task : registered) {
            task.cancel();
        }
    }

    /** 终止所有玩家任务；终止后注册表不再接受新任务。 */
    public void cancelAll() {
        close();
    }

    private void dispatch(RegisteredTask registered, Consumer<Player> action) {
        if (!registered.tryEnter()) {
            return;
        }
        try {
            Player current = registered.resolveCurrentPlayer();
            if (current != null) {
                action.accept(current);
            }
        } finally {
            registered.exit();
            registered.terminate();
        }
    }

    private Player resolveCurrentPlayerLocked(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        return playerResolver.apply(playerId);
    }

    int trackedTaskCount() {
        synchronized (registrationLock) {
            return tasks.values().stream().mapToInt(Set::size).sum();
        }
    }

    private void remove(RegisteredTask task) {
        synchronized (registrationLock) {
            Set<RegisteredTask> registered = tasks.get(task.playerId);
            if (registered == null) {
                return;
            }
            registered.remove(task);
            if (registered.isEmpty()) {
                tasks.remove(task.playerId, registered);
            }
        }
    }

    private final class RegisteredTask implements MuzScheduler.TaskHandle {
        private final UUID playerId;
        private final long generation;
        private final AtomicReference<MuzScheduler.TaskHandle> delegate = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final Object terminationLock = new Object();
        private final List<Runnable> terminationListeners = new CopyOnWriteArrayList<>();

        private RegisteredTask(UUID playerId, long generation) {
            this.playerId = playerId;
            this.generation = generation;
        }

        private void bind(MuzScheduler.TaskHandle handle) {
            if (handle == null) {
                cancelled.set(true);
                terminate();
                return;
            }
            if (delegate.compareAndSet(null, handle)) {
                handle.onTermination(this::delegateTerminated);
            }
            if (cancelled.get()) {
                handle.cancel();
            }
        }

        private void delegateTerminated() {
            if (!running.get()) {
                terminate();
            }
        }

        private boolean tryEnter() {
            boolean allowed;
            synchronized (registrationLock) {
                allowed = accepting
                    && !cancelled.get()
                    && !terminated.get()
                    && generation == cancellationGenerations.getOrDefault(playerId, 0L)
                    && running.compareAndSet(false, true);
            }
            if (!allowed) {
                terminate();
            }
            return allowed;
        }

        private Player resolveCurrentPlayer() {
            synchronized (registrationLock) {
                if (!accepting || cancelled.get() || terminated.get()
                    || generation != cancellationGenerations.getOrDefault(playerId, 0L)) {
                    return null;
                }
                return resolveCurrentPlayerLocked(playerId);
            }
        }

        private void exit() {
            running.set(false);
        }

        private void terminate() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            remove(this);
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
            remove(this);
            MuzScheduler.TaskHandle current = delegate.get();
            if (current != null && current != this) {
                current.cancel();
            }
            if (!running.get()) {
                terminate();
            }
        }

        @Override
        public boolean isCancelled() {
            MuzScheduler.TaskHandle current = delegate.get();
            return cancelled.get() || (current != null && current.isCancelled());
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
    }
}
