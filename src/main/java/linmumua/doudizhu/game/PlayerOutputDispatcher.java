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
import java.util.function.BiConsumer;
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
 *
 * <p>【唯一例外：实体可见性走实体 owner region】{@link #showEntity(UUID, Plugin, UUID)} 与
 * {@link #hideEntity(UUID, Plugin, UUID)} 不落在 player lane，而是经 {@link EntityRegionLane}
 * 投递到**实体所属 region**。这不是取舍，而是两个 API 的语义逼出来的唯一合法 lane：
 *
 * <ul>
 *   <li>玩家侧：{@code player.showEntity/hideEntity} 会读**实体**状态——
 *       {@code CraftPlayer#showEntity0} 里的 {@code Entity#isVisibleByDefault()} 与
 *       {@code CraftPlayer#canSee(entity)} 都经 {@code CraftEntity#getHandle()}，而该方法在
 *       Folia 上带 owner region 门禁（{@code TickThread.ensureTickThread(entity,
 *       "Accessing entity state off owning region's thread")}）。所以这些调用只有在实体的 owner
 *       region 上才不抛；放在玩家自己的 lane 上，只要玩家与实体不在同一个 region 就必然抛。</li>
 *   <li>实体侧：{@code CraftPlayer#trackAndShowEntity} / {@code #untrackAndHideEntity} 会经
 *       {@code ChunkMap.TrackedEntity#updatePlayer}/{@code removePlayer} 改实体追踪集合，并让
 *       {@code TrackedEntity#updatePlayer} 在实体 region 上按 {@code canSee} 决定是否把玩家加入
 *       {@code seenBy}——Folia 的实体追踪本身就是「实体 region 负责」的。</li>
 *   <li>玩家侧在实体 region 上只剩「读 {@code ServerPlayer} 字段 + 发包含实体状态的包」：
 *       {@code CraftPlayer#getHandle()} 是裸字段读、{@code ServerGamePacketListenerImpl#send}
 *       没有任何 tick thread 门禁，{@code ChunkMap$TrackedEntity#updatePlayer} 只有
 *       {@code AsyncCatcher.catchOp}（要求「是某个 tick 线程」，不要求拥有玩家）。
 *       因此该 lane 合法，且这正是 Folia 自己发实体包的位置。</li>
 * </ul>
 *
 * <p>隐藏/显示状态按「玩家 × 实体」记账（{@code CraftPlayer#invertedVisibilityEntities}），
 * 由 {@code canSee} 在追踪阶段生效，所以实体 region 的调用不但合法，而且对跨 region 的玩家同样有效。
 * 该 lane 里**只允许**做这一件事：不要在这里调用其它玩家 API（那会变成跨玩家实体访问）。
 */
public final class PlayerOutputDispatcher {
    /**
     * 跨 region 跳过实体可见性操作时的限频窗口。
     *
     * <p>【本轮之后这条限频只服务异常路径】实体可见性已改投实体 owner region（见类注释），
     * 原先「按玩家 × 每 2 秒」刷屏的跨 region 跳过分支不再存在，因此正常情况下一次都不会打。
     * 保留限频机制是为了剩下三类真正异常的结果：实体在投递后换了 region、投递被调度拒绝、
     * 玩家可见性 API 抛错——它们都可能被同一实体的多次重算反复命中。
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
     * 实体可见性操作的 owner region 投递接缝。
     *
     * <p>生产实现见 {@link #schedulingEntityRegionLane(DoudizhuPlugin)}；测试注入替身即可观察
     * 「投递到哪条 lane」「投递结果」，不必伪造 Folia 内核。
     */
    private final EntityRegionLane entityRegionLane;

    /**
     * 每个实体上一次真正打日志的时间戳（毫秒）。限频的唯一键是实体 UUID，不是玩家。
     *
     * <p>【为什么必须有时间戳】：限频口径写的是「同实体 30 秒最多一条」，但只用 {@code Set} 记账会把
     * 它退化成「每个实体一辈子只打一条」——{@code PhysicalTableManager} 每 2 秒重算一次且永不停止，于是
     * 某个实体第一次被跳过之后，整轮服务端运行都不会再报它。窗口必须按真实时间判定，光有常量是不够的。
     */
    private final Map<UUID, Long> lastEntityLaneSkipLogMillis = new ConcurrentHashMap<>();

    /**
     * 每个实体在本窗口内被限频静默丢弃的跳过次数；真正打日志时随附并清零，避免限频变成「丢信息」。
     *
     * <p>按实体记账而不是全局：全局计数会让 A 实体的丢弃次数出现在 B 实体的日志里，反而误导排查。
     */
    private final Map<UUID, AtomicLong> suppressedEntityLaneSkips = new ConcurrentHashMap<>();

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
        this.entityRegionLane = schedulingEntityRegionLane(plugin);
    }

    /**
     * 生产接缝：先在调用线程按 UUID 拿到实体，再经 {@code MuzScheduler.runEntity} 投到实体所属 region。
     *
     * <p>【为什么这一段可以在调用线程做】{@code Bukkit.getEntity(UUID)} 只是实体表查表
     * （{@code ServerLevel#getEntity(UUID)} → 实体 getter），不读实体状态，因此不触发
     * {@code CraftEntity#getHandle} 的 owner region 门禁；这正是本方法能安全「先拿实体再换 lane」的前提。
     *
     * <p>【为什么用 runEntity 而不是 runRegion(location)】{@code EntityScheduler} 的归属语义就是
     * 「拥有该实体的 region」，内部只用 {@code CraftEntity#getHandleRaw()} 与
     * {@code TickThread#isTickThreadFor(entity)} 判定（都是裸字段读 / 布尔查询，不触发门禁），
     * 并且实体在回调前被移除时会走 retired，不会把任务留给已经不存在的实体。
     */
    private static EntityRegionLane schedulingEntityRegionLane(DoudizhuPlugin plugin) {
        return (entityId, task) -> {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity == null) {
                return EntityRegionLane.Submission.ENTITY_ABSENT;
            }
            // 延迟解析：闭包不捕获调用点的实体状态，执行时在实体 region 内重新按 UUID 解析当前实例。
            MuzScheduler.TaskHandle handle = plugin.scheduler().runEntity(entity, () -> {
                Entity live = Bukkit.getEntity(entityId);
                if (live != null) {
                    task.accept(live);
                }
            });
            // 关闭期门面会返回已取消句柄（不抛异常），据此如实报告「没排进去」，不再静默。
            return handle != null && handle.isCancelled()
                ? EntityRegionLane.Submission.REJECTED
                : EntityRegionLane.Submission.SUBMITTED;
        };
    }

    /** 测试构造：注入玩家任务注册表，避免测试依赖 Bukkit 线程实现。 */
    public PlayerOutputDispatcher(PlayerTaskRegistry tasks) {
        this(tasks, System::currentTimeMillis);
    }

    /** 测试构造：额外注入毫秒时钟，让限频窗口可被测试推进而不是真实等待 30 秒。 */
    public PlayerOutputDispatcher(PlayerTaskRegistry tasks, LongSupplier millisClock) {
        this(tasks, millisClock, EntityRegionLane.direct());
    }

    /** 测试构造：额外注入实体 owner region 投递接缝（默认 {@link EntityRegionLane#direct()}）。 */
    public PlayerOutputDispatcher(
        PlayerTaskRegistry tasks,
        LongSupplier millisClock,
        EntityRegionLane entityRegionLane
    ) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.millisClock = Objects.requireNonNull(millisClock, "millisClock");
        this.entityRegionLane = Objects.requireNonNull(entityRegionLane, "entityRegionLane");
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
     * 在**实体所属 region** 按实体 UUID 显示实体；执行时在该 lane 内重新解析当前 live Entity。
     *
     * <p>调用线程既不读实体状态、也不持有桌 owner 的旧实体对象；排队闭包只捕获 UUID。
     * 为什么不是 player lane 见类注释（跨 region 时 player lane 里读实体会被
     * {@code CraftEntity#getHandle} 的 owner region 门禁直接拒绝）。
     */
    public void showEntity(UUID viewerId, Plugin plugin, UUID entityId) {
        runEntityVisibility(
            plugin, "showEntity", viewerId, entityId, (player, entity) -> player.showEntity(plugin, entity));
    }

    /** 兼容旧 Entity 重载，但只在调用点立即转换为 UUID。 */
    public void showEntity(UUID viewerId, Plugin plugin, Entity entity) {
        if (entity == null) {
            return;
        }
        showEntity(viewerId, plugin, entity.getUniqueId());
    }

    /**
     * 在**实体所属 region** 按实体 UUID 隐藏实体；执行时在该 lane 内重新解析当前 live Entity。
     *
     * <p>隐藏状态按「玩家 × 实体」记在 {@code CraftPlayer#invertedVisibilityEntities}，
     * 由实体追踪在 {@code canSee} 里生效，因此对跨 region 的玩家同样有效。
     */
    public void hideEntity(UUID viewerId, Plugin plugin, UUID entityId) {
        runEntityVisibility(
            plugin, "hideEntity", viewerId, entityId, (player, entity) -> player.hideEntity(plugin, entity));
    }

    /** 兼容旧 Entity 重载，但只在调用点立即转换为 UUID。 */
    public void hideEntity(UUID viewerId, Plugin plugin, Entity entity) {
        if (entity == null) {
            return;
        }
        hideEntity(viewerId, plugin, entity.getUniqueId());
    }

    /**
     * 实体可见性的统一投递：路由到实体所属 region，再在那里解析玩家并调用玩家 API。
     *
     * <p>【为什么调用线程不解析 Player】调用线程可能是桌 owner region、global lane 或异步 lane，
     * 都不是玩家自己的 lane；沿用本类既有约定——调用线程只登记，玩家实例留到执行时按 UUID 重新解析，
     * 这样迟到的回调也不会作用在已经离线的旧实例上。
     */
    private void runEntityVisibility(
        Plugin plugin,
        String operation,
        UUID viewerId,
        UUID entityId,
        BiConsumer<Player, Entity> action
    ) {
        if (plugin == null || entityId == null) {
            return;
        }
        EntityRegionLane.Submission submission;
        try {
            submission = entityRegionLane.runOnEntityRegion(
                entityId,
                entity -> applyVisibilityInEntityRegion(plugin, operation, viewerId, entityId, entity, action)
            );
        } catch (RuntimeException | Error failure) {
            reportEntityLaneSkip(plugin, operation, viewerId, entityId, "投递实体 owner region 失败: " + failure);
            return;
        }
        if (submission == EntityRegionLane.Submission.REJECTED) {
            reportEntityLaneSkip(plugin, operation, viewerId, entityId, "插件正在关闭或调度已关闭，投递被拒绝");
        }
        // ENTITY_ABSENT 与 SUBMITTED 都不留痕：前者是拆除/重建竞态下的正常结果（调用方下一轮会重算），
        // 后者已经把动作交给实体 lane。
    }

    /**
     * 实体所属 region 内的实际执行体。
     *
     * <p>只做三件事：复核归属、按 UUID 重新解析当前在线玩家、调用玩家可见性 API。
     * 【刻意只做这一件事】该 lane 拥有的是**实体**：玩家侧唯一合法动作是「发一条携带实体状态的包」。
     * 在这里读玩家实体状态或调用其它玩家 API 就会变成新的跨 region 访问，是明确禁止的。
     */
    private void applyVisibilityInEntityRegion(
        Plugin plugin,
        String operation,
        UUID viewerId,
        UUID entityId,
        Entity entity,
        BiConsumer<Player, Entity> action
    ) {
        // 实体在投递后被移除：没有可见性状态需要维护。正常结果，不留痕。
        if (!entity.isValid()) {
            return;
        }
        // 提交与执行之间实体换了 region：让位给调用方的下一轮重算，绝不硬改（会读到不属于本 lane 的状态）。
        if (!Bukkit.isOwnedByCurrentRegion(entity)) {
            reportEntityLaneSkip(plugin, operation, viewerId, entityId, "实体已不在投递时的 region");
            return;
        }
        Player viewer = currentPlayer(viewerId);
        if (viewer == null) {
            // 玩家已离线：没有客户端需要更新。正常结果，不留痕。
            return;
        }
        try {
            action.accept(viewer, entity);
        } catch (RuntimeException | Error failure) {
            // 可见性失败不能把异常抛回调用方（调用方是 owner tick / refresh，抛出去会打断整轮刷新）。
            reportEntityLaneSkip(plugin, operation, viewerId, entityId, "玩家可见性 API 抛错: " + failure);
        }
    }

    /**
     * 记录一次「未能执行」的实体可见性操作，按实体 UUID 限频。
     *
     * <p>【为什么可以放弃，但不能无声】这些分支在实服的表现是「某个玩家的桌边动态/座位名没按预期
     * 显示或隐藏」，而服务端毫无痕迹（与本项目此前修过的「道具投掷卡住且无日志」是同一类可排查性缺陷）。
     * 与改造前的区别是：跨 region 本身**不再**出现在这里——那类调用现在会投递到实体 owner region 正常执行，
     * 所以留痕只覆盖真正异常的三类（实体中途换 region、投递被关闭期拒绝、玩家可见性 API 抛错），
     * 不再按「玩家数 × 每 2 秒」刷屏。
     *
     * <p>【为什么不写成异常】这些情况都是 Folia 语义下的正常结果：调用方
     * （{@code PhysicalTableManager} 的按人可见性重算）会在下一次 refresh / owner tick 重新计算并重试，
     * 不需要把整个流程判失败。因此只留痕，不改控制流。
     *
     * <p>【限频口径】同实体 30 秒最多一条（见 {@link #SKIP_LOG_INTERVAL_MILLIS}），并把被限频窗口丢掉的
     * 次数随下一次日志一起报出；换实体只换日志的「首次」，不重置其它实体的窗口。
     * {@code viewerId} 只记录在日志文本里参与排查，不作为限频键，否则按人重算会按玩家数放大条数。
     */
    private void reportEntityLaneSkip(
        Plugin plugin,
        String operation,
        UUID viewerId,
        UUID entityId,
        String reason
    ) {
        // 计数在限频判定之前累加：本次调用无论最终是否打日志都会被算进「被折叠掉的次数」，
        // 打日志时再整体取走并清零。所以日志里报出的是「本窗口内除正在打的这一条之外被压掉的次数」，
        // 必须在取走后减掉本次调用自己，否则第一次日志会把自己算成「被静默丢弃」。
        AtomicLong suppressedForEntity = suppressedEntityLaneSkips.computeIfAbsent(
            entityId, ignored -> new AtomicLong()
        );
        suppressedForEntity.incrementAndGet();
        long now = millisClock.getAsLong();
        Long lastLogged = lastEntityLaneSkipLogMillis.putIfAbsent(entityId, now);
        if (lastLogged != null) {
            // 距上次打日志还不到限频窗口：本次静默丢弃，但计数留给下一次真正打日志时随附报出。
            if (now - lastLogged < SKIP_LOG_INTERVAL_MILLIS) {
                return;
            }
            // 窗口已过：抢占本次打日志的权利，抢不到说明另一个线程刚打过了。
            if (!lastEntityLaneSkipLogMillis.replace(entityId, lastLogged, now)) {
                return;
            }
        }
        // 取走并清零本窗口累计次数，再扣掉本次调用（它自己即将被打印，不算「被丢弃」）。
        long discarded = Math.max(0L, suppressedForEntity.getAndSet(0L) - 1L);
        plugin.getLogger().warning(
            "实体可见性操作未能执行 " + operation + "（" + reason + "，按实体 " + SKIP_LOG_INTERVAL_MILLIS / 1000
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

    /**
     * 把实体可见性操作投递到「实体所属 owner region」的接缝。
     *
     * <p>【为什么必须有这个接缝，而不是复用 runPlayer】实体可见性必须落在实体自己的 region（见类注释）：
     * Paper 的两个 API 都会读实体状态，而 {@code CraftEntity#getHandle()} 有 owner region 门禁。
     * 该接缝同时是测试接缝——单测注入替身后可以观察「投递到哪条 lane」「投递结果」，
     * 不必伪造 Folia 内核。
     */
    @FunctionalInterface
    public interface EntityRegionLane {
        /** 投递结果，决定调用方是否需要留痕。 */
        enum Submission {
            /** 已排入实体所属 region，回调会在那里执行。 */
            SUBMITTED,
            /** 实体已不存在：没有任何实体可见性状态需要维护，也不是故障。 */
            ENTITY_ABSENT,
            /** 实体存在但排不进去（插件正在关闭 / 调度已关闭）。 */
            REJECTED
        }

        /**
         * 在 {@code entityId} 所属 region 上执行可见性操作；{@code task} 收到的是该 lane 内重新解析的实体，
         * 因此闭包不要捕获调用点的实体状态。
         */
        Submission runOnEntityRegion(UUID entityId, Consumer<Entity> task);

        /**
         * 直接执行、不做任何调度的实现。
         *
         * <p>只用于单测与「已经确认在实体本 region 上」的场景；生产装配必须用
         * {@link #schedulingEntityRegionLane(DoudizhuPlugin)}，否则会在错误的 lane 上读实体状态。
         */
        static EntityRegionLane direct() {
            return (entityId, task) -> {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity == null) {
                    return Submission.ENTITY_ABSENT;
                }
                task.accept(entity);
                return Submission.SUBMITTED;
            };
        }
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
