package dev.codex.doudizhu.compat;

import dev.codex.doudizhu.DoudizhuPlugin;
import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class CraftEngineFurnitureService {
    private final DoudizhuPlugin plugin;
    private Plugin craftEngine;
    private Method keyOfMethod;
    private Method placeMethod;
    private Method removeMethod;
    private Method removeWithFlagsMethod;
    private boolean unavailable;

    public CraftEngineFurnitureService(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        return bridge() != null;
    }

    public Entity placeFurniture(Location location, String itemId) {
        if (bridge() == null) {
            return null;
        }
        try {
            Object key = keyOfMethod.invoke(null, itemId);
            Object furniture = placeMethod.invoke(null, location, key);
            if (furniture == null) {
                return null;
            }
            Object bukkitEntity = furniture.getClass().getMethod("bukkitEntity").invoke(furniture);
            return bukkitEntity instanceof Entity entity ? entity : null;
        } catch (ReflectiveOperationException exception) {
            unavailable = true;
            plugin.getLogger().warning("CraftEngine furniture placement failed: " + exception.getMessage());
            return null;
        }
    }

    public boolean removeFurniture(Entity entity) {
        if (entity == null || bridge() == null) {
            return false;
        }
        try {
            if (removeWithFlagsMethod != null) {
                removeWithFlagsMethod.invoke(null, entity, false, true);
            } else if (removeMethod != null) {
                removeMethod.invoke(null, entity);
            } else {
                entity.remove();
            }
            return true;
        } catch (ReflectiveOperationException exception) {
            entity.remove();
            unavailable = true;
            return false;
        }
    }

    private Plugin bridge() {
        if (unavailable) {
            return null;
        }
        if (craftEngine != null && craftEngine.isEnabled()) {
            return craftEngine;
        }
        Plugin detected = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (detected == null || !detected.isEnabled()) {
            return null;
        }
        try {
            ClassLoader loader = detected.getClass().getClassLoader();
            Class<?> keyClass = Class.forName("net.momirealms.craftengine.core.util.Key", true, loader);
            Class<?> furnitureClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture", true, loader);
            keyOfMethod = keyClass.getMethod("of", String.class);
            placeMethod = furnitureClass.getMethod("place", Location.class, keyClass);
            try {
                removeWithFlagsMethod = furnitureClass.getMethod("remove", Entity.class, boolean.class, boolean.class);
            } catch (NoSuchMethodException ignored) {
                removeMethod = furnitureClass.getMethod("remove", Entity.class);
            }
            craftEngine = detected;
            return craftEngine;
        } catch (ReflectiveOperationException exception) {
            unavailable = true;
            plugin.getLogger().warning("CraftEngine detected but furniture bridge could not initialize: " + exception.getMessage());
            return null;
        }
    }
}

