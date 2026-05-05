package dev.mumu.doudizhu.mahjong;

import dev.mumu.doudizhu.DoudizhuPlugin;
import dev.mumu.doudizhu.compat.VersionCompat;
import dev.mumu.doudizhu.ui.MuzTheme;
import dev.mumu.doudizhu.ui.TypewriterTextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class MahjongTableManager implements Listener {
    private static final String PROTECTED_ENTITY_TAG = "muz_mahjong_protected";
    private final DoudizhuPlugin plugin;
    private final Map<String, MahjongTableSession> tables = new ConcurrentHashMap<>();
    private final Map<UUID, SeatBinding> seatBindings = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerTableIds = new ConcurrentHashMap<>();
    private volatile MahjongLayoutConfig layoutConfig;

    public MahjongTableManager(DoudizhuPlugin plugin, MahjongLayoutConfig layoutConfig) {
        this.plugin = plugin;
        this.layoutConfig = layoutConfig;
    }

    public void reloadLayout(MahjongLayoutConfig layoutConfig) {
        this.layoutConfig = layoutConfig;
        for (MahjongTableSession table : tables.values()) {
            table.applyLayout(layoutConfig);
            rerender(table);
        }
    }

    public int tableCount() {
        return tables.size();
    }

    public Collection<MahjongTableSession> tables() {
        return List.copyOf(tables.values());
    }

    public String nextAvailableId() {
        return nextId();
    }

    public boolean containsPlayer(UUID playerId) {
        return playerId != null && playerTableIds.containsKey(playerId);
    }

    public MahjongTableSession createTable(Player owner, String requestedId) {
        return createTable(owner, requestedId, plugin.defaultTableAnchor(owner));
    }

    public MahjongTableSession createTable(Player owner, String requestedId, Location center) {
        if (owner == null) {
            throw new IllegalArgumentException("创建麻将桌需要有效玩家。" );
        }
        if (center == null || center.getWorld() == null) {
            throw new IllegalArgumentException("这里暂时还不能创建麻将桌。" );
        }
        String id = normalizeId(requestedId == null || requestedId.isBlank() ? nextId() : requestedId);
        if (tables.containsKey(id)) {
            throw new IllegalArgumentException("麻将桌 " + id + " 已存在。");
        }
        MahjongTableSession session = new MahjongTableSession(id, center.clone(), owner.getUniqueId(), owner.getName(), System.currentTimeMillis(), layoutConfig);
        tables.put(id, session);
        render(session);
        plugin.persistMahjongTable(id, center, owner.getUniqueId(), owner.getName());
        return session;
    }

    public MahjongTableSession restoreTable(String tableId, Location center, java.util.UUID ownerId, String ownerName) {
        if (center == null || center.getWorld() == null) {
            return null;
        }
        String id = normalizeId(tableId == null || tableId.isBlank() ? nextId() : tableId);
        if (tables.containsKey(id)) {
            return tables.get(id);
        }
        MahjongTableSession session = new MahjongTableSession(id, center.clone(), ownerId, ownerName, System.currentTimeMillis(), layoutConfig);
        tables.put(id, session);
        render(session);
        return session;
    }

    public MahjongTableSession removeTable(String tableId) {
        MahjongTableSession removed;
        if (tableId == null || tableId.isBlank()) {
            removed = nearestTable(null);
            if (removed != null) {
                tables.remove(removed.id());
            }
        } else {
            removed = tables.remove(normalizeId(tableId));
        }
        if (removed != null) {
            for (UUID playerId : List.copyOf(removed.occupants().values())) {
                playerTableIds.remove(playerId);
            }
            cleanup(removed);
            plugin.deletePersistedTable("MAHJONG", removed.id());
        }
        return removed;
    }

    public MahjongTableSession table(String tableId) {
        return tableId == null ? null : tables.get(normalizeId(tableId));
    }

    public Location tableAnchor(String tableId) {
        MahjongTableSession table = table(tableId);
        return table == null ? null : table.center();
    }

    public Location previewTableCenter(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return null;
        }
        MahjongLayoutConfig layout = layoutConfig;
        return anchor.clone().add(
            layout.displayCenterXOffset(),
            layout.displayCenterYOffset() + layout.tableVisualYOffset(),
            layout.displayCenterZOffset()
        );
    }

    public List<Location> previewSeatBases(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return List.of();
        }
        MahjongLayoutConfig layout = layoutConfig;
        Location displayCenter = anchor.clone().add(layout.displayCenterXOffset(), layout.displayCenterYOffset(), layout.displayCenterZOffset());
        double distance = Math.max(0.6, layout.seatDistanceFromHandBase());
        List<Location> seats = new ArrayList<>();
        for (MahjongTableSession.Seat seat : MahjongTableSession.Seat.values()) {
            seats.add(displayCenter.clone().add(seat.xFactor() * distance, layout.seatBaseYOffset(), seat.zFactor() * distance));
        }
        return seats;
    }

    public String placementObstructionReason(Location anchor) {
        if (anchor == null || anchor.getWorld() == null) {
            return "这里暂时还不能放麻将桌。";
        }
        MahjongLayoutConfig layout = layoutConfig;
        Location previewDisplayCenter = anchor.clone().add(layout.displayCenterXOffset(), layout.displayCenterYOffset(), layout.displayCenterZOffset());
        for (MahjongTableSession existing : tables.values()) {
            Location existingCenter = displayCenter(existing);
            if (existingCenter.getWorld() == null || !existingCenter.getWorld().equals(previewDisplayCenter.getWorld())) {
                continue;
            }
            if (existingCenter.distanceSquared(previewDisplayCenter) < 3.24) {
                return "离另一张麻将桌太近了，稍微挪开一点再放。";
            }
        }
        return null;
    }

    public boolean canRemoveTable(Player player, String tableId) {
        MahjongTableSession table = table(tableId);
        if (player == null || table == null) {
            return false;
        }
        return player.hasPermission("muz.admin") || table.ownerId().equals(player.getUniqueId());
    }

    public String removeDeniedReason(Player player, String tableId) {
        MahjongTableSession table = table(tableId);
        if (table == null) {
            return "找不到这张麻将桌。";
        }
        if (player != null && player.hasPermission("muz.admin")) {
            return "";
        }
        String owner = table.ownerName() == null || table.ownerName().isBlank() ? "原放置者" : table.ownerName();
        return "这张麻将桌是 " + owner + " 放的，你不能拆。";
    }

    public String targetedTable(Player player, double maxDistance) {
        if (player == null) {
            return null;
        }
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector direction = eye.getDirection().normalize();
        String bestTable = null;
        double bestDistance = Double.MAX_VALUE;
        for (MahjongTableSession table : tables.values()) {
            double distance = sightDistance(eye, direction, displayCenter(table), 1.15, maxDistance);
            if (distance >= 0.0 && distance < bestDistance) {
                bestDistance = distance;
                bestTable = table.id();
            }
        }
        return bestTable;
    }

    public boolean isProtectedEntity(UUID entityId) {
        Entity entity = Bukkit.getEntity(entityId);
        while (entity != null) {
            if (entity.getScoreboardTags().contains(PROTECTED_ENTITY_TAG)) {
                return true;
            }
            for (MahjongTableSession table : tables.values()) {
                if (table.visualEntityIds().contains(entity.getUniqueId())) {
                    return true;
                }
            }
            entity = entity.getVehicle();
        }
        return false;
    }

    public MahjongTableSession nearestTable(Location location) {
        if (tables.isEmpty()) {
            return null;
        }
        if (location == null || location.getWorld() == null) {
            return tables.values().stream().sorted(Comparator.comparing(MahjongTableSession::id)).findFirst().orElse(null);
        }
        MahjongTableSession nearest = null;
        double best = Double.MAX_VALUE;
        for (MahjongTableSession table : tables.values()) {
            Location center = displayCenter(table);
            if (center.getWorld() == null || !center.getWorld().equals(location.getWorld())) {
                continue;
            }
            double distance = center.distanceSquared(location);
            if (distance < best) {
                best = distance;
                nearest = table;
            }
        }
        return nearest;
    }

    public List<Component> openLobbyLines(Player player) {
        List<Component> lines = new ArrayList<>();
        lines.add(MuzTheme.banner("MUZ Mahjong", "内嵌运行时", MuzTheme.muted("第一版骨架已启用")));
        lines.add(MuzTheme.body("/muz mahjong create [桌号]"));
        lines.add(MuzTheme.body("/muz mahjong list"));
        lines.add(MuzTheme.body("/muz mahjong state [桌号]"));
        lines.add(MuzTheme.body("/muz mahjong remove <桌号>"));
        lines.add(MuzTheme.muted("当前桌数 · " + tableCount() + " · " + layoutConfig.summary()));
        return lines;
    }

    public List<Component> listLines() {
        if (tables.isEmpty()) {
            return List.of(MuzTheme.muted("当前还没有内嵌麻将桌。"));
        }
        List<Component> lines = new ArrayList<>();
        tables.values().stream()
            .sorted(Comparator.comparing(MahjongTableSession::id))
            .forEach(table -> {
                Location center = displayCenter(table);
                lines.add(MuzTheme.body("- " + table.id()
                    + " · " + table.ownerName()
                    + " · " + safeWorld(center)
                    + " (" + center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ() + ")"));
            });
        return lines;
    }

    public List<Component> stateLines(MahjongTableSession table) {
        if (table == null) {
            return List.of(MuzTheme.danger("没找到目标麻将桌。"));
        }
        Location center = displayCenter(table);
        List<Component> lines = new ArrayList<>();
        lines.add(MuzTheme.banner("麻将桌 " + table.id(), table.ownerName(), MuzTheme.muted("内嵌运行时")));
        lines.add(MuzTheme.body("位置 · " + safeWorld(center) + " (" + center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ() + ")"));
        lines.add(MuzTheme.body("布局 · " + table.layoutConfig().summary()));
        lines.add(MuzTheme.body("座位 · " + seatStateSummary(table)));
        lines.add(MuzTheme.body("创建时间戳 · " + table.createdAtMillis()));
        return lines;
    }

    public Map<String, String> statusMap() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("tables", String.valueOf(tableCount()));
        status.put("layout", layoutConfig.summary());
        return status;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSeatInteract(PlayerInteractAtEntityEvent event) {
        SeatBinding binding = seatBindings.get(event.getRightClicked().getUniqueId());
        if (binding == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        MahjongTableSession table = tables.get(binding.tableId());
        if (table == null) {
            seatBindings.remove(event.getRightClicked().getUniqueId());
            return;
        }
        String currentTableId = playerTableIds.get(player.getUniqueId());
        MahjongTableSession.Seat currentSeat = table.seatOf(player.getUniqueId());
        if (currentSeat == binding.seat()) {
            if (player.isSneaking()) {
                table.leave(player.getUniqueId());
                playerTableIds.remove(player.getUniqueId());
                rerender(table);
                player.sendMessage(MuzTheme.warning("你已从 " + table.id() + " 的" + binding.seat().label() + "离座。"));
                return;
            }
            boolean ready = table.toggleReady(player.getUniqueId());
            rerender(table);
            player.sendMessage(ready
                ? MuzTheme.success("你已在 " + table.id() + " 的" + binding.seat().label() + "准备。")
                : MuzTheme.warning("你已取消 " + table.id() + " 的" + binding.seat().label() + "准备。"));
            if (table.occupants().size() == MahjongTableSession.Seat.values().length && table.readyCount() == MahjongTableSession.Seat.values().length) {
                broadcast(table, MuzTheme.success("麻将桌 " + table.id() + " 四人已就位且全部准备；下一步接完整发牌流程。"));
            }
            return;
        }
        if (currentTableId != null && !currentTableId.equalsIgnoreCase(table.id())) {
            player.sendMessage(MuzTheme.danger("你已经在另一张麻将桌入座了：" + currentTableId));
            return;
        }
        UUID occupiedBy = table.occupants().get(binding.seat());
        if (occupiedBy != null && !occupiedBy.equals(player.getUniqueId())) {
            player.sendMessage(MuzTheme.danger(binding.seat().label() + " 已有人。"));
            return;
        }
        if (currentSeat != null) {
            table.leave(player.getUniqueId());
        }
        if (!table.sit(binding.seat(), player.getUniqueId(), player.getName())) {
            player.sendMessage(MuzTheme.danger("这个座位暂时没坐进去。"));
            return;
        }
        playerTableIds.put(player.getUniqueId(), table.id());
        rerender(table);
        player.sendMessage(MuzTheme.success("你已坐到麻将桌 " + table.id() + " 的" + binding.seat().label() + "。"));
    }

    public void shutdown() {
        for (MahjongTableSession table : List.copyOf(tables.values())) {
            cleanup(table);
        }
        tables.clear();
        seatBindings.clear();
        playerTableIds.clear();
    }

    private String nextId() {
        int index = 1;
        while (tables.containsKey(String.valueOf(index))) {
            index++;
        }
        return String.valueOf(index);
    }

    private void render(MahjongTableSession table) {
        cleanup(table);
        Location center = displayCenter(table);
        MahjongLayoutConfig layout = table.layoutConfig();
        // [MUZ-DEBUG] 详细日志：查看渲染时使用的配置值
        plugin.getLogger().info("[MUZ-DEBUG] render() called for table=" + table.id());
        plugin.getLogger().info("[MUZ-DEBUG]   layoutConfig: tableVisualYOffset=" + layout.tableVisualYOffset()
            + " displayCenterXOffset=" + layout.displayCenterXOffset()
            + " displayCenterYOffset=" + layout.displayCenterYOffset()
            + " displayCenterZOffset=" + layout.displayCenterZOffset());
        plugin.getLogger().info("[MUZ-DEBUG]   table.center=" + table.center());
        plugin.getLogger().info("[MUZ-DEBUG]   displayCenter=" + center);
        Location tableVisualLocation = center.clone().add(0.0, layout.tableVisualYOffset(), 0.0);
        plugin.getLogger().info("[MUZ-DEBUG]   tableVisualLocation=" + tableVisualLocation);
        ItemDisplay tableDisplay = spawnItemDisplay(tableVisualLocation, tableVisualItem(), 2.25f);
        remember(table, tableDisplay);
        TextDisplay centerText = spawnText(center.clone().add(0.0, layout.centerLabelYOffset(), 0.0), buildCenterText(table), 0.55f);
        remember(table, centerText);
        for (MahjongTableSession.Seat seat : MahjongTableSession.Seat.values()) {
            Location seatLocation = seatLocation(table, seat);
            ItemDisplay chair = spawnItemDisplay(seatLocation.clone().add(0.0, layout.seatBaseYOffset(), 0.0), chairVisualItem(), 1.35f, seatYaw(seat));
            remember(table, chair);
            Location labelLocation = applySeatOffset(seatLocation, seat, sideSeatHorizontalOffset(seat, layout), layout.seatAnchorYOffset(), layout.seatLabelDepthOffset());
            TextDisplay label = spawnText(labelLocation, buildSeatText(table, seat), (float) layout.seatActionLabelScale());
            remember(table, label);
            Location interactionLocation = applySeatOffset(seatLocation, seat, sideSeatHorizontalOffset(seat, layout), layout.seatActionLabelYOffset(), layout.seatLabelDepthOffset());
            Interaction interaction = spawnInteraction(interactionLocation, (float) layout.seatActionHitboxWidth(), (float) layout.seatActionHitboxHeight());
            seatBindings.put(interaction.getUniqueId(), new SeatBinding(table.id(), seat));
            remember(table, interaction);
        }
    }

    private void rerender(MahjongTableSession table) {
        if (table != null) {
            render(table);
        }
    }

    private void cleanup(MahjongTableSession table) {
        if (table == null) {
            return;
        }
        for (UUID entityId : List.copyOf(table.visualEntityIds())) {
            seatBindings.remove(entityId);
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
        table.clearVisuals();
    }

    private void remember(MahjongTableSession table, Entity entity) {
        if (table == null || entity == null) {
            return;
        }
        table.visualEntityIds().add(entity.getUniqueId());
    }

    private void broadcast(MahjongTableSession table, Component message) {
        if (table == null || message == null) {
            return;
        }
        for (UUID playerId : table.occupants().values()) {
            Player online = Bukkit.getPlayer(playerId);
            if (online != null) {
                online.sendMessage(message);
            }
        }
    }

    private Location displayCenter(MahjongTableSession table) {
        MahjongLayoutConfig layout = table.layoutConfig();
        return table.center().clone().add(layout.displayCenterXOffset(), layout.displayCenterYOffset(), layout.displayCenterZOffset());
    }

    private Location seatLocation(MahjongTableSession table, MahjongTableSession.Seat seat) {
        Location center = displayCenter(table);
        double distance = Math.max(0.6, table.layoutConfig().seatDistanceFromHandBase());
        return center.clone().add(seat.xFactor() * distance, 0.0, seat.zFactor() * distance);
    }

    private float seatYaw(MahjongTableSession.Seat seat) {
        return switch (seat) {
            case EAST -> 0.0f;
            case SOUTH -> 90.0f;
            case WEST -> 180.0f;
            case NORTH -> -90.0f;
        };
    }

    private double sideSeatHorizontalOffset(MahjongTableSession.Seat seat, MahjongLayoutConfig layout) {
        return seat == MahjongTableSession.Seat.SOUTH || seat == MahjongTableSession.Seat.NORTH
            ? layout.seatSideActionHorizontalOffset()
            : 0.0;
    }

    private Location applySeatOffset(Location base, MahjongTableSession.Seat seat, double lateral, double vertical, double towardCenter) {
        double forwardX = -seat.xFactor();
        double forwardZ = -seat.zFactor();
        double lateralX = -forwardZ;
        double lateralZ = forwardX;
        return base.clone().add(
            lateralX * lateral + forwardX * towardCenter,
            vertical,
            lateralZ * lateral + forwardZ * towardCenter
        );
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

    private Component buildCenterText(MahjongTableSession table) {
        return MuzTheme.row(
            MuzTheme.accent("麻将桌 " + table.id()),
            List.of(MuzTheme.muted("已入座 " + table.occupants().size() + "/4"), MuzTheme.muted("已准备 " + table.readyCount() + "/4"), MuzTheme.muted("点击入座，同位再点准备，潜行点离座"))
        );
    }

    private Component buildSeatText(MahjongTableSession table, MahjongTableSession.Seat seat) {
        String occupant = table.occupantNames().get(seat);
        return MuzTheme.row(
            MuzTheme.warm(seat.label()),
            List.of(
                occupant == null || occupant.isBlank()
                    ? MuzTheme.muted("点击入座")
                    : (table.isReady(seat) ? MuzTheme.success(occupant + " · 已准备") : MuzTheme.warning(occupant + " · 未准备"))
            )
        );
    }

    private String seatStateSummary(MahjongTableSession table) {
        List<String> parts = new ArrayList<>();
        for (MahjongTableSession.Seat seat : MahjongTableSession.Seat.values()) {
            String occupant = table.occupantNames().get(seat);
            parts.add(seat.label() + ":" + (occupant == null ? "空" : occupant));
        }
        return String.join(" · ", parts);
    }

    private ItemDisplay spawnItemDisplay(Location location, ItemStack item, float scale) {
        return spawnItemDisplay(location, item, scale, 0.0f);
    }

    private ItemDisplay spawnItemDisplay(Location location, ItemStack item, float scale, float yaw) {
        return VersionCompat.spawnEntity(location.getWorld(), location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            spawned.setRotation(yaw, 0.0f);
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

    private Interaction spawnInteraction(Location location, float width, float height) {
        return VersionCompat.spawnEntity(location.getWorld(), location, Interaction.class, spawned -> {
            spawned.setInteractionWidth(Math.max(0.1f, width));
            spawned.setInteractionHeight(Math.max(0.1f, height));
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

    private ItemStack tableVisualItem() {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.TABLE);
        if (configured != null && !configured.getType().isAir()) {
            return configured.clone();
        }
        ItemStack item = new ItemStack(Material.CARTOGRAPHY_TABLE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MuzTheme.accent("麻将桌"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack chairVisualItem() {
        ItemStack configured = plugin.getConfiguredFurnitureItem(DoudizhuPlugin.FurnitureType.CHAIR);
        if (configured != null && !configured.getType().isAir()) {
            return configured.clone();
        }
        ItemStack item = new ItemStack(Material.OAK_STAIRS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MuzTheme.warm("麻将座位"));
        item.setItemMeta(meta);
        return item;
    }

    private String normalizeId(String id) {
        return id.trim().toUpperCase(Locale.ROOT);
    }

    private String safeWorld(Location location) {
        return location.getWorld() == null ? "unknown" : location.getWorld().getName();
    }

    private record SeatBinding(String tableId, MahjongTableSession.Seat seat) {
    }
}
