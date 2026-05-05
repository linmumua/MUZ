package dev.mumu.doudizhu.world;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.assets.PackAssets;
import dev.mumu.doudizhu.compat.VersionCompat;
import dev.mumu.doudizhu.game.SimpleBotBrain;
import dev.mumu.doudizhu.game.GamePhase;
import dev.mumu.doudizhu.game.GameTable;
import dev.mumu.doudizhu.model.CardRank;
import dev.mumu.doudizhu.model.DoudizhuCard;
import dev.mumu.doudizhu.room.TableLevel;
import dev.mumu.doudizhu.ui.MuzTheme;
import dev.mumu.doudizhu.ui.TypewriterTextStyle;
import java.util.ArrayList;
import java.util.EnumMap;
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
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class PhysicalTableManager {
    // 桌椅与按钮属于世界里的公共实体；手牌和个人按钮则是按玩家隐藏/显示的私有实体
    private static final String PROTECTED_ENTITY_TAG = "muz_table_protected";
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final float DEFAULT_PRIVATE_CARD_RENDER_SCALE = 0.50f;
    private static final float DEFAULT_PUBLIC_CARD_RENDER_SCALE = 0.58f;
    private static final int CARD_HOVER_GRACE_TICKS = 2;
    private static final int CARD_HOVER_SWITCH_TICKS = 1;
    private static final int ACTION_HOVER_GRACE_TICKS = 2;
    private long playDetailLastRefreshBucket = Long.MIN_VALUE;
    private final DoudizhuPlugin plugin;
    private final Map<String, PlacedTable> placedTables = new LinkedHashMap<>();
    private final Map<UUID, ActionBinding> actionBindings = new LinkedHashMap<>();
    private final Map<UUID, CardBinding> cardBindings = new LinkedHashMap<>();
    private final Map<UUID, Integer> hintIndices = new LinkedHashMap<>();
    private final Map<UUID, Integer> hoveredCardIds = new LinkedHashMap<>();
    private final Map<UUID, Integer> hoverCandidateCardIds = new LinkedHashMap<>();
    private final Map<UUID, Integer> hoverCandidateTicksByViewer = new LinkedHashMap<>();
    private final Map<UUID, Integer> hoverGraceTicksByViewer = new LinkedHashMap<>();
    private final Map<UUID, Map<Integer, Float>> hoverProgressByPlayer = new LinkedHashMap<>();
    private final Map<UUID, Map<Integer, Float>> selectedProgressByPlayer = new LinkedHashMap<>();
    private final Map<UUID, UUID> hoveredActionDisplayByViewer = new LinkedHashMap<>();
    private final Map<UUID, Integer> actionHoverGraceTicksByViewer = new LinkedHashMap<>();
    private final Map<UUID, Float> actionHoverProgressByDisplay = new LinkedHashMap<>();
    private final Map<UUID, UUID> actionDisplayByBinding = new LinkedHashMap<>();
    private final Set<UUID> actionDisplayIds = new LinkedHashSet<>();
    private final Map<String, String> actionSignatureByTable = new LinkedHashMap<>();
    private final Map<String, String> publicSignatureByTable = new LinkedHashMap<>();
    private final Map<String, Map<UUID, String>> privateHandSignatureByTable = new LinkedHashMap<>();
    private final Map<String, Map<UUID, String>> backsideHandSignatureByTable = new LinkedHashMap<>();

    public PhysicalTableManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public GameTable placeNewTable(Player owner, String name) {
        return placeNewTable(owner, name, TableLevel.FUN);
    }

    public GameTable placeNewTable(Player owner, String name, TableLevel roomLevel) {
        Location anchor = plugin.defaultTableAnchor(owner);
        return placeNewTableAt(owner, name, roomLevel, anchor, placementYaw(owner));
    }

    public GameTable placeNewTableAt(Player owner, String name, Location anchor, float yaw) {
        return placeNewTableAt(owner, name, TableLevel.FUN, anchor, yaw);
    }

    public GameTable placeNewTableAt(Player owner, String name, TableLevel roomLevel, Location anchor, float yaw) {
        return placeNewTableInternal(owner, name, roomLevel, anchor, yaw);
    }

    public float placementYaw(Player owner) {
        // HARD-CODED TABLE FACING:
        // The player's own side must stay open when placing a 斗地主 table.
        // We therefore flip the snapped facing by 180 degrees so the side nearest the placer is the missing-chair side.
        // Do not change this back unless the user explicitly asks for a different placement convention.
        return snappedYaw(owner.getLocation().getYaw() + 180.0f);
    }

    public Location placementAnchor(org.bukkit.block.Block floorBlock) {
        return floorBlock.getLocation().add(0.5, plugin.getTableSpawnOffsetY(), 0.5);
    }

    public Location previewTableCenter(Location anchor) {
        return anchor.clone().add(0.0, plugin.getTableDisplayHeight(), 0.0);
    }

    public List<Location> previewChairBases(Location anchor, float yaw) {
        List<Location> seats = new ArrayList<>(3);
        for (int index = 0; index < 3; index++) {
            Vector chairAdjustment = chairVisualAdjustment(index);
            seats.add(rotate(
                anchor,
                yaw,
                chairOffsets(index)[0] + chairAdjustment.x(),
                plugin.getChairBaseHeight() + chairAdjustment.y(),
                chairOffsets(index)[1] + chairAdjustment.z()
            ));
        }
        return seats;
    }

    public Location previewOpenSide(Location anchor, float yaw) {
        return rotate(anchor, yaw, 0.0, plugin.getChairBaseHeight(), plugin.getChairDistance());
    }

    public String placementObstructionReason(Location anchor, float yaw) {
        if (anchor == null || anchor.getWorld() == null) {
            return "这里暂时还不能放牌桌。";
        }
        ensureChunkReady(anchor);
        String tableReason = obstructionReason("桌面", blockPlacementLocation(previewTableCenter(anchor)), 0.95, -0.10, 0.95);
        if (tableReason != null) {
            return tableReason;
        }
        List<Location> chairBases = previewChairBases(anchor, yaw);
        for (int index = 0; index < chairBases.size(); index++) {
            String chairReason = obstructionReason("椅子 " + (index + 1), blockPlacementLocation(chairBases.get(index)), 0.55, -0.10, 1.05);
            if (chairReason != null) {
                return chairReason;
            }
        }
        return null;
    }

    private GameTable placeNewTableInternal(Player owner, String name, TableLevel roomLevel, Location anchor, float yaw) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            throw new IllegalArgumentException("这个牌桌已经有实体桌面了。");
        }
        if (plugin.getTableManager().getTableOf(owner) != null) {
            throw new IllegalArgumentException("你已经在另一个牌桌里了。");
        }
        String obstruction = placementObstructionReason(anchor, yaw);
        if (obstruction != null) {
            throw new IllegalStateException(obstruction);
        }
        GameTable table = plugin.getTableManager().getTable(name);
        if (table == null) {
            table = plugin.getTableManager().createTable(name, roomLevel);
        }
        placedTables.put(key, spawnTable(table, anchor.clone(), yaw, owner.getUniqueId(), owner.getName()));
        plugin.persistDoudizhuTable(table.getName(), table.getRoomLevel(), anchor, yaw, owner.getUniqueId(), owner.getName());
        refresh(table);
        return table;
    }

    public GameTable restoreTable(String name, TableLevel roomLevel, Location anchor, float yaw, UUID ownerId, String ownerName) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            return plugin.getTableManager().getTable(name);
        }
        ensureWorldVisualsReady("恢复牌桌");
        ensureChunkReady(anchor);
        purgeResidualWorldArtifacts(anchor, yaw);
        GameTable table = plugin.getTableManager().getTable(name);
        if (table == null) {
            table = plugin.getTableManager().createTable(name, roomLevel);
        } else {
            table.setRoomLevel(roomLevel);
        }
        placedTables.put(key, spawnTable(table, anchor.clone(), yaw, ownerId, ownerName));
        refresh(table);
        return table;
    }

    public boolean isPlaced(String tableName) {
        return placedTable(tableName) != null;
    }

    public int placedTableCount() {
        return placedTables.size();
    }

    public List<String> placedTableNames() {
        return placedTables.values().stream()
            .map(PlacedTable::tableName)
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
            // Startup warmup can rebuild the same persisted tables multiple times.
            // If any old chair/table visual survives tracked cleanup, the next rebuild would stack another copy on top.
            // Always purge anchor-side residual world artifacts before respawning the rebuilt table.
            purgeResidualWorldArtifacts(previous.anchor(), previous.yaw());
            GameTable table = plugin.getTableManager().getTable(previous.tableName());
            if (table == null) {
                continue;
            }
            PlacedTable rebuilt = spawnTable(table, previous.anchor().clone(), previous.yaw(), previous.ownerId(), previous.ownerName());
            rebuilt.seatAssignments().putAll(previous.seatAssignments());
            placedTables.put(entry.getKey(), rebuilt);
            refresh(table);
        }
    }

    public void repairIncompleteTables(String reason) {
        List<String> targets = new ArrayList<>();
        for (PlacedTable placed : placedTables.values()) {
            if (placed == null) {
                continue;
            }
            if (isIncomplete(placed)) {
                targets.add(placed.tableName() + "(" + incompleteReason(placed) + ")");
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        if (reason != null && reason.startsWith("viewer-")) {
            plugin.getLogger().fine("[MUZ/repair/ddz] reason=" + reason + " tables=" + targets);
        } else {
            plugin.getLogger().warning("[MUZ/repair/ddz] reason=" + reason + " tables=" + targets);
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
            purgeResidualWorldArtifacts(previous.anchor().clone().add(0.0, deltaY, 0.0), previous.yaw());
            GameTable table = plugin.getTableManager().getTable(previous.tableName());
            if (table == null) {
                continue;
            }
            PlacedTable rebuilt = spawnTable(table, previous.anchor().clone().add(0.0, deltaY, 0.0), previous.yaw(), previous.ownerId(), previous.ownerName());
            rebuilt.seatAssignments().putAll(previous.seatAssignments());
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
        purgeResidualWorldArtifacts(previous.anchor(), previous.yaw());
        GameTable table = plugin.getTableManager().getTable(previous.tableName());
        if (table == null) {
            return;
        }
        PlacedTable rebuilt = spawnTable(table, previous.anchor().clone(), previous.yaw(), previous.ownerId(), previous.ownerName());
        rebuilt.seatAssignments().putAll(previous.seatAssignments());
        placedTables.put(key, rebuilt);
        refresh(table);
    }

    private boolean isIncomplete(PlacedTable placed) {
        return !incompleteReason(placed).isBlank();
    }

    private String incompleteReason(PlacedTable placed) {
        List<String> missing = new ArrayList<>();
        if (placed.statusDisplayId() == null || Bukkit.getEntity(placed.statusDisplayId()) == null) {
            missing.add("status");
        }
        if (placed.playDetailDisplayId() == null || Bukkit.getEntity(placed.playDetailDisplayId()) == null) {
            missing.add("play-detail");
        }
        if (placed.statusAvatarDisplayId() == null || Bukkit.getEntity(placed.statusAvatarDisplayId()) == null) {
            missing.add("status-avatar");
        }
        if (placed.statusAvatarNameDisplayId() == null || Bukkit.getEntity(placed.statusAvatarNameDisplayId()) == null) {
            missing.add("status-avatar-name");
        }
        if (placed.seatAvatarDisplayIds().size() < 3 || placed.seatNameDisplayIds().size() < 3 || placed.seatInfoDisplayIds().size() < 3) {
            missing.add("seat-display-count");
        }
        for (UUID id : placed.seatAvatarDisplayIds()) {
            if (Bukkit.getEntity(id) == null) {
                missing.add("seat-avatar");
                break;
            }
        }
        for (UUID id : placed.seatNameDisplayIds()) {
            if (Bukkit.getEntity(id) == null) {
                missing.add("seat-name");
                break;
            }
        }
        for (UUID id : placed.seatInfoDisplayIds()) {
            if (Bukkit.getEntity(id) == null) {
                missing.add("seat-info");
                break;
            }
        }
        return String.join(",", missing);
    }

    public void refresh(GameTable table) {
        // 统一刷新入口：桌面状态、座位信息、按钮、公共出牌区、私人手牌都在这里协同更新
        if (table == null) {
            return;
        }
        PlacedTable placed = placedTable(table.getName());
        if (placed == null) {
            return;
        }
        reconcileSeatAssignments(table, placed);
        refreshStatus(table, placed);
        refreshStatusAvatar(table, placed);
        refreshPlayDetail(table, placed);
        refreshSeatInfos(table, placed);
        refreshActionButtons(table, placed);
        refreshPublicTrick(table, placed);
        refreshPrivateHands(table, placed);
        plugin.persistDoudizhuTable(table.getName(), table.getRoomLevel(), placed.anchor(), placed.yaw(), placed.ownerId(), placed.ownerName());
    }

    public void refreshPrivateHand(GameTable table, UUID playerId) {
        if (table == null) {
            return;
        }
        PlacedTable placed = placedTable(table.getName());
        if (placed == null) {
            return;
        }
        renderPrivateHand(table, placed, playerId);
    }

    public Location tableAnchor(String tableName) {
        PlacedTable placed = placedTable(tableName);
        return placed == null ? null : placed.anchor().clone();
    }

    public float tableYaw(String tableName) {
        PlacedTable placed = placedTable(tableName);
        return placed == null ? 0.0f : placed.yaw();
    }

    public boolean canRemoveTable(Player player, String tableName) {
        if (player == null) {
            return false;
        }
        if (player.hasPermission("muz.admin")) {
            return true;
        }
        PlacedTable placed = placedTable(tableName);
        return placed != null && placed.ownerId() != null && placed.ownerId().equals(player.getUniqueId());
    }

    public String removeDeniedReason(Player player, String tableName) {
        PlacedTable placed = placedTable(tableName);
        if (placed == null) {
            return "找不到这张牌桌。";
        }
        if (player != null && player.hasPermission("muz.admin")) {
            return "";
        }
        if (placed.ownerId() == null) {
            return "这张牌桌没有记录放置者，只有管理员才能拆。";
        }
        String owner = placed.ownerName() == null || placed.ownerName().isBlank() ? "原放置者" : placed.ownerName();
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
        for (PlacedTable placed : placedTables.values()) {
            Location center = previewTableCenter(placed.anchor());
            double distance = sightDistance(eye, direction, center, 1.25, maxDistance);
            if (distance >= 0.0 && distance < bestDistance) {
                bestDistance = distance;
                bestTable = placed.tableName();
            }
        }
        return bestTable;
    }

    public void hidePrivateEntitiesFrom(Player viewer) {
        for (PlacedTable placed : placedTables.values()) {
            for (Map.Entry<UUID, List<UUID>> entry : placed.privateEntitiesByPlayer().entrySet()) {
                if (entry.getKey().equals(viewer.getUniqueId())) {
                    continue;
                }
                for (UUID entityId : entry.getValue()) {
                    Entity entity = Bukkit.getEntity(entityId);
                    if (entity != null) {
                        viewer.hideEntity(plugin, entity);
                    }
                }
            }
        }
    }

    public void syncViewer(Player viewer) {
        if (viewer == null || plugin.isShuttingDown()) {
            return;
        }
        List<String> incomplete = new ArrayList<>();
        for (PlacedTable placed : placedTables.values()) {
            String reason = incompleteReason(placed);
            if (!reason.isBlank()) {
                incomplete.add(placed.tableName() + "(" + reason + ")");
            }
        }
        if (!incomplete.isEmpty()) {
            plugin.getLogger().fine("[MUZ/viewer-sync/ddz] viewer=" + viewer.getName() + " incomplete=" + incomplete);
            for (String tableName : incomplete.stream().map(entry -> entry.substring(0, entry.indexOf('('))).toList()) {
                rebuildSingleTable(tableName);
            }
        }
        clearHover(viewer.getUniqueId());
        clearActionHover(viewer.getUniqueId());
        actionSignatureByTable.clear();
        publicSignatureByTable.clear();
        privateHandSignatureByTable.clear();
        backsideHandSignatureByTable.clear();
        for (PlacedTable placed : placedTables.values()) {
            GameTable table = plugin.getTableManager().getTable(placed.tableName());
            if (table != null) {
                refresh(table);
            }
            showPublicEntitiesTo(viewer, placed.staticEntities());
            showPublicEntitiesTo(viewer, placed.publicEntities());
            showPublicEntitiesTo(viewer, placed.seatAvatarDisplayIds());
            showPublicEntitiesTo(viewer, placed.seatNameDisplayIds());
            showPublicEntitiesTo(viewer, placed.seatInfoDisplayIds());
            if (placed.statusDisplayId() != null) {
                showPublicEntitiesTo(viewer, List.of(placed.statusDisplayId()));
            }
            if (placed.playDetailDisplayId() != null) {
                showPublicEntitiesTo(viewer, List.of(placed.playDetailDisplayId()));
            }
            if (placed.statusAvatarDisplayId() != null) {
                showPublicEntitiesTo(viewer, List.of(placed.statusAvatarDisplayId()));
            }
            if (placed.statusAvatarNameDisplayId() != null) {
                showPublicEntitiesTo(viewer, List.of(placed.statusAvatarNameDisplayId()));
            }
        }
        hidePrivateEntitiesFrom(viewer);
    }

    private void showPublicEntitiesTo(Player viewer, List<UUID> entityIds) {
        for (UUID entityId : entityIds) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                viewer.showEntity(plugin, entity);
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

    public boolean handleInteraction(Player player, Entity entity) {
        CardBinding cardBinding = cardBindings.get(entity.getUniqueId());
        if (cardBinding != null) {
            if (!cardBinding.ownerId().equals(player.getUniqueId())) {
                return true;
            }
            GameTable table = plugin.getTableManager().getTable(cardBinding.tableName());
            if (table != null) {
                boolean wasSelected = table.getSelection(player.getUniqueId()).contains(cardBinding.cardId());
                table.toggleSelection(player.getUniqueId(), cardBinding.cardId());
                PlacedTable placed = placedTable(table.getName());
                if (placed != null) {
                    updatePrivateSelection(table, placed, player.getUniqueId());
                    updateBacksideSelection(table, placed, player.getUniqueId());
                    refreshPublicTrick(table, placed);
                }
                playSelectionSound(player, !wasSelected);
            }
            return true;
        }

        ActionBinding binding = actionBindings.get(entity.getUniqueId());
        if (binding != null) {
            clearActionHoverNow(player.getUniqueId());
            GameTable table = plugin.getTableManager().getTable(binding.tableName());
            if (table == null) {
                return true;
            }

            try {
                switch (binding.action()) {
                    case JOIN -> joinSeat(table, placedTable(table.getName()), player, binding.seatIndex());
                    case READY -> table.toggleReady(player);
                    case START -> table.startRound(player);
                    case STATUS -> hint(player, "查看桌面上方状态面板。", NamedTextColor.YELLOW);
                    case LEAVE -> plugin.getTableManager().leaveTable(player);
                    case PLAY_SELECTED -> table.playSelected(player);
                    case PASS_TURN -> table.pass(player);
                    case HINT_PLAY -> applyHint(table, player);
                    case CLEAR_SELECTION -> {
                        table.clearSelection(player.getUniqueId());
                        refreshPrivateHand(table, player.getUniqueId());
                        hint(player, "已清除已选牌。", NamedTextColor.GRAY);
                    }
                    case DOUBLE_NO -> table.chooseDouble(player, false);
                    case DOUBLE_YES -> table.chooseDouble(player, true);
                    case OPEN_SETTINGS -> {
                        plugin.getHandGuiService().openSettings(player);
                        hint(player, "已打开你的个人微调菜单。", NamedTextColor.GREEN);
                    }
                    case BID_0 -> table.bid(player, 0);
                    case BID_1 -> table.bid(player, 1);
                    case BID_2 -> table.bid(player, 2);
                    case BID_3 -> table.bid(player, 3);
                }
                refresh(table);
            } catch (RuntimeException exception) {
                hint(player, exception.getMessage(), NamedTextColor.RED);
            }
            return true;
        }

        return false;
    }

    public boolean handleAttack(Player player, Entity entity) {
        CardBinding cardBinding = cardBindings.get(entity.getUniqueId());
        if (cardBinding == null) {
            return false;
        }
        if (!cardBinding.ownerId().equals(player.getUniqueId())) {
            return true;
        }
        GameTable table = plugin.getTableManager().getTable(cardBinding.tableName());
        if (table == null) {
            return true;
        }
        try {
            Set<Integer> selection = table.getSelection(player.getUniqueId());
            if (selection.isEmpty()) {
                hint(player, "请先右键选择要出的牌。", NamedTextColor.YELLOW);
                return true;
            }
            if (!selection.contains(cardBinding.cardId())) {
                hint(player, "请左键点击已选中的牌来出牌。", NamedTextColor.YELLOW);
                return true;
            }
            table.playSelected(player);
            refresh(table);
        } catch (RuntimeException exception) {
            hint(player, exception.getMessage(), NamedTextColor.RED);
            refresh(table);
        }
        return true;
    }

    public void removeTable(String tableName) {
        GameTable table = plugin.getTableManager().getTable(tableName);
        if (table != null && table.getSeats().stream().anyMatch(playerId -> !table.isBot(playerId))) {
            throw new IllegalStateException("请先让玩家离桌，再移除实体牌桌。");
        }
        PlacedTable placed = removePlacedTable(tableName);
        if (placed == null) {
            throw new IllegalArgumentException("这个牌桌没有实体桌面。");
        }
        cleanupPlacedTable(placed);
        // HARD-CODED REMOVAL SAFETY:
        // After a server restart, tracked entity ids can be incomplete while world-side furniture/blocks still exist.
        // A normal tracked cleanup is not enough, so remove now also performs an anchor-based residual sweep over the
        // expected table/chair locations. Do not delete this fallback unless the user explicitly asks.
        purgeResidualWorldArtifacts(placed.anchor(), placed.yaw());
        if (table != null) {
            plugin.getTableManager().unregisterTable(table.getName());
        }
        plugin.deletePersistedTable("DOUDIZHU", tableName);
    }

    public void forceRemoveTable(String tableName) {
        PlacedTable placed = removePlacedTable(tableName);
        if (placed != null) {
            cleanupPlacedTable(placed);
            purgeResidualWorldArtifacts(placed.anchor(), placed.yaw());
        }
        plugin.getTableManager().unregisterTable(tableName);
        plugin.deletePersistedTable("DOUDIZHU", tableName);
    }

    public void shutdown() {
        if (plugin.getServer().isStopping()) {
            placedTables.clear();
            actionBindings.clear();
            cardBindings.clear();
            hintIndices.clear();
            hoveredCardIds.clear();
            hoverCandidateCardIds.clear();
            hoverCandidateTicksByViewer.clear();
            hoverGraceTicksByViewer.clear();
            hoverProgressByPlayer.clear();
            selectedProgressByPlayer.clear();
            hoveredActionDisplayByViewer.clear();
            actionHoverGraceTicksByViewer.clear();
            actionHoverProgressByDisplay.clear();
            actionDisplayByBinding.clear();
            actionDisplayIds.clear();
            actionSignatureByTable.clear();
            publicSignatureByTable.clear();
            privateHandSignatureByTable.clear();
            backsideHandSignatureByTable.clear();
            return;
        }
        for (PlacedTable placed : new ArrayList<>(placedTables.values())) {
            cleanupPlacedTable(placed);
        }
        placedTables.clear();
        actionBindings.clear();
        cardBindings.clear();
    }

    private void ensureWorldVisualsReady(String action) {
        if (canSafelyReplaceWorldVisuals()) {
            return;
        }
        throw new IllegalStateException("CraftEngine 桌椅资源还没准备好，稍后再" + action + "。");
    }

    private boolean canSafelyReplaceWorldVisuals() {
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
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            GameTable table = plugin.getTableManager().getTableOf(viewer);
            if (table == null) {
                clearHover(viewer.getUniqueId());
                clearActionHover(viewer.getUniqueId());
                continue;
            }
            updateHoverState(table, viewer);
            updateActionHoverState(viewer);
            PlacedTable placed = placedTable(table.getName());
            if (placed != null) {
                updatePrivateSelection(table, placed, viewer.getUniqueId());
                updateBacksideSelection(table, placed, viewer.getUniqueId());
                updateViewerPreviewEntities(table, placed, viewer.getUniqueId());
            }
        }
        updateActionHoverAnimations();
        long bucket = System.currentTimeMillis() / 2000L;
        if (bucket != playDetailLastRefreshBucket) {
            playDetailLastRefreshBucket = bucket;
            for (PlacedTable placed : placedTables.values()) {
                GameTable table = plugin.getTableManager().getTable(placed.tableName());
                if (table != null) {
                    refreshPlayDetail(table, placed);
                }
            }
        }
    }

    private void reconcileSeatAssignments(GameTable table, PlacedTable placed) {
        // 座位分配与 GameTable 的玩家列表保持同步，并去掉失效/重复绑定
        placed.seatAssignments().entrySet().removeIf(entry -> !table.contains(entry.getValue()));
        List<UUID> seen = new ArrayList<>();
        placed.seatAssignments().entrySet().removeIf(entry -> !seen.add(entry.getValue()));
        for (UUID playerId : table.getSeats()) {
            if (placed.seatAssignments().containsValue(playerId)) {
                continue;
            }
            for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
                if (!placed.seatAssignments().containsKey(seatIndex)) {
                    placed.seatAssignments().put(seatIndex, playerId);
                    break;
                }
            }
        }
    }

    private void joinSeat(GameTable table, PlacedTable placed, Player player, Integer seatIndex) {
        // 点击哪张座位的“加入”按钮，就强制把该玩家绑定到哪个座位
        if (placed == null || seatIndex == null) {
            throw new IllegalStateException("座位信息异常。");
        }
        if (placed.seatAssignments().containsKey(seatIndex)) {
            if (player.getUniqueId().equals(placed.seatAssignments().get(seatIndex))) {
                throw new IllegalStateException("你已经在这个座位上了。");
            }
            throw new IllegalStateException("这个座位已经有人了。");
        }
        GameTable currentTable = plugin.getTableManager().getTableOf(player);
        dev.mumu.doudizhu.zhajinhua.ZjhTable currentTexas = plugin.getZjhManager().getTableOf(player);
        boolean switchingFromOtherDdz = currentTable != null && !currentTable.getName().equalsIgnoreCase(table.getName());
        boolean switchingFromTexas = currentTexas != null;
        if ((switchingFromOtherDdz || switchingFromTexas) && table.getPhase() != GamePhase.LOBBY) {
            throw new IllegalStateException("目标牌桌这局已经开始了，暂时不能加入。");
        }
        if (currentTable != null && table.getPhase() != GamePhase.LOBBY) {
            throw new IllegalStateException("对局开始后不能切换座位。");
        }
        UUID playerId = player.getUniqueId();
        if ((switchingFromOtherDdz || switchingFromTexas) && !plugin.canAffordEntry(playerId, table.getRoomLevel())) {
            throw new IllegalStateException(plugin.insufficientEntryMessage(playerId, table.getRoomLevel()));
        }
        int previousSeat = placedSeatIndex(placed, playerId);
        if (previousSeat == seatIndex) {
            throw new IllegalStateException("你已经在这个座位上了。");
        }
        if (switchingFromOtherDdz) {
            plugin.getTableManager().leaveTable(player);
            currentTable = null;
        } else if (switchingFromTexas) {
            plugin.getZjhManager().leaveTable(player);
        }
        if (previousSeat >= 0) {
            placed.seatAssignments().remove(previousSeat);
            placed.seatAssignments().put(seatIndex, playerId);
            hint(player, "你已切换到座位 " + (seatIndex + 1) + "。", NamedTextColor.GREEN);
            return;
        }
        placed.seatAssignments().put(seatIndex, player.getUniqueId());
        try {
            plugin.getTableManager().joinTable(player, table.getName());
        } catch (RuntimeException exception) {
            placed.seatAssignments().remove(seatIndex);
            throw exception;
        }
        placed.seatAssignments().entrySet().removeIf(entry -> !entry.getKey().equals(seatIndex) && entry.getValue().equals(player.getUniqueId()));
        hint(player, "你已加入 " + table.getName() + " 号牌桌的座位 " + (seatIndex + 1) + "。", NamedTextColor.GREEN);
    }

    private void hint(Player player, String text, NamedTextColor color) {
        player.sendActionBar(message(text, color));
    }

    private void applyJoinVisibility(GameTable table, Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.showEntity(plugin, entity);
        }
    }

    public boolean isProtectedEntity(UUID entityId) {
        Entity entity = Bukkit.getEntity(entityId);
        while (entity != null) {
            if (entity.getScoreboardTags().contains(PROTECTED_ENTITY_TAG)) {
                return true;
            }
            UUID currentId = entity.getUniqueId();
            for (PlacedTable placed : placedTables.values()) {
                if (placed.staticEntities().contains(currentId)
                    || placed.actionEntities().contains(currentId)
                    || placed.publicEntities().contains(currentId)
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
            for (BlockRestore blockRestore : placed.blockRestores()) {
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

    private PlacedTable spawnTable(GameTable table, Location anchor, float yaw, UUID ownerId, String ownerName) {
        ensureChunkReady(anchor);
        // 这里只生成“永久桌面层”：桌子、椅子、桌顶状态文字
        List<UUID> staticEntities = new ArrayList<>();
        PlacedTable placed = new PlacedTable(
            table.getName(),
            anchor.clone(),
            yaw,
            ownerId,
            ownerName,
            staticEntities,
            new ArrayList<>(),
            new ArrayList<>(),
            new LinkedHashMap<>(),
            new ArrayList<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new LinkedHashMap<>(),
            new ArrayList<>(),
            null,
            null,
            null,
            null,
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>()
        );

        Location tableLocation = anchor.clone().add(0.0, plugin.getTableDisplayHeight(), 0.0);
        TablePlacement tablePlacement = spawnTableVisual(tableLocation, yaw);
        UUID tableVisualId;
        if (tablePlacement.entityId() != null) {
            addEntityTreeIds(tablePlacement.entityId(), staticEntities);
            tableVisualId = tablePlacement.entityId();
            if (tablePlacement.craftEngineEntity()) {
                addEntityTreeIds(tablePlacement.entityId(), placed.craftEngineVisualEntities());
            }
        } else {
            ItemDisplay fallbackTableDisplay = spawnFurnitureDisplay(tableLocation, tableItem(), plugin.getTableScale());
            staticEntities.add(fallbackTableDisplay.getUniqueId());
            tableVisualId = fallbackTableDisplay.getUniqueId();
        }
        if (tablePlacement.blockRestore() != null) {
            placed.blockRestores().add(tablePlacement.blockRestore());
        }

        for (int index = 0; index < 3; index++) {
            Vector chairAdjustment = chairVisualAdjustment(index);
            Location chairLocation = rotate(
                anchor,
                yaw,
                chairOffsets(index)[0] + chairAdjustment.x(),
                plugin.getChairBaseHeight() + chairAdjustment.y(),
                chairOffsets(index)[1] + chairAdjustment.z()
            );
            ChairPlacement chairPlacement = spawnChairVisual(chairLocation, yaw + chairYawOffset(index) + (float) plugin.getChairRotationDegrees());
            if (chairPlacement.entityId() != null) {
                addEntityTreeIds(chairPlacement.entityId(), staticEntities);
                if (chairPlacement.craftEngineEntity()) {
                    addEntityTreeIds(chairPlacement.entityId(), placed.craftEngineVisualEntities());
                }
            }
            if (chairPlacement.blockRestore() != null) {
                placed.blockRestores().add(chairPlacement.blockRestore());
            }

            Location seatBase = chairPlacement.seatBaseLocation();
            placed.seatBaseLocations().add(seatBase.clone());

            // HARD-CODED AVATAR / NAME / META SPLIT:
            // Chair-side avatar, chair-side player name, and chair-side meta text must stay as three separate display entities.
            // Avatar now uses an ItemDisplay with a real PLAYER_HEAD to avoid player-head text-object refresh issues.
            ItemDisplay seatAvatar = spawnAvatarDisplay(
                seatAvatarLocation(seatBase, yaw),
                seatAvatarItem(table, index),
                seatAvatarScale()
            );
            staticEntities.add(seatAvatar.getUniqueId());
            placed.seatAvatarDisplayIds().add(seatAvatar.getUniqueId());

            TextDisplay seatName = spawnText(
                seatNameLocation(table, index, seatBase, yaw),
                seatName(table, index),
                Display.Billboard.CENTER,
                false,
                seatNameScale(table, index)
            );
            staticEntities.add(seatName.getUniqueId());
            placed.seatNameDisplayIds().add(seatName.getUniqueId());

            TextDisplay seatInfo = spawnText(
                seatInfoLocation(table, index, seatBase, yaw),
                seatInfo(table, index),
                Display.Billboard.CENTER,
                false,
                plugin.getSmallTextScale()
            );
            staticEntities.add(seatInfo.getUniqueId());
            placed.seatInfoDisplayIds().add(seatInfo.getUniqueId());
        }

        TextDisplay status = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getStatusHeight(), 0.0),
            buildStatus(table),
            Display.Billboard.CENTER,
            false,
            plugin.getStatusTextScale()
        );
        placed = placed.withStatusDisplayId(status.getUniqueId());
        staticEntities.add(status.getUniqueId());

        // HARD-CODED STATUS AVATAR SPLIT:
        // The top-bar avatar and the top-bar name are intentionally separate from the status text body.
        // Avatar now uses an ItemDisplay with a real PLAYER_HEAD instead of a player-head text object.
        ItemDisplay statusAvatar = spawnAvatarDisplay(
            statusAvatarLocation(anchor, yaw),
            statusAvatarItem(table),
            statusAvatarScale()
        );
        placed = placed.withStatusAvatarDisplayId(statusAvatar.getUniqueId());
        staticEntities.add(statusAvatar.getUniqueId());

        TextDisplay statusAvatarName = spawnText(
            statusAvatarNameLocation(table, anchor, yaw),
            statusAvatarName(table),
            Display.Billboard.CENTER,
            false,
            statusNameScale()
        );
        placed = placed.withStatusAvatarNameDisplayId(statusAvatarName.getUniqueId());
        staticEntities.add(statusAvatarName.getUniqueId());

        TextDisplay playDetail = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getPlayDetailHeight(), 0.0),
            buildPlayDetail(table),
            Display.Billboard.CENTER,
            false,
            plugin.getSmallTextScale()
        );
        placed = placed.withPlayDetailDisplayId(playDetail.getUniqueId());
        staticEntities.add(playDetail.getUniqueId());

        return placed;
    }

    private void ensureChunkReady(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        // Startup restore can run before the destination chunk has been warmed up.
        // Force the anchor chunk loaded first so chairs, text, and CraftEngine furniture do not half-spawn.
        org.bukkit.Chunk chunk = anchor.getChunk();
        if (!chunk.isLoaded()) {
            chunk.load();
        }
    }

    private void refreshActionButtons(GameTable table, PlacedTable placed) {
        // 按钮区优先复用已有实体，避免点击和打牌时整排闪烁
        String signature = actionSignature(table, placed);
        String tableKey = normalize(table.getName());
        if (Objects.equals(actionSignatureByTable.get(tableKey), signature)) {
            return;
        }
        actionSignatureByTable.put(tableKey, signature);

        List<ActionButtonState> phaseStates = switch (table.getPhase()) {
            case BIDDING -> List.of(
                new ActionButtonState("bid", "不叫", ButtonAction.BID_0, -0.96),
                new ActionButtonState("bid", "叫1分", ButtonAction.BID_1, -0.32),
                new ActionButtonState("bid", "叫2分", ButtonAction.BID_2, 0.32),
                new ActionButtonState("bid", "叫3分", ButtonAction.BID_3, 0.96)
            );
            case DOUBLING -> List.of(
                new ActionButtonState("pass", "不加倍", ButtonAction.DOUBLE_NO, -0.40),
                new ActionButtonState("ready", "加倍", ButtonAction.DOUBLE_YES, 0.40)
            );
            case PLAYING -> List.of(
                new ActionButtonState("inspect", "提示", ButtonAction.HINT_PLAY, -0.72),
                new ActionButtonState("pass", "不要", ButtonAction.PASS_TURN, -0.24),
                new ActionButtonState("refresh", "清选", ButtonAction.CLEAR_SELECTION, 0.24)
            );
            case LOBBY -> List.of(
                new ActionButtonState("ready", "准备", ButtonAction.READY, -0.64),
                new ActionButtonState("start", "开始", ButtonAction.START, 0.00),
                new ActionButtonState("leave", "离开", ButtonAction.LEAVE, 0.64)
            );
        };

        List<ActionWidgetSpec> specs = new ArrayList<>();
        for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
            Location base = actionBase(placed.anchor(), placed.yaw(), seatIndex);
            UUID owner = placed.seatAssignments().get(seatIndex);
            float rowYaw = handCardYaw(placed.yaw(), seatIndex);
            List<ActionButtonState> activeStates = actionStatesForSeat(table, owner, phaseStates);
            double maxOffset = activeStates.stream()
                .mapToDouble(state -> Math.abs(state.offsetX()))
                .max()
                .orElse(1.0);

            if (owner == null) {
                Location joinLocation = base.clone();
                Vector buttonHitbox = buttonHitboxAdjustment(placed.yaw(), seatIndex);
                specs.add(new ActionWidgetSpec(
                    uiItem("join"),
                    joinLocation,
                    rowYaw,
                    TypewriterTextStyle.focus("加入座位" + (seatIndex + 1)),
                    joinLocation.clone().add(0.0, plugin.getJoinLabelHeight(), 0.0),
                    joinLocation.clone().add(buttonHitbox.x(), buttonHitbox.y(), buttonHitbox.z()),
                    new ActionBinding(table.getName(), ButtonAction.JOIN, seatIndex),
                    owner,
                    true
                ));
                continue;
            }

            if (table.isBot(owner)) {
                continue;
            }

            for (ActionButtonState state : activeStates) {
                Vector arcOffset = actionArcOffset(placed.yaw(), seatIndex, state.offsetX(), maxOffset, activeStates.size());
                Location buttonLocation = base.clone().add(arcOffset.x(), 0.0, arcOffset.z());
                Vector buttonHitbox = buttonHitboxAdjustment(placed.yaw(), seatIndex);
                specs.add(new ActionWidgetSpec(
                    uiItem(state.modelId()),
                    buttonLocation,
                    rowYaw,
                    TypewriterTextStyle.focus(state.label()),
                    buttonLocation.clone().add(0.0, plugin.getActionLabelHeight(), 0.0),
                    buttonLocation.clone().add(buttonHitbox.x(), buttonHitbox.y(), buttonHitbox.z()),
                    new ActionBinding(table.getName(), state.action(), seatIndex),
                    owner,
                    false
                ));
            }

        }
        syncActionWidgets(table, placed, specs);
    }

    private void syncActionWidgets(GameTable table, PlacedTable placed, List<ActionWidgetSpec> specs) {
        int required = specs.size() * 3;
        for (int index = 0; index < specs.size(); index++) {
            int base = index * 3;
            ActionWidgetSpec spec = specs.get(index);
            ItemDisplay icon = null;
            TextDisplay label = null;
            Interaction interaction = null;
            if (placed.actionEntities().size() >= base + 3) {
                Entity iconEntity = Bukkit.getEntity(placed.actionEntities().get(base));
                Entity labelEntity = Bukkit.getEntity(placed.actionEntities().get(base + 1));
                Entity interactionEntity = Bukkit.getEntity(placed.actionEntities().get(base + 2));
                if (iconEntity instanceof ItemDisplay existingIcon
                    && labelEntity instanceof TextDisplay existingLabel
                    && interactionEntity instanceof Interaction existingInteraction) {
                    icon = existingIcon;
                    label = existingLabel;
                    interaction = existingInteraction;
                }
            }
            if (icon == null || label == null || interaction == null) {
                while (placed.actionEntities().size() > base) {
                    UUID removedId = placed.actionEntities().remove(placed.actionEntities().size() - 1);
                    clearActionMappings(List.of(removedId));
                    clearEntities(new ArrayList<>(List.of(removedId)), false);
                }
                icon = spawnFlatButtonItem(spec.iconLocation(), spec.iconItem(), plugin.getButtonScale(), spec.yaw());
                label = spawnText(
                    spec.labelLocation(),
                    spec.labelText(),
                    Display.Billboard.CENTER,
                    false,
                    spec.joinVisibility() ? joinLabelTextScale() : actionLabelTextScale()
                );
                mountTextDisplay(icon, label, spec.labelLocation(), false);
                interaction = spawnInteraction(spec.interactionLocation(), actionHitboxWidth(spec.binding()), actionHitboxHeight(spec.binding()));
                placed.actionEntities().add(icon.getUniqueId());
                placed.actionEntities().add(label.getUniqueId());
                placed.actionEntities().add(interaction.getUniqueId());
            } else {
                teleportIfMoved(icon, spec.iconLocation());
                icon.setItemStack(spec.iconItem());
                applyStableYaw(icon, spec.yaw());
                teleportIfMoved(label, spec.labelLocation());
                updateTextEntity(label, spec.labelText());
                teleportIfMoved(interaction, spec.interactionLocation());
                interaction.setInteractionWidth(actionHitboxWidth(spec.binding()));
                interaction.setInteractionHeight(actionHitboxHeight(spec.binding()));
            }
            actionBindings.put(icon.getUniqueId(), spec.binding());
            actionBindings.put(label.getUniqueId(), spec.binding());
            actionBindings.put(interaction.getUniqueId(), spec.binding());
            rememberActionVisual(icon.getUniqueId(), icon.getUniqueId());
            rememberActionVisual(label.getUniqueId(), icon.getUniqueId());
            rememberActionVisual(interaction.getUniqueId(), icon.getUniqueId());
            if (spec.joinVisibility()) {
                applyJoinVisibility(table, icon);
                applyJoinVisibility(table, label);
                applyJoinVisibility(table, interaction);
            } else {
                applyPrivateVisibility(spec.owner(), icon);
                applyPrivateVisibility(spec.owner(), label);
                applyPrivateVisibility(spec.owner(), interaction);
            }
        }
        for (int base = required; base + 2 < placed.actionEntities().size(); base += 3) {
            List<UUID> ids = List.of(
                placed.actionEntities().get(base),
                placed.actionEntities().get(base + 1),
                placed.actionEntities().get(base + 2)
            );
            clearActionMappings(ids);
            deactivateEntities(ids);
        }
    }

    private void refreshStatus(GameTable table, PlacedTable placed) {
        if (placed.statusDisplayId() == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(placed.statusDisplayId());
        updateTextEntity(entity, buildStatus(table));
    }

    private void refreshPlayDetail(GameTable table, PlacedTable placed) {
        if (placed.playDetailDisplayId() == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(placed.playDetailDisplayId());
        updateTextEntity(entity, buildPlayDetail(table));
    }

    private void refreshStatusAvatar(GameTable table, PlacedTable placed) {
        if (placed.statusAvatarDisplayId() != null) {
            Entity avatar = Bukkit.getEntity(placed.statusAvatarDisplayId());
            updateAvatarEntity(avatar, statusAvatarItem(table));
            if (avatar != null) {
                teleportIfMoved(avatar, statusAvatarLocation(placed.anchor(), placed.yaw()));
            }
        }
        if (placed.statusAvatarNameDisplayId() != null) {
            Entity name = Bukkit.getEntity(placed.statusAvatarNameDisplayId());
            updateTextEntity(name, statusAvatarName(table));
            if (name != null) {
                teleportIfMoved(name, statusAvatarNameLocation(table, placed.anchor(), placed.yaw()));
            }
        }
    }

    private void refreshSeatInfos(GameTable table, PlacedTable placed) {
        for (int index = 0; index < placed.seatAvatarDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatAvatarDisplayIds().get(index));
            updateAvatarEntity(entity, seatAvatarItem(table, index));
            if (entity != null && index < placed.seatBaseLocations().size()) {
                teleportIfMoved(entity, seatAvatarLocation(placed.seatBaseLocations().get(index), placed.yaw()));
            }
        }
        for (int index = 0; index < placed.seatNameDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatNameDisplayIds().get(index));
            updateTextEntity(entity, seatName(table, index));
            if (entity != null && index < placed.seatBaseLocations().size()) {
                teleportIfMoved(entity, seatNameLocation(table, index, placed.seatBaseLocations().get(index), placed.yaw()));
            }
        }
        for (int index = 0; index < placed.seatInfoDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatInfoDisplayIds().get(index));
            updateTextEntity(entity, seatInfo(table, index));
            if (entity != null && index < placed.seatBaseLocations().size()) {
                teleportIfMoved(entity, seatInfoLocation(table, index, placed.seatBaseLocations().get(index), placed.yaw()));
            }
        }
        updateSeatInfoVisibility(table, placed);
    }

    private void updateSeatInfoVisibility(GameTable table, PlacedTable placed) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (int index = 0; index < placed.seatAvatarDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatAvatarDisplayIds().get(index));
            if (entity == null) {
                continue;
            }
            UUID owner = placed.seatAssignments().get(index);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (owner != null && viewer.getUniqueId().equals(owner) && !table.isBot(owner)) {
                    viewer.hideEntity(plugin, entity);
                } else {
                    viewer.showEntity(plugin, entity);
                }
            }
        }
        for (int index = 0; index < placed.seatNameDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatNameDisplayIds().get(index));
            if (entity == null) {
                continue;
            }
            UUID owner = placed.seatAssignments().get(index);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (owner != null && viewer.getUniqueId().equals(owner) && !table.isBot(owner)) {
                    viewer.hideEntity(plugin, entity);
                } else {
                    viewer.showEntity(plugin, entity);
                }
            }
        }
        for (int index = 0; index < placed.seatInfoDisplayIds().size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatInfoDisplayIds().get(index));
            if (entity == null) {
                continue;
            }
            UUID owner = placed.seatAssignments().get(index);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (owner != null && viewer.getUniqueId().equals(owner) && !table.isBot(owner)) {
                    viewer.hideEntity(plugin, entity);
                } else {
                    viewer.showEntity(plugin, entity);
                }
            }
        }
    }

    private void updateStatusAvatarVisibility(GameTable table, PlacedTable placed) {
        if (plugin.isShuttingDown()) {
            return;
        }
        UUID focus = statusFocusPlayer(table);
        if (placed.statusAvatarDisplayId() != null) {
            Entity entity = Bukkit.getEntity(placed.statusAvatarDisplayId());
            if (entity != null) {
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (focus != null && viewer.getUniqueId().equals(focus) && !table.isBot(focus)) {
                        viewer.hideEntity(plugin, entity);
                    } else {
                        viewer.showEntity(plugin, entity);
                    }
                }
            }
        }
        if (placed.statusAvatarNameDisplayId() != null) {
            Entity entity = Bukkit.getEntity(placed.statusAvatarNameDisplayId());
            if (entity != null) {
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (focus != null && viewer.getUniqueId().equals(focus) && !table.isBot(focus)) {
                        viewer.hideEntity(plugin, entity);
                    } else {
                        viewer.showEntity(plugin, entity);
                    }
                }
            }
        }
    }

    private void refreshPrivateHands(GameTable table, PlacedTable placed) {
        // 每位玩家都有两层牌：
        // 1. 只有自己能看到的正面牌
        // 2. 其他人能看到的背面牌
        Set<UUID> currentPlayers = Set.copyOf(table.getSeats());
        String tableKey = normalize(table.getName());
        Map<UUID, String> privateSignatures = privateHandSignatureByTable.computeIfAbsent(tableKey, ignored -> new LinkedHashMap<>());
        Map<UUID, String> backsideSignatures = backsideHandSignatureByTable.computeIfAbsent(tableKey, ignored -> new LinkedHashMap<>());
        for (UUID playerId : new ArrayList<>(placed.backsideEntitiesByPlayer().keySet())) {
            if (!currentPlayers.contains(playerId)) {
                clearBacksideEntities(placed, playerId);
                backsideSignatures.remove(playerId);
            }
        }
        for (UUID playerId : new ArrayList<>(placed.privateEntitiesByPlayer().keySet())) {
            if (!currentPlayers.contains(playerId)) {
                clearPrivateEntities(placed, playerId);
                privateSignatures.remove(playerId);
            }
        }
        for (UUID playerId : table.getSeats()) {
            String handSignature = handSignature(table, placed, playerId);
            if (!Objects.equals(backsideSignatures.get(playerId), handSignature)) {
                renderBacksideHand(table, placed, playerId);
                backsideSignatures.put(playerId, handSignature);
            }
            if (!Objects.equals(privateSignatures.get(playerId), handSignature)) {
                renderPrivateHand(table, placed, playerId);
                privateSignatures.put(playerId, handSignature);
            }
        }
    }

    private void renderBacksideHand(GameTable table, PlacedTable placed, UUID playerId) {
        // 背面牌是给“其他人”看的，所以会对牌主人隐藏
        clearBacksideEntities(placed, playerId);
        if (table.getPhase() == GamePhase.LOBBY) {
            return;
        }
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (hand.isEmpty()) {
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }

        List<UUID> spawned = new ArrayList<>();
        Map<Integer, UUID> visuals = new LinkedHashMap<>();
        Set<Integer> selected = table.getSelection(playerId);
        Vector step = handStep(seatIndex);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = globalHandAdjustment(seatIndex);
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            boolean isSelected = selected.contains(card.id());
            double delta = startOffset + index;
            Location cardBaseLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
                center.y() + adjustment.y(),
                center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
            );
            double lift = selectedCardLift(isSelected, false);
            ItemDisplay cardDisplay = spawnPlacedCard(cardBaseLocation, backCardItem(), privateCardScale(false, false), cardYaw, (float) lift);
            applyCardGlow(cardDisplay, playerId, isSelected, false);
            spawned.add(cardDisplay.getUniqueId());
            visuals.put(card.id(), cardDisplay.getUniqueId());
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(playerId) && !table.isBot(playerId)) {
                    viewer.hideEntity(plugin, cardDisplay);
                } else {
                    viewer.showEntity(plugin, cardDisplay);
                }
            }
        }
        placed.backsideEntitiesByPlayer().put(playerId, spawned);
        placed.backsideVisualsByPlayer().put(playerId, visuals);
    }

    private void renderPrivateHand(GameTable table, PlacedTable placed, UUID playerId) {
        // 正面手牌只给主人自己看，右键切换选择、左键对已选牌执行出牌
        clearPrivateEntities(placed, playerId);

        Player owner = Bukkit.getPlayer(playerId);
        if (owner == null) {
            return;
        }
        if (table.getPhase() == GamePhase.LOBBY) {
            return;
        }

        List<DoudizhuCard> hand = table.getHand(playerId);
        if (hand.isEmpty()) {
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }

        Set<Integer> selected = table.getSelection(playerId);
        Integer hovered = hoveredCardIds.get(playerId);
        List<UUID> spawned = new ArrayList<>();
        Map<Integer, HandCardVisual> visuals = new LinkedHashMap<>();
        Vector step = privateHandStep(seatIndex, playerId);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = privateHandAdjustment(seatIndex, playerId);
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        Map<CardRank, Integer> rankCounts = countRanks(hand);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            boolean isSelected = selected.contains(card.id());
            boolean isHovered = hovered != null && hovered == card.id();
            boolean previewAnimated = isHovered && !isSelected;
            double delta = startOffset + index;
            Location cardBaseLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
                center.y() + adjustment.y(),
                center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
            );
            double lift = selectedCardLift(isSelected, previewAnimated);

            ItemDisplay cardDisplay = spawnPlacedCard(cardBaseLocation, cardItem(card), privateCardScale(isSelected, previewAnimated), cardYaw, (float) lift);
            applyCardGlow(cardDisplay, playerId, isSelected, isHovered);
            spawned.add(cardDisplay.getUniqueId());
            cardBindings.put(cardDisplay.getUniqueId(), new CardBinding(table.getName(), playerId, card.id()));
            List<UUID> interactionIds = spawnCardHitboxCluster(
                table.getName(),
                playerId,
                card.id(),
                cardBaseLocation.clone().add(0.0, lift, 0.0),
                placed.yaw(),
                seatIndex
            );
            spawned.addAll(interactionIds);

            if (shouldShowPrivateLabel(playerId, card, rankCounts)) {
                Location labelLocation = privateCardLabelLocation(cardBaseLocation, seatIndex, lift);
                TextDisplay label = spawnText(
                    labelLocation,
                    MuzTheme.cardLabel(card.rank().label()),
                    Display.Billboard.CENTER,
                    false,
                    plugin.getLabelTextScale()
                );
                mountTextDisplay(cardDisplay, label, labelLocation, false);
                spawned.add(label.getUniqueId());
                cardBindings.put(label.getUniqueId(), new CardBinding(table.getName(), playerId, card.id()));
                applyPrivateVisibility(playerId, label);
                visuals.put(card.id(), new HandCardVisual(cardDisplay.getUniqueId(), interactionIds, label.getUniqueId()));
            } else {
                visuals.put(card.id(), new HandCardVisual(cardDisplay.getUniqueId(), interactionIds, null));
            }

            applyPrivateVisibility(playerId, cardDisplay);
            for (UUID interactionId : interactionIds) {
                Entity interactionEntity = Bukkit.getEntity(interactionId);
                if (interactionEntity != null) {
                    applyPrivateVisibility(playerId, interactionEntity);
                }
            }
        }

        placed.privateEntitiesByPlayer().put(playerId, spawned);
        placed.privateVisualsByPlayer().put(playerId, visuals);
    }

    private void updatePrivateSelection(GameTable table, PlacedTable placed, UUID playerId) {
        // 为了避免右键选牌整排闪烁，这里只移动/发光已存在的实体，不整手重建
        Map<Integer, HandCardVisual> visuals = placed.privateVisualsByPlayer().get(playerId);
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (visuals == null || visuals.size() != hand.size()) {
            renderPrivateHand(table, placed, playerId);
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }
        Set<Integer> selected = table.getSelection(playerId);
        Integer hovered = hoveredCardIds.get(playerId);
        Vector step = privateHandStep(seatIndex, playerId);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = privateHandAdjustment(seatIndex, playerId);
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        float animationStep = cardAnimationStep();
        float animationFallStep = Math.min(1.0f, animationStep * 1.8f);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            HandCardVisual visual = visuals.get(card.id());
            if (visual == null) {
                renderPrivateHand(table, placed, playerId);
                return;
            }
            boolean isSelected = selected.contains(card.id());
            boolean isHovered = hovered != null && hovered == card.id();
            boolean previewAnimated = isHovered && !isSelected;
            double delta = startOffset + index;
            Location cardBaseLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
                center.y() + adjustment.y(),
                center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
            );
            double lift = selectedCardLift(isSelected, previewAnimated);

            Entity cardEntity = Bukkit.getEntity(visual.cardDisplayId());
            Entity labelEntity = visual.labelId() == null ? null : Bukkit.getEntity(visual.labelId());
            if (!(cardEntity instanceof ItemDisplay cardDisplay) || visual.interactionIds().isEmpty()) {
                renderPrivateHand(table, placed, playerId);
                return;
            }

            float selectedProgress = advanceAnimation(selectedProgressByPlayer, playerId, card.id(), isSelected, animationStep, animationFallStep);
            float hoverProgress = advanceAnimation(hoverProgressByPlayer, playerId, card.id(), previewAnimated, animationStep, animationFallStep);
            double animatedLift = animatedCardLift(selectedProgress, hoverProgress);
            Vector3f animatedScale = privateCardScale(hoverProgress);
            float currentLift = cardDisplay.getTransformation().getTranslation().y;
            Vector3f currentScale = cardDisplay.getTransformation().getScale();
            boolean transformChanged = Math.abs(currentLift - animatedLift) >= 0.0001f
                || Math.abs(currentScale.x - animatedScale.x) >= 0.0001f
                || Math.abs(currentScale.y - animatedScale.y) >= 0.0001f
                || Math.abs(currentScale.z - animatedScale.z) >= 0.0001f;

            teleportIfMoved(cardEntity, cardBaseLocation);
            // Keep the card yaw locked to the table layout.
            // Do not add hover/click rotation here: that old regression made cards visibly rotate and rebound on click.
            applyStableYaw(cardDisplay, cardYaw);
            teleportCardHitboxCluster(cardBaseLocation.clone().add(0.0, animatedLift, 0.0), placed.yaw(), seatIndex, visual.interactionIds());
            if (transformChanged) {
                configureCardAnimation(cardDisplay);
                cardDisplay.setTransformation(cardTransformation(animatedScale, (float) animatedLift));
            }
            applyCardGlow(cardDisplay, playerId, isSelected, isHovered);
            if (labelEntity != null) {
                teleportIfMoved(labelEntity, privateCardLabelLocation(cardBaseLocation, seatIndex, animatedLift));
            }
        }
    }

    private void updateBacksideSelection(GameTable table, PlacedTable placed, UUID playerId) {
        Map<Integer, UUID> visuals = placed.backsideVisualsByPlayer().get(playerId);
        List<DoudizhuCard> hand = table.getHand(playerId);
        if (visuals == null || visuals.size() != hand.size()) {
            renderBacksideHand(table, placed, playerId);
            return;
        }

        int seatIndex = placedSeatIndex(placed, playerId);
        if (seatIndex < 0) {
            return;
        }

        Set<Integer> selected = table.getSelection(playerId);
        Vector step = handStep(seatIndex);
        Vector center = handCenter(seatIndex);
        Vector depth = handDepth(seatIndex);
        Vector adjustment = globalHandAdjustment(seatIndex);
        double startOffset = -((hand.size() - 1) * 0.5);
        float cardYaw = handCardYaw(placed.yaw(), seatIndex);
        float animationStep = cardAnimationStep();
        float animationFallStep = Math.min(1.0f, animationStep * 1.8f);

        for (int index = 0; index < hand.size(); index++) {
            DoudizhuCard card = hand.get(index);
            UUID entityId = visuals.get(card.id());
            Entity entity = entityId == null ? null : Bukkit.getEntity(entityId);
            if (!(entity instanceof ItemDisplay cardDisplay)) {
                renderBacksideHand(table, placed, playerId);
                return;
            }
            boolean isSelected = selected.contains(card.id());
            double delta = startOffset + index;
            Location cardBaseLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                center.x() + adjustment.x() + step.x() * delta + depth.x() * delta,
                center.y() + adjustment.y(),
                center.z() + adjustment.z() + step.z() * delta + depth.z() * delta
            );
            float selectedProgress = advanceAnimation(selectedProgressByPlayer, playerId, card.id(), isSelected, animationStep, animationFallStep);
            double lift = animatedCardLift(selectedProgress, 0.0f);
            float currentLift = cardDisplay.getTransformation().getTranslation().y;
            teleportIfMoved(cardDisplay, cardBaseLocation);
            applyStableYaw(cardDisplay, cardYaw);
            if (Math.abs(currentLift - lift) >= 0.0001f) {
                configureCardAnimation(cardDisplay);
                cardDisplay.setTransformation(cardTransformation(privateCardScale(false, false), (float) lift));
            }
            applyCardGlow(cardDisplay, playerId, isSelected, false);
        }
    }

    private void refreshPublicTrick(GameTable table, PlacedTable placed) {
        // 公共出牌区固定显示在桌子上方：详情文字在上，牌组在下
        String tableKey = normalize(table.getName());
        String signature = publicTrickSignature(table);
        if (Objects.equals(publicSignatureByTable.get(tableKey), signature)) {
            return;
        }
        publicSignatureByTable.put(tableKey, signature);
        List<PreviewCardSpec> publicSpecs = new ArrayList<>();
        if (!table.getCurrentTrickCards().isEmpty()) {
            collectPreviewCardSpecs(
                table,
                placed,
                null,
                publicSpecs,
                table.getCurrentTrickCards(),
                plugin.getPublicCardScale(),
                plugin.getPublicTrickHeight(),
                0.0,
                NamedTextColor.YELLOW
            );
        }
        syncPreviewEntities(table, null, placed.publicEntities(), publicSpecs);

        Set<UUID> expectedViewers = new LinkedHashSet<>();
        for (UUID playerId : table.getSeats()) {
            if (table.isBot(playerId)) {
                continue;
            }
            List<DoudizhuCard> selectedCards = selectedCards(table, playerId);
            boolean hasCurrentTrick = !table.getCurrentTrickCards().isEmpty();
            boolean showOpponentRow = hasCurrentTrick && plugin.isOpponentPreviewEnabledFor(playerId);
            if (!showOpponentRow && selectedCards.isEmpty()) {
                continue;
            }
            expectedViewers.add(playerId);
            List<UUID> viewerEntities = placed.viewerTrickEntitiesByPlayer().computeIfAbsent(playerId, ignored -> new ArrayList<>());
            List<PreviewCardSpec> viewerSpecs = new ArrayList<>();
            float scale = plugin.getPublicCardScale();
            double previewHeight = plugin.getPublicTrickHeight();
            if (showOpponentRow) {
                collectPreviewCardSpecs(
                    table,
                    placed,
                    playerId,
                    viewerSpecs,
                    table.getCurrentTrickCards(),
                    scale,
                    previewHeight,
                    selectedCards.isEmpty() ? 0.0 : plugin.getPublicPreviewCompareRowOffset(),
                    NamedTextColor.YELLOW
                );
            }
            if (!selectedCards.isEmpty()) {
                collectPreviewCardSpecs(
                    table,
                    placed,
                    playerId,
                    viewerSpecs,
                    selectedCards,
                    scale,
                    previewHeight,
                    showOpponentRow ? plugin.getPublicPreviewSelectedRowOffset() : 0.0,
                    NamedTextColor.AQUA
                );
            }
            syncPreviewEntities(table, playerId, viewerEntities, viewerSpecs);
        }
        for (UUID playerId : new ArrayList<>(placed.viewerTrickEntitiesByPlayer().keySet())) {
            if (!expectedViewers.contains(playerId)) {
                clearViewerTrickEntities(placed, playerId);
            }
        }
    }

    private void collectPreviewCardSpecs(
        GameTable table,
        PlacedTable placed,
        UUID ownerId,
        List<PreviewCardSpec> target,
        List<DoudizhuCard> cards,
        float scale,
        double height,
        double verticalBias,
        NamedTextColor labelColor
    ) {
        if (cards.isEmpty()) {
            return;
        }
        Map<CardRank, Integer> rankCounts = countRanks(cards);
        int perRow = plugin.getPreviewCardsPerRow();
        int rowCount = Math.max(1, (cards.size() + perRow - 1) / perRow);
        int ownerSeatIndex = ownerId == null ? -1 : placedSeatIndex(placed, ownerId);
        for (int index = 0; index < cards.size(); index++) {
            int row = index / perRow;
            int col = index % perRow;
            int rowSize = Math.min(perRow, cards.size() - row * perRow);
            double centered = col - ((rowSize - 1) * 0.5);
            double rowOffset = row - ((rowCount - 1) * 0.5);
            Location location = previewLocation(placed, ownerId, centered, rowOffset, height + verticalBias);
            DoudizhuCard card = cards.get(index);
            float yaw = previewYaw(placed, ownerId, ownerSeatIndex);
            target.add(new PreviewCardSpec(cardItem(card), location, yaw, Component.empty(), ownerId));
        }
    }

    private void syncPreviewEntities(GameTable table, UUID ownerId, List<UUID> target, List<PreviewCardSpec> specs) {
        int required = specs.size();
        for (int index = 0; index < specs.size(); index++) {
            PreviewCardSpec spec = specs.get(index);
            ItemDisplay display = null;
            if (target.size() > index) {
                Entity displayEntity = Bukkit.getEntity(target.get(index));
                if (displayEntity instanceof ItemDisplay existingDisplay) {
                    display = existingDisplay;
                }
            }
            if (display == null) {
                display = spawnPlacedCard(spec.location(), spec.item(), publicCardScale(plugin.getPublicCardScale()), spec.yaw());
                target.add(display.getUniqueId());
            } else {
                teleportIfMoved(display, spec.location());
                display.setItemStack(spec.item());
                applyStableYaw(display, spec.yaw());
            }
            applyTrickVisibility(table, ownerId, display);
        }
        for (int index = required; index < target.size(); index++) {
            UUID entityId = target.get(index);
            deactivateEntities(List.of(entityId));
        }
    }

    private void updateViewerPreviewEntities(GameTable table, PlacedTable placed, UUID playerId) {
        if (table.isBot(playerId)) {
            return;
        }
        List<DoudizhuCard> selectedCards = selectedCards(table, playerId);
        boolean hasCurrentTrick = !table.getCurrentTrickCards().isEmpty();
        boolean showOpponentRow = hasCurrentTrick && plugin.isOpponentPreviewEnabledFor(playerId);
        if (!showOpponentRow && selectedCards.isEmpty()) {
            clearViewerTrickEntities(placed, playerId);
            return;
        }
        List<PreviewCardSpec> specs = new ArrayList<>();
        float scale = plugin.getPublicCardScale();
        double previewHeight = plugin.getPublicTrickHeight();
        if (showOpponentRow) {
            collectPreviewCardSpecs(
                table,
                placed,
                playerId,
                specs,
                table.getCurrentTrickCards(),
                scale,
                previewHeight,
                selectedCards.isEmpty() ? 0.0 : plugin.getPublicPreviewCompareRowOffset(),
                NamedTextColor.YELLOW
            );
        }
        if (!selectedCards.isEmpty()) {
            collectPreviewCardSpecs(
                table,
                placed,
                playerId,
                specs,
                selectedCards,
                scale,
                previewHeight,
                showOpponentRow ? plugin.getPublicPreviewSelectedRowOffset() : 0.0,
                NamedTextColor.AQUA
            );
        }
        List<UUID> viewerEntities = placed.viewerTrickEntitiesByPlayer().computeIfAbsent(playerId, ignored -> new ArrayList<>());
        syncPreviewEntities(table, playerId, viewerEntities, specs);
    }

    private List<DoudizhuCard> selectedCards(GameTable table, UUID playerId) {
        Set<Integer> selected = table.getSelection(playerId);
        if (selected.isEmpty()) {
            return List.of();
        }
        return table.getHand(playerId).stream()
            .filter(card -> selected.contains(card.id()))
            .toList();
    }

    private void clearPrivateEntities(PlacedTable placed, UUID playerId) {
        List<UUID> entities = placed.privateEntitiesByPlayer().remove(playerId);
        placed.privateVisualsByPlayer().remove(playerId);
        hoverProgressByPlayer.remove(playerId);
        selectedProgressByPlayer.remove(playerId);
        if (entities != null) {
            clearEntities(entities, false);
        }
    }

    private void clearBacksideEntities(PlacedTable placed, UUID playerId) {
        List<UUID> entities = placed.backsideEntitiesByPlayer().remove(playerId);
        placed.backsideVisualsByPlayer().remove(playerId);
        selectedProgressByPlayer.remove(playerId);
        if (entities != null) {
            clearEntities(entities, false);
        }
    }

    private void clearViewerTrickEntities(PlacedTable placed, UUID playerId) {
        List<UUID> entities = placed.viewerTrickEntitiesByPlayer().remove(playerId);
        if (entities != null) {
            clearEntities(entities, false);
        }
    }

    private void clearEntities(List<UUID> entityIds, boolean publicCards) {
        for (UUID entityId : entityIds) {
            actionBindings.remove(entityId);
            actionDisplayByBinding.remove(entityId);
            cardBindings.remove(entityId);
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
        if (publicCards) {
            entityIds.clear();
        }
    }

    private void cleanupPlacedTable(PlacedTable placed) {
        clearCraftEngineEntities(placed.craftEngineVisualEntities());
        String tableKey = normalize(placed.tableName());
        actionSignatureByTable.remove(tableKey);
        publicSignatureByTable.remove(tableKey);
        privateHandSignatureByTable.remove(tableKey);
        backsideHandSignatureByTable.remove(tableKey);
        clearEntities(placed.actionEntities(), false);
        clearEntities(placed.publicEntities(), false);
        for (UUID playerId : new ArrayList<>(placed.backsideEntitiesByPlayer().keySet())) {
            clearBacksideEntities(placed, playerId);
        }
        for (UUID playerId : new ArrayList<>(placed.viewerTrickEntitiesByPlayer().keySet())) {
            clearViewerTrickEntities(placed, playerId);
        }
        for (UUID playerId : new ArrayList<>(placed.privateEntitiesByPlayer().keySet())) {
            clearPrivateEntities(placed, playerId);
        }
        clearEntities(placed.staticEntities(), false);
        restoreBlocks(placed.blockRestores());
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

    private void purgeResidualWorldArtifacts(Location anchor, float yaw) {
        if (anchor == null || anchor.getWorld() == null) {
            return;
        }
        List<Location> hotspots = new ArrayList<>();
        Location tableLocation = anchor.clone().add(0.0, plugin.getTableDisplayHeight(), 0.0);
        hotspots.add(tableLocation);
        clearResidualPlacementBlock(blockPlacementLocation(tableLocation));
        for (int index = 0; index < 3; index++) {
            Vector chairAdjustment = chairVisualAdjustment(index);
            Location chairLocation = rotate(
                anchor,
                yaw,
                chairOffsets(index)[0] + chairAdjustment.x(),
                plugin.getChairBaseHeight() + chairAdjustment.y(),
                chairOffsets(index)[1] + chairAdjustment.z()
            );
            hotspots.add(chairLocation);
            clearResidualPlacementBlock(blockPlacementLocation(chairLocation));
        }
        clearResidualEntities(hotspots, 0.95, 1.6);
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

    private String obstructionReason(String label, Location center, double radiusXz, double minYOffset, double maxYOffset) {
        if (!hasSolidObstruction(center, radiusXz, minYOffset, maxYOffset)) {
            return null;
        }
        return label + "位置被方块挡住了，先清空附近空间。";
    }

    private boolean hasSolidObstruction(Location center, double radiusXz, double minYOffset, double maxYOffset) {
        if (center == null || center.getWorld() == null) {
            return false;
        }
        int minX = (int) Math.floor(center.getX() - radiusXz);
        int maxX = (int) Math.floor(center.getX() + radiusXz);
        int minY = (int) Math.floor(center.getY() + minYOffset);
        int maxY = (int) Math.floor(center.getY() + maxYOffset);
        int minZ = (int) Math.floor(center.getZ() - radiusXz);
        int maxZ = (int) Math.floor(center.getZ() + radiusXz);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    org.bukkit.block.Block block = center.getWorld().getBlockAt(x, y, z);
                    if (!block.getType().isAir()) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private void applyTrickVisibility(GameTable table, UUID ownerId, Entity entity) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (ownerId != null) {
                if (viewer.getUniqueId().equals(ownerId)) {
                    viewer.showEntity(plugin, entity);
                } else {
                    viewer.hideEntity(plugin, entity);
                }
                continue;
            }
            GameTable current = plugin.getTableManager().getTableOf(viewer);
            if (current != null && current.getName().equalsIgnoreCase(table.getName())) {
                viewer.hideEntity(plugin, entity);
            } else {
                viewer.showEntity(plugin, entity);
            }
        }
    }

    private void deactivateEntities(List<UUID> entityIds) {
        if (plugin.isShuttingDown()) {
            return;
        }
        for (UUID entityId : entityIds) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity == null) {
                continue;
            }
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                viewer.hideEntity(plugin, entity);
            }
        }
    }

    private ItemDisplay spawnFurnitureDisplay(Location location, ItemStack item, float scale) {
        return VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
            ));
            protectEntity(spawned);
        });
    }

    private ItemDisplay spawnBillboardItem(Location location, ItemStack item, Vector3f scale) {
        return VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.CENTER);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                scale,
                new AxisAngle4f()
            ));
            configureDisplayAnimation(spawned);
            protectEntity(spawned);
        });
    }

    private ItemDisplay spawnFlatButtonItem(Location location, ItemStack item, float scale, float yaw) {
        ItemDisplay display = VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f((float) Math.toRadians(plugin.getButtonRollDegrees()), 0.0f, 0.0f, 1.0f),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f()
            ));
            configureButtonAnimation(spawned);
            protectEntity(spawned);
        });
        applyStableYaw(display, yaw);
        return display;
    }

    private ItemDisplay spawnPlacedCard(Location location, ItemStack item, Vector3f scale, float yaw) {
        return spawnPlacedCard(location, item, scale, yaw, 0.0f);
    }

    private ItemDisplay spawnPlacedCard(Location location, ItemStack item, Vector3f scale, float yaw, float lift) {
        ItemDisplay display = VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(cardTransformation(scale, lift));
            configureCardAnimation(spawned);
            protectEntity(spawned);
        });
        applyStableYaw(display, yaw);
        return display;
    }

    private ItemDisplay spawnAvatarDisplay(Location location, ItemStack item, float scale) {
        return spawnBillboardItem(location, item, new Vector3f(scale, scale, scale));
    }

    private TextDisplay spawnText(Location location, Component text, Display.Billboard billboard, boolean background) {
        return spawnText(location, text, billboard, background, 1.0f);
    }

    private TextDisplay spawnText(Location location, Component text, Display.Billboard billboard, boolean background, float scale) {
        return VersionCompat.spawnEntity(location.getWorld(), location, TextDisplay.class, spawned -> {
            try {
                spawned.text(text);
            } catch (NoSuchMethodError e) {
                // 1.20.1 不支持 text() 方法，使用 setCustomName() 作为替代
                spawned.setCustomName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(text));
                spawned.setCustomNameVisible(true);
            }
            TypewriterTextStyle.apply(spawned, billboard, background, scale);
            protectEntity(spawned);
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
            try {
                display.text(MuzTheme.plain(text).decoration(TextDecoration.ITALIC, false));
            } catch (NoSuchMethodError e) {
                // 1.20.1 不支持 text() 方法，使用 setCustomName() 作为替代
                display.setCustomName(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(MuzTheme.plain(text).decoration(TextDecoration.ITALIC, false)));
                display.setCustomNameVisible(true);
            }
        }
    }

    private void updateAvatarEntity(Entity entity, ItemStack item) {
        if (entity instanceof ItemDisplay display) {
            display.setItemStack(item == null ? new ItemStack(Material.AIR) : item);
        }
    }

    private Interaction spawnInteraction(Location location, float width, float height) {
        return VersionCompat.spawnEntity(location.getWorld(), location, Interaction.class, spawned -> {
            spawned.setInteractionWidth(width);
            spawned.setInteractionHeight(height);
            spawned.setResponsive(true);
            protectEntity(spawned);
        });
    }

    private List<UUID> spawnCardHitboxCluster(String tableName, UUID ownerId, int cardId, Location cardLocation, float tableYaw, int seatIndex) {
        List<UUID> ids = new ArrayList<>();
        List<Location> locations = cardHitboxLocations(cardLocation, tableYaw, seatIndex);
        float sliceWidth = (float) Math.min(plugin.getCardHitboxWidth(), plugin.getCardHitboxLength());
        float sliceHeight = (float) plugin.getCardHitboxHeight();
        for (Location location : locations) {
            Interaction interaction = spawnInteraction(location, sliceWidth, sliceHeight);
            ids.add(interaction.getUniqueId());
            cardBindings.put(interaction.getUniqueId(), new CardBinding(tableName, ownerId, cardId));
        }
        return ids;
    }

    private void teleportCardHitboxCluster(Location cardLocation, float tableYaw, int seatIndex, List<UUID> interactionIds) {
        List<Location> locations = cardHitboxLocations(cardLocation, tableYaw, seatIndex);
        if (locations.size() != interactionIds.size()) {
            return;
        }
        for (int index = 0; index < interactionIds.size(); index++) {
            Entity entity = Bukkit.getEntity(interactionIds.get(index));
            if (entity != null) {
                teleportIfMoved(entity, locations.get(index));
            }
        }
    }

    private void teleportIfMoved(Entity entity, Location target) {
        Location current = entity.getLocation();
        if (current.getWorld() == target.getWorld()
            && current.distanceSquared(target) < 0.0004) {
            return;
        }
        Location moved = target.clone();
        moved.setYaw(current.getYaw());
        moved.setPitch(current.getPitch());
        entity.teleport(moved);
    }

    private void applyStableYaw(ItemDisplay display, float targetYaw) {
        // IMPORTANT REGRESSION GUARD:
        // Never spam equivalent yaw writes every tick.
        // Repeated setRotation calls with near-identical angles can still make cards/buttons appear to twist and snap back.
        float normalizedTarget = normalizeYaw(targetYaw);
        float normalizedCurrent = normalizeYaw(display.getLocation().getYaw());
        float diff = Math.abs(normalizeYaw(normalizedTarget - normalizedCurrent));
        if (diff < 0.01f) {
            return;
        }
        display.setRotation(normalizedTarget, 0.0f);
    }

    private List<Location> cardHitboxLocations(Location cardLocation, float tableYaw, int seatIndex) {
        Vector base = cardHitboxAdjustment(tableYaw, seatIndex);
        double width = Math.max(0.05, plugin.getCardHitboxWidth());
        double length = Math.max(0.05, plugin.getCardHitboxLength());
        double sliceSize = Math.min(width, length);
        int slices = Math.max(1, (int) Math.ceil(Math.max(width, length) / sliceSize));
        Vector lateralAxis = rotateVector(normalizeHorizontal(handStep(seatIndex)), tableYaw);
        Vector depthAxis = rotateVector(towardTableAxis(seatIndex), tableYaw);
        boolean alongLateral = length >= width;
        double totalSpan = alongLateral ? length : width;
        List<Location> locations = new ArrayList<>(slices);
        for (int index = 0; index < slices; index++) {
            double delta = slices == 1 ? 0.0 : -totalSpan / 2.0 + sliceSize / 2.0 + index * sliceSize;
            double dx = base.x() + (alongLateral ? lateralAxis.x() * delta : depthAxis.x() * delta);
            double dz = base.z() + (alongLateral ? lateralAxis.z() * delta : depthAxis.z() * delta);
            locations.add(cardLocation.clone().add(dx, base.y(), dz));
        }
        return locations;
    }

    private void protectEntity(Entity entity) {
        entity.setInvulnerable(true);
        entity.setPersistent(false);
        entity.setGravity(false);
        entity.addScoreboardTag(PROTECTED_ENTITY_TAG);
    }

    private boolean matchesExpectedPlacedBlock(org.bukkit.block.Block block, PlacedTable placed) {
        Location tableBlock = snappedBlockLocation(blockPlacementLocation(placed.anchor().clone().add(0.0, plugin.getTableDisplayHeight(), 0.0)));
        if (sameBlock(block, tableBlock)) {
            return true;
        }
        for (int index = 0; index < 3; index++) {
            Vector chairAdjustment = chairVisualAdjustment(index);
            Location chairLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                chairOffsets(index)[0] + chairAdjustment.x(),
                plugin.getChairBaseHeight() + chairAdjustment.y(),
                chairOffsets(index)[1] + chairAdjustment.z()
            );
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
        // After restart, CraftEngine furniture entities can come back with ids/tags that MUZ did not track in memory.
        // We still protect them by checking whether a furniture-like entity is standing on the expected table/chair
        // positions for this placed table. Do not remove this fallback unless the user explicitly asks.
        Location location = entity.getLocation();
        Location tableLocation = placed.anchor().clone().add(0.0, plugin.getTableDisplayHeight(), 0.0);
        if (nearExpectedLocation(location, tableLocation, 1.10, 1.80)) {
            return true;
        }
        for (int index = 0; index < 3; index++) {
            Vector chairAdjustment = chairVisualAdjustment(index);
            Location chairLocation = rotate(
                placed.anchor(),
                placed.yaw(),
                chairOffsets(index)[0] + chairAdjustment.x(),
                plugin.getChairBaseHeight() + chairAdjustment.y(),
                chairOffsets(index)[1] + chairAdjustment.z()
            );
            if (nearExpectedLocation(location, chairLocation, 0.85, 1.60)) {
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
        protectEntity(entity);
        for (Entity passenger : entity.getPassengers()) {
            protectEntityTree(passenger);
        }
    }

    private void configureDisplayAnimation(Display display) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(3);
        try {
            display.setTeleportDuration(0);
        } catch (NoSuchMethodError e) {
            // 1.20.1 不支持 setTeleportDuration，忽略
        }
    }

    private void configureCardAnimation(Display display) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(Math.max(2, Math.min(4, plugin.getCardHoverInterpolationTicks() / 2)));
        // IMPORTANT REGRESSION GUARD:
        // Card teleports must not interpolate, otherwise click/hover refreshes can look like a rotate-and-rebound bug.
        try {
            display.setTeleportDuration(0);
        } catch (NoSuchMethodError e) {
            // 1.20.1 不支持 setTeleportDuration，忽略
        }
    }

    private void configureButtonAnimation(Display display) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(Math.max(2, Math.min(4, plugin.getButtonHoverInterpolationTicks() / 2)));
        // IMPORTANT REGRESSION GUARD:
        // Buttons share the same client interpolation pitfall as cards; keep teleport interpolation disabled.
        try {
            display.setTeleportDuration(0);
        } catch (NoSuchMethodError e) {
            // 1.20.1 不支持 setTeleportDuration，忽略
        }
    }

    private ItemStack tableItem() {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.TABLE);
        if (configured != null) {
            configured.setAmount(1);
            return configured;
        }
        NamespacedKey model = configuredModelKey(
            plugin.getTableItemModelId(),
            PackAssets.furnitureModel(plugin, "table_visual")
        );
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        VersionCompat.setItemModel(meta, model);
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
        NamespacedKey model = configuredModelKey(
            plugin.getChairItemModelId(),
            PackAssets.furnitureModel(plugin, "seat_chair")
        );
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        VersionCompat.setItemModel(meta, model);
        meta.displayName(message(plugin.getChairDisplayName(), NamedTextColor.RED));
        item.setItemMeta(meta);
        return item;
    }

    private TablePlacement spawnTableVisual(Location location, float yaw) {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.TABLE);
        Location target = location.clone();
        target.setYaw(yaw);
        for (String itemId : plugin.getTableFurnitureItemIdCandidates()) {
            dev.mumu.doudizhu.compat.CraftEngineFurnitureService.PlacementKind kind =
                plugin.getCraftEngineFurnitureService().detectPlacementKind(itemId);
            if (kind == dev.mumu.doudizhu.compat.CraftEngineFurnitureService.PlacementKind.FURNITURE) {
                Entity furniture = plugin.getCraftEngineFurnitureService().placeFurniture(target, itemId);
                if (furniture != null) {
                    return TablePlacement.furniture(furniture.getUniqueId(), true);
                }
            } else if (kind == dev.mumu.doudizhu.compat.CraftEngineFurnitureService.PlacementKind.BLOCK) {
                Location blockLocation = blockPlacementLocation(location);
                BlockRestore restore = captureBlockRestore(blockLocation);
                Location snappedBlockLocation = snappedBlockLocation(blockLocation);
                String orientedState = orientedCraftEngineBlockState(itemId, yaw);
                if (plugin.getCraftEngineFurnitureService().placeBlockWithState(snappedBlockLocation, orientedState)) {
                    return TablePlacement.block(restore);
                }
            }
        }
        if (configured != null && configured.getType().isBlock()) {
            Location blockLocation = blockPlacementLocation(location);
            BlockRestore restore = captureBlockRestore(blockLocation);
            Location snappedBlockLocation = snappedBlockLocation(blockLocation);
            snappedBlockLocation.getBlock().setType(configured.getType(), false);
            orientVanillaBlockToYaw(snappedBlockLocation, yaw);
            return TablePlacement.block(restore);
        }
        return TablePlacement.none();
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
                Location snappedBlockLocation = snappedBlockLocation(blockLocation);
                String orientedState = orientedCraftEngineBlockState(itemId, yaw);
                if (plugin.getCraftEngineFurnitureService().placeBlockWithState(snappedBlockLocation, orientedState)) {
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

    private PlacedTable placedTable(String tableName) {
        if (tableName == null) {
            return null;
        }
        String normalized = normalize(tableName);
        PlacedTable placed = placedTables.get(normalized);
        if (placed != null) {
            return placed;
        }
        for (PlacedTable candidate : placedTables.values()) {
            if (candidate.tableName().equalsIgnoreCase(tableName.trim())) {
                return candidate;
            }
        }
        return null;
    }

    private PlacedTable removePlacedTable(String tableName) {
        if (tableName == null) {
            return null;
        }
        String normalized = normalize(tableName);
        PlacedTable removed = placedTables.remove(normalized);
        if (removed != null) {
            return removed;
        }
        for (Map.Entry<String, PlacedTable> entry : new ArrayList<>(placedTables.entrySet())) {
            if (entry.getValue().tableName().equalsIgnoreCase(tableName.trim())) {
                placedTables.remove(entry.getKey());
                return entry.getValue();
            }
        }
        return null;
    }

    private NamespacedKey configuredModelKey(String itemModelId, NamespacedKey fallback) {
        if (itemModelId == null || itemModelId.isBlank()) {
            return fallback;
        }
        NamespacedKey configured = NamespacedKey.fromString(itemModelId.trim());
        return configured == null ? fallback : configured;
    }

    private ItemStack uiItem(String modelId) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        VersionCompat.setItemModel(meta, PackAssets.uiModel(plugin, modelId));
        item.setItemMeta(meta);
        return item;
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

    private Vector globalHandAdjustment(int seatIndex) {
        double lateral = plugin.getGlobalPrivateHandLateralOffset();
        double vertical = plugin.getGlobalPrivateHandVerticalOffset();
        double depth = plugin.getGlobalPrivateHandDepthOffset();
        Vector lateralAxis = normalizeHorizontal(handStep(seatIndex));
        Vector depthAxis = towardTableAxis(seatIndex);
        return new Vector(
            lateralAxis.x() * lateral + depthAxis.x() * depth,
            vertical,
            lateralAxis.z() * lateral + depthAxis.z() * depth
        );
    }

    private Vector privateHandAdjustment(int seatIndex, UUID playerId) {
        Vector global = globalHandAdjustment(seatIndex);
        double lateral = plugin.getPlayerHandLateralOffset(playerId);
        double vertical = plugin.getPlayerHandVerticalOffset(playerId);
        double depth = plugin.getPlayerHandDepthOffset(playerId);
        Vector lateralAxis = normalizeHorizontal(handStep(seatIndex));
        Vector depthAxis = towardTableAxis(seatIndex);
        return new Vector(
            global.x() + lateralAxis.x() * lateral + depthAxis.x() * depth,
            global.y() + vertical,
            global.z() + lateralAxis.z() * lateral + depthAxis.z() * depth
        );
    }

    private Vector privateHandStep(int seatIndex, UUID playerId) {
        double spacing = Math.max(0.02, plugin.getHandSpacing());
        return switch (seatIndex) {
            case 0 -> new Vector(spacing, 0.0, 0.0);
            case 1 -> new Vector(0.0, 0.0, -spacing);
            default -> new Vector(0.0, 0.0, spacing);
        };
    }

    private Vector actionArcOffset(float tableYaw, int seatIndex, double offset, double maxOffset, int buttonCount) {
        double normalized = maxOffset <= 0.0001 ? 0.0 : offset / maxOffset;
        double maxAngle = Math.toRadians(buttonCount >= 4 ? plugin.getButtonArcLargeAngleDegrees() : plugin.getButtonArcSmallAngleDegrees());
        double radius = buttonCount >= 4 ? plugin.getButtonArcLargeRadius() : plugin.getButtonArcSmallRadius();
        double angle = normalized * maxAngle * plugin.getButtonSpacingScale();
        double lateral = Math.sin(angle) * radius;
        double depth = radius * (1.0 - Math.cos(maxAngle)) * 0.22;
        Vector lateralAxis = rotateVector(normalizeHorizontal(actionStep(seatIndex)), tableYaw);
        Vector depthAxis = rotateVector(towardTableAxis(seatIndex), tableYaw);
        return new Vector(
            lateralAxis.x() * lateral + depthAxis.x() * depth,
            0.0,
            lateralAxis.z() * lateral + depthAxis.z() * depth
        );
    }

    private Vector chairVisualAdjustment(int seatIndex) {
        Vector lateralAxis = normalizeHorizontal(actionStep(seatIndex));
        return new Vector(
            lateralAxis.x() * plugin.getChairVisualLateralOffset(),
            plugin.getChairVisualVerticalOffset(),
            lateralAxis.z() * plugin.getChairVisualLateralOffset()
        );
    }

    private Vector chairHitboxAdjustment(int seatIndex) {
        Vector lateralAxis = normalizeHorizontal(actionStep(seatIndex));
        return new Vector(
            lateralAxis.x() * plugin.getChairHitboxLateralOffset(),
            plugin.getChairHitboxVerticalOffset(),
            lateralAxis.z() * plugin.getChairHitboxLateralOffset()
        );
    }

    private Vector buttonHitboxAdjustment(float tableYaw, int seatIndex) {
        Vector lateralAxis = rotateVector(normalizeHorizontal(actionStep(seatIndex)), tableYaw);
        Vector depthAxis = rotateVector(towardTableAxis(seatIndex), tableYaw);
        return new Vector(
            lateralAxis.x() * plugin.getButtonHitboxLateralOffset() + depthAxis.x() * plugin.getButtonHitboxDepthOffset(),
            plugin.getButtonHitboxVerticalOffset(),
            lateralAxis.z() * plugin.getButtonHitboxLateralOffset() + depthAxis.z() * plugin.getButtonHitboxDepthOffset()
        );
    }

    private Vector cardHitboxAdjustment(float tableYaw, int seatIndex) {
        Vector lateralAxis = rotateVector(normalizeHorizontal(handStep(seatIndex)), tableYaw);
        Vector depthAxis = rotateVector(towardTableAxis(seatIndex), tableYaw);
        return new Vector(
            lateralAxis.x() * plugin.getCardHitboxLateralOffset() + depthAxis.x() * plugin.getCardHitboxDepthOffset(),
            plugin.getCardHitboxVerticalOffset(),
            lateralAxis.z() * plugin.getCardHitboxLateralOffset() + depthAxis.z() * plugin.getCardHitboxDepthOffset()
        );
    }

    private List<ActionButtonState> actionStatesForSeat(GameTable table, UUID owner, List<ActionButtonState> phaseStates) {
        if (table.getPhase() == GamePhase.LOBBY && owner != null) {
            return List.of(
                new ActionButtonState("ready", table.isReady(owner) ? "取消准备" : "准备", ButtonAction.READY, -0.64),
                new ActionButtonState("start", "开始", ButtonAction.START, 0.00),
                new ActionButtonState("leave", "离开", ButtonAction.LEAVE, 0.64)
            );
        }
        if (table.getPhase() == GamePhase.BIDDING) {
            if (owner == null || !owner.equals(table.getCurrentTurn())) {
                return List.of();
            }
            return phaseStates;
        }
        if (table.getPhase() == GamePhase.DOUBLING) {
            if (owner == null || !owner.equals(table.getCurrentTurn())) {
                return List.of();
            }
            return phaseStates;
        }
        if (table.getPhase() != GamePhase.PLAYING || owner == null) {
            return phaseStates;
        }
        if (!owner.equals(table.getCurrentTurn())) {
            return List.of();
        }
        boolean canPass = table.getLeadPlayer() != null && !owner.equals(table.getLeadPlayer());
        return canPass
            ? phaseStates
            : List.of(
                new ActionButtonState("inspect", "提示", ButtonAction.HINT_PLAY, -0.56),
                new ActionButtonState("refresh", "清选", ButtonAction.CLEAR_SELECTION, 0.56)
            );
    }

    private Component buildStatus(GameTable table) {
        // Keep the status text head-free.
        // The top avatar/name pair already shows the focused player, so the banner should only describe the phase and state.
        Component top = MuzTheme.accent("斗地主")
            .append(MuzTheme.divider(" · "))
            .append(MuzTheme.multiplierWarm(table.getName() + " 号桌"));
        Component middle = TypewriterTextStyle.joinInline(
            MuzTheme.warm(plugin.roomDisplayTag(table.getRoomLevel())),
            MuzTheme.accent(table.getPhase().displayName()),
            currentTurnStatusBanner(table)
        );
        Component detail = TypewriterTextStyle.joinInline(
            table.getBottomCards().isEmpty() ? null : MuzTheme.warm("底牌 " + table.getBottomCards().size() + " 张"),
            table.getLandlord() == null ? null : MuzTheme.landlord("地主"),
            table.getLandlord() == null ? null : plugin.playerNameComponent(table.getLandlord(), table.displayName(table.getLandlord()), NamedTextColor.WHITE)
        );
        return TypewriterTextStyle.joinLines(top, middle, detail, table.currentMultiplierBannerComponent());
    }

    private Component buildPlayDetail(GameTable table) {
        List<Component> lines = new ArrayList<>();
        lines.add(MuzTheme.warning("桌边动态"));
        if (table.getPhase() == GamePhase.LOBBY) {
            lines.addAll(table.recentLobbyPreviewComponents());
        } else {
            if (table.getPhase() == GamePhase.PLAYING) {
                lines.addAll(table.slidingTrickPreviewComponents(System.currentTimeMillis()));
                lines.add(MINI.deserialize("<!i><#8fc7da>右键选择</#8fc7da><dark_gray>｜</dark_gray><#ffd670><bold>左键出牌</bold></#ffd670>"));
            } else {
                lines.add(table.lastActionComponent());
            }
        }
        return TypewriterTextStyle.joinLines(lines.toArray(Component[]::new));
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

    private void applyHint(GameTable table, Player player) {
        // “提示”按钮每按一次就切到下一组建议牌
        List<List<DoudizhuCard>> hints = buildHints(table, player.getUniqueId());
        if (hints.isEmpty()) {
            hint(player, "没有可出的建议牌组。", NamedTextColor.GRAY);
            return;
        }
        int index = hintIndices.getOrDefault(player.getUniqueId(), 0) % hints.size();
        table.replaceSelection(player.getUniqueId(), hints.get(index));
        hintIndices.put(player.getUniqueId(), index + 1);
        refreshPrivateHand(table, player.getUniqueId());
        hint(player, "提示第 " + (index + 1) + " 组，可再次点击切换。", NamedTextColor.YELLOW);
    }

    private List<List<DoudizhuCard>> buildHints(GameTable table, UUID playerId) {
        List<DoudizhuCard> hand = new ArrayList<>(table.getHand(playerId));
        hand.sort(DoudizhuCard.ORDER);
        List<List<DoudizhuCard>> hints = new ArrayList<>();
        List<DoudizhuCard> best = SimpleBotBrain.choosePlay(
            hand,
            table.getLeadPlayer() != null && !table.getLeadPlayer().equals(playerId) ? table.getCurrentPattern() : null
        );
        if (!best.isEmpty()) {
            hints.add(best);
        }
        for (DoudizhuCard card : hand) {
            List<DoudizhuCard> single = List.of(card);
            if (table.getCurrentPattern() != null && table.getLeadPlayer() != null && !table.getLeadPlayer().equals(playerId)) {
                boolean legal = dev.mumu.doudizhu.model.PatternAnalyzer.analyze(single)
                    .map(pattern -> pattern.canBeat(table.getCurrentPattern()))
                    .orElse(false);
                if (!legal) {
                    continue;
                }
            }
            if (hints.stream().noneMatch(existing -> sameCards(existing, single))) {
                hints.add(single);
            }
            if (hints.size() >= 6) {
                break;
            }
        }
        return hints;
    }

    private boolean sameCards(List<DoudizhuCard> left, List<DoudizhuCard> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index).id() != right.get(index).id()) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldShowLabel(DoudizhuCard card, Map<CardRank, Integer> rankCounts) {
        return plugin.isCardHologramLabelsEnabled();
    }

    private boolean shouldShowPrivateLabel(UUID playerId, DoudizhuCard card, Map<CardRank, Integer> rankCounts) {
        if (!plugin.isCardLabelsEnabledFor(playerId)) {
            return false;
        }
        return shouldShowLabel(card, rankCounts);
    }

    private Map<CardRank, Integer> countRanks(List<DoudizhuCard> cards) {
        Map<CardRank, Integer> counts = new EnumMap<>(CardRank.class);
        for (DoudizhuCard card : cards) {
            counts.merge(card.rank(), 1, Integer::sum);
        }
        return counts;
    }

    private ItemStack seatAvatarItem(GameTable table, int seatIndex) {
        UUID seat = placedSeat(table, seatIndex);
        return seatAvatarVisible(table, seat) ? playerHeadItem(seat) : new ItemStack(Material.AIR);
    }

    private Component seatName(GameTable table, int seatIndex) {
        UUID seat = placedSeat(table, seatIndex);
        if (seat == null) {
            return TypewriterTextStyle.warning("空位");
        }
        if (!seatNameVisible(table, seat)) {
            return Component.empty();
        }
        NamedTextColor color = table.isBot(seat) ? NamedTextColor.AQUA : NamedTextColor.WHITE;
        return plugin.playerNameComponent(seat, table.displayName(seat), color)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false);
    }

    private Component seatInfo(GameTable table, int seatIndex) {
        UUID seat = placedSeat(table, seatIndex);
        if (seat == null) {
            return TypewriterTextStyle.meta("座位 " + (seatIndex + 1));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(TypewriterTextStyle.meta("座位 " + (seatIndex + 1)));
        lines.add(TypewriterTextStyle.meta(table.isBot(seat) ? "机器人" : "玩家"));
        if (table.isReady(seat)) {
            lines.add(TypewriterTextStyle.success("已准备"));
        } else if (table.getPhase() == GamePhase.LOBBY) {
            lines.add(TypewriterTextStyle.meta("未准备"));
        }
        if (table.getRole(seat) != null) {
            lines.add(table.getRole(seat) == dev.mumu.doudizhu.game.PlayerRole.LANDLORD
                ? MuzTheme.landlord(table.getRole(seat).displayName())
                : MuzTheme.farmer(table.getRole(seat).displayName()));
        }
        if (table.getPhase() == GamePhase.PLAYING) {
            lines.add(MuzTheme.hotMetric("剩余", String.valueOf(table.getHand(seat).size()), "张"));
        }
        lines.add(TypewriterTextStyle.meta("分数 " + table.getScore(seat)));
        if (seat.equals(table.getCurrentTurn())) {
            lines.add(TypewriterTextStyle.accent("当前操作"));
        }
        return TypewriterTextStyle.joinLines(lines.toArray(Component[]::new));
    }

    private float seatAvatarScale() {
        return plugin.getSmallTextScale() * Math.max(0.5f, plugin.getSeatAvatarScale());
    }

    private float seatNameScale(GameTable table, int seatIndex) {
        UUID seat = placedSeat(table, seatIndex);
        return seat == null
            ? Math.max(0.08f, plugin.getEmptySeatScale())
            : Math.max(0.08f, plugin.getSeatNameScale());
    }

    private float statusAvatarScale() {
        return plugin.getStatusTextScale() * Math.max(0.45f, plugin.getStatusAvatarScale());
    }

    private float seatInfoScale() {
        return Math.max(0.08f, plugin.getSeatInfoScale());
    }

    private float statusNameScale() {
        return Math.max(0.08f, plugin.getStatusNameScale());
    }

    private Location statusAvatarLocation(Location anchor, float yaw) {
        return rotate(
            anchor,
            yaw,
            plugin.getStatusAvatarLateralOffset(),
            plugin.getStatusHeight() + plugin.getStatusAvatarVerticalOffset(),
            plugin.getStatusAvatarDepthOffset()
        );
    }

    private Location statusAvatarNameLocation(GameTable table, Location anchor, float yaw) {
        return rotate(
            anchor,
            yaw,
            plugin.getStatusNameLateralOffset(),
            plugin.getStatusHeight() + plugin.getStatusNameVerticalOffset(),
            plugin.getStatusNameDepthOffset()
        );
    }

    private Location seatAvatarLocation(Location seatBase, float yaw) {
        return offsetFromYaw(
            seatBase,
            yaw,
            plugin.getSeatAvatarLateralOffset(),
            plugin.getChairLabelHeight() + plugin.getSeatAvatarVerticalOffset(),
            plugin.getSeatAvatarDepthOffset()
        );
    }

    private Location seatNameLocation(GameTable table, int seatIndex, Location seatBase, float yaw) {
        UUID seat = placedSeat(table, seatIndex);
        double lateral = seat == null ? plugin.getEmptySeatLateralOffset() : plugin.getSeatNameLateralOffset();
        double vertical = seat == null ? plugin.getEmptySeatVerticalOffset() : plugin.getSeatNameVerticalOffset();
        double depth = seat == null ? plugin.getEmptySeatDepthOffset() : plugin.getSeatNameDepthOffset();
        return offsetFromYaw(
            seatBase,
            yaw,
            lateral,
            plugin.getChairLabelHeight() + vertical,
            depth
        );
    }

    private Location seatInfoLocation(GameTable table, int seatIndex, Location seatBase, float yaw) {
        UUID seat = placedSeat(table, seatIndex);
        if (!seatNameVisible(table, seat)) {
            return seatNameLocation(table, seatIndex, seatBase, yaw);
        }
        double gap = 0.18 + Math.max(0.0f, plugin.getSmallTextScale() - 0.46f) * 0.06;
        return seatNameLocation(table, seatIndex, seatBase, yaw).clone().add(0.0, -gap, 0.0);
    }

    private ItemStack statusAvatarItem(GameTable table) {
        UUID focus = statusFocusPlayer(table);
        return statusAvatarVisible(table, focus) ? playerHeadItem(focus) : new ItemStack(Material.AIR);
    }

    private Component statusAvatarName(GameTable table) {
        UUID focus = statusFocusPlayer(table);
        if (!statusNameVisible(table, focus)) {
            return Component.empty();
        }
        NamedTextColor color = focus != null && table.isBot(focus) ? NamedTextColor.AQUA : NamedTextColor.WHITE;
        return plugin.playerNameComponent(focus, table.displayName(focus), color);
    }

    private boolean seatAvatarVisible(GameTable table, UUID seat) {
        return seat != null && !table.isBot(seat) && plugin.shouldShowPlayerHeadAvatar();
    }

    private boolean seatNameVisible(GameTable table, UUID seat) {
        return seat == null || table.isBot(seat) || plugin.shouldShowPlayerHeadName();
    }

    private boolean statusAvatarVisible(GameTable table, UUID focus) {
        return focus != null && !table.isBot(focus) && plugin.shouldShowPlayerHeadAvatar();
    }

    private boolean statusNameVisible(GameTable table, UUID focus) {
        return focus != null && (table.isBot(focus) || plugin.shouldShowPlayerHeadName());
    }

    private ItemStack playerHeadItem(UUID playerId) {
        if (playerId == null) {
            return new ItemStack(Material.AIR);
        }
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            meta.setOwningPlayer(online);
        } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);
            if (offline.getName() != null && !offline.getName().isBlank()) {
                meta.setOwningPlayer(offline);
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private Component currentTurnStatusBanner(GameTable table) {
        if (table == null || table.getCurrentTurn() == null) {
            return null;
        }
        return switch (table.getPhase()) {
            case BIDDING -> MuzTheme.warm("当前叫分");
            case DOUBLING -> MuzTheme.warm("当前加倍");
            case PLAYING -> MuzTheme.warm("当前出牌");
            case LOBBY -> null;
        };
    }

    private Component currentTurnSeatBadge(GameTable table) {
        if (table == null || table.getCurrentTurn() == null) {
            return null;
        }
        return switch (table.getPhase()) {
            case BIDDING -> TypewriterTextStyle.accent("当前叫分");
            case DOUBLING -> TypewriterTextStyle.accent("当前加倍");
            case PLAYING -> TypewriterTextStyle.accent("当前出牌");
            case LOBBY -> null;
        };
    }

    private UUID statusFocusPlayer(GameTable table) {
        if (table == null) {
            return null;
        }
        if (table.getCurrentTurn() != null) {
            return table.getCurrentTurn();
        }
        if (table.getLandlord() != null) {
            return table.getLandlord();
        }
        return table.getSeats().stream().findFirst().orElse(null);
    }

    private Location offsetFromYaw(Location base, float yaw, double lateral, double vertical, double depth) {
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double x = (-cos * lateral) + (sin * depth);
        double z = (sin * lateral) + (cos * depth);
        return base.clone().add(x, vertical, z);
    }

    private float joinLabelTextScale() {
        return Math.max(0.08f, plugin.getJoinLabelScale());
    }

    private float actionLabelTextScale() {
        return Math.max(0.08f, plugin.getActionLabelScale());
    }

    private UUID placedSeat(GameTable table, int seatIndex) {
        PlacedTable placed = placedTable(table.getName());
        return placed == null ? null : placed.seatAssignments().get(seatIndex);
    }

    private int placedSeatIndex(PlacedTable placed, UUID playerId) {
        return placed.seatAssignments().entrySet().stream()
            .filter(entry -> entry.getValue().equals(playerId))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(-1);
    }

    private Location actionBase(Location anchor, float tableYaw, int seatIndex) {
        // 斗地主三边按钮统一使用同一个离桌半径，前座/侧座两个参数共同决定这个半径。
        double baseDistance = plugin.getButtonDistance();
        double frontDistance = plugin.getButtonFrontBaseDistance()
            + Math.max(0.0, (baseDistance - 1.10) * plugin.getButtonDistanceFactor());
        double sideDistance = plugin.getButtonSideBaseDistance()
            + Math.max(0.0, (baseDistance - 1.10) * plugin.getButtonDistanceFactor());
        double unifiedDistance = (frontDistance + sideDistance) * 0.5;
        double height = plugin.getButtonHeight();
        return switch (seatIndex) {
            case 0 -> rotate(anchor, tableYaw, 0.0, height, -unifiedDistance);
            case 1 -> rotate(anchor, tableYaw, -unifiedDistance, height, 0.0);
            default -> rotate(anchor, tableYaw, unifiedDistance, height, 0.0);
        };
    }

    private void playSelectionSound(Player player, boolean selected) {
        DoudizhuPlugin.SelectionSound sound = plugin.selectionSoundFor(player.getUniqueId());
        if (sound.volume() <= 0.0f) {
            return;
        }
        float pitch = selected ? sound.selectedPitch() : sound.deselectedPitch();
        player.playSound(player.getLocation(), sound.key(), sound.volume(), pitch);
    }

    private Component message(String text, NamedTextColor color) {
        return MuzTheme.named(text, color).decoration(TextDecoration.ITALIC, false);
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

    private Vector handCenter(int seatIndex) {
        double distance = plugin.getHandCenterDistance();
        return switch (seatIndex) {
            case 0 -> new Vector(0.0, plugin.getHandCenterHeight(), -distance);
            case 1 -> new Vector(-distance, plugin.getHandCenterHeight(), 0.0);
            default -> new Vector(distance, plugin.getHandCenterHeight(), 0.0);
        };
    }

    private Vector handStep(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(plugin.getHandSpacing(), 0.0, 0.0);
            case 1 -> new Vector(0.0, 0.0, -plugin.getHandSpacing());
            default -> new Vector(0.0, 0.0, plugin.getHandSpacing());
        };
    }

    private Vector handDepth(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(0.0, 0.0, plugin.getCardDepthOffset());
            case 1 -> new Vector(plugin.getCardDepthOffset(), 0.0, 0.0);
            default -> new Vector(-plugin.getCardDepthOffset(), 0.0, 0.0);
        };
    }

    private Vector towardTableAxis(int seatIndex) {
        Vector center = handCenter(seatIndex);
        return normalizeHorizontal(new Vector(-center.x(), 0.0, -center.z()));
    }

    private static Vector normalizeHorizontal(Vector vector) {
        double length = Math.sqrt(vector.x() * vector.x() + vector.z() * vector.z());
        if (length < 0.0001) {
            return new Vector(0.0, 0.0, 0.0);
        }
        return new Vector(vector.x() / length, 0.0, vector.z() / length);
    }

    private static Vector rotateVector(Vector vector, float yaw) {
        double radians = Math.toRadians(yaw);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double rx = vector.x() * cos - vector.z() * sin;
        double rz = vector.x() * sin + vector.z() * cos;
        return new Vector(rx, vector.y(), rz);
    }

    private static Vector actionStep(int seatIndex) {
        return switch (seatIndex) {
            case 0 -> new Vector(1.0, 0.0, 0.0);
            case 1 -> new Vector(0.0, 0.0, 1.0);
            default -> new Vector(0.0, 0.0, -1.0);
        };
    }

    private static float handCardYaw(float tableYaw, int seatIndex) {
        return switch (seatIndex) {
            case 0 -> tableYaw;
            case 1 -> tableYaw - 90.0f;
            default -> tableYaw + 90.0f;
        };
    }

    private static float publicCardYaw(float tableYaw) {
        return tableYaw + 180.0f;
    }

    private String handSignature(GameTable table, PlacedTable placed, UUID playerId) {
        int seatIndex = placedSeatIndex(placed, playerId);
        String cards = table.getHand(playerId).stream()
            .map(card -> Integer.toString(card.id()))
            .collect(java.util.stream.Collectors.joining(","));
        return table.getPhase().displayName() + "|" + seatIndex + "|" + cards;
    }

    private String publicTrickSignature(GameTable table) {
        StringBuilder builder = new StringBuilder()
            .append(table.getPhase().displayName())
            .append("|trick=")
            .append(table.getCurrentTrickCards().stream().map(card -> Integer.toString(card.id())).collect(java.util.stream.Collectors.joining(",")));
        for (UUID playerId : table.getSeats()) {
            if (table.isBot(playerId)) {
                continue;
            }
            builder.append("|").append(playerId)
                .append(":preview=")
                .append(plugin.isOpponentPreviewEnabledFor(playerId))
                .append(":selected=")
                .append(selectedCards(table, playerId).stream().map(card -> Integer.toString(card.id())).collect(java.util.stream.Collectors.joining(",")));
        }
        return builder.toString();
    }

    private String actionSignature(GameTable table, PlacedTable placed) {
        List<ActionButtonState> phaseStates = switch (table.getPhase()) {
            case BIDDING -> List.of(
                new ActionButtonState("bid", "不叫", ButtonAction.BID_0, -0.96),
                new ActionButtonState("bid", "叫1分", ButtonAction.BID_1, -0.32),
                new ActionButtonState("bid", "叫2分", ButtonAction.BID_2, 0.32),
                new ActionButtonState("bid", "叫3分", ButtonAction.BID_3, 0.96)
            );
            case DOUBLING -> List.of(
                new ActionButtonState("pass", "不加倍", ButtonAction.DOUBLE_NO, -0.40),
                new ActionButtonState("ready", "加倍", ButtonAction.DOUBLE_YES, 0.40)
            );
            case PLAYING -> List.of(
                new ActionButtonState("inspect", "提示", ButtonAction.HINT_PLAY, -0.72),
                new ActionButtonState("pass", "不要", ButtonAction.PASS_TURN, -0.24),
                new ActionButtonState("refresh", "清选", ButtonAction.CLEAR_SELECTION, 0.24)
            );
            case LOBBY -> List.of(
                new ActionButtonState("ready", "准备", ButtonAction.READY, -0.64),
                new ActionButtonState("start", "开始", ButtonAction.START, 0.00),
                new ActionButtonState("leave", "离开", ButtonAction.LEAVE, 0.64)
            );
        };
        StringBuilder builder = new StringBuilder(table.getPhase().displayName());
        for (int seatIndex = 0; seatIndex < 3; seatIndex++) {
            UUID owner = placed.seatAssignments().get(seatIndex);
            builder.append("|seat=").append(seatIndex).append(":owner=").append(owner);
            List<ActionButtonState> activeStates = actionStatesForSeat(table, owner, phaseStates);
            for (ActionButtonState state : activeStates) {
                builder.append(":").append(state.modelId()).append("/").append(state.label()).append("/").append(state.action()).append("/").append(state.offsetX());
            }
        }
        return builder.toString();
    }

    private double[] chairOffsets(int index) {
        double distance = plugin.getChairDistance();
        return switch (index) {
            case 0 -> new double[] {0.0, -distance};
            case 1 -> new double[] {-distance, 0.0};
            default -> new double[] {distance, 0.0};
        };
    }

    private Vector3f privateCardScale(boolean selected, boolean hovered) {
        return privateCardScale(hovered ? 1.0f : 0.0f);
    }

    private Vector3f privateCardScale(float hoverProgress) {
        float easedHover = applyCurve(hoverProgress, plugin.cardHoverAnimationCurve());
        float factor = 1.0f + (plugin.getHoverCardScale() - 1.0f) * easedHover;
        float baseFactor = Math.max(0.01f, plugin.getPrivateCardScale() / DEFAULT_PRIVATE_CARD_RENDER_SCALE);
        return new Vector3f(
            plugin.getPrivateCardWidthScale() * baseFactor * factor,
            plugin.getPrivateCardHeightScale() * baseFactor * factor,
            plugin.getPrivateCardDepthScale() * baseFactor * factor
        );
    }

    private double selectedCardLift(boolean selected, boolean hovered) {
        return animatedCardLift(selected ? 1.0f : 0.0f, hovered ? 1.0f : 0.0f);
    }

    private double animatedCardLift(float selectedProgress, float hoverProgress) {
        return plugin.getSelectedCardLift() * applyCurve(selectedProgress, plugin.cardHoverAnimationCurve())
            + plugin.getHoverCardLift() * applyCurve(hoverProgress, plugin.cardHoverAnimationCurve());
    }

    private Vector3f publicCardScale(float scale) {
        float baseFactor = Math.max(0.01f, scale / DEFAULT_PUBLIC_CARD_RENDER_SCALE);
        return new Vector3f(
            plugin.getPublicCardWidthScale() * baseFactor,
            plugin.getPublicCardHeightScale() * baseFactor,
            plugin.getPublicCardDepthScale() * baseFactor
        );
    }

    private Transformation cardTransformation(Vector3f scale, float lift) {
        // Translation + scale only.
        // Never introduce roll/pitch/yaw here, otherwise hovered or clicked cards start tilting and snapping back.
        return new Transformation(
            new Vector3f(0.0f, lift, 0.0f),
            new AxisAngle4f(),
            scale,
            new AxisAngle4f()
        );
    }

    private float advanceAnimation(Map<UUID, Map<Integer, Float>> animationMap, UUID playerId, int cardId, boolean active, float riseStep, float fallStep) {
        Map<Integer, Float> cardMap = animationMap.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        float current = cardMap.getOrDefault(cardId, 0.0f);
        float next;
        if (active) {
            next = Math.min(1.0f, current + riseStep);
        } else {
            next = Math.max(0.0f, current - fallStep);
        }
        if (next <= 0.0001f) {
            cardMap.remove(cardId);
            if (cardMap.isEmpty()) {
                animationMap.remove(playerId);
            }
            return 0.0f;
        }
        cardMap.put(cardId, next);
        return next;
    }

    private float cardAnimationStep() {
        return 1.0f / Math.max(1, plugin.getCardHoverInterpolationTicks());
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

    private void applyCardGlow(ItemDisplay display, UUID playerId, boolean selected, boolean hovered) {
        if (hovered && plugin.isHoverGlowEnabled()) {
            display.setGlowing(true);
            display.setGlowColorOverride(plugin.previewGlowColorFor(playerId));
            return;
        }
        if (selected && plugin.isSelectedGlowEnabled()) {
            display.setGlowing(true);
            display.setGlowColorOverride(plugin.selectionGlowColorFor(playerId));
            return;
        }
        display.setGlowing(false);
        display.setGlowColorOverride(null);
    }

    private Location previewLocation(PlacedTable placed, UUID ownerId, double centered, double rowOffset, double height) {
        if (ownerId == null) {
            return rotate(
                placed.anchor(),
                placed.yaw(),
                centered * plugin.getPublicTrickSpacing(),
                height,
                rowOffset * plugin.getPublicPreviewRowDepthSpacing()
            );
        }
        Vector depth = previewFacingAxis(placed, ownerId);
        Vector lateral = previewLateralAxis(depth);
        double depthOffset = rowOffset * plugin.getPublicPreviewRowDepthSpacing();
        return placed.anchor().clone().add(
            lateral.x() * centered * plugin.getPublicTrickSpacing() + depth.x() * depthOffset,
            height,
            lateral.z() * centered * plugin.getPublicTrickSpacing() + depth.z() * depthOffset
        );
    }

    private Location privateCardLabelLocation(Location cardBaseLocation, int seatIndex, double lift) {
        Vector lateralAxis = normalizeHorizontal(handStep(seatIndex));
        Vector depthAxis = towardTableAxis(seatIndex);
        return cardBaseLocation.clone().add(
            lateralAxis.x() * plugin.getCardLabelLateralOffset() + depthAxis.x() * plugin.getCardLabelDepthOffset(),
            plugin.getCardLabelHeight() + 0.08 + lift,
            lateralAxis.z() * plugin.getCardLabelLateralOffset() + depthAxis.z() * plugin.getCardLabelDepthOffset()
        );
    }

    private float previewYaw(PlacedTable placed, UUID ownerId, int ownerSeatIndex) {
        if (ownerId == null) {
            return publicCardYaw(placed.yaw());
        }
        Vector facing = previewFacingAxis(placed, ownerId);
        if (Math.abs(facing.x()) < 0.0001 && Math.abs(facing.z()) < 0.0001) {
            return ownerSeatIndex < 0 ? publicCardYaw(placed.yaw()) : handCardYaw(placed.yaw(), ownerSeatIndex);
        }
        float viewerYaw = (float) Math.toDegrees(Math.atan2(-facing.x(), facing.z()));
        return normalizeYaw(viewerYaw + 180.0f);
    }

    private Vector previewFacingAxis(PlacedTable placed, UUID ownerId) {
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null) {
            return new Vector(0.0, 0.0, 1.0);
        }
        Location anchor = placed.anchor();
        Location viewerLocation = owner.getLocation();
        return normalizeHorizontal(new Vector(viewerLocation.getX() - anchor.getX(), 0.0, viewerLocation.getZ() - anchor.getZ()));
    }

    private Vector previewLateralAxis(Vector depthAxis) {
        return normalizeHorizontal(new Vector(-depthAxis.z(), 0.0, depthAxis.x()));
    }

    private static float normalizeYaw(float yaw) {
        float normalized = yaw % 360.0f;
        if (normalized > 180.0f) {
            normalized -= 360.0f;
        } else if (normalized <= -180.0f) {
            normalized += 360.0f;
        }
        return normalized;
    }

    private void updateHoverState(GameTable table, Player viewer) {
        PlacedTable placed = placedTable(table.getName());
        if (placed == null) {
            clearHover(viewer.getUniqueId());
            return;
        }
        Entity target = viewer.getTargetEntity(6);
        Integer hovered = null;
        CardBinding binding = target == null ? null : cardBindings.get(target.getUniqueId());
        if (binding != null && binding.ownerId().equals(viewer.getUniqueId()) && binding.tableName().equalsIgnoreCase(table.getName())) {
            hovered = binding.cardId();
        }
        Integer previous = hoveredCardIds.get(viewer.getUniqueId());
        // IMPORTANT REGRESSION GUARD:
        // Hover state must clear immediately when the pointer leaves a card.
        // Candidate/grace retention caused stale hover residue and made cards look stuck in an old hover frame.
        hoverCandidateCardIds.remove(viewer.getUniqueId());
        hoverCandidateTicksByViewer.remove(viewer.getUniqueId());
        hoverGraceTicksByViewer.remove(viewer.getUniqueId());
        if ((previous == null && hovered == null) || (previous != null && previous.equals(hovered))) {
            return;
        }
        if (hovered == null) {
            hoveredCardIds.remove(viewer.getUniqueId());
        } else {
            hoveredCardIds.put(viewer.getUniqueId(), hovered);
        }
    }

    private void clearHover(UUID playerId) {
        hoveredCardIds.remove(playerId);
        hoverCandidateCardIds.remove(playerId);
        hoverCandidateTicksByViewer.remove(playerId);
        hoverGraceTicksByViewer.remove(playerId);
        hoverProgressByPlayer.remove(playerId);
    }

    private void updateActionHoverState(Player viewer) {
        Entity target = viewer.getTargetEntity(6);
        UUID displayId = null;
        if (target != null) {
            displayId = actionDisplayByBinding.get(target.getUniqueId());
        }
        UUID previous = hoveredActionDisplayByViewer.get(viewer.getUniqueId());
        if (Objects.equals(previous, displayId)) {
            if (displayId != null) {
                actionHoverGraceTicksByViewer.remove(viewer.getUniqueId());
            }
            return;
        }
        if (displayId == null) {
            // IMPORTANT REGRESSION GUARD:
            // Button hover should also clear immediately, otherwise lift/glow lingers after the cursor leaves.
            actionHoverGraceTicksByViewer.remove(viewer.getUniqueId());
            hoveredActionDisplayByViewer.remove(viewer.getUniqueId());
            return;
        }
        actionHoverGraceTicksByViewer.remove(viewer.getUniqueId());
        hoveredActionDisplayByViewer.put(viewer.getUniqueId(), displayId);
    }

    private void clearActionHover(UUID viewerId) {
        hoveredActionDisplayByViewer.remove(viewerId);
        actionHoverGraceTicksByViewer.remove(viewerId);
    }

    private void clearActionHoverNow(UUID viewerId) {
        UUID displayId = hoveredActionDisplayByViewer.remove(viewerId);
        actionHoverGraceTicksByViewer.remove(viewerId);
        if (displayId == null) {
            return;
        }
        actionHoverProgressByDisplay.remove(displayId);
        Entity entity = Bukkit.getEntity(displayId);
        if (entity instanceof ItemDisplay display) {
            configureButtonAnimation(display);
            display.setTransformation(buttonTransformation(plugin.getButtonScale(), 0.0f));
            display.setGlowing(false);
            display.setGlowColorOverride(null);
        }
    }

    private float actionHitboxWidth(ActionBinding binding) {
        float base = (float) plugin.getButtonHitboxWidth();
        if (binding == null) {
            return base;
        }
        if (isDoublingAction(binding.action())) {
            return Math.max(base, base * 1.45f);
        }
        return base;
    }

    private float actionHitboxHeight(ActionBinding binding) {
        float base = (float) plugin.getButtonHitboxHeight();
        if (binding == null) {
            return base;
        }
        if (isDoublingAction(binding.action())) {
            return Math.max(base, base * 1.55f);
        }
        return base;
    }

    private boolean isDoublingAction(ButtonAction action) {
        return action == ButtonAction.DOUBLE_NO
            || action == ButtonAction.DOUBLE_YES;
    }

    private void rememberActionVisual(UUID bindingId, UUID displayId) {
        actionDisplayByBinding.put(bindingId, displayId);
        actionDisplayIds.add(displayId);
    }

    private void updateActionHoverAnimations() {
        float riseStep = 1.0f / Math.max(1, plugin.getButtonHoverInterpolationTicks());
        float fallStep = Math.min(1.0f, riseStep * 1.8f);
        Set<UUID> activeDisplays = new LinkedHashSet<>(hoveredActionDisplayByViewer.values());
        for (UUID displayId : new ArrayList<>(actionDisplayIds)) {
            Entity entity = Bukkit.getEntity(displayId);
            if (!(entity instanceof ItemDisplay display)) {
                actionHoverProgressByDisplay.remove(displayId);
                continue;
            }
            float current = actionHoverProgressByDisplay.getOrDefault(displayId, 0.0f);
            float next = activeDisplays.contains(displayId)
                ? Math.min(1.0f, current + riseStep)
                : Math.max(0.0f, current - fallStep);
            if (Math.abs(next - current) < 0.0001f) {
                if (next <= 0.0001f) {
                    actionHoverProgressByDisplay.remove(displayId);
                }
                continue;
            }
            if (next <= 0.0001f) {
                actionHoverProgressByDisplay.remove(displayId);
            } else {
                actionHoverProgressByDisplay.put(displayId, next);
            }
            float eased = applyCurve(next, plugin.buttonHoverAnimationCurve());
            float scale = plugin.getButtonScale() * (1.0f + (plugin.getHoverButtonScale() - 1.0f) * eased);
            float lift = (float) (plugin.getHoverButtonLift() * eased);
            configureButtonAnimation(display);
            display.setTransformation(buttonTransformation(scale, lift));
            UUID hoverViewer = hoveredActionDisplayByViewer.entrySet().stream()
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
        // Buttons must stay stable during hover.
        // Only the static configured Z-roll is allowed; do not add animated rotation/rebound based on hover progress.
        return new Transformation(
            new Vector3f(0.0f, lift, 0.0f),
            new AxisAngle4f((float) Math.toRadians(plugin.getButtonRollDegrees()), 0.0f, 0.0f, 1.0f),
            new Vector3f(scale, scale, scale),
            new AxisAngle4f()
        );
    }

    private void clearActionMappings(List<UUID> ids) {
        for (UUID id : ids) {
            actionBindings.remove(id);
            UUID displayId = actionDisplayByBinding.remove(id);
            if (displayId != null) {
                actionDisplayIds.remove(displayId);
                actionHoverProgressByDisplay.remove(displayId);
                hoveredActionDisplayByViewer.entrySet().removeIf(entry -> displayId.equals(entry.getValue()));
                actionHoverGraceTicksByViewer.keySet().removeIf(viewerId -> !hoveredActionDisplayByViewer.containsKey(viewerId));
            }
            actionDisplayIds.remove(id);
            actionHoverProgressByDisplay.remove(id);
            hoveredActionDisplayByViewer.entrySet().removeIf(entry -> id.equals(entry.getValue()));
            actionHoverGraceTicksByViewer.keySet().removeIf(viewerId -> !hoveredActionDisplayByViewer.containsKey(viewerId));
        }
    }

    private static float chairYawOffset(int index) {
        return switch (index) {
            case 0 -> 0.0f;
            case 1 -> -90.0f;
            default -> 90.0f;
        };
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private record Vector(double x, double y, double z) {
    }

    private record ButtonDefinition(String modelId, String label, ButtonAction action, double offsetX, double offsetY, double offsetZ) {
    }

    private record ActionBinding(String tableName, ButtonAction action, Integer seatIndex) {
    }

    private record CardBinding(String tableName, UUID ownerId, int cardId) {
    }

    private record ActionButtonState(String modelId, String label, ButtonAction action, double offsetX) {
    }

    private record ActionWidgetSpec(
        ItemStack iconItem,
        Location iconLocation,
        float yaw,
        Component labelText,
        Location labelLocation,
        Location interactionLocation,
        ActionBinding binding,
        UUID owner,
        boolean joinVisibility
    ) {
    }

    private record HandCardVisual(UUID cardDisplayId, List<UUID> interactionIds, UUID labelId) {
    }

    private record PreviewCardSpec(ItemStack item, Location location, float yaw, Component label, UUID ownerId) {
    }

    private record PlacedTable(
        String tableName,
        Location anchor,
        float yaw,
        UUID ownerId,
        String ownerName,
        List<UUID> staticEntities,
        List<UUID> craftEngineVisualEntities,
        List<BlockRestore> blockRestores,
        Map<Integer, UUID> seatAssignments,
        List<Location> seatBaseLocations,
        Map<UUID, List<UUID>> privateEntitiesByPlayer,
        Map<UUID, Map<Integer, HandCardVisual>> privateVisualsByPlayer,
        Map<UUID, List<UUID>> backsideEntitiesByPlayer,
        Map<UUID, Map<Integer, UUID>> backsideVisualsByPlayer,
        Map<UUID, List<UUID>> viewerTrickEntitiesByPlayer,
        List<UUID> publicEntities,
        UUID statusDisplayId,
        UUID playDetailDisplayId,
        UUID statusAvatarDisplayId,
        UUID statusAvatarNameDisplayId,
        List<UUID> seatAvatarDisplayIds,
        List<UUID> seatNameDisplayIds,
        List<UUID> seatInfoDisplayIds,
        List<UUID> actionEntities
    ) {
        private PlacedTable withStatusDisplayId(UUID newStatusDisplayId) {
            return new PlacedTable(
                tableName,
                anchor,
                yaw,
                ownerId,
                ownerName,
                staticEntities,
                craftEngineVisualEntities,
                blockRestores,
                seatAssignments,
                seatBaseLocations,
                privateEntitiesByPlayer,
                privateVisualsByPlayer,
                backsideEntitiesByPlayer,
                backsideVisualsByPlayer,
                viewerTrickEntitiesByPlayer,
                publicEntities,
                newStatusDisplayId,
                playDetailDisplayId,
                statusAvatarDisplayId,
                statusAvatarNameDisplayId,
                seatAvatarDisplayIds,
                seatNameDisplayIds,
                seatInfoDisplayIds,
                actionEntities
            );
        }

        private PlacedTable withPlayDetailDisplayId(UUID newPlayDetailDisplayId) {
            return new PlacedTable(
                tableName,
                anchor,
                yaw,
                ownerId,
                ownerName,
                staticEntities,
                craftEngineVisualEntities,
                blockRestores,
                seatAssignments,
                seatBaseLocations,
                privateEntitiesByPlayer,
                privateVisualsByPlayer,
                backsideEntitiesByPlayer,
                backsideVisualsByPlayer,
                viewerTrickEntitiesByPlayer,
                publicEntities,
                statusDisplayId,
                newPlayDetailDisplayId,
                statusAvatarDisplayId,
                statusAvatarNameDisplayId,
                seatAvatarDisplayIds,
                seatNameDisplayIds,
                seatInfoDisplayIds,
                actionEntities
            );
        }

        private PlacedTable withStatusAvatarDisplayId(UUID newStatusAvatarDisplayId) {
            return new PlacedTable(
                tableName,
                anchor,
                yaw,
                ownerId,
                ownerName,
                staticEntities,
                craftEngineVisualEntities,
                blockRestores,
                seatAssignments,
                seatBaseLocations,
                privateEntitiesByPlayer,
                privateVisualsByPlayer,
                backsideEntitiesByPlayer,
                backsideVisualsByPlayer,
                viewerTrickEntitiesByPlayer,
                publicEntities,
                statusDisplayId,
                playDetailDisplayId,
                newStatusAvatarDisplayId,
                statusAvatarNameDisplayId,
                seatAvatarDisplayIds,
                seatNameDisplayIds,
                seatInfoDisplayIds,
                actionEntities
            );
        }

        private PlacedTable withStatusAvatarNameDisplayId(UUID newStatusAvatarNameDisplayId) {
            return new PlacedTable(
                tableName,
                anchor,
                yaw,
                ownerId,
                ownerName,
                staticEntities,
                craftEngineVisualEntities,
                blockRestores,
                seatAssignments,
                seatBaseLocations,
                privateEntitiesByPlayer,
                privateVisualsByPlayer,
                backsideEntitiesByPlayer,
                backsideVisualsByPlayer,
                viewerTrickEntitiesByPlayer,
                publicEntities,
                statusDisplayId,
                playDetailDisplayId,
                statusAvatarDisplayId,
                newStatusAvatarNameDisplayId,
                seatAvatarDisplayIds,
                seatNameDisplayIds,
                seatInfoDisplayIds,
                actionEntities
            );
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

    private record TablePlacement(UUID entityId, BlockRestore blockRestore, boolean craftEngineEntity) {
        private static TablePlacement furniture(UUID entityId, boolean craftEngineEntity) {
            return new TablePlacement(entityId, null, craftEngineEntity);
        }

        private static TablePlacement block(BlockRestore blockRestore) {
            return new TablePlacement(null, blockRestore, false);
        }

        private static TablePlacement none() {
            return new TablePlacement(null, null, false);
        }
    }

    private record BlockRestore(BlockState originalState) {
    }

    private enum ButtonAction {
        JOIN,
        READY,
        START,
        STATUS,
        LEAVE,
        PLAY_SELECTED,
        PASS_TURN,
        HINT_PLAY,
        CLEAR_SELECTION,
        DOUBLE_NO,
        DOUBLE_YES,
        OPEN_SETTINGS,
        BID_0,
        BID_1,
        BID_2,
        BID_3
    }
}

