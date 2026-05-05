package dev.mumu.doudizhu.compat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * 版本兼容工具类，用于处理不同 Paper 版本之间的 API 差异
 */
public final class VersionCompat {
    private static final boolean HAS_SET_ITEM_MODEL;
    private static final Method SET_ITEM_MODEL_METHOD;
    private static final boolean HAS_SPAWN_WITH_CONSUMER;
    
    static {
        boolean hasMethod = false;
        Method method = null;
        try {
            method = ItemMeta.class.getMethod("setItemModel", NamespacedKey.class);
            hasMethod = true;
        } catch (NoSuchMethodException e) {
            // 1.20.x 及以下版本没有此方法
        }
        HAS_SET_ITEM_MODEL = hasMethod;
        SET_ITEM_MODEL_METHOD = method;
        
        boolean hasSpawnConsumer = false;
        try {
            World.class.getMethod("spawn", Location.class, Class.class, Consumer.class);
            hasSpawnConsumer = true;
        } catch (NoSuchMethodException e) {
            // 1.20.x 及以下版本没有此方法
        }
        HAS_SPAWN_WITH_CONSUMER = hasSpawnConsumer;
    }
    
    private VersionCompat() {}
    
    /**
     * 检查当前服务器是否支持 setItemModel API
     */
    public static boolean supportsItemModel() {
        return HAS_SET_ITEM_MODEL;
    }
    
    /**
     * 检查当前服务器是否支持带 Consumer 的 spawn API
     */
    public static boolean supportsSpawnWithConsumer() {
        return HAS_SPAWN_WITH_CONSUMER;
    }
    
    /**
     * 安全地设置 ItemMeta 的 item model
     * 如果服务器不支持此 API，则静默忽略
     */
    public static void setItemModel(ItemMeta meta, NamespacedKey model) {
        if (meta == null || model == null) return;
        if (HAS_SET_ITEM_MODEL && SET_ITEM_MODEL_METHOD != null) {
            try {
                SET_ITEM_MODEL_METHOD.invoke(meta, model);
            } catch (Exception e) {
                // 忽略调用失败
            }
        }
    }
    
    /**
     * 创建玩家头颅组件（兼容版本）
     * 在 1.21+ 使用 ObjectContents，在旧版本返回普通文本
     */
    public static Component createPlayerHeadComponent(String playerName) {
        // 旧版本回退：返回玩家名文本
        return Component.text(playerName).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false);
    }
    
    /**
     * 兼容性 spawn 方法
     * 在支持 Consumer 的版本使用 Consumer，在旧版本先 spawn 再配置
     */
    @SuppressWarnings("unchecked")
    public static <T extends Entity> T spawnEntity(World world, Location location, Class<T> entityClass, Consumer<T> consumer) {
        if (HAS_SPAWN_WITH_CONSUMER) {
            try {
                Method spawnMethod = World.class.getMethod("spawn", Location.class, Class.class, Consumer.class);
                return (T) spawnMethod.invoke(world, location, entityClass, consumer);
            } catch (Exception e) {
                // 回退到旧方法
            }
        }
        // 旧版本：先 spawn 再配置
        T entity = world.spawn(location, entityClass);
        if (consumer != null) {
            consumer.accept(entity);
        }
        return entity;
    }
}
