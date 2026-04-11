package dev.codex.doudizhu.game;

import dev.codex.doudizhu.DoudizhuPlugin;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

public final class TableManager {
    private final DoudizhuPlugin plugin;
    private final Map<String, GameTable> tables = new LinkedHashMap<>();
    private final Map<UUID, String> playerToTable = new LinkedHashMap<>();

    public TableManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public GameTable createTable(String rawName) {
        String key = normalizeKey(rawName);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("牌桌名称不能为空。");
        }
        if (tables.containsKey(key)) {
            throw new IllegalArgumentException("已经存在同名牌桌。");
        }
        GameTable table = new GameTable(plugin, this, rawName);
        tables.put(key, table);
        return table;
    }

    public GameTable joinTable(Player player, String rawName) {
        if (getTableOf(player) != null) {
            throw new IllegalArgumentException("你已经在一个牌桌里了。");
        }
        GameTable table = getTable(rawName);
        if (table == null) {
            throw new IllegalArgumentException("找不到这个牌桌。");
        }
        table.addPlayer(player);
        playerToTable.put(player.getUniqueId(), normalizeKey(rawName));
        return table;
    }

    public void leaveTable(Player player) {
        GameTable table = getTableOf(player);
        if (table == null) {
            throw new IllegalArgumentException("你当前不在任何牌桌里。");
        }
        table.removePlayer(player, player.getName() + " 离开了牌桌。");
        playerToTable.remove(player.getUniqueId());
        cleanupIfEmpty(table);
    }

    public void removePlayerSilently(Player player, String reason) {
        GameTable table = getTableOf(player);
        if (table == null) {
            return;
        }
        table.removePlayer(player, reason);
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

    public Collection<GameTable> getTables() {
        return new ArrayList<>(tables.values());
    }

    public void unregisterPlayer(UUID playerId) {
        playerToTable.remove(playerId);
    }

    public void unregisterTable(String tableName) {
        tables.remove(normalizeKey(tableName));
    }

    public void shutdown() {
        for (GameTable table : new ArrayList<>(tables.values())) {
            table.shutdown();
        }
        tables.clear();
        playerToTable.clear();
    }

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

    private String normalizeKey(String rawName) {
        if (rawName == null) {
            return null;
        }
        return rawName.trim().toLowerCase(Locale.ROOT);
    }
}
