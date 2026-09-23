package linmumua.doudizhu.game;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
    private final PlayerTaskRegistry tasks;

    /**
     * 生产构造：先在 global lane 按 UUID 查找调度器，再把实际输出排入 player lane。
     * 调用线程不会解析 Player；玩家执行阶段仍会重新按 UUID 解析当前在线实例。
     */
    public PlayerOutputDispatcher(DoudizhuPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
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
        this.tasks = Objects.requireNonNull(tasks, "tasks");
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
            }
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
            }
        });
    }

    /** 兼容旧 Entity 重载，但只在调用点立即转换为 UUID。 */
    public void hideEntity(UUID viewerId, Plugin plugin, Entity entity) {
        if (entity == null) {
            return;
        }
        hideEntity(viewerId, plugin, entity.getUniqueId());
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
