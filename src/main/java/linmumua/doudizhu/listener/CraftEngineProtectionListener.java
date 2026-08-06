package linmumua.doudizhu.listener;

import linmumua.doudizhu.DoudizhuPlugin;
import net.momirealms.craftengine.bukkit.api.event.CustomBlockBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureBreakEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureHitEvent;
import net.momirealms.craftengine.bukkit.api.event.FurnitureInteractEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import java.util.function.Supplier;
import net.kyori.adventure.text.format.NamedTextColor;

public final class CraftEngineProtectionListener implements Listener {
    private final DoudizhuPlugin plugin;

    public CraftEngineProtectionListener(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFurnitureBreak(FurnitureBreakEvent event) {
        Entity entity = event.furniture().bukkitEntity();
        if (entity == null) {
            return;
        }
        if (plugin.getPhysicalTableManager().isProtectedEntity(entity.getUniqueId())) {
            event.setDropItems(false);
            event.setCancelled(true);
        }
    }

    /**
     * 右键 CE 家具：把落在手牌上的那一次点击抢回来。
     *
     * <p>事件拓扑（反编译 craft-engine-paper 26.8 + 核对生成的 furniture.yml 得出，
     * 这三条决定了每种点击走哪条路，改动判定框配置前先回来看这里）：
     * <ul>
     *   <li><b>桌子</b>：四个 {@code type: shulker}，<b>没有</b> {@code interaction_entity}。
     *       shulker 判定框是发包伪实体，服务端无对应 Bukkit 实体，原版实体事件根本不产生。
     *       所以本事件是点桌子（也就是点牌，射线穿过无判定框的牌命中桌子）<b>唯一</b>的路。</li>
     *   <li><b>椅子</b>：shulker + {@code interaction_entity: true}，CE 额外挂真实 Interaction 实体，
     *       于是原版事件<b>也</b>会到。两条路都会走到手牌仲裁，而椅子在
     *       isChairFurnitureEntity 里判定为真、yieldsToBlockingEntity 让位，
     *       两条路给出同一个裁决（不接手），椅子照样能坐。</li>
     *   <li><b>按钮</b>：MUZ 自己 spawn 的 Interaction 实体，是真实体，只走原版那条。</li>
     * </ul>
     *
     * <p>椅子那条「两条路都到」意味着 handleHandCardClick 里的同 tick 去重
     * 现在还兼任跨路径防重：先到的那条 toggle，后到的只取消事件不再切一次。
     *
     * <p>为什么手牌仲裁必须挂在这里，而不是 PlayerInteractEntityEvent 上。
     * 桌子家具的判定框在 furniture.yml 里配的是 {@code type: shulker}，
     * 而 CE 的 ShulkerFurnitureHitbox 字段只有 {@code spawnPacket / despawnPacket / int[] entityIds}
     * ——它是靠发包在客户端造出来的伪实体，服务端不存在对应的 Bukkit 实体。
     * 客户端命中它时发来的 entityId 在服务端查不到实体，原版那条链根本不会构造
     * PlayerInteractEntityEvent；CE 的 network InteractListener 在包层截下来，
     * 自己 fire 这个 FurnitureInteractEvent。
     * 所以挂在 PlayerInteractEntityEvent 上的仲裁不是"被别的插件取消了"，
     * 而是那条路上压根没有事件送到面前。这是右键选牌一直没作用的真正原因。
     *
     * <p>牌是 ItemDisplay 没有判定框，客户端的实体射线直接穿过它命中后面桌子的 shulker 判定框，
     * 于是每次点牌都变成"点桌子"。这里把点击转交给同一套解析拾取与让位判据：
     * 命中牌就取消事件、由手牌接手；没命中就放行，桌子该有的交互一个不少。
     *
     * <p>优先级 LOWEST 且不加 ignoreCancelled，与 WorldTableInteractionListener 里
     * 三条手牌入口同口径：手牌点击不应被别的插件在同优先级抢先取消掉就失效。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFurnitureInteract(FurnitureInteractEvent event) {
        // 追踪：六个事件入口之一。CE 家具路 blocking 取自 event.furniture().bukkitEntity()
        org.bukkit.entity.Player player = event.player();
        Entity blocking = event.furniture().bukkitEntity();
        boolean rightClick = true;
        plugin.getPhysicalTableManager().traceForListener(player,
            NamedTextColor.DARK_PURPLE, () ->
                "入口 FurnitureInteractEvent: rightClick=" + rightClick
                + " blocking=" + (blocking == null ? "null" : blocking.getType().name()));
        if (handleHandCardClickOnFurniture(player, true, blocking)) {
            event.setCancelled(true);
        }
    }

    /**
     * 左键 CE 家具：出牌那一次点击同样要抢回来。
     *
     * <p>成因与右键完全一致（见 onFurnitureInteract）：shulker 判定框是发包伪实体，
     * 左键命中它时服务端不会产生 EntityDamageByEntityEvent，
     * WorldTableInteractionListener.onAttack 那条链收不到，所以出牌也走不通。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onFurnitureHit(FurnitureHitEvent event) {
        // 追踪：六个事件入口之一。left click on CE furniture
        org.bukkit.entity.Player player = event.player();
        Entity blocking = event.furniture().bukkitEntity();
        boolean rightClick = false;
        plugin.getPhysicalTableManager().traceForListener(player,
            NamedTextColor.DARK_PURPLE, () ->
                "入口 FurnitureHitEvent: rightClick=" + rightClick
                + " blocking=" + (blocking == null ? "null" : blocking.getType().name()));
        if (handleHandCardClickOnFurniture(player, false, blocking)) {
            event.setCancelled(true);
        }
    }

    /**
     * 把 CE 家具上的一次点击交给手牌仲裁。
     *
     * <p>复用 handleHandCardClickBlockedBy 而不是另写一套：让位判据、拾取几何、
     * 同 tick 去重都在那一份实现里，两条路径必须给出同一个裁决，
     * 否则「点同一个位置，走哪条事件路结果不同」会变成极难复现的偶发问题。
     *
     * @param player   点击的玩家
     * @param rightClick true 为右键选牌，false 为左键出牌
     * @param base     家具的基座实体，CE 侧可能为 null
     * @return 手牌接手了这次点击时返回 true，调用方需要取消事件
     */
    private boolean handleHandCardClickOnFurniture(
        org.bukkit.entity.Player player, boolean rightClick, Entity base) {
        if (player == null || base == null) {
            return false;
        }
        // 放桌/拆桌棍握在手里时不认手牌点击，和另外三条入口同口径。
        if (plugin.isTablePlacer(player.getInventory().getItemInMainHand())
            || plugin.isDoudizhuTableRemover(player.getInventory().getItemInMainHand())) {
            return false;
        }
        return plugin.getPhysicalTableManager()
            .handleHandCardClickBlockedBy(player, rightClick, base);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCustomBlockBreak(CustomBlockBreakEvent event) {
        if (plugin.getPhysicalTableManager().isProtectedPlacedBlock(event.bukkitBlock())) {
            event.setDropItems(false);
            event.setCancelled(true);
        }
    }
}
