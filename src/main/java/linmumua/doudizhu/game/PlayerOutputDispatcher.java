package linmumua.doudizhu.game;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.scheduler.MuzScheduler;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 玩家输出与玩家归属任务的最小门面。
 *
 * <p>业务只传 UUID。兼容的 {@code runPlayer} 重载仍使用旧 Player scheduler；
 * {@code enqueuePlayer} 则把 UUID 交给 owner lane，再在回调内解析当前 Player。玩家 API
 * 只允许在 player lane 内调用，迟到回调会再次按 UUID 解析当前在线玩家。
 */
public final class PlayerOutputDispatcher {
    /**
     * 跨 region 跳过实体可见性操作时的限频窗口。
     *
     * <p>为什么不加限频会刷屏：{@code PhysicalTableManager} 的按人可见性重算（桌边动态、座位名/信息、
     * 手牌）会对**每个在线玩家 × 每个实体**显式调用一次 show/hide，并且每张桌的 owner tick 与
     * refresh 都会重跑；一旦某张桌的实体不归 viewer 所在 region，同一实体 UUID 会按「玩家数 × 每 2 秒」
     * 的节奏反复命中跳过分支。
     */
    private static final long SKIP_LOG_INTERVAL_MILLIS = 30_000L;

    private final PlayerTaskRegistry tasks;

    /**
     * 限频判定用的毫秒时钟。默认即 {@link System#currentTimeMillis()}，生产行为不变。
     *
     * <p>抽成接缝只为可测：30 秒窗口若只能靠真实等待来跨越，「被限频窗口丢掉的次数随下一次日志报出」这条
     * 语义就无法在单测里验证（睡眠 30 秒不可接受）。写法沿用仓库既有做法（见 {@code TableSpeechPanelService}
     * 的 tickSource）。
     */
    private final LongSupplier millisClock;

    /**
     * 每个实体上一次真正打日志的时间戳（毫秒）。限频的唯一键是实体 UUID，不是玩家。
     *
     * <p>【为什么必须有时间戳】：限频口径写的是「同实体 30 秒最多一条」，但只用 {@code Set} 记账会把
     * 它退化成「每个实体一辈子只打一条」——{@code PhysicalTableManager} 每 2 秒重算一次且永不停止，于是
     * 某个实体第一次被跳过之后，整轮服务端运行都不会再报它。窗口必须按真实时间判定，光有常量是不够的。
     */
    private final Map<UUID, Long> lastCrossRegionSkipLogMillis = new ConcurrentHashMap<>();

    /**
     * 每个实体在本窗口内被限频静默丢弃的跳过次数；真正打日志时随附并清零，避免限频变成「丢信息」。
     *
     * <p>按实体记账而不是全局：全局计数会让 A 实体的丢弃次数出现在 B 实体的日志里，反而误导排查。
     */
    private final Map<UUID, AtomicLong> suppressedCrossRegionSkips = new ConcurrentHashMap<>();

    /**
     * 生产构造：先在 global lane 按 UUID 查找调度器，再把实际输出排入 player lane。
     * 调用线程不会解析 Player；玩家执行阶段仍会重新按 UUID 解析当前在线实例。
     */
    public PlayerOutputDispatcher(DoudizhuPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        this.millisClock = System::currentTimeMillis;
        this.tasks = PlayerTaskRegistry.uuidFirst(
            // PlayerTaskRegistry 约定「解析器只返回在线玩家」，而 Bukkit.getPlayer 会返回离线实例。
            // canRevealHand / isPlayerPresent 等资格判断依赖 currentPlayer 的在线语义，所以在此补回
            // 离线门禁：此前直接用 Bukkit::getPlayer 会让离线真人拿到明牌资格（本批次重构引入的回归）。
            playerId -> {
                Player player = Bukkit.getPlayer(playerId);
                return player != null && player.isOnline() ? player : null;
            },
            (playerId, delayTicks, task) -> scheduleUuidPlayer(plugin, playerId, delayTicks, task)
        );
    }

    private static MuzScheduler.TaskHandle scheduleUuidPlayer(
        DoudizhuPlugin plugin,
        UUID playerId,
        long delayTicks,
        Runnable task
    ) {
        UuidPlayerTaskHandle composite = new UuidPlayerTaskHandle();
        MuzScheduler.TaskHandle global = plugin.scheduler().runGlobal(() -> {
            if (composite.isCancelled()) {
                return;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                composite.complete();
                return;
            }
            MuzScheduler.TaskHandle playerTask = plugin.scheduler().runPlayer(player, delayTicks, task);
            composite.bindPlayer(playerTask);
        });
        composite.bindGlobal(global);
        return composite;
    }

    /** 测试构造：注入玩家任务注册表，避免测试依赖 Bukkit 线程实现。 */
    public PlayerOutputDispatcher(PlayerTaskRegistry tasks) {
        this(tasks, System::currentTimeMillis);
    }

    /** 测试构造：额外注入毫秒时钟，让限频窗口可被测试推进而不是真实等待 30 秒。 */
    public PlayerOutputDispatcher(PlayerTaskRegistry tasks, LongSupplier millisClock) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.millisClock = Objects.requireNonNull(millisClock, "millisClock");
    }

    /** 返回 UUID 当前解析到的玩家；生产解析器只返回在线玩家。 */
    public Player currentPlayer(UUID playerId) {
        return tasks.currentPlayer(playerId);
    }

    public MuzScheduler.TaskHandle runPlayer(UUID playerId, Consumer<Player> action) {
        return runPlayer(playerId, 0L, action);
    }

    /**
     * 兼容保留的门面名称；生产注册表使用 UUID-first，测试/旧注入注册表继续使用兼容路径。
     */
    public MuzScheduler.TaskHandle runPlayer(UUID playerId, long delayTicks, Consumer<Player> action) {
        if (tasks.isUuidFirst()) {
            return tasks.enqueuePlayer(playerId, delayTicks, action);
        }
        return tasks.runPlayer(playerId, delayTicks, action);
    }

    /**
     * UUID-first 玩家任务入口；调用线程不解析 Player，解析在 global 查找后进入 player owner lane。
     */
    public MuzScheduler.TaskHandle enqueuePlayer(UUID playerId, Consumer<Player> action) {
        return tasks.enqueuePlayer(playerId, action);
    }

    /** UUID-first 延迟玩家任务入口。 */
    public MuzScheduler.TaskHandle enqueuePlayer(UUID playerId, long delayTicks, Consumer<Player> action) {
        return tasks.enqueuePlayer(playerId, delayTicks, action);
    }

    public void sendActionBar(UUID playerId, Component message) {
        runPlayer(playerId, player -> player.sendActionBar(message == null ? Component.empty() : message));
    }

    public void sendActionBar(Collection<UUID> playerIds, Component message) {
        if (playerIds == null) {
            return;
        }
        for (UUID playerId : playerIds) {
            sendActionBar(playerId, message);
        }
    }

    public void sendMessage(UUID playerId, Component message) {
        runPlayer(playerId, player -> player.sendMessage(message == null ? Component.empty() : message));
    }

    public void sendMessage(Collection<UUID> playerIds, Component message) {
        if (playerIds == null) {
            return;
        }
        for (UUID playerId : playerIds) {
            sendMessage(playerId, message);
        }
    }

    public void playSound(UUID playerId, String key, float volume, float pitch) {
        if (key == null || key.isBlank()) {
            return;
        }
        runPlayer(playerId, player -> {
            if (!player.isOnline()) {
                return;
            }
            player.playSound(player.getLocation(), key, volume, pitch);
        });
    }

    public void stopSound(UUID playerId, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        runPlayer(playerId, player -> {
            if (!player.isOnline()) {
                return;
            }
            player.stopSound(key);
        });
    }

    /** 在 player lane 显示标题；标题对象由调用方预先构造且不会捕获 Player。 */
    public void showTitle(UUID playerId, Title title) {
        if (title == null) {
            return;
        }
        runPlayer(playerId, player -> player.showTitle(title));
    }

    /** 在 player lane 显示 BossBar。 */
    public void showBossBar(UUID playerId, BossBar bossBar) {
        if (bossBar == null) {
            return;
        }
        runPlayer(playerId, player -> player.showBossBar(bossBar));
    }

    /** 在 player lane 隐藏 BossBar。 */
    public void hideBossBar(UUID playerId, BossBar bossBar) {
        if (bossBar == null) {
            return;
        }
        runPlayer(playerId, player -> player.hideBossBar(bossBar));
    }

    /**
     * 在 player lane 按实体 UUID 显示实体；执行时重新解析当前 live Entity。
     * 调用线程和排队闭包都不持有桌 owner 的旧实体对象。
     */
    public void showEntity(UUID viewerId, Plugin plugin, UUID entityId) {
        if (plugin == null || entityId == null) {
            return;
        }
        runPlayer(viewerId, player -> {
            Entity entity = Bukkit.getEntity(entityId);
            // Folia：showEntity 会读取实体状态（isVisibleByDefault），实体不属于本玩家所在 region 时直接抛
            // "Accessing entity state off owning region's thread"。跨 region 的实体本就不在该玩家追踪范围内，跳过即可。
            if (entity != null && entity.isValid() && Bukkit.isOwnedByCurrentRegion(entity)) {
                player.showEntity(plugin, entity);
                return;
            }
            reportCrossRegionSkip(plugin, "showEntity", viewerId, entityId, entity);
        });
    }

    /** 兼容旧 Entity 重载，但只在调用点立即转换为 UUID。 */
    public void showEntity(UUID viewerId, Plugin plugin, Entity entity) {
        if (entity == null) {
            return;
        }
        showEntity(viewerId, plugin, entity.getUniqueId());
    }

    /** 在 player lane 按实体 UUID 隐藏实体；执行时重新解析当前 live Entity。 */
    public void hideEntity(UUID viewerId, Plugin plugin, UUID entityId) {
        if (plugin == null || entityId == null) {
            return;
        }
        runPlayer(viewerId, player -> {
            Entity entity = Bukkit.getEntity(entityId);
            // 同 showEntity：跨 region 的实体不能在玩家 lane 访问；私有实体出生即 visibleByDefault=false，跳过不会泄漏。
            if (entity != null && entity.isValid() && Bukkit.isOwnedByCurrentRegion(entity)) {
                player.hideEntity(plugin, entity);
                return;
            }
            reportCrossRegionSkip(plugin, "hideEntity", viewerId, entityId, entity);
        });
    }

    /** 兼容旧 Entity 重载，但只在调用点立即转换为 UUID。 */
    public void hideEntity(UUID viewerId, Plugin plugin, Entity entity) {
        if (entity == null) {
            return;
        }
        hideEntity(viewerId, plugin, entity.getUniqueId());
    }

    /**
     * 记录一次「按设计跳过」的实体可见性调用，按实体 UUID 限频。
     *
     * <p>【为什么放弃可以，但不能无声】：这两个跳过分支在实服的表现是「某个玩家的桌边动态/座位名没
     * 按预期显示或隐藏」，而服务端毫无痕迹（与本项目此前修过的「道具投掷卡住且无日志」是同一类问题）。
     * 之前只有「该实体不存在」与「跨 region」两种情况被合并成静默 {@code if}，排查时无法区分
     * 「实体已删」和「实体不归本 region」，也无从知道哪个玩家、哪次操作被丢掉了。
     *
     * <p>【为什么不写成异常】跨 region 跳过本身是 Folia 语义下的正常结果：实体本就不在该玩家追踪范围，
     * 调用方（{@code PhysicalTableManager} 的按人可见性重算）会在下一次 refresh / owner tick 重新计算并
     * 重试，不需要把整个流程判失败。因此只提示痕迹，不改控制流。
     *
     * <p>【限频口径】同实体 30 秒最多一条（见 {@link #SKIP_LOG_INTERVAL_MILLIS}），并把被限频窗口丢掉的
     * 次数随下一次日志一起报出；换实体只换日志的「首次」，不重置其它实体的窗口。
     * {@code viewerId} 只记录在日志文本里参与排查，不作为限频键，否则默认的按人重算会按玩家数放大条数。
     */
    private void reportCrossRegionSkip(
        Plugin plugin,
        String operation,
        UUID viewerId,
        UUID entityId,
        Entity entity
    ) {
        String reason;
        if (entity == null) {
            // 实体已经不存在（桌被拆、区块重载后重建）。这是正常结果，但同样不能无声。
            reason = "实体不存在";
        } else if (!entity.isValid()) {
            reason = "实体已失效";
        } else {
            reason = "实体不归当前 region 所有";
        }
        // 计数在限频判定之前累加：本次调用无论最终是否打日志都会被算进「被折叠掉的次数」，
        // 打日志时再整体取走并清零。所以日志里报出的是「本窗口内除正在打的这一条之外被压掉的次数」，
        // 必须在取走后减掉本次调用自己，否则第一次日志会把自己算成「被静默丢弃」。
        AtomicLong suppressedForEntity = suppressedCrossRegionSkips.computeIfAbsent(
            entityId, ignored -> new AtomicLong()
        );
        suppressedForEntity.incrementAndGet();
        long now = millisClock.getAsLong();
        Long lastLogged = lastCrossRegionSkipLogMillis.putIfAbsent(entityId, now);
        if (lastLogged != null) {
            // 距上次打日志还不到限频窗口：本次静默丢弃，但计数留给下一次真正打日志时随附报出。
            if (now - lastLogged < SKIP_LOG_INTERVAL_MILLIS) {
                return;
            }
            // 窗口已过：抢占本次打日志的权利，抢不到说明另一个线程刚打过了。
            if (!lastCrossRegionSkipLogMillis.replace(entityId, lastLogged, now)) {
                return;
            }
        }
        // 取走并清零本窗口累计次数，再扣掉本次调用（它自己即将被打印，不算「被丢弃」）。
        long discarded = Math.max(0L, suppressedForEntity.getAndSet(0L) - 1L);
        plugin.getLogger().warning(
            "跳过跨 region 的实体可见性操作 " + operation + "（" + reason + "，按实体 " + SKIP_LOG_INTERVAL_MILLIS / 1000
                + " 秒限频）: 实体 " + entityId + "，玩家 " + viewerId + "，本窗口内已静默丢弃 " + discarded + " 次");
    }

    /** 在 player lane 打开指定库存。 */
    public void openInventory(UUID playerId, Inventory inventory) {
        if (inventory == null) {
            return;
        }
        runPlayer(playerId, player -> player.openInventory(inventory));
    }

    /** 在 player lane 关闭当前库存。 */
    public void closeInventory(UUID playerId) {
        runPlayer(playerId, Player::closeInventory);
    }

    /** 在 player lane 向指定玩家发送粒子。 */
    public void spawnParticle(
        UUID playerId,
        Particle particle,
        Location location,
        int count,
        double offsetX,
        double offsetY,
        double offsetZ,
        double extra,
        Object data
    ) {
        if (particle == null || location == null || count < 0) {
            return;
        }
        runPlayer(playerId, player -> player.spawnParticle(
            particle, location, count, offsetX, offsetY, offsetZ, extra, data
        ));
    }

    /** 在 player lane 执行玩家命令；控制台命令不应调用此方法。 */
    public void performCommand(UUID playerId, String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        runPlayer(playerId, player -> player.performCommand(command));
    }

    public void cancel(UUID playerId) {
        tasks.cancel(playerId);
    }

    /** 不可逆关闭玩家输出；关闭后不再接受新的 player lane 任务。 */
    public void close() {
        tasks.close();
    }

    /** 终止所有玩家任务并永久关闭新的任务注册。 */
    public void cancelAll() {
        tasks.close();
    }

    int trackedTaskCount() {
        return tasks.trackedTaskCount();
    }

    /** 将 global 查找阶段与随后绑定的 player 任务合并为一个可取消句柄。 */
    private static final class UuidPlayerTaskHandle implements MuzScheduler.TaskHandle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicReference<MuzScheduler.TaskHandle> global = new AtomicReference<>();
        private final AtomicReference<MuzScheduler.TaskHandle> player = new AtomicReference<>();
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

        private void bindGlobal(MuzScheduler.TaskHandle handle) {
            if (!global.compareAndSet(null, handle)) {
                handle.cancel();
                return;
            }
            if (cancelled.get()) {
                handle.cancel();
                terminate();
            }
        }

        private void bindPlayer(MuzScheduler.TaskHandle handle) {
            if (handle == null) {
                complete();
                return;
            }
            if (!player.compareAndSet(null, handle)) {
                handle.cancel();
                return;
            }
            handle.onTermination(this::terminate);
            if (cancelled.get()) {
                handle.cancel();
                terminate();
            }
        }

        private void complete() {
            terminate();
        }

        private void terminate() {
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            for (Runnable listener : listeners) {
                listener.run();
            }
            listeners.clear();
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            MuzScheduler.TaskHandle globalHandle = global.get();
            if (globalHandle != null) {
                globalHandle.cancel();
            }
            MuzScheduler.TaskHandle playerHandle = player.get();
            if (playerHandle != null) {
                playerHandle.cancel();
            }
            terminate();
        }

        @Override
        public boolean isCancelled() {
            MuzScheduler.TaskHandle playerHandle = player.get();
            return cancelled.get()
                || (playerHandle != null && playerHandle.isCancelled());
        }

        @Override
        public void onTermination(Runnable listener) {
            Objects.requireNonNull(listener, "listener");
            if (terminated.get()) {
                listener.run();
                return;
            }
            listeners.add(listener);
            if (terminated.get() && listeners.remove(listener)) {
                listener.run();
            }
        }

        @Override
        public String ownerId() {
            return "player";
        }
    }
}
