package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 * <p>所有临时实体和命中粒子都由这一份 service 的 {@link #tick()} 统一迭代，
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

    private final DoudizhuPlugin plugin;
    private final Map<UUID, ActiveEffect> effects = new LinkedHashMap<>();
    private final Map<UUID, Integer> lastPlayTicks = new LinkedHashMap<>();
    private boolean stopped;

    public TableGadgetEffectService(DoudizhuPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    /** 尝试开始一次桌内道具效果；失败时不留下实体，也不消费冷却。 */
    public boolean play(
        GameTable table,
        Player actor,
        Player target,
        ItemStack item,
        TableGadgetSettings settings
    ) {
        if (stopped || table == null || actor == null || target == null || item == null || item.getType().isAir()
            || settings == null || !settings.enabled() || !eligible(table, actor) || !eligible(table, target)
            || actor.getUniqueId().equals(target.getUniqueId())
            || !sameWorld(actor, target) || !visibleBetween(actor, target, settings.range())) {
            return false;
        }
        int now = Bukkit.getCurrentTick();
        Integer last = lastPlayTicks.get(actor.getUniqueId());
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
            lastPlayTicks.put(actor.getUniqueId(), now);
            playGenericSound(table, Sound.ENTITY_SNOWBALL_THROW, plugin.getEffectVolume(), 1.0f);
            return true;
        } catch (Throwable failure) {
            if (effect != null) {
                effects.remove(effect.id());
                removeEntities(effect);
            }
            lastPlayTicks.remove(actor.getUniqueId());
            plugin.getLogger().log(Level.WARNING, "牌桌道具效果生成失败（" + item.getType().name() + "）。", failure);
            return false;
        }
    }

    /** 主线程每 tick 调用；单次迭代推进全部实体与粒子效果。 */
    public void tick() {
        if (stopped) {
            return;
        }
        int currentTick = Bukkit.getCurrentTick();
        Iterator<ActiveEffect> iterator = effects.values().iterator();
        while (iterator.hasNext()) {
            ActiveEffect effect = iterator.next();
            boolean keep;
            try {
                keep = effect.tick(currentTick);
            } catch (Throwable failure) {
                plugin.getLogger().log(Level.WARNING, "牌桌道具动画更新失败。", failure);
                keep = false;
            }
            if (!keep) {
                removeEntities(effect);
                iterator.remove();
            }
        }
        lastPlayTicks.entrySet().removeIf(entry -> currentTick - entry.getValue() > 400);
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
            removeEntities(effect);
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
                Player viewer = Bukkit.getPlayer(seat);
                if (viewer != null && viewer.isOnline()) {
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
        Iterator<ActiveEffect> iterator = effects.values().iterator();
        while (iterator.hasNext()) {
            ActiveEffect effect = iterator.next();
            if (predicate.test(effect)) {
                removeEntities(effect);
                iterator.remove();
            }
        }
    }

    private int entityCost(boolean water) {
        return TableGadgetEffectGeometry.entityCost(water);
    }

    private int activeEntityCount() {
        int count = 0;
        for (ActiveEffect effect : effects.values()) {
            count += effect.entityCount();
        }
        return count;
    }

    private void removeEntities(ActiveEffect effect) {
        for (Entity entity : effect.entities()) {
            removeEntity(entity);
        }
    }

    private void removeEntity(Entity entity) {
        if (entity != null && !entity.isDead()) {
            entity.remove();
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
            Player actor = Bukkit.getPlayer(actorId);
            Player target = Bukkit.getPlayer(targetId);
            if (actor == null || target == null || !eligible(table, actor) || !eligible(table, target)
                || !sameWorld(actor, target) || target.isDead()) {
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
            entity().teleport(next);
            previous = next;
            age++;
            if (age < duration) {
                return true;
            }
            Player target = Bukkit.getPlayer(targetId());
            boolean hit = target != null && target.isOnline() && target.getWorld().equals(world)
                && TableGadgetEffectGeometry.withinRadius(point(target.getLocation().clone().add(0.0, 1.0, 0.0)), point(lockedTarget), HIT_RADIUS)
                && visibleBetween(Bukkit.getPlayer(actorId()), target, Math.max(1.0, start.distance(target.getEyeLocation()) + 1.0));
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
            Player target = Bukkit.getPlayer(targetId());
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
            entity().teleport(center);
            bucket.teleport(new Location(
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
            Player viewer = Bukkit.getPlayer(seat);
            if (viewer != null && viewer.isOnline()) {
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
            Player player = Bukkit.getPlayer(seat);
            if (player != null && player.isOnline() && world.equals(player.getWorld())) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    private TableGadgetEffectGeometry.Point point(Location location) {
        return new TableGadgetEffectGeometry.Point(location.getX(), location.getY(), location.getZ());
    }
}
