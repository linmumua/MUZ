package linmumua.doudizhu.game;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.room.TableLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import linmumua.doudizhu.scheduler.MuzScheduler;
import org.bukkit.entity.Player;

public final class TableManager {
    private final DoudizhuPlugin plugin;
    private final Map<String, GameTable> tables = new LinkedHashMap<>();
    private final Map<UUID, String> playerToTable = new LinkedHashMap<>();
    /** 每张桌的周期任务描述与当前 global/region 绑定。 */
    private final TablePeriodicTaskRegistry periodicTasks;
    /** 牌桌对局内部的周期任务；与 ActionBar 周期分开，允许独立重绑定。 */
    private final TablePeriodicTaskRegistry ownerPeriodicTasks;
    /** 已放置牌桌的世界实体 owner tick；未放置桌不注册此类任务。 */
    private final TablePeriodicTaskRegistry worldPeriodicTasks;
    /** 每桌当前 owner 锚点；缺少条目表示 global，值只保存私有 Location 副本。 */
    private final Map<String, org.bukkit.Location> tableOwnerAnchors = new LinkedHashMap<>();
    /* 为同一张桌生成独立的内部周期 owner key。 */
    private long ownerPeriodicTaskSequence;

    public TableManager(DoudizhuPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.periodicTasks = new TablePeriodicTaskRegistry(plugin.scheduler());
        this.ownerPeriodicTasks = new TablePeriodicTaskRegistry(plugin.scheduler());
        this.worldPeriodicTasks = new TablePeriodicTaskRegistry(plugin.scheduler());
    }

    public GameTable createTable(String rawName) {
        return createTable(rawName, TableLevel.FUN);
    }

    public GameTable createTable(String rawName, TableLevel level) {
        String key = normalizeKey(rawName);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("牌桌名称不能为空。");
        }
        if (tables.containsKey(key)) {
            throw new IllegalArgumentException("已经存在同名牌桌。");
        }
        // 新逻辑桌先固定走 global；只有放置/恢复成功后的 rebind 才能切换到 region。
        tableOwnerAnchors.remove(key);
        GameTable table = new GameTable(plugin, this, rawName, level == null ? TableLevel.FUN : level);
        tables.put(key, table);
        try {
            registerTablePeriodicTask(table, 1L, 10L, table::tickActionBar);
            return table;
        } catch (RuntimeException | Error failure) {
            tables.remove(key, table);
            tableOwnerAnchors.remove(key);
            table.shutdown();
            throw failure;
        }
    }

    public GameTable joinTable(Player player, String rawName) {
        if (getTableOf(player) != null) {
            throw new IllegalArgumentException("你已经在一个牌桌里了。");
        }
        GameTable table = getTable(rawName);
        if (table == null) {
            throw new IllegalArgumentException("找不到这个牌桌。");
        }
        table.addPlayer(player.getUniqueId(), player.getName());
        playerToTable.put(player.getUniqueId(), normalizeKey(rawName));
        return table;
    }

    public void leaveTable(Player player) {
        GameTable table = getTableOf(player);
        if (table == null) {
            throw new IllegalArgumentException("你当前不在任何牌桌里。");
        }
        table.removePlayer(player.getUniqueId(), player.getName() + " 离开了牌桌。");
        playerToTable.remove(player.getUniqueId());
        cleanupIfEmpty(table);
    }

    public void removePlayerSilently(Player player, String reason) {
        GameTable table = getTableOf(player);
        if (table == null) {
            return;
        }
        table.removePlayer(player.getUniqueId(), reason);
        playerToTable.remove(player.getUniqueId());
        cleanupIfEmpty(table);
    }

    public GameTable getTable(String rawName) {
        return tables.get(normalizeKey(rawName));
    }

    public GameTable getTableOf(Player player) {
        String key = playerToTable.get(player.getUniqueId());
        return key == null ? null : tables.get(key);
    }

    /**
     * 按 UUID 查玩家所在牌桌；不需要 Player 实体的路径用这个，避免在 owner lane 上取玩家对象。
     *
     * <p>与 {@link #getTableOf(Player)} 同源（同一份 {@code playerToTable} 映射），只是省略了
     * 从 Player 取 UUID 那一步。离线玩家不会被登记，所以查不到时返回 null，与在线查询语义一致。
     */
    public GameTable getTableOf(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        String key = playerToTable.get(playerId);
        return key == null ? null : tables.get(key);
    }

    public Collection<GameTable> getTables() {
        return new ArrayList<>(tables.values());
    }

    public void unregisterPlayer(UUID playerId) {
        playerToTable.remove(playerId);
    }

    public void unregisterTable(String tableName) {
        String key = normalizeKey(tableName);
        if (key == null) {
            return;
        }
        GameTable table = tables.remove(key);
        tableOwnerAnchors.remove(key);
        if (table != null) {
            periodicTasks.cancel(key, table);
            ownerPeriodicTasks.cancelOwner(table);
            worldPeriodicTasks.cancelOwner(table);
        }
    }

    /** 为指定牌桌 owner 注册周期任务；已放置牌桌绑定到锚点 region。 */
    public MuzScheduler.TaskHandle registerTablePeriodicTask(
        GameTable table,
        long delayTicks,
        long periodTicks,
        Runnable task
    ) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(task, "task");
        String ownerKey = normalizeKey(table.getName());
        if (ownerKey == null || tables.get(ownerKey) != table) {
            throw new IllegalArgumentException("牌桌尚未注册: " + table.getName());
        }
        org.bukkit.Location anchor = ownerAnchor(ownerKey);
        return periodicTasks.register(
            ownerKey,
            table,
            new TablePeriodicTaskRegistry.PeriodicDescription(delayTicks, periodTicks),
            anchor,
            () -> {
                if (tables.get(ownerKey) == table) {
                    task.run();
                }
            }
        );
    }

    /**
     * 为已放置牌桌注册世界实体 owner tick。
     *
     * <p>未放置的纯逻辑桌不注册世界任务；桌子完成放置或重建后由
     * {@code PhysicalTableManager} 通过 {@link #rebindTablePeriodicTask(GameTable, org.bukkit.Location)}
     * 绑定到锚点所在 region。
     */
    private boolean rebindWorldPeriodicTask(GameTable table, org.bukkit.Location anchor) {
        Objects.requireNonNull(table, "table");
        String ownerKey = normalizeKey(table.getName());
        if (ownerKey == null || tables.get(ownerKey) != table) {
            return false;
        }
        if (anchor == null) {
            worldPeriodicTasks.cancelOwner(table);
            return true;
        }
        if (worldPeriodicTasks.rebind(ownerKey, table, anchor)) {
            return true;
        }
        worldPeriodicTasks.register(
            ownerKey,
            table,
            new TablePeriodicTaskRegistry.PeriodicDescription(1L, 1L),
            anchor,
            () -> {
                if (tables.get(ownerKey) == table) {
                    plugin.getPhysicalTableManager().tickTable(table);
                }
            }
        );
        return true;
    }

    /**
     * 为牌桌 owner 投递一次性回调；已放置桌走锚点 region，未放置桌走 global。
     * 牌桌注销后，注册表会使迟到回调失效。
     */
    public MuzScheduler.TaskHandle runTableLater(GameTable table, long delayTicks, Runnable task) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(task, "task");
        String ownerKey = normalizeKey(table.getName());
        if (ownerKey == null || tables.get(ownerKey) != table) {
            throw new IllegalArgumentException("牌桌尚未注册: " + table.getName());
        }
        org.bukkit.Location anchor = ownerAnchor(ownerKey);
        return periodicTasks.scheduleLater(
            ownerKey,
            table,
            anchor,
            delayTicks,
            () -> {
                if (tables.get(ownerKey) == table) {
                    task.run();
                }
            }
        );
    }

    /** 为牌桌 owner 投递立即回调，路由规则与 {@link #runTableLater} 相同。 */
    public MuzScheduler.TaskHandle runTableNow(GameTable table, Runnable task) {
        return runTableLater(table, 0L, task);
    }

    /**
     * 为牌桌 owner 投递周期回调；已放置桌走锚点 region，未放置桌走 global。
     * 牌桌实例注销或替换后，迟到周期回调会自行取消。
     */
    public MuzScheduler.TaskHandle runTableTimer(
        GameTable table,
        long delayTicks,
        long periodTicks,
        Consumer<MuzScheduler.TaskHandle> task
    ) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(task, "task");
        String ownerKey = normalizeKey(table.getName());
        if (ownerKey == null || tables.get(ownerKey) != table) {
            throw new IllegalArgumentException("牌桌尚未注册: " + table.getName());
        }
        org.bukkit.Location anchor = ownerAnchor(ownerKey);
        String taskKey = ownerKey + "#owner-" + (++ownerPeriodicTaskSequence);
        MuzScheduler.TaskHandle[] handleRef = new MuzScheduler.TaskHandle[1];
        MuzScheduler.TaskHandle stable = ownerPeriodicTasks.register(
            taskKey,
            table,
            new TablePeriodicTaskRegistry.PeriodicDescription(delayTicks, periodTicks),
            anchor,
            () -> {
                if (tables.get(ownerKey) != table) {
                    if (handleRef[0] != null) {
                        handleRef[0].cancel();
                    }
                    return;
                }
                task.accept(handleRef[0]);
            }
        );
        handleRef[0] = stable;
        return stable;
    }

    /** 牌桌放置、恢复、重建或锚点移动后重新选择 global/region lane。 */
    public boolean rebindTablePeriodicTask(GameTable table, org.bukkit.Location anchor) {
        if (table == null) {
            return false;
        }
        String ownerKey = normalizeKey(table.getName());
        if (ownerKey == null || tables.get(ownerKey) != table) {
            return false;
        }
        org.bukkit.Location safeAnchor = cloneAnchor(anchor);
        boolean rebound = periodicTasks.rebind(ownerKey, table, cloneAnchor(safeAnchor));
        ownerPeriodicTasks.rebindOwner(table, cloneAnchor(safeAnchor));
        rebindWorldPeriodicTask(table, cloneAnchor(safeAnchor));
        // 只有全部 owner 周期任务完成重绑定后才提交新的 lane 状态；存储副本，
        // 后续 PhysicalTableManager 或调用方修改原 Location 不会改变一次性任务路由。
        setOwnerAnchor(ownerKey, safeAnchor);
        return rebound;
    }

    /** 取消指定牌桌 owner 的周期任务，重复调用安全。 */
    public void cancelOwnerPeriodicTasks(String tableName) {
        String key = normalizeKey(tableName);
        if (key == null) {
            return;
        }
        GameTable table = tables.get(key);
        if (table != null) {
            periodicTasks.cancel(key, table);
            ownerPeriodicTasks.cancelOwner(table);
            worldPeriodicTasks.cancelOwner(table);
        }
    }

    /** 牌桌暂时未放置；保留逻辑桌并切回 global lane。 */
    public boolean markTableUnplaced(String tableName) {
        String key = normalizeKey(tableName);
        GameTable table = key == null ? null : tables.get(key);
        if (table == null) {
            return false;
        }
        boolean rebound = periodicTasks.rebind(key, table, null);
        ownerPeriodicTasks.rebindOwner(table, null);
        rebindWorldPeriodicTask(table, null);
        // unload 后清除私有锚点，后续一次性任务必须走 global，而不是重新读取旧世界状态。
        tableOwnerAnchors.remove(key);
        return rebound;
    }

    public void shutdown() {
        for (GameTable table : new ArrayList<>(tables.values())) {
            cancelOwnerPeriodicTasks(table.getName());
            table.shutdown();
        }
        periodicTasks.cancelAll();
        ownerPeriodicTasks.cancelAll();
        worldPeriodicTasks.cancelAll();
        tableOwnerAnchors.clear();
        tables.clear();
        playerToTable.clear();
    }

    /** 兼容旧的手动 tick 入口；正式装配由每桌 owner 周期任务负责。 */
    public void tick() {
        for (GameTable table : tables.values()) {
            table.tickActionBar();
        }
    }

    private void cleanupIfEmpty(GameTable table) {
        if (table.isEmpty() && !plugin.getPhysicalTableManager().isPlaced(table.getName())) {
            unregisterTable(table.getName());
        }
    }

    private org.bukkit.Location ownerAnchor(String ownerKey) {
        return cloneAnchor(tableOwnerAnchors.get(ownerKey));
    }

    private void setOwnerAnchor(String ownerKey, org.bukkit.Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            tableOwnerAnchors.remove(ownerKey);
            return;
        }
        tableOwnerAnchors.put(ownerKey, anchor.clone());
    }

    private static org.bukkit.Location cloneAnchor(org.bukkit.Location anchor) {
        return anchor == null ? null : anchor.clone();
    }

    private String normalizeKey(String rawName) {
        if (rawName == null) {
            return null;
        }
        return rawName.trim().toLowerCase(Locale.ROOT);
    }
}

