package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.compat.VersionCompat;
import linmumua.doudizhu.world.TableEntityGeometry;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.BoundingBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * 牌桌道具效果的唯一动画更新器。
 *
 * <p>所有临时实体和命中粒子都由这一份 service 的 {@link #tickTable(GameTable)} 按桌统一迭代，
 * 不为单个水滴或碎片创建 Bukkit 任务。实体默认不可见，生成后只向同桌在线真人显式
 * {@code showEntity}；效果结束、目标失效、牌桌清理和插件关闭都会走同一套移除路径。
 */
public final class TableGadgetEffectService {
    private static final String MUZ_EFFECT_TAG = "muz_table_gadget_effect";
    private static final double PROJECTILE_ARC = 0.65;
    private static final double HIT_RADIUS = 0.90;
    private static final int IMPACT_TICKS = 3;
    private static final int PARTICLE_INTERVAL = 2;
    private static final int HARD_ENTITY_LIMIT = 128;

    /* 失败日志限频窗口：清理/推进失败可能在区域线程上每 tick 复现，限频既避免刷屏也不静默吞掉。 */
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 10_000L;

    private final DoudizhuPlugin plugin;
    // IMPORTANT FOLIA: 效果推进现在跑在各牌桌 owner lane 上，而效果的生成与清理跑在玩家 lane 上，
    // 两侧会并发访问这两个表，必须使用并发容器。
    private final Map<UUID, ActiveEffect> effects = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastPlayTicks = new ConcurrentHashMap<>();
    /* 失败限频表，键是失败原因文本。 */
    private final Map<String, Long> lastFailureLogMillis = new ConcurrentHashMap<>();
    /*
     * 运行时边界（时钟 / 玩家解析 / 实体归属 / 实体移除）。
     *
     * 【为什么必须抽出来】效果实体只能在它所属 region 的线程上生成、推进与删除：Folia 的
     * CraftEntity#getHandle 会对非 owner region 的线程直接抛
     * “Accessing entity state off owning region's thread”，而 Bukkit.getCurrentTick 又只在 ticking
     * region 内可读。抽成注入点后，生产实现仍旧直接使用 Bukkit，包内测试可以注入替身，真实驱动
     * play/tickTable 验证「跨 region 跳过」与「失败必须归还名额」两条语义。
     */
    private final EffectRuntime runtime;
    private boolean stopped;

    public TableGadgetEffectService(DoudizhuPlugin plugin) {
        this(plugin, new BukkitEffectRuntime());
    }

    /** 包内可见：注入时钟与实体操作替身，供行为级测试驱动跨 region / 失败释放路径。 */
    TableGadgetEffectService(DoudizhuPlugin plugin, EffectRuntime runtime) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    /**
     * 效果运行时边界。
     *
     * <p>生产实现只用 Bukkit 公开 API；测试替身可以指定时钟与实体归属，并让实体移除失败，从而在
     * 不启动服务端的前提下验证状态一定归还。
     */
    interface EffectRuntime {
        /** 当前 tick；global lane 没有 ticking region，只能在实际 ticking 的 lane 上读。 */
        int currentTick();

        /** 按 UUID 解析当前玩家；离线或不存在时返回 null。 */
        Player player(UUID playerId);

        /** 实体是否由当前线程所在 region 拥有；不拥有时禁止读 bounding box、remove、showEntity。 */
        boolean isOwnedByCurrentRegion(Entity entity);

        /** 移除效果实体；跨 region 或实体已失效时可能抛异常，调用方必须先把名额归还。 */
        void remove(Entity entity);
    }

    /** 生产边界：直接使用 Bukkit 与实体自身的 region/线程语义。 */
    private static final class BukkitEffectRuntime implements EffectRuntime {
        @Override
        public int currentTick() {
            return Bukkit.getCurrentTick();
        }

        @Override
        public Player player(UUID playerId) {
            return Bukkit.getPlayer(playerId);
        }

        @Override
        public boolean isOwnedByCurrentRegion(Entity entity) {
            return entity != null && Bukkit.isOwnedByCurrentRegion(entity);
        }

        @Override
        public void remove(Entity entity) {
            entity.remove();
        }
    }

    /**
     * 尝试开始一次桌内道具效果；失败时不留下实体，也不消费冷却。
     *
     * <p>IMPORTANT FOLIA: 调用方必须把本方法投递到**牌桌锚点 region** 的 owner lane 上（见
     * {@code TableGadgetService.tryUse}）。actor/target 只传 UUID，玩家实例在本方法内部按当前
     * lane 重新解析，避免把旧 Player 或调用线程的解析结果带进效果状态。
     */
    public boolean play(
        GameTable table,
        UUID actorId,
        UUID targetId,
        ItemStack item,
        TableGadgetSettings settings
    ) {
        if (stopped || table == null || actorId == null || targetId == null || item == null
            || item.getType().isAir() || settings == null || !settings.enabled()) {
            return false;
        }
        Player actor = runtime.player(actorId);
        Player target = runtime.player(targetId);
        if (actor == null || target == null) {
            return false;
        }
        // IMPORTANT FOLIA: 归属判定必须早于任何实体/玩家状态读取。actor 或 target 不在当前 region
        // 时，后面的 getEyeLocation / getBoundingBox / showEntity / remove 都会直接抛
        // “Accessing entity state off owning region's thread”；而且生成出来的实体也不会由本 lane
        // 拥有，推进与清理必然失败。这里直接放弃本次投掷：不生成实体、不写冷却、不消费快照。
        //
        // 【为什么这一条必须记录日志】未放置桌的 owner lane 会回退到 global，而 global 不拥有任何
        // 实体 region，于是这里会静默 return false——玩家看到的就是「投掷毫无反应」，服务端却没有
        // 任何日志，排查时无从下手（实服「投掷卡住，没有日志」的一种形态）。失败可以放弃，但不能无声。
        if (!runtime.isOwnedByCurrentRegion(actor) || !runtime.isOwnedByCurrentRegion(target)) {
            reportFailure("投掷者或目标不在当前 owner region，放弃桌内道具投掷");
            return false;
        }
        if (!eligible(table, actor) || !eligible(table, target)
            || actorId.equals(targetId)
            || !sameWorld(actor, target) || !visibleBetween(actor, target, settings.range())) {
            return false;
        }
        int now;
        try {
            now = runtime.currentTick();
        } catch (RuntimeException failure) {
            // 未放置桌会把 owner lane 回退到 global，而 global 没有 ticking region（Folia 抛
            // “No currently ticking region”）。此时读不到冷却时钟，直接放弃本次投掷：不生成实体、
            // 不消费快照，也绝不让异常逃到调度器把 owner/global 任务打挂（那会让后续投掷彻底卡死）。
            reportFailure("当前 lane 不是 ticking region，放弃桌内道具投掷", failure);
            return false;
        }
        // 冷却表按 400 tick 窗口清理。原先这一步在全局 tick 里做，而 tick 现在只在某张桌确实
        // 有效果时才运行；放到交互路径（本方法本来就在合法 ticking region 上取时钟）可保证清理
        // 一定发生，表的大小也始终受“曾经投掷过的玩家数”限制。
        lastPlayTicks.entrySet().removeIf(entry -> now - entry.getValue() > 400);
        Integer last = lastPlayTicks.get(actorId);
        if (last != null && now - last < settings.cooldownTicks()) {
            return false;
        }
        ItemStack snapshot = item.clone();
        int limit = TableGadgetEffectGeometry.clampConcurrentLimit(settings.maxActive(), HARD_ENTITY_LIMIT);
        boolean water = snapshot.getType() == Material.WATER_BUCKET;
        if (activeEntityCount() + entityCost(water) > limit) {
            return false;
        }

        ActiveEffect effect = null;
        try {
            effect = water
                ? spawnWater(table, actor, target, settings)
                : spawnProjectile(table, actor, target, snapshot, settings);
            if (effect == null) {
                return false;
            }
            effects.put(effect.id(), effect);
            lastPlayTicks.put(actorId, now);
            playGenericSound(table, Sound.ENTITY_SNOWBALL_THROW, plugin.getEffectVolume(), 1.0f);
            return true;
        } catch (Throwable failure) {
            if (effect != null) {
                discardEffect(effect);
            }
            lastPlayTicks.remove(actorId);
            reportFailure("牌桌道具效果生成失败（" + item.getType().name() + "）", failure);
            return false;
        }
    }

    /**
     * 推进属于指定牌桌的道具效果；由 {@code TableGadgetService} 投递到该桌的 owner lane。
     *
     * <p>IMPORTANT FOLIA: 真实 Folia 冒烟日志记录到本方法原先作为 global 周期任务的一部分运行，
     * {@code Bukkit.getCurrentTick()} 抛 {@code IllegalStateException("No currently ticking region")}
     * （global lane 没有 ticking region），随后调度器取消该周期任务。未放置桌的 owner lane 仍会回退到
     * global，所以必须在读取时钟、操作实体之前，先用「本桌没有效果」直接返回。
     */
    public void tickTable(GameTable table) {
        if (stopped || table == null) {
            return;
        }
        List<ActiveEffect> pending = new ArrayList<>();
        for (ActiveEffect effect : effects.values()) {
            if (effect.table() == table) {
                pending.add(effect);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        int currentTick;
        try {
            currentTick = runtime.currentTick();
        } catch (RuntimeException failure) {
            // 未放置桌的 owner lane 会回退到 global，而 global 没有 ticking region（Folia 抛
            // “No currently ticking region”）。此时本轮无法推进，直接返回即可：效果仍留在表里，
            // 等该桌重新放置后由锚点 region 的正常推进收尾；绝不让异常逃到调度器。
            reportFailure("当前 lane 不是 ticking region，跳过本轮桌内道具效果推进", failure);
            return;
        }
        for (ActiveEffect effect : pending) {
            boolean keep;
            try {
                keep = effect.tick(currentTick);
            } catch (Throwable failure) {
                reportFailure("牌桌道具动画更新失败", failure);
                keep = false;
            }
            if (!keep) {
                // 单条效果的清理异常不得逃出本方法：逃出去会让同一张桌后面的效果本轮不再推进，
                // 也会让这条效果的名额永久占用。
                discardEffect(effect);
            }
        }
    }

    /**
     * 释放一条效果状态并尽力清理实体。
     *
     * <p>【为什么先摘状态、再删实体】实体清理可能因为跨 region 或实体失效抛异常（见 {@link EffectRuntime}）。
     * 若把 {@code effects.remove} 放在实体操作之后，一次异常就会让该条效果永久留在表里，
     * {@link #activeEntityCount()} 永不归还，之后所有投掷都会被静默拒绝——这正是实服「投掷卡住」
     * 被永久化的路径。因此状态释放必须无条件先行，实体清理失败只记限频日志。
     */
    private void discardEffect(ActiveEffect effect) {
        effects.remove(effect.id());
        try {
            removeEntities(effect);
        } catch (Throwable failure) {
            reportFailure("牌桌道具效果实体清理失败", failure);
        }
    }

    /**
     * 限频记录失败：同一原因 10 秒最多一条。
     *
     * <p>效果推进跑在区域线程上，失败可能每 tick 复现；既不刷屏也不静默吞掉异常（项目硬约束）。
     */
    private void reportFailure(String message, Throwable failure) {
        if (!shouldLogFailure(message)) {
            return;
        }
        plugin.getLogger().log(Level.WARNING, message + "。", failure);
    }

    /**
     * 无异常对象的限频告警：用于「按设计放弃但不该无声」的分支（例如跨 region 直接跳过投掷）。
     *
     * <p>这类分支不产生 Throwable，但同样属于玩家可见的失败，必须留痕，否则实服只能看到「投掷没反应」。
     */
    private void reportFailure(String message) {
        if (!shouldLogFailure(message)) {
            return;
        }
        plugin.getLogger().log(Level.WARNING, message + "。");
    }

    /** 同一原因文本 10 秒内只放行一条；返回 true 表示本次应当记录。 */
    private boolean shouldLogFailure(String message) {
        long now = System.currentTimeMillis();
        Long last = lastFailureLogMillis.get(message);
        if (last != null && now - last < FAILURE_LOG_INTERVAL_MILLIS) {
            return false;
        }
        lastFailureLogMillis.put(message, now);
        return true;
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        lastPlayTicks.remove(playerId);
        clearMatching(effect -> effect.actorId().equals(playerId) || effect.targetId().equals(playerId));
    }

    public void clearTable(GameTable table) {
        if (table == null) {
            return;
        }
        clearMatching(effect -> effect.table() == table);
    }

    /** 清理当前效果但保留服务可再次使用，供 stop/reload 调用。 */
    public void clearAll() {
        for (ActiveEffect effect : new ArrayList<>(effects.values())) {
            // 逐条走 discardEffect：跨 region 的实体删除失败也必须先把名额归还，否则 clearAll 之后
            // 仍会被 activeEntityCount 挡住后续投掷。
            discardEffect(effect);
        }
        effects.clear();
        lastPlayTicks.clear();
    }

    /** clearAll 的语义别名，便于生命周期协调器表达 reload/reset。 */
    public void reset() {
        clearAll();
    }

    public void shutdown() {
        if (stopped) {
            return;
        }
        clearAll();
        stopped = true;
    }

    private ActiveEffect spawnProjectile(
        GameTable table,
        Player actor,
        Player target,
        ItemStack item,
        TableGadgetSettings settings
    ) {
        Location start = actor.getEyeLocation().clone();
        Vector direction = start.getDirection().normalize();
        start.add(direction.multiply(0.45));
        Location lockedTarget = target.getLocation().clone().add(0.0, 1.0, 0.0);
        World world = start.getWorld();
        if (world == null || lockedTarget.getWorld() == null || !world.equals(lockedTarget.getWorld())) {
            return null;
        }
        ItemDisplay display = spawnDisplay(world, start, item.clone(), table);
        if (display == null) {
            return null;
        }
        return new ProjectileEffect(
            UUID.randomUUID(), table, actor.getUniqueId(), target.getUniqueId(), display,
            start, lockedTarget, settings.flightTicks(), world
        );
    }

    private ActiveEffect spawnWater(GameTable table, Player actor, Player target, TableGadgetSettings settings) {
        Location feet = target.getLocation().clone();
        World world = feet.getWorld();
        if (world == null) {
            return null;
        }
        ItemDisplay display = spawnDisplay(world, feet, waterSheetItem(), table);
        ItemDisplay bucket = null;
        try {
            if (display == null) {
                return null;
            }
            Location bucketLocation = feet.clone().add(0.0, 2.15, 0.0);
            bucket = spawnDisplay(world, bucketLocation, waterBucketItem(), table);
            if (bucket == null) {
                removeEntity(display);
                return null;
            }
            return new WaterEffect(
                UUID.randomUUID(), table, actor.getUniqueId(), target.getUniqueId(), display, bucket,
                settings.waterTicks(), world
            );
        } catch (Throwable failure) {
            removeEntity(display);
            removeEntity(bucket);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("水桶效果实体生成失败。", failure);
        }
    }

    private ItemDisplay spawnDisplay(World world, Location location, ItemStack item, GameTable table) {
        ItemDisplay display = null;
        try {
            display = VersionCompat.spawnEntity(world, location, ItemDisplay.class, entity -> {
                TableEntityGeometry.protectEntity(entity, MUZ_EFFECT_TAG);
                entity.setVisibleByDefault(false);
                entity.setItemStack(item);
                entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                entity.setDisplayWidth(1.0f);
                entity.setDisplayHeight(1.0f);
                entity.setInterpolationDuration(1);
            });
            if (display == null) {
                throw new IllegalStateException("Paper 未返回 ItemDisplay 实体。");
            }
            for (UUID seat : table.getSeats()) {
                if (table.isBot(seat)) {
                    continue;
                }
                Player viewer = runtime.player(seat);
                // IMPORTANT FOLIA: Player#showEntity 会同时读实体与玩家自身的状态
                // （CraftPlayer#showEntity0 → Entity#isVisibleByDefault / player.getHandle → ensureTickThread），
                // 因此只有同样由当前 region 拥有的观众才能安全显示；跨 region 观众直接跳过。
                if (viewer != null && viewer.isOnline() && runtime.isOwnedByCurrentRegion(viewer)) {
                    viewer.showEntity(plugin, display);
                }
            }
            return display;
        } catch (Throwable failure) {
            removeEntity(display);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("道具实体可见性设置失败。", failure);
        }
    }

    private ItemStack waterSheetItem() {
        ItemStack item = new ItemStack(Material.WATER_BUCKET);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("水幕 ItemMeta 不可用。");
        }
        VersionCompat.setItemModel(meta, modelKey("muz:table_gadget_water_sheet"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack waterBucketItem() {
        return new ItemStack(Material.WATER_BUCKET);
    }

    private org.bukkit.NamespacedKey modelKey(String key) {
        org.bukkit.NamespacedKey parsed = org.bukkit.NamespacedKey.fromString(key);
        if (parsed == null) {
            throw new IllegalStateException("无效道具模型键：" + key);
        }
        return parsed;
    }

    private boolean eligible(GameTable table, Player player) {
        return player.isOnline() && table.getPhase() == GamePhase.PLAYING
            && table.contains(player.getUniqueId()) && !table.isBot(player.getUniqueId());
    }

    private boolean sameWorld(Player actor, Player target) {
        return actor.getWorld() != null && actor.getWorld().equals(target.getWorld());
    }

    private boolean visibleBetween(Player actor, Player target, double range) {
        Location eye = actor.getEyeLocation();
        Location targetEye = target.getEyeLocation();
        double distance = eye.distance(targetEye);
        if (distance > range) {
            return false;
        }
        Vector direction = targetEye.toVector().subtract(eye.toVector());
        if (direction.lengthSquared() < 1.0E-8) {
            return false;
        }
        RayTraceResult blocked = actor.getWorld().rayTraceBlocks(
            eye, direction.normalize(), distance, FluidCollisionMode.NEVER, true
        );
        return blocked == null || blocked.getHitPosition().distance(eye.toVector()) + 0.20 >= distance;
    }

    private void clearMatching(java.util.function.Predicate<ActiveEffect> predicate) {
        // 用快照迭代 + discardEffect：并发容器上先摘状态再删实体，跨 region 删除失败也不会漏条目。
        for (ActiveEffect effect : new ArrayList<>(effects.values())) {
            if (predicate.test(effect)) {
                discardEffect(effect);
            }
        }
    }

    private int entityCost(boolean water) {
        return TableGadgetEffectGeometry.entityCost(water);
    }

    /**
     * 当前活动效果占用的实体名额。
     *
     * <p>{@link #play(GameTable, UUID, UUID, ItemStack, TableGadgetSettings)} 的上限判定读的就是这个数：
     * 它一旦因为清理失败而不归还，后续所有投掷都会被静默拒绝。包内可见以便行为测试断言状态确实释放。
     */
    int activeEntityCount() {
        int count = 0;
        for (ActiveEffect effect : effects.values()) {
            count += effect.entityCount();
        }
        return count;
    }

    /** 当前登记的效果条数；用于断言失败路径确实摘除了条目，而不只是归还了名额。 */
    int trackedEffectCount() {
        return effects.size();
    }

    private void removeEntities(ActiveEffect effect) {
        for (Entity entity : effect.entities()) {
            removeEntity(entity);
        }
    }

    private void removeEntity(Entity entity) {
        // 是否存活用字段读（跨 region 安全）；真正的 remove 交给运行时边界，失败由调用方记账。
        if (entity != null && !entity.isDead()) {
            runtime.remove(entity);
        }
    }

    private abstract class ActiveEffect {
        private final UUID id;
        private final GameTable table;
        private final UUID actorId;
        private final UUID targetId;
        private final ItemDisplay entity;
        private Location lastActorLocation;
        private Location lastTargetLocation;

        private ActiveEffect(UUID id, GameTable table, UUID actorId, UUID targetId, ItemDisplay entity) {
            this.id = id;
            this.table = table;
            this.actorId = actorId;
            this.targetId = targetId;
            this.entity = entity;
        }

        UUID id() { return id; }
        GameTable table() { return table; }
        UUID actorId() { return actorId; }
        UUID targetId() { return targetId; }
        ItemDisplay entity() { return entity; }
        List<Entity> entities() { return List.of(entity); }
        int entityCount() { return entities().size(); }
        abstract boolean tick(int currentTick);

        boolean validTarget() {
            Player actor = runtime.player(actorId);
            Player target = runtime.player(targetId);
            // 归属门禁提到最前：下面的 eligible/sameWorld/isDead 也会读实体状态，跨 region 时同样非法。
            if (actor == null || target == null
                || !runtime.isOwnedByCurrentRegion(actor) || !runtime.isOwnedByCurrentRegion(target)) {
                return false;
            }
            if (!eligible(table, actor) || !eligible(table, target)
                || !sameWorld(actor, target) || target.isDead()) {
                return false;
            }
            // IMPORTANT FOLIA: 本方法是两条效果推进路径（飞行物 / 水幕）的共同入口，它后面的
            // getEyeLocation、getBoundingBox 都需要 actor/target 的 owner region 就是当前 lane。
            // 任一目标离开本 region 时直接判定目标失效，由调用方走 discardEffect 归还名额，
            // 绝不跨 region 读实体状态（跨 region 会抛 Accessing entity state off owning region's thread）。
            if (!runtime.isOwnedByCurrentRegion(actor) || !runtime.isOwnedByCurrentRegion(target)) {
                return false;
            }
            Location actorLocation = actor.getLocation();
            Location current = target.getLocation();
            if ((lastActorLocation != null
                    && (!lastActorLocation.getWorld().equals(actorLocation.getWorld())
                        || lastActorLocation.distanceSquared(actorLocation) > 16.0))
                || (lastTargetLocation != null
                    && (!lastTargetLocation.getWorld().equals(current.getWorld())
                        || lastTargetLocation.distanceSquared(current) > 16.0))) {
                return false;
            }
            lastActorLocation = actorLocation.clone();
            lastTargetLocation = current.clone();
            return true;
        }
    }

    private final class ProjectileEffect extends ActiveEffect {
        private final Location start;
        private final Location lockedTarget;
        private final int duration;
        private final World world;
        private int age;
        private Location previous;
        private Location impactLocation;
        private int impactRemaining;

        private ProjectileEffect(UUID id, GameTable table, UUID actorId, UUID targetId, ItemDisplay entity,
                                 Location start, Location lockedTarget, int duration, World world) {
            super(id, table, actorId, targetId, entity);
            this.start = start;
            this.lockedTarget = lockedTarget;
            this.duration = duration;
            this.world = world;
            this.previous = start.clone();
        }

        @Override
        boolean tick(int currentTick) {
            if (impactRemaining > 0) {
                if (impactLocation != null) {
                    spawnImpact(world, impactLocation, table());
                }
                impactRemaining--;
                return impactRemaining > 0;
            }
            if (!validTarget() || entity().isDead() || !world.equals(entity().getWorld())) {
                return false;
            }
            TableGadgetEffectGeometry.Point point = TableGadgetEffectGeometry.projectilePosition(
                point(start), point(lockedTarget), age + 1, duration, PROJECTILE_ARC
            );
            Location next = new Location(world, point.x(), point.y(), point.z());
            Vector segment = next.toVector().subtract(previous.toVector());
            if (segment.lengthSquared() > 1.0E-8) {
                RayTraceResult blocked = world.rayTraceBlocks(
                    previous, segment.normalize(), segment.length(), FluidCollisionMode.NEVER, true
                );
                if (blocked != null) {
                    return false;
                }
            }
            // IMPORTANT FOLIA: 区域线程里禁止同步传送；飞行物每 tick 推进必须走 teleportAsync
            // （实服验收在牌桌实体路径上确认过同一条限制，见 PhysicalTableManager.teleportIfMoved）。
            entity().teleportAsync(next);
            previous = next;
            age++;
            if (age < duration) {
                return true;
            }
            Player target = runtime.player(targetId());
            boolean hit = target != null && target.isOnline() && target.getWorld().equals(world)
                && TableGadgetEffectGeometry.withinRadius(point(target.getLocation().clone().add(0.0, 1.0, 0.0)), point(lockedTarget), HIT_RADIUS)
                && visibleBetween(runtime.player(actorId()), target, Math.max(1.0, start.distance(target.getEyeLocation()) + 1.0));
            if (hit) {
                impactLocation = target.getLocation().clone().add(0.0, 1.0, 0.0);
                impactRemaining = IMPACT_TICKS;
                removeEntity(entity());
                spawnImpact(world, impactLocation, table());
                impactRemaining--;
                return impactRemaining > 0;
            }
            return false;
        }
    }

    private final class WaterEffect extends ActiveEffect {
        private final int duration;
        private final World world;
        /** 顶部倒水表现；与水幕一起属于同一个可清理效果。 */
        private final ItemDisplay bucket;
        private int age;

        private WaterEffect(UUID id, GameTable table, UUID actorId, UUID targetId, ItemDisplay entity,
                            ItemDisplay bucket, int duration, World world) {
            super(id, table, actorId, targetId, entity);
            this.duration = duration;
            this.world = world;
            this.bucket = bucket;
        }

        @Override
        List<Entity> entities() {
            return List.of(entity(), bucket);
        }

        @Override
        boolean tick(int currentTick) {
            if (!validTarget() || entity().isDead() || bucket.isDead() || !world.equals(entity().getWorld())) {
                return false;
            }
            Player target = runtime.player(targetId());
            if (target == null) {
                return false;
            }
            BoundingBox body = target.getBoundingBox();
            double bodyHeight = Math.max(0.1, body.getMaxY() - body.getMinY());
            double width = Math.max(0.35, body.getMaxX() - body.getMinX() + 0.08);
            double depth = Math.max(0.35, body.getMaxZ() - body.getMinZ() + 0.08);
            double height = TableGadgetEffectGeometry.waterHeight(bodyHeight, age + 1, duration);
            double centerY = TableGadgetEffectGeometry.waterDisplayCenterY(
                body.getMaxY(), bodyHeight, age + 1, duration
            );
            Location center = new Location(
                world,
                (body.getMinX() + body.getMaxX()) * 0.5,
                centerY,
                (body.getMinZ() + body.getMaxZ()) * 0.5
            );
            Location feet = center.clone().add(0.0, -height * 0.5, 0.0);
            // IMPORTANT FOLIA: 同上，水幕本体与头顶水桶两处位置写入同样必须走 teleportAsync。
            entity().teleportAsync(center);
            bucket.teleportAsync(new Location(
                world,
                center.getX(),
                body.getMaxY() + 0.30,
                center.getZ()
            ));
            entity().setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f((float) width, (float) height, (float) depth),
                new AxisAngle4f()
            ));
            if (age % PARTICLE_INTERVAL == 0) {
                spawnWaterParticles(table(), world, feet, height);
            }
            age++;
            return age < duration;
        }
    }

    private void spawnImpact(World world, Location location, GameTable table) {
        for (Player viewer : viewers(table, world)) {
            viewer.spawnParticle(org.bukkit.Particle.CLOUD, location, 8, 0.20, 0.24, 0.20, 0.02);
        }
    }

    private void playGenericSound(GameTable table, Sound sound, float volume, float pitch) {
        if (table == null || sound == null || volume <= 0.0f) {
            return;
        }
        for (UUID seat : table.getSeats()) {
            if (table.isBot(seat)) {
                continue;
            }
            Player viewer = runtime.player(seat);
            // IMPORTANT FOLIA: playSound 的播放位置取自 viewer.getLocation()，而读位置要求该玩家由当前
            // region 拥有；跨 region 玩家的 getLocation() 会抛 “Accessing entity state off owning
            // region's thread”，把本次投掷整体打成失败（本方法在 play 的成功路径上调）。跨 region
            // 玩家听不到这段音效本就无害，直接跳过，绝不因此失败整次投掷。
            if (viewer != null && viewer.isOnline() && runtime.isOwnedByCurrentRegion(viewer)) {
                viewer.playSound(viewer.getLocation(), sound, volume, pitch);
            }
        }
    }

    private void spawnWaterParticles(GameTable table, World world, Location feet, double height) {
        Location drop = feet.clone().add(0.0, Math.max(0.2, height), 0.0);
        for (Player viewer : viewers(table, world)) {
            viewer.spawnParticle(org.bukkit.Particle.FALLING_WATER, drop, 1, 0.18, 0.05, 0.18, 0.0);
            viewer.spawnParticle(org.bukkit.Particle.DRIPPING_WATER, feet.clone().add(0.0, 0.15, 0.0), 1,
                0.15, 0.02, 0.15, 0.0);
        }
    }

    private List<Player> viewers(GameTable table, World world) {
        List<Player> viewers = new ArrayList<>();
        for (UUID seat : table.getSeats()) {
            if (table.isBot(seat)) {
                continue;
            }
            Player player = runtime.player(seat);
            // IMPORTANT FOLIA: 与 playGenericSound 同口径——跨 region 玩家先按归属门禁跳过，再读 getWorld()，
            // 否则 spawnParticle/getWorld 抛异常会让本应有效的效果被 tickTable 的单条 catch 提前丢弃。
            if (player != null && player.isOnline() && runtime.isOwnedByCurrentRegion(player)
                && world.equals(player.getWorld())) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    private TableGadgetEffectGeometry.Point point(Location location) {
        return new TableGadgetEffectGeometry.Point(location.getX(), location.getY(), location.getZ());
    }
}
