package linmumua.doudizhu.game;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.scheduler.MuzScheduler;
import linmumua.doudizhu.listener.TableGadgetLifecycleListener;
import linmumua.doudizhu.ui.VirtualGadgetBar;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * 牌桌道具的 ItemStack 快照选择、资格、瞄准与事件闸门。
 *
 * <p>选择与玩家真实物品栏解耦；每次选择都保存独立快照，成功投掷后一次性清空。
 */
public final class TableGadgetService {
    /* 失败限频窗口：扫描派发失败可能在区域线程上每 tick 复现，限频既避免刷屏也不静默吞掉异常。 */
    private static final long FAILURE_LOG_INTERVAL_MILLIS = 10_000L;

    private final DoudizhuPlugin plugin;
    private final TableGadgetEffectService effects;
    private final PlayerOutputDispatcher output;
    // IMPORTANT FOLIA: 这些表会被 global 扫描线程、各牌桌 owner lane 与各 player lane 并发访问，
    // 必须使用并发容器；HashMap 在并发结构修改下会丢条目甚至死循环。
    private final Map<UUID, ItemStack> selectedItems = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> selectedSlots = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastUseTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastAttemptTicks = new ConcurrentHashMap<>();
    private final Map<UUID, TargetState> targets = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> targetReferences = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> originalGlowing = new ConcurrentHashMap<>();
    /* 失败限频表，键是失败原因文本。 */
    private final Map<String, Long> lastFailureLogMillis = new ConcurrentHashMap<>();
    private MuzScheduler.TaskHandle task;
    private TableGadgetLifecycleListener lifecycleListener;
    private TableGadgetSettings settings;
    private boolean stopped;
    private int tickCounter;

    public TableGadgetService(
        DoudizhuPlugin plugin,
        TableGadgetEffectService effects,
        TableGadgetSettings settings,
        PlayerOutputDispatcher output
    ) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.effects = java.util.Objects.requireNonNull(effects, "effects");
        this.settings = java.util.Objects.requireNonNull(settings, "settings");
        // 目标高亮属玩家级工作，统一经门面投递到 player lane。
        this.output = java.util.Objects.requireNonNull(output, "output");
    }

    public TableGadgetSettings settings() {
        return settings;
    }

    /** 返回当前虚拟道具槽位；没有选择时回到首个槽位。 */
    public int selectedIndex(UUID playerId) {
        return selectedSlots.getOrDefault(playerId, 0);
    }

    /** 选择一份独立快照；调用方后续修改原 ItemStack 不会影响本次选择。 */
    public void select(UUID playerId, ItemStack item) {
        select(playerId, selectedIndex(playerId), item);
    }

    /** 选择指定虚拟槽位及其独立 ItemStack 快照。 */
    public void select(UUID playerId, int slot, ItemStack item) {
        if (playerId == null || item == null || item.getType().isAir()) {
            clear(playerId);
            return;
        }
        selectedSlots.put(playerId, Math.max(0, Math.min(VirtualGadgetBar.SLOT_COUNT - 1, slot)));
        selectedItems.put(playerId, item.clone());
    }

    public void select(Player player, ItemStack item) {
        select(player == null ? null : player.getUniqueId(), item);
    }

    /** 清除玩家当前已选道具。 */
    public void clear(UUID playerId) {
        if (playerId != null) {
            selectedItems.remove(playerId);
        }
    }

    public void clear(Player player) {
        clear(player == null ? null : player.getUniqueId());
    }

    /** 返回当前快照；空值表示未选择道具。 */
    public ItemStack current(UUID playerId) {
        ItemStack item = playerId == null ? null : selectedItems.get(playerId);
        return item == null ? null : item.clone();
    }

    public ItemStack current(Player player) {
        return current(player == null ? null : player.getUniqueId());
    }

    /** 保留空入口以兼容既有监听器；道具选择不依赖换槽事件。 */
    public void onHeldChange(Object ignored) {
    }

    /**
     * 尝试消费一次右键。无资格或当前准星没有同桌真人时返回 false，不碰事件；
     * 有效目标处于冷却时仍返回 true 以阻止真实物品交互，但不会生成动画。
     *
     * <p>IMPORTANT FOLIA: 本方法跑在 actor 自己的 player lane 上，只做「读快照 + 判定 + 投递」：
     * 视线射线用的是 actor 自己的 region，合法；真正的效果实体生成必须由牌桌锚点 region 执行
     * （见 {@link #tick()} 与 {@code TableGadgetEffectService.play} 的归属门禁）。投递是异步的，
     * 所以本方法只表达「本次右键已被桌内道具路由消费」，成功投掷才消费冷却与道具快照。
     */
    public boolean tryUse(Player actor) {
        if (!isEligibleActor(actor)) {
            return false;
        }
        UUID actorId = actor.getUniqueId();
        TableManager manager = plugin.getTableManager();
        GameTable table = manager == null ? null : manager.getTableOf(actor);
        Player target = findTarget(actor, table);
        if (target == null) {
            return false;
        }
        int now = Bukkit.getCurrentTick();
        Integer lastAttempt = lastAttemptTicks.put(actorId, now);
        if (lastAttempt != null && lastAttempt == now) {
            return true;
        }
        Integer last = lastUseTicks.get(actorId);
        if (last != null && now - last < settings.cooldownTicks()) {
            return true;
        }
        ItemStack selected = current(actorId);
        if (selected == null || selected.getType().isAir()) {
            return false;
        }
        if (manager == null || table == null) {
            return false;
        }
        UUID targetId = target.getUniqueId();
        ItemStack snapshot = selected.clone();
        TableGadgetSettings snapshotSettings = settings;
        try {
            manager.runTableNow(table, () -> {
                if (effects.play(table, actorId, targetId, snapshot, snapshotSettings)) {
                    // 只在真正投掷成功时消费冷却与道具快照；失败时效果服务自己保证不留下实体与名额。
                    // 冷却基准沿用玩家 lane 上读到的 tick，避免在 owner lane 上再取一次时钟。
                    lastUseTicks.put(actorId, now);
                    clear(actorId);
                }
            });
        } catch (RuntimeException failure) {
            // 投递失败（例如本轮扫描期间牌桌已被其它路径注销）：不消费冷却与快照，也不拦真实交互。
            reportFailure("桌内道具投掷派发失败", failure);
            return false;
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
        selectedItems.clear();
        lastUseTicks.clear();
        lastAttemptTicks.clear();
        effects.clearAll();
    }

    public void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        selectedItems.remove(playerId);
        selectedSlots.remove(playerId);
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

    /**
     * global lane 上的纯派发入口。
     *
     * <p>IMPORTANT FOLIA: 真实 Folia 冒烟日志证明这里原来整套 tick 直接跑在 global lane 上，于是
     * {@code TableGadgetEffectService.tick} 里的 {@code Bukkit.getCurrentTick()} 抛
     * {@code IllegalStateException("No currently ticking region")}；而调度器在异常时会取消周期任务，
     * 因此道具效果推进与目标高亮在真实 Folia 上一直是静默失效状态。现在本方法只做两件事：
     * 读取桌/座位快照并按 owner lane 派发——效果推进走桌子 owner lane（{@code runTableNow}），
     * 目标高亮走 player lane。这里不得取时钟、不得触碰玩家或实体。
     */
    private void tick() {
        if (stopped) {
            return;
        }
        boolean enabled = settings != null && settings.enabled();
        tickCounter++;
        TableManager manager = plugin.getTableManager();
        if (manager == null) {
            return;
        }
        // 保持原有节奏：目标高亮每 2 tick 扫描一次；配置关闭时不做扫描而是清空。
        boolean sweep = enabled && (tickCounter & 1) == 0;
        Set<UUID> eligible = new HashSet<>();
        for (GameTable table : tables()) {
            // IMPORTANT FOLIA: 每张桌的处理整段包在 try 里。本扫描是 global 周期任务，回调抛异常会被
            // 调度器取消整条任务（MuzScheduler.schedule → managed.fail(repeating=true) 会 cancel 后端任务）；
            // 一旦被取消，所有桌的效果推进与目标高亮都永久失效且无法自愈——这是「投掷卡住」最严重的
            // 形态。所以「派发」「读阶段」「读座位」「投递高亮」任何一步失败都只能限频记录并继续扫描
            // 下一张桌，不得逃出本方法。
            try {
                manager.runTableNow(table, () -> effects.tickTable(table));
                if (!sweep || table.getPhase() != GamePhase.PLAYING) {
                    continue;
                }
                for (UUID seat : table.getSeats()) {
                    Player actor = Bukkit.getPlayer(seat);
                    if (actor == null || !actor.isOnline() || table.isBot(seat)) {
                        continue;
                    }
                    eligible.add(seat);
                    output.runPlayer(seat, online -> updateActorTarget(online, table));
                }
            } catch (IllegalArgumentException ignored) {
                // 本轮扫描期间该桌已被注销；跳过即可，不能让异常把 global 周期任务打挂。
            } catch (RuntimeException failure) {
                // 单桌派发失败仍限频记录，不静默吞掉（项目硬约束）。
                reportFailure("牌桌道具扫描派发失败", failure);
            }
        }
        if (!enabled) {
            // 配置关闭：清掉全部目标状态。清理会改玩家发光，必须回到各自的 player lane。
            for (UUID actorId : new ArrayList<>(targets.keySet())) {
                output.runPlayer(actorId, ignored -> clearPlayerTarget(actorId));
            }
            for (UUID targetId : new ArrayList<>(targetReferences.keySet())) {
                output.runPlayer(targetId, ignored -> releaseTargetCompletely(targetId));
            }
            originalGlowing.clear();
            return;
        }
        if (!sweep) {
            return;
        }
        for (UUID actorId : new ArrayList<>(targets.keySet())) {
            if (!eligible.contains(actorId)) {
                output.runPlayer(actorId, ignored -> clearPlayerTarget(actorId));
            }
        }
    }

    /**
     * 单个 actor 的目标高亮：读视线做射线命中并维护发光引用。
     *
     * <p>IMPORTANT FOLIA: 该方法读玩家视线、改目标发光，属玩家级工作，必须运行在 actor 自己的
     * player lane 上（由 {@link #tick()} 经 {@code output.runPlayer} 投递）；不再跨桌使用全局
     * eligible 集合，actor 变成不合格时由本方法自己撤销其目标状态。
     */
    private void updateActorTarget(Player actor, GameTable table) {
        if (actor == null || table == null) {
            return;
        }
        UUID actorId = actor.getUniqueId();
        if (table.getPhase() != GamePhase.PLAYING || !isEligibleActor(actor)) {
            clearPlayerTarget(actorId);
            return;
        }
        Player target = findTarget(actor, table);
        setTarget(actorId, target == null ? null : target.getUniqueId());
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
        Set<UUID> refs = targetReferences.computeIfAbsent(targetId, ignored -> ConcurrentHashMap.newKeySet());
        if (!refs.add(actorId)) {
            return;
        }
        if (refs.size() == 1) {
            // IMPORTANT FOLIA: 本方法跑在 actor 的 player lane，而发光属于 target 实体；
            // 必须改投到 target 自己的 lane 读写，跨 region 直接读写会抛异常并每 2 tick 刷屏。
            output.runPlayer(targetId, target -> {
                if (!target.isOnline() || !targetReferences.containsKey(targetId)) {
                    return;
                }
                originalGlowing.putIfAbsent(targetId, target.isGlowing());
                if (!target.isGlowing()) {
                    target.setGlowing(true);
                }
            });
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
        // 恢复同样投到 target 自己的 lane：在 actor lane 上写跨 region 实体会失败，
        // 且引用表已清空，失败即意味着 target 永久残留发光。
        output.runPlayer(targetId, target -> {
            if (targetReferences.containsKey(targetId)) {
                return; // 已被重新引用，由新引用负责发光
            }
            Boolean wasGlowing = originalGlowing.remove(targetId);
            if (target.isOnline() && Boolean.FALSE.equals(wasGlowing)) {
                target.setGlowing(false);
            }
        });
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

    private boolean isEligibleActor(Player player) {
        if (player == null || !player.isOnline() || settings == null || !settings.enabled()) {
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

    /**
     * 限频记录失败：同一原因 10 秒最多一条。
     *
     * <p>与 {@code TableGadgetEffectService.reportFailure} 同口径：既不刷屏也不静默吞异常（项目硬约束）。
     */
    private void reportFailure(String message, Throwable failure) {
        long now = System.currentTimeMillis();
        Long last = lastFailureLogMillis.get(message);
        if (last != null && now - last < FAILURE_LOG_INTERVAL_MILLIS) {
            return;
        }
        lastFailureLogMillis.put(message, now);
        plugin.getLogger().log(java.util.logging.Level.WARNING, message + "。", failure);
    }

    private record TargetState(UUID targetId) {
    }
}
