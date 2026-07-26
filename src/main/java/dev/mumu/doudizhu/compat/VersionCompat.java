package dev.mumu.doudizhu.compat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import java.util.function.Consumer;

/**
 * 版本兼容工具类。
 * 当前发布同时面向 Paper 1.21.11、26.1.2 与 26.2；
 * setItemModel 与带 Consumer 的 spawn 在这些目标 API 中均可直接调用。
 */
public final class VersionCompat {

    private VersionCompat() {}

    /**
     * 检查当前服务器是否支持 setItemModel API
     */
    public static boolean supportsItemModel() {
        return true;
    }

    /**
     * 检查当前服务器是否支持带 Consumer 的 spawn API
     */
    public static boolean supportsSpawnWithConsumer() {
        return true;
    }

    /**
     * 设置 ItemMeta 的 item model
     */
    public static void setItemModel(ItemMeta meta, NamespacedKey model) {
        if (meta == null || model == null) return;
        meta.setItemModel(model);
    }

    /**
     * 创建玩家头颅组件
     */
    public static Component createPlayerHeadComponent(String playerName) {
        return Component.text(playerName).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 带 Consumer 的 spawn 方法
     */
    public static <T extends Entity> T spawnEntity(World world, Location location, Class<T> entityClass, Consumer<T> consumer) {
        return world.spawn(location, entityClass, consumer);
    }
}
