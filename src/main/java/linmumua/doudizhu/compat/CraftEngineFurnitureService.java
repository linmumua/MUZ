package linmumua.doudizhu.compat;

import linmumua.doudizhu.DoudizhuPlugin;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import taboolib.library.reflex.AnalyseMode;
import taboolib.library.reflex.ClassAnalyser;
import taboolib.library.reflex.ClassMethod;
import taboolib.library.reflex.ReflexClass;

public final class CraftEngineFurnitureService {
    private final DoudizhuPlugin plugin;
    private Plugin craftEngine;
    private Class<?> keyClass;
    private Class<?> immutableBlockStateClass;
    // 【为什么这些成员从 java.lang.reflect.Method 换成 TabooLib 的 ClassMethod】：
    // 项目约定 Java 反射一律走 TabooLib 的 reflex 工具。这里每个槽位都是「单态、参数形状固定」
    // 的 CE API，正适合 ClassMethod。两个必须知道的语义差异（调用点已按此改写）：
    //   1. 静态方法【不能】用 invoke(null, ...)（实测抛 NPE「parameter src is null」），
    //      必须用 invokeStatic(...)。
    //   2. 实参必须与形参的装箱类型【精确一致】（实测传 String 给 int 形参抛 ClassCastException，
    //      传 Integer 给 boolean 形参也抛），因此 basic 类型参数一律显式装箱。
    private ClassMethod keyOfMethod;
    private ClassMethod placeMethod;
    private ClassMethod removeMethod;
    private ClassMethod removeWithFlagsMethod;
    private ClassMethod furnitureManagerInstanceMethod;
    private ClassMethod furnitureByIdMethod;
    private ClassMethod itemManagerInstanceMethod;
    private ClassMethod itemWrapMethod;
    private ClassMethod itemIsCustomMethod;
    private ClassMethod itemIsBlockItemMethod;
    private ClassMethod itemCustomIdMethod;
    private ClassMethod itemIdMethod;
    private ClassMethod keyAsStringMethod;
    private ClassMethod blockDeserializeMethod;
    private ClassMethod blockPlaceMethod;
    private ClassMethod getLoadedFurnitureByMetaEntityMethod;
    private ClassMethod getLoadedFurnitureBySeatMethod;
    private ClassMethod getLoadedFurnitureByColliderMethod;
    private ClassMethod networkManagerInstanceMethod;
    private ClassMethod getOnlineUserMethod;
    private ClassMethod furnitureSnapshotStateMethod;
    private ClassMethod hideHitboxesMethod;
    private ClassMethod showHitboxesMethod;
    private boolean unavailable;
    private boolean hitboxVisibilityUnavailable;
    /** 已记录过「跨 region 跳过」告警的实体 UUID；同一个实体只记一次，避免上线事件里对每个 viewer 刷屏。 */
    private final Set<UUID> skippedCrossRegionLogs = ConcurrentHashMap.newKeySet();
    private final RegionGate regionGate;

    public CraftEngineFurnitureService(DoudizhuPlugin plugin) {
        this(plugin, new BukkitRegionGate());
    }

    /** 包内可见：注入 region 归属判定替身，供行为级测试驱动「跨 region 直接放弃」路径。 */
    CraftEngineFurnitureService(DoudizhuPlugin plugin, RegionGate regionGate) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.regionGate = Objects.requireNonNull(regionGate, "regionGate");
    }

    /**
     * 实体归属运行时边界。
     *
     * <p>生产实现只用 Bukkit 的公开判定；测试替身可以在不启动服务端的前提下指定实体归属，
     * 从而真实驱动「跨 region 必须在读实体之前放弃」这条语义（Bukkit 静态方法无法在单测里伪造）。
     */
    interface RegionGate {
        /** 实体是否由当前线程所在 region 拥有；不拥有时禁止读取或修改实体状态。 */
        boolean isOwnedByCurrentRegion(Entity entity);
    }

    /** 生产边界：直接使用 Bukkit 的 region 归属判定。 */
    private static final class BukkitRegionGate implements RegionGate {
        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            return entity != null && Bukkit.isOwnedByCurrentRegion(entity);
        }
    }

    public boolean isAvailable() {
        return bridge() != null;
    }

    public Entity placeFurniture(Location location, String itemId) {
        if (bridge() == null) {
            return null;
        }
        try {
            Object key = keyOfMethod.invokeStatic(new Object[] { itemId });
            Object furniture = placeMethod.invokeStatic(new Object[] { location, key });
            if (furniture == null) {
                return null;
            }
            Object bukkitEntity = firstMethod(furniture.getClass(), "bukkitEntity").invoke(furniture, new Object[0]);
            return bukkitEntity instanceof Entity entity ? entity : null;
        } catch (Throwable exception) {
            unavailable = true;
            // 同 describeFailure：包装/反射异常的 getMessage() 常为 null，直接拼接会让实服日志只剩
            // “... failed: null”，完全无法排查。统一走 describeFailure 带上异常类型与目标异常。
            plugin.getLogger().warning("CraftEngine furniture placement failed: " + describeFailure(exception));
            return null;
        }
    }

    public PlacementKind detectPlacementKind(String itemId) {
        if (bridge() == null || itemId == null || itemId.isBlank()) {
            return PlacementKind.UNKNOWN;
        }
        try {
            Object key = keyOfMethod.invokeStatic(new Object[] { itemId });
            if (key != null && furnitureManagerInstanceMethod != null && furnitureByIdMethod != null) {
                Object manager = furnitureManagerInstanceMethod.invokeStatic(new Object[0]);
                Object optional = furnitureByIdMethod.invoke(manager, new Object[] { key });
                if (optional instanceof java.util.Optional<?> value && value.isPresent()) {
                    return PlacementKind.FURNITURE;
                }
            }
            if (blockDeserializeMethod != null) {
                Object state = blockDeserializeMethod.invokeStatic(new Object[] { itemId });
                if (state != null) {
                    return PlacementKind.BLOCK;
                }
            }
            return PlacementKind.UNKNOWN;
        } catch (Throwable exception) {
            unavailable = true;
            plugin.getLogger().warning("CraftEngine placement detection failed: " + describeFailure(exception));
            return PlacementKind.UNKNOWN;
        }
    }

    public ResolvedItem resolveCustomItem(ItemStack itemStack) {
        if (bridge() == null || itemStack == null || itemStack.getType().isAir()) {
            return null;
        }
        try {
            Object itemManager = itemManagerInstanceMethod.invokeStatic(new Object[0]);
            Object wrapped = itemWrapMethod.invoke(itemManager, new Object[] { itemStack });
            if (wrapped == null) {
                return null;
            }
            Object custom = itemIsCustomMethod.invoke(wrapped, new Object[0]);
            if (!(custom instanceof Boolean isCustom) || !isCustom) {
                return null;
            }
            PlacementKind kind = PlacementKind.FURNITURE;
            Object blockItem = itemIsBlockItemMethod.invoke(wrapped, new Object[0]);
            if (blockItem instanceof Boolean isBlockItem && isBlockItem) {
                kind = PlacementKind.BLOCK;
            }
            Object optional = itemCustomIdMethod.invoke(wrapped, new Object[0]);
            Object key = optional instanceof Optional<?> value && value.isPresent()
                ? value.get() : itemIdMethod.invoke(wrapped, new Object[0]);
            if (key == null) {
                return null;
            }
            Object asString = keyAsStringMethod.invoke(key, new Object[0]);
            if (!(asString instanceof String itemId) || itemId.isBlank()) {
                return null;
            }
            return new ResolvedItem(itemId, kind);
        } catch (Throwable exception) {
            unavailable = true;
            plugin.getLogger().warning("CraftEngine custom item resolve failed: " + describeFailure(exception));
            return null;
        }
    }

    public boolean placeBlock(Location location, String itemId) {
        if (bridge() == null || blockDeserializeMethod == null || blockPlaceMethod == null) {
            return false;
        }
        try {
            Object state = blockDeserializeMethod.invokeStatic(new Object[] { itemId });
            if (state == null) {
                return false;
            }
            // 显式装箱：int/boolean 形参要求 Integer/Boolean 实参，TabooLib 不做宽松转换。
            Object result = blockPlaceMethod.invokeStatic(
                new Object[] { location, state, Integer.valueOf(3), Boolean.FALSE });
            return result instanceof Boolean value && value;
        } catch (Throwable exception) {
            unavailable = true;
            plugin.getLogger().warning("CraftEngine block placement failed: " + describeFailure(exception));
            return false;
        }
    }

    public boolean placeBlockWithState(Location location, String blockState) {
        return placeBlock(location, blockState);
    }

    public boolean removeFurniture(Entity entity) {
        if (entity == null) {
            return false;
        }
        // IMPORTANT FOLIA: CE 的 remove 与兜底 entity.remove() 都会读取实体状态，跨 region 调用会抛
        // “Accessing entity state off owning region's thread”。调用方（PhysicalTableManager 的清理计划）
        // 本应把清理投到桌锚点 region；这里再守一道门禁，跨 region 时直接放弃这个实体的清理，
        // 由后续的重试/ChunkLoad 修复路径收口，而不是在错误的 lane 上抛异常打断整批清理。
        // 归属判定放在 bridge() 之前：跨 region 时无论 CE 是否可用都不该继续碰这个实体。
        if (!ownsEntityState(entity)) {
            reportSkipped(entity, "removeFurniture");
            return false;
        }
        if (bridge() == null) {
            return false;
        }
        try {
            if (removeWithFlagsMethod != null) {
                // 显式装箱：boolean 形参要求 Boolean 实参。
                removeWithFlagsMethod.invokeStatic(
                    new Object[] { entity, Boolean.FALSE, Boolean.TRUE });
            } else if (removeMethod != null) {
                removeMethod.invokeStatic(new Object[] { entity });
            } else {
                entity.remove();
            }
            return true;
        } catch (Throwable exception) {
            entity.remove();
            plugin.getLogger().warning("CraftEngine furniture removal fallback for entity "
                + entity.getUniqueId() + ": " + describeFailure(exception));
            return false;
        }
    }

    public boolean setFurnitureHitboxesVisible(Entity entity, Player viewer, boolean visible) {
        if (entity == null || viewer == null || hitboxVisibilityUnavailable) {
            return false;
        }
        // IMPORTANT FOLIA: resolveLoadedFurniture 会经 CraftEngineFurniture.getLoadedFurnitureByMetaEntity →
        // CraftEntity.getEntityId 读取【实体状态】，而 Interaction 实体属于牌桌所在 region。本方法在实服
        // 是由 PhysicalTableManager.restoreOccupiedChairHitboxVisibility 经 PlayerOutputDispatcher.runPlayer
        // 投到 viewer 的 player lane 调用的，那条 lane 并不拥有这个实体，于是直接抛
        // “Accessing entity state off owning region's thread”，并在日志里表现为
        // “CraftEngine furniture hitbox visibility bridge failed: null”。跨 region 时直接放弃：
        // 不读实体、不写 hitbox 包（这个 viewer 本就不该在这里改别人的家具可见性），调用方无需处理。
        // 归属判定放在 bridge() 之前：跨 region 时无论 CE 是否可用都不该继续碰这个实体。
        if (!ownsEntityState(entity)) {
            reportSkipped(entity, "setFurnitureHitboxesVisible");
            return false;
        }
        if (bridge() == null) {
            return false;
        }
        try {
            Object furniture = resolveLoadedFurniture(entity);
            if (furniture == null) {
                return false;
            }
            Object networkManager = networkManagerInstanceMethod.invokeStatic(new Object[0]);
            Object craftEnginePlayer = getOnlineUserMethod.invoke(
                networkManager, new Object[] { viewer.getUniqueId() });
            if (craftEnginePlayer == null) {
                return false;
            }
            Object snapshot = furnitureSnapshotStateMethod.invoke(furniture, new Object[0]);
            if (snapshot == null) {
                return false;
            }
            ClassMethod visibilityMethod = visible ? showHitboxesMethod : hideHitboxesMethod;
            visibilityMethod.invoke(snapshot, new Object[] { craftEnginePlayer });
            return true;
        } catch (Throwable exception) {
            hitboxVisibilityUnavailable = true;
            plugin.getLogger().warning(
                "CraftEngine furniture hitbox visibility bridge failed: " + describeFailure(exception));
            return false;
        }
    }

    private Object resolveLoadedFurniture(Entity entity) {
        Object furniture = getLoadedFurnitureByMetaEntityMethod.invokeStatic(new Object[] { entity });
        if (furniture == null) {
            furniture = getLoadedFurnitureBySeatMethod.invokeStatic(new Object[] { entity });
        }
        if (furniture == null) {
            furniture = getLoadedFurnitureByColliderMethod.invokeStatic(new Object[] { entity });
        }
        return furniture;
    }

    /**
     * 当前线程所在 region 是否拥有该实体的状态。
     *
     * <p>Folia 上对非 owner region 的实体做任何状态读取都会抛
     * “Accessing entity state off owning region's thread”。本类所有会碰实体的公共入口都必须先过这道门禁。
     * 拿不到实体对象或 API 尚未就绪时一律视为「不拥有」，宁可放弃也不要跨 region 访问。
     */
    private boolean ownsEntityState(Entity entity) {
        return entity != null && regionGate.isOwnedByCurrentRegion(entity);
    }

    /**
     * 记录一次「按设计放弃」的跨 region 跳过。
     *
     * <p>【为什么不能完全无声】：这个分支在实服表现为「椅子的判定框没恢复」而服务端毫无痕迹，
     * 排查时无从下手（与桌内道具投掷的同类问题一致）。失败可以放弃，但不能无声，所以按实体 UUID 限频记录，
     * 避免同一次上线事件里对每个 viewer 各刷一条。
     */
    private void reportSkipped(Entity entity, String operation) {
        UUID entityId = entity == null ? null : entity.getUniqueId();
        if (entityId == null || !skippedCrossRegionLogs.add(entityId)) {
            return;
        }
        plugin.getLogger().warning(
            "跳过跨 region 的 CraftEngine 家具操作 " + operation + "（实体不归当前 region 所有）: " + entityId);
    }

    /**
     * 把反射异常描述成可排查的文本。
     *
     * <p>旧写法只拼 {@code getMessage()}，而包装异常的 message 常为 {@code null}，于是实服日志只有
     * “bridge failed: null”，完全看不出原因。这里在 message 为空时退回到异常类名，并在有 cause 时
     * 带上被包装的目标异常类型与消息。
     *
     * <p>【为什么参数从 {@code ReflectiveOperationException} 放宽到 {@code Throwable}】：
     * 反射调用改用 TabooLib 的 {@code ClassMethod.invoke} 后，目标方法抛出的异常【不再被包成
     * {@code InvocationTargetException}】（实测原样抛出），因此调用点 catch 的是 {@code Throwable}。
     * 方法体同时兼容两种形态：既认历史 {@code InvocationTargetException} 的 target，
     * 也认通用的 {@code getCause()}，日志质量不下降。
     */
    private static String describeFailure(Throwable exception) {
        String message = exception.getMessage();
        StringBuilder text = new StringBuilder();
        text.append(message == null || message.isBlank() ? exception.getClass().getName() : message);
        Throwable target = exception instanceof InvocationTargetException invocation
            ? invocation.getTargetException()
            : exception.getCause();
        if (target != null) {
            text.append(" (目标异常: ").append(target.getClass().getName());
            String targetMessage = target.getMessage();
            if (targetMessage != null && !targetMessage.isBlank()) {
                text.append(": ").append(targetMessage);
            }
            text.append(')');
        }
        return text.toString();
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
            // 【为什么这里仍然是 Class.forName】：CraftEngine 是 compileOnly，它的类在 MUZ 自己的
            // ClassLoader 里【根本看不到】，必须先按 CE 插件类加载器把 Class 取回来。
            // TabooLib 的 ReflexClass 接的是一个已拿到的 Class<?>，不替代类加载这一步。
            keyClass = Class.forName("net.momirealms.craftengine.core.util.Key", true, loader);
            Class<?> furnitureClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineFurniture", true, loader);
            Class<?> furnitureManagerClass = Class.forName("net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurnitureManager", true, loader);
            Class<?> itemManagerClass = Class.forName("net.momirealms.craftengine.bukkit.item.BukkitItemManager", true, loader);
            Class<?> itemClass = Class.forName("net.momirealms.craftengine.core.item.Item", true, loader);
            Class<?> blockStateParserClass = Class.forName("net.momirealms.craftengine.core.block.parser.BlockStateParser", true, loader);
            immutableBlockStateClass = Class.forName("net.momirealms.craftengine.core.block.ImmutableBlockState", true, loader);
            Class<?> craftEngineBlocksClass = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineBlocks", true, loader);
            // 一律按「方法名 + 完整参数列表」取方法（注意 getMethodByTypes 是宽松匹配，见其说明），
            // 每个 API 都把参数列表写全；参数类型要与 CE 签名一致（含 primitive 的 .class，
            // 例如 int.class），否则取不到。
            keyOfMethod = methodByTypes(keyClass, "of", String.class);
            keyAsStringMethod = methodByTypes(keyClass, "asString");
            placeMethod = methodByTypes(furnitureClass, "place", Location.class, keyClass);
            furnitureManagerInstanceMethod = methodByTypes(furnitureManagerClass, "instance");
            furnitureByIdMethod = methodByTypes(furnitureManagerClass, "furnitureById", keyClass);
            itemManagerInstanceMethod = methodByTypes(itemManagerClass, "instance");
            itemWrapMethod = methodByTypes(itemManagerClass, "wrap", Object.class);
            itemIsCustomMethod = methodByTypes(itemClass, "isCustomItem");
            itemIsBlockItemMethod = methodByTypes(itemClass, "isBlockItem");
            itemCustomIdMethod = methodByTypes(itemClass, "customId");
            itemIdMethod = methodByTypes(itemClass, "id");
            blockDeserializeMethod = methodByTypes(blockStateParserClass, "deserialize", String.class);
            blockPlaceMethod = methodByTypes(
                craftEngineBlocksClass, "place", Location.class, immutableBlockStateClass, int.class, boolean.class);
            // CE 各代 remove 的形状不同（带 flags 与不带 flags），按「先试更具体的签名」回退。
            ClassMethod withFlags = methodByTypesSilently(
                furnitureClass, "remove", Entity.class, boolean.class, boolean.class);
            if (withFlags != null) {
                removeWithFlagsMethod = withFlags;
            } else {
                removeMethod = methodByTypes(furnitureClass, "remove", Entity.class);
            }
            try {
                initializeHitboxVisibilityBridge(loader, furnitureClass);
            } catch (ClassNotFoundException | RuntimeException exception) {
                hitboxVisibilityUnavailable = true;
                plugin.getLogger().warning("CraftEngine furniture hitbox visibility bridge unavailable: " + exception.getMessage());
            }
            craftEngine = detected;
            return craftEngine;
        } catch (ClassNotFoundException | RuntimeException | LinkageError exception) {
            unavailable = true;
            plugin.getLogger().warning("CraftEngine detected but furniture bridge could not initialize: " + exception.getMessage());
            return null;
        }
    }

    private void initializeHitboxVisibilityBridge(ClassLoader loader, Class<?> furnitureApiClass) throws ClassNotFoundException {
        Class<?> furnitureClass = Class.forName("net.momirealms.craftengine.core.entity.furniture.Furniture", true, loader);
        Class<?> snapshotClass = Class.forName("net.momirealms.craftengine.core.entity.furniture.FurnitureSnapshotState", true, loader);
        Class<?> playerClass = Class.forName("net.momirealms.craftengine.core.entity.player.Player", true, loader);
        Class<?> networkManagerClass = Class.forName("net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager", true, loader);
        getLoadedFurnitureByMetaEntityMethod = methodByTypes(furnitureApiClass, "getLoadedFurnitureByMetaEntity", Entity.class);
        getLoadedFurnitureBySeatMethod = methodByTypes(furnitureApiClass, "getLoadedFurnitureBySeat", Entity.class);
        getLoadedFurnitureByColliderMethod = methodByTypes(furnitureApiClass, "getLoadedFurnitureByCollider", Entity.class);
        networkManagerInstanceMethod = methodByTypes(networkManagerClass, "instance");
        getOnlineUserMethod = methodByTypes(networkManagerClass, "getOnlineUser", UUID.class);
        furnitureSnapshotStateMethod = methodByTypes(furnitureClass, "snapshotState");
        hideHitboxesMethod = methodByTypes(snapshotClass, "hideHitboxes", playerClass);
        showHitboxesMethod = methodByTypes(snapshotClass, "showHitboxes", playerClass);
    }

    /**
     * 已解析的类结构缓存：{@code Class} → 结构分析结果。
     *
     * <p>【为什么要缓存】{@code placeFurniture} / {@code resolveCustomItem} / {@code bridge()} 等
     * 都是热路径，原先每次都重做一遍全类结构分析。以 {@code Class} 为键即可：CraftEngine 自身
     * reload 若换掉实现类，{@code Class.forName} 拿到的是另一个 {@code Class}，缓存自然不命中，
     * 不需要按名字做失效逻辑。CE 的重载与我们自己的调用可能来自不同线程，故用并发容器。
     */
    private static final ConcurrentHashMap<Class<?>, ReflexClass> ANALYSED_CLASSES = new ConcurrentHashMap<>();

    /**
     * 按纯反射方式分析一个类结构（按 Class 缓存，见 {@link #ANALYSED_CLASSES}）。
     *
     * <p>用 {@code REFLECTION_ONLY} 而不是默认的 {@code ASM_FIRST}：CraftEngine 的类来自它自己的
     * 插件类加载器，ASM 分析要把 {@code .class} 当资源读出来，跨加载器不保证拿得到；纯反射分析
     * 只需要 {@code Class} 对象本身。
     */
    private static ReflexClass analyse(Class<?> type) {
        return ANALYSED_CLASSES.computeIfAbsent(type, key -> new ReflexClass(
            ClassAnalyser.INSTANCE.analyse(key, AnalyseMode.REFLECTION_ONLY), AnalyseMode.REFLECTION_ONLY));
    }

    /**
     * 按「方法名 + 参数类型列表」取方法；没有就抛异常，绝不静默降级。
     *
     * <p>【勘误：这里的匹配并不「精确」】{@code getMethodByTypes} 内部对形参用
     * {@code isAssignableFrom}，属【宽松匹配】（例如形参是 {@code Object} 时任何实参类型都能命中），
     * 不是签名全等。旧注释写的「精确参数类型」是不准确的。本类每个调用点都把完整参数列表写出来，
     * 且这些 CE API 都无重载，所以宽松与否不影响结果；但若 CE 给同一名字加上重载，必须改成
     * 逐个显式比较签名，不能依赖这个入口「精确」到唯一解。
     */
    private static ClassMethod methodByTypes(Class<?> type, String name, Class<?>... parameterTypes) {
        return analyse(type).getMethodByTypes(name, false, false, parameterTypes);
    }

    /** 同上，但找不到时返回 null，供「按代次回退到另一个重载」的分支使用。 */
    private static ClassMethod methodByTypesSilently(Class<?> type, String name, Class<?>... parameterTypes) {
        return analyse(type).getMethodByTypeSilently(name, false, false, parameterTypes);
    }

    /**
     * 取类里指定名字的【无参】方法，并向上查找父类与接口。
     *
     * <p>用于「一定存在」的简单访问器（例：家具实体的 {@code bukkitEntity}）。
     *
     * <p>【为什么不直接用 {@code getStructure().getMethodsMap()}】它只描述【该类自己声明】的成员，
     * 父类/接口声明的访问器会漏掉；而且同名重载列表的顺序没有语义保证，取 {@code get(0)} 等于
     * 依赖枚举顺序。这里从目标类开始逐层向上找、按参数个数筛：同一层里同名同 arity 至多一个
     * 方法，结果是确定的，且最派生的声明优先。
     */
    private static ClassMethod firstMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            List<ClassMethod> methods = analyse(current).getStructure().getMethodsMap().get(name);
            if (methods != null) {
                for (ClassMethod method : methods) {
                    if (method.getParameterTypes().length == 0) {
                        return method;
                    }
                }
            }
            current = current.getSuperclass();
        }
        throw new UnsupportedOperationException(
            "CraftEngine API 缺失：" + type.getName() + "#" + name + "()");
    }

    public enum PlacementKind {
        FURNITURE,
        BLOCK,
        UNKNOWN
    }

    public record ResolvedItem(String itemId, PlacementKind kind) {
    }
}


