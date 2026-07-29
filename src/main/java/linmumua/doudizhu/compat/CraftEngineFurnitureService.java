package linmumua.doudizhu.compat;

import linmumua.doudizhu.DoudizhuPlugin;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class CraftEngineFurnitureService {
    private final DoudizhuPlugin plugin;
    private Plugin craftEngine;
    private Class<?> keyClass;
    private Class<?> immutableBlockStateClass;
    private Method keyOfMethod;
    private Method placeMethod;
    private Method removeMethod;
    private Method removeWithFlagsMethod;
    private Method furnitureManagerInstanceMethod;
    private Method furnitureByIdMethod;
    private Method itemManagerInstanceMethod;
    private Method itemWrapMethod;
    private Method itemIsCustomMethod;
    private Method itemIsBlockItemMethod;
    private Method itemCustomIdMethod;
    private Method itemIdMethod;
    private Method keyAsStringMethod;
    private Method blockDeserializeMethod;
    private Method blockPlaceMethod;
    private Method getLoadedFurnitureByMetaEntityMethod;
    private Method getLoadedFurnitureBySeatMethod;
    private Method getLoadedFurnitureByColliderMethod;
    private Method networkManagerInstanceMethod;
    private Method getOnlineUserMethod;
    private Method furnitureSnapshotStateMethod;
    private Method hideHitboxesMethod;
    private Method showHitboxesMethod;
    private boolean unavailable;
    private boolean hitboxVisibilityUnavailable;

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

    public PlacementKind detectPlacementKind(String itemId) {
        if (bridge() == null || itemId == null || itemId.isBlank()) {
            return PlacementKind.UNKNOWN;
        }
        try {
            Object key = keyOfMethod.invoke(null, itemId);
            if (key != null && furnitureManagerInstanceMethod != null && furnitureByIdMethod != null) {
                Object manager = furnitureManagerInstanceMethod.invoke(null);
                Object optional = furnitureByIdMethod.invoke(manager, key);
                if (optional instanceof java.util.Optional<?> value && value.isPresent()) {
                    return PlacementKind.FURNITURE;
                }
            }
            if (blockDeserializeMethod != null) {
                Object state = blockDeserializeMethod.invoke(null, itemId);
                if (state != null) {
                    return PlacementKind.BLOCK;
                }
            }
            return PlacementKind.UNKNOWN;
        } catch (ReflectiveOperationException exception) {
            unavailable = true;
            plugin.getLogger().warning("CraftEngine placement detection failed: " + exception.getMessage());
            return PlacementKind.UNKNOWN;
        }
    }

    public ResolvedItem resolveCustomItem(ItemStack itemStack) {
        if (bridge() == null || itemStack == null || itemStack.getType().isAir()) {
            return null;
        }
        try {
            Object itemManager = itemManagerInstanceMethod.invoke(null);
            Object wrapped = itemWrapMethod.invoke(itemManager, itemStack);
            if (wrapped == null) {
                return null;
            }
            Object custom = itemIsCustomMethod.invoke(wrapped);
            if (!(custom instanceof Boolean isCustom) || !isCustom) {
                return null;
            }
            PlacementKind kind = PlacementKind.FURNITURE;
            Object blockItem = itemIsBlockItemMethod.invoke(wrapped);
            if (blockItem instanceof Boolean isBlockItem && isBlockItem) {
                kind = PlacementKind.BLOCK;
            }
            Object optional = itemCustomIdMethod.invoke(wrapped);
            Object key = optional instanceof Optional<?> value && value.isPresent() ? value.get() : itemIdMethod.invoke(wrapped);
            if (key == null) {
                return null;
            }
            Object asString = keyAsStringMethod.invoke(key);
            if (!(asString instanceof String itemId) || itemId.isBlank()) {
                return null;
            }
            return new ResolvedItem(itemId, kind);
        } catch (ReflectiveOperationException exception) {
            unavailable = true;
            plugin.getLogger().warning("CraftEngine custom item resolve failed: " + exception.getMessage());
            return null;
        }
    }

    public boolean placeBlock(Location location, String itemId) {
        if (bridge() == null || blockDeserializeMethod == null || blockPlaceMethod == null) {
            return false;
        }
        try {
            Object state = blockDeserializeMethod.invoke(null, itemId);
            if (state == null) {
                return false;
            }
            Object result = blockPlaceMethod.invoke(null, location, state, 3, false);
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException exception) {
            unavailable = true;
            plugin.getLogger().warning("CraftEngine block placement failed: " + exception.getMessage());
            return false;
        }
    }

    public boolean placeBlockWithState(Location location, String blockState) {
        return placeBlock(location, blockState);
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
            plugin.getLogger().warning("CraftEngine furniture removal fallback for entity " + entity.getUniqueId() + ": " + exception.getMessage());
            return false;
        }
    }

    public boolean setFurnitureHitboxesVisible(Entity entity, Player viewer, boolean visible) {
        if (entity == null || viewer == null || bridge() == null || hitboxVisibilityUnavailable) {
            return false;
        }
        try {
            Object furniture = resolveLoadedFurniture(entity);
            if (furniture == null) {
                return false;
            }
            Object networkManager = networkManagerInstanceMethod.invoke(null);
            Object craftEnginePlayer = getOnlineUserMethod.invoke(networkManager, viewer.getUniqueId());
            if (craftEnginePlayer == null) {
                return false;
            }
            Object snapshot = furnitureSnapshotStateMethod.invoke(furniture);
            if (snapshot == null) {
                return false;
            }
            Method visibilityMethod = visible ? showHitboxesMethod : hideHitboxesMethod;
            visibilityMethod.invoke(snapshot, craftEnginePlayer);
            return true;
        } catch (ReflectiveOperationException exception) {
            hitboxVisibilityUnavailable = true;
            plugin.getLogger().warning("CraftEngine furniture hitbox visibility bridge failed: " + exception.getMessage());
            return false;
        }
    }

    private Object resolveLoadedFurniture(Entity entity) throws ReflectiveOperationException {
        Object furniture = getLoadedFurnitureByMetaEntityMethod.invoke(null, entity);
        if (furniture == null) {
            furniture = getLoadedFurnitureBySeatMethod.invoke(null, entity);
        }
        if (furniture == null) {
            furniture = getLoadedFurnitureByColliderMethod.invoke(null, entity);
        }
        return furniture;
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
            keyClass = Class.forName("net.momirealms.craftengine.core.util.Key", true, loader);
            Class<?> furnitureClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture", true, loader);
            Class<?> furnitureManagerClass = Class.forName("net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurnitureManager", true, loader);
            Class<?> itemManagerClass = Class.forName("net.momirealms.craftengine.bukkit.item.BukkitItemManager", true, loader);
            Class<?> itemClass = Class.forName("net.momirealms.craftengine.core.item.Item", true, loader);
            Class<?> blockStateParserClass = Class.forName("net.momirealms.craftengine.core.block.parser.BlockStateParser", true, loader);
            immutableBlockStateClass = Class.forName("net.momirealms.craftengine.core.block.ImmutableBlockState", true, loader);
            Class<?> craftEngineBlocksClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks", true, loader);
            keyOfMethod = keyClass.getMethod("of", String.class);
            keyAsStringMethod = keyClass.getMethod("asString");
            placeMethod = furnitureClass.getMethod("place", Location.class, keyClass);
            furnitureManagerInstanceMethod = furnitureManagerClass.getMethod("instance");
            furnitureByIdMethod = furnitureManagerClass.getMethod("furnitureById", keyClass);
            itemManagerInstanceMethod = itemManagerClass.getMethod("instance");
            itemWrapMethod = itemManagerClass.getMethod("wrap", Object.class);
            itemIsCustomMethod = itemClass.getMethod("isCustomItem");
            itemIsBlockItemMethod = itemClass.getMethod("isBlockItem");
            itemCustomIdMethod = itemClass.getMethod("customId");
            itemIdMethod = itemClass.getMethod("id");
            blockDeserializeMethod = blockStateParserClass.getMethod("deserialize", String.class);
            blockPlaceMethod = craftEngineBlocksClass.getMethod("place", Location.class, immutableBlockStateClass, int.class, boolean.class);
            try {
                removeWithFlagsMethod = furnitureClass.getMethod("remove", Entity.class, boolean.class, boolean.class);
            } catch (NoSuchMethodException ignored) {
                removeMethod = furnitureClass.getMethod("remove", Entity.class);
            }
            try {
                initializeHitboxVisibilityBridge(loader, furnitureClass);
            } catch (ReflectiveOperationException exception) {
                hitboxVisibilityUnavailable = true;
                plugin.getLogger().warning("CraftEngine furniture hitbox visibility bridge unavailable: " + exception.getMessage());
            }
            craftEngine = detected;
            return craftEngine;
        } catch (ReflectiveOperationException exception) {
            unavailable = true;
            plugin.getLogger().warning("CraftEngine detected but furniture bridge could not initialize: " + exception.getMessage());
            return null;
        }
    }

    private void initializeHitboxVisibilityBridge(ClassLoader loader, Class<?> furnitureApiClass) throws ReflectiveOperationException {
        Class<?> furnitureClass = Class.forName("net.momirealms.craftengine.core.entity.furniture.Furniture", true, loader);
        Class<?> snapshotClass = Class.forName("net.momirealms.craftengine.core.entity.furniture.FurnitureSnapshotState", true, loader);
        Class<?> playerClass = Class.forName("net.momirealms.craftengine.core.entity.player.Player", true, loader);
        Class<?> networkManagerClass = Class.forName("net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager", true, loader);
        getLoadedFurnitureByMetaEntityMethod = furnitureApiClass.getMethod("getLoadedFurnitureByMetaEntity", Entity.class);
        getLoadedFurnitureBySeatMethod = furnitureApiClass.getMethod("getLoadedFurnitureBySeat", Entity.class);
        getLoadedFurnitureByColliderMethod = furnitureApiClass.getMethod("getLoadedFurnitureByCollider", Entity.class);
        networkManagerInstanceMethod = networkManagerClass.getMethod("instance");
        getOnlineUserMethod = networkManagerClass.getMethod("getOnlineUser", UUID.class);
        furnitureSnapshotStateMethod = furnitureClass.getMethod("snapshotState");
        hideHitboxesMethod = snapshotClass.getMethod("hideHitboxes", playerClass);
        showHitboxesMethod = snapshotClass.getMethod("showHitboxes", playerClass);
    }

    public enum PlacementKind {
        FURNITURE,
        BLOCK,
        UNKNOWN
    }

    public record ResolvedItem(String itemId, PlacementKind kind) {
    }
}


