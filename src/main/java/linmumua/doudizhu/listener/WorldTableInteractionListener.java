package linmumua.doudizhu.listener;

import linmumua.doudizhu.DoudizhuPlugin;
import linmumua.doudizhu.ui.MuzTheme;
import linmumua.doudizhu.room.TableLevel;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import net.kyori.adventure.text.Component;
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProtectedBlockInteract(PlayerInteractEvent event) {
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) {
            return;
        }
        if (plugin.getPhysicalTableManager().isProtectedPlacedBlock(clickedBlock)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (plugin.getPhysicalTableManager().isProtectedPlacedBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (plugin.getPhysicalTableManager().handleInteraction(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
            return;
        }
        if (shouldCancelProtectedInteract(event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (shouldCancelProtectedInteract(event.getRightClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private boolean shouldCancelProtectedInteract(UUID entityId) {
        return shouldCancelProtectedInteract(
            plugin.getPhysicalTableManager().isProtectedEntity(entityId),
            plugin.getPhysicalTableManager().isChairFurnitureEntity(entityId)
        );
    }

    /**
     * 判断右键保护实体是否需要拦下
     * 椅子家具必须放行，否则 CraftEngine 在 LOWEST 优先级就收不到事件，玩家坐不上去。
     * @param protectedEntity 是否属于牌桌保护实体
     * @param chairFurniture 是否属于椅子家具
     * @return 需要拦下时返回 true
     */
    static boolean shouldCancelProtectedInteract(boolean protectedEntity, boolean chairFurniture) {
        if (!protectedEntity) {
            return false;
        }
        return !chairFurniture;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (plugin.getPhysicalTableManager().handleAttack(player, event.getEntity())
            || plugin.getPhysicalTableManager().isProtectedEntity(event.getEntity().getUniqueId())) {
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

