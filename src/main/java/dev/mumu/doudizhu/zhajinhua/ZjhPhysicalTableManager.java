package dev.mumu.doudizhu.zhajinhua;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.assets.PackAssets;
import dev.mumu.doudizhu.compat.VersionCompat;
import dev.mumu.doudizhu.game.GameTable;
import dev.mumu.doudizhu.model.DoudizhuCard;
import dev.mumu.doudizhu.room.TableLevel;
import dev.mumu.doudizhu.ui.MuzTheme;
import dev.mumu.doudizhu.ui.TypewriterTextStyle;
import dev.mumu.doudizhu.world.PlacementObstruction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class ZjhPhysicalTableManager {
    private static final String PROTECTED_ENTITY_TAG = "muz_table_protected";
    private static final float TABLE_SCALE = 2.25f;
    private static final float CHAIR_SCALE = 1.35f;
    private static final float CARD_SCALE = 0.50f;
    private static final float TEXT_SCALE = 0.46f;
    private static final float STATUS_SCALE = 0.72f;
    private static final float BUTTON_SCALE = 0.42f;
    private static final int BUTTON_HOVER_GRACE_TICKS = 2;
    private final DoudizhuPlugin plugin;
    private final Map<String, PlacedTable> placedTables = new LinkedHashMap<>();
    private final Map<UUID, JoinBinding> joinBindings = new LinkedHashMap<>();
    private final Map<UUID, SeatActionBinding> seatActionBindings = new LinkedHashMap<>();
    private final Map<UUID, UUID> buttonDisplayByBinding = new LinkedHashMap<>();
    private final Map<UUID, Location> buttonBaseLocationByDisplay = new LinkedHashMap<>();
    private final Map<UUID, UUID> hoveredDisplayByViewer = new LinkedHashMap<>();
    private final Map<UUID, Integer> buttonHoverGraceTicksByViewer = new LinkedHashMap<>();
    private final Map<UUID, Float> buttonHoverProgressByDisplay = new LinkedHashMap<>();

    public ZjhPhysicalTableManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public ZjhTable placeNewTable(org.bukkit.entity.Player owner, String name, int maxPlayers) {
        return placeNewTable(owner, name, maxPlayers, TableLevel.FUN);
    }

    public ZjhTable placeNewTableAt(org.bukkit.entity.Player owner, String name, int maxPlayers, TableLevel roomLevel, Location anchor, float yaw) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            throw new IllegalArgumentException("这个德州扑克牌桌已经有实体桌面了。");
        }
        ZjhTable table = plugin.getZjhManager().getTable(name);
        if (table == null) {
            table = plugin.getZjhManager().createTable(name, maxPlayers, roomLevel);
        }
        placedTables.put(key, spawnTable(table, anchor, yaw, owner.getUniqueId(), owner.getName()));
        plugin.persistTexasTable(table.getName(), table.getMaxPlayers(), table.getRoomLevel(), anchor, yaw, owner.getUniqueId(), owner.getName());
        refresh(table);
        return table;
    }

    public float placementYaw(org.bukkit.entity.Player owner) {
        return snappedYaw(owner.getLocation().getYaw());
    }

    public Location placementAnchor(org.bukkit.block.Block floorBlock) {
        return floorBlock.getLocation().add(0.5, plugin.getTableSpawnOffsetY(), 0.5);
    }

    public Location previewTableCenter(Location anchor) {
        return anchor.clone().add(0.0, plugin.getTableDisplayHeight(), 0.0);
    }

    public List<Location> previewSeatBases(Location anchor, float yaw, int maxPlayers) {
        List<Location> seats = new ArrayList<>();
        for (double[] offset : seatOffsets(maxPlayers)) {
            seats.add(rotate(anchor, yaw, offset[0], plugin.getChairBaseHeight(), offset[1]));
        }
        return seats;
    }

    public ZjhTable placeNewTable(org.bukkit.entity.Player owner, String name, int maxPlayers, TableLevel roomLevel) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            throw new IllegalArgumentException("这个德州扑克牌桌已经有实体桌面了。");
        }
        ZjhTable table = plugin.getZjhManager().getTable(name);
        if (table == null) {
            table = plugin.getZjhManager().createTable(name, maxPlayers, roomLevel);
        }
        Location anchor = plugin.defaultTableAnchor(owner);
        float snappedYaw = snappedYaw(owner.getLocation().getYaw());
        placedTables.put(key, spawnTable(table, anchor, snappedYaw, owner.getUniqueId(), owner.getName()));
        plugin.persistTexasTable(table.getName(), table.getMaxPlayers(), table.getRoomLevel(), anchor, snappedYaw, owner.getUniqueId(), owner.getName());
        refresh(table);
        return table;
    }

    public ZjhTable restoreTable(String name, int maxPlayers, TableLevel roomLevel, Location anchor, float yaw, UUID ownerId, String ownerName) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            return plugin.getZjhManager().getTable(name);
        }
        ensureWorldVisualsReady("恢复牌桌");
        ensureChunkReady(anchor);
        purgeResidualWorldArtifacts(anchor, yaw, maxPlayers);
        ZjhTable table = plugin.getZjhManager().getTable(name);
        if (table == null) {
            table = plugin.getZjhManager().createTable(name, maxPlayers, roomLevel);
        } else {
            table.setRoomLevel(roomLevel);
        }
        placedTables.put(key, spawnTable(table, anchor, yaw, ownerId, ownerName));
        refresh(table);
        return table;
    }

    public void forceRemoveTable(String tableName) {
        PlacedTable placed = placedTables.remove(normalize(tableName));
        if (placed != null) {
            cleanupPlacedTable(placed);
            purgeResidualWorldArtifacts(placed.anchor, placed.yaw, Math.max(2, placed.seatLabelIds.size()));
        }
        plugin.getZjhManager().unregisterTable(tableName);
        plugin.deletePersistedTable("TEXAS", tableName);
    }

    public boolean isPlaced(String tableName) {
        return placedTables.containsKey(normalize(tableName));
    }

    public int placedTableCount() {
        return placedTables.size();
    }

    public List<String> placedTableNames() {
        return placedTables.keySet().stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public void rebuildAllTables() {
        if (!canSafelyReplaceWorldVisuals()) {
            return;
        }
        Map<String, PlacedTable> snapshot = new LinkedHashMap<>(placedTables);
        placedTables.clear();
        for (Map.Entry<String, PlacedTable> entry : snapshot.entrySet()) {
            PlacedTable previous = entry.getValue();
            cleanupPlacedTable(previous);
            // HARD-CODED REBUILD SAFETY:
            // Texas tables also go through repeated startup warmup rebuilds.
            // Purge any leftover chair/table visuals around the anchor before each respawn so repeated rebuild passes
            // cannot stack duplicate furniture/entities after a restart.
            purgeResidualWorldArtifacts(previous.anchor, previous.yaw, Math.max(2, previous.seatLabelIds.size()));
            ZjhTable table = plugin.getZjhManager().getTable(entry.getKey());
            if (table == null) {
                continue;
            }
            PlacedTable rebuilt = spawnTable(table, previous.anchor.clone(), previous.yaw, previous.ownerId, previous.ownerName);
            rebuilt.seatAssignments.putAll(previous.seatAssignments);
            placedTables.put(entry.getKey(), rebuilt);
            refresh(table);
        }
    }

    public void repairIncompleteTables(String reason) {
        List<String> targets = new ArrayList<>();
        for (Map.Entry<String, PlacedTable> entry : placedTables.entrySet()) {
            if (isIncomplete(entry.getValue())) {
                targets.add(entry.getKey() + "(" + incompleteReason(entry.getValue()) + ")");
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        if (reason != null && reason.startsWith("viewer-")) {
            plugin.getLogger().fine("[MUZ/repair/texas] reason=" + reason + " tables=" + targets);
        } else {
            plugin.getLogger().warning("[MUZ/repair/texas] reason=" + reason + " tables=" + targets);
        }
        for (String tableName : targets) {
            rebuildSingleTable(tableName);
        }
    }

    public void shiftAllAnchors(double deltaY) {
        if (Math.abs(deltaY) < 0.0001) {
            return;
        }
        if (!canSafelyReplaceWorldVisuals()) {
            return;
        }
        Map<String, PlacedTable> snapshot = new LinkedHashMap<>(placedTables);
        placedTables.clear();
        for (Map.Entry<String, PlacedTable> entry : snapshot.entrySet()) {
            PlacedTable previous = entry.getValue();
            cleanupPlacedTable(previous);
            purgeResidualWorldArtifacts(previous.anchor.clone().add(0.0, deltaY, 0.0), previous.yaw, Math.max(2, previous.seatLabelIds.size()));
            ZjhTable table = plugin.getZjhManager().getTable(entry.getKey());
            if (table == null) {
                continue;
            }
            PlacedTable rebuilt = spawnTable(table, previous.anchor.clone().add(0.0, deltaY, 0.0), previous.yaw, previous.ownerId, previous.ownerName);
            rebuilt.seatAssignments.putAll(previous.seatAssignments);
            placedTables.put(entry.getKey(), rebuilt);
            refresh(table);
        }
    }

    private void rebuildSingleTable(String tableName) {
        if (!canSafelyReplaceWorldVisuals()) {
            return;
        }
        String key = normalize(tableName);
        PlacedTable previous = placedTables.remove(key);
        if (previous == null) {
            return;
        }
        cleanupPlacedTable(previous);
        purgeResidualWorldArtifacts(previous.anchor, previous.yaw, Math.max(2, previous.seatLabelIds.size()));
        ZjhTable table = plugin.getZjhManager().getTable(tableName);
        if (table == null) {
            return;
        }
        PlacedTable rebuilt = spawnTable(table, previous.anchor.clone(), previous.yaw, previous.ownerId, previous.ownerName);
        rebuilt.seatAssignments.putAll(previous.seatAssignments);
        placedTables.put(key, rebuilt);
        refresh(table);
    }

    private boolean isIncomplete(PlacedTable placed) {
        return !incompleteReason(placed).isBlank();
    }

    private String incompleteReason(PlacedTable placed) {
        if (placed == null) {
            return "";
        }
        List<String> missing = new ArrayList<>();
        if (placed.statusId == null || Bukkit.getEntity(placed.statusId) == null) {
            missing.add("status");
        }
        if (placed.statusAvatarId == null || Bukkit.getEntity(placed.statusAvatarId) == null) {
            missing.add("status-avatar");
        }
        if (placed.statusAvatarNameId == null || Bukkit.getEntity(placed.statusAvatarNameId) == null) {
            missing.add("status-avatar-name");
        }
        if (placed.seatAvatarIds.isEmpty() || placed.seatLabelIds.isEmpty()) {
            missing.add("seat-display-count");
        }
        for (UUID id : placed.seatAvatarIds) {
            if (Bukkit.getEntity(id) == null) {
                missing.add("seat-avatar");
                break;
            }
        }
        for (UUID id : placed.seatLabelIds) {
            if (Bukkit.getEntity(id) == null) {
                missing.add("seat-label");
                break;
            }
        }
        return String.join(",", missing);
    }

    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.isShuttingDown()) {
            return;
        }
        List<String> incomplete = new ArrayList<>();
        for (Map.Entry<String, PlacedTable> entry : placedTables.entrySet()) {
            String reason = incompleteReason(entry.getValue());
            if (!reason.isBlank()) {
                incomplete.add(entry.getKey() + "(" + reason + ")");
            }
        }
        if (!incomplete.isEmpty()) {
            plugin.getLogger().fine("[MUZ/viewer-sync/texas] viewer=" + viewer.getName() + " incomplete=" + incomplete);
            for (String tableName : incomplete.stream().map(entry -> entry.substring(0, entry.indexOf('('))).toList()) {
                rebuildSingleTable(tableName);
            }
        }
        hoveredDisplayByViewer.remove(viewer.getUniqueId());
        for (ZjhTable table : plugin.getZjhManager().getTables()) {
            refresh(table);
        }
        for (PlacedTable placed : placedTables.values()) {
            showPublicEntitiesTo(viewer, placed.entities);
            showPublicEntitiesTo(viewer, placed.communityEntityIds);
            showPublicEntitiesTo(viewer, placed.dealerEntityIds);
        }
    }

    private void showPublicEntitiesTo(Player viewer, List<UUID> entityIds) {
        for (UUID entityId : entityIds) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                viewer.showEntity(plugin, entity);
            }
        }
    }

    public void refresh(ZjhTable table) {
        if (plugin.isShuttingDown()) {
            return;
        }
        if (table == null) {
            return;
        }
        PlacedTable placed = placedTables.get(normalize(table.getName()));
        if (placed == null) {
            return;
        }
        reconcileSeatAssignments(table, placed);
        if (placed.statusId != null) {
            Entity entity = Bukkit.getEntity(placed.statusId);
            updateTextEntity(entity, TypewriterTextStyle.joinLines(
                TypewriterTextStyle.title("德州扑克"),
                TypewriterTextStyle.joinInline(
                    TypewriterTextStyle.meta(plugin.roomDisplayTag(table.getRoomLevel())),
                    TypewriterTextStyle.warm("倍率 " + plugin.formatMultiplier(plugin.roomMultiplier(table.getRoomLevel())))
                ),
                TypewriterTextStyle.joinInline(
                    TypewriterTextStyle.warm(table.getStreet().displayName()),
                    TypewriterTextStyle.meta(table.potBreakdownText()),
                    TypewriterTextStyle.meta("当前下注 " + table.getCurrentBet())
                ),
                table.lastActionComponent()
            ));
        }
        if (placed.statusAvatarId != null) {
            Entity entity = Bukkit.getEntity(placed.statusAvatarId);
            updateTextEntity(entity, statusAvatar(table));
            teleportIfMoved(entity, statusAvatarLocation(placed.anchor, placed.yaw));
        }
        if (placed.statusAvatarNameId != null) {
            Entity entity = Bukkit.getEntity(placed.statusAvatarNameId);
            updateTextEntity(entity, statusAvatarName(table));
            teleportIfMoved(entity, statusAvatarNameLocation(placed.anchor, placed.yaw));
        }
        for (int index = 0; index < placed.seatAvatarIds.size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatAvatarIds.get(index));
            UUID seat = placed.seatAssignments.get(index);
            updateTextEntity(entity, seat == null ? Component.empty() : plugin.playerHeadComponent(seat, table.displayName(seat), NamedTextColor.GOLD));
            teleportIfMoved(entity, seatAvatarLocation(placed.anchor, placed.yaw, table.getMaxPlayers(), index));
        }
        for (int index = 0; index < placed.seatLabelIds.size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatLabelIds.get(index));
            UUID seat = placed.seatAssignments.get(index);
            updateTextEntity(entity, seatInfo(table, index, seat));
            teleportIfMoved(entity, seat == null
                ? joinLabelLocation(texasJoinLocation(placed.anchor, placed.yaw, table.getMaxPlayers(), index))
                : seatInfoLocation(placed.anchor, placed.yaw, table.getMaxPlayers(), index));
        }
        updateJoinButtons(table, placed);
        refreshSeatActions(table, placed);
        refreshCommunityCards(table, placed);
        refreshHoleCards(table, placed);
        refreshDealerMarker(table, placed);
        plugin.persistTexasTable(table.getName(), table.getMaxPlayers(), table.getRoomLevel(), placed.anchor, placed.yaw, placed.ownerId, placed.ownerName);
    }

    public boolean handleInteraction(org.bukkit.entity.Player player, Entity entity) {
        JoinBinding binding = joinBindings.get(entity.getUniqueId());
        if (binding == null) {
            SeatActionBinding action = seatActionBindings.get(entity.getUniqueId());
            if (action == null) {
                return false;
            }
            clearButtonHoverNow(player.getUniqueId());
            ZjhTable table = plugin.getZjhManager().getTable(action.tableName);
            if (table == null) {
                return true;
            }
            if (action.ownerId != null && !action.ownerId.equals(player.getUniqueId())) {
                player.sendActionBar(message("这不是你的按钮。", NamedTextColor.RED));
                return true;
            }
            try {
                switch (action.action) {
                    case READY -> table.toggleReady(player);
                    case START -> table.startRound(player);
                    case LEAVE -> plugin.getZjhManager().leaveTable(player);
                    case CHECK -> table.check(player);
                    case CALL -> table.call(player);
                    case RAISE -> table.raise(player, action.amount() == null ? table.suggestedRaiseTo(player.getUniqueId()) : action.amount());
                    case FOLD -> table.fold(player);
                    case ALL_IN -> table.allIn(player);
                }
                refresh(table);
            } catch (RuntimeException exception) {
                player.sendActionBar(message(exception.getMessage(), NamedTextColor.RED));
            }
            return true;
        }
        ZjhTable table = plugin.getZjhManager().getTable(binding.tableName);
        if (table == null) {
            return true;
        }
        ZjhTable currentZjh = plugin.getZjhManager().getTableOf(player);
        GameTable currentDdz = plugin.getTableManager().getTableOf(player);
        boolean switchingFromDdz = currentDdz != null;
        boolean switchingFromOtherTexas = currentZjh != null && !currentZjh.getName().equalsIgnoreCase(table.getName());
        PlacedTable placed = placedTables.get(normalize(table.getName()));
        if (placed == null) {
            return true;
        }
        reconcileSeatAssignments(table, placed);
        if (binding.seatIndex < 0 || binding.seatIndex >= placed.seatLabelIds.size()) {
            player.sendActionBar(message("这个座位不可用。", NamedTextColor.RED));
            return true;
        }
        if (placed.seatAssignments.get(binding.seatIndex) != null) {
            player.sendActionBar(message("这个座位已经有人了。", NamedTextColor.RED));
            return true;
        }
        if ((switchingFromDdz || switchingFromOtherTexas) && table.getPhase() != ZjhPhase.LOBBY) {
            player.sendActionBar(message("目标牌桌这局已经开始了，暂时不能加入。", NamedTextColor.RED));
            return true;
        }
        if ((switchingFromDdz || switchingFromOtherTexas) && !plugin.canAffordEntry(player.getUniqueId(), table.getRoomLevel())) {
            player.sendActionBar(message(plugin.insufficientEntryMessage(player.getUniqueId(), table.getRoomLevel()), NamedTextColor.RED));
            return true;
        }
        if (currentZjh != null) {
            if (table.getPhase() != ZjhPhase.LOBBY) {
                player.sendActionBar(message("对局开始后不能切换座位。", NamedTextColor.RED));
                return true;
            }
            placed.seatAssignments.entrySet().removeIf(entry -> player.getUniqueId().equals(entry.getValue()));
            placed.seatAssignments.put(binding.seatIndex, player.getUniqueId());
            refresh(table);
            player.sendActionBar(message("你已切换到座位 " + (binding.seatIndex + 1) + "。", NamedTextColor.GREEN));
            return true;
        }
        if (switchingFromDdz) {
            plugin.getTableManager().leaveTable(player);
        } else if (switchingFromOtherTexas) {
            plugin.getZjhManager().leaveTable(player);
        }
        plugin.getZjhManager().joinTable(player, table.getName());
        placed.seatAssignments.entrySet().removeIf(entry -> player.getUniqueId().equals(entry.getValue()));
        placed.seatAssignments.put(binding.seatIndex, player.getUniqueId());
        refresh(table);
        player.sendActionBar(message("你已加入 " + table.getName() + " 号牌桌的座位 " + (binding.seatIndex + 1) + "。", NamedTextColor.GREEN));
        return true;
    }

    public boolean handleAttack(org.bukkit.entity.Player player, Entity entity) {
        return false;
    }

    public void removeTable(String tableName) {
        PlacedTable placed = placedTables.remove(normalize(tableName));
        if (placed == null) {
            throw new IllegalArgumentException("这个德州扑克牌桌没有实体桌面。");
        }
        cleanupPlacedTable(placed);
        purgeResidualWorldArtifacts(placed.anchor, placed.yaw, Math.max(2, placed.seatLabelIds.size()));
        plugin.getZjhManager().unregisterTable(tableName);
        plugin.deletePersistedTable("TEXAS", tableName);
    }

    public void shutdown() {
        if (plugin.getServer().isStopping()) {
            placedTables.clear();
            return;
        }
        for (PlacedTable placed : placedTables.values()) {
            cleanupPlacedTable(placed);
        }
        placedTables.clear();
    }

    private void ensureWorldVisualsReady(String action) {
        if (canSafelyReplaceWorldVisuals()) {
            return;
        }
        throw new IllegalStateException("CraftEngine 桌椅资源还没准备好，稍后再" + action + "。");
    }

    private boolean canSafelyReplaceWorldVisuals() {
        if (!plugin.isTexasSpawnFurniture()) {
            return true;
        }
        org.bukkit.plugin.Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return true;
        }
        if (plugin.getCraftEngineFurnitureService().isAvailable()) {
            return true;
        }
        return usesVanillaBlockFallback(DoudizhuPlugin.FurnitureType.TABLE)
            && usesVanillaBlockFallback(DoudizhuPlugin.FurnitureType.CHAIR);
    }

    private boolean usesVanillaBlockFallback(DoudizhuPlugin.FurnitureType type) {
        ItemStack configured = plugin.getConfiguredFurnitureItem(type);
        return configured != null && configured.getType().isBlock();
    }

    public void tick() {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            updateButtonHover(viewer);
        }
        updateButtonHoverAnimations();
    }

    public boolean isProtectedEntity(UUID entityId) {
        Entity entity = Bukkit.getEntity(entityId);
        while (entity != null) {
            if (entity.getScoreboardTags().contains(PROTECTED_ENTITY_TAG)) {
                return true;
            }
            UUID currentId = entity.getUniqueId();
            for (PlacedTable placed : placedTables.values()) {
                if (placed.entities.contains(currentId)
                    || placed.communityEntityIds.contains(currentId)
                    || placed.seatActionEntityIds.contains(currentId)
                    || placed.dealerEntityIds.contains(currentId)
                    || matchesExpectedFurnitureEntity(entity, placed)) {
                    return true;
                }
            }
            entity = entity.getVehicle();
        }
        return false;
    }

    public boolean isProtectedPlacedBlock(org.bukkit.block.Block block) {
        if (block == null) {
            return false;
        }
        for (PlacedTable placed : placedTables.values()) {
            if (matchesExpectedPlacedBlock(block, placed)) {
                return true;
            }
            for (BlockRestore blockRestore : placed.blockRestores) {
                Location location = blockRestore.originalState().getLocation();
                if (location.getWorld() != null
                    && location.getWorld().equals(block.getWorld())
                    && location.getBlockX() == block.getX()
                    && location.getBlockY() == block.getY()
                    && location.getBlockZ() == block.getZ()) {
                    return true;
                }
            }
        }
        return false;
    }

    public Location tableAnchor(String tableName) {
        PlacedTable placed = placedTables.get(normalize(tableName));
        return placed == null ? null : placed.anchor.clone();
    }

    public float tableYaw(String tableName) {
        PlacedTable placed = placedTables.get(normalize(tableName));
        return placed == null ? 0.0f : placed.yaw;
    }

    public boolean canRemoveTable(Player player, String tableName) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission("muz.admin")) {
            return true;
        }
        PlacedTable placed = placedTables.get(normalize(tableName));
        return placed != null && placed.ownerId != null && placed.ownerId.equals(player.getUniqueId());
    }

    public String removeDeniedReason(Player player, String tableName) {
        PlacedTable placed = placedTables.get(normalize(tableName));
        if (placed == null) {
            return "找不到这张牌桌。";
        }
        if (player != null && player.hasPermission("muz.admin")) {
            return "";
        }
        if (placed.ownerId == null) {
            return "这张牌桌没有记录放置者，只有管理员才能拆。";
        }
        String owner = placed.ownerName == null || placed.ownerName.isBlank() ? "原放置者" : placed.ownerName;
        return "这张牌桌是 " + owner + " 放的，你不能拆。";
    }

    public String targetedTable(Player player, double maxDistance) {
        if (player == null) {
            return null;
        }
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector direction = eye.getDirection().normalize();
        String bestTable = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<String, PlacedTable> entry : placedTables.entrySet()) {
            double distance = sightDistance(eye, direction, previewTableCenter(entry.getValue().anchor), 1.45, maxDistance);
            if (distance >= 0.0 && distance < bestDistance) {
                bestDistance = distance;
                bestTable = entry.getKey();
            }
        }
        return bestTable;
    }

    /**
     * 返回放桌失败的玩家提示文案
     * @param anchor 放桌锚点
     * @param yaw 放桌朝向
     * @param maxPlayers 座位数量
     * @return 可以放置时返回 null，否则返回失败原因
     */
    public String placementObstructionReason(Location anchor, float yaw, int maxPlayers) {
        PlacementObstruction obstruction = placementObstruction(anchor, yaw, maxPlayers);
        return obstruction == null ? null : obstruction.reason();
    }

    /**
     * 检测放桌区域的阻挡情况，返回第一个失败的详细结果
     * @param anchor 放桌锚点
     * @param yaw 放桌朝向
     * @param maxPlayers 座位数量
     * @return 可以放置时返回 null，否则返回带原因与被挡方块的结果
     */
    public PlacementObstruction placementObstruction(Location anchor, float yaw, int maxPlayers) {
        if (anchor == null || anchor.getWorld() == null) {
            return PlacementObstruction.ofReason("这里暂时还不能放牌桌。");
        }
        ensureChunkReady(anchor);
        PlacementObstruction tableObstruction = PlacementObstruction.detect(
            "桌面",
            blockPlacementLocation(previewTableCenter(anchor)),
            1.05,
            -0.10,
            0.95
        );
        if (tableObstruction != null) {
            return tableObstruction;
        }
        List<double[]> offsets = seatOffsets(maxPlayers);
        for (int index = 0; index < offsets.size(); index++) {
            Location chairLocation = rotate(anchor, yaw, offsets.get(index)[0], plugin.getChairBaseHeight(), offsets.get(index)[1]);
            PlacementObstruction chairObstruction = PlacementObstruction.detect(
                "座位 " + (index + 1),
                blockPlacementLocation(chairLocation),
                0.60,
                -0.10,
                1.05
            );
            if (chairObstruction != null) {
                return chairObstruction;
            }
        }
        return null;
    }

    /**
     * 收集放桌区域内所有被挡方块，供粒子高亮复用
     * @param anchor 放桌锚点
     * @param yaw 放桌朝向
     * @param maxPlayers 座位数量
     * @return 桌面与全部座位的被挡方块整格坐标，去重后按检测顺序排列；区块未加载时返回空列表
     */
    public List<Location> placementBlockedBlocks(Location anchor, float yaw, int maxPlayers) {
        if (anchor == null || anchor.getWorld() == null || !anchor.getChunk().isLoaded()) {
            return List.of();
        }
        Set<Location> blocked = new LinkedHashSet<>();
        blocked.addAll(PlacementObstruction.collectBlockingBlocks(
            blockPlacementLocation(previewTableCenter(anchor)),
            1.05,
            -0.10,
            0.95
        ));
        for (double[] offset : seatOffsets(maxPlayers)) {
            Location chairLocation = rotate(anchor, yaw, offset[0], plugin.getChairBaseHeight(), offset[1]);
            blocked.addAll(PlacementObstruction.collectBlockingBlocks(
                blockPlacementLocation(chairLocation),
                0.60,
                -0.10,
                1.05
            ));
        }
        return new ArrayList<>(blocked);
    }

    private void reconcileSeatAssignments(ZjhTable table, PlacedTable placed) {
        placed.seatAssignments.entrySet().removeIf(entry ->
            entry.getKey() < 0
                || entry.getKey() >= placed.seatLabelIds.size()
                || !table.getSeats().contains(entry.getValue())
        );
        List<UUID> seen = new ArrayList<>();
        placed.seatAssignments.entrySet().removeIf(entry -> !seen.add(entry.getValue()));
        for (UUID playerId : table.getSeats()) {
            if (placed.seatAssignments.containsValue(playerId)) {
                continue;
            }
            for (int seatIndex = 0; seatIndex < placed.seatLabelIds.size(); seatIndex++) {
                if (!placed.seatAssignments.containsKey(seatIndex)) {
                    placed.seatAssignments.put(seatIndex, playerId);
                    break;
                }
            }
        }
    }

    private PlacedTable spawnTable(ZjhTable table, Location anchor, float yaw, UUID ownerId, String ownerName) {
        ensureChunkReady(anchor);
        List<UUID> entities = new ArrayList<>();
        List<UUID> craftEngineVisualEntityIds = new ArrayList<>();
        List<BlockRestore> blockRestores = new ArrayList<>();
        List<UUID> seatAvatars = new ArrayList<>();
        List<UUID> seatLabels = new ArrayList<>();
        Map<Integer, UUID> seatAssignments = new LinkedHashMap<>();
        List<UUID> seatActionEntities = new ArrayList<>();
        UUID statusAvatarId = null;
        UUID statusAvatarNameId = null;
        UUID tableVisualId = null;
        int maxPlayers = table.getMaxPlayers();
        List<double[]> offsets = seatOffsets(maxPlayers);
        if (plugin.isTexasSpawnFurniture()) {
            Location tableLocation = anchor.clone().add(0.0, plugin.getTableDisplayHeight(), 0.0);
            ChairPlacement tablePlacement = spawnTableVisual(tableLocation, yaw);
            if (tablePlacement.entityId() != null) {
                addEntityTreeIds(tablePlacement.entityId(), entities);
                tableVisualId = tablePlacement.entityId();
                if (tablePlacement.craftEngineEntity()) {
                    addEntityTreeIds(tablePlacement.entityId(), craftEngineVisualEntityIds);
                }
            }
            if (tablePlacement.blockRestore() != null) {
                blockRestores.add(tablePlacement.blockRestore());
            }
        }
        for (int index = 0; index < offsets.size(); index++) {
            double[] offset = offsets.get(index);
            Location chairLocation = rotate(anchor, yaw, offset[0], plugin.getChairBaseHeight(), offset[1]);
            UUID chairVisualId = null;
            if (plugin.isTexasSpawnFurniture()) {
                ChairPlacement chairPlacement = spawnChairVisual(chairLocation, chairYaw(offset));
                if (chairPlacement.entityId() != null) {
                    addEntityTreeIds(chairPlacement.entityId(), entities);
                    chairVisualId = chairPlacement.entityId();
                    if (chairPlacement.craftEngineEntity()) {
                        addEntityTreeIds(chairPlacement.entityId(), craftEngineVisualEntityIds);
                    }
                }
                if (chairPlacement.blockRestore() != null) {
                    blockRestores.add(chairPlacement.blockRestore());
                }
                chairLocation = chairPlacement.seatBaseLocation();
            }
            // HARD-CODED AVATAR SPLIT:
            // Texas seat avatars and the name/info block must remain separate so admin can tune size and position
            // independently, while keeping the visible name fixed below the avatar instead of inlining everything.
            TextDisplay avatar = spawnText(
                seatAvatarLocation(anchor, yaw, maxPlayers, index),
                Component.empty(),
                seatAvatarScale()
            );
            entities.add(avatar.getUniqueId());
            seatAvatars.add(avatar.getUniqueId());

            Location joinLocation = chairLocation.clone().add(0.0, plugin.getTexasJoinButtonHeight(), 0.0);
            TextDisplay label = spawnText(joinLabelLocation(joinLocation), TypewriterTextStyle.meta("座位 " + (index + 1)), TEXT_SCALE);
            entities.add(label.getUniqueId());
            seatLabels.add(label.getUniqueId());
            ItemDisplay joinButton = spawnFurniture(joinLocation, joinItem(), BUTTON_SCALE);
            configureButtonAnimation(joinButton);
            entities.add(joinButton.getUniqueId());
            Interaction interaction = spawnInteraction(joinLocation.clone().add(0.0, 0.05, 0.0), 0.32f, 0.32f);
            entities.add(interaction.getUniqueId());
        joinBindings.put(joinButton.getUniqueId(), new JoinBinding(table.getName(), index));
        joinBindings.put(interaction.getUniqueId(), new JoinBinding(table.getName(), index));
        joinBindings.put(label.getUniqueId(), new JoinBinding(table.getName(), index));
        rememberButtonVisual(joinButton.getUniqueId(), joinButton.getUniqueId(), joinLocation);
        rememberButtonVisual(label.getUniqueId(), joinButton.getUniqueId(), joinLocation);
        rememberButtonVisual(interaction.getUniqueId(), joinButton.getUniqueId(), joinLocation);
        }
        TextDisplay status = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getTexasStatusHeight(), 0.0),
            TypewriterTextStyle.title("德州扑克"),
            STATUS_SCALE
        );
        entities.add(status.getUniqueId());
        // HARD-CODED STATUS AVATAR SPLIT:
        // The top status avatar/name pair is separate from the status text body on purpose.
        // Do not merge the head back into the text line unless the user explicitly requests that regression.
        TextDisplay statusAvatar = spawnText(
            statusAvatarLocation(anchor, yaw),
            Component.empty(),
            statusAvatarScale()
        );
        entities.add(statusAvatar.getUniqueId());
        statusAvatarId = statusAvatar.getUniqueId();
        TextDisplay statusAvatarName = spawnText(
            statusAvatarNameLocation(anchor, yaw),
            Component.empty(),
            TEXT_SCALE
        );
        entities.add(statusAvatarName.getUniqueId());
        statusAvatarNameId = statusAvatarName.getUniqueId();
        return new PlacedTable(entities, craftEngineVisualEntityIds, blockRestores, seatAvatars, seatLabels, status.getUniqueId(), statusAvatarId, statusAvatarNameId, seatAssignments, seatActionEntities, new ArrayList<>(), anchor.clone(), yaw, ownerId, ownerName);
    }

    private void ensureChunkReady(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        // Startup restore can happen before the destination chunk is fully live.
        // Force-load the anchor chunk first so seat text, chairs, and table visuals rebuild in one pass.
        org.bukkit.Chunk chunk = anchor.getChunk();
        if (!chunk.isLoaded()) {
            chunk.load();
        }
    }

    private List<double[]> seatOffsets(int maxPlayers) {
        List<double[]> base = switch (Math.max(2, Math.min(10, maxPlayers))) {
            case 2 -> List.of(new double[] {0.0, -2.25}, new double[] {0.0, 2.25});
            case 3 -> List.of(new double[] {0.0, -2.25}, new double[] {-2.25, 0.0}, new double[] {2.25, 0.0});
            case 4 -> List.of(new double[] {0.0, -2.25}, new double[] {-2.25, 0.0}, new double[] {0.0, 2.25}, new double[] {2.25, 0.0});
            case 5 -> List.of(
                new double[] {-1.15, -2.25},
                new double[] {1.15, -2.25},
                new double[] {-2.25, 0.0},
                new double[] {0.0, 2.25},
                new double[] {2.25, 0.0}
            );
            case 6 -> List.of(
                new double[] {-1.15, -2.25},
                new double[] {1.15, -2.25},
                new double[] {-2.25, 0.0},
                new double[] {-1.15, 2.25},
                new double[] {1.15, 2.25},
                new double[] {2.25, 0.0}
            );
            case 7 -> List.of(
                new double[] {-1.60, -2.25},
                new double[] {0.0, -2.35},
                new double[] {1.60, -2.25},
                new double[] {-2.35, 0.0},
                new double[] {-1.10, 2.25},
                new double[] {1.10, 2.25},
                new double[] {2.35, 0.0}
            );
            case 8 -> List.of(
                new double[] {-1.60, -2.25},
                new double[] {0.0, -2.35},
                new double[] {1.60, -2.25},
                new double[] {-2.35, -0.70},
                new double[] {-2.35, 0.70},
                new double[] {2.35, -0.70},
                new double[] {2.35, 0.70},
                new double[] {0.0, 2.35}
            );
            case 9 -> List.of(
                new double[] {-1.60, -2.25},
                new double[] {0.0, -2.35},
                new double[] {1.60, -2.25},
                new double[] {-2.35, -0.70},
                new double[] {-2.35, 0.70},
                new double[] {2.35, -0.70},
                new double[] {2.35, 0.70},
                new double[] {-1.10, 2.25},
                new double[] {1.10, 2.25}
            );
            default -> List.of(
                new double[] {-1.90, -2.20},
                new double[] {-0.60, -2.35},
                new double[] {0.60, -2.35},
                new double[] {1.90, -2.20},
                new double[] {-2.35, -0.70},
                new double[] {-2.35, 0.70},
                new double[] {2.35, -0.70},
                new double[] {2.35, 0.70},
                new double[] {-0.90, 2.30},
                new double[] {0.90, 2.30}
            );
        };
        double scale = plugin.getTexasSeatDistance() / 2.25;
        return base.stream()
            .map(offset -> new double[] {offset[0] * scale, offset[1] * scale})
            .toList();
    }

    private ItemDisplay spawnFurniture(Location location, ItemStack item, float scale) {
        return VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            protect(spawned);
        });
    }

    private TextDisplay spawnText(Location location, Component text, float scale) {
        return VersionCompat.spawnEntity(location.getWorld(), location, TextDisplay.class, spawned -> {
            spawned.text(text);
            TypewriterTextStyle.apply(spawned, Display.Billboard.CENTER, false, scale);
            protect(spawned);
        });
    }

    private void mountTextDisplay(Entity anchor, TextDisplay display, Location desiredLocation, boolean panel) {
        if (display == null || desiredLocation == null) {
            return;
        }
        teleportIfMoved(display, desiredLocation);
    }

    private void updateTextEntity(Entity entity, Component text) {
        if (entity instanceof TextDisplay display) {
            display.text(text.decoration(TextDecoration.ITALIC, false));
        }
    }

    private void teleportIfMoved(Entity entity, Location destination) {
        if (entity == null || destination == null) {
            return;
        }
        Location current = entity.getLocation();
        if (!current.getWorld().equals(destination.getWorld())
            || current.distanceSquared(destination) > 0.0004) {
            Location moved = destination.clone();
            moved.setYaw(current.getYaw());
            moved.setPitch(current.getPitch());
            entity.teleport(moved);
        }
    }

    private Interaction spawnInteraction(Location location, float width, float height) {
        return VersionCompat.spawnEntity(location.getWorld(), location, Interaction.class, spawned -> {
            spawned.setInteractionWidth(width);
            spawned.setInteractionHeight(height);
            spawned.setResponsive(true);
            protect(spawned);
        });
    }

    private void protect(Entity entity) {
        entity.setInvulnerable(true);
        entity.setPersistent(false);
        entity.setGravity(false);
        entity.addScoreboardTag(PROTECTED_ENTITY_TAG);
    }

    private boolean matchesExpectedPlacedBlock(org.bukkit.block.Block block, PlacedTable placed) {
        Location tableBlock = snappedBlockLocation(blockPlacementLocation(placed.anchor.clone().add(0.0, plugin.getTableDisplayHeight(), 0.0)));
        if (sameBlock(block, tableBlock)) {
            return true;
        }
        for (double[] offset : seatOffsets(Math.max(2, placed.seatLabelIds.size()))) {
            Location chairLocation = rotate(placed.anchor, placed.yaw, offset[0], plugin.getChairBaseHeight(), offset[1]);
            if (sameBlock(block, snappedBlockLocation(blockPlacementLocation(chairLocation)))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesExpectedFurnitureEntity(Entity entity, PlacedTable placed) {
        if (entity == null || !isLikelyFurnitureEntity(entity)) {
            return false;
        }
        // HARD-CODED CE FURNITURE PROTECTION:
        // Restarted CraftEngine furniture can survive with fresh entity ids outside MUZ's tracked lists.
        // Protect any furniture-like entity that sits on the expected table/chair positions for this Texas table.
        // Keep this fallback unless the user explicitly asks to remove it.
        Location location = entity.getLocation();
        Location tableLocation = placed.anchor.clone().add(0.0, plugin.getTableDisplayHeight(), 0.0);
        if (nearExpectedLocation(location, tableLocation, 1.15, 1.80)) {
            return true;
        }
        for (double[] offset : seatOffsets(Math.max(2, placed.seatLabelIds.size()))) {
            Location chairLocation = rotate(placed.anchor, placed.yaw, offset[0], plugin.getChairBaseHeight(), offset[1]);
            if (nearExpectedLocation(location, chairLocation, 0.90, 1.60)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyFurnitureEntity(Entity entity) {
        return entity instanceof Display
            || entity instanceof Interaction
            || entity instanceof org.bukkit.entity.ArmorStand
            || entity instanceof org.bukkit.entity.Hanging;
    }

    private boolean nearExpectedLocation(Location current, Location expected, double radiusXz, double radiusY) {
        if (current == null || expected == null || current.getWorld() == null || expected.getWorld() == null) {
            return false;
        }
        if (!current.getWorld().equals(expected.getWorld())) {
            return false;
        }
        double dx = current.getX() - expected.getX();
        double dz = current.getZ() - expected.getZ();
        double dy = Math.abs(current.getY() - expected.getY());
        return (dx * dx + dz * dz) <= (radiusXz * radiusXz) && dy <= radiusY;
    }

    private boolean sameBlock(org.bukkit.block.Block block, Location location) {
        return location != null
            && location.getWorld() != null
            && location.getWorld().equals(block.getWorld())
            && location.getBlockX() == block.getX()
            && location.getBlockY() == block.getY()
            && location.getBlockZ() == block.getZ();
    }

    private void addEntityTreeIds(UUID rootId, List<UUID> target) {
        Entity root = Bukkit.getEntity(rootId);
        if (root == null) {
            if (!target.contains(rootId)) {
                target.add(rootId);
            }
            return;
        }
        protectEntityTree(root);
        collectEntityTreeIds(root, target);
    }

    private void collectEntityTreeIds(Entity entity, List<UUID> target) {
        if (entity == null) {
            return;
        }
        if (!target.contains(entity.getUniqueId())) {
            target.add(entity.getUniqueId());
        }
        for (Entity passenger : entity.getPassengers()) {
            collectEntityTreeIds(passenger, target);
        }
    }

    private void protectEntityTree(Entity entity) {
        if (entity == null) {
            return;
        }
        protect(entity);
        for (Entity passenger : entity.getPassengers()) {
            protectEntityTree(passenger);
        }
    }

    private void configureDisplayAnimation(Display display) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(3);
        display.setTeleportDuration(0);
    }

    private void configureButtonAnimation(Display display) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(Math.max(2, Math.min(4, plugin.getButtonHoverInterpolationTicks() / 2)));
        // IMPORTANT REGRESSION GUARD:
        // Buttons must not use teleport interpolation, otherwise hover/click refreshes can look like a spin-back animation.
        display.setTeleportDuration(0);
    }

    private ItemStack tableItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        VersionCompat.setItemModel(meta, model(plugin.getTableItemModelId(), PackAssets.furnitureModel(plugin, "table_visual")));
        meta.displayName(message(plugin.getTableDisplayName(), NamedTextColor.GOLD));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack chairItem() {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.CHAIR);
        if (configured != null) {
            configured.setAmount(1);
            return configured;
        }
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        VersionCompat.setItemModel(meta, model(plugin.getChairItemModelId(), PackAssets.furnitureModel(plugin, "seat_chair")));
        meta.displayName(message(plugin.getChairDisplayName(), NamedTextColor.RED));
        item.setItemMeta(meta);
        return item;
    }

    private ChairPlacement spawnTableVisual(Location location, float yaw) {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.TABLE);
        Location target = location.clone();
        target.setYaw(yaw);
        for (String itemId : plugin.getTableFurnitureItemIdCandidates()) {
            dev.mumu.doudizhu.compat.CraftEngineFurnitureService.PlacementKind kind =
                plugin.getCraftEngineFurnitureService().detectPlacementKind(itemId);
            if (kind == dev.mumu.doudizhu.compat.CraftEngineFurnitureService.PlacementKind.FURNITURE) {
                Entity furniture = plugin.getCraftEngineFurnitureService().placeFurniture(target, itemId);
                if (furniture != null) {
                    return ChairPlacement.furniture(furniture.getUniqueId(), location.clone(), true);
                }
            } else if (kind == dev.mumu.doudizhu.compat.CraftEngineFurnitureService.PlacementKind.BLOCK) {
                Location blockLocation = blockPlacementLocation(location);
                BlockRestore restore = captureBlockRestore(blockLocation);
                if (plugin.getCraftEngineFurnitureService().placeBlockWithState(snappedBlockLocation(blockLocation), orientedCraftEngineBlockState(itemId, yaw))) {
                    return ChairPlacement.block(blockLocation.clone(), restore);
                }
            }
        }
        if (configured != null && configured.getType().isBlock()) {
            Location blockLocation = blockPlacementLocation(location);
            BlockRestore restore = captureBlockRestore(blockLocation);
            Location snappedBlockLocation = snappedBlockLocation(blockLocation);
            snappedBlockLocation.getBlock().setType(configured.getType(), false);
            orientVanillaBlockToYaw(snappedBlockLocation, yaw);
            return ChairPlacement.block(blockLocation.clone(), restore);
        }
        ItemDisplay fallback = spawnFurniture(location, tableItem(), TABLE_SCALE);
        return ChairPlacement.furniture(fallback.getUniqueId(), location.clone(), false);
    }

    private ChairPlacement spawnChairVisual(Location location, float yaw) {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.CHAIR);
        Location target = location.clone();
        target.setYaw(yaw);
        for (String itemId : plugin.getChairFurnitureItemIdCandidates()) {
            dev.mumu.doudizhu.compat.CraftEngineFurnitureService.PlacementKind kind =
                plugin.getCraftEngineFurnitureService().detectPlacementKind(itemId);
            if (kind == dev.mumu.doudizhu.compat.CraftEngineFurnitureService.PlacementKind.FURNITURE) {
                Entity furniture = plugin.getCraftEngineFurnitureService().placeFurniture(target, itemId);
                if (furniture != null) {
                    return ChairPlacement.furniture(furniture.getUniqueId(), location.clone(), true);
                }
            } else if (kind == dev.mumu.doudizhu.compat.CraftEngineFurnitureService.PlacementKind.BLOCK) {
                Location blockLocation = blockPlacementLocation(location);
                BlockRestore restore = captureBlockRestore(blockLocation);
                if (plugin.getCraftEngineFurnitureService().placeBlockWithState(snappedBlockLocation(blockLocation), orientedCraftEngineBlockState(itemId, yaw))) {
                    return ChairPlacement.block(snappedBlockCenter(blockLocation), restore);
                }
            }
        }
        if (configured != null && configured.getType().isBlock()) {
            Location blockLocation = blockPlacementLocation(location);
            BlockRestore restore = captureBlockRestore(blockLocation);
            Location snappedBlockLocation = snappedBlockLocation(blockLocation);
            snappedBlockLocation.getBlock().setType(configured.getType(), false);
            orientVanillaBlockToYaw(snappedBlockLocation, yaw);
            return ChairPlacement.block(snappedBlockCenter(blockLocation), restore);
        }
        return ChairPlacement.none(location.clone());
    }

    private String orientedCraftEngineBlockState(String itemId, float yaw) {
        String normalized = itemId == null ? "" : itemId.trim();
        if (normalized.isEmpty() || normalized.contains("[")) {
            return normalized;
        }
        BlockFace facing = yawToBlockFace(yaw);
        return normalized + "[facing=" + facing.name().toLowerCase(Locale.ROOT) + "]";
    }

    private void orientVanillaBlockToYaw(Location location, float yaw) {
        BlockData data = location.getBlock().getBlockData();
        BlockFace facing = yawToBlockFace(yaw);
        boolean changed = false;
        if (data instanceof Directional directional && directional.getFaces().contains(facing)) {
            directional.setFacing(facing);
            changed = true;
        } else if (data instanceof Rotatable rotatable) {
            rotatable.setRotation(facing);
            changed = true;
        }
        if (changed) {
            location.getBlock().setBlockData(data, false);
        }
    }

    private BlockFace yawToBlockFace(float yaw) {
        float normalized = yaw % 360.0f;
        if (normalized < 0.0f) {
            normalized += 360.0f;
        }
        if (normalized >= 315.0f || normalized < 45.0f) {
            return BlockFace.SOUTH;
        }
        if (normalized < 135.0f) {
            return BlockFace.WEST;
        }
        if (normalized < 225.0f) {
            return BlockFace.NORTH;
        }
        return BlockFace.EAST;
    }

    private BlockRestore captureBlockRestore(Location location) {
        return new BlockRestore(snappedBlockLocation(location).getBlock().getState());
    }

    private Location blockPlacementLocation(Location location) {
        return location.clone().add(0.0, 1.0, 0.0);
    }

    private Location snappedBlockLocation(Location location) {
        return location.getBlock().getLocation();
    }

    private Location snappedBlockCenter(Location location) {
        Location base = snappedBlockLocation(location);
        return base.add(0.5, 0.0, 0.5);
    }

    private ItemStack joinItem() {
        ItemStack item = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message("加入", NamedTextColor.GREEN));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack readyItem() {
        ItemStack item = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message("准备", NamedTextColor.GREEN));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack startItem() {
        ItemStack item = new ItemStack(Material.YELLOW_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message("开始", NamedTextColor.GOLD));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack leaveItem() {
        ItemStack item = new ItemStack(Material.RED_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message("离开", NamedTextColor.RED));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack checkItem() {
        ItemStack item = new ItemStack(Material.LIME_CANDLE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message("过牌", NamedTextColor.GREEN));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack callItem(int amount) {
        ItemStack item = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message("跟注 " + amount, NamedTextColor.YELLOW));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack raiseItem(int amount) {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message("加注到 " + amount, NamedTextColor.GOLD));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack foldItem() {
        ItemStack item = new ItemStack(Material.GRAY_DYE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message("弃牌", NamedTextColor.RED));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack allInItem() {
        ItemStack item = new ItemStack(Material.REDSTONE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message("ALL-IN", NamedTextColor.LIGHT_PURPLE));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack dealerItem() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(message("按钮位", NamedTextColor.GOLD));
        item.setItemMeta(meta);
        return item;
    }

    private NamespacedKey model(String raw, NamespacedKey fallback) {
        NamespacedKey parsed = raw == null ? null : NamespacedKey.fromString(raw.trim());
        return parsed == null ? fallback : parsed;
    }

    private void applyPrivateVisibility(UUID ownerId, Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(ownerId)) {
                viewer.showEntity(plugin, entity);
            } else {
                viewer.hideEntity(plugin, entity);
            }
        }
    }

    private void hideFromOwner(UUID ownerId, Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(ownerId)) {
                viewer.hideEntity(plugin, entity);
            } else {
                viewer.showEntity(plugin, entity);
            }
        }
    }

    private void rememberButtonVisual(UUID bindingId, UUID displayId, Location baseLocation) {
        buttonDisplayByBinding.put(bindingId, displayId);
        buttonBaseLocationByDisplay.put(displayId, baseLocation.clone());
    }

    private void updateButtonHover(Player viewer) {
        if (plugin.isShuttingDown()) {
            return;
        }
        Entity target = viewer.getTargetEntity(6);
        UUID displayId = target == null ? null : buttonDisplayByBinding.get(target.getUniqueId());
        UUID previous = hoveredDisplayByViewer.get(viewer.getUniqueId());
        if (Objects.equals(previous, displayId)) {
            if (displayId != null) {
                buttonHoverGraceTicksByViewer.remove(viewer.getUniqueId());
            }
            return;
        }
        if (displayId == null) {
            // IMPORTANT REGRESSION GUARD:
            // Clear button hover immediately when the pointer leaves, otherwise the hover lift/glow visibly lingers.
            buttonHoverGraceTicksByViewer.remove(viewer.getUniqueId());
            hoveredDisplayByViewer.remove(viewer.getUniqueId());
            return;
        }
        buttonHoverGraceTicksByViewer.remove(viewer.getUniqueId());
        hoveredDisplayByViewer.put(viewer.getUniqueId(), displayId);
    }

    private void updateButtonHoverAnimations() {
        float riseStep = 1.0f / Math.max(1, plugin.getButtonHoverInterpolationTicks());
        float fallStep = Math.min(1.0f, riseStep * 1.8f);
        java.util.Set<UUID> activeDisplays = new java.util.LinkedHashSet<>(hoveredDisplayByViewer.values());
        for (UUID displayId : new ArrayList<>(buttonBaseLocationByDisplay.keySet())) {
            Entity entity = Bukkit.getEntity(displayId);
            if (!(entity instanceof ItemDisplay display)) {
                buttonHoverProgressByDisplay.remove(displayId);
                continue;
            }
            float current = buttonHoverProgressByDisplay.getOrDefault(displayId, 0.0f);
            float next = activeDisplays.contains(displayId)
                ? Math.min(1.0f, current + riseStep)
                : Math.max(0.0f, current - fallStep);
            if (Math.abs(next - current) < 0.0001f) {
                if (next <= 0.0001f) {
                    buttonHoverProgressByDisplay.remove(displayId);
                }
                continue;
            }
            if (next <= 0.0001f) {
                buttonHoverProgressByDisplay.remove(displayId);
            } else {
                buttonHoverProgressByDisplay.put(displayId, next);
            }
            float eased = applyCurve(next, plugin.buttonHoverAnimationCurve());
            float scale = BUTTON_SCALE * (1.0f + (plugin.getHoverButtonScale() - 1.0f) * eased);
            float lift = (float) (plugin.getHoverButtonLift() * eased);
            configureButtonAnimation(display);
            display.setTransformation(buttonTransformation(scale, lift));
            UUID hoverViewer = hoveredDisplayByViewer.entrySet().stream()
                .filter(entry -> displayId.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
            if (hoverViewer != null && plugin.isHoverGlowEnabled()) {
                display.setGlowing(true);
                display.setGlowColorOverride(plugin.previewGlowColorFor(hoverViewer));
            } else {
                display.setGlowing(false);
                display.setGlowColorOverride(null);
            }
        }
    }

    private Transformation buttonTransformation(float scale, float lift) {
        // Keep hover animation translation/scale-only here as well.
        // Do not add any dynamic rotation or rebound curve, otherwise button clicks regress into visible spin-back motion.
        return new Transformation(
            new Vector3f(0.0f, lift, 0.0f),
            new AxisAngle4f(),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f()
        );
    }

    private void clearButtonHoverNow(UUID viewerId) {
        UUID displayId = hoveredDisplayByViewer.remove(viewerId);
        buttonHoverGraceTicksByViewer.remove(viewerId);
        if (displayId == null) {
            return;
        }
        buttonHoverProgressByDisplay.remove(displayId);
        Entity entity = Bukkit.getEntity(displayId);
        if (entity instanceof ItemDisplay display) {
            configureButtonAnimation(display);
            display.setTransformation(buttonTransformation(BUTTON_SCALE, 0.0f));
            display.setGlowing(false);
            display.setGlowColorOverride(null);
        }
    }

    private void clearEntities(List<UUID> entities) {
        for (UUID entityId : entities) {
            joinBindings.remove(entityId);
            seatActionBindings.remove(entityId);
            UUID displayId = buttonDisplayByBinding.remove(entityId);
            if (displayId != null) {
                buttonBaseLocationByDisplay.remove(displayId);
                buttonHoverProgressByDisplay.remove(displayId);
                hoveredDisplayByViewer.entrySet().removeIf(entry -> entry.getValue().equals(displayId));
                buttonHoverGraceTicksByViewer.keySet().removeIf(viewerId -> !hoveredDisplayByViewer.containsKey(viewerId));
            }
            buttonBaseLocationByDisplay.remove(entityId);
            buttonHoverProgressByDisplay.remove(entityId);
            hoveredDisplayByViewer.entrySet().removeIf(entry -> entry.getValue().equals(entityId));
            buttonHoverGraceTicksByViewer.keySet().removeIf(viewerId -> !hoveredDisplayByViewer.containsKey(viewerId));
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    private static float easeOutCubic(float progress) {
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        float inverted = 1.0f - clamped;
        return 1.0f - inverted * inverted * inverted;
    }

    private static float linear(float progress) {
        return Math.max(0.0f, Math.min(1.0f, progress));
    }

    private static float easeInOutCubic(float progress) {
        float clamped = linear(progress);
        return clamped < 0.5f
            ? 4.0f * clamped * clamped * clamped
            : 1.0f - (float) Math.pow(-2.0f * clamped + 2.0f, 3.0f) / 2.0f;
    }

    private static float backOut(float progress) {
        float clamped = linear(progress);
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float value = 1.0f + c3 * (float) Math.pow(clamped - 1.0f, 3.0f) + c1 * (float) Math.pow(clamped - 1.0f, 2.0f);
        return Math.max(0.0f, Math.min(1.15f, value));
    }

    private static float applyCurve(float progress, DoudizhuPlugin.AnimationCurve curve) {
        return switch (curve) {
            case LINEAR -> linear(progress);
            case EASE_OUT -> easeOutCubic(progress);
            case EASE_IN_OUT -> easeInOutCubic(progress);
            case BACK_OUT -> backOut(progress);
        };
    }

    private void cleanupPlacedTable(PlacedTable placed) {
        clearCraftEngineEntities(placed.craftEngineVisualEntityIds);
        clearEntities(placed.entities);
        clearEntities(placed.communityEntityIds);
        clearEntities(placed.seatActionEntityIds);
        clearEntities(placed.dealerEntityIds);
        for (List<UUID> ids : placed.privateHoleEntities.values()) {
            clearEntities(ids);
        }
        for (List<UUID> ids : placed.publicBackEntities.values()) {
            clearEntities(ids);
        }
        placed.privateHoleEntities.clear();
        placed.publicBackEntities.clear();
        restoreBlocks(placed.blockRestores);
    }

    private void clearCraftEngineEntities(List<UUID> entityIds) {
        java.util.LinkedHashSet<UUID> uniqueIds = new java.util.LinkedHashSet<>(entityIds);
        for (UUID entityId : uniqueIds) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                plugin.getCraftEngineFurnitureService().removeFurniture(entity);
                forceRemoveEntityTree(entity);
            }
        }
        entityIds.clear();
    }

    private void forceRemoveEntityTree(Entity entity) {
        if (entity == null) {
            return;
        }
        for (Entity passenger : new ArrayList<>(entity.getPassengers())) {
            forceRemoveEntityTree(passenger);
        }
        if (entity.isValid()) {
            entity.remove();
        }
    }

    private void restoreBlocks(List<BlockRestore> blockRestores) {
        for (BlockRestore blockRestore : blockRestores) {
            blockRestore.originalState().update(true, false);
        }
        blockRestores.clear();
    }

    private void purgeResidualWorldArtifacts(Location anchor, float yaw, int maxPlayers) {
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        List<Location> hotspots = new ArrayList<>();
        Location tableLocation = anchor.clone().add(0.0, plugin.getTableDisplayHeight(), 0.0);
        hotspots.add(tableLocation);
        clearResidualPlacementBlock(blockPlacementLocation(tableLocation));
        for (double[] offset : seatOffsets(maxPlayers)) {
            Location chairLocation = rotate(anchor, yaw, offset[0], plugin.getChairBaseHeight(), offset[1]);
            hotspots.add(chairLocation);
            clearResidualPlacementBlock(blockPlacementLocation(chairLocation));
        }
        clearResidualEntities(hotspots, 1.0, 1.6);
    }

    private void clearResidualPlacementBlock(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        org.bukkit.block.Block block = snappedBlockLocation(location).getBlock();
        if (!block.getType().isAir()) {
            block.setType(Material.AIR, false);
        }
    }

    private void clearResidualEntities(List<Location> hotspots, double radiusXz, double radiusY) {
        java.util.LinkedHashSet<UUID> removed = new java.util.LinkedHashSet<>();
        for (Location hotspot : hotspots) {
            if (hotspot == null || hotspot.getWorld() == null) {
                continue;
            }
            for (Entity entity : hotspot.getWorld().getNearbyEntities(hotspot, radiusXz, radiusY, radiusXz)) {
                if (!removed.add(entity.getUniqueId())) {
                    continue;
                }
                if (entity instanceof Player) {
                    continue;
                }
                if (entity instanceof org.bukkit.entity.LivingEntity living && !(living instanceof org.bukkit.entity.ArmorStand)) {
                    continue;
                }
                if (entity instanceof ItemDisplay || entity instanceof TextDisplay || entity instanceof Interaction) {
                    forceRemoveEntityTree(entity);
                    continue;
                }
                if (plugin.getCraftEngineFurnitureService().removeFurniture(entity)) {
                    forceRemoveEntityTree(entity);
                }
            }
        }
    }

    private double sightDistance(Location eye, org.bukkit.util.Vector direction, Location center, double radius, double maxDistance) {
        if (eye == null || center == null || eye.getWorld() == null || center.getWorld() == null) {
            return -1.0;
        }
        if (!eye.getWorld().equals(center.getWorld())) {
            return -1.0;
        }
        org.bukkit.util.Vector offset = center.toVector().subtract(eye.toVector());
        double projection = offset.dot(direction);
        if (projection < 0.0 || projection > maxDistance) {
            return -1.0;
        }
        org.bukkit.util.Vector closest = eye.toVector().add(direction.clone().multiply(projection));
        double radiusSquared = radius * radius;
        return closest.distanceSquared(center.toVector()) <= radiusSquared ? projection : -1.0;
    }

    private void refreshCommunityCards(ZjhTable table, PlacedTable placed) {
        clearEntities(placed.communityEntityIds);
        placed.communityEntityIds.clear();
        Entity streetBanner = spawnText(
            rotate(placed.anchor, placed.yaw, 0.0, plugin.getTexasStatusHeight() - 0.02, 0.0),
            TypewriterTextStyle.joinInline(
                TypewriterTextStyle.warm(table.getStreet().displayName()),
                TypewriterTextStyle.meta(table.potBreakdownText())
            ),
            STATUS_SCALE
        );
        placed.communityEntityIds.add(streetBanner.getUniqueId());
        if (table.communityCards().isEmpty()) {
            return;
        }
        double spacing = plugin.getTexasCommunityCardSpacing();
        double start = -((table.communityCards().size() - 1) * 0.5);
        for (int index = 0; index < table.communityCards().size(); index++) {
            Location location = rotate(placed.anchor, placed.yaw, (start + index) * spacing, plugin.getTexasCommunityCardHeight(), 0.0);
            ItemDisplay display = spawnCard(location, cardItem(table.communityCards().get(index)), CARD_SCALE, placed.yaw);
            placed.communityEntityIds.add(display.getUniqueId());
            TextDisplay label = spawnText(location.clone().add(0.0, plugin.getPublicPreviewLabelHeight() + 0.08, 0.0), MuzTheme.cardLabel(table.communityCards().get(index).displayLabel()), TEXT_SCALE);
            mountTextDisplay(display, label, location.clone().add(0.0, plugin.getPublicPreviewLabelHeight() + 0.08, 0.0), false);
            placed.communityEntityIds.add(label.getUniqueId());
        }
    }

    private void refreshHoleCards(ZjhTable table, PlacedTable placed) {
        for (Integer seatIndex : new ArrayList<>(placed.privateHoleEntities.keySet())) {
            clearEntities(placed.privateHoleEntities.remove(seatIndex));
        }
        for (Integer seatIndex : new ArrayList<>(placed.publicBackEntities.keySet())) {
            clearEntities(placed.publicBackEntities.remove(seatIndex));
        }
        if (table.getPhase() != ZjhPhase.PLAYING) {
            return;
        }
        List<double[]> offsets = seatOffsets(table.getMaxPlayers());
        for (int seatIndex = 0; seatIndex < offsets.size(); seatIndex++) {
            UUID occupant = placed.seatAssignments.get(seatIndex);
            if (occupant == null) {
                continue;
            }
            List<DoudizhuCard> hole = table.handOf(occupant);
            if (hole.isEmpty()) {
                continue;
            }
            List<UUID> privateIds = new ArrayList<>();
            List<UUID> publicIds = new ArrayList<>();
            double[] offset = offsets.get(seatIndex);
            double start = -((hole.size() - 1) * 0.5);
            float yaw = (float) Math.toDegrees(Math.atan2(offset[0], -offset[1]));
            for (int index = 0; index < hole.size(); index++) {
                double shift = (start + index) * plugin.getTexasHoleCardSpacing();
                Location cardLocation = rotate(
                    placed.anchor,
                    placed.yaw,
                    offset[0] * plugin.getTexasHoleRadiusFactor() + lateralAxisX(offset) * shift,
                    plugin.getTexasHoleCardHeight(),
                    offset[1] * plugin.getTexasHoleRadiusFactor() + lateralAxisZ(offset) * shift
                );
                ItemDisplay front = spawnCard(cardLocation, cardItem(hole.get(index)), CARD_SCALE, yaw);
                privateIds.add(front.getUniqueId());
                applyPrivateVisibility(occupant, front);
                TextDisplay frontLabel = spawnText(cardLocation.clone().add(0.0, 0.28, 0.0), MuzTheme.cardLabel(hole.get(index).displayLabel()), TEXT_SCALE);
                mountTextDisplay(front, frontLabel, cardLocation.clone().add(0.0, 0.28, 0.0), false);
                privateIds.add(frontLabel.getUniqueId());
                applyPrivateVisibility(occupant, frontLabel);

                ItemDisplay back = spawnCard(cardLocation, backCardItem(), CARD_SCALE, yaw);
                publicIds.add(back.getUniqueId());
                hideFromOwner(occupant, back);
                TextDisplay mark = spawnText(cardLocation.clone().add(0.0, 0.28, 0.0), TypewriterTextStyle.meta("?"), TEXT_SCALE);
                mountTextDisplay(back, mark, cardLocation.clone().add(0.0, 0.28, 0.0), false);
                publicIds.add(mark.getUniqueId());
                hideFromOwner(occupant, mark);
            }
            placed.privateHoleEntities.put(seatIndex, privateIds);
            placed.publicBackEntities.put(seatIndex, publicIds);
        }
    }

    private void updateJoinButtons(ZjhTable table, PlacedTable placed) {
        if (plugin.isShuttingDown()) {
            return;
        }
        int seatCount = placed.seatLabelIds.size();
        for (int seatIndex = 0; seatIndex < seatCount; seatIndex++) {
            UUID occupant = placed.seatAssignments.get(seatIndex);
            for (UUID entityId : placed.entities) {
                JoinBinding binding = joinBindings.get(entityId);
                if (binding == null || binding.seatIndex != seatIndex) {
                    continue;
                }
                Entity entity = Bukkit.getEntity(entityId);
                if (entity == null) {
                    continue;
                }
                if (occupant == null) {
                    entity.setVisibleByDefault(true);
                    for (Player viewer : Bukkit.getOnlinePlayers()) {
                        viewer.showEntity(plugin, entity);
                    }
                } else {
                    entity.setVisibleByDefault(false);
                    for (Player viewer : Bukkit.getOnlinePlayers()) {
                        viewer.hideEntity(plugin, entity);
                    }
                }
            }
        }
    }

    private void refreshSeatActions(ZjhTable table, PlacedTable placed) {
        clearEntities(placed.seatActionEntityIds);
        placed.seatActionEntityIds.clear();
        List<double[]> offsets = seatOffsets(table.getMaxPlayers());
        for (int seatIndex = 0; seatIndex < offsets.size(); seatIndex++) {
            UUID occupant = placed.seatAssignments.get(seatIndex);
            if (occupant == null) {
                continue;
            }
            double[] offset = offsets.get(seatIndex);
            Location base = rotate(placed.anchor, placed.yaw, offset[0], plugin.getTexasActionButtonHeight(), offset[1]);
            if (table.getPhase() == ZjhPhase.LOBBY) {
                spawnSeatAction(placed, table.getName(), occupant, Action.READY, null, "准备", actionOffset(base, placed.yaw, offset, 0.0, 0.0, -0.30), readyItem());
                spawnSeatAction(placed, table.getName(), null, Action.START, null, "开始", actionOffset(base, placed.yaw, offset, 0.0, 0.0, 0.0), startItem());
                spawnSeatAction(placed, table.getName(), occupant, Action.LEAVE, null, "离开", actionOffset(base, placed.yaw, offset, 0.0, 0.0, 0.30), leaveItem());
            } else if (table.getPhase() == ZjhPhase.PLAYING && Objects.equals(occupant, table.getCurrentTurn()) && Bukkit.getPlayer(occupant) != null) {
                List<SeatActionLayout> layouts = new ArrayList<>();
                if (table.toCall(occupant) == 0) {
                    layouts.add(new SeatActionLayout(Action.CHECK, null, "过牌", checkItem()));
                } else {
                    layouts.add(new SeatActionLayout(Action.CALL, null, "跟注", callItem(table.toCall(occupant))));
                }
                List<Integer> raises = table.raiseOptions(occupant);
                for (Integer raiseTo : raises) {
                    layouts.add(new SeatActionLayout(Action.RAISE, raiseTo, "加到" + raiseTo, raiseItem(raiseTo)));
                }
                layouts.add(new SeatActionLayout(Action.FOLD, null, "弃牌", foldItem()));
                layouts.add(new SeatActionLayout(Action.ALL_IN, null, "全下", allInItem()));
                spawnSeatActionGrid(placed, table.getName(), occupant, base, placed.yaw, offset, layouts);
            }
        }
    }

    private void refreshDealerMarker(ZjhTable table, PlacedTable placed) {
        clearEntities(placed.dealerEntityIds);
        placed.dealerEntityIds.clear();
        UUID dealer = table.getDealerPlayer();
        if (dealer == null) {
            return;
        }
        int seatIndex = placed.seatAssignments.entrySet().stream()
            .filter(entry -> dealer.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(-1);
        if (seatIndex < 0) {
            return;
        }
        double[] offset = seatOffsets(table.getMaxPlayers()).get(seatIndex);
        Location markerLocation = rotate(
            placed.anchor,
            placed.yaw,
            offset[0] * plugin.getTexasDealerMarkerRadiusFactor(),
            plugin.getTexasDealerMarkerHeight(),
            offset[1] * plugin.getTexasDealerMarkerRadiusFactor()
        );
        ItemDisplay marker = spawnFurniture(markerLocation, dealerItem(), 0.32f);
        TextDisplay label = spawnText(markerLocation.clone().add(0.0, 0.24, 0.0), TypewriterTextStyle.warm("BTN"), buttonLabelTextScale());
        mountTextDisplay(marker, label, markerLocation.clone().add(0.0, 0.24, 0.0), false);
        placed.dealerEntityIds.add(marker.getUniqueId());
        placed.dealerEntityIds.add(label.getUniqueId());
    }

    private void spawnSeatAction(PlacedTable placed, String tableName, UUID ownerId, Action action, Integer amount, String labelText, Location location, ItemStack icon) {
        ItemDisplay button = spawnFurniture(location, icon, BUTTON_SCALE);
        configureButtonAnimation(button);
        Interaction interaction = spawnInteraction(location.clone().add(0.0, 0.05, 0.0), 0.28f, 0.28f);
        TextDisplay label = spawnText(location.clone().add(0.0, 0.26, 0.0), TypewriterTextStyle.focus(labelText), buttonLabelTextScale());
        mountTextDisplay(button, label, location.clone().add(0.0, 0.26, 0.0), false);
        placed.seatActionEntityIds.add(button.getUniqueId());
        placed.seatActionEntityIds.add(interaction.getUniqueId());
        placed.seatActionEntityIds.add(label.getUniqueId());
        seatActionBindings.put(button.getUniqueId(), new SeatActionBinding(tableName, ownerId, action, amount));
        seatActionBindings.put(interaction.getUniqueId(), new SeatActionBinding(tableName, ownerId, action, amount));
        seatActionBindings.put(label.getUniqueId(), new SeatActionBinding(tableName, ownerId, action, amount));
        rememberButtonVisual(button.getUniqueId(), button.getUniqueId(), location);
        rememberButtonVisual(label.getUniqueId(), button.getUniqueId(), location);
        rememberButtonVisual(interaction.getUniqueId(), button.getUniqueId(), location);
        if (ownerId != null) {
            applyPrivateVisibility(ownerId, button);
            applyPrivateVisibility(ownerId, interaction);
            applyPrivateVisibility(ownerId, label);
        }
    }

    private void spawnSeatActionGrid(PlacedTable placed, String tableName, UUID ownerId, Location base, float tableYaw, double[] seatOffset, List<SeatActionLayout> layouts) {
        int perRow = 3;
        double step = plugin.getTexasActionButtonStep();
        double rowDepth = 0.28;
        for (int index = 0; index < layouts.size(); index++) {
            int row = index / perRow;
            int col = index % perRow;
            int rowSize = Math.min(perRow, layouts.size() - row * perRow);
            double centered = col - ((rowSize - 1) * 0.5);
            double rowStart = -((Math.ceil((double) layouts.size() / perRow) - 1) * 0.5);
            double depth = (rowStart + row) * rowDepth;
            Location actionLocation = actionOffset(base, tableYaw, seatOffset, centered * step, 0.0, depth);
            SeatActionLayout layout = layouts.get(index);
            spawnSeatAction(placed, tableName, ownerId, layout.action(), layout.amount(), layout.labelText(), actionLocation, layout.icon());
        }
    }

    private Location actionOffset(Location base, float tableYaw, double[] seatOffset, double lateral, double vertical, double depth) {
        AxisPair axes = seatAxes(seatOffset, tableYaw);
        return base.clone().add(
            axes.lateralX() * lateral + axes.depthX() * depth,
            vertical,
            axes.lateralZ() * lateral + axes.depthZ() * depth
        );
    }

    private AxisPair seatAxes(double[] seatOffset, float tableYaw) {
        double radialLength = Math.sqrt(seatOffset[0] * seatOffset[0] + seatOffset[1] * seatOffset[1]);
        if (radialLength < 0.0001) {
            return new AxisPair(1.0, 0.0, 0.0, -1.0);
        }
        double localDepthX = -seatOffset[0] / radialLength;
        double localDepthZ = -seatOffset[1] / radialLength;
        double localLateralX = -localDepthZ;
        double localLateralZ = localDepthX;
        double radians = Math.toRadians(tableYaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double worldLateralX = localLateralX * cos - localLateralZ * sin;
        double worldLateralZ = localLateralX * sin + localLateralZ * cos;
        double worldDepthX = localDepthX * cos - localDepthZ * sin;
        double worldDepthZ = localDepthX * sin + localDepthZ * cos;
        return new AxisPair(worldLateralX, worldLateralZ, worldDepthX, worldDepthZ);
    }

    private Component seatInfo(ZjhTable table, int seatIndex, UUID seat) {
        if (seat == null) {
            return TypewriterTextStyle.joinLines(
                TypewriterTextStyle.warning("空位"),
                TypewriterTextStyle.meta("座位 " + (seatIndex + 1))
            );
        }
        List<Component> lines = new ArrayList<>();
        lines.add(TypewriterTextStyle.focus(table.displayName(seat)));
        lines.add(TypewriterTextStyle.meta("座位 " + (seatIndex + 1)));
        String position = table.positionLabel(seat);
        if (!position.isEmpty()) {
            lines.add(TypewriterTextStyle.warm(position));
        }
        lines.add(TypewriterTextStyle.meta(table.isBot(seat) ? "机器人" : "玩家"));
        lines.add(TypewriterTextStyle.meta("筹码 " + table.chipStack(seat)));
        if (Objects.equals(seat, table.getSmallBlindPlayer())) {
            lines.add(TypewriterTextStyle.meta("小盲"));
        }
        if (Objects.equals(seat, table.getBigBlindPlayer())) {
            lines.add(TypewriterTextStyle.meta("大盲"));
        }
        if (table.isFolded(seat)) {
            lines.add(TypewriterTextStyle.danger("已弃牌"));
        } else if (table.isAllIn(seat)) {
            lines.add(TypewriterTextStyle.warning("ALL-IN"));
        } else if (Objects.equals(table.getCurrentTurn(), seat)) {
            lines.add(TypewriterTextStyle.accent("当前行动"));
        }
        return TypewriterTextStyle.joinLines(lines.toArray(Component[]::new));
    }

    private float seatAvatarScale() {
        return TEXT_SCALE * Math.max(0.5f, plugin.getSeatAvatarScale());
    }

    private float statusAvatarScale() {
        return STATUS_SCALE * Math.max(0.45f, plugin.getStatusAvatarScale());
    }

    private Location statusAvatarLocation(Location anchor, float yaw) {
        return rotate(
            anchor,
            yaw,
            plugin.getStatusAvatarLateralOffset(),
            plugin.getTexasStatusHeight() + plugin.getStatusAvatarVerticalOffset(),
            plugin.getStatusAvatarDepthOffset()
        );
    }

    private Location statusAvatarNameLocation(Location anchor, float yaw) {
        return rotate(
            anchor,
            yaw,
            plugin.getStatusNameLateralOffset(),
            plugin.getTexasStatusHeight() + plugin.getStatusNameVerticalOffset(),
            plugin.getStatusNameDepthOffset()
        );
    }


    private Location seatAvatarLocation(Location anchor, float yaw, int seatCount, int seatIndex) {
        List<double[]> offsets = seatOffsets(seatCount);
        double[] offset = offsets.get(Math.min(seatIndex, offsets.size() - 1));
        return rotate(
            anchor,
            yaw,
            offset[0] + plugin.getSeatAvatarLateralOffset(),
            plugin.getTexasSeatLabelHeight() + plugin.getSeatAvatarVerticalOffset(),
            offset[1] + plugin.getSeatAvatarDepthOffset()
        );
    }

    private Location seatInfoLocation(Location anchor, float yaw, int seatCount, int seatIndex) {
        double gap = 0.22 + Math.max(0.0f, plugin.getSeatAvatarScale() - 1.0f) * 0.08;
        return seatAvatarLocation(anchor, yaw, seatCount, seatIndex).clone().add(0.0, -gap, 0.0);
    }

    private Location texasJoinLocation(Location anchor, float yaw, int seatCount, int seatIndex) {
        List<double[]> offsets = seatOffsets(seatCount);
        double[] offset = offsets.get(Math.min(seatIndex, offsets.size() - 1));
        return rotate(anchor, yaw, offset[0], plugin.getChairBaseHeight() + plugin.getTexasJoinButtonHeight(), offset[1]);
    }

    private Location joinLabelLocation(Location joinLocation) {
        return joinLocation.clone().add(0.0, plugin.getJoinLabelHeight(), 0.0);
    }

    private Component statusAvatar(ZjhTable table) {
        UUID focus = statusFocusPlayer(table);
        if (focus == null) {
            return Component.empty();
        }
        return plugin.playerHeadComponent(focus, table.displayName(focus), NamedTextColor.GOLD);
    }

    private Component statusAvatarName(ZjhTable table) {
        UUID focus = statusFocusPlayer(table);
        if (focus == null) {
            return Component.empty();
        }
        return plugin.playerNameComponent(focus, table.displayName(focus), NamedTextColor.WHITE);
    }

    private UUID statusFocusPlayer(ZjhTable table) {
        if (table == null) {
            return null;
        }
        if (table.getCurrentTurn() != null) {
            return table.getCurrentTurn();
        }
        if (!table.getSeats().isEmpty()) {
            return table.getSeats().getFirst();
        }
        return null;
    }

    private float buttonLabelTextScale() {
        return Math.max(0.08f, TEXT_SCALE * 0.5f);
    }

    private ItemDisplay spawnCard(Location location, ItemStack item, float scale, float yaw) {
        ItemDisplay display = VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            configureDisplayAnimation(spawned);
            protect(spawned);
        });
        display.setRotation(yaw, 0.0f);
        return display;
    }

    private ItemStack cardItem(DoudizhuCard card) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        VersionCompat.setItemModel(meta, PackAssets.cardModel(plugin, card));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backCardItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        VersionCompat.setItemModel(meta, PackAssets.backModel(plugin));
        item.setItemMeta(meta);
        return item;
    }

    private float chairYaw(double[] offset) {
        return (float) Math.toDegrees(Math.atan2(offset[0], -offset[1]));
    }

    private double lateralAxisX(double[] offset) {
        return Math.abs(offset[0]) > Math.abs(offset[1]) ? 0.0 : 1.0;
    }

    private double lateralAxisZ(double[] offset) {
        return Math.abs(offset[0]) > Math.abs(offset[1]) ? -Math.signum(offset[0]) : 0.0;
    }

    private Component message(String text, NamedTextColor color) {
        return MuzTheme.named(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private Component append(Component... components) {
        Component result = Component.empty();
        for (Component component : components) {
            if (component != null) {
                result = result.append(component);
            }
        }
        return result.decoration(TextDecoration.ITALIC, false);
    }

    private static float snappedYaw(float yaw) {
        return Math.round(yaw / 90.0f) * 90.0f;
    }

    private static Location rotate(Location anchor, float yaw, double x, double y, double z) {
        double radians = Math.toRadians(yaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double rx = x * cos - z * sin;
        double rz = x * sin + z * cos;
        return anchor.clone().add(rx, y, rz);
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static final class PlacedTable {
        private final List<UUID> entities;
        private final List<UUID> craftEngineVisualEntityIds;
        private final List<BlockRestore> blockRestores;
        private final List<UUID> seatAvatarIds;
        private final List<UUID> seatLabelIds;
        private final UUID statusId;
        private final UUID statusAvatarId;
        private final UUID statusAvatarNameId;
        private final Map<Integer, UUID> seatAssignments;
        private final List<UUID> seatActionEntityIds;
        private final List<UUID> dealerEntityIds;
        private final Map<Integer, List<UUID>> privateHoleEntities;
        private final Map<Integer, List<UUID>> publicBackEntities;
        private final List<UUID> communityEntityIds;
        private final Location anchor;
        private final float yaw;
        private final UUID ownerId;
        private final String ownerName;

        private PlacedTable(List<UUID> entities, List<UUID> craftEngineVisualEntityIds, List<BlockRestore> blockRestores, List<UUID> seatAvatarIds, List<UUID> seatLabelIds, UUID statusId, UUID statusAvatarId, UUID statusAvatarNameId, Map<Integer, UUID> seatAssignments, List<UUID> seatActionEntityIds, List<UUID> dealerEntityIds, Location anchor, float yaw, UUID ownerId, String ownerName) {
            this.entities = entities;
            this.craftEngineVisualEntityIds = craftEngineVisualEntityIds;
            this.blockRestores = blockRestores;
            this.seatAvatarIds = seatAvatarIds;
            this.seatLabelIds = seatLabelIds;
            this.statusId = statusId;
            this.statusAvatarId = statusAvatarId;
            this.statusAvatarNameId = statusAvatarNameId;
            this.seatAssignments = seatAssignments;
            this.seatActionEntityIds = seatActionEntityIds;
            this.dealerEntityIds = dealerEntityIds;
            this.privateHoleEntities = new LinkedHashMap<>();
            this.publicBackEntities = new LinkedHashMap<>();
            this.communityEntityIds = new ArrayList<>();
            this.anchor = anchor;
            this.yaw = yaw;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
        }
    }

    private record ChairPlacement(UUID entityId, Location seatBaseLocation, BlockRestore blockRestore, boolean craftEngineEntity) {
        private static ChairPlacement furniture(UUID entityId, Location seatBaseLocation, boolean craftEngineEntity) {
            return new ChairPlacement(entityId, seatBaseLocation, null, craftEngineEntity);
        }

        private static ChairPlacement block(Location seatBaseLocation, BlockRestore blockRestore) {
            return new ChairPlacement(null, seatBaseLocation, blockRestore, false);
        }

        private static ChairPlacement none(Location seatBaseLocation) {
            return new ChairPlacement(null, seatBaseLocation, null, false);
        }
    }

    private record BlockRestore(BlockState originalState) {
    }

    private record JoinBinding(String tableName, int seatIndex) {
    }

    private record SeatActionBinding(String tableName, UUID ownerId, Action action, Integer amount) {
    }

    private record SeatActionLayout(Action action, Integer amount, String labelText, ItemStack icon) {
    }

    private record AxisPair(double lateralX, double lateralZ, double depthX, double depthZ) {
    }

    private enum Action {
        READY,
        START,
        LEAVE,
        CHECK,
        CALL,
        RAISE,
        FOLD,
        ALL_IN
    }
}

