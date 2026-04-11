package dev.codex.doudizhu.zhajinhua;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.assets.PackAssets;
import dev.codex.doudizhu.model.DoudizhuCard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
    private static final float TABLE_SCALE = 2.25f;
    private static final float CHAIR_SCALE = 1.35f;
    private static final float CARD_SCALE = 0.50f;
    private static final float TEXT_SCALE = 0.46f;
    private static final float STATUS_SCALE = 0.72f;
    private static final float BUTTON_SCALE = 0.42f;

    private final DoudizhuPlugin plugin;
    private final Map<String, PlacedTable> placedTables = new LinkedHashMap<>();
    private final Map<UUID, JoinBinding> joinBindings = new LinkedHashMap<>();
    private final Map<UUID, SeatActionBinding> seatActionBindings = new LinkedHashMap<>();
    private final Map<UUID, UUID> buttonDisplayByBinding = new LinkedHashMap<>();
    private final Map<UUID, Location> buttonBaseLocationByDisplay = new LinkedHashMap<>();
    private final Map<UUID, UUID> hoveredDisplayByViewer = new LinkedHashMap<>();

    public ZjhPhysicalTableManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public ZjhTable placeNewTable(org.bukkit.entity.Player owner, String name, int maxPlayers) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            throw new IllegalArgumentException("这个德州扑克牌桌已经有实体桌面了。");
        }
        ZjhTable table = plugin.getZjhManager().getTable(name);
        if (table == null) {
            table = plugin.getZjhManager().createTable(name, maxPlayers);
        }
        Location anchor = owner.getLocation().getBlock().getLocation().add(0.5, plugin.getTableSpawnOffsetY(), 0.5);
        placedTables.put(key, spawnTable(table, anchor, snappedYaw(owner.getLocation().getYaw())));
        refresh(table);
        return table;
    }

    public boolean isPlaced(String tableName) {
        return placedTables.containsKey(normalize(tableName));
    }

    public void refresh(ZjhTable table) {
        if (table == null) {
            return;
        }
        PlacedTable placed = placedTables.get(normalize(table.getName()));
        if (placed == null) {
            return;
        }
        if (placed.statusId != null) {
            Entity entity = Bukkit.getEntity(placed.statusId);
            if (entity instanceof TextDisplay display) {
                display.text(message(
                    "德州扑克\n"
                        + table.getStreet().displayName() + "\n"
                        + table.potBreakdownText() + "\n"
                        + "当前下注 " + table.getCurrentBet() + "\n"
                        + table.lastActionText(),
                    NamedTextColor.GREEN
                ));
            }
        }
        int sequential = 0;
        for (int index = 0; index < placed.seatLabelIds.size(); index++) {
            Entity entity = Bukkit.getEntity(placed.seatLabelIds.get(index));
            if (entity instanceof TextDisplay display) {
                UUID seat = placed.seatAssignments.get(index);
                if (seat == null && sequential < table.getSeats().size()) {
                    UUID candidate = table.getSeats().get(sequential++);
                    if (!placed.seatAssignments.containsValue(candidate)) {
                        placed.seatAssignments.put(index, candidate);
                        seat = candidate;
                    }
                }
                String text = seat == null
                    ? "座位" + (index + 1) + "\n空位"
                    : seatText(table, index, seat);
                display.text(message(text, seat == null ? NamedTextColor.GRAY : NamedTextColor.GOLD));
            }
        }
        updateJoinButtons(table, placed);
        refreshSeatActions(table, placed);
        refreshCommunityCards(table, placed);
        refreshHoleCards(table, placed);
        refreshDealerMarker(table, placed);
    }

    public boolean handleInteraction(org.bukkit.entity.Player player, Entity entity) {
        JoinBinding binding = joinBindings.get(entity.getUniqueId());
        if (binding == null) {
            SeatActionBinding action = seatActionBindings.get(entity.getUniqueId());
            if (action == null) {
                return false;
            }
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
        if (plugin.getZjhManager().getTableOf(player) != null || plugin.getTableManager().getTableOf(player) != null) {
            player.sendActionBar(message("你已经在别的牌桌里了。", NamedTextColor.RED));
            return true;
        }
        if (binding.seatIndex < table.getSeats().size()) {
            player.sendActionBar(message("这个座位已经有人了。", NamedTextColor.RED));
            return true;
        }
        plugin.getZjhManager().joinTable(player, table.getName());
        PlacedTable placed = placedTables.get(normalize(table.getName()));
        if (placed != null) {
            placed.seatAssignments.put(binding.seatIndex, player.getUniqueId());
            refresh(table);
        }
        player.sendActionBar(message("你加入了德州扑克牌桌。", NamedTextColor.GREEN));
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
        plugin.getZjhManager().unregisterTable(tableName);
    }

    public void shutdown() {
        for (PlacedTable placed : placedTables.values()) {
            cleanupPlacedTable(placed);
        }
        placedTables.clear();
    }

    public void tick() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            updateButtonHover(viewer);
        }
    }

    private PlacedTable spawnTable(ZjhTable table, Location anchor, float yaw) {
        List<UUID> entities = new ArrayList<>();
        List<UUID> seatLabels = new ArrayList<>();
        Map<Integer, UUID> seatAssignments = new LinkedHashMap<>();
        List<UUID> seatActionEntities = new ArrayList<>();
        ItemDisplay tableDisplay = spawnFurniture(anchor.clone().add(0.0, 0.55, 0.0), tableItem(), TABLE_SCALE);
        entities.add(tableDisplay.getUniqueId());
        int maxPlayers = table.getMaxPlayers();
        List<double[]> offsets = seatOffsets(maxPlayers);
        for (int index = 0; index < offsets.size(); index++) {
            double[] offset = offsets.get(index);
            Location chairLocation = rotate(anchor, yaw, offset[0], 0.20, offset[1]);
            ItemDisplay chair = spawnFurniture(chairLocation, chairItem(), CHAIR_SCALE);
            chair.setRotation(chairYaw(offset), 0.0f);
            entities.add(chair.getUniqueId());
            TextDisplay label = spawnText(chairLocation.clone().add(0.0, 1.35, 0.0), message("座位" + (index + 1), NamedTextColor.GRAY), TEXT_SCALE);
            entities.add(label.getUniqueId());
            seatLabels.add(label.getUniqueId());

            Location joinLocation = chairLocation.clone().add(0.0, 0.85, 0.0);
            ItemDisplay joinButton = spawnFurniture(joinLocation, joinItem(), BUTTON_SCALE);
            entities.add(joinButton.getUniqueId());
            Interaction interaction = spawnInteraction(joinLocation.clone().add(0.0, 0.05, 0.0), 0.32f, 0.32f);
            entities.add(interaction.getUniqueId());
            joinBindings.put(joinButton.getUniqueId(), new JoinBinding(table.getName(), index));
            joinBindings.put(interaction.getUniqueId(), new JoinBinding(table.getName(), index));
            rememberButtonVisual(joinButton.getUniqueId(), joinButton.getUniqueId(), joinLocation);
            rememberButtonVisual(interaction.getUniqueId(), joinButton.getUniqueId(), joinLocation);
        }
        TextDisplay status = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getStatusHeight(), 0.0),
            message("德州扑克", NamedTextColor.GREEN),
            STATUS_SCALE
        );
        status.setLineWidth(250);
        entities.add(status.getUniqueId());
        return new PlacedTable(entities, seatLabels, status.getUniqueId(), seatAssignments, seatActionEntities, new ArrayList<>(), anchor.clone(), yaw);
    }

    private List<double[]> seatOffsets(int maxPlayers) {
        return switch (Math.max(2, Math.min(10, maxPlayers))) {
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
    }

    private ItemDisplay spawnFurniture(Location location, ItemStack item, float scale) {
        return location.getWorld().spawn(location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            protect(spawned);
        });
    }

    private TextDisplay spawnText(Location location, Component text, float scale) {
        return location.getWorld().spawn(location, TextDisplay.class, spawned -> {
            spawned.text(text);
            spawned.setBillboard(Display.Billboard.CENTER);
            spawned.setDefaultBackground(false);
            spawned.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            protect(spawned);
        });
    }

    private Interaction spawnInteraction(Location location, float width, float height) {
        return location.getWorld().spawn(location, Interaction.class, spawned -> {
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
    }

    private ItemStack tableItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model(plugin.getTableItemModelId(), PackAssets.furnitureModel(plugin, "table_visual")));
        meta.displayName(message(plugin.getTableDisplayName(), NamedTextColor.GOLD));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack chairItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(model(plugin.getChairItemModelId(), PackAssets.furnitureModel(plugin, "seat_chair")));
        meta.displayName(message(plugin.getChairDisplayName(), NamedTextColor.RED));
        item.setItemMeta(meta);
        return item;
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
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(ownerId)) {
                viewer.showEntity(plugin, entity);
            } else {
                viewer.hideEntity(plugin, entity);
            }
        }
    }

    private void hideFromOwner(UUID ownerId, Entity entity) {
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
        Entity target = viewer.getTargetEntity(6);
        UUID displayId = target == null ? null : buttonDisplayByBinding.get(target.getUniqueId());
        UUID previous = hoveredDisplayByViewer.get(viewer.getUniqueId());
        if (Objects.equals(previous, displayId)) {
            return;
        }
        if (previous != null) {
            restoreButtonVisual(previous);
        }
        if (displayId == null) {
            hoveredDisplayByViewer.remove(viewer.getUniqueId());
            return;
        }
        hoveredDisplayByViewer.put(viewer.getUniqueId(), displayId);
        applyButtonHover(displayId);
    }

    private void applyButtonHover(UUID displayId) {
        Entity entity = Bukkit.getEntity(displayId);
        Location base = buttonBaseLocationByDisplay.get(displayId);
        if (!(entity instanceof ItemDisplay display) || base == null) {
            return;
        }
        display.teleport(base.clone().add(0.0, plugin.getHoverButtonLift(), 0.0));
        display.setTransformation(new Transformation(
            new Vector3f(),
            new AxisAngle4f(),
            new Vector3f(BUTTON_SCALE * plugin.getHoverButtonScale(), BUTTON_SCALE * plugin.getHoverButtonScale(), BUTTON_SCALE * plugin.getHoverButtonScale()),
            new AxisAngle4f()
        ));
    }

    private void restoreButtonVisual(UUID displayId) {
        Entity entity = Bukkit.getEntity(displayId);
        Location base = buttonBaseLocationByDisplay.get(displayId);
        if (!(entity instanceof ItemDisplay display) || base == null) {
            return;
        }
        display.teleport(base);
        display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(BUTTON_SCALE, BUTTON_SCALE, BUTTON_SCALE), new AxisAngle4f()));
    }

    private void clearEntities(List<UUID> entities) {
        for (UUID entityId : entities) {
            joinBindings.remove(entityId);
            seatActionBindings.remove(entityId);
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    private void cleanupPlacedTable(PlacedTable placed) {
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
    }

    private void refreshCommunityCards(ZjhTable table, PlacedTable placed) {
        clearEntities(placed.communityEntityIds);
        placed.communityEntityIds.clear();
        TextDisplay streetBanner = spawnText(
            rotate(placed.anchor, placed.yaw, 0.0, 1.54, 0.0),
            message(table.getStreet().displayName() + " | " + table.potBreakdownText(), NamedTextColor.AQUA),
            STATUS_SCALE
        );
        placed.communityEntityIds.add(streetBanner.getUniqueId());
        if (table.communityCards().isEmpty()) {
            return;
        }
        double spacing = 0.34;
        double start = -((table.communityCards().size() - 1) * 0.5);
        for (int index = 0; index < table.communityCards().size(); index++) {
            Location location = rotate(placed.anchor, placed.yaw, (start + index) * spacing, 1.18, 0.0);
            ItemDisplay display = spawnCard(location, cardItem(table.communityCards().get(index)), CARD_SCALE, placed.yaw);
            placed.communityEntityIds.add(display.getUniqueId());
            TextDisplay label = spawnText(location.clone().add(0.0, 0.22, 0.0), message(table.communityCards().get(index).displayLabel(), NamedTextColor.YELLOW), TEXT_SCALE);
            placed.communityEntityIds.add(label.getUniqueId());
        }
    }

    private void refreshHoleCards(ZjhTable table, PlacedTable placed) {
        for (UUID playerId : new ArrayList<>(placed.privateHoleEntities.keySet())) {
            clearEntities(placed.privateHoleEntities.remove(playerId));
        }
        for (UUID playerId : new ArrayList<>(placed.publicBackEntities.keySet())) {
            clearEntities(placed.publicBackEntities.remove(playerId));
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
                double shift = (start + index) * 0.30;
                Location cardLocation = rotate(placed.anchor, placed.yaw, offset[0] * 0.68 + lateralAxisX(offset) * shift, 1.18, offset[1] * 0.68 + lateralAxisZ(offset) * shift);
                ItemDisplay front = spawnCard(cardLocation, cardItem(hole.get(index)), CARD_SCALE, yaw);
                privateIds.add(front.getUniqueId());
                applyPrivateVisibility(occupant, front);
                TextDisplay frontLabel = spawnText(cardLocation.clone().add(0.0, 0.20, 0.0), message(hole.get(index).displayLabel(), NamedTextColor.WHITE), TEXT_SCALE);
                privateIds.add(frontLabel.getUniqueId());
                applyPrivateVisibility(occupant, frontLabel);

                ItemDisplay back = spawnCard(cardLocation, backCardItem(), CARD_SCALE, yaw);
                publicIds.add(back.getUniqueId());
                hideFromOwner(occupant, back);
                TextDisplay mark = spawnText(cardLocation.clone().add(0.0, 0.20, 0.0), message("?", NamedTextColor.GRAY), TEXT_SCALE);
                publicIds.add(mark.getUniqueId());
                hideFromOwner(occupant, mark);
            }
            placed.privateHoleEntities.put(occupant, privateIds);
            placed.publicBackEntities.put(occupant, publicIds);
        }
    }

    private void updateJoinButtons(ZjhTable table, PlacedTable placed) {
        int seatCount = placed.seatLabelIds.size();
        int sequential = 0;
        for (int seatIndex = 0; seatIndex < seatCount; seatIndex++) {
            UUID occupant = placed.seatAssignments.get(seatIndex);
            if (occupant == null && sequential < table.getSeats().size()) {
                UUID candidate = table.getSeats().get(sequential++);
                if (!placed.seatAssignments.containsValue(candidate)) {
                    placed.seatAssignments.put(seatIndex, candidate);
                    occupant = candidate;
                }
            }
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
            Location base = rotate(placed.anchor, placed.yaw, offset[0], 0.88, offset[1]);
            if (table.getPhase() == ZjhPhase.LOBBY) {
                spawnSeatAction(placed, table.getName(), occupant, Action.READY, null, "准备", base.clone().add(-0.38, 0.0, 0.0), readyItem());
                spawnSeatAction(placed, table.getName(), null, Action.START, null, "开始", base.clone(), startItem());
                spawnSeatAction(placed, table.getName(), occupant, Action.LEAVE, null, "离开", base.clone().add(0.38, 0.0, 0.0), leaveItem());
            } else if (table.getPhase() == ZjhPhase.PLAYING && Objects.equals(occupant, table.getCurrentTurn()) && Bukkit.getPlayer(occupant) != null) {
                double step = 0.34;
                if (table.toCall(occupant) == 0) {
                    spawnSeatAction(placed, table.getName(), occupant, Action.CHECK, null, "过牌", base.clone().add(-step * 2.5, 0.0, 0.0), checkItem());
                } else {
                    spawnSeatAction(placed, table.getName(), occupant, Action.CALL, null, "跟注", base.clone().add(-step * 2.5, 0.0, 0.0), callItem(table.toCall(occupant)));
                }
                List<Integer> raises = table.raiseOptions(occupant);
                for (int index = 0; index < raises.size(); index++) {
                    int raiseTo = raises.get(index);
                    double offsetX = -step + index * step;
                    spawnSeatAction(placed, table.getName(), occupant, Action.RAISE, raiseTo, "加到" + raiseTo, base.clone().add(offsetX, 0.0, 0.0), raiseItem(raiseTo));
                }
                spawnSeatAction(placed, table.getName(), occupant, Action.FOLD, null, "弃牌", base.clone().add(step * 2.0, 0.0, 0.0), foldItem());
                spawnSeatAction(placed, table.getName(), occupant, Action.ALL_IN, null, "全下", base.clone().add(step * 3.0, 0.0, 0.0), allInItem());
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
        Location markerLocation = rotate(placed.anchor, placed.yaw, offset[0] * 0.86, 1.56, offset[1] * 0.86);
        ItemDisplay marker = spawnFurniture(markerLocation, dealerItem(), 0.32f);
        TextDisplay label = spawnText(markerLocation.clone().add(0.0, 0.16, 0.0), message("BTN", NamedTextColor.GOLD), TEXT_SCALE);
        placed.dealerEntityIds.add(marker.getUniqueId());
        placed.dealerEntityIds.add(label.getUniqueId());
    }

    private void spawnSeatAction(PlacedTable placed, String tableName, UUID ownerId, Action action, Integer amount, String labelText, Location location, ItemStack icon) {
        ItemDisplay button = spawnFurniture(location, icon, BUTTON_SCALE);
        Interaction interaction = spawnInteraction(location.clone().add(0.0, 0.05, 0.0), 0.28f, 0.28f);
        TextDisplay label = spawnText(location.clone().add(0.0, 0.18, 0.0), message(labelText, NamedTextColor.WHITE), TEXT_SCALE);
        placed.seatActionEntityIds.add(button.getUniqueId());
        placed.seatActionEntityIds.add(interaction.getUniqueId());
        placed.seatActionEntityIds.add(label.getUniqueId());
        seatActionBindings.put(button.getUniqueId(), new SeatActionBinding(tableName, ownerId, action, amount));
        seatActionBindings.put(interaction.getUniqueId(), new SeatActionBinding(tableName, ownerId, action, amount));
        rememberButtonVisual(button.getUniqueId(), button.getUniqueId(), location);
        rememberButtonVisual(interaction.getUniqueId(), button.getUniqueId(), location);
        if (ownerId != null) {
            applyPrivateVisibility(ownerId, button);
            applyPrivateVisibility(ownerId, interaction);
            applyPrivateVisibility(ownerId, label);
        }
    }

    private String seatText(ZjhTable table, int seatIndex, UUID seat) {
        StringBuilder builder = new StringBuilder("座位").append(seatIndex + 1).append("\n").append(table.displayName(seat));
        String position = table.positionLabel(seat);
        if (!position.isEmpty()) {
            builder.append("\n").append(position);
        }
        builder.append("\n筹码 ").append(table.chipStack(seat));
        if (Objects.equals(seat, table.getSmallBlindPlayer())) {
            builder.append("\n小盲");
        }
        if (Objects.equals(seat, table.getBigBlindPlayer())) {
            builder.append("\n大盲");
        }
        if (table.isFolded(seat)) {
            builder.append("\n已弃牌");
        } else if (table.isAllIn(seat)) {
            builder.append("\nALL-IN");
        } else if (Objects.equals(table.getCurrentTurn(), seat)) {
            builder.append("\n当前行动");
        }
        return builder.toString();
    }

    private ItemDisplay spawnCard(Location location, ItemStack item, float scale, float yaw) {
        ItemDisplay display = location.getWorld().spawn(location, ItemDisplay.class, spawned -> {
            spawned.setItemStack(item);
            spawned.setBillboard(Display.Billboard.FIXED);
            spawned.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(scale, scale, scale), new AxisAngle4f()));
            protect(spawned);
        });
        display.setRotation(yaw, 0.0f);
        return display;
    }

    private ItemStack cardItem(DoudizhuCard card) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(PackAssets.cardModel(plugin, card));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack backCardItem() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setItemModel(PackAssets.backModel(plugin));
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
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
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
        private final List<UUID> seatLabelIds;
        private final UUID statusId;
        private final Map<Integer, UUID> seatAssignments;
        private final List<UUID> seatActionEntityIds;
        private final List<UUID> dealerEntityIds;
        private final Map<UUID, List<UUID>> privateHoleEntities;
        private final Map<UUID, List<UUID>> publicBackEntities;
        private final List<UUID> communityEntityIds;
        private final Location anchor;
        private final float yaw;

        private PlacedTable(List<UUID> entities, List<UUID> seatLabelIds, UUID statusId, Map<Integer, UUID> seatAssignments, List<UUID> seatActionEntityIds, List<UUID> dealerEntityIds, Location anchor, float yaw) {
            this.entities = entities;
            this.seatLabelIds = seatLabelIds;
            this.statusId = statusId;
            this.seatAssignments = seatAssignments;
            this.seatActionEntityIds = seatActionEntityIds;
            this.dealerEntityIds = dealerEntityIds;
            this.privateHoleEntities = new LinkedHashMap<>();
            this.publicBackEntities = new LinkedHashMap<>();
            this.communityEntityIds = new ArrayList<>();
            this.anchor = anchor;
            this.yaw = yaw;
        }
    }

    private record JoinBinding(String tableName, int seatIndex) {
    }

    private record SeatActionBinding(String tableName, UUID ownerId, Action action, Integer amount) {
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
