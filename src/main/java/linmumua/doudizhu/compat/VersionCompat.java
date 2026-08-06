package linmumua.doudizhu.compat;

import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.object.ObjectContents;
import net.kyori.adventure.text.object.PlayerHeadObjectContents;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.meta.ItemMeta;

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
     * 创建原生玩家头像文本对象。
     *
     * 1.21.11+ 的 object 文本组件能直接把玩家皮肤正脸渲染进聊天、行动栏等 HUD，
     * 客户端按档案 UUID 解析皮肤，不需要为每个玩家动态生成资源包字形。
     * 帽子层显式开启，保证第二皮肤层（帽子、耳朵、头发等）和游戏内头像一致。
     *
     * @param playerId 正版玩家档案 UUID
     * @param playerName 玩家名，只作为档案补充字段
     * @return 可直接拼进 Adventure 文本的 8x8 玩家头像
     */
    public static Component createPlayerHeadComponent(UUID playerId, String playerName) {
        return createPlayerHeadComponent(playerId, playerName, null);
    }

    /**
     * 创建携带已登录玩家皮肤属性的原生头像，避免服务端为每次 HUD 刷新远程补全档案。
     * UUID 与名字仍显式写入，确保客户端有稳定的档案身份可用于缓存与回退。
     */
    public static Component createPlayerHeadComponent(
        UUID playerId,
        String playerName,
        PlayerHeadObjectContents.SkinSource skinSource
    ) {
        if (playerId == null && (playerName == null || playerName.isBlank())) {
            return Component.text("?")
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false);
        }
        PlayerHeadObjectContents.Builder builder = ObjectContents.playerHead().hat(true);
        if (skinSource != null) {
            builder.skin(skinSource);
        }
        if (playerId != null) {
            builder.id(playerId);
        }
        if (playerName != null && !playerName.isBlank()) {
            builder.name(playerName);
        }
        // 真人头像是 player_head 对象组件，与位图字形一样受文本颜色乘法染色：
        // 白色像素被完全染成文本颜色，彩色像素保留部分色偏。
        // 显式设 WHITE 而不是 reset：WHITE 是明确的白色（乘以 1.0 = 不染色），
        // 保留贴图原色；reset 只清样式，某些客户端仍会落回父节点颜色。
        // 同时关掉粗体和斜体，避免客户端把头像拉伸变形。
        return Component.object(builder.build())
            .color(NamedTextColor.WHITE)
            .decoration(TextDecoration.BOLD, false)
            .decoration(TextDecoration.ITALIC, false);
    }

    /**
     * 带 Consumer 的 spawn 方法
     */
    public static <T extends Entity> T spawnEntity(World world, Location location, Class<T> entityClass, Consumer<T> consumer) {
        return world.spawn(location, entityClass, consumer);
    }
}
