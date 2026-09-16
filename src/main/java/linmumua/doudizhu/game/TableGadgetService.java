package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.compat.CraftEngineOffsetService;
import linmumua.doudizhu.model.TableGadget;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.listener.TableGadgetLifecycleListener;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 牌桌三道具的选择、资格、瞄准与事件闸门。
 *
 * <p>选择是虚拟的 0..2 索引，不改变玩家真实物品栏槽位。真实换槽事件无法携带
 * 「滚轮」还是「数字键」的来源信息，因此同一 previous/new 映射统一按循环方向处理，
 * 不宣称能区分两种输入。
 */
public final class TableGadgetService {
    private final DoudizhuPlugin plugin;
    private final TableGadgetEffectService effects;
    private final Map<UUID, Integer> selectedIndexes = new HashMap<>();
    private final Map<UUID, Integer> lastUseTicks = new HashMap<>();
    private final Map<UUID, Integer> lastAttemptTicks = new HashMap<>();
    private final Map<UUID, TargetState> targets = new HashMap<>();
    private final Map<UUID, Set<UUID>> targetReferences = new HashMap<>();
    private final Map<UUID, Boolean> originalGlowing = new HashMap<>();
    private MuzScheduler.TaskHandle task;
    private TableGadgetLifecycleListener lifecycleListener;
    private TableGadgetSettings settings;
    private boolean stopped;
    private int tickCounter;

    public TableGadgetService(
        DoudizhuPlugin plugin,
        TableGadgetEffectService effects,
        TableGadgetSettings settings
    ) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.effects = java.util.Objects.requireNonNull(effects, "effects");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
    }

    public TableGadgetSettings settings() {
        return settings;
    }

    public int selectedIndex(UUID playerId) {
        return TableGadget.wrapIndex(selectedIndexes.getOrDefault(playerId, 0));
    }

    public TableGadget selectedGadget(UUID playerId) {
        return TableGadget.fromIndex(selectedIndex(playerId));
    }

    /** 将真实 9 槽换槽映射成最短循环方向；8↔0 是相邻一步。 */
    static int slotDelta(int previousSlot, int newSlot) {
        int previous = Math.floorMod(previousSlot, 9);
        int next = Math.floorMod(newSlot, 9);
        int clockwise = Math.floorMod(next - previous, 9);
        return clockwise <= 4 ? clockwise : clockwise - 9;
    }

    /**
     * 处理真实换槽事件。只有 hotbar HUD 开启、PLAYING 真人座位才取消真实槽切换；
     * 其他阶段和普通玩家保持原版物品栏行为。
     */
    public void onHeldChange(PlayerItemHeldEvent event) {
        if (event == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!isEligibleActor(player)) {
            return;
        }
        int delta = slotDelta(event.getPreviousSlot(), event.getNewSlot());
        int current = selectedIndex(player.getUniqueId());
        selectedIndexes.put(player.getUniqueId(), TableGadget.wrapIndex(current + delta));
        event.setCancelled(true);
    }

    /**
     * 尝试消费一次右键。无资格或当前准星没有同桌真人时返回 false，不碰事件；
     * 有效目标处于冷却时仍返回 true 以阻止真实物品交互，但不会生成动画。
     */
    public boolean tryUse(Player actor) {
        if (!isEligibleActor(actor)) {
            return false;
        }
        GameTable table = plugin.getTableManager().getTableOf(actor);
        Player target = findTarget(actor, table);
        if (target == null) {
            return false;
        }
        UUID actorId = actor.getUniqueId();
        int now = Bukkit.getCurrentTick();
        Integer lastAttempt = lastAttemptTicks.put(actorId, now);
        if (lastAttempt != null && lastAttempt == now) {
            return true;
        }
        Integer last = lastUseTicks.get(actorId);
        if (last != null && now - last < settings.cooldownTicks()) {
            return true;
        }
        boolean played = effects.play(table, actor, target, selectedGadget(actorId), settings);
        if (played) {
            lastUseTicks.put(actorId, now);
        }
        return true;
    }

    public void reload(TableGadgetSettings settings) {
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        if (!settings.enabled()) {
            clearAllTargeting();
            effects.clearAll();
        }
    }

    public void start() {
        if (stopped) {
            return;
        }
        if (lifecycleListener == null) {
            lifecycleListener = new TableGadgetLifecycleListener(plugin);
            plugin.getServer().getPluginManager().registerEvents(lifecycleListener, plugin);
        }
        if (task == null) {
            task = plugin.scheduler().runTimer(1L, 1L, this::tick);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        clearAllTargeting();
        selectedIndexes.clear();
        lastUseTicks.clear();
        lastAttemptTicks.clear();
        effects.clearAll();
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        selectedIndexes.remove(playerId);
        lastUseTicks.remove(playerId);
        lastAttemptTicks.remove(playerId);
        clearPlayerTarget(playerId);
        for (UUID targetId : new ArrayList<>(targetReferences.keySet())) {
            releaseReference(targetId, playerId);
        }
        // 玩家也可能是别人的准星目标；离线/死亡/传送时必须立即撤销指向他的全部引用，
        // 否则原有发光状态会被旧引用长期保留。
        releaseTargetCompletely(playerId);
        effects.clearPlayer(playerId);
    }

    public void clearTable(GameTable table) {
        if (table == null) {
            return;
        }
        for (UUID seat : table.getSeats()) {
            clearPlayer(seat);
        }
        effects.clearTable(table);
    }

    public void shutdown() {
        if (stopped) {
            return;
        }
        stop();
        stopped = true;
        if (lifecycleListener != null) {
            org.bukkit.event.HandlerList.unregisterAll(lifecycleListener);
            lifecycleListener = null;
        }
        effects.shutdown();
    }

    private void tick() {
        if (stopped) {
            return;
        }
        effects.tick();
        if (!settings.enabled() || !isHotbarHudReady()) {
            clearAllTargeting();
            return;
        }
        tickCounter++;
        if ((tickCounter & 1) == 0) {
            updateTargets();
        }
    }

    private void updateTargets() {
        Set<UUID> eligible = new HashSet<>();
        for (GameTable table : tables()) {
            if (table.getPhase() != GamePhase.PLAYING) {
                continue;
            }
            for (UUID seat : table.getSeats()) {
                Player actor = Bukkit.getPlayer(seat);
                if (!isEligibleActor(actor)) {
                    continue;
                }
                eligible.add(seat);
                Player target = findTarget(actor, table);
                setTarget(seat, target == null ? null : target.getUniqueId());
            }
        }
        for (UUID actorId : new ArrayList<>(targets.keySet())) {
            if (!eligible.contains(actorId)) {
                clearPlayerTarget(actorId);
            }
        }
    }

    private void setTarget(UUID actorId, UUID targetId) {
        TargetState previous = targets.get(actorId);
        UUID previousId = previous == null ? null : previous.targetId();
        if (java.util.Objects.equals(previousId, targetId)) {
            return;
        }
        if (previousId != null) {
            releaseReference(previousId, actorId);
        }
        if (targetId == null) {
            targets.remove(actorId);
            return;
        }
        targets.put(actorId, new TargetState(targetId));
        acquireReference(targetId, actorId);
    }

    private void clearPlayerTarget(UUID actorId) {
        TargetState state = targets.remove(actorId);
        if (state != null) {
            releaseReference(state.targetId(), actorId);
        }
    }

    private void acquireReference(UUID targetId, UUID actorId) {
        Set<UUID> refs = targetReferences.computeIfAbsent(targetId, ignored -> new HashSet<>());
        if (!refs.add(actorId)) {
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (refs.size() == 1 && target != null && target.isOnline()) {
            originalGlowing.put(targetId, target.isGlowing());
            if (!target.isGlowing()) {
                target.setGlowing(true);
            }
        }
    }

    private void releaseReference(UUID targetId, UUID actorId) {
        Set<UUID> refs = targetReferences.get(targetId);
        if (refs == null || !refs.remove(actorId) || !refs.isEmpty()) {
            return;
        }
        releaseTargetCompletely(targetId);
    }

    private void releaseTargetCompletely(UUID targetId) {
        targetReferences.remove(targetId);
        // 目标离开/传送时，不能只撤 glow 引用；还要删掉所有 actor→target 状态，
        // 否则目标回来后 setTarget(同一 UUID) 会短路，永远不会重新建立引用。
        for (UUID actorId : new ArrayList<>(targets.keySet())) {
            TargetState state = targets.get(actorId);
            if (state != null && targetId.equals(state.targetId())) {
                targets.remove(actorId);
            }
        }
        Player target = Bukkit.getPlayer(targetId);
        Boolean wasGlowing = originalGlowing.remove(targetId);
        if (target != null && target.isOnline() && Boolean.FALSE.equals(wasGlowing)) {
            target.setGlowing(false);
        }
    }

    private void clearAllTargeting() {
        for (UUID actorId : new ArrayList<>(targets.keySet())) {
            clearPlayerTarget(actorId);
        }
        for (UUID targetId : new ArrayList<>(targetReferences.keySet())) {
            releaseTargetCompletely(targetId);
        }
        originalGlowing.clear();
    }

    private boolean isHotbarHudReady() {
        HotbarHudService hotbarHud = plugin.getHotbarHudService();
        if (hotbarHud == null || !hotbarHud.isEnabled() || !hotbarHud.isRunning()) {
            return false;
        }
        CraftEngineOffsetService offsetService = plugin.getCraftEngineOffsetService();
        return offsetService != null && offsetService.isAvailable();
    }

    private boolean isEligibleActor(Player player) {
        if (player == null || !player.isOnline() || settings == null || !settings.enabled()) {
            return false;
        }
        if (!isHotbarHudReady()) {
            return false;
        }
        TableManager manager = plugin.getTableManager();
        if (manager == null) {
            return false;
        }
        GameTable table = manager.getTableOf(player);
        return table != null
            && table.getPhase() == GamePhase.PLAYING
            && table.contains(player.getUniqueId())
            && !table.isBot(player.getUniqueId());
    }

    private Player findTarget(Player actor, GameTable table) {
        if (actor == null || table == null || !isEligibleActor(actor)) {
            return null;
        }
        Location eye = actor.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0E-8) {
            return null;
        }
        double maxDistance = settings.range();
        RayTraceResult hit = actor.getWorld().rayTraceEntities(
            eye,
            direction,
            maxDistance,
            0.3,
            entity -> entity instanceof Player target
                && !target.getUniqueId().equals(actor.getUniqueId())
                && target.isOnline()
                && table.contains(target.getUniqueId())
                && !table.isBot(target.getUniqueId())
        );
        if (hit == null || !(hit.getHitEntity() instanceof Player target)) {
            return null;
        }
        if (!actor.getWorld().equals(target.getWorld())) {
            return null;
        }
        double targetDistance = eye.distance(target.getEyeLocation());
        RayTraceResult blocked = actor.getWorld().rayTraceBlocks(
            eye,
            direction,
            Math.min(maxDistance, targetDistance),
            FluidCollisionMode.NEVER,
            true
        );
        if (blocked != null && blocked.getHitPosition().distance(eye.toVector()) + 0.25 < targetDistance) {
            return null;
        }
        return target;
    }

    private List<GameTable> tables() {
        TableManager manager = plugin.getTableManager();
        return manager == null ? List.of() : new ArrayList<>(manager.getTables());
    }

    private record TargetState(UUID targetId) {
    }
}
