package linmumua.doudizhu.game;

import linmumua.doudizhu.DoudizhuPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 普通 ActionBar 叠加服务。
 *
 * <p>这里只维护普通消息及其生命周期，不读取资源包、CraftEngine 或三道具字形。
 * HotbarHudService 的兼容入口可以委托到这里；本服务本身始终只发送普通 ActionBar，
 * 保证普通提示不依赖自定义物品栏 HUD 是否启用。
 */
public final class ActionBarOverlayService {
    private final DoudizhuPlugin plugin;
    private final Map<UUID, OverlayEntry> overlays = new HashMap<>();

    public ActionBarOverlayService(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    /** 显示指定时长的普通 ActionBar，并记录给 Hotbar 兼容合成层读取。 */
    public void showOverlay(Collection<UUID> players, Component message, int durationTicks) {
        storeOverlay(players, message, durationTicks);
        sendActionBar(players, message);
    }

    /** 仅记录叠加消息，供 HotbarHudService 后续与三道具字形合成。 */
    public void storeOverlay(Collection<UUID> players, Component message, int durationTicks) {
        Component plain = normalize(message);
        long expireAt = System.currentTimeMillis() + Math.max(0L, (long) durationTicks * 50L);
        for (UUID id : players) {
            if (onlinePlayer(id) == null) {
                overlays.remove(id);
                continue;
            }
            overlays.put(id, new OverlayEntry(plain, expireAt));
        }
    }

    public void storeOverlay(UUID playerId, Component message, int durationTicks) {
        storeOverlay(List.of(playerId), message, durationTicks);
    }

    public void showOverlay(Collection<UUID> players, Component message) {
        showOverlay(players, message, 60);
    }

    public void showOverlay(UUID playerId, Component message) {
        showOverlay(List.of(playerId), message, 60);
    }

    public void showOverlay(UUID playerId, Component message, int durationTicks) {
        showOverlay(List.of(playerId), message, durationTicks);
    }

    /** 直接发送一次普通 ActionBar，不加入叠加队列。 */
    public void sendActionBar(UUID playerId, Component message) {
        Player player = onlinePlayer(playerId);
        if (player != null) {
            player.sendActionBar(normalize(message));
        }
    }

    /** 直接向多个在线玩家发送一次普通 ActionBar，不加入叠加队列。 */
    public void sendActionBar(Collection<UUID> playerIds, Component message) {
        Component plain = normalize(message);
        for (UUID playerId : playerIds) {
            Player player = onlinePlayer(playerId);
            if (player != null) {
                player.sendActionBar(plain);
            }
        }
    }

    /** 返回当前仍有效的普通叠加消息，供兼容的 Hotbar 合成路径读取。 */
    public Component currentOverlay(UUID playerId) {
        OverlayEntry entry = overlays.get(playerId);
        if (entry == null) {
            return null;
        }
        if (entry.expireAt() <= System.currentTimeMillis()) {
            overlays.remove(playerId);
            return null;
        }
        return entry.message();
    }

    /** 主动清除某个玩家的普通叠加消息并清掉客户端旧 ActionBar。 */
    public void clearOverlay(UUID playerId) {
        overlays.remove(playerId);
        Player player = onlinePlayer(playerId);
        if (player != null) {
            player.sendActionBar(Component.empty());
        }
    }

    /** 清除整桌普通叠加消息。 */
    public void clearTable(GameTable table) {
        if (table == null) {
            return;
        }
        for (UUID playerId : table.getSeats()) {
            clearOverlay(playerId);
        }
    }

    /** 停止使用时释放所有普通叠加状态。 */
    public void stop() {
        overlays.clear();
    }

    private Player onlinePlayer(UUID playerId) {
        Player player = playerId == null ? null : Bukkit.getPlayer(playerId);
        return player != null && player.isOnline() ? player : null;
    }

    private static Component normalize(Component message) {
        return (message == null ? Component.empty() : message)
            .decoration(TextDecoration.ITALIC, false);
    }

    private record OverlayEntry(Component message, long expireAt) {
    }
}
