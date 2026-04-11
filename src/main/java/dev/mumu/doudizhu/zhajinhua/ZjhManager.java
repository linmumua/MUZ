package dev.mumu.doudizhu.zhajinhua;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.room.TableLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

public final class ZjhManager {
    private final DoudizhuPlugin plugin;
    private final Map<String, ZjhTable> tables = new LinkedHashMap<>();
    private final Map<UUID, String> playerToTable = new LinkedHashMap<>();

    public ZjhManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public ZjhTable createTable(String rawName, int maxPlayers) {
        return createTable(rawName, maxPlayers, TableLevel.FUN);
    }

    public ZjhTable createTable(String rawName, int maxPlayers, TableLevel level) {
        String key = normalize(rawName);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("德州扑克牌桌名称不能为空。");
        }
        if (tables.containsKey(key)) {
            throw new IllegalArgumentException("已存在同名德州扑克牌桌。");
        }
        ZjhTable table = new ZjhTable(plugin, this, rawName, maxPlayers, level == null ? TableLevel.FUN : level);
        tables.put(key, table);
        return table;
    }

    public ZjhTable joinTable(Player player, String rawName) {
        if (getTableOf(player) != null) {
            throw new IllegalArgumentException("你已经在一个德州扑克牌桌里了。");
        }
        ZjhTable table = getTable(rawName);
        if (table == null) {
            throw new IllegalArgumentException("找不到这个德州扑克牌桌。");
        }
        table.addPlayer(player);
        playerToTable.put(player.getUniqueId(), normalize(rawName));
        return table;
    }

    public void leaveTable(Player player) {
        ZjhTable table = getTableOf(player);
        if (table == null) {
            throw new IllegalArgumentException("你当前不在任何德州扑克牌桌里。");
        }
        table.removePlayer(player, player.getName() + " 离开了德州扑克牌桌。");
        playerToTable.remove(player.getUniqueId());
    }

    public void removePlayerSilently(Player player, String reason) {
        ZjhTable table = getTableOf(player);
        if (table == null) {
            return;
        }
        table.removePlayer(player, reason);
        playerToTable.remove(player.getUniqueId());
    }

    public ZjhTable getTable(String rawName) {
        return tables.get(normalize(rawName));
    }

    public ZjhTable getTableOf(Player player) {
        String key = playerToTable.get(player.getUniqueId());
        return key == null ? null : tables.get(key);
    }

    public Collection<ZjhTable> getTables() {
        return new ArrayList<>(tables.values());
    }

    public void unregisterPlayer(UUID playerId) {
        playerToTable.remove(playerId);
    }

    public void unregisterTable(String tableName) {
        tables.remove(normalize(tableName));
    }

    public void tick() {
        for (ZjhTable table : tables.values()) {
            table.tickActionBar();
        }
    }

    public void shutdown() {
        for (ZjhTable table : tables.values()) {
            table.shutdown();
        }
        tables.clear();
        playerToTable.clear();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}

