package dev.codex.doudizhu.zhajinhua;

import dev.codex.doudizhu.DoudizhuPlugin;
import dev.codex.doudizhu.assets.PackAssets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final float TEXT_SCALE = 0.46f;
    private static final float STATUS_SCALE = 0.72f;
    private static final float BUTTON_SCALE = 0.42f;

    private final DoudizhuPlugin plugin;
    private final Map<String, PlacedTable> placedTables = new LinkedHashMap<>();
    private final Map<UUID, JoinBinding> joinBindings = new LinkedHashMap<>();

    public ZjhPhysicalTableManager(DoudizhuPlugin plugin) {
        this.plugin = plugin;
    }

    public ZjhTable placeNewTable(org.bukkit.entity.Player owner, String name, int maxPlayers) {
        String key = normalize(name);
        if (placedTables.containsKey(key)) {
            throw new IllegalArgumentException("这个炸金花牌桌已经有实体桌面了。");
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
                display.text(message("炸金花 | " + table.getName() + "\n人数 " + table.getSeats().size() + "/" + table.getMaxPlayers() + "\n底池 " + table.getPot() + " | 明注 " + table.getCurrentBet(), NamedTextColor.GREEN));
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
                    : "座位" + (index + 1) + "\n" + table.displayName(seat) + "\n筹码 " + table.chipStack(seat);
                display.text(message(text, seat == null ? NamedTextColor.GRAY : NamedTextColor.GOLD));
            }
        }
        updateJoinButtons(table, placed);
    }

    public boolean handleInteraction(org.bukkit.entity.Player player, Entity entity) {
        JoinBinding binding = joinBindings.get(entity.getUniqueId());
        if (binding == null) {
            return false;
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
        player.sendActionBar(message("你加入了炸金花牌桌。", NamedTextColor.GREEN));
        return true;
    }

    public boolean handleAttack(org.bukkit.entity.Player player, Entity entity) {
        return false;
    }

    public void removeTable(String tableName) {
        PlacedTable placed = placedTables.remove(normalize(tableName));
        if (placed == null) {
            throw new IllegalArgumentException("这个炸金花牌桌没有实体桌面。");
        }
        clearEntities(placed.entities);
        plugin.getZjhManager().unregisterTable(tableName);
    }

    public void shutdown() {
        for (PlacedTable placed : placedTables.values()) {
            clearEntities(placed.entities);
        }
        placedTables.clear();
    }

    public void tick() {
    }

    private PlacedTable spawnTable(ZjhTable table, Location anchor, float yaw) {
        List<UUID> entities = new ArrayList<>();
        List<UUID> seatLabels = new ArrayList<>();
        Map<Integer, UUID> seatAssignments = new LinkedHashMap<>();
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
        }
        TextDisplay status = spawnText(
            rotate(anchor, yaw, 0.0, plugin.getStatusHeight(), 0.0),
            message("炸金花 | " + table.getName(), NamedTextColor.GREEN),
            STATUS_SCALE
        );
        status.setLineWidth(250);
        entities.add(status.getUniqueId());
        return new PlacedTable(entities, seatLabels, status.getUniqueId(), seatAssignments);
    }

    private List<double[]> seatOffsets(int maxPlayers) {
        return switch (Math.max(2, Math.min(6, maxPlayers))) {
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
            default -> List.of(
                new double[] {-1.15, -2.25},
                new double[] {1.15, -2.25},
                new double[] {-2.25, 0.0},
                new double[] {-1.15, 2.25},
                new double[] {1.15, 2.25},
                new double[] {2.25, 0.0}
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

    private NamespacedKey model(String raw, NamespacedKey fallback) {
        NamespacedKey parsed = raw == null ? null : NamespacedKey.fromString(raw.trim());
        return parsed == null ? fallback : parsed;
    }

    private void clearEntities(List<UUID> entities) {
        for (UUID entityId : entities) {
            joinBindings.remove(entityId);
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
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

    private float chairYaw(double[] offset) {
        return (float) Math.toDegrees(Math.atan2(offset[0], -offset[1]));
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

        private PlacedTable(List<UUID> entities, List<UUID> seatLabelIds, UUID statusId, Map<Integer, UUID> seatAssignments) {
            this.entities = entities;
            this.seatLabelIds = seatLabelIds;
            this.statusId = statusId;
            this.seatAssignments = seatAssignments;
        }
    }

    private record JoinBinding(String tableName, int seatIndex) {
    }
}
