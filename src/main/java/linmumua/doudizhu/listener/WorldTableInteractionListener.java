package linmumua.doudizhu.listener;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.ui.MuzTheme;
import linmumua.doudizhu.room.TableLevel;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class WorldTableInteractionListener implements Listener {
    /* 被方块挡住时的红色高亮颜色 */
    private static final Color BLOCKED_HIGHLIGHT_COLOR = Color.fromRGB(255, 64, 64);

    /* 单次预览最多高亮的被挡方块数量，避免粒子包过多 */
    private static final int MAX_HIGHLIGHTED_BLOCKED_BLOCKS = 8;

    /* 每条棱的粒子采样段数，1 格棱长不需要过密 */
    private static final int BLOCKED_EDGE_SAMPLES = 4;

    private final DoudizhuPlugin plugin;
    private final Map<UUID, TablePlacerPreview> tablePlacerPreviews = new LinkedHashMap<>();
    private final Map<UUID, TableRemoverPreview> tableRemoverPreviews = new LinkedHashMap<>();

    /* 最近一次已消费的按钮点击：玩家 -> [实体, tick]，用于 AT 与 INTERACT 之间去重 */
    private final Map<UUID, ConsumedButtonClick> consumedButtonClicks = new LinkedHashMap<>();

    /** 记录一次已经执行过的按钮点击，供同一次右键的另一个事件识别并跳过。 */
    record ConsumedButtonClick(UUID entityId, int tick) {
    }

    public WorldTableInteractionListener(DoudizhuPlugin plugin) {
        this.plugin = plugin;
        plugin.scheduler().runTimer(1L, 4L, this::tickToolPreviews);
    }

    @EventHandler
    public void onUseTablePlacer(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (!plugin.isTablePlacer(item) && !plugin.isDoudizhuTableRemover(item)) {
            return;
        }
        event.setCancelled(true);
        try {
            if (plugin.isTablePlacer(item)) {
                handleDoudizhuTablePlacer(event.getPlayer(), item, event.getClickedBlock());
            } else {
                handleDoudizhuTableRemover(event.getPlayer());
            }
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "放置桌子失败。" : exception.getMessage();
            plugin.playPlacementBlockedWarning(event.getPlayer());
            event.getPlayer().sendActionBar(MuzTheme.danger(message));
        }
    }

    /**
     * 手牌点击入口 + 桌子方块保护（合并为单一 PlayerInteractEvent 监听，
     * 消除同优先级 HashSet 迭代顺序不确定导致的竞态——见 JavaPluginLoader.createRegisteredListeners）。
     *
     * <p>牌是 ItemDisplay，上面不再挂 Interaction 触发器，所以点牌不产生任何实体事件，
     * 只能从这里的方块/空气事件转进去，由解析拾取判断准星是否落在牌面上。
     *
     * <p>优先级取 LOWEST：手牌判定和方块保护都需要尽早拦截。
     * ignoreCancelled 取默认 false：手牌点击不应被别的插件在同优先级抢先 cancel 掉就失效；
     * 但判不中时完全不碰事件，准星没落在牌上时放方块、挖方块、用其他物品都照常，
     * 不会像旧触发器那样在牌周围留下一圈吞事件的死区。
     * 受保护方块判定在方法体内手动检查 isCancelled()，等价于原独立方法上 ignoreCancelled = true 的语义。
     *
     * <p>方法体内用语句顺序表达确定性优先级：
     * 手牌点击 → 命中就 cancel 并 return → 再做方块保护判定。
     * 这样无论 JVM 如何排列 Method 的 hashCode，行为都一致。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHandCardClick(PlayerInteractEvent event) {
        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR
            || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean leftClick = event.getAction() == Action.LEFT_CLICK_AIR
            || event.getAction() == Action.LEFT_CLICK_BLOCK;

        if (rightClick || leftClick) {
            // 追踪：六个事件入口之一。blocking 实体恒为 null（牌无判定框，
            // 纯 PlayerInteractEvent 路不携带实体），打出来确认路由预期
            plugin.getPhysicalTableManager().traceForListener(event.getPlayer(),
                NamedTextColor.DARK_PURPLE, () ->
                    "入口 PlayerInteractEvent: rightClick=" + rightClick
                    + " blocking=null");
            // 放桌/拆桌棍先行：onUseTablePlacer 不看 isCancelled，这里若抢先取消事件，
            // 同一次右键会既选牌又去放桌子。手持这两把工具时干脆不认手牌点击，
            // 但仍需走下面的方块保护判定。
            if (!plugin.isTablePlacer(event.getItem()) && !plugin.isDoudizhuTableRemover(event.getItem())) {
                // 主手/副手会为同一次右键各发一次事件，去重在 handleHandCardClick 里按 tick 做，
                // 这里不能靠"只认主手"过滤：主手空手时那一次未必发得出来。
                if (plugin.getPhysicalTableManager().handleHandCardClick(event.getPlayer(), rightClick)) {
                    event.setCancelled(true);
                    return; // 命中手牌，不再做方块保护（牌悬浮在桌子上方，准星必然同时落在桌面方块上）
                }
            }
        }

        // 受保护方块判定：只在事件尚未被取消时执行，尊重其他逻辑的取消决定。
        // 手动检查 isCancelled 等价于原来独立方法上 ignoreCancelled = true 的语义。
        // 不限 Action 类型：PHYSICAL（踩压力板）等也要保护桌子方块。
        if (!event.isCancelled()) {
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock != null && plugin.getPhysicalTableManager().isProtectedPlacedBlock(clickedBlock)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (plugin.getPhysicalTableManager().isProtectedPlacedBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * 右键实体入口：手牌仲裁 + 拆桌棍 + 桌子实体保护。
     *
     * <p>不加 ignoreCancelled，和 onHandCardClick、onAttack 对齐，理由同 onHandCardClick 那条原则：
     * 手牌点击不应被别的插件在同优先级抢先 cancel 掉就失效。三条手牌入口口径统一，
     * 别再让其中一条单独挂 ignoreCancelled。
     *
     * <p>取证记录（免得日后又照着错的因果链排查）：曾推断 CraftEngine 会在 LOWEST 取消本事件，
     * 反编译 craft-engine-paper 26.8 全量核对后【证伪】——CE 只有三处碰这两个事件，
     * ItemEventListener.onInteractEntity 是 HIGHEST、ArmorEventListener.onInteractHorse 是 NORMAL，
     * 都排在 LOWEST 之后；BukkitSeatManager.onInteractArmorStand 虽是 LOWEST 但挂在
     * PlayerInteractAtEntityEvent 上且只认 ArmorStand + CE 座位 PDC，我们的判定框是 Interaction，
     * 走不到。所以本方法从来没有被 CE 跳过。
     * 同服 MuChess 的 BoardLabelListener 也取消本事件，但用 TabooLib 缺省 NORMAL，同样在我们之后。
     *
     * <p>那么去掉 ignoreCancelled 是为了什么：它不修任何已知故障，只是把这条入口和另外两条对齐，
     * 让「别的插件在同优先级取消事件」这件事将来真发生时手牌仍然能选。
     * 保护性取消的原有语义改为方法体内手动检查 isCancelled() 保留，行为等价。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEntityEvent event) {
        // 追踪：六个事件入口之一。onAttack 与 onInteractAt 的 tracing 用同一前缀，
        // 但这里 blocking 可能不为 null
        boolean rightClick = true; // INTERACT 入口恒为右键，与 BlockedBy 调用处口径一致
        Entity blocking = event.getRightClicked();
        plugin.getPhysicalTableManager().traceForListener(event.getPlayer(),
            NamedTextColor.DARK_PURPLE, () ->
                "入口 PlayerInteractEntityEvent: rightClick=" + rightClick
                + " blocking=" + (blocking == null ? "null" : blocking.getType().name()));
        // PlayerInteractAtEntityEvent 有自己的 HANDLER_LIST（javap 核对 paper-api 1.21.11 确认），
        // 所以 AT 事件只会进 onInteractAt，不会进这里。这个判断实际是死代码，
        // 留着是一道廉价的防御：万一哪天上游把 HANDLER_LIST 合并回父类，
        // 没有它就会变成同一次右键被仲裁两遍。
        if (event instanceof PlayerInteractAtEntityEvent) {
            return;
        }
        // 手牌仲裁必须排在 handleInteraction 和保护实体取消之前。
        // 牌是 ItemDisplay 没有判定框，客户端的实体射线直接跳过它，命中的是牌【后面】
        // 桌子家具的 CE 判定框。那一路只发实体事件，PlayerInteractEvent 压根不触发，
        // onHandCardClick 就永远不执行；而桌子整棵实体树都是保护实体，
        // 事件最后走到下面的保护判定被静默取消——点牌连提示都没有。
        // 让位判据见 handleHandCardClickBlockedBy：按能否消费点击决定，不比距离。
        // 放桌/拆桌棍握在手里时不认手牌点击，和 onHandCardClick 同口径。
        if (!plugin.isTablePlacer(event.getPlayer().getInventory().getItemInMainHand())
            && !plugin.isDoudizhuTableRemover(event.getPlayer().getInventory().getItemInMainHand())
            && plugin.getPhysicalTableManager()
                .handleHandCardClickBlockedBy(event.getPlayer(), true, event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }
        // 下面三段都是保护性/工具性取消判定，只在事件尚未被取消时执行，尊重别的插件的取消决定。
        // 手动检查 isCancelled 等价于原来方法上 ignoreCancelled = true 的语义；
        // 上面的手牌仲裁刻意排在这道闸门之前，那正是去掉 ignoreCancelled 要救的那一路。
        if (event.isCancelled()) {
            return;
        }
        // 按钮走 handleActionButtonOnce，与 onInteractAt 共用去重，避免同一次右键执行两遍。
        if (handleActionButtonOnce(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getPhysicalTableManager().handleInteraction(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }
        // 准星落在桌子家具上时，右键只会走实体事件，PlayerInteractEvent 根本不触发，
        // 下面的保护判定又会把它拦掉，于是拆桌棍表现为"完全没反应、连提示都没有"。
        // 桌椅几何修正后家具不再陷进地板，准星更容易直接命中家具，这条路径就成了常态。
        // 所以在保护判定之前先认一次拆桌棍，把它转交给和方块路径同一个处理函数。
        if (handleTableRemoverOnEntity(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }
        if (shouldCancelProtectedInteract(event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 手里拿着拆桌棍时，把右键家具当成一次拆桌操作。
     * 只在纯 INTERACT 事件里调用：客户端一次右键会先发 INTERACT_AT 再发 INTERACT，
     * 两处都接就会把"再次右键确认"的两步流程压成一步，直接拆掉。
     * @param player 右键的玩家
     * @return 已经当成拆桌处理时返回 true，调用方需要取消事件
     */
    private boolean handleTableRemoverOnEntity(Player player) {
        if (!plugin.isDoudizhuTableRemover(player.getInventory().getItemInMainHand())) {
            return false;
        }
        try {
            handleDoudizhuTableRemover(player);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "拆桌失败。" : exception.getMessage();
            plugin.playPlacementBlockedWarning(player);
            player.sendActionBar(MuzTheme.danger(message));
        }
        return true;
    }

    /**
     * INTERACT_AT 那一路只做实体保护，不碰按钮逻辑。
     *
     * <p>客户端一次右键实体会先发 INTERACT_AT，再视情况发 INTERACT，服务端构造两个独立事件对象，
     * 各有自己的 HANDLER_LIST（javap 核对 paper-api 1.21.11 确认），于是 AT 进本方法、
     * INTERACT 进 onInteract。
     *
     * <p>按钮不能只挂在 INTERACT 那一路：按钮判定框是 {@code setResponsive(true)} 的
     * Interaction 实体，客户端把 interactAt 视为已消费，后续 INTERACT 包不再发出——
     * 这正是「按钮完全没反应」的成因。因此两路都调 {@link #handleActionButtonOnce}，
     * 由它按「同一实体 + 同一 tick」去重，两个包都到达时也只执行一次。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        // 追踪：六个事件入口之一。AT 与 INTERACT 两条路都打一次，看哪个包先到、
        // 以及 blockedBy 的 blocking 实体分别是什么
        boolean rightClick = true;
        Entity blocking = event.getRightClicked();
        plugin.getPhysicalTableManager().traceForListener(event.getPlayer(),
            NamedTextColor.DARK_PURPLE, () ->
                "入口 PlayerInteractAtEntityEvent: rightClick=" + rightClick
                + " blocking=" + (blocking == null ? "null" : blocking.getType().name()));
        // 手牌仲裁必须和按钮一样在这一路也接一次，成因完全相同：牌后面桌子家具的
        // CE 判定框是 setResponsive(true) 的，客户端把 interactAt 视为已消费，
        // 后续 INTERACT 包不再发出，于是只挂在 onInteract 的手牌仲裁永远等不到，
        // 事件最后走到下面的保护判定被静默取消——表现就是「牌照常高亮却右键选不动」。
        // 悬停不受影响，因为它走 tick 里的解析求交，从不看实体。
        // 重复执行由 handleHandCardClick 内部按「同一玩家 + 同一 tick」去重兜住：
        // 两个包真的都到达时第二次直接返回 true，不会把 toggle 执行两遍。
        if (!plugin.isTablePlacer(event.getPlayer().getInventory().getItemInMainHand())
            && !plugin.isDoudizhuTableRemover(event.getPlayer().getInventory().getItemInMainHand())
            && plugin.getPhysicalTableManager()
                .handleHandCardClickBlockedBy(event.getPlayer(), true, event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }
        // 按钮必须在这一路也处理：按钮的判定框是 setResponsive(true) 的 Interaction 实体，
        // 客户端把 interactAt 当成已消费，于是后续那个 INTERACT 包根本不会发出，
        // 只挂在 onInteract 的 handleInteraction 永远等不到。
        // 靠 consumedButtonClicks 去重，两个包真的都到达时也只执行一次。
        if (handleActionButtonOnce(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }
        if (shouldCancelProtectedInteract(event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 把一次右键按钮转交 {@code handleInteraction}，并保证同一次右键只执行一次。
     *
     * <p>AT 与 INTERACT 两个包都可能到达（取决于客户端与实体的 responsive 设置），
     * 而 {@code handleInteraction} 内部没有去重。执行两遍的后果已取证：
     * READY / DOUBLE_* 这类 toggle 切两次等于没切（玩家看到「点了没反应」），
     * BID_n / JOIN / PLAY_SELECTED 第二遍会抛异常变成红字提示。
     *
     * @param player 右键的玩家
     * @param entity 被右键的实体
     * @return 已作为按钮消费时返回 true，调用方需要取消事件
     */
    private boolean handleActionButtonOnce(Player player, Entity entity) {
        if (!plugin.getPhysicalTableManager().isActionButtonEntity(entity.getUniqueId())) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        int currentTick = Bukkit.getCurrentTick();
        if (isDuplicateButtonClick(consumedButtonClicks.get(playerId), entity.getUniqueId(), currentTick)) {
            // 同一次右键的第二个包：已经执行过了，只需继续把事件拦下。
            return true;
        }
        consumedButtonClicks.put(playerId, new ConsumedButtonClick(entity.getUniqueId(), currentTick));
        return plugin.getPhysicalTableManager().handleInteraction(player, entity);
    }

    /**
     * 判断这次按钮点击是否是同一次右键的重复投递。
     *
     * <p>判据是「同一实体 + 同一 tick」：客户端一次右键产生的 AT 与 INTERACT 必然落在同一 tick，
     * 而玩家手速再快也不可能在同一 tick 内点两次。
     *
     * @param previous 该玩家最近一次已消费的按钮点击，没有则为 null
     * @param entityId 本次点击的实体
     * @param currentTick 当前 tick
     * @return 属于重复投递时返回 true
     */
    static boolean isDuplicateButtonClick(ConsumedButtonClick previous, UUID entityId, int currentTick) {
        return previous != null
            && previous.tick() == currentTick
            && previous.entityId().equals(entityId);
    }

    private boolean shouldCancelProtectedInteract(UUID entityId) {
        return shouldCancelProtectedInteract(
            plugin.getPhysicalTableManager().isProtectedEntity(entityId),
            plugin.getPhysicalTableManager().isChairFurnitureEntity(entityId),
            plugin.getPhysicalTableManager().isActionButtonEntity(entityId)
        );
    }

    /**
     * 判断右键保护实体是否需要拦下。
     *
     * <p>两个放行口，都对应已取证的故障：
     * <ul>
     *   <li><b>椅子家具</b>——不放行，CraftEngine 在 LOWEST 优先级就收不到事件，玩家坐不上去；</li>
     *   <li><b>按钮</b>——不放行，按钮完全没反应。客户端一次右键先发 INTERACT_AT 再发 INTERACT，
     *       AT 在这里被取消后 INTERACT 不再送达，而 {@code handleInteraction} 为避免同一次右键
     *       被执行两遍只挂在 INTERACT 那一路，于是唯一的出路也被堵死。</li>
     * </ul>
     *
     * @param protectedEntity 是否属于牌桌保护实体
     * @param chairFurniture 是否属于椅子家具
     * @param actionButton 是否是绑着动作的按钮
     * @return 需要拦下时返回 true
     */
    static boolean shouldCancelProtectedInteract(
        boolean protectedEntity,
        boolean chairFurniture,
        boolean actionButton
    ) {
        if (!protectedEntity) {
            return false;
        }
        return !chairFurniture && !actionButton;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        // 左键出牌主路走 PlayerInteractEvent → onHandCardClick：牌是 ItemDisplay 没有判定框，
        // 客户端选不中它，正常情况下左键会落到牌后面的桌面方块上。
        // 但牌后面的桌子家具带 CE 判定框且 setResponsive(true)，实体射线命中它时
        // 左键只发这个攻击事件，PlayerInteractEvent 压根不触发。
        // 不在这里补一次，那些角度下就永远出不了牌（悬停却照常高亮，因为悬停不看实体）。
        Player damager = (Player) event.getDamager();
        // 追踪：六个事件入口之一。onAttack 是唯一走左键的入口
        boolean rightClick = false;
        Entity blocking = event.getEntity();
        plugin.getPhysicalTableManager().traceForListener(damager,
            NamedTextColor.DARK_PURPLE, () ->
                "入口 EntityDamageByEntityEvent: rightClick=" + rightClick
                + " blocking=" + (blocking == null ? "null" : blocking.getType().name()));
        if (!plugin.isTablePlacer(damager.getInventory().getItemInMainHand())
            && !plugin.isDoudizhuTableRemover(damager.getInventory().getItemInMainHand())
            && plugin.getPhysicalTableManager()
                .handleHandCardClickBlockedBy(damager, false, event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        if (plugin.getPhysicalTableManager().isProtectedEntity(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (plugin.getPhysicalTableManager().isProtectedEntity(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (plugin.getPhysicalTableManager().isProtectedEntity(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (plugin.getPhysicalTableManager().isProtectedEntity(event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.getPhysicalTableManager().isProtectedPlacedBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block ->
            plugin.getPhysicalTableManager().isProtectedPlacedBlock(block)
        );
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block ->
            plugin.getPhysicalTableManager().isProtectedPlacedBlock(block)
        );
    }

    private void handleDoudizhuTablePlacer(Player player, ItemStack item, Block clickedBlock) {
        if (plugin.getTableManager().getTableOf(player) != null) {
            throw new IllegalStateException("你已经在牌桌里了，先离桌再放新的。");
        }
        DoudizhuPlugin.TableMode mode = plugin.tablePlacerMode(item);
        if (mode == null) {
            throw new IllegalStateException("这个放桌器没写玩法，用不了。");
        }
        Block floor = resolvePlacementFloor(player, clickedBlock);
        if (floor == null || !floor.getType().isSolid()) {
            throw new IllegalStateException("对着地面右键，先看看桌椅摆哪。");
        }
        String tableId = plugin.doudizhuTablePlacerId(item);
        TableLevel level = plugin.doudizhuTablePlacerLevel(item);
        int maxPlayers = 10;
        Location anchor = plugin.getPhysicalTableManager().placementAnchor(floor);
        float yaw = plugin.getPhysicalTableManager().placementYaw(player);
        String obstruction = plugin.getPhysicalTableManager().placementObstructionReason(anchor, yaw);
        long now = System.currentTimeMillis();
        if (obstruction != null) {
            // 被挡时保留一个只用于红色高亮的预览，让玩家看清是哪些方块挡住了放桌位置。
            TablePlacerPreview blockedPreview = new TablePlacerPreview(
                mode,
                tableId,
                level,
                maxPlayers,
                anchor.clone(),
                yaw,
                now + 5000L,
                true
            );
            tablePlacerPreviews.put(player.getUniqueId(), blockedPreview);
            spawnTablePlacerPreview(player, blockedPreview);
            throw new IllegalStateException(obstruction);
        }
        TablePlacerPreview previous = tablePlacerPreviews.get(player.getUniqueId());
        // HARD-CODED TABLE PLACER FLOW:
        // Right click once = particle preview.
        // Right click again on the same preview = actual placement.
        // Keep this two-step flow intact unless the user explicitly asks to change the interaction contract.
        if (matchesExistingPreview(previous, mode, anchor, yaw, tableId, level, now)) {
            plugin.getPhysicalTableManager().placeNewTableAt(player, tableId, level, anchor, yaw);
            consumeMainHand(player);
            tablePlacerPreviews.remove(player.getUniqueId());
            ensurePlayerHasTableRemover(player, mode, tableId);
            player.sendActionBar(MuzTheme.success("已放置 " + tableId + " 号桌"));
            return;
        }
        tablePlacerPreviews.put(player.getUniqueId(), new TablePlacerPreview(mode, tableId, level, maxPlayers, anchor.clone(), yaw, now + 5000L, false));
        spawnTablePlacerPreview(player, tablePlacerPreviews.get(player.getUniqueId()));
        player.sendActionBar(MuzTheme.warning("已预览 " + tableId + " 号桌，再次右键放置"));
    }

    private void handleDoudizhuTableRemover(Player player) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        DoudizhuPlugin.TableMode boundMode = plugin.tableRemoverMode(tool);
        String boundTableId = plugin.tableRemoverId(tool);
        if (boundMode == null || boundTableId.isBlank()) {
            tableRemoverPreviews.remove(player.getUniqueId());
            throw new IllegalStateException("这根拆桌棍缺少绑定的牌桌信息。");
        }
        RemovalTarget target = findRemovalTarget(player);
        if (target == null) {
            tableRemoverPreviews.remove(player.getUniqueId());
            throw new IllegalStateException("请把准星对准 " + boundTableId + " 号桌。");
        }
        if (target.mode() != boundMode || !target.tableName().equalsIgnoreCase(boundTableId)) {
            tableRemoverPreviews.remove(player.getUniqueId());
            throw new IllegalStateException("这根拆桌棍只能拆 " + boundTableId + " 号桌。");
        }
        if (target.mode() == DoudizhuPlugin.TableMode.DOUDIZHU
            && !plugin.getPhysicalTableManager().canRemoveTable(player, target.tableName())) {
            tableRemoverPreviews.remove(player.getUniqueId());
            throw new IllegalStateException(plugin.getPhysicalTableManager().removeDeniedReason(player, target.tableName()));
        }
        long now = System.currentTimeMillis();
        TableRemoverPreview previous = tableRemoverPreviews.get(player.getUniqueId());
        if (previous != null
            && previous.mode() == target.mode()
            && previous.tableName().equalsIgnoreCase(target.tableName())
            && now <= previous.expiresAtMillis()) {
            consumeRemover(player);
            linmumua.doudizhu.game.GameTable table = plugin.getTableManager().getTable(target.tableName());
            TableLevel level = table == null ? TableLevel.FUN : table.getRoomLevel();
            if (table != null) {
                table.forceClose("玩家 " + player.getName() + " 拆掉了牌桌 " + target.tableName() + "。");
            }
            plugin.getPhysicalTableManager().removeTable(target.tableName());
            givePlacerBack(player, DoudizhuPlugin.TableMode.DOUDIZHU, target.tableName(), level);
            tableRemoverPreviews.remove(player.getUniqueId());
            player.sendActionBar(MuzTheme.success("已拆掉 " + target.tableName() + " 号桌"));
            return;
        }
        tableRemoverPreviews.put(player.getUniqueId(), new TableRemoverPreview(target.mode(), target.tableName(), now + 5000L));
        spawnTableRemoverPreview(player, target);
        player.sendActionBar(MuzTheme.warning("已选中 " + target.tableName() + " 号桌，再次右键拆掉"));
    }

    private Block resolvePlacementFloor(Player player, Block clickedBlock) {
        if (clickedBlock != null && clickedBlock.getType().isSolid()) {
            return clickedBlock;
        }
        Block target = player.getTargetBlockExact(8);
        if (target != null && target.getType().isSolid()) {
            return target;
        }
        Block fallback = player.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
        return fallback.getType().isSolid() ? fallback : null;
    }

    private boolean matchesExistingPreview(TablePlacerPreview preview, DoudizhuPlugin.TableMode mode, Location anchor, float yaw, String tableId, TableLevel level, long now) {
        if (preview == null || preview.blocked() || now > preview.expiresAtMillis()) {
            return false;
        }
        return preview.mode() == mode
            && preview.tableId().equalsIgnoreCase(tableId)
            && preview.level() == level
            && preview.anchor().getWorld().equals(anchor.getWorld())
            && preview.anchor().distanceSquared(anchor) < 0.0004
            && Math.abs(preview.yaw() - yaw) < 0.01f;
    }

    private void tickToolPreviews() {
        tickTablePlacerPreviews();
        tickTableRemoverPreviews();
    }

    private void tickTablePlacerPreviews() {
        Iterator<Map.Entry<UUID, TablePlacerPreview>> iterator = tablePlacerPreviews.entrySet().iterator();
        long now = System.currentTimeMillis();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TablePlacerPreview> entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || now > entry.getValue().expiresAtMillis()) {
                iterator.remove();
                continue;
            }
            if (!plugin.isTablePlacer(player.getInventory().getItemInMainHand())) {
                iterator.remove();
                continue;
            }
            spawnTablePlacerPreview(player, entry.getValue());
        }
    }

    private void tickTableRemoverPreviews() {
        Iterator<Map.Entry<UUID, TableRemoverPreview>> iterator = tableRemoverPreviews.entrySet().iterator();
        long now = System.currentTimeMillis();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TableRemoverPreview> entry = iterator.next();
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || now > entry.getValue().expiresAtMillis()) {
                iterator.remove();
                continue;
            }
            if (!plugin.isDoudizhuTableRemover(player.getInventory().getItemInMainHand())) {
                iterator.remove();
                continue;
            }
            if (plugin.tableRemoverMode(player.getInventory().getItemInMainHand()) != entry.getValue().mode()
                || !entry.getValue().tableName().equalsIgnoreCase(plugin.tableRemoverId(player.getInventory().getItemInMainHand()))) {
                iterator.remove();
                continue;
            }
            RemovalTarget targeted = findRemovalTarget(player);
            if (targeted == null
                || targeted.mode() != entry.getValue().mode()
                || !targeted.tableName().equalsIgnoreCase(entry.getValue().tableName())) {
                iterator.remove();
                continue;
            }
            spawnTableRemoverPreview(player, targeted);
        }
    }

    private void spawnTablePlacerPreview(Player player, TablePlacerPreview preview) {
        List<Location> blockedBlocks = plugin.getPhysicalTableManager()
            .placementBlockedBlocks(preview.anchor(), preview.yaw());
        if (!preview.blocked()) {
            Location tableCenter = plugin.getPhysicalTableManager().previewTableCenter(preview.anchor());
            drawRing(player, tableCenter, 0.90, Color.fromRGB(255, 208, 92));
            for (Location seat : plugin.getPhysicalTableManager().previewChairBases(preview.anchor(), preview.yaw())) {
                drawRing(player, seat.clone().add(0.0, 0.08, 0.0), 0.34, Color.fromRGB(110, 210, 255));
            }
            drawLine(player, tableCenter.clone().add(0.0, 0.05, 0.0), plugin.getPhysicalTableManager().previewOpenSide(preview.anchor(), preview.yaw()).clone().add(0.0, 0.05, 0.0), Color.fromRGB(135, 255, 165));
        }
        drawBlockedBlocks(player, blockedBlocks);
    }

    private void spawnTableRemoverPreview(Player player, RemovalTarget target) {
        Location anchor = plugin.getPhysicalTableManager().tableAnchor(target.tableName());
        if (anchor == null) {
            return;
        }
        float yaw = plugin.getPhysicalTableManager().tableYaw(target.tableName());
        Location tableCenter = plugin.getPhysicalTableManager().previewTableCenter(anchor);
        drawRing(player, tableCenter, 0.96, Color.fromRGB(255, 106, 136));
        for (Location seat : plugin.getPhysicalTableManager().previewChairBases(anchor, yaw)) {
            drawRing(player, seat.clone().add(0.0, 0.08, 0.0), 0.38, Color.fromRGB(255, 176, 104));
        }
        drawLine(player, tableCenter.clone().add(0.0, 0.08, 0.0), player.getEyeLocation(), Color.fromRGB(255, 215, 120));
    }

    private void drawBlockedBlocks(Player player, List<Location> blockedBlocks) {
        int drawn = 0;
        for (Location blockedBlock : blockedBlocks) {
            if (drawn >= MAX_HIGHLIGHTED_BLOCKED_BLOCKS) {
                return;
            }
            if (!player.getWorld().equals(blockedBlock.getWorld())) {
                continue;
            }
            drawBlockOutline(player, blockedBlock, BLOCKED_HIGHLIGHT_COLOR);
            drawn++;
        }
    }

    private void drawBlockOutline(Player player, Location blockCorner, Color color) {
        World world = blockCorner.getWorld();
        if (world == null) {
            return;
        }
        double minX = blockCorner.getBlockX();
        double minY = blockCorner.getBlockY();
        double minZ = blockCorner.getBlockZ();
        double maxX = minX + 1.0;
        double maxY = minY + 1.0;
        double maxZ = minZ + 1.0;
        for (double y : new double[] {minY, maxY}) {
            drawEdge(player, world, minX, y, minZ, maxX, y, minZ, color);
            drawEdge(player, world, minX, y, maxZ, maxX, y, maxZ, color);
            drawEdge(player, world, minX, y, minZ, minX, y, maxZ, color);
            drawEdge(player, world, maxX, y, minZ, maxX, y, maxZ, color);
        }
        drawEdge(player, world, minX, minY, minZ, minX, maxY, minZ, color);
        drawEdge(player, world, maxX, minY, minZ, maxX, maxY, minZ, color);
        drawEdge(player, world, minX, minY, maxZ, minX, maxY, maxZ, color);
        drawEdge(player, world, maxX, minY, maxZ, maxX, maxY, maxZ, color);
    }

    private void drawEdge(
        Player player,
        World world,
        double fromX,
        double fromY,
        double fromZ,
        double toX,
        double toY,
        double toZ,
        Color color
    ) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 0.7f);
        for (int index = 0; index <= BLOCKED_EDGE_SAMPLES; index++) {
            double progress = (double) index / BLOCKED_EDGE_SAMPLES;
            player.spawnParticle(
                Particle.DUST,
                new Location(
                    world,
                    fromX + (toX - fromX) * progress,
                    fromY + (toY - fromY) * progress,
                    fromZ + (toZ - fromZ) * progress
                ),
                1,
                0.0,
                0.0,
                0.0,
                0.0,
                dust
            );
        }
    }

    private void drawRing(Player player, Location center, double radius, Color color) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);
        for (int index = 0; index < 16; index++) {
            double angle = (Math.PI * 2.0 * index) / 16.0;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
            player.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    private void drawLine(Player player, Location from, Location to, Color color) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.0f);
        for (int index = 0; index <= 10; index++) {
            double progress = index / 10.0;
            Location point = from.clone().add(
                (to.getX() - from.getX()) * progress,
                (to.getY() - from.getY()) * progress,
                (to.getZ() - from.getZ()) * progress
            );
            player.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, dust);
        }
    }

    private void consumeMainHand(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!plugin.isTablePlacer(main)) {
            return;
        }
        if (main.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        main.setAmount(main.getAmount() - 1);
        player.getInventory().setItemInMainHand(main);
    }

    private void consumeRemover(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (!plugin.isDoudizhuTableRemover(main)) {
            return;
        }
        if (main.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        main.setAmount(main.getAmount() - 1);
        player.getInventory().setItemInMainHand(main);
    }

    private void ensurePlayerHasTableRemover(Player player) {
        if (player == null) {
            return;
        }
    }

    private void ensurePlayerHasTableRemover(Player player, DoudizhuPlugin.TableMode mode, String tableId) {
        if (player == null || playerHasTableRemover(player, mode, tableId)) {
            return;
        }
        ItemStack remover = plugin.createTableRemoverItem(mode, tableId);
        java.util.HashMap<Integer, ItemStack> rejected = player.getInventory().addItem(remover);
        if (!rejected.isEmpty()) {
            rejected.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
    }

    private boolean playerHasTableRemover(Player player, DoudizhuPlugin.TableMode mode, String tableId) {
        for (ItemStack itemStack : player.getInventory().getContents()) {
            if (plugin.isDoudizhuTableRemover(itemStack)
                && plugin.tableRemoverMode(itemStack) == mode
                && tableId.equalsIgnoreCase(plugin.tableRemoverId(itemStack))) {
                return true;
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        return plugin.isDoudizhuTableRemover(offhand)
            && plugin.tableRemoverMode(offhand) == mode
            && tableId.equalsIgnoreCase(plugin.tableRemoverId(offhand));
    }

    private void givePlacerBack(Player player, DoudizhuPlugin.TableMode mode, String tableId, TableLevel level) {
        ItemStack placer = plugin.createTablePlacerItem(mode, tableId, level);
        java.util.HashMap<Integer, ItemStack> rejected = player.getInventory().addItem(placer);
        if (!rejected.isEmpty()) {
            rejected.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
        }
    }

    private RemovalTarget findRemovalTarget(Player player) {
        String doudizhu = plugin.getPhysicalTableManager().targetedTable(player, 10.0);
        if (doudizhu != null) {
            return new RemovalTarget(DoudizhuPlugin.TableMode.DOUDIZHU, doudizhu);
        }
        return null;
    }

    private record TablePlacerPreview(DoudizhuPlugin.TableMode mode, String tableId, TableLevel level, int maxPlayers, Location anchor, float yaw, long expiresAtMillis, boolean blocked) {
    }

    private record TableRemoverPreview(DoudizhuPlugin.TableMode mode, String tableName, long expiresAtMillis) {
    }

    private record RemovalTarget(DoudizhuPlugin.TableMode mode, String tableName) {
    }
}

